package com.stocksage.harness;

import com.stocksage.evidence.EvidenceModels.EvidenceDimension;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.stocksage.agent.intent.TimeSensitivity;
import com.stocksage.agent.ordinary.OrdinaryPlan;
import com.stocksage.research.report.InvestmentReport;

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
        DATA_REQUIREMENT_UNMET,
        EVIDENCE_CONTEXT_INCOMPLETE,
        EVIDENCE_TIME_REQUIREMENT_UNMET,
        UNAPPROVED_CAPABILITY,
        REPORT_PARSE_INVALID,
        REPORT_SCHEMA_INVALID,
        REPORT_TARGET_MISMATCH,
        DEBATE_CONTRACT_INVALID,
        DEBATE_ASSESSMENT_INVALID,
        DEBATE_DECISION_INSUFFICIENT,
        REPORT_DECISION_AUDIT_MISSING,
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

    /** Research Manager 输出的解析/结构校验状态。 */
    public enum ParseStatus {
        VALID,
        INVALID_JSON,
        INVALID_SCHEMA,
        EMPTY_OUTPUT,
        MODEL_FAILURE
    }

    /** 普通路线已确认的需求、实际交付给模型的证据状态和本轮财报正文召回事实。 */
    public record OrdinaryEvidenceContext(
            OrdinaryPlan plan,
            List<Map<String, Object>> observations,
            boolean filingTextRetrieved
    ) {
        public OrdinaryEvidenceContext {
            observations = List.copyOf(observations);
        }
    }

    /**
     * 一次策略评估的工作流与恢复预算上下文。
     *
     * @param workflow 路由/工作流名称
     * @param recoveryAttempts 各恢复动作已执行次数
     * @param timeSensitivity 本次请求的时间要求；旧记录缺失时保持 UNSPECIFIED
     * @param ordinaryEvidence 普通路线验收事实；DEEP 不使用
     */
    public record RunContext(
            String workflow,
            Map<RecoveryAction, Integer> recoveryAttempts,
            TimeSensitivity timeSensitivity,
            OrdinaryEvidenceContext ordinaryEvidence
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

        public RunContext(String workflow, Map<RecoveryAction, Integer> recoveryAttempts, TimeSensitivity timeSensitivity) {
            this(workflow, recoveryAttempts, timeSensitivity, null);
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
     * 在任何恢复副作用之前写入的持久化策略状态。
     *
     * <p>快照只包含有限决策元数据；原始 prompt 和工具 payload 不进入 checkpoint。
     * 恢复前先把计数加一再落盘，接管者据此判断预算是否已用掉；{@code outcome == RECOVER}
     * 表示该阶段的修复已批准但新结果尚未写回。旧 checkpoint 中的生命周期、副作用键等字段直接忽略。</p>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HarnessSnapshot(
            String policyId,
            String policyVersion,
            HarnessPhase phase,
            HarnessOutcome outcome,
            List<ViolationCode> violations,
            Map<RecoveryAction, Integer> recoveryAttempts,
            List<RecoveryAction> recoveryActions
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
            recoveryActions = recoveryActions == null ? List.of() : List.copyOf(recoveryActions);
        }

        /** 根据策略决策和（已含本次恢复的）计数生成快照。 */
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
                    safeDecision.recoveryActions()
            );
        }

        /** @return 该阶段是否有已批准、尚未写回结果的修复 */
        @JsonIgnore
        public boolean hasPendingRecovery(HarnessPhase target) {
            return phase == target
                    && outcome == HarnessOutcome.RECOVER
                    && !recoveryActions.isEmpty();
        }
    }

    /**
     * Research Manager 的结构化产物及解析校验结果。
     *
     * @param report 成功解析的报告，失败时可为空
     * @param parseStatus 解析或模型失败分类
     * @param validationIssues 缺失/非法字段名的有限列表
     * @param outputExcerpt 有界的失败报告片段；只供既有报告修复使用
     */
    public record SynthesisResult(
            InvestmentReport report,
            ParseStatus parseStatus,
            List<String> validationIssues,
            String outputExcerpt
    ) {
        public SynthesisResult(InvestmentReport report, ParseStatus parseStatus, List<String> validationIssues) {
            this(report, parseStatus, validationIssues, "");
        }

        public SynthesisResult {
            parseStatus = parseStatus == null ? ParseStatus.MODEL_FAILURE : parseStatus;
            validationIssues = validationIssues == null ? List.of() : List.copyOf(validationIssues);
            outputExcerpt = boundedExcerpt(outputExcerpt);
        }
    }

    /** 与待修复快照一起持久化的一次修复输入；违规码沿用 HarnessSnapshot，不保存报告全文。 */
    public record ReportRepairFeedback(
            ParseStatus parseStatus,
            List<String> validationIssues,
            String outputExcerpt
    ) {
        public ReportRepairFeedback {
            parseStatus = parseStatus == null ? ParseStatus.MODEL_FAILURE : parseStatus;
            validationIssues = validationIssues == null ? List.of() : validationIssues.stream()
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .limit(24)
                    .map(issue -> issue.substring(0, Math.min(issue.length(), 160)))
                    .toList();
            outputExcerpt = boundedExcerpt(outputExcerpt);
        }
    }

    private static String boundedExcerpt(String value) {
        String text = safe(value);
        return text.substring(0, Math.min(text.length(), 2000));
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }

}
