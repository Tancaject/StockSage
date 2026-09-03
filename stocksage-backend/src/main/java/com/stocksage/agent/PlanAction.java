package com.stocksage.agent;

/**
 * 执行计划中允许出现的确定性动作。
 *
 * <p>此前计划动作是裸字符串，Coordinator 的默认计划、预取编排的 switch 分支和前端追踪标签
 * 三处靠肉眼对齐。收敛为枚举后，标签字符串（{@link #label()}）仍与历史值逐字一致，
 * 因此链路追踪与 SSE 输出不变；但编译器能兜住拼写漂移。</p>
 */
public enum PlanAction {

    FUNDAMENTALS_AGENT("Fundamentals Agent"),
    MARKET_AGENT("Market Agent"),
    NEWS_AGENT("News Agent"),
    BULL_RESEARCHER("Bull Researcher"),
    BEAR_RESEARCHER("Bear Researcher"),
    RESEARCH_MANAGER("Research Manager"),
    KNOWLEDGE_RETRIEVAL("Knowledge Retrieval"),
    SEARCH_STOCKS("searchStocks"),
    GET_STOCK_KLINE("getStockKLine"),
    GET_FINANCIAL_METRICS("getFinancialMetrics"),
    GET_TECHNICAL_INDICATORS("getTechnicalIndicators"),
    GET_FINANCIAL_REPORTS("getFinancialReports"),
    GET_STRUCTURED_FINANCIALS("getStructuredFinancials"),
    SEARCH_COMPANY_REPORTS("searchCompanyReports"),
    SEARCH_NEWS("searchNews"),
    WEB_SEARCH("webSearch"),
    GET_MARKET_OVERVIEW("getMarketOverview"),
    FINAL_ANSWER("Final Answer");

    /** 与现有 Trace、SSE 和前端协议兼容的展示标签。 */
    private final String label;

    PlanAction(String label) {
        this.label = label;
    }

    /** 面向追踪面板与前端展示的历史标签字符串。 */
    public String label() {
        return label;
    }

    /** 是否代表计划中的领域/研究角色，而不是工具或最终回答步骤。 */
    public boolean isAgentRole() {
        return switch (this) {
            case FUNDAMENTALS_AGENT, MARKET_AGENT, NEWS_AGENT,
                    BULL_RESEARCHER, BEAR_RESEARCHER, RESEARCH_MANAGER -> true;
            default -> false;
        };
    }

}
