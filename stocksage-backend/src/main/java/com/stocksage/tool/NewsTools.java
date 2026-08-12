package com.stocksage.tool;

import com.stocksage.client.DataServiceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 可由模型调用的新闻和搜索工具。
 *
 * <p>时效性归一化放在这里处理，因为模型有时会复用旧示例中的过期年份。
 * 是否把搜索改写到当前年份，由用户原始问题决定。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NewsTools {

    /** 用于识别模型自行带入的四位年份，以便仅在用户确有时效诉求时纠正。 */
    private static final Pattern YEAR_PATTERN = Pattern.compile("\\b(20\\d{2})\\b");

    /** 实际执行网页/新闻检索的 Python 数据服务客户端。 */
    private final DataServiceClient dataServiceClient;

    /**
     * 获取指定股票近期新闻。
     *
     * <p>该方法适合围绕单一标的做新闻补充，具体数据源和去重逻辑由 Python 数据服务负责。</p>
     */
    @Tool(description = "获取指定股票的近期新闻和公告摘要")
    public String getStockNews(
            @ToolParam(description = "股票代码或名称，如 AAPL、TSLA") String code,
            @ToolParam(description = "获取最近多少天的新闻") int days) {
        return dataServiceClient.getStockNews(code, days);
    }

    /**
     * 执行通用网页搜索（basic 检索深度）。
     *
     * <p>搜索前会根据用户原始问题判断是否需要把过去年份纠正为当前年份。
     * 若用户表达了时效诉求，则改走新闻检索并按时间范围过滤，
     * 避免通用网页搜索按相关性返回陈旧的权威页面。</p>
     */
    @Tool(description = "联网搜索金融相关信息。当知识库中没有相关内容、或需要获取最新市场新闻、政策变化、公司公告时使用")
    public String webSearch(
            @ToolParam(description = "搜索关键词，建议用中文+英文混合。若用户问最近、近期、最新、当前等问题，应使用当前年份或不带年份的查询词，不要擅自使用过去年份") String query,
            @ToolParam(description = "返回结果数量，默认5条") int maxResults) {
        return webSearch(query, maxResults, false);
    }

    /**
     * 执行通用网页搜索，可指定检索深度。
     *
     * <p>advanced 召回更全、相关性更好，但每次消耗 2 个 Tavily credit（basic 为 1）。
     * 模型工具调用始终走 basic；advanced 仅由后端在深度投研预取等需要高质量证据的场景显式开启。</p>
     *
     * @param query 搜索词；仅在原始问题要求最新信息时才会纠正过期年份
     * @param maxResults 期望返回条数
     * @param advanced 是否使用 Tavily advanced 深度检索
     * @return 数据服务返回的搜索 JSON
     */
    public String webSearch(String query, int maxResults, boolean advanced) {
        String timeLimit = resolveTimeLimit(ToolCallContext.getUserQuery());
        String normalizedQuery = normalizeFreshSearchQuery(query);
        if (timeLimit != null) {
            // 用户明确问“今天/最近”时改用带时间窗的新闻接口，避免相关性排序返回旧页面。
            return dataServiceClient.newsSearch(normalizedQuery, maxResults, timeLimit, advanced);
        }
        return dataServiceClient.webSearch(normalizedQuery, maxResults, null, advanced);
    }

    /**
     * 搜索最新财经新闻（basic 检索深度）。
     *
     * <p>与 webSearch 相比，该入口更适合新闻、政策、市场热点和公司动态类问题。
     * 用户明确表达时效诉求时按其粒度（24 小时 / 周 / 月）过滤；否则交给新闻检索本身的近期排序，
     * 不强行加窗，避免误伤"去年/某历史年份"等回溯类新闻问题。</p>
     */
    @Tool(description = "搜索最新财经新闻。当用户询问某只股票的最新消息、市场热点、政策动态时使用")
    public String searchNews(
            @ToolParam(description = "搜索关键词。若用户问最近、近期、最新、当前等问题，应使用当前年份或不带年份的查询词，不要擅自使用过去年份") String query,
            @ToolParam(description = "返回结果数量，默认5条") int maxResults) {
        return searchNews(query, maxResults, false);
    }

    /**
     * 搜索最新财经新闻，可指定检索深度。
     *
     * @param query 新闻查询词
     * @param maxResults 期望返回条数
     * @param advanced 是否使用 Tavily advanced 深度检索（2 credit/次 vs basic 1 credit/次）
     * @return 数据服务返回的新闻 JSON
     */
    public String searchNews(String query, int maxResults, boolean advanced) {
        String timeLimit = resolveTimeLimit(ToolCallContext.getUserQuery());
        return dataServiceClient.newsSearch(normalizeFreshSearchQuery(query), maxResults, timeLimit, advanced);
    }

    /**
     * 根据用户原始问题推断检索时间范围。
     *
     * <p>返回 DuckDuckGo 的时间范围代码：d=24 小时、w=一周、m=一月；
     * 没有明确时效诉求时返回 null，表示不做时间过滤。粒度更细的关键词优先匹配。</p>
     */
    private String resolveTimeLimit(String userQuery) {
        if (userQuery == null || userQuery.isBlank()) {
            return null;
        }
        String lower = userQuery.toLowerCase();
        if (userQuery.contains("今天") || userQuery.contains("今日")
                || userQuery.contains("现在") || userQuery.contains("此刻")
                || userQuery.contains("刚刚") || lower.contains("today")) {
            return "d";
        }
        if (userQuery.contains("最近") || userQuery.contains("近期")
                || userQuery.contains("近段") || userQuery.contains("最新")
                || userQuery.contains("这几天") || userQuery.contains("这两天")
                || userQuery.contains("本周") || userQuery.contains("这周")
                || lower.contains("recent") || lower.contains("latest")
                || lower.contains("current") || lower.contains("this week")) {
            return "w";
        }
        if (userQuery.contains("本月") || userQuery.contains("这个月")
                || userQuery.contains("近一个月") || lower.contains("this month")) {
            return "m";
        }
        return null;
    }

    /**
     * 如果用户询问最近或当前信息，则替换模型自行引入的过期年份，
     * 并追加表示最新信息的关键词和当前年份。
     */
    private String normalizeFreshSearchQuery(String query) {
        if (query == null || query.isBlank()) {
            return query;
        }

        String userQuery = ToolCallContext.getUserQuery();
        if (!isFreshnessQuestion(userQuery)) {
            return query;
        }

        int currentYear = LocalDate.now().getYear();
        Matcher matcher = YEAR_PATTERN.matcher(query);
        StringBuffer normalized = new StringBuffer();
        boolean changed = false;
        while (matcher.find()) {
            String yearText = matcher.group(1);
            int year = Integer.parseInt(yearText);
            // 用户亲自写出的历史年份必须保留，只纠正模型额外引入的过期年份。
            if (year < currentYear && (userQuery == null || !userQuery.contains(yearText))) {
                matcher.appendReplacement(normalized, String.valueOf(currentYear));
                changed = true;
            }
        }
        matcher.appendTail(normalized);

        String result = changed ? normalized.toString() : query;
        if (!result.contains(String.valueOf(currentYear))) {
            result = result + " " + currentYear;
        }
        if (!result.toLowerCase().contains("latest") && !result.contains("最新")) {
            result = result + " latest";
        }
        return result.trim();
    }

    /**
     * 判断用户原始问题是否表达了“最新/当前/近期”的时效诉求。
     */
    private boolean isFreshnessQuestion(String userQuery) {
        if (userQuery == null || userQuery.isBlank()) {
            return false;
        }
        String lower = userQuery.toLowerCase();
        return lower.contains("recent")
                || lower.contains("latest")
                || lower.contains("current")
                || lower.contains("today")
                || lower.contains("now")
                || userQuery.contains("最近")
                || userQuery.contains("近期")
                || userQuery.contains("近段")
                || userQuery.contains("最新")
                || userQuery.contains("当前")
                || userQuery.contains("现在")
                || userQuery.contains("今天")
                || userQuery.contains("今日")
                || userQuery.contains("本周")
                || userQuery.contains("本月")
                || userQuery.contains("今年");
    }
}
