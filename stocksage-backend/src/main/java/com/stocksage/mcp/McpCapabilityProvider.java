package com.stocksage.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.capability.CapabilityException;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.SyncMcpToolCallback;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lazily initializes configured MCP clients and snapshots only locally approved tools.
 *
 * <p>Spring AI creates transports with {@code initialized=false}; failures during initialize,
 * tools/list or call are contained here so the application can fall back to local tools.</p>
 */
@Slf4j
@Component
public class McpCapabilityProvider {

    private static final int MAX_TOOL_PAGES = 20;

    private final ObjectProvider<List<McpSyncClient>> mcpClients;
    private final McpProperties properties;
    private final StockSageMcpToolFilter toolFilter;
    private final ObjectMapper objectMapper;
    private final Object discoveryLock = new Object();

    private volatile Catalog catalog = Catalog.empty("MCP discovery has not run");
    private volatile Instant lastAttempt = Instant.EPOCH;

    public McpCapabilityProvider(ObjectProvider<List<McpSyncClient>> mcpClients,
                                 McpProperties properties,
                                 StockSageMcpToolFilter toolFilter,
                                 ObjectMapper objectMapper) {
        this.mcpClients = mcpClients;
        this.properties = properties;
        this.toolFilter = toolFilter;
        this.objectMapper = objectMapper;
    }

    public boolean isNewsSearchAvailable() {
        return properties.hasNewsSearchTarget()
                && currentCatalog().callbacks().containsKey(properties.newsSearchKey());
    }

    public String callNewsSearch(Map<String, Object> arguments) {
        ToolCallback callback = currentCatalog().callbacks().get(properties.newsSearchKey());
        if (callback == null) {
            throw new CapabilityException(CapabilityException.Reason.UNAVAILABLE,
                    "Approved MCP news search tool is unavailable");
        }
        try {
            return callback.call(objectMapper.writeValueAsString(arguments));
        } catch (CapabilityException error) {
            throw error;
        } catch (Exception error) {
            throw new CapabilityException(CapabilityException.Reason.FAILED,
                    "MCP tool call failed: " + safeMessage(error), error);
        }
    }

    public Status status() {
        Catalog current = currentCatalog();
        return new Status(
                properties.isEnabled(),
                properties.hasNewsSearchTarget()
                        && current.callbacks().containsKey(properties.newsSearchKey()),
                current.callbacks().size(),
                current.protocolVersions(),
                current.error(),
                current.checkedAt()
        );
    }

    /**
     * Returns the last known process-local state without triggering discovery
     * or any external MCP call. Admin polling must remain read-only.
     */
    public Status statusSnapshot() {
        Catalog current = catalog;
        return new Status(
                properties.isEnabled(),
                properties.hasNewsSearchTarget()
                        && current.callbacks().containsKey(properties.newsSearchKey()),
                current.callbacks().size(),
                current.protocolVersions(),
                current.error(),
                current.checkedAt()
        );
    }

    private Catalog currentCatalog() {
        if (!properties.isEnabled()) {
            return Catalog.empty("MCP is disabled");
        }
        Catalog current = catalog;
        if (current.callbacks().containsKey(properties.newsSearchKey())) {
            return current;
        }
        Instant retryAfter = lastAttempt.plusSeconds(Math.max(1, properties.getDiscoveryRetrySeconds()));
        if (Instant.now().isBefore(retryAfter)) {
            return current;
        }
        synchronized (discoveryLock) {
            retryAfter = lastAttempt.plusSeconds(Math.max(1, properties.getDiscoveryRetrySeconds()));
            if (Instant.now().isBefore(retryAfter)) {
                return catalog;
            }
            lastAttempt = Instant.now();
            catalog = discover();
            return catalog;
        }
    }

    private Catalog discover() {
        List<McpSyncClient> clients = mcpClients.getIfAvailable(List::of);
        if (clients == null || clients.isEmpty()) {
            return Catalog.empty("No MCP client connections are configured");
        }

        Map<String, ToolCallback> callbacks = new LinkedHashMap<>();
        Map<String, String> protocolVersions = new LinkedHashMap<>();
        String lastError = "";
        for (McpSyncClient client : clients) {
            try {
                McpSchema.InitializeResult initialized = client.isInitialized()
                        ? client.getCurrentInitializationResult()
                        : client.initialize();
                String serverName = serverName(client, initialized);
                protocolVersions.put(serverName, initialized == null ? "unknown" : initialized.protocolVersion());
                discoverClientTools(client, serverName, callbacks);
            } catch (Exception error) {
                lastError = safeMessage(error);
                log.warn("MCP discovery failed; approved Skills will use local fallback: {}", lastError);
            }
        }
        if (!properties.hasNewsSearchTarget()) {
            lastError = "MCP news search target is not configured";
        } else if (!callbacks.containsKey(properties.newsSearchKey()) && lastError.isBlank()) {
            lastError = "Configured MCP news search tool was not discovered or was denied";
        }
        return new Catalog(Map.copyOf(callbacks), Map.copyOf(protocolVersions), lastError, Instant.now());
    }

    private void discoverClientTools(McpSyncClient client,
                                     String serverName,
                                     Map<String, ToolCallback> callbacks) {
        String cursor = null;
        for (int page = 0; page < MAX_TOOL_PAGES; page++) {
            McpSchema.ListToolsResult result = cursor == null
                    ? client.listTools()
                    : client.listTools(cursor);
            if (result == null || result.tools() == null) {
                return;
            }
            for (McpSchema.Tool tool : result.tools()) {
                if (!toolFilter.isAllowed(serverName, tool.name())) {
                    continue;
                }
                String key = serverName + "/" + tool.name();
                ToolCallback callback = SyncMcpToolCallback.builder()
                        .mcpClient(client)
                        .tool(tool)
                        .prefixedToolName(tool.name())
                        .build();
                ToolCallback previous = callbacks.putIfAbsent(key, callback);
                if (previous != null) {
                    throw new IllegalStateException("Duplicate approved MCP tool: " + key);
                }
            }
            cursor = result.nextCursor();
            if (cursor == null || cursor.isBlank()) {
                return;
            }
        }
        throw new IllegalStateException("MCP tools/list exceeded page limit for server: " + serverName);
    }

    private String serverName(McpSyncClient client, McpSchema.InitializeResult initialized) {
        McpSchema.Implementation serverInfo = initialized == null ? client.getServerInfo() : initialized.serverInfo();
        if (serverInfo == null || serverInfo.name() == null || serverInfo.name().isBlank()) {
            throw new IllegalStateException("MCP server did not provide a stable name");
        }
        return serverInfo.name().trim();
    }

    private String safeMessage(Throwable error) {
        String message = error == null ? "unknown error" : error.getMessage();
        if (message == null || message.isBlank()) {
            return error == null ? "unknown error" : error.getClass().getSimpleName();
        }
        return message.length() <= 300 ? message : message.substring(0, 300) + "...";
    }

    private record Catalog(
            Map<String, ToolCallback> callbacks,
            Map<String, String> protocolVersions,
            String error,
            Instant checkedAt
    ) {
        private static Catalog empty(String error) {
            return new Catalog(Map.of(), Map.of(), error, Instant.now());
        }
    }

    public record Status(
            boolean enabled,
            boolean newsSearchAvailable,
            int approvedToolCount,
            Map<String, String> protocolVersions,
            String error,
            Instant checkedAt
    ) {
    }
}
