package com.stocksage.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/** StockSage-owned MCP policy and the fixed NEWS walking-skeleton contract. */
@Component
@ConfigurationProperties(prefix = "stocksage.mcp")
public class McpProperties {

    private boolean enabled;
    private Set<String> allowedTools = new LinkedHashSet<>();
    private String newsSearchServer = "";
    private String newsSearchTool = "";
    private String newsSearchQueryField = "query";
    private String newsSearchLimitField = "maxResults";
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

    public String newsSearchKey() {
        return clean(newsSearchServer) + "/" + clean(newsSearchTool);
    }

    public boolean hasNewsSearchTarget() {
        return !clean(newsSearchServer).isBlank() && !clean(newsSearchTool).isBlank();
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
