package com.stocksage.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.mcp.McpConnectionInfo;
import org.springframework.ai.mcp.McpToolFilter;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * MCP 工具的本地精确 allowlist 与副作用名称二次拦截器。
 *
 * <p>Spring AI 在生成工具 callback 时会调用 {@link #test}，Provider 手动发现时也调用
 * {@link #isAllowed}。工具必须同时满足总开关、精确 {@code server/tool} 配置和名称安全检查；
 * 这层过滤不代替 CapabilityPolicy，而是防止写类工具进入本地目录。</p>
 */
@Component
public class StockSageMcpToolFilter implements McpToolFilter {

    /** 明显表示下单、写入、执行或资金动作的名称片段；命中即拒绝。 */
    private static final List<String> DENIED_NAME_FRAGMENTS = List.of(
            "placeorder", "place_order", "trade", "buy", "sell", "write", "delete",
            "update", "create", "upload", "execute", "shell", "payment", "transfer"
    );

    /** MCP 总开关和精确 allowlist 的配置来源。 */
    private final McpProperties properties;

    public StockSageMcpToolFilter(McpProperties properties) {
        this.properties = properties;
    }

    /**
     * 从 Spring AI 连接信息提取服务器名，并复用统一准入判断。
     *
     * @param connection 已完成或正在完成握手的 MCP 连接
     * @param tool 远端声明的工具
     * @return 工具可进入本地 callback 目录时为 {@code true}
     */
    @Override
    public boolean test(McpConnectionInfo connection, McpSchema.Tool tool) {
        String serverName = "";
        if (connection != null && connection.initializeResult() != null
                && connection.initializeResult().serverInfo() != null) {
            serverName = connection.initializeResult().serverInfo().name();
        }
        return isAllowed(serverName, tool == null ? null : tool.name());
    }

    /**
     * 按稳定服务器名和工具名执行失败关闭的准入判断。
     *
     * @return 仅在命中精确 allowlist 且名称不含副作用片段时为 {@code true}
     */
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
