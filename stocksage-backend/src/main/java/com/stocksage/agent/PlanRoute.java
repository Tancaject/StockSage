package com.stocksage.agent;

import java.util.Locale;
import java.util.Optional;

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

    /**
     * 把文本收敛到已知路由，未知值安全回到 DIRECT。
     *
     * @param route 模型或历史调用给出的路由文本
     * @return 后端可执行的有限路由
     */
    public static PlanRoute normalize(String route) {
        return parse(route).orElse(DIRECT);
    }

    /**
     * 严格解析路由；用于区分模型真的选择 DIRECT，还是输出了非法路由后被安全归一化。
     *
     * @param route 待解析文本
     * @return 合法五选一路由；空值或未知值为空
     */
    public static Optional<PlanRoute> parse(String route) {
        if (route == null || route.isBlank()) {
            return Optional.empty();
        }
        String normalized = route.trim().toUpperCase(Locale.ROOT);
        try {
            return Optional.of(PlanRoute.valueOf(normalized));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
