package com.stocksage.agent;

/**
 * 路由模型允许返回的最小结构化结果。
 *
 * <p>自由文本 intent 只解释模型理解，不引入第二套路由类型；只有归一化后的 {@code route}
 * 影响执行。所有诊断文本和置信度都在构造时限界，不保存原始 prompt 或隐藏思维链。</p>
 *
 * @param intentSummary 有界意图摘要
 * @param rawRoute 模型原始路由词
 * @param route 后端归一化后的稳定路由
 * @param rationale 有界选择理由
 * @param confidence 归一化到 0 到 1 的置信度
 */
public record RouteDecision(
        String intentSummary,
        String rawRoute,
        PlanRoute route,
        String rationale,
        double confidence
) {
    /** 收敛空值、文本长度和置信度范围。 */
    public RouteDecision {
        intentSummary = bounded(intentSummary, 160);
        rawRoute = bounded(rawRoute, 48);
        route = route == null ? PlanRoute.DIRECT : route;
        rationale = bounded(rationale, 240);
        confidence = Math.max(0.0, Math.min(1.0, confidence));
    }

    private static String bounded(String value, int maxLength) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return normalized.substring(0, Math.min(maxLength, normalized.length()));
    }
}
