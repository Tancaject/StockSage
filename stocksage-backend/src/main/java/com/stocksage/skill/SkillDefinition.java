package com.stocksage.skill;

import com.stocksage.agent.ModelTier;
import com.stocksage.agent.PlanRoute;
import com.stocksage.capability.CapabilityDescriptor;

import java.util.List;
import java.util.Set;

/** Version-controlled declarative workflow; it can reference only registered step types. */
public record SkillDefinition(
        String id,
        int version,
        String displayName,
        boolean enabled,
        Set<PlanRoute> routes,
        ExecutionMode executionMode,
        ModelTier minimumModelTier,
        SkillPolicy policy,
        List<SkillStep> steps,
        List<String> fallbackSkillIds
) {

    public SkillDefinition {
        routes = routes == null ? Set.of() : Set.copyOf(routes);
        steps = steps == null ? List.of() : List.copyOf(steps);
        fallbackSkillIds = fallbackSkillIds == null ? List.of() : List.copyOf(fallbackSkillIds);
    }

    public enum ExecutionMode {
        INLINE_DETERMINISTIC,
        INLINE_AGENT,
        BACKGROUND_RESEARCH
    }

    public record SkillPolicy(
            Set<CapabilityDescriptor.RiskLevel> allowedRiskLevels,
            int maxCapabilityCalls,
            long maxDurationSeconds
    ) {
        public SkillPolicy {
            allowedRiskLevels = allowedRiskLevels == null ? Set.of() : Set.copyOf(allowedRiskLevels);
        }
    }

    public record SkillStep(
            StepType type,
            String capability,
            boolean required,
            String fallbackCapability,
            String role,
            Set<String> allowedCapabilities
    ) {
        public SkillStep {
            allowedCapabilities = allowedCapabilities == null ? Set.of() : Set.copyOf(allowedCapabilities);
        }
    }

    public enum StepType {
        CAPABILITY,
        AGENT,
        SUBMIT_DEEP_RESEARCH,
        FINAL_ANSWER
    }
}
