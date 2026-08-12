package com.stocksage.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Trace、SSE 与指标共享的安全结构化路由元数据。
 *
 * <p>对象只包含最终路由和有界诊断字段，不保存原始 prompt、完整 RAG chunk、用户问题、
 * 提供方异常明细或隐藏思维链。Coordinator 创建它，Observer/Trace 只消费该不可变快照。</p>
 *
 * @param decisionSource 路由模型或确定性兜底
 * @param rawRoute 模型/规则给出的原始路由词
 * @param route 最终后端路由
 * @param intentSummary 有界意图摘要
 * @param rationale 有界路由理由
 * @param confidence 0 到 1 的置信度
 * @param matchedSignals 有限规则/置信度信号
 * @param ragHitCount RAG 命中数
 * @param fallbackReason 兜底原因代码
 * @param durationMs 路由耗时
 */
public record RoutingDecisionMetadata(
        RoutingDecisionSource decisionSource,
        String rawRoute,
        PlanRoute route,
        String intentSummary,
        String rationale,
        double confidence,
        List<String> matchedSignals,
        int ragHitCount,
        String fallbackReason,
        long durationMs
) {
    public RoutingDecisionMetadata {
        decisionSource = decisionSource == null ? RoutingDecisionSource.DETERMINISTIC_FALLBACK : decisionSource;
        rawRoute = bounded(rawRoute, 48);
        route = route == null ? PlanRoute.DIRECT : route;
        intentSummary = bounded(intentSummary, 160);
        rationale = bounded(rationale, 240);
        confidence = Math.max(0.0, Math.min(1.0, confidence));
        matchedSignals = matchedSignals == null ? List.of() : List.copyOf(matchedSignals);
        ragHitCount = Math.max(0, ragHitCount);
        fallbackReason = fallbackReason == null ? "" : fallbackReason;
        durationMs = Math.max(0L, durationMs);
    }

    /** @return 是否由确定性兜底产生 */
    public boolean fallback() {
        return decisionSource == RoutingDecisionSource.DETERMINISTIC_FALLBACK;
    }

    /** @return 指标使用的 success 或 fallback */
    public String outcome() {
        return fallback() ? "fallback" : "success";
    }

    /**
     * 转为 AgentStep/Phoenix 可安全持久化的属性 Map。
     *
     * @return 不可变、有界属性
     */
    public Map<String, Object> toAttributes() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("kind", "routing-decision");
        attributes.put("source", decisionSource.name());
        attributes.put("rawRoute", rawRoute);
        attributes.put("route", route.name());
        attributes.put("intentSummary", intentSummary);
        attributes.put("rationale", rationale);
        attributes.put("confidence", confidence);
        attributes.put("matchedSignals", matchedSignals);
        attributes.put("ragHitCount", ragHitCount);
        attributes.put("fallbackReason", fallbackReason);
        attributes.put("outcome", outcome());
        attributes.put("durationMs", durationMs);
        return Map.copyOf(attributes);
    }

    private static String bounded(String value, int maxLength) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return normalized.substring(0, Math.min(maxLength, normalized.length()));
    }
}
