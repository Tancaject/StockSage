package com.stocksage.capability;

/** Fail-closed error from capability policy, discovery or execution. */
public class CapabilityException extends RuntimeException {

    private final Reason reason;

    public CapabilityException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public CapabilityException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        UNKNOWN,
        DENIED,
        UNAVAILABLE,
        TIMEOUT,
        FAILED
    }
}
