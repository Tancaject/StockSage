package com.stocksage.model.dto;

import java.util.List;
import java.util.Map;

public record PlannerEvalResponse(
        String schemaVersion,
        PlannerEvalMode mode,
        String status,
        int totalCases,
        int passedCases,
        int criticalFailures,
        double routeAccuracy,
        double macroF1,
        Map<String, RouteEvalMetrics> perRoute,
        double requiredActionRecall,
        double forbiddenActionRate,
        double executableRate,
        long durationMs,
        String checkedAt,
        List<PlannerEvalResult> results
) {
}
