package com.stocksage.agent;

import com.stocksage.agent.intent.FineIntent;
import com.stocksage.agent.intent.IntentSignalSource;

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
 * @param fineIntent 细粒度意图枚举名
 * @param intentGroup 意图分组枚举名
 * @param timeSensitivity 时间敏感度枚举名
 * @param analysisDepth 分析深度枚举名
 * @param entities 仅包含有界、已抽取实体，不保存完整用户问题
 * @param sourceScores 各有限识别来源的融合分数
 * @param needsClarification 是否建议先向用户澄清
 * @param reasonCodes 有界、可审计的理由代码
 * @param signalDiagnostics 各实际识别来源的最高置信候选，不包含自由文本或实体
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
        long durationMs,
        String fineIntent,
        String intentGroup,
        String timeSensitivity,
        String analysisDepth,
        Map<String, String> entities,
        Map<String, Double> sourceScores,
        boolean needsClarification,
        List<String> reasonCodes,
        List<SignalSnapshot> signalDiagnostics
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
        fineIntent = bounded(fineIntent, 48);
        intentGroup = bounded(intentGroup, 48);
        timeSensitivity = bounded(timeSensitivity, 32);
        analysisDepth = bounded(analysisDepth, 32);
        entities = boundedEntities(entities);
        sourceScores = boundedScores(sourceScores);
        reasonCodes = reasonCodes == null
                ? List.of()
                : reasonCodes.stream().map(value -> bounded(value, 64)).filter(value -> !value.isBlank()).limit(12).toList();
        signalDiagnostics = signalDiagnostics == null ? List.of() : List.copyOf(signalDiagnostics);
    }

    /** 保留十八字段构造方式；旧调用没有逐来源候选观测。 */
    public RoutingDecisionMetadata(
            RoutingDecisionSource decisionSource, String rawRoute, PlanRoute route,
            String intentSummary, String rationale, double confidence, List<String> matchedSignals,
            int ragHitCount, String fallbackReason, long durationMs,
            String fineIntent, String intentGroup, String timeSensitivity, String analysisDepth,
            Map<String, String> entities, Map<String, Double> sourceScores,
            boolean needsClarification, List<String> reasonCodes
    ) {
        this(decisionSource, rawRoute, route, intentSummary, rationale, confidence,
                matchedSignals, ragHitCount, fallbackReason, durationMs,
                fineIntent, intentGroup, timeSensitivity, analysisDepth, entities, sourceScores,
                needsClarification, reasonCodes, List.of());
    }

    /** 保留原有十字段构造方式，避免已有 Trace/测试调用在迁移期失效。 */
    public RoutingDecisionMetadata(
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
        this(decisionSource, rawRoute, route, intentSummary, rationale, confidence,
                matchedSignals, ragHitCount, fallbackReason, durationMs,
                "", "", "", "", Map.of(), Map.of(), false, List.of());
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
        attributes.put("fineIntent", fineIntent);
        attributes.put("intentGroup", intentGroup);
        attributes.put("timeSensitivity", timeSensitivity);
        attributes.put("analysisDepth", analysisDepth);
        attributes.put("entities", entities);
        attributes.put("sourceScores", sourceScores);
        attributes.put("needsClarification", needsClarification);
        attributes.put("reasonCodes", reasonCodes);
        attributes.put("signalDiagnostics", signalDiagnostics.stream().map(signal -> Map.of(
                "source", signal.source().name(),
                "targetRoute", signal.targetRoute().name(),
                "fineIntent", signal.fineIntent().name(),
                "confidence", signal.confidence())).toList());
        return Map.copyOf(attributes);
    }

    /** 诊断只允许有限枚举和分数，不能携带识别器的 prompt、实体、理由或消歧问题。 */
    public record SignalSnapshot(IntentSignalSource source, PlanRoute targetRoute,
                                 FineIntent fineIntent, double confidence) {}

    private static Map<String, String> boundedEntities(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, String> bounded = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (bounded.size() < 8) {
                String safeKey = bounded(key, 32);
                String safeValue = bounded(value, 96);
                if (!safeKey.isBlank() && !safeValue.isBlank()) {
                    bounded.put(safeKey, safeValue);
                }
            }
        });
        return Map.copyOf(bounded);
    }

    private static Map<String, Double> boundedScores(Map<String, Double> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, Double> bounded = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (bounded.size() < 8) {
                String safeKey = bounded(key, 32);
                if (!safeKey.isBlank() && value != null && Double.isFinite(value)) {
                    bounded.put(safeKey, Math.max(0.0, Math.min(1.0, value)));
                }
            }
        });
        return Map.copyOf(bounded);
    }

    private static String bounded(String value, int maxLength) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return normalized.substring(0, Math.min(maxLength, normalized.length()));
    }
}
