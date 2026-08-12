package com.stocksage.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.client.DataServiceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作台股票新闻读取服务。
 *
 * <p>不新增任何数据源——直接复用既有的 data-service 新闻管线
 * （{@code /api/stock/news}，Tavily 检索、DDG 兜底、已带缓存），
 * 把其返回的 {@code results} 归一成前端可直接渲染的条目列表
 * {@code [{title,url,source,date,snippet}]}，屏蔽 provider 差异。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockNewsService {

    /** 复用 Python 新闻端点及其缓存、搜索降级策略。 */
    private final DataServiceClient dataServiceClient;
    /** 将供应商 JSON 解析成统一前端结构。 */
    private final ObjectMapper objectMapper;

    /**
     * 读取某 ticker 近 {@code days} 天新闻并归一为前端条目列表。
     *
     * @param ticker 股票代码
     * @param days 回溯天数；非正数默认 7 天
     * @return 包含 provider、items、count、empty 和可选 error 的载荷
     */
    public Map<String, Object> getNews(String ticker, int days) {
        String norm = ticker == null ? "" : ticker.trim().toUpperCase();
        int window = days <= 0 ? 7 : days;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ticker", norm);
        List<Map<String, Object>> items = new ArrayList<>();
        String provider = "";

        if (!norm.isEmpty()) {
            try {
                // 调用 data-service 的既有新闻管线，不在 Java 侧直接访问第三方搜索供应商。
                String raw = dataServiceClient.getStockNews(norm, window);
                JsonNode root = objectMapper.readTree(raw == null || raw.isBlank() ? "{}" : raw);
                provider = root.path("provider").asText("");
                JsonNode results = root.path("results");
                if (results.isArray()) {
                    for (JsonNode r : results) {
                        String title = r.path("title").asText("").trim();
                        if (title.isBlank()) {
                            continue;
                        }
                        String url = firstNonBlank(r.path("link").asText(""), r.path("url").asText(""));
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("title", title);
                        item.put("url", url);
                        item.put("source", firstNonBlank(r.path("source").asText(""), domainOf(url)));
                        item.put("date", r.path("date").asText(""));
                        item.put("snippet", r.path("snippet").asText(""));
                        items.add(item);
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to load stock news for {}: {}", norm, e.getMessage());
                result.put("error", "新闻读取失败");
            }
        }

        result.put("provider", provider);
        result.put("items", items);
        result.put("count", items.size());
        result.put("empty", items.isEmpty());
        return result;
    }

    /** 在供应商兼容字段中选择第一个非空文本。 */
    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : (b == null ? "" : b);
    }

    /** URL 未提供 source 字段时提取主机名作为展示来源。 */
    private static String domainOf(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        try {
            String host = URI.create(url).getHost();
            if (host == null) {
                return "";
            }
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (Exception e) {
            return "";
        }
    }
}
