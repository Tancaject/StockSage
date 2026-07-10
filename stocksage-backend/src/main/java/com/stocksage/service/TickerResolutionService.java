package com.stocksage.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.model.entity.Message;
import com.stocksage.repository.MessageRepository;
import com.stocksage.tool.MarketTools;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 股票标的解析服务。
 *
 * <p>集中承载"从自然语言问题定位主标的"的全部启发式：中文公司名映射、
 * 大写 ticker 提取（含非 ticker 词过滤）、会话上下文回看，以及数据服务搜索兜底。
 * Coordinator 与预取编排共用这一份词表，避免两处启发式各自漂移。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TickerResolutionService {

    private static final Pattern US_TICKER_PATTERN = Pattern.compile("\\b[A-Z]{1,5}\\b");

    /**
     * 不应被当作美股 ticker 的常见缩写。
     * 该词表是原 ChatService 与 Coordinator 两份词表的并集（Coordinator 原词表为其子集）。
     */
    private static final Set<String> NON_TICKER_TERMS = Set.of(
            "AI", "PE", "PB", "ROE", "RSI", "MACD", "MA", "K", "Q", "SEC", "HK", "IPO",
            "ETF", "USD", "EPS", "EV", "FCF", "GDP", "CPI", "CEO", "CFO", "US", "QOQ", "YOY"
    );

    private static final List<String> GENERIC_STOCK_QUERY_TERMS = List.of(
            "是否", "值得", "长期", "投资", "分析", "一下", "为什么", "为何", "原因",
            "股价", "暴涨", "大涨", "下跌", "今天", "这两天", "最近", "近期", "最新",
            "公司", "股票", "港股", "美股", "A股", "财报", "年报", "季报"
    );

    private static final Map<String, String> TICKER_SECTOR_MAP = Map.ofEntries(
            Map.entry("AAPL", "消费电子与服务"),
            Map.entry("MSFT", "软件与云服务"),
            Map.entry("NVDA", "半导体"),
            Map.entry("GOOGL", "互联网与数字广告"),
            Map.entry("META", "社交媒体与数字广告"),
            Map.entry("TSLA", "汽车"),
            Map.entry("AMZN", "电商与云服务"),
            Map.entry("MU", "半导体"),
            Map.entry("XOM", "能源"),
            Map.entry("JNJ", "医药"),
            Map.entry("JPM", "金融"),
            Map.entry("BABA", "电商与云服务"),
            Map.entry("JD", "电商"),
            Map.entry("BIDU", "互联网与 AI"),
            Map.entry("NTES", "游戏与互联网"),
            Map.entry("SH.600519", "白酒"),
            Map.entry("SZ.000001", "银行"),
            Map.entry("SH.600036", "银行"),
            Map.entry("SZ.300750", "新能源"),
            Map.entry("SZ.002594", "新能源汽车"),
            Map.entry("0700.HK", "互联网"),
            Map.entry("9988.HK", "互联网电商"),
            Map.entry("1211.HK", "新能源汽车")
    );

    private final MessageRepository messageRepository;
    private final MarketTools marketTools;
    private final ObjectMapper objectMapper;

    /**
     * 从用户问题中解析主 ticker。
     */
    public String resolvePrimaryTicker(String query) {
        return resolvePrimaryTicker(query, null);
    }

    /**
     * 结合会话短期记忆解析主 ticker。
     */
    public String resolvePrimaryTicker(String query, Long conversationId) {
        String direct = resolvePrimaryTickerFromText(query);
        if (!direct.isBlank()) {
            return direct;
        }

        if (conversationId == null) {
            return "";
        }

        try {
            List<Message> history = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
            for (int i = history.size() - 1, checked = 0; i >= 0 && checked < 6; i--) {
                Message message = history.get(i);
                if (!"user".equalsIgnoreCase(message.getRole())) {
                    continue;
                }
                checked++;
                String previous = message.getContent();
                if (previous == null || previous.isBlank() || previous.equals(query)) {
                    continue;
                }
                String resolved = resolvePrimaryTickerFromText((query == null ? "" : query) + " " + previous);
                if (!resolved.isBlank()) {
                    return resolved;
                }
            }
        } catch (Exception e) {
            log.debug("Failed to resolve ticker from conversation context: {}", e.getMessage());
        }

        return "";
    }

    /**
     * 判断文本中是否包含疑似美股 ticker 的大写词（过滤 PE、ROE 等指标缩写）。
     * 供 Coordinator 的确定性路由规则复用。
     */
    public boolean containsLikelyTicker(String query) {
        Matcher matcher = US_TICKER_PATTERN.matcher(query == null ? "" : query);
        while (matcher.find()) {
            String candidate = matcher.group();
            if (!NON_TICKER_TERMS.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断 ticker 是否更像美股 SEC 标的。
     */
    public boolean isLikelySecTicker(String ticker) {
        if (ticker == null) {
            return false;
        }
        String normalized = ticker.trim().toUpperCase(Locale.ROOT);
        if (normalized.matches("(SH|SZ|BJ)\\.\\d{6}")
                || normalized.matches("\\d{6}")
                || normalized.matches("\\d{1,5}(\\.HK)?")
                || normalized.startsWith("HK:")
                || normalized.startsWith("HKG:")
                || normalized.endsWith(".HK")) {
            return false;
        }
        return normalized.matches("[A-Z][A-Z0-9.-]{0,9}") && !normalized.endsWith(".HK");
    }

    /**
     * 根据 ticker 推断行业名称。
     */
    public String resolveSectorForTicker(String ticker) {
        if (ticker == null || ticker.isBlank()) {
            return "";
        }
        String normalized = ticker.trim().toUpperCase(Locale.ROOT);
        return TICKER_SECTOR_MAP.getOrDefault(normalized, "");
    }

    /**
     * 仅从当前文本中解析主 ticker：先中文公司名映射，再大写 ticker 提取，最后搜索兜底。
     */
    private String resolvePrimaryTickerFromText(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        String upper = query.toUpperCase(Locale.ROOT);
        if (query.contains("美光") || upper.contains("MICRON")) return "MU";
        if (query.contains("英伟达") || query.contains("英偉達") || upper.contains("NVIDIA")) return "NVDA";
        if (query.contains("微软") || query.contains("微軟") || upper.contains("MICROSOFT")) return "MSFT";
        if (query.contains("苹果") || query.contains("蘋果") || upper.contains("APPLE")) return "AAPL";
        if (query.contains("亚马逊") || query.contains("亞馬遜") || upper.contains("AMAZON")) return "AMZN";
        if (query.contains("谷歌") || upper.contains("GOOGLE") || upper.contains("ALPHABET")) return "GOOGL";
        if (query.contains("特斯拉") || upper.contains("TESLA")) return "TSLA";
        if (query.contains("强生") || query.contains("強生") || upper.contains("JOHNSON")) return "JNJ";
        if (query.contains("埃克森") || upper.contains("EXXON")) return "XOM";
        if (query.contains("摩根") || upper.contains("JPMORGAN")) return "JPM";

        // 在原始大小写文本上匹配：用户写 ticker 时本就是大写（NVDA），写普通词时是小写（Run）。
        // 大小写本身就是区分 ticker 和普通词的信号，不能先把整句转大写后再匹配。
        Matcher matcher = US_TICKER_PATTERN.matcher(query);
        while (matcher.find()) {
            String candidate = matcher.group();
            if (!NON_TICKER_TERMS.contains(candidate)) {
                return candidate;
            }
        }
        String searchQuery = stockSearchQuery(query);
        if (searchQuery.isBlank()) {
            return "";
        }
        return resolvePrimaryTickerWithSearch(searchQuery);
    }

    /**
     * 构造用于股票搜索工具的查询文本。
     */
    private String stockSearchQuery(String query) {
        String cleaned = query == null ? "" : query.trim();
        for (String term : GENERIC_STOCK_QUERY_TERMS) {
            cleaned = cleaned.replace(term, " ");
        }
        cleaned = cleaned.replaceAll("[,，。；;：:（）()【】\\[\\]\"'?!！？、]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return cleaned.length() >= 2 ? cleaned : "";
    }

    /**
     * 通过股票搜索工具解析主 ticker。
     */
    private String resolvePrimaryTickerWithSearch(String query) {
        try {
            String raw = marketTools.searchStocks(query, 3);
            JsonNode root = objectMapper.readTree(raw);
            JsonNode candidates = root.path("candidates");
            if (!candidates.isArray()) {
                return "";
            }
            for (JsonNode candidate : candidates) {
                String market = candidate.path("market").asText("");
                if (market.isBlank() || "UNSUPPORTED".equalsIgnoreCase(market)) {
                    continue;
                }
                String symbol = firstNonBlank(
                        candidate.path("symbol").asText(""),
                        candidate.path("input").asText(""),
                        candidate.path("resolvedCode").asText("")
                );
                if (!symbol.isBlank()) {
                    return symbol;
                }
            }
        } catch (Exception e) {
            log.debug("searchStocks ticker resolution failed for query='{}': {}", query, e.getMessage());
        }
        return "";
    }

    /**
     * 返回第一个非空白字符串。
     */
    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }
}
