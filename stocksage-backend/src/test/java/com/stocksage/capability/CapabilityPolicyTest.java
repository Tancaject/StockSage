package com.stocksage.capability;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityPolicyTest {

    private final CapabilityPolicy policy = new CapabilityPolicy();

    @Test
    void deniesCapabilityMissingFromTheSelectedSkillAllowlist() {
        CapabilityDescriptor descriptor = descriptor(CapabilityDescriptor.RiskLevel.READ_ONLY);
        CapabilityInvocationContext context = new CapabilityInvocationContext(
                "user", 1L, "trace", "skill", Set.of(), Instant.now().plusSeconds(5));

        assertThatThrownBy(() -> policy.authorize(descriptor, context))
                .isInstanceOf(CapabilityException.class)
                .extracting(error -> ((CapabilityException) error).reason())
                .isEqualTo(CapabilityException.Reason.DENIED);
    }

    @Test
    void deniesWriteRiskEvenWhenTheSkillNamesTheCapability() {
        CapabilityDescriptor descriptor = descriptor(CapabilityDescriptor.RiskLevel.WRITE);
        CapabilityInvocationContext context = new CapabilityInvocationContext(
                "user", 1L, "trace", "skill", Set.of(descriptor.id()), Instant.now().plusSeconds(5));

        assertThatThrownBy(() -> policy.authorize(descriptor, context))
                .isInstanceOf(CapabilityException.class)
                .extracting(error -> ((CapabilityException) error).reason())
                .isEqualTo(CapabilityException.Reason.DENIED);
    }

    private CapabilityDescriptor descriptor(CapabilityDescriptor.RiskLevel risk) {
        return new CapabilityDescriptor(
                "test.capability", CapabilityDescriptor.ProviderType.LOCAL,
                "test", "test", risk, 1000, 1024, true);
    }
}
