package com.stocksage.harness;

import com.stocksage.evidence.EvidenceModels.EvidenceDimension;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.stocksage.agent.intent.TimeSensitivity;
import com.stocksage.model.dto.InvestmentReport;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 研究完成策略共享的有限、可序列化契约集合。
 *
 * <p>这些枚举和 record 只携带元数据，不保存原始工具 payload。这样策略判断可重复，
 * 同一对象也能安全写入 checkpoint 与 Trace。上游研究流水线构造这些对象，
 * {@link ResearchCompletionPolicy} 读取它们，恢复流程再持久化 {@link HarnessSnapshot}。</p>
 */
public final class HarnessModels {

    private HarnessModels() {
    }

    /** Harness 执行的两个阶段边界。 */
    public enum HarnessPhase {
        EVIDENCE,
        REPORT
    }

    /**
     * 策略结果：通过、有限恢复、安全降级或立即阻断。
     */
    public enum HarnessOutcome {
        PASS,
        RECOVER,
        DEGRADE,
        BLOCK
    }

    /** 可持久化和离线评估的有限违规代码。 */
    public enum ViolationCode {
        TARGET_UNRESOLVED,
        TARGET_AMBIGUOUS,
        TARGET_MISMATCH,
        FUNDAMENTALS_MISSING,
        MARKET_MISSING,
        NEWS_MISSING,
        RAG_MISSING,
        PROVENANCE_MISSING,
        EVIDENCE_TIME_REQUIREMENT_UNMET,
        UNAPPROVED_CAPABILITY,
        REPORT_PARSE_INVALID,
        REPORT_SCHEMA_INVALID,
        REPORT_TARGET_MISMATCH,
        DEBATE_CONTRACT_INVALID,
        DEBATE_ASSESSMENT_INVALID,
        DEBATE_DECISION_INSUFFICIENT,
        REPORT_DECISION_AUDIT_MISSING,
        REPORT_DECISION_POLICY_MISMATCH,
        REPORT_DECISION_SNAPSHOT_MISMATCH,
        REPORT_DECISION_OUTPUT_MISMATCH,
        REPORT_DECISION_EVIDENCE_REFERENCE_UNUSABLE,
        REPORT_EVIDENCE_REFERENCE_MISSING,
        REPORT_EVIDENCE_REFERENCE_UNKNOWN,
        REPORT_EVIDENCE_REFERENCE_UNUSABLE
    }

    /** 策略允许请求的有限恢复动作；流水线决定如何执行副作用。 */
    public enum RecoveryAction {
        RETRY_FUNDAMENTALS,
        RETRY_MARKET,
        RETRY_NEWS,
        USE_APPROVED_FALLBACK,
        RESYNTHESIZE_REPORT,
        RETURN_NOT_RATED,
        RETURN_SAFE_REFUSAL
    }

    /**
     * 有限恢复副作用的持久化生命周期。
     *
     * <p>{@link #PLANNED} 必须先于只读工具副作用写入；接管者看到它时继续同一逻辑副作用并重跑证据验收。
     * {@link #REVALIDATED} 表示副作用后的账本或报告已重新评估。</p>
     */
    public enum RecoveryLifecycle {
        NONE,
        PLANNED,
        REVALIDATED
    }

    /** Research Manager 输出的解析/结构校验状态。 */
    public enum ParseStatus {
        VALID,
        INVALID_JSON,
        INVALID_SCHEMA,
        EMPTY_OUTPUT,
        MODEL_FAILURE
    }

    /**
     * 一次策略评估的工作流与恢复预算上下文。
     *
     * @param workflow 路由/工作流名称
     * @param recoveryAttempts 各恢复动作已执行次数
     * @param timeSensitivity 本次请求的时间要求；旧记录缺失时保持 UNSPECIFIED
     */
    public record RunContext(
            String workflow,
            Map<RecoveryAction, Integer> recoveryAttempts,
            TimeSensitivity timeSensitivity
    ) {
        public RunContext {
            workflow = safe(workflow);
            timeSensitivity = timeSensitivity == null ? TimeSensitivity.UNSPECIFIED : timeSensitivity;
            EnumMap<RecoveryAction, Integer> bounded = new EnumMap<>(RecoveryAction.class);
            if (recoveryAttempts != null) {
                recoveryAttempts.forEach((action, attempts) -> {
                    if (action != null) {
                        bounded.put(action, Math.max(0, attempts == null ? 0 : attempts));
                    }
                });
            }
            recoveryAttempts = Map.copyOf(bounded);
        }

        public RunContext(String workflow, Map<RecoveryAction, Integer> recoveryAttempts) {
            this(workflow, recoveryAttempts, TimeSensitivity.UNSPECIFIED);
        }

        /** @return 未使用任何恢复预算的 DEEP 上下文 */
        public static RunContext deepResearch() {
            return new RunContext("DEEP", Map.of());
        }

        /** @return 指定恢复动作已执行次数，未出现时为 0 */
        public int attempts(RecoveryAction action) {
            return recoveryAttempts.getOrDefault(action, 0);
        }
    }

