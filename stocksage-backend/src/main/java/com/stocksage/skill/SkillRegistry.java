package com.stocksage.skill;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Immutable startup registry for repository-owned Skill manifests. */
@Component
public class SkillRegistry {

    private static final String SKILL_PATTERN = "classpath*:skills/*.yml";

    private final Map<String, SkillDefinition> skills;

    public SkillRegistry(SkillValidator validator) {
        Map<String, SkillDefinition> loaded = new LinkedHashMap<>();
        for (SkillDefinition skill : loadSkills()) {
            validator.validate(skill);
            if (loaded.putIfAbsent(skill.id(), skill) != null) {
                throw new IllegalStateException("Duplicate Skill id: " + skill.id());
            }
        }
        this.skills = Map.copyOf(loaded);
    }

    public Optional<SkillDefinition> find(String skillId) {
        return Optional.ofNullable(skills.get(skillId));
    }

    public List<SkillDefinition> list() {
        return List.copyOf(skills.values());
    }

    private List<SkillDefinition> loadSkills() {
        YAMLMapper mapper = YAMLMapper.builder()
                .findAndAddModules()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            Resource[] resources = resolver.getResources(SKILL_PATTERN);
            if (resources.length == 0) {
                throw new IllegalStateException("No Skill manifests found at " + SKILL_PATTERN);
            }
            Map<String, SkillDefinition> loaded = new LinkedHashMap<>();
            for (Resource resource : resources) {
                try (var input = resource.getInputStream()) {
                    SkillDefinition skill = mapper.readValue(input, SkillDefinition.class);
                    if (loaded.putIfAbsent(skill.id(), skill) != null) {
                        throw new IllegalStateException("Duplicate Skill id: " + skill.id());
                    }
                }
            }
            return List.copyOf(loaded.values());
        } catch (IOException error) {
            throw new IllegalStateException("Failed to load Skill manifests", error);
        }
    }
}
