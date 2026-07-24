package com.stocksage.agent;

import java.util.List;
import java.util.Map;

public record IntentDecision(
        IntentType primaryIntent,
        List<IntentType> secondaryIntents,
        Map<String, String> entities,
        String timeRange,
        boolean needsFreshData,
        boolean needsRag,
        boolean needsDeepResearch,
        PlanRoute suggestedRoute,
        String rationale,
        double confidence
) {
    public IntentDecision {
        primaryIntent = primaryIntent == null ? IntentType.UNKNOWN : primaryIntent;
        secondaryIntents = secondaryIntents == null ? List.of() : List.copyOf(secondaryIntents);
        entities = entities == null ? Map.of() : Map.copyOf(entities);
        timeRange = timeRange == null ? "" : timeRange;
        suggestedRoute = suggestedRoute == null ? PlanRoute.DIRECT : suggestedRoute;
        rationale = rationale == null ? "" : rationale.substring(0, Math.min(rationale.length(), 240));
        confidence = Math.max(0.0, Math.min(1.0, confidence));
    }
}
