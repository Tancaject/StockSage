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

/**
 * 仓库内 Skill 清单的启动期不可变注册表。
 *
 * <p>构造时读取 {@code skills/*.yml}，逐项交给 {@link SkillValidator} 做安全校验，并拒绝重复 ID。
 * 下游 Resolver 只读取完成校验的快照，运行期间不会接受模型或远端服务动态注册流程。</p>
 */
@Component
public class SkillRegistry {

    /** classpath 中 Skill YAML 清单的位置。 */
    private static final String SKILL_PATTERN = "classpath*:skills/*.yml";

    /** 按稳定 ID 索引的不可变 Skill 快照。 */
    private final Map<String, SkillDefinition> skills;

    /**
     * 加载并校验所有 Skill；任何不安全引用都会阻止应用启动。
     *
     * @param validator Skill 元数据、预算和能力引用校验器
     */
    public SkillRegistry(SkillValidator validator) {
        this.skills = loadSkills(validator);
    }

    /** @return 指定 ID 的已启用或禁用定义；不存在时为空 */
    public Optional<SkillDefinition> find(String skillId) {
        return Optional.ofNullable(skills.get(skillId));
    }

    /** @return 注册表中全部 Skill 的只读列表 */
    public List<SkillDefinition> list() {
        return List.copyOf(skills.values());
    }

    /** 从所有 classpath YAML 读取并去重 Skill 定义。 */
    private Map<String, SkillDefinition> loadSkills(SkillValidator validator) {
        YAMLMapper mapper = YAMLMapper.builder()
                .findAndAddModules()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
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
                    validator.validate(skill);
                    if (loaded.putIfAbsent(skill.id(), skill) != null) {
                        throw new IllegalStateException("Duplicate Skill id: " + skill.id());
                    }
                }
            }
            return Map.copyOf(loaded);
        } catch (IOException error) {
            throw new IllegalStateException("Failed to load Skill manifests", error);
        }
    }
}
