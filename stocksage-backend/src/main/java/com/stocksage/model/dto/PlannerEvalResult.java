package com.stocksage.model.dto;

import com.stocksage.agent.PlanAction;
import com.stocksage.agent.PlanRoute;

import java.util.List;

public record PlannerEvalResult(
        String id,
        PlanRoute expectedRoute,
        PlanRoute actualRoute,
        List<PlanAction> plannedActions,
        List<PlanAction> missingRequiredActions,
        List<PlanAction> matchedForbiddenActions,
        boolean critical,
        boolean passed,
        boolean executable,
        long durationMs,
        String errorCode,
        String decisionSource,
        String rawRoute,
        String intentSummary,
        String rationale,
        double confidence,
        String fallbackReason
) {
}
