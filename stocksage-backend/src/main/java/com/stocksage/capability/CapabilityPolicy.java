package com.stocksage.capability;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

/**
 * 能力执行前的 V1 授权策略。
 *
 * <p>上游 Gateway 传入本地描述和 Skill 上下文；本类只允许已启用、被该 Skill 显式列入
 * allowlist、风险为只读且尚未过期的能力。它不访问远端服务，也不根据工具名称猜测权限。</p>
 */
@Component
public class CapabilityPolicy {

    /** V1 唯一允许的只读风险集合，写能力即使被误配到 Skill 也会被拒绝。 */
    private static final Set<CapabilityDescriptor.RiskLevel> V1_ALLOWED_RISKS = EnumSet.of(
            CapabilityDescriptor.RiskLevel.READ_ONLY,
            CapabilityDescriptor.RiskLevel.EXTERNAL_READ
    );

    /**
     * 对单次能力调用执行失败关闭的授权检查。
     *
     * @param descriptor 注册表中的本地能力策略
     * @param context 已选 Skill 生成的调用上下文
     * @throws CapabilityException 能力被禁用、越权、风险过高或已过期
     */
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
