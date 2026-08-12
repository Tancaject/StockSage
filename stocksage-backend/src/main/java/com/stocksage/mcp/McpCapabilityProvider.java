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
 * 延迟初始化 MCP 客户端，并只缓存本地批准的远端工具。
 *
 * <p>上游 {@link McpNewsSearchCapabilityAdapter} 通过本类检查和调用 NEWS 能力；下游是 Spring AI
 * 创建的 {@link McpSyncClient}。客户端初始为未握手状态，本类负责 initialize、分页 tools/list
 * 与 ToolCallback 适配。任一远端失败都被收敛为可降级状态，让 Skill 转入本地工具。</p>
 *
 * <p>远端发现结果还必须经过 {@link StockSageMcpToolFilter} 的精确 allowlist；发现本身不能扩权。</p>
 */
@Slf4j
@Component
public class McpCapabilityProvider {

    /** 防止异常服务器用无尽 nextCursor 拖住发现线程。 */
    private static final int MAX_TOOL_PAGES = 20;

    /** Spring 配置创建的同步 MCP 客户端；没有配置时允许为空。 */
    private final ObjectProvider<List<McpSyncClient>> mcpClients;
    /** StockSage 自有的启用开关、allowlist 和 NEWS 参数映射。 */
    private final McpProperties properties;
    /** 对每个远端 server/tool 执行本地准入检查。 */
    private final StockSageMcpToolFilter toolFilter;
    /** 把稳定本地参数序列化为 MCP 工具 JSON 入参。 */
    private final ObjectMapper objectMapper;
    /** 串行化发现刷新，避免并发请求重复握手同一批服务器。 */
    private final Object discoveryLock = new Object();

    /** 最近一次进程内工具目录；volatile 让其他请求立即看到完整替换后的快照。 */
    private volatile Catalog catalog = Catalog.empty("MCP discovery has not run");
    /** 最近发现尝试时间，用于限制故障服务器的重试频率。 */
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

    /**
     * 检查配置的 NEWS 工具是否已发现且被批准；必要时会触发一次受限发现。
     *
     * @return 当前可执行时为 {@code true}
     */
    public boolean isNewsSearchAvailable() {
        return properties.hasNewsSearchTarget()
                && currentCatalog().callbacks().containsKey(properties.newsSearchKey());
    }

    /**
     * 调用已批准的 MCP NEWS 工具。
     *
     * @param arguments 已由本地适配器改写为远端字段名的参数
     * @return MCP ToolCallback 返回的文本
     * @throws CapabilityException 工具未发现或协议调用失败
     */
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

    /**
     * 返回当前 MCP 状态；在需要时允许触发发现刷新。
     *
     * @return 仅含数量、协议版本和受限错误摘要的状态
     */
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
     * 只返回进程内最近状态，不触发发现或任何外部 MCP 调用。
     *
     * <p>管理面板轮询必须使用该方法，避免“查看状态”意外访问外部系统。</p>
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

    /** 按重试间隔返回现有目录或串行刷新一次目录。 */
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
            // 进入锁后再次检查时间，避免等待锁的请求重复执行远端发现。
            retryAfter = lastAttempt.plusSeconds(Math.max(1, properties.getDiscoveryRetrySeconds()));
            if (Instant.now().isBefore(retryAfter)) {
                return catalog;
            }
            lastAttempt = Instant.now();
            catalog = discover();
            return catalog;
        }
    }

    /** 初始化所有 MCP 客户端并生成只含批准工具的不可变目录。 */
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
                // Spring AI 客户端可能尚未握手；先 initialize 才能获得稳定服务器名和协议版本。
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

    /** 分页读取单个服务器工具，并把通过过滤器的工具包装为 Spring AI callback。 */
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
                // 远端声称可用不等于本地允许；必须命中精确 server/tool allowlist。
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

    /** 从握手结果提取稳定服务器名；缺失时拒绝建立可授权键。 */
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

    /** 一次发现后的进程内不可变目录，不持久化远端工具 schema 或调用结果。 */
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

    /**
     * 管理端可安全展示的 MCP 状态快照。
     *
     * @param enabled 总开关
     * @param newsSearchAvailable 配置的 NEWS 工具是否已批准并发现
     * @param approvedToolCount 当前目录中的批准工具数
     * @param protocolVersions 按服务器名记录的协商协议版本
     * @param error 最近一次受限错误摘要
     * @param checkedAt 目录生成时间
     */
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
