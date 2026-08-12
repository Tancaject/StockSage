package com.stocksage.skill;

import com.stocksage.capability.CapabilityDescriptor;
import com.stocksage.capability.CapabilityRegistry;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 在应用启动时拒绝格式错误、越权或引用未知能力的 Skill。
 *
 * <p>它与运行时 {@code CapabilityPolicy} 形成两层防线：本类尽早阻止坏清单启动，Gateway 仍会对
 * 每次调用重新授权。V1 只接受有限时长、有限调用次数和只读风险。</p>
 */
@Component
public class SkillValidator {

    /** Skill ID 的稳定、小写、有限长度格式。 */
    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9-]{2,63}");
    /** V1 清单允许声明的只读风险集合。 */
    private static final Set<CapabilityDescriptor.RiskLevel> V1_RISKS = Set.of(
            CapabilityDescriptor.RiskLevel.READ_ONLY,
            CapabilityDescriptor.RiskLevel.EXTERNAL_READ
    );

    /** 用于确认清单中每个能力引用都已本地注册。 */
    private final CapabilityRegistry capabilityRegistry;

    public SkillValidator(CapabilityRegistry capabilityRegistry) {
        this.capabilityRegistry = capabilityRegistry;
    }

    /**
     * 校验一份完整 Skill 定义及其所有步骤。
     *
     * @param skill 启动时从 YAML 读取的定义
     * @throws IllegalStateException 元数据、预算、风险或能力引用不合法
     */
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

    /** 确认能力存在且风险被当前 Skill 策略显式允许。 */
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
