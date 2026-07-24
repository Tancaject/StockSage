package com.stocksage.model.dto;

import java.util.List;

public record PlannerEvalResponse(
        String schemaVersion,
        PlannerEvalMode mode,
        String status,
        int totalCases,
        int passedCases,
        int criticalFailures,
        double routeAccuracy,
        double requiredActionRecall,
        double forbiddenActionRate,
        double executableRate,
        long durationMs,
        String checkedAt,
        List<PlannerEvalResult> results
) {
}
