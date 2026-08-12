package com.stocksage.mcp;

import com.stocksage.capability.CapabilityAdapter;
import com.stocksage.capability.CapabilityException;
import com.stocksage.capability.CapabilityInvocationContext;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把稳定的本地 NEWS 能力契约映射到一个显式批准的远端 MCP 工具。
 *
 * <p>上游 CapabilityGateway 仍负责授权、超时和限长；本类只校验查询词、限制结果数，并按
 * {@link McpProperties} 把本地字段名改写为远端 schema。远端工具不可用时由 Skill 选择本地 fallback。</p>
 */
@Component
public class McpNewsSearchCapabilityAdapter implements CapabilityAdapter {

    /** 能力清单和 NEWS Skill 共同引用的稳定 ID。 */
    public static final String ID = "mcp.news.search";

    /** 负责 MCP 握手、发现缓存和实际 ToolCallback 调用。 */
    private final McpCapabilityProvider provider;
    /** 提供受控的远端参数字段映射。 */
    private final McpProperties properties;

    public McpNewsSearchCapabilityAdapter(McpCapabilityProvider provider, McpProperties properties) {
        this.provider = provider;
        this.properties = properties;
    }

    /** @return MCP 新闻搜索的本地稳定能力 ID */
    @Override
    public String capabilityId() {
        return ID;
    }

    /** @return 配置目标已被发现并通过本地过滤时为 {@code true} */
    @Override
    public boolean isAvailable() {
        return provider.isNewsSearchAvailable();
    }

    /**
     * 校验并映射新闻查询参数后调用远端 MCP 工具。
     *
     * @param arguments 支持 {@code query} 与 {@code maxResults}
     * @param context 当前调用上下文；授权已由 Gateway 完成
     * @return 远端工具返回的文本
     */
    @Override
    public String invoke(Map<String, Object> arguments, CapabilityInvocationContext context) {
        String query = String.valueOf(arguments.getOrDefault("query", "")).trim();
        if (query.isBlank()) {
            throw new CapabilityException(CapabilityException.Reason.DENIED,
                    "MCP news search query must not be blank");
        }
        int limit = boundedLimit(arguments.getOrDefault("maxResults", 5));
        // 字段名来自服务器受控配置，而不是远端响应或模型输出。
        Map<String, Object> remoteArguments = new LinkedHashMap<>();
        remoteArguments.put(nonBlank(properties.getNewsSearchQueryField(), "query"), query);
        remoteArguments.put(nonBlank(properties.getNewsSearchLimitField(), "maxResults"), limit);
        return provider.callNewsSearch(remoteArguments);
    }

    private int boundedLimit(Object raw) {
        int value;
        try {
            value = raw instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(raw));
        } catch (NumberFormatException error) {
            value = 5;
        }
        return Math.max(1, Math.min(10, value));
    }

    private String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
