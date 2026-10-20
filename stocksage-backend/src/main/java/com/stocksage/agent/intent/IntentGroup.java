package com.stocksage.agent.intent;

import com.stocksage.agent.PlanRoute;

/** 供评测和可观测性使用的粗粒度意图分组。 */
public enum IntentGroup {
    KNOWLEDGE,
    MARKET,
    FUNDAMENTALS,
    NEWS,
    RESEARCH,
    UNKNOWN;

    /** 按可执行路由给出默认意图分组。 */
    public static IntentGroup forRoute(PlanRoute route) {
        return switch (route) {
            case DIRECT -> KNOWLEDGE;
            case MARKET -> MARKET;
            case FUNDAMENTALS -> FUNDAMENTALS;
            case NEWS -> NEWS;
            case DEEP -> RESEARCH;
        };
    }
}
