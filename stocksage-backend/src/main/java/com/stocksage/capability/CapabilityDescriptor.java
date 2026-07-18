package com.stocksage.capability;

import java.time.Duration;
import java.util.Objects;

/**
 * Version-controlled policy metadata for one executable capability.
 *
 * <p>Remote MCP metadata never creates a descriptor by itself. A capability is executable only
 * when this local descriptor and a matching adapter are both present.</p>
 */
public record CapabilityDescriptor(
        String id,
        ProviderType providerType,
        String providerId,
        String nativeName,
        RiskLevel riskLevel,
        long timeoutMs,
        int maxResultBytes,
        boolean enabled
) {

    public CapabilityDescriptor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(providerType, "providerType");
        Objects.requireNonNull(providerId, "providerId");
        Objects.requireNonNull(nativeName, "nativeName");
        Objects.requireNonNull(riskLevel, "riskLevel");
        if (id.isBlank() || providerId.isBlank() || nativeName.isBlank()) {
            throw new IllegalArgumentException("Capability id/provider/nativeName must not be blank");
        }
        if (timeoutMs < 1 || maxResultBytes < 1) {
            throw new IllegalArgumentException("Capability timeout and result limit must be positive: " + id);
        }
    }

    public Duration timeout() {
        return Duration.ofMillis(timeoutMs);
    }

    public enum ProviderType {
        LOCAL,
        MCP
    }

    public enum RiskLevel {
        READ_ONLY,
        EXTERNAL_READ,
        SENSITIVE_READ,
        WRITE
    }
}
