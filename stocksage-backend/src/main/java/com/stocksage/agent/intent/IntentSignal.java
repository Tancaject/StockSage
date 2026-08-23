package com.stocksage.agent.intent;

import com.stocksage.agent.PlanRoute;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 单个识别来源产生的不可变意图证据。
 *
 * <p>{@code rawRoute} 只用于统计非法原始输出；执行方只能读取已经收敛到五个枚举值之一的
 * {@code targetRoute}。该对象不包含动作或工具名称。</p>
 */
public record IntentSignal(
        FineIntent fineIntent,
        IntentGroup intentGroup,
        String rawRoute,
        PlanRoute targetRoute,
        IntentSignalSource source,
        double confidence,
        TimeSensitivity timeSensitivity,
        AnalysisDepth analysisDepth,
        Map<IntentSignalSource, Double> sourceScores,
        Map<String, String> entities,
        String resolvedQuery,
        String rationale,
        List<String> reasonCodes
) {
    public IntentSignal {
        fineIntent = fineIntent == null ? FineIntent.UNKNOWN : fineIntent;
        intentGroup = intentGroup == null ? IntentGroup.UNKNOWN : intentGroup;
        rawRoute = IntentBounds.text(rawRoute, IntentBounds.MAX_RAW_ROUTE_CHARS);
        targetRoute = targetRoute == null ? PlanRoute.DIRECT : targetRoute;
        source = source == null ? IntentSignalSource.FALLBACK : source;
        confidence = IntentBounds.confidence(confidence);
        timeSensitivity = timeSensitivity == null ? TimeSensitivity.UNSPECIFIED : timeSensitivity;
        analysisDepth = analysisDepth == null ? AnalysisDepth.UNSPECIFIED : analysisDepth;

        EnumMap<IntentSignalSource, Double> scores = new EnumMap<>(IntentSignalSource.class);
        scores.putAll(IntentBounds.sourceScores(sourceScores));
        scores.merge(source, confidence, Math::max);
        sourceScores = IntentBounds.sourceScores(scores);

        entities = IntentBounds.entities(entities);
        resolvedQuery = IntentBounds.text(resolvedQuery, IntentBounds.MAX_RESOLVED_QUERY_CHARS);
        rationale = IntentBounds.text(rationale, IntentBounds.MAX_RATIONALE_CHARS);
        reasonCodes = IntentBounds.reasonCodes(reasonCodes);
    }

    /** @return 原始字符串本身是否是合法的五选一路由名 */
    public boolean rawRouteValid() {
        if (rawRoute.isBlank()) {
            return false;
        }
        try {
            PlanRoute.valueOf(rawRoute.trim().toUpperCase(Locale.ROOT));
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    /** @return 合法原始路由是否与最终可执行路由一致 */
    public boolean rawRouteMatchesTarget() {
        return rawRouteValid()
                && PlanRoute.valueOf(rawRoute.trim().toUpperCase(Locale.ROOT)) == targetRoute;
    }
}
