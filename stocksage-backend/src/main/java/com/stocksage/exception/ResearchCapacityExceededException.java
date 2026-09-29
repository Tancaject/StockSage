package com.stocksage.exception;

/** Local execution admission failure, not a provider outage eligible for offline publication. */
public final class ResearchCapacityExceededException extends IllegalStateException {
    public ResearchCapacityExceededException(String stage, Throwable cause) {
        super("执行容量不足（" + stage + "），本阶段未启动；请稍后重试。", cause);
    }

    public static void rethrowIfPresent(Throwable failure) {
        ResearchCapacityExceededException capacity = find(failure);
        if (capacity != null) throw capacity;
    }

    public static ResearchCapacityExceededException find(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ResearchCapacityExceededException capacity) return capacity;
        }
        return null;
    }
}
