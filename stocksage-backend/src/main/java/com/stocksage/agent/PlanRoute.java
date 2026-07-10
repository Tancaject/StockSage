package com.stocksage.agent;

import java.util.Locale;

/**
 * Coordinator 支持的路由分支。
 *
 * <p>名称与历史字符串值逐字一致（追踪面板的"分层路线："文本依赖它）。</p>
 */
public enum PlanRoute {

    DIRECT,
    MARKET,
    FUNDAMENTALS,
    NEWS,
    DEEP;

    /** 只允许已知路由，未知值回到 DIRECT。 */
    public static PlanRoute normalize(String route) {
        String normalized = route == null ? "DIRECT" : route.trim().toUpperCase(Locale.ROOT);
        try {
            return PlanRoute.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            return DIRECT;
        }
    }
}