    /**
     * 一条策略违规及其可选证据维度。
     *
     * @param code 稳定违规代码
     * @param dimension 仅维度相关问题需要填写
     */
    public record HarnessViolation(
            ViolationCode code,
            EvidenceDimension dimension
    ) {
        public HarnessViolation {
            if (code == null) {
                throw new IllegalArgumentException("violation code is required");
            }
        }
    }

    /**
     * 完成策略返回给执行流水线的纯决策。
     *
     * @param outcome 总体结果
     * @param violations 触发该结果的有限违规列表
     * @param recoveryActions 建议的有限恢复动作
     */
    public record HarnessDecision(
            HarnessOutcome outcome,
            List<HarnessViolation> violations,
            List<RecoveryAction> recoveryActions
    ) {
        public HarnessDecision {
            if (outcome == null) {
                throw new IllegalArgumentException("harness outcome is required");
            }
            violations = violations == null ? List.of() : List.copyOf(violations);
            recoveryActions = recoveryActions == null ? List.of() : List.copyOf(recoveryActions);
        }

        /** @return 只有 PASS 才允许生成投资建议 */
        public boolean allowsRecommendation() {
            return outcome == HarnessOutcome.PASS;
        }
    }

    /**
     * 被另一阶段恢复暂时让位的恢复上下文。
     *
     * <p>例如报告修复已经 PLANNED 时，证据阶段又需要一次有限重试；把被挂起上下文一并持久化，
     * 可防止进程崩溃后重置已预留的 Manager 修复预算。</p>
     */
    public record SuspendedRecovery(
            HarnessPhase phase,
            HarnessOutcome outcome,
            List<ViolationCode> violations,
            RecoveryLifecycle recoveryLifecycle,
            List<RecoveryAction> recoveryActions,
            String recoveryEffectKey
    ) {
        public SuspendedRecovery {
            phase = phase == null ? HarnessPhase.REPORT : phase;
            outcome = outcome == null ? HarnessOutcome.BLOCK : outcome;
            violations = violations == null ? List.of() : List.copyOf(violations);
            recoveryLifecycle = recoveryLifecycle == null
                    ? RecoveryLifecycle.NONE
                    : recoveryLifecycle;
            recoveryActions = recoveryActions == null ? List.of() : List.copyOf(recoveryActions);
            recoveryEffectKey = safe(recoveryEffectKey);
        }

        /** @return 从现有快照复制的挂起恢复；输入为空时返回 {@code null} */
        public static SuspendedRecovery from(HarnessSnapshot snapshot) {
            if (snapshot == null) {
                return null;
            }
            return new SuspendedRecovery(
                    snapshot.phase(),
                    snapshot.outcome(),
                    snapshot.violations(),
                    snapshot.recoveryLifecycle(),
                    snapshot.recoveryActions(),
                    snapshot.recoveryEffectKey()
            );
        }
    }

