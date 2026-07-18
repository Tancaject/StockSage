package com.stocksage.skill;

import com.stocksage.capability.CapabilityDescriptor;
import com.stocksage.capability.CapabilityRegistry;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Pattern;

/** Rejects malformed, unsafe or unknown workflow references during application startup. */
@Component
public class SkillValidator {

    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9-]{2,63}");
    private static final Set<CapabilityDescriptor.RiskLevel> V1_RISKS = Set.of(
            CapabilityDescriptor.RiskLevel.READ_ONLY,
            CapabilityDescriptor.RiskLevel.EXTERNAL_READ
    );

    private final CapabilityRegistry capabilityRegistry;

    public SkillValidator(CapabilityRegistry capabilityRegistry) {
        this.capabilityRegistry = capabilityRegistry;
    }

    public void validate(SkillDefinition skill) {
        if (skill == null || skill.id() == null || !ID_PATTERN.matcher(skill.id()).matches()) {
            throw new IllegalStateException("Invalid Skill id");
        }
        if (skill.version() < 1 || skill.displayName() == null || skill.displayName().isBlank()) {
            throw new IllegalStateException("Invalid Skill metadata: " + skill.id());
        }
        if (skill.routes().isEmpty() || skill.executionMode() == null || skill.minimumModelTier() == null
                || skill.policy() == null || skill.steps().isEmpty()) {
            throw new IllegalStateException("Incomplete Skill definition: " + skill.id());
        }
        if (skill.policy().maxCapabilityCalls() < 1 || skill.policy().maxCapabilityCalls() > 20
                || skill.policy().maxDurationSeconds() < 1 || skill.policy().maxDurationSeconds() > 120) {
            throw new IllegalStateException("Skill limits are out of bounds: " + skill.id());
        }
        if (!V1_RISKS.containsAll(skill.policy().allowedRiskLevels())) {
            throw new IllegalStateException("Skill requests a forbidden risk level: " + skill.id());
        }

        long capabilitySteps = 0;
        for (SkillDefinition.SkillStep step : skill.steps()) {
            if (step.type() == null) {
                throw new IllegalStateException("Skill step type is missing: " + skill.id());
            }
            if (step.type() == SkillDefinition.StepType.CAPABILITY) {
                capabilitySteps++;
                validateCapability(skill, step.capability());
                if (step.fallbackCapability() != null && !step.fallbackCapability().isBlank()) {
                    validateCapability(skill, step.fallbackCapability());
                }
            }
            if (step.type() == SkillDefinition.StepType.AGENT) {
                for (String capabilityId : step.allowedCapabilities()) {
                    validateCapability(skill, capabilityId);
                }
            }
        }
        if (capabilitySteps > skill.policy().maxCapabilityCalls()) {
            throw new IllegalStateException("Skill has more capability steps than its call limit: " + skill.id());
        }
    }

    private void validateCapability(SkillDefinition skill, String capabilityId) {
        if (capabilityId == null || capabilityId.isBlank()) {
            throw new IllegalStateException("Skill contains a blank capability reference: " + skill.id());
        }
        CapabilityDescriptor descriptor = capabilityRegistry.require(capabilityId).descriptor();
        if (!skill.policy().allowedRiskLevels().contains(descriptor.riskLevel())) {
            throw new IllegalStateException("Skill policy does not allow capability risk: "
                    + skill.id() + " -> " + capabilityId);
        }
    }
}
