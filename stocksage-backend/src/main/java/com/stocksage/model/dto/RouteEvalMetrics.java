package com.stocksage.model.dto;

/** Per-route classification metrics, following EchoMind's per-class eval shape. */
public record RouteEvalMetrics(
        double precision,
        double recall,
        double f1,
        int expectedCount,
        int predictedCount
) {
}
