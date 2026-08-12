package com.stocksage.skill;

import com.stocksage.agent.ExecutionPlan;
import com.stocksage.agent.PlanRoute;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 把 Coordinator 路由解析为服务器预注册的 Skill。
 *
 * <p>当前只为 NEWS 路由选择配置的默认 Skill；若默认项不可用，再按其清单声明顺序查找 fallback。
 * 模型不能在这里提交任意 Skill ID，且路由与模型层级不满足时不会强行执行。</p>
 */
@Component
public class SkillResolver {

    /** 只包含启动时已校验 Skill 的注册表。 */
    private final SkillRegistry registry;
    /** NEWS 路由默认 Skill ID，可通过配置切换为仓库内另一项。 */
    private final String defaultNewsSkill;

    public SkillResolver(SkillRegistry registry,
                         @Value("${stocksage.skills.defaults.news:latest-news-mcp}") String defaultNewsSkill) {
        this.registry = registry;
        this.defaultNewsSkill = defaultNewsSkill;
    }

    /**
     * 为执行计划选择一个已启用且兼容的 Skill。
     *
     * @param executionPlan Coordinator 的受控计划
     * @return 默认 Skill、其第一个兼容 fallback，或空表示继续旧路径
     */
    public Optional<SkillDefinition> resolve(ExecutionPlan executionPlan) {
        if (executionPlan == null || executionPlan.route() != PlanRoute.NEWS) {
            return Optional.empty();
        }
        Optional<SkillDefinition> selected = registry.find(defaultNewsSkill)
                .filter(SkillDefinition::enabled)
                .filter(skill -> skill.routes().contains(PlanRoute.NEWS))
                .filter(skill -> supportsTier(executionPlan, skill));
        if (selected.isPresent()) {
            return selected;
        }
        // fallback ID 只能来自默认 Skill 的本地清单，不能由请求或模型临时指定。
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

    /** 检查计划模型层级是否达到 Skill 声明的最低要求。 */
    private boolean supportsTier(ExecutionPlan executionPlan, SkillDefinition skill) {
        return executionPlan.modelTier() != null
                && executionPlan.modelTier().ordinal() >= skill.minimumModelTier().ordinal();
    }
}
