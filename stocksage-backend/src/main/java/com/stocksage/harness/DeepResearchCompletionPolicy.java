package com.stocksage.harness;

import com.stocksage.harness.HarnessModels.EvidenceDimension;
import com.stocksage.harness.HarnessModels.HarnessDecision;
import com.stocksage.harness.HarnessModels.HarnessOutcome;
import com.stocksage.harness.HarnessModels.HarnessViolation;
import com.stocksage.harness.HarnessModels.RecoveryAction;
import com.stocksage.harness.HarnessModels.RunContext;
import com.stocksage.harness.HarnessModels.SynthesisResult;
import com.stocksage.harness.HarnessModels.TargetResolutionStatus;
import com.stocksage.harness.HarnessModels.ViolationCode;
import com.stocksage.model.dto.AnalysisHorizon;
import com.stocksage.model.dto.InvestmentReport;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * DEEP 股票研究的确定性完成策略。
 *
 * <p>{@link ResearchHarness} 在证据收集后和报告综合后分别调用本策略。证据阶段检查标的、
 * 基本面、行情、来源和能力审批；报告阶段检查结构、标的一致性以及引用是否来自
 * {@link EvidenceLedger#usableEvidenceIds()}。策略只返回 PASS/RECOVER/DEGRADE/BLOCK 决策，
 * 不调用工具、不写 checkpoint，也绝不在证据不足时编造评级。</p>
 */
@Component
public class DeepResearchCompletionPolicy implements ResearchCompletionPolicy {

    /** 持久化到 Trace/checkpoint 的稳定策略 ID。 */
    public static final String POLICY_ID = "deep-equity-v1";
    /** 当前规则版本；规则语义变化时递增，用于拒绝陈旧报告权威。 */
    public static final int POLICY_VERSION = 3;

    @Override
    public String policyId() {
        return POLICY_ID;
    }

    @Override
    public int policyVersion() {
        return POLICY_VERSION;
    }

    /**
     * 对已收集证据执行第一阶段门槛。
     *
     * @param context 当前恢复动作的已用次数
     * @param ledger 本轮结构化证据元数据
     * @return 通过、一次定向恢复、安全降级或阻断决策
     */
    @Override
    public HarnessDecision afterEvidence(RunContext context, EvidenceLedger ledger) {
        RunContext safeContext = context == null ? RunContext.deepResearch() : context;
        EvidenceLedger safeLedger = ledger == null ? EvidenceLedger.empty() : ledger;
        List<HarnessViolation> violations = new ArrayList<>();

        // 标的错误会污染所有下游结论，必须优先于“缺哪类证据”检查并直接阻断。
        if (safeLedger.target().status() == TargetResolutionStatus.AMBIGUOUS) {
            violations.add(new HarnessViolation(ViolationCode.TARGET_AMBIGUOUS, null));
            return decision(HarnessOutcome.BLOCK, violations, List.of(RecoveryAction.RETURN_SAFE_REFUSAL));
        }
        if (!safeLedger.target().isResolved()) {
            violations.add(new HarnessViolation(ViolationCode.TARGET_UNRESOLVED, null));
            return decision(HarnessOutcome.BLOCK, violations, List.of(RecoveryAction.RETURN_SAFE_REFUSAL));
        }
        if (!safeLedger.targetConsistent()) {
            violations.add(new HarnessViolation(ViolationCode.TARGET_MISMATCH, null));
            return decision(HarnessOutcome.BLOCK, violations, List.of(RecoveryAction.RETURN_SAFE_REFUSAL));
        }
        if (safeLedger.hasUnapprovedCapability()) {
            violations.add(new HarnessViolation(ViolationCode.UNAPPROVED_CAPABILITY, null));
            return decision(HarnessOutcome.BLOCK, violations, List.of(RecoveryAction.RETURN_SAFE_REFUSAL));
        }

        boolean fundamentalsAvailable = safeLedger.hasUsable(EvidenceDimension.FUNDAMENTALS);
        boolean marketAvailable = safeLedger.hasUsable(EvidenceDimension.MARKET);
        if (!fundamentalsAvailable) {
            violations.add(new HarnessViolation(
                    ViolationCode.FUNDAMENTALS_MISSING, EvidenceDimension.FUNDAMENTALS));
        }
        if (!marketAvailable) {
            violations.add(new HarnessViolation(
                    ViolationCode.MARKET_MISSING, EvidenceDimension.MARKET));
        }
        if (!safeLedger.hasUsable(EvidenceDimension.NEWS)) {
            violations.add(new HarnessViolation(ViolationCode.NEWS_MISSING, EvidenceDimension.NEWS));
        }

        boolean provenanceMissing =
                safeLedger.hasMissingProvenance(EvidenceDimension.FUNDAMENTALS)
                        || safeLedger.hasMissingProvenance(EvidenceDimension.MARKET);
        if (provenanceMissing) {
            violations.add(new HarnessViolation(ViolationCode.PROVENANCE_MISSING, null));
            return decision(HarnessOutcome.DEGRADE, violations, List.of(RecoveryAction.RETURN_NOT_RATED));
        }

        // 每类关键证据最多请求一次定向恢复；预算用尽后返回 NOT_RATED，而不是无限重试。
        List<RecoveryAction> recoveries = new ArrayList<>();
        if (!fundamentalsAvailable
                && safeContext.attempts(RecoveryAction.RETRY_FUNDAMENTALS) == 0) {
            recoveries.add(RecoveryAction.RETRY_FUNDAMENTALS);
        }
        if (!marketAvailable && safeContext.attempts(RecoveryAction.RETRY_MARKET) == 0) {
            recoveries.add(RecoveryAction.RETRY_MARKET);
        }
        if (!fundamentalsAvailable || !marketAvailable) {
            if (!recoveries.isEmpty()) {
                return decision(HarnessOutcome.RECOVER, violations, recoveries);
            }
            return decision(
                    HarnessOutcome.DEGRADE, violations, List.of(RecoveryAction.RETURN_NOT_RATED));
        }

        return decision(HarnessOutcome.PASS, violations, List.of());
    }

    /**
     * 对 Research Manager 的结构化报告执行第二阶段验收。
     *
     * @param context 报告重新综合的已用次数
     * @param ledger 与报告同一研究轮次的证据账本
     * @param synthesis 模型报告、解析状态和字段问题
     * @return 通过、最多一次重新综合或 NOT_RATED 降级决策
     */
    @Override
    public HarnessDecision afterReport(
            RunContext context,
            EvidenceLedger ledger,
            SynthesisResult synthesis
    ) {
        RunContext safeContext = context == null ? RunContext.deepResearch() : context;
        EvidenceLedger safeLedger = ledger == null ? EvidenceLedger.empty() : ledger;
        List<HarnessViolation> violations = new ArrayList<>();

        if (synthesis == null || synthesis.parseStatus() != HarnessModels.ParseStatus.VALID
                || synthesis.report() == null) {
            violations.add(new HarnessViolation(ViolationCode.REPORT_PARSE_INVALID, null));
            return repairOrDegrade(safeContext, violations);
        }

        InvestmentReport report = synthesis.report();
        String recommendation = report.getRecommendation() == null
                ? ""
                : report.getRecommendation().strip().toUpperCase(Locale.ROOT);
        // UNSPECIFIED 是合法的显式兼容值；null 表示新报告没有满足期限契约。
        AnalysisHorizon analysisHorizon = report.getAnalysisHorizon();
        boolean requiredFieldsPresent = report.getAnalystSummary() != null
                && !report.getAnalystSummary().isBlank()
                && report.getDataFreshness() != null
                && !report.getDataFreshness().isBlank()
                && report.getRationale() != null && !report.getRationale().isEmpty()
                && report.getRiskFactors() != null && !report.getRiskFactors().isEmpty()
                && report.getUnknowns() != null && !report.getUnknowns().isEmpty()
                && analysisHorizon != null
                && List.of("BUY", "OVERWEIGHT", "HOLD", "UNDERWEIGHT", "SELL")
                .contains(recommendation);
        if (!requiredFieldsPresent) {
            violations.add(new HarnessViolation(ViolationCode.REPORT_SCHEMA_INVALID, null));
        }
        if (report.getTicker() == null || !safeLedger.target().canonicalKey()
                .equals(report.getTicker().strip().toUpperCase(Locale.ROOT))) {
            violations.add(new HarnessViolation(ViolationCode.REPORT_TARGET_MISMATCH, null));
        }

        Set<String> knownEvidenceIds = safeLedger.evidenceIds();
        Set<String> usableEvidenceIds = safeLedger.usableEvidenceIds();
        boolean missingEvidenceReference =
                report.getEvidenceItems() == null || report.getEvidenceItems().isEmpty()
                || report.getEvidenceItems().stream()
                .anyMatch(item -> item.getSourceEvidenceIds() == null
                        || item.getSourceEvidenceIds().isEmpty());
        if (missingEvidenceReference) {
            violations.add(new HarnessViolation(
                    ViolationCode.REPORT_EVIDENCE_REFERENCE_MISSING, null));
        } else {
            List<String> referencedEvidenceIds = report.getEvidenceItems().stream()
                    .flatMap(item -> item.getSourceEvidenceIds().stream())
                    .toList();
            if (referencedEvidenceIds.stream()
                    .anyMatch(id -> !knownEvidenceIds.contains(id))) {
                violations.add(new HarnessViolation(
                        ViolationCode.REPORT_EVIDENCE_REFERENCE_UNKNOWN, null));
            }
            // “ID 存在”仍不够；失败、无来源、未审批或跨标的证据不能支撑投资结论。
            if (referencedEvidenceIds.stream()
                    .filter(knownEvidenceIds::contains)
                    .anyMatch(id -> !usableEvidenceIds.contains(id))) {
                violations.add(new HarnessViolation(
                        ViolationCode.REPORT_EVIDENCE_REFERENCE_UNUSABLE, null));
            }
        }

        if (!violations.isEmpty()) {
            return repairOrDegrade(safeContext, violations);
        }
        return decision(HarnessOutcome.PASS, List.of(), List.of());
    }

    /** 报告首次失败允许重综合一次，第二次仍失败则安全降级为不评级。 */
    private HarnessDecision repairOrDegrade(
            RunContext context,
            List<HarnessViolation> violations
    ) {
        if (context.attempts(RecoveryAction.RESYNTHESIZE_REPORT) == 0) {
            return decision(
                    HarnessOutcome.RECOVER,
                    violations,
                    List.of(RecoveryAction.RESYNTHESIZE_REPORT)
            );
        }
        return decision(
                HarnessOutcome.DEGRADE,
                violations,
                List.of(RecoveryAction.RETURN_NOT_RATED)
        );
    }

    private HarnessDecision decision(
            HarnessOutcome outcome,
            List<HarnessViolation> violations,
            List<RecoveryAction> recoveries
    ) {
        return new HarnessDecision(outcome, violations, recoveries);
    }
}
