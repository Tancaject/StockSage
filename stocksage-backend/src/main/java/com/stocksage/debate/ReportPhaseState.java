package com.stocksage.debate;

import com.stocksage.debate.DebateModels.DebateTurn;
import com.stocksage.debate.DebateModels.Side;
import com.stocksage.harness.DeepResearchCompletionPolicy;
import com.stocksage.harness.HarnessModels.HarnessDecision;
import com.stocksage.harness.HarnessModels.HarnessOutcome;
import com.stocksage.harness.HarnessModels.HarnessPhase;
import com.stocksage.harness.HarnessModels.HarnessSnapshot;
import com.stocksage.harness.HarnessModels.HarnessViolation;
import com.stocksage.harness.HarnessModels.RecoveryAction;
import com.stocksage.harness.HarnessModels.ViolationCode;
import com.stocksage.research.deep.AnalysisState;
import com.stocksage.research.report.InvestmentReport;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 报告阶段的 Harness 快照与修复预算：写入终态或待修复快照、计数唯一一次报告修复，
 * 并在辩论或报告未通过时构造不带 recommendation 的 NOT_RATED 报告。
 */
@Component
@RequiredArgsConstructor
class ReportPhaseState {

    /** 构造 NOT_RATED 时判断已有裁决是否仍绑定当前输入。 */
    private final DebateDecisionPolicy debateDecisionPolicy;
    /** DEEP 证据/报告的权威完成规则。 */
    private final DeepResearchCompletionPolicy completionPolicy;

    /** 已持久化的辩论失败属于终态，不能通过报告重综合绕过。 */
    boolean hasFailClosedDebateViolation(HarnessSnapshot snapshot) {
        return snapshot != null && snapshot.violations().stream().anyMatch(code ->
                code == ViolationCode.DEBATE_CONTRACT_INVALID
                        || code == ViolationCode.DEBATE_ASSESSMENT_INVALID
                        || code == ViolationCode.DEBATE_DECISION_INSUFFICIENT);
    }

    /** 从快照读取报告恢复预算；无快照时返回空不可变表。 */
    Map<RecoveryAction, Integer> reportRecoveryAttempts(HarnessSnapshot snapshot) {
        return snapshot == null ? Map.of() : snapshot.recoveryAttempts();
    }

    /** 恢复待执行修复时确保对应预算至少记为已使用一次。 */
    Map<RecoveryAction, Integer> withMinimumRecoveryAttempt(
            Map<RecoveryAction, Integer> attempts,
            RecoveryAction action,
            int minimum
    ) {
        java.util.EnumMap<RecoveryAction, Integer> merged =
                new java.util.EnumMap<>(RecoveryAction.class);
        if (attempts != null) {
            merged.putAll(attempts);
        }
        merged.merge(action, Math.max(0, minimum), Math::max);
        return Map.copyOf(merged);
    }

    /** 在不可变副本中增加指定恢复动作次数。 */
    Map<RecoveryAction, Integer> incrementRecoveryAttempt(
            Map<RecoveryAction, Integer> attempts,
            RecoveryAction action
    ) {
        java.util.EnumMap<RecoveryAction, Integer> incremented =
                new java.util.EnumMap<>(RecoveryAction.class);
        if (attempts != null) {
            incremented.putAll(attempts);
        }
        incremented.merge(action, 1, Integer::sum);
        return Map.copyOf(incremented);
    }

    /** 从持久化的待修复快照重建本次报告修复决策。 */
    HarnessDecision recoveryDecisionFrom(HarnessSnapshot snapshot) {
        List<HarnessViolation> violations = snapshot.violations().stream()
                .map(code -> new HarnessViolation(code, null))
                .toList();
        if (violations.isEmpty()) {
            violations = List.of(new HarnessViolation(ViolationCode.REPORT_SCHEMA_INVALID, null));
        }
        return new HarnessDecision(
                HarnessOutcome.RECOVER,
                violations,
                List.of(RecoveryAction.RESYNTHESIZE_REPORT)
        );
    }

    /** @return 报告是否由当前 ID/版本的完成策略验收 */
    boolean hasCurrentPolicyMetadata(InvestmentReport report) {
        return report != null
                && completionPolicy.policyId().equals(report.getCompletionPolicyId())
                && Integer.valueOf(completionPolicy.policyVersion())
                .equals(report.getCompletionPolicyVersion());
    }

    /**
     * 计算从 Round 1 开始连续、且 Bull/Bear 双方都已完成的轮数。
     *
     * <p>只完成单方或中间缺轮都不算，防止基于残缺辩论直接修复报告。</p>
     */
    int completedDebateRounds(AnalysisState state) {
        if (state == null || state.getDebateTurns() == null) {
            return 0;
        }
        Map<Integer, EnumSet<Side>> sidesByRound = new HashMap<>();
        for (DebateTurn turn : state.getDebateTurns()) {
            if (turn == null || turn.round() <= 0 || turn.side() == null
                    || turn.points() == null || turn.points().isEmpty()) {
                continue;
            }
            sidesByRound.computeIfAbsent(
                    turn.round(),
                    ignored -> EnumSet.noneOf(Side.class)
            ).add(turn.side());
        }
        int completed = 0;
        while (sidesByRound.getOrDefault(
                completed + 1,
                EnumSet.noneOf(Side.class)
        ).containsAll(EnumSet.allOf(Side.class))) {
            completed++;
        }
        return completed;
    }

