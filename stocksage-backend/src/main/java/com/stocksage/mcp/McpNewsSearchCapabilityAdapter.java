package com.stocksage.mcp;

import com.stocksage.capability.CapabilityAdapter;
import com.stocksage.capability.CapabilityException;
import com.stocksage.capability.CapabilityInvocationContext;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Fixed local contract mapped onto one explicitly approved remote MCP tool. */
@Component
public class McpNewsSearchCapabilityAdapter implements CapabilityAdapter {

    public static final String ID = "mcp.news.search";

    private final McpCapabilityProvider provider;
    private final McpProperties properties;

    public McpNewsSearchCapabilityAdapter(McpCapabilityProvider provider, McpProperties properties) {
        this.provider = provider;
        this.properties = properties;
    }

    @Override
    public String capabilityId() {
        return ID;
    }

    @Override
    public boolean isAvailable() {
        return provider.isNewsSearchAvailable();
    }

    @Override
    public String invoke(Map<String, Object> arguments, CapabilityInvocationContext context) {
        String query = String.valueOf(arguments.getOrDefault("query", "")).trim();
        if (query.isBlank()) {
            throw new CapabilityException(CapabilityException.Reason.DENIED,
                    "MCP news search query must not be blank");
        }
        int limit = boundedLimit(arguments.getOrDefault("maxResults", 5));
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
