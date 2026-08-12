package com.stocksage.capability;

import java.time.Instant;
import java.util.Set;

/**
 * 由已选 Skill 创建的一次能力调用上下文。
 *
 * <p>{@link CapabilityPolicy} 使用 allowlist 和 deadline 做授权，Observer 使用用户、会话和 trace
 * 关联可观测数据。该对象不携带原始工具结果或敏感凭据。</p>
 *
 * @param userId 当前租户用户 ID
 * @param conversationId 当前会话 ID，可为空
 * @param traceId 当前执行链路 ID，可为空
 * @param skillId 发起调用的仓库内 Skill ID
 * @param allowedCapabilities 本次 Skill 明确允许的能力集合
 * @param deadline 整个 Skill 执行的绝对截止时间
 */
public record CapabilityInvocationContext(
        String userId,
        Long conversationId,
        String traceId,
        String skillId,
        Set<String> allowedCapabilities,
        Instant deadline
) {

    /** 将可变集合复制为只读快照，并为缺省截止时间提供立即到期的安全值。 */
    public CapabilityInvocationContext {
        allowedCapabilities = allowedCapabilities == null ? Set.of() : Set.copyOf(allowedCapabilities);
        deadline = deadline == null ? Instant.now() : deadline;
    }
}
