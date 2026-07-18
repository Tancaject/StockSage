package com.stocksage.capability;

import com.stocksage.tool.NewsTools;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Existing local news search exposed through the unified capability gateway. */
@Component
public class LocalNewsSearchCapabilityAdapter implements CapabilityAdapter {

    public static final String ID = "local.news.searchNews";

    private final NewsTools newsTools;

    public LocalNewsSearchCapabilityAdapter(NewsTools newsTools) {
        this.newsTools = newsTools;
    }

    @Override
    public String capabilityId() {
        return ID;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public String invoke(Map<String, Object> arguments, CapabilityInvocationContext context) {
        return newsTools.searchNews(requiredQuery(arguments), boundedLimit(arguments));
    }

    private String requiredQuery(Map<String, Object> arguments) {
        String query = String.valueOf(arguments.getOrDefault("query", "")).trim();
        if (query.isBlank()) {
            throw new CapabilityException(CapabilityException.Reason.DENIED,
                    "News search query must not be blank");
        }
        return query;
    }

    private int boundedLimit(Map<String, Object> arguments) {
        Object raw = arguments.getOrDefault("maxResults", 5);
        int value;
        try {
            value = raw instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(raw));
        } catch (NumberFormatException error) {
            value = 5;
        }
        return Math.max(1, Math.min(10, value));
    }
}
