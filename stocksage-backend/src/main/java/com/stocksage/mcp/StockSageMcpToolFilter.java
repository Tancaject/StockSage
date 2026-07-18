package com.stocksage.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.mcp.McpConnectionInfo;
import org.springframework.ai.mcp.McpToolFilter;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/** Exact local allowlist with a second deny guard for obvious side-effect tool names. */
@Component
public class StockSageMcpToolFilter implements McpToolFilter {

    private static final List<String> DENIED_NAME_FRAGMENTS = List.of(
            "placeorder", "place_order", "trade", "buy", "sell", "write", "delete",
            "update", "create", "upload", "execute", "shell", "payment", "transfer"
    );

    private final McpProperties properties;

    public StockSageMcpToolFilter(McpProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean test(McpConnectionInfo connection, McpSchema.Tool tool) {
        String serverName = "";
        if (connection != null && connection.initializeResult() != null
                && connection.initializeResult().serverInfo() != null) {
            serverName = connection.initializeResult().serverInfo().name();
        }
        return isAllowed(serverName, tool == null ? null : tool.name());
    }

    public boolean isAllowed(String serverName, String toolName) {
        if (!properties.isEnabled() || serverName == null || serverName.isBlank()
                || toolName == null || toolName.isBlank()) {
            return false;
        }
        String normalizedName = toolName.replace("-", "_").toLowerCase(Locale.ROOT);
        if (DENIED_NAME_FRAGMENTS.stream().anyMatch(normalizedName::contains)) {
            return false;
        }
        return properties.getAllowedTools().contains(serverName.trim() + "/" + toolName.trim());
    }
}
