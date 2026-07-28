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
import com.stocksage.model.dto.InvestmentReport;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * V1 deterministic evidence policy for DEEP equity research.
 */
@Component
public class DeepResearchCompletionPolicy implements ResearchCompletionPolicy {

    public static final String POLICY_ID = "deep-equity-v1";
    public static final int POLICY_VERSION = 2;

    @Override
    public String policyId() {
        return POLICY_ID;
    }

    @Override
    public int policyVersion() {
        return POLICY_VERSION;
    }

    @Override
    public HarnessDecision afterEvidence(RunContext context, EvidenceLedger ledger) {
        RunContext safeContext = context == null ? RunContext.deepResearch() : context;
        EvidenceLedger safeLedger = ledger == null ? EvidenceLedger.empty() : ledger;
        List<HarnessViolation> violations = new ArrayList<>();

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
        boolean requiredFieldsPresent = report.getAnalystSummary() != null
                && !report.getAnalystSummary().isBlank()
                && report.getDataFreshness() != null
                && !report.getDataFreshness().isBlank()
                && report.getRationale() != null && !report.getRationale().isEmpty()
                && report.getRiskFactors() != null && !report.getRiskFactors().isEmpty()
                && report.getUnknowns() != null && !report.getUnknowns().isEmpty()
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
