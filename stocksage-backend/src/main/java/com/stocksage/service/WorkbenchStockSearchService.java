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

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkbenchStockSearchService {

    private static final int DEFAULT_LIMIT = 8;
    private static final int MAX_LIMIT = 20;
    private static final Pattern A_SHARE_CODE = Pattern.compile("^(?:SH|SZ|BJ)\\.(\\d{6})$");
    private static final Pattern HK_CODE = Pattern.compile("^0*(\\d{1,5})\\.HK$");

    private final DataServiceClient dataServiceClient;
    private final ObjectMapper objectMapper;

    public List<WorkbenchStockSuggestion> search(String query, int limit) {
        String cleanedQuery = String.valueOf(query == null ? "" : query).trim();
        if (cleanedQuery.isBlank()) {
            return List.of();
        }

        int maxResults = normalizeLimit(limit);
        try {
            String raw = dataServiceClient.searchStocks(cleanedQuery, maxResults);
            return parseSuggestions(raw, maxResults);
        } catch (Exception e) {
            log.warn("Workbench stock search failed for query='{}': {}", cleanedQuery, e.getMessage());
            return List.of();
        }
    }

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

    private int normalizeLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

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