    /**
     * 在任何恢复副作用之前写入的持久化策略状态。
     *
     * <p>快照只包含有限决策元数据；原始 prompt 和工具 payload 不进入 checkpoint。
     * {@code recoveryEffectKey} 让接管者识别同一个逻辑副作用，避免重复消耗恢复预算。</p>
     */
    public record HarnessSnapshot(
            String policyId,
            String policyVersion,
            HarnessPhase phase,
            HarnessOutcome outcome,
            List<ViolationCode> violations,
            Map<RecoveryAction, Integer> recoveryAttempts,
            RecoveryLifecycle recoveryLifecycle,
            List<RecoveryAction> recoveryActions,
            String recoveryEffectKey,
            SuspendedRecovery suspendedRecovery
    ) {
        public HarnessSnapshot {
            policyId = safe(policyId);
            policyVersion = safe(policyVersion);
            phase = phase == null ? HarnessPhase.EVIDENCE : phase;
            outcome = outcome == null ? HarnessOutcome.BLOCK : outcome;
            violations = violations == null ? List.of() : List.copyOf(violations);
            EnumMap<RecoveryAction, Integer> bounded = new EnumMap<>(RecoveryAction.class);
            if (recoveryAttempts != null) {
                recoveryAttempts.forEach((action, attempts) -> {
                    if (action != null) {
                        bounded.put(action, Math.max(0, attempts == null ? 0 : attempts));
                    }
                });
            }
            recoveryAttempts = Map.copyOf(bounded);
            recoveryLifecycle = recoveryLifecycle == null
                    ? inferRecoveryLifecycle(outcome, recoveryAttempts)
                    : recoveryLifecycle;
            recoveryActions = recoveryActions == null ? List.of() : List.copyOf(recoveryActions);
            recoveryEffectKey = safe(recoveryEffectKey);
        }

        /** 根据普通策略决策生成不带显式副作用键的快照。 */
        public static HarnessSnapshot from(
                String policyId,
                String policyVersion,
                HarnessPhase phase,
                HarnessDecision decision,
                Map<RecoveryAction, Integer> recoveryAttempts
        ) {
            HarnessDecision safeDecision = decision == null
                    ? new HarnessDecision(HarnessOutcome.BLOCK, List.of(), List.of())
                    : decision;
            return new HarnessSnapshot(
                    policyId,
                    policyVersion,
                    phase,
                    safeDecision.outcome(),
                    safeDecision.violations().stream().map(HarnessViolation::code).toList(),
                    recoveryAttempts,
                    inferRecoveryLifecycle(safeDecision.outcome(), recoveryAttempts),
                    safeDecision.recoveryActions(),
                    "",
                    null
            );
        }

        /** 生成当前阶段的恢复快照，不挂起其他阶段。 */
        public static HarnessSnapshot recovery(
                String policyId,
                String policyVersion,
                HarnessPhase phase,
                HarnessDecision decision,
                Map<RecoveryAction, Integer> recoveryAttempts,
                RecoveryLifecycle recoveryLifecycle,
                List<RecoveryAction> recoveryActions,
                String recoveryEffectKey
        ) {
            return recovery(
                    policyId,
                    policyVersion,
                    phase,
                    decision,
                    recoveryAttempts,
                    recoveryLifecycle,
                    recoveryActions,
                    recoveryEffectKey,
                    null
            );
        }

        /** 生成完整恢复快照，并可携带被暂时挂起的另一阶段恢复。 */
        public static HarnessSnapshot recovery(
                String policyId,
                String policyVersion,
                HarnessPhase phase,
                HarnessDecision decision,
                Map<RecoveryAction, Integer> recoveryAttempts,
                RecoveryLifecycle recoveryLifecycle,
                List<RecoveryAction> recoveryActions,
                String recoveryEffectKey,
                SuspendedRecovery suspendedRecovery
        ) {
            HarnessDecision safeDecision = decision == null
                    ? new HarnessDecision(HarnessOutcome.BLOCK, List.of(), List.of())
                    : decision;
            return new HarnessSnapshot(
                    policyId,
                    policyVersion,
                    phase,
                    safeDecision.outcome(),
                    safeDecision.violations().stream().map(HarnessViolation::code).toList(),
                    recoveryAttempts,
                    recoveryLifecycle,
                    recoveryActions,
                    recoveryEffectKey,
                    suspendedRecovery
            );
        }

        /** @return 是否存在尚待执行/重验的证据阶段恢复 */
        @JsonIgnore
        public boolean hasPendingEvidenceRecovery() {
            return phase == HarnessPhase.EVIDENCE
                    && outcome == HarnessOutcome.RECOVER
                    && recoveryLifecycle == RecoveryLifecycle.PLANNED;
        }

        private static RecoveryLifecycle inferRecoveryLifecycle(
                HarnessOutcome outcome,
                Map<RecoveryAction, Integer> recoveryAttempts
        ) {
            if (outcome == HarnessOutcome.RECOVER) {
                return RecoveryLifecycle.PLANNED;
            }
            return recoveryAttempts == null || recoveryAttempts.isEmpty()
                    ? RecoveryLifecycle.NONE
                    : RecoveryLifecycle.REVALIDATED;
        }
    }

    /**
     * Research Manager 的结构化产物及解析校验结果。
     *
     * @param report 成功解析的报告，失败时可为空
     * @param parseStatus 解析或模型失败分类
     * @param validationIssues 缺失/非法字段名的有限列表
     */
    public record SynthesisResult(
            InvestmentReport report,
            ParseStatus parseStatus,
            List<String> validationIssues
    ) {
        public SynthesisResult {
            parseStatus = parseStatus == null ? ParseStatus.MODEL_FAILURE : parseStatus;
            validationIssues = validationIssues == null ? List.of() : List.copyOf(validationIssues);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }

}