    /** 把最终报告决策写回 HarnessSnapshot，并保留已执行修复的稳定 effect key。 */
    void applyFinalReportSnapshot(
            AnalysisState state,
            HarnessDecision decision,
            Map<RecoveryAction, Integer> recoveryAttempts
    ) {
        // 终态快照不带恢复动作，接管者不会把它误判成待执行的修复。
        state.setHarnessSnapshot(new HarnessSnapshot(
                completionPolicy.policyId(),
                Integer.toString(completionPolicy.policyVersion()),
                HarnessPhase.REPORT,
                decision.outcome(),
                decision.violations().stream().map(HarnessViolation::code).toList(),
                recoveryAttempts,
                List.of()
        ));
    }

    /** @return 快照是否表示尚待执行/重验的报告修复 */
    boolean isReportRecoveryCheckpoint(HarnessSnapshot snapshot) {
        return snapshot != null && snapshot.hasPendingRecovery(HarnessPhase.REPORT);
    }

    /** @return 快照声称修复已重验、但实际报告产物缺失的故障状态 */
    boolean isRevalidatedReportMissingArtifact(
            HarnessSnapshot snapshot,
            AnalysisState state
    ) {
        return snapshot != null
                && snapshot.phase() == HarnessPhase.REPORT
                && !snapshot.hasPendingRecovery(HarnessPhase.REPORT)
                && snapshot.recoveryAttempts()
                .getOrDefault(RecoveryAction.RESYNTHESIZE_REPORT, 0) > 0
                && (state == null || state.getInvestmentReport() == null);
    }

    /** 为“修复已完成但缺产物”构造 NOT_RATED 的失败关闭决策。 */
    HarnessDecision failSafeMissingReportDecision(HarnessSnapshot snapshot) {
        List<HarnessViolation> violations = snapshot.violations().stream()
                .map(code -> new HarnessViolation(code, null))
                .toList();
        if (violations.isEmpty()) {
            violations = List.of(new HarnessViolation(ViolationCode.REPORT_SCHEMA_INVALID, null));
        }
        return new HarnessDecision(HarnessOutcome.DEGRADE, violations, List.of());
    }

    /** 写入报告阶段快照；决策为 RECOVER 时即“修复已批准、结果未写回”。 */
    void applyReportRecoverySnapshot(
            AnalysisState state,
            HarnessDecision decision,
            Map<RecoveryAction, Integer> recoveryAttempts
    ) {
        state.setHarnessSnapshot(HarnessSnapshot.from(
                completionPolicy.policyId(),
                Integer.toString(completionPolicy.policyVersion()),
                HarnessPhase.REPORT,
                decision,
                recoveryAttempts
        ));
    }

    /** 调用可选持久化回调；无后台任务/测试场景可不提供。 */
    void checkpointHarnessState(
            Consumer<AnalysisState> harnessCheckpointer,
            AnalysisState state
    ) {
        if (harnessCheckpointer != null) {
            harnessCheckpointer.accept(state);
        }
    }

    InvestmentReport withCoverageUnknowns(AnalysisState state, InvestmentReport report) {
        List<String> unknowns = new ArrayList<>(report.getUnknowns() == null ? List.of() : report.getUnknowns());
        unknowns.addAll(state.evidenceCoverageUnknowns());
        report.setUnknowns(unknowns.stream().distinct().toList());
        return report;
    }

    /** 根据 Harness 违规构造无 recommendation、无引用的安全 NOT_RATED 报告。 */
    InvestmentReport buildNotRatedReport(
            AnalysisState state,
            HarnessDecision decision
    ) {
        List<String> violationCodes = decision == null
                ? List.of("REPORT_VALIDATION_FAILED")
                : decision.violations().stream()
                .map(violation -> violation.code().name())
                .toList();
        return InvestmentReport.builder()
                .ticker(state.getPrimaryTicker())
                .dataSnapshotHash(state.getDataSnapshotHash())
                .contextHash(state.getContextHash())
                .decisionAudit(debateDecisionPolicy.isCurrentVerdict(state)
                        ? state.getDebateVerdict()
                        : null)
                .qualityStatus(InvestmentReport.ReportQualityStatus.NOT_RATED)
                .completionPolicyId(completionPolicy.policyId())
                .completionPolicyVersion(completionPolicy.policyVersion())
                .recommendation(null)
                .analystSummary("研究经理输出未通过结构或证据引用校验，本轮不生成投资评级。")
                .rationale(List.of("报告校验未通过：" + String.join(", ", violationCodes)))
                .riskFactors(List.of("关键证据与最终结论尚未形成可审计映射。"))
                .unknowns(List.of("需要重新获取或人工核验报告证据引用。"))
                .dataFreshness("本轮报告未通过完成策略验收。")
                .citations(List.of())
                .evidenceItems(List.of())
                .build();
    }
}
