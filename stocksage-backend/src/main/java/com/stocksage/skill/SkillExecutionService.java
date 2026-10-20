package com.stocksage.skill;

import com.stocksage.agent.ExecutionPlan;
import com.stocksage.capability.CapabilityException;
import com.stocksage.capability.CapabilityGateway;
import com.stocksage.capability.CapabilityInvocationContext;
import com.stocksage.capability.CapabilityResult;
import com.stocksage.tool.ChatStreamEmitter;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 执行已校验 Skill 中的确定性能力预取部分。
 *
 * <p>上游工具预取服务传入 {@link ExecutionPlan}；本类解析对应 Skill、建立能力 allowlist 与总截止时间，
 * 按清单顺序经 {@link CapabilityGateway} 执行能力，并把能力结果作为证据交给后续 Agent 综合。
 * 当前仅承接 NEWS 的确定性内联步骤，其他路由继续走既有执行路径。</p>
 */
@Service
public class SkillExecutionService {

    /** 根据受控路由选择仓库内 Skill 或其声明式 fallback。 */
    private final SkillResolver skillResolver;
    /** 统一执行授权、超时、限长和可观测性。 */
    private final CapabilityGateway capabilityGateway;
    /** 把降级原因推送到当前 SSE/Trace 流。 */
    private final ChatStreamEmitter chatStreamEmitter;

    public SkillExecutionService(SkillResolver skillResolver,
                                 CapabilityGateway capabilityGateway,
                                 ChatStreamEmitter chatStreamEmitter) {
        this.skillResolver = skillResolver;
        this.capabilityGateway = capabilityGateway;
        this.chatStreamEmitter = chatStreamEmitter;
    }

    /**
     * 执行计划对应的确定性能力预取。
     *
     * <p>没有可用 Skill 时返回空结果，让调用方继续旧路径；策略拒绝、未知能力以及
     * required 步骤失败时抛出异常；已选 Skill 的可选步骤失败时记录缺口，
     * 不再从旧路径重复调用同一本地能力。</p>
     *
     * @param executionPlan Coordinator 生成的受控计划
     * @param userQuery 用户原始问题，将作为新闻能力查询词
     * @param maxSearchResults 期望结果数，内部限制到安全范围
     * @param traceId 当前链路 ID
     * @param conversationId 当前会话 ID
     * @param userId 当前租户用户 ID
     * @return 实际执行的 Skill ID 和取得的能力证据
     */
    public ExecutionResult executePrefetch(ExecutionPlan executionPlan,
                                           String userQuery,
                                           int maxSearchResults,
                                           String traceId,
                                           Long conversationId,
                                           String userId) {
        // 解析失败不是请求失败：尚未迁移到 Skill 的路由继续使用原有预取实现。
        Optional<SkillDefinition> resolved = skillResolver.resolve(executionPlan);
        if (resolved.isEmpty()) {
            return ExecutionResult.empty();
        }
        SkillDefinition skill = resolved.get();

        // allowlist 来自服务器持有的清单，而不是模型输出。
        Set<String> allowedCapabilities = collectAllowedCapabilities(skill);
        CapabilityInvocationContext invocationContext = new CapabilityInvocationContext(
                userId,
                conversationId,
                traceId,
                skill.id(),
                allowedCapabilities,
                Instant.now().plusSeconds(skill.policy().maxDurationSeconds())
        );

        java.util.List<CapabilityResult> evidence = new java.util.ArrayList<>();
        int calls = 0;
        for (SkillDefinition.SkillStep step : skill.steps()) {
            calls = requireCallBudget(skill, calls + 1);
            Map<String, Object> arguments = newsArguments(userQuery, maxSearchResults);
            try {
                evidence.add(capabilityGateway.invoke(step.capability(), arguments, invocationContext));
            } catch (CapabilityException primaryError) {
                if (primaryError.reason() == CapabilityException.Reason.CAPACITY_EXCEEDED) {
                    if (step.required()) throw primaryError;
                    emit(traceId, conversationId,
                            "执行容量不足，可选能力 " + step.capability() + " 未启动；请稍后重试。");
                    continue;
                }
                // 策略或注册表错误不能被可选步骤吞掉。
                if (primaryError.reason() == CapabilityException.Reason.DENIED
                        || primaryError.reason() == CapabilityException.Reason.UNKNOWN) {
                    throw primaryError;
                }
                if (step.required()) {
                    throw primaryError;
                } else {
                    emit(traceId, conversationId,
                            "可选能力 " + step.capability() + " 不可用，本轮使用其他已取得的证据，请稍后重试。");
                }
            }
        }
        return new ExecutionResult(skill.id(), java.util.List.copyOf(evidence));
    }

    /** 汇总各步骤能力，形成单次调用不可变白名单。 */
    private Set<String> collectAllowedCapabilities(SkillDefinition skill) {
        Set<String> allowed = new LinkedHashSet<>();
        for (SkillDefinition.SkillStep step : skill.steps()) {
            if (step.capability() != null && !step.capability().isBlank()) {
                allowed.add(step.capability());
            }
        }
        return Set.copyOf(allowed);
    }

    /** 构造 NEWS walking skeleton 的稳定参数，并限制结果数量。 */
    private Map<String, Object> newsArguments(String userQuery, int maxSearchResults) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("query", userQuery == null ? "" : userQuery.trim());
        arguments.put("maxResults", Math.max(1, Math.min(10, maxSearchResults)));
        return Map.copyOf(arguments);
    }

    /** 校验实际调用次数，不允许超出 Skill 清单预算。 */
    private int requireCallBudget(SkillDefinition skill, int calls) {
        if (calls > skill.policy().maxCapabilityCalls()) {
            throw new CapabilityException(CapabilityException.Reason.DENIED,
                    "Skill capability call limit exceeded: " + skill.id());
        }
        return calls;
    }

    private void emit(String traceId, Long conversationId, String content) {
        chatStreamEmitter.emit(traceId, conversationId, "observation", content);
    }

    /**
     * Skill 预取返回给原有编排层的最小结果。
     *
     * @param skillId 实际执行的 Skill ID；未选择 Skill 时为空
     * @param evidence 取得的能力证据
     */
    public record ExecutionResult(
            String skillId,
            java.util.List<CapabilityResult> evidence
    ) {
        /** @return 未选择 Skill 时使用的空结果 */
        public static ExecutionResult empty() {
            return new ExecutionResult("", java.util.List.of());
        }
    }
}
