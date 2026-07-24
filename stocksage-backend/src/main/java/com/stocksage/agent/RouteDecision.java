package com.stocksage.agent;

/**
 * Minimal structured output produced by the routing LLM.
 *
 * <p>The free-form intent summary explains what the model understood without
 * introducing a second typed intent taxonomy. Only {@code route} affects execution.</p>
 */
public record RouteDecision(
        String intentSummary,
        String rawRoute,
        PlanRoute route,
        String rationale,
        double confidence
) {
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
