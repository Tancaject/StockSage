package com.stocksage.model.dto;

import com.stocksage.exception.ResearchBudgetExceededException;

/** Explicit attribution shared by parallel model calls; never inferred from a thread. */
public record ModelInvocationContext(Long runId, int attempt, String leaseToken, String traceId,
                                     String evidenceSnapshotId, long deadlineEpochMs, String inputSha256) {
    public ModelInvocationContext(Long runId, int attempt, String leaseToken, String traceId,
                                  String evidenceSnapshotId, long deadlineEpochMs) {
        this(runId, attempt, leaseToken, traceId, evidenceSnapshotId, deadlineEpochMs, null);
    }

    public ModelInvocationContext {
        if (runId == null || runId <= 0 || attempt <= 0 || leaseToken == null || leaseToken.isBlank()
                || deadlineEpochMs <= 0) {
            throw new IllegalArgumentException("Model invocation requires a run, attempt and lease owner");
        }
        boolean snapshot = evidenceSnapshotId != null && !evidenceSnapshotId.isBlank() && inputSha256 == null;
        boolean preEvidence = evidenceSnapshotId == null && inputSha256 != null && inputSha256.matches("[0-9a-f]{64}");
        if (!snapshot && !preEvidence) {
            throw new IllegalArgumentException("Model invocation requires exactly one evidence snapshot or SHA-256 input identity");
        }
    }

    public long remainingMillis() {
        return ResearchBudgetExceededException.remainingMillis(runId, deadlineEpochMs);
    }
}
