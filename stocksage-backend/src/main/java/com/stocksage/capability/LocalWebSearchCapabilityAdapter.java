package com.stocksage.capability;

import com.stocksage.tool.NewsTools;
import org.springframework.stereotype.Component;
import java.util.Map;

/** 普通网页搜索复用新闻工具，能力网关拥有授权与观测。 */
@Component
public class LocalWebSearchCapabilityAdapter implements CapabilityAdapter {
    public static final String ID = "local.news.webSearch";
    private final NewsTools newsTools;

    public LocalWebSearchCapabilityAdapter(NewsTools newsTools) {
        this.newsTools = newsTools;
    }

    @Override public String capabilityId() { return ID; }
    @Override public boolean isAvailable() { return true; }

    @Override
    public String invoke(Map<String, Object> arguments, CapabilityInvocationContext context) {
        String query = LocalNewsSearchCapabilityAdapter.requiredQuery(arguments);
        int limit = LocalNewsSearchCapabilityAdapter.boundedLimit(arguments, 20);
        return LocalNewsSearchCapabilityAdapter.advanced(arguments)
                ? newsTools.webSearch(query, limit, true) : newsTools.webSearch(query, limit);
    }
}
