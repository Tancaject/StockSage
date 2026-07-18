package com.stocksage.capability;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

/** V1 policy: explicit Skill allowlist plus read-only risk classes only. */
@Component
public class CapabilityPolicy {

    private static final Set<CapabilityDescriptor.RiskLevel> V1_ALLOWED_RISKS = EnumSet.of(
            CapabilityDescriptor.RiskLevel.READ_ONLY,
            CapabilityDescriptor.RiskLevel.EXTERNAL_READ
    );

    public void authorize(CapabilityDescriptor descriptor, CapabilityInvocationContext context) {
        if (!descriptor.enabled()) {
            throw new CapabilityException(CapabilityException.Reason.DENIED,
                    "Capability is disabled: " + descriptor.id());
        }
        if (context == null || !context.allowedCapabilities().contains(descriptor.id())) {
            throw new CapabilityException(CapabilityException.Reason.DENIED,
                    "Skill is not allowed to invoke capability: " + descriptor.id());
        }
        if (!V1_ALLOWED_RISKS.contains(descriptor.riskLevel())) {
            throw new CapabilityException(CapabilityException.Reason.DENIED,
                    "Capability risk is not allowed in V1: " + descriptor.id());
        }
        if (!context.deadline().isAfter(Instant.now())) {
            throw new CapabilityException(CapabilityException.Reason.TIMEOUT,
                    "Skill deadline already expired before invoking: " + descriptor.id());
        }
    }
}
