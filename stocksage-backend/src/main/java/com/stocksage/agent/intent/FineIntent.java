package com.stocksage.agent.intent;

import com.stocksage.agent.PlanRoute;

/**
 * 用户想完成的细粒度投研任务。
 *
 * <p>它描述语义目的，不拥有工具、动作或模型选择权；最终可执行边界仍是
 * {@link com.stocksage.agent.PlanRoute}。</p>
 */
public enum FineIntent {
    KNOWLEDGE_EXPLANATION,
    MARKET_DATA,
    TECHNICAL_ANALYSIS,
    FUNDAMENTALS,
    NEWS_EVENT,
    COMPARISON,
    PORTFOLIO_DIAGNOSIS,
    DEEP_RESEARCH,
    UNKNOWN;

    /** 识别来源未给出细粒度意图时，按可执行路由给出默认意图。 */
    public static FineIntent forRoute(PlanRoute route) {
        return switch (route) {
            case DIRECT -> KNOWLEDGE_EXPLANATION;
            case MARKET -> MARKET_DATA;
            case FUNDAMENTALS -> FUNDAMENTALS;
            case NEWS -> NEWS_EVENT;
            case DEEP -> DEEP_RESEARCH;
        };
    }
}
