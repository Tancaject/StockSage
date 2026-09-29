package com.stocksage.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.client.DataServiceClient;
import com.stocksage.client.SearchResponse;
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
    /** 验证 data-service 契约后转为统一前端结构。 */
    private final ObjectMapper objectMapper;

    /**
     * 按 {@code days} 请求新闻窗口，并保留供应商实际使用的检索窗口。
     *
     * @param ticker 股票代码
     * @param days 回溯天数；非正数默认 7 天
     * @return 新闻条目、实际检索元数据、状态和可选的错误说明
     */
    public Map<String, Object> getNews(String ticker, int days) {
        String norm = ticker == null ? "" : ticker.trim().toUpperCase();
        int window = days <= 0 ? 7 : days;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ticker", norm);
        result.put("provider", "");
        List<Map<String, Object>> items = new ArrayList<>();

        if (!norm.isEmpty()) {
            try {
                // 调用 data-service 的既有新闻管线，不在 Java 侧直接访问第三方搜索供应商。
                String raw = dataServiceClient.getStockNews(norm, window);
                SearchResponse response = SearchResponse.fromJson(objectMapper.readTree(raw));
                Map<String, Object> metadata = objectMapper.convertValue(response, new TypeReference<LinkedHashMap<String, Object>>() {});
                metadata.remove("results");
                metadata.remove("error");
                result.putAll(metadata);
                if (response.error()) {
                    result.put("error", "新闻读取失败（data-service /api/stock/news）："
                            + firstNonBlank(response.message(), "上游服务未返回可用结果")
                            + "。请稍后重试；若持续失败，请检查 data-service 日志和搜索服务配置。");
                } else {
                    for (var r : response.results()) {
                        String title = r.title().trim();
                        if (title.isBlank()) {
                            continue;
                        }
                        String url = r.link();
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("title", title);
                        item.put("url", url);
                        item.put("source", firstNonBlank(r.source(), domainOf(url)));
                        item.put("date", r.date());
                        item.put("snippet", r.snippet());
                        item.put("publishedTimeKind", r.publishedTimeKind().name());
                        item.put("publishedAt", r.publishedAt());
                        item.put("publishedDate", r.publishedDate());
                        item.put("publishedTimeBasis", r.publishedTimeBasis());
                        items.add(item);
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to load stock news for {}: {}", norm, e.getMessage());
                result.put("status", "ERROR");
                result.put("error", "新闻读取失败（Java 新闻服务）：无法读取 data-service 的新闻响应。"
                        + "请检查 data-service 日志及接口版本后重试。");
            }
        }

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
