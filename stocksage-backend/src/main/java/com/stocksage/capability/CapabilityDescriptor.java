package com.stocksage.capability;

import java.time.Duration;
import java.util.Objects;

/**
 * 一项可执行能力的版本库受控策略描述。
 *
 * <p>{@link CapabilityRegistry} 从 YAML 清单加载该记录，并与同 ID 的
 * {@link CapabilityAdapter} 绑定。远端 MCP 返回的元数据不能自行创建能力；只有本地描述和适配器
 * 同时存在时才可执行，从而避免把远端新出现的工具自动暴露给 Agent。</p>
 *
 * @param id Skill 使用的稳定能力 ID
 * @param providerType 本地或 MCP 提供方类型
 * @param providerId 可观测性中使用的提供方标识
 * @param nativeName 提供方原生工具名
 * @param riskLevel 能力风险等级，供策略授权
 * @param timeoutMs 单次调用的最大超时
 * @param maxResultBytes 允许进入上下文的最大 UTF-8 字节数
 * @param enabled 是否允许注册后执行
 */
public record CapabilityDescriptor(
        String id,
        ProviderType providerType,
        String providerId,
        String nativeName,
        RiskLevel riskLevel,
        long timeoutMs,
        int maxResultBytes,
        boolean enabled
) {

    /** 启动时校验能力元数据，非法配置直接阻止应用带病启动。 */
    public CapabilityDescriptor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(providerType, "providerType");
        Objects.requireNonNull(providerId, "providerId");
        Objects.requireNonNull(nativeName, "nativeName");
        Objects.requireNonNull(riskLevel, "riskLevel");
        if (id.isBlank() || providerId.isBlank() || nativeName.isBlank()) {
            throw new IllegalArgumentException("Capability id/provider/nativeName must not be blank");
        }
        if (timeoutMs < 1 || maxResultBytes < 1) {
            throw new IllegalArgumentException("Capability timeout and result limit must be positive: " + id);
        }
    }

    /** @return 毫秒配置转换后的 {@link Duration} */
    public Duration timeout() {
        return Duration.ofMillis(timeoutMs);
    }

    /** 能力的接入方式；不代表风险等级。 */
    public enum ProviderType {
        LOCAL,
        MCP
    }

    /** 能力可能产生的副作用等级；V1 只允许只读类别。 */
    public enum RiskLevel {
        READ_ONLY,
        EXTERNAL_READ,
        SENSITIVE_READ,
        WRITE
    }
}
