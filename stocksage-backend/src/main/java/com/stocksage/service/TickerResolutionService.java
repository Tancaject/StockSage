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

    /** 匹配用户显式输入的 1～5 位大写美股代码。 */
    private static final Pattern US_TICKER_PATTERN = Pattern.compile("\\b[A-Z]{1,5}\\b");

    /**
     * 不应被当作美股 ticker 的常见缩写。
     * 该词表是原 ChatService 与 Coordinator 两份词表的并集（Coordinator 原词表为其子集）。
     */
    private static final Set<String> NON_TICKER_TERMS = Set.of(
            "AI", "PE", "PB", "ROE", "RSI", "MACD", "MA", "K", "Q", "SEC", "HK", "IPO",
            "ETF", "USD", "EPS", "EV", "FCF", "GDP", "CPI", "CEO", "CFO", "US", "QOQ", "YOY"
    );

    /** 搜索兜底前应从问题中剔除的泛化投资措辞。 */
    private static final List<String> GENERIC_STOCK_QUERY_TERMS = List.of(
            "是否", "值得", "长期", "投资", "分析", "一下", "为什么", "为何", "原因",
            "股价", "暴涨", "大涨", "下跌", "今天", "这两天", "最近", "近期", "最新",
            "公司", "股票", "港股", "美股", "A股", "财报", "年报", "季报"
    );

    /**
     * 常见公司名按既有优先级排列；一句话出现多个公司时，第一个匹配项仍是主标的。
     */
    private static final List<CompanyAlias> COMPANY_ALIASES = List.of(
            new CompanyAlias("MU", List.of("美光", "MICRON")),
            new CompanyAlias("NVDA", List.of("英伟达", "英偉達", "NVIDIA")),
            new CompanyAlias("MSFT", List.of("微软", "微軟", "MICROSOFT")),
            new CompanyAlias("AAPL", List.of("苹果", "蘋果", "APPLE")),
            new CompanyAlias("AMZN", List.of("亚马逊", "亞馬遜", "AMAZON")),
            new CompanyAlias("GOOGL", List.of("谷歌", "GOOGLE", "ALPHABET")),
            new CompanyAlias("TSLA", List.of("特斯拉", "TESLA")),
            new CompanyAlias("JNJ", List.of("强生", "強生", "JOHNSON")),
            new CompanyAlias("XOM", List.of("埃克森", "EXXON")),
            new CompanyAlias("JPM", List.of("摩根", "JPMORGAN"))
    );

    /** 常见 ticker 的轻量行业映射，未知标的返回空字符串。 */
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

    /** 读取最近用户消息，用于省略主语的追问解析。 */
    private final MessageRepository messageRepository;
    /** 本地启发式失败后调用股票搜索工具兜底。 */
    private final MarketTools marketTools;
    /** 解析股票搜索工具返回的候选 JSON。 */
    private final ObjectMapper objectMapper;

    /**
     * 从用户问题中解析主 ticker。
     *
     * @param query 当前用户问题
     * @return 解析出的 ticker；无法判断时为空字符串
     */
    public String resolvePrimaryTicker(String query) {
        return resolvePrimaryTicker(query, null);
    }

    /**
     * 结合会话短期记忆解析主 ticker。
     *
     * @param query 当前用户问题
     * @param conversationId 会话 ID；为空时不回看历史
     * @return 当前问题或最近六条用户消息中的主 ticker；无法判断时为空字符串
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
     *
     * @param query 待检查文本
     * @return 存在至少一个非保留大写候选时为 true
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
     *
     * @param ticker 待分类的股票代码
     * @return 美股式代码为 true，A/H 股格式或空值为 false
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
     *
     * @param ticker 股票代码
     * @return 已知行业名称；映射中不存在时为空字符串
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
        for (CompanyAlias company : COMPANY_ALIASES) {
            if (company.matches(upper)) {
                return company.ticker();
            }
        }

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
        // 调用 MarketTools.searchStocks 获取规范代码，网络或数据异常统一降级为空结果。
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

    /**
     * 一组按顺序匹配的公司别名；列表顺序决定多标的问题中谁是主标的。
     *
     * @param ticker 规范美股代码
     * @param aliases 可识别的中英文公司名
     */
    private record CompanyAlias(String ticker, List<String> aliases) {

        private boolean matches(String normalizedQuery) {
            return aliases.stream().anyMatch(normalizedQuery::contains);
        }
    }
}
