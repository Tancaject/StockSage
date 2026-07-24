package com.stocksage.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Safe, structured routing metadata shared by traces, SSE and metrics.
 *
 * <p>The object contains the selected route plus bounded diagnostic text from the
 * router. It never stores the raw prompt, complete RAG chunks, user query, provider
 * exception message, or hidden chain of thought.</p>
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
