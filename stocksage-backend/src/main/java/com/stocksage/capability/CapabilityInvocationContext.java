package com.stocksage.capability;

import java.time.Instant;
import java.util.Set;

/** Request-scoped identity, policy and deadline supplied by the selected Skill. */
public record CapabilityInvocationContext(
        String userId,
        Long conversationId,
        String traceId,
        String skillId,
        Set<String> allowedCapabilities,
        Instant deadline
) {

    public CapabilityInvocationContext {
        allowedCapabilities = allowedCapabilities == null ? Set.of() : Set.copyOf(allowedCapabilities);
        deadline = deadline == null ? Instant.now() : deadline;
    }
}
