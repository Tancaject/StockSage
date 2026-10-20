package com.stocksage.agent;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 普通路线中用户明确要求的数据类别。
 *
 * <p>需求只从问题里明确提到的内容推导，不把路由的固定计划整套当作需求；
 * 每类需求只能由指定工具的合格证据满足，例如 K 线不能满足技术指标，网页搜索摘要不能满足财报原文。</p>
 */
public enum DataRequirement {

    PRICE_HISTORY(Pattern.compile("k\\s?线|日线|周线|月线|小时线|分钟线|股价|走势|涨跌|行情|candles?|\\bbars\\b|\\bprice\\b"),
            Set.of("getStockKLine", "getIbkrHistoricalBars")),
    VALUATION(Pattern.compile("估值|市盈率|市净率|市销率|市值|(?<![a-z])p/?[eb](?![a-z])|valuation|ev/ebitda"),
            Set.of("getFinancialMetrics", "getStructuredFinancials")),
    TECHNICAL(Pattern.compile("macd|rsi|kdj|均线|技术指标|布林|bollinger|(?<![a-z])ma\\d+|moving average"),
            Set.of("getTechnicalIndicators")),
    FINANCIAL_STATEMENTS(Pattern.compile("营收|营业收入|净利润|毛利率|现金流|资产负债|财务报表|财务数据|revenue|net income|cash flow"
            + "|gross margin|balance sheet|income statement|(?<![a-z])eps(?![a-z])"),
            Set.of("getFinancialReports", "getStructuredFinancials")),
    /** 财报原文只由知识库召回满足，见 {@link #isSatisfied}。 */
    FILING_TEXT(Pattern.compile("风险因素|risk factor|管理层讨论|md&a|(?<![a-z0-9])10-?[kq](?![a-z0-9])|披露|disclos"),
            Set.of()),
    NEWS(Pattern.compile("新闻|消息|事件|最新动态|headline|(?<![a-z])news(?![a-z])"),
            Set.of("searchNews", "webSearch", "local.news.searchNews", "local.news.webSearch"));

    private final Pattern mention;
    private final Set<String> tools;

    DataRequirement(Pattern mention, Set<String> tools) {
        this.mention = mention;
        this.tools = tools;
    }

    /** 问题中明确提到的数据需求。 */
    public static Set<DataRequirement> explicitIn(String query) {
        String text = query == null ? "" : query.toLowerCase(Locale.ROOT);
        Set<DataRequirement> found = EnumSet.noneOf(DataRequirement.class);
        for (DataRequirement requirement : values()) {
            if (requirement.mention.matcher(text).find()) found.add(requirement);
        }
        return Set.copyOf(found);
    }

    /**
     * 判断某个标的的证据是否满足本需求。
     *
     * @param observations 该标的的证据观察行（含 tool、status、gap、citable）
     * @param filingTextRetrieved 知识库是否召回了该标的的财报正文
     */
    public boolean isSatisfied(Iterable<Map<String, Object>> observations, boolean filingTextRetrieved) {
        if (this == FILING_TEXT) return filingTextRetrieved;
        for (Map<String, Object> row : observations) {
            if (!tools.contains(String.valueOf(row.get("tool")))) continue;
            // 时效无法确认已由原有验收降为 DEGRADED，不等于数据缺失；其余 gap（期间、份数不符等）才算未满足。
            Object gap = row.get("gap");
            boolean onlyFreshnessGap = gap != null && gap.equals(row.get("freshnessReason"));
            boolean usable = Boolean.TRUE.equals(row.get("citable")) && ("".equals(gap) || onlyFreshnessGap);
            // 新闻正常查无结果也是一个确定的答案，回答中说明即可。
            boolean newsChecked = this == NEWS && "NO_RESULTS".equals(row.get("status"));
            if (usable || newsChecked) return true;
        }
        return false;
    }

    /** 计划中是否有能产出本需求证据的步骤；财报原文由知识库检索提供，不依赖取数步骤。 */
    public boolean coveredBy(java.util.Collection<PlanAction> actions) {
        return switch (this) {
            case PRICE_HISTORY -> actions.contains(PlanAction.GET_STOCK_KLINE);
            case VALUATION -> actions.contains(PlanAction.GET_FINANCIAL_METRICS);
            case TECHNICAL -> actions.contains(PlanAction.GET_TECHNICAL_INDICATORS);
            case FINANCIAL_STATEMENTS -> actions.contains(PlanAction.GET_FINANCIAL_REPORTS)
                    || actions.contains(PlanAction.GET_STRUCTURED_FINANCIALS);
            case FILING_TEXT -> true;
            case NEWS -> actions.contains(PlanAction.SEARCH_NEWS) || actions.contains(PlanAction.WEB_SEARCH);
        };
    }

    /** 回答和 Trace 中使用的中文名。 */
    public String label() {
        return switch (this) {
            case PRICE_HISTORY -> "价格走势";
            case VALUATION -> "估值";
            case TECHNICAL -> "技术指标";
            case FINANCIAL_STATEMENTS -> "财务报表";
            case FILING_TEXT -> "财报原文";
            case NEWS -> "新闻";
        };
    }
}
