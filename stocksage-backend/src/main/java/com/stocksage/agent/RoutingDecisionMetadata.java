package com.stocksage.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Safe, structured routing metadata shared by traces, SSE and metrics.
 *
 * <p>The object deliberately contains only bounded enums, stable signal codes and
 * counts. User queries, model rationales and exception messages must not be stored
 * here.</p>
 */
public record RoutingDecisionMetadata(
        RoutingDecisionSource decisionSource,
        String primaryIntent,
        List<String> secondaryIntents,
        PlanRoute route,
        List<String> matchedSignals,
        int ragHitCount,
        String fallbackReason,
        long durationMs
) {
    public RoutingDecisionMetadata {
        decisionSource = decisionSource == null ? RoutingDecisionSource.DETERMINISTIC_FALLBACK : decisionSource;
        route = route == null ? PlanRoute.DIRECT : route;
        primaryIntent = primaryIntent == null || primaryIntent.isBlank() ? route.name() : primaryIntent;
        secondaryIntents = secondaryIntents == null ? List.of() : List.copyOf(secondaryIntents);
        matchedSignals = matchedSignals == null ? List.of() : List.copyOf(matchedSignals);
        ragHitCount = Math.max(0, ragHitCount);
        fallbackReason = fallbackReason == null ? "" : fallbackReason;
        durationMs = Math.max(0L, durationMs);
    }

    public boolean fallback() {
        return decisionSource == RoutingDecisionSource.DETERMINISTIC_FALLBACK;
    }

    public String outcome() {
        return fallback() ? "fallback" : "success";
    }

    public Map<String, Object> toAttributes() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("kind", "routing-decision");
        attributes.put("source", decisionSource.name());
        attributes.put("primaryIntent", primaryIntent);
        attributes.put("secondaryIntents", secondaryIntents);
        attributes.put("route", route.name());
        attributes.put("matchedSignals", matchedSignals);
        attributes.put("ragHitCount", ragHitCount);
        attributes.put("fallbackReason", fallbackReason);
        attributes.put("outcome", outcome());
        attributes.put("durationMs", durationMs);
        return Map.copyOf(attributes);
    }
}
