package com.stocksage.agent.intent;

import com.stocksage.agent.PlanRoute;

import java.util.List;
import java.util.Map;

/** 融合后的唯一意图决策；它直接携带经过校验的五选一目标路由。 */
public record IntentDecision(
        FineIntent fineIntent,
        IntentGroup intentGroup,
        PlanRoute targetRoute,
        double confidence,
        TimeSensitivity timeSensitivity,
        AnalysisDepth analysisDepth,
        Map<String, String> entities,
        String resolvedQuery,
        Map<IntentSignalSource, Double> sourceScores,
        boolean needsClarification,
        List<String> reasonCodes
) {
    public IntentDecision {
        fineIntent = fineIntent == null ? FineIntent.UNKNOWN : fineIntent;
        intentGroup = intentGroup == null ? IntentGroup.UNKNOWN : intentGroup;
        targetRoute = targetRoute == null ? PlanRoute.DIRECT : targetRoute;
        // 澄清是执行不变量：任何不确定决定都只能停在无副作用的 DIRECT 路由。
        if (needsClarification) {
            targetRoute = PlanRoute.DIRECT;
        }
        confidence = IntentBounds.confidence(confidence);
        timeSensitivity = timeSensitivity == null ? TimeSensitivity.UNSPECIFIED : timeSensitivity;
        analysisDepth = analysisDepth == null ? AnalysisDepth.UNSPECIFIED : analysisDepth;
        entities = IntentBounds.entities(entities);
        resolvedQuery = IntentBounds.text(resolvedQuery, IntentBounds.MAX_RESOLVED_QUERY_CHARS);
        sourceScores = IntentBounds.sourceScores(sourceScores);
        reasonCodes = IntentBounds.reasonCodes(reasonCodes);
    }
}
