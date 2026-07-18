package com.stocksage.capability;

/** Normalized result returned by local and MCP capability adapters. */
public record CapabilityResult(
        String capabilityId,
        String providerId,
        Status status,
        String content,
        int resultBytes,
        long durationMs
) {

    public enum Status {
        SUCCESS,
        TRUNCATED
    }
}
