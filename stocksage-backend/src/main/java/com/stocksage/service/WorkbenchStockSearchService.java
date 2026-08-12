package com.stocksage.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.client.DataServiceClient;
import com.stocksage.model.dto.WorkbenchStockSuggestion;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工作台统一股票搜索适配器。
 *
 * <p>调用 {@link DataServiceClient#searchStocks} 获取 A 股、港股和美股候选，再把供应商字段差异
 * 收敛为 {@link WorkbenchStockSuggestion}；搜索异常降级为空列表，不阻断工作台页面。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkbenchStockSearchService {

    /** 未传有效 limit 时的默认候选数。 */
    private static final int DEFAULT_LIMIT = 8;
    /** 防止一次搜索返回过多候选的上限。 */
    private static final int MAX_LIMIT = 20;
    /** 识别 data-service 返回的标准 A 股代码。 */
    private static final Pattern A_SHARE_CODE = Pattern.compile("^(?:SH|SZ|BJ)\\.(\\d{6})$");
    /** 识别带 .HK 后缀的港股代码。 */
    private static final Pattern HK_CODE = Pattern.compile("^0*(\\d{1,5})\\.HK$");

    /** 访问跨市场股票目录搜索端点。 */
    private final DataServiceClient dataServiceClient;
    /** 解析候选响应 JSON。 */
    private final ObjectMapper objectMapper;

    /**
     * 搜索并归一化工作台股票候选。
     *
     * @param query 公司名、代码或别名片段
     * @param limit 最大候选数；非正数使用默认值
     * @return 去重后的候选列表；空查询或上游失败时返回空列表
     */
    public List<WorkbenchStockSuggestion> search(String query, int limit) {
        String cleanedQuery = String.valueOf(query == null ? "" : query).trim();
        if (cleanedQuery.isBlank()) {
            return List.of();
        }

        int maxResults = normalizeLimit(limit);
        try {
            // 调用 data-service 的本地解析→外部目录搜索链，再在 Java 侧统一前端 DTO。
            String raw = dataServiceClient.searchStocks(cleanedQuery, maxResults);
            return parseSuggestions(raw, maxResults);
        } catch (Exception e) {
            log.warn("Workbench stock search failed for query='{}': {}", cleanedQuery, e.getMessage());
            return List.of();
        }
    }

    /** 解析供应商兼容字段、按 ticker 去重并限制结果数。 */
    private List<WorkbenchStockSuggestion> parseSuggestions(String raw, int limit) throws Exception {
        JsonNode root = objectMapper.readTree(String.valueOf(raw == null ? "" : raw));
        if (root.path("error").asBoolean(false) || !root.path("candidates").isArray()) {
            return List.of();
        }

        List<WorkbenchStockSuggestion> suggestions = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode candidate : root.path("candidates")) {
            String market = firstNonBlank(
                    text(candidate, "market"),
                    text(candidate, "exchange"),
                    text(candidate, "quoteType")
            );
            String ticker = normalizeTickerForWatchlist(firstNonBlank(
                    text(candidate, "resolvedCode"),
                    text(candidate, "symbol"),
                    text(candidate, "code"),
                    text(candidate, "input")
            ), market);
            if (ticker.isBlank() || !seen.add(ticker)) {
                continue;
            }

            suggestions.add(new WorkbenchStockSuggestion(
                    ticker,
                    firstNonBlank(
                            text(candidate, "name"),
                            text(candidate, "shortName"),
                            text(candidate, "longName"),
                            text(candidate, "companyName"),
                            ticker
                    ),
                    market,
                    firstNonBlank(
                            text(candidate, "origin"),
                            text(candidate, "source"),
                            text(candidate, "provider")
                    )
            ));
            if (suggestions.size() >= limit) {
                break;
            }
        }
        return suggestions;
    }

    /** 将标准市场代码转换为关注列表使用的无标点展示代码。 */
    private String normalizeTickerForWatchlist(String value, String market) {
        String ticker = String.valueOf(value == null ? "" : value).trim().toUpperCase(Locale.ROOT);
        String normalizedMarket = String.valueOf(market == null ? "" : market).trim().toUpperCase(Locale.ROOT);
        if (ticker.isBlank()) {
            return "";
        }

        Matcher aShare = A_SHARE_CODE.matcher(ticker);
        if ("A_SHARE".equals(normalizedMarket) && aShare.matches()) {
            return aShare.group(1);
        }

        Matcher hk = HK_CODE.matcher(ticker);
        if ("HK".equals(normalizedMarket) && hk.matches()) {
            return hk.group(1).length() >= 4 ? hk.group(1) : "0".repeat(4 - hk.group(1).length()) + hk.group(1);
        }

        return ticker.replaceAll("[^A-Z0-9]", "");
    }

    /** 将调用方 limit 限制在默认值与 MAX_LIMIT 之间。 */
    private int normalizeLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    /** 容错读取候选节点的文本字段。 */
    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    /** 从不同供应商字段中选择第一个非空值。 */
    private String firstNonBlank(String... values) {
        for (String value : values) {
            String cleaned = String.valueOf(value == null ? "" : value).trim();
            if (!cleaned.isBlank()) {
                return cleaned;
            }
        }
        return "";
    }
}
