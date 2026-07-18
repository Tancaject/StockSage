package com.stocksage.skill;

import com.stocksage.agent.ExecutionPlan;
import com.stocksage.agent.PlanRoute;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Resolves a Coordinator route only to a pre-registered server-side Skill id. */
@Component
public class SkillResolver {

    private final SkillRegistry registry;
    private final String defaultNewsSkill;

    public SkillResolver(SkillRegistry registry,
                         @Value("${stocksage.skills.defaults.news:latest-news-mcp}") String defaultNewsSkill) {
        this.registry = registry;
        this.defaultNewsSkill = defaultNewsSkill;
    }

    public Optional<SkillDefinition> resolve(ExecutionPlan executionPlan) {
        if (executionPlan == null || PlanRoute.normalize(executionPlan.taskType()) != PlanRoute.NEWS) {
            return Optional.empty();
        }
        Optional<SkillDefinition> selected = registry.find(defaultNewsSkill)
                .filter(SkillDefinition::enabled)
                .filter(skill -> skill.routes().contains(PlanRoute.NEWS))
                .filter(skill -> supportsTier(executionPlan, skill));
        if (selected.isPresent()) {
            return selected;
        }
        return registry.find(defaultNewsSkill)
                .stream()
                .flatMap(skill -> skill.fallbackSkillIds().stream())
                .map(registry::find)
                .flatMap(Optional::stream)
                .filter(SkillDefinition::enabled)
                .filter(skill -> skill.routes().contains(PlanRoute.NEWS))
                .filter(skill -> supportsTier(executionPlan, skill))
                .findFirst();
    }

    private boolean supportsTier(ExecutionPlan executionPlan, SkillDefinition skill) {
        return executionPlan.modelTier() != null
                && executionPlan.modelTier().ordinal() >= skill.minimumModelTier().ordinal();
    }
}
