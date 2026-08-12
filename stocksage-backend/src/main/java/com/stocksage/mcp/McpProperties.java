package com.stocksage.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * StockSage 自有的 MCP 准入配置和固定 NEWS 契约。
 *
 * <p>Spring Boot 从 {@code stocksage.mcp.*} 绑定这些字段；
 * {@link StockSageMcpToolFilter} 使用精确 allowlist，Provider 使用目标服务器/工具和重试间隔，
 * 适配器使用参数字段映射。默认关闭，且这里只配置只读接入，不承载凭据或写操作开关。</p>
 */
@Component
@ConfigurationProperties(prefix = "stocksage.mcp")
public class McpProperties {

    /** MCP 总开关，关闭时不执行发现或调用。 */
    private boolean enabled;
    /** 允许的精确 {@code serverName/toolName} 集合。 */
    private Set<String> allowedTools = new LinkedHashSet<>();
    /** NEWS walking skeleton 绑定的稳定服务器名。 */
    private String newsSearchServer = "";
    /** NEWS walking skeleton 绑定的远端工具名。 */
    private String newsSearchTool = "";
    /** 远端工具接收查询词的字段名。 */
    private String newsSearchQueryField = "query";
    /** 远端工具接收结果上限的字段名。 */
    private String newsSearchLimitField = "maxResults";
    /** 发现失败后再次访问远端前的最短等待秒数。 */
    private long discoveryRetrySeconds = 30;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Set<String> getAllowedTools() {
        return allowedTools == null ? Set.of() : allowedTools.stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public void setAllowedTools(Set<String> allowedTools) {
        this.allowedTools = allowedTools;
    }

    public String getNewsSearchServer() {
        return newsSearchServer;
    }

    public void setNewsSearchServer(String newsSearchServer) {
        this.newsSearchServer = newsSearchServer;
    }

    public String getNewsSearchTool() {
        return newsSearchTool;
    }

    public void setNewsSearchTool(String newsSearchTool) {
        this.newsSearchTool = newsSearchTool;
    }

    public String getNewsSearchQueryField() {
        return newsSearchQueryField;
    }

    public void setNewsSearchQueryField(String newsSearchQueryField) {
        this.newsSearchQueryField = newsSearchQueryField;
    }

    public String getNewsSearchLimitField() {
        return newsSearchLimitField;
    }

    public void setNewsSearchLimitField(String newsSearchLimitField) {
        this.newsSearchLimitField = newsSearchLimitField;
    }

    public long getDiscoveryRetrySeconds() {
        return discoveryRetrySeconds;
    }

    public void setDiscoveryRetrySeconds(long discoveryRetrySeconds) {
        this.discoveryRetrySeconds = discoveryRetrySeconds;
    }

    /** @return 当前配置目标的 {@code server/tool} 目录键 */
    public String newsSearchKey() {
        return clean(newsSearchServer) + "/" + clean(newsSearchTool);
    }

    /** @return NEWS 服务器和工具名均已配置时为 {@code true} */
    public boolean hasNewsSearchTarget() {
        return !clean(newsSearchServer).isBlank() && !clean(newsSearchTool).isBlank();
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
