package com.stocksage.exception;

/** A terminal run constraint, never a provider outage eligible for offline fallback. */
public final class ResearchBudgetExceededException extends IllegalStateException {
    public enum Reason { DEADLINE, MODEL_CALL_LIMIT, TOKEN_LIMIT, BUDGET_UNAVAILABLE }

    private final Reason reason;

    public ResearchBudgetExceededException(Long runId, Reason reason) {
        super("研究任务 " + runId + " 的运行预算已用尽或无法确认（" + reason
                + "）；执行已停止，请检查预算配置或发起新的研究。");
        this.reason = reason;
    }

    public Reason reason() { return reason; }

    public static long remainingMillis(Long runId, Long deadlineEpochMs) {
        if (deadlineEpochMs == null || deadlineEpochMs <= 0) {
            throw new ResearchBudgetExceededException(runId, Reason.BUDGET_UNAVAILABLE);
        }
        long remaining = deadlineEpochMs - System.currentTimeMillis();
        if (remaining <= 0) throw new ResearchBudgetExceededException(runId, Reason.DEADLINE);
        return remaining;
    }

    public static void rethrowIfPresent(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ResearchBudgetExceededException budget) throw budget;
        }
    }
}
