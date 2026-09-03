package com.stocksage.skill;

import com.stocksage.agent.ModelTier;
import com.stocksage.agent.PlanRoute;
import com.stocksage.capability.CapabilityDescriptor;

import java.util.List;
import java.util.Set;

/**
 * 仓库内版本受控的声明式研究流程。
 *
 * <p>{@link SkillRegistry} 从 YAML 加载该记录，{@link SkillValidator} 在启动时校验其中的能力引用，
 * {@link SkillResolver} 再按 Coordinator 路由选择可用 Skill。Skill 只能编排服务器已知步骤，
 * 不能让模型临时发明工具、放宽风险或自行增加调用预算。</p>
 *
 * @param id 稳定 Skill ID
 * @param version 清单版本
 * @param displayName 管理面板使用的名称
 * @param enabled 是否允许被解析器选择
 * @param routes 适用的 Coordinator 路由
 * @param executionMode 内联或后台执行模式
 * @param minimumModelTier 计划需要达到的最低模型层级
 * @param policy 能力风险、次数和总时长边界
 * @param steps 按声明顺序执行的步骤
 * @param fallbackSkillIds 当前 Skill 不可选时的候选 Skill ID
 */
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

    /** 把清单中的集合复制为不可变快照，避免启动后被外部修改。 */
    public SkillDefinition {
        routes = routes == null ? Set.of() : Set.copyOf(routes);
        steps = steps == null ? List.of() : List.copyOf(steps);
        fallbackSkillIds = fallbackSkillIds == null ? List.of() : List.copyOf(fallbackSkillIds);
    }

    /** 当前 Skill executor 唯一支持的运行承载方式。 */
    public enum ExecutionMode {
        INLINE_DETERMINISTIC
    }

    /**
     * Skill 级执行预算。
     *
     * @param allowedRiskLevels 该流程允许引用的能力风险等级
     * @param maxCapabilityCalls 单次执行的最大能力调用数，包含 fallback
     * @param maxDurationSeconds 单次 Skill 的总截止时间
     */
    public record SkillPolicy(
            Set<CapabilityDescriptor.RiskLevel> allowedRiskLevels,
            int maxCapabilityCalls,
            long maxDurationSeconds
    ) {
        public SkillPolicy {
            allowedRiskLevels = allowedRiskLevels == null ? Set.of() : Set.copyOf(allowedRiskLevels);
        }
    }

    /**
     * 一条声明式执行步骤。
     *
     * @param type 步骤类别
     * @param capability 主能力 ID，仅 CAPABILITY 步骤使用
     * @param required 主能力及其 fallback 都失败时是否终止 Skill
     * @param fallbackCapability 主能力不可用时的本地降级能力
     */
    public record SkillStep(
            StepType type,
            String capability,
            boolean required,
            String fallbackCapability
    ) {
    }

    /** Skill 清单允许声明的有限步骤类型。 */
    public enum StepType {
        CAPABILITY
    }
}
