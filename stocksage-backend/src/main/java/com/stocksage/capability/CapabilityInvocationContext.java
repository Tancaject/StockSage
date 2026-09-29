package com.stocksage.capability;

import java.time.Instant;
import java.util.Set;
import com.stocksage.tool.ToolCallContext.RunDeadline;
import com.stocksage.tool.ToolCallContext.RunExecution;

/**
 * 由后端普通编排或已选 Skill 创建的一次能力调用上下文。
 *
 * <p>{@link CapabilityPolicy} 使用 allowlist 和 deadline 做授权，Observer 使用用户、会话和 trace
 * 关联可观测数据。运行身份仅在内部传递，Observer 不序列化整个上下文或 owner token。</p>
 *
 * @param userId 当前租户用户 ID
 * @param conversationId 当前会话 ID，可为空
 * @param traceId 当前执行链路 ID，可为空
 * @param skillId 发起调用的仓库内 Skill ID；普通确定性调用为 null
 * @param allowedCapabilities 本次后端计划或 Skill 明确允许的能力集合
 * @param deadline 本次调用所属执行范围的绝对截止时间
 * @param userQuery 用户原始问题，与可能改写的搜索词分开；旧调用方为空时沿用搜索词
 * @param runExecution 显式研究运行身份；只在执行边界传递，不写入观察日志
 */
public record CapabilityInvocationContext(
        String userId,
        Long conversationId,
        String traceId,
        String skillId,
        Set<String> allowedCapabilities,
        Instant deadline,
        RunDeadline runDeadline,
        String userQuery,
        RunExecution runExecution
) {

    public CapabilityInvocationContext(String userId, Long conversationId, String traceId,
            String skillId, Set<String> allowedCapabilities, Instant deadline, RunDeadline runDeadline) {
        this(userId, conversationId, traceId, skillId, allowedCapabilities, deadline, runDeadline, null, null);
    }

    public CapabilityInvocationContext(String userId, Long conversationId, String traceId,
            String skillId, Set<String> allowedCapabilities, Instant deadline) {
        this(userId, conversationId, traceId, skillId, allowedCapabilities, deadline, null);
    }

    /** 将可变集合复制为只读快照，并为缺省截止时间提供立即到期的安全值。 */
    public CapabilityInvocationContext {
        allowedCapabilities = allowedCapabilities == null ? Set.of() : Set.copyOf(allowedCapabilities);
        deadline = deadline == null ? Instant.now() : deadline;
        if (runExecution != null) {
            RunDeadline ownedDeadline = new RunDeadline(runExecution.runId(), runExecution.deadlineEpochMs());
            if (runDeadline != null && !runDeadline.equals(ownedDeadline)) {
                throw new IllegalArgumentException("Capability execution and deadline must belong to the same run");
            }
            runDeadline = ownedDeadline;
        }
    }
}
