package com.stocksage.harness;

import com.stocksage.harness.HarnessModels.EvidenceDimension;
import com.stocksage.harness.HarnessModels.EvidenceEnvelope;
import com.stocksage.harness.HarnessModels.EvidenceStatus;
import com.stocksage.harness.HarnessModels.HarnessOutcome;
import com.stocksage.harness.HarnessModels.ParseStatus;
import com.stocksage.harness.HarnessModels.RecoveryAction;
import com.stocksage.harness.HarnessModels.RunContext;
import com.stocksage.harness.HarnessModels.TargetIdentity;
import com.stocksage.harness.HarnessModels.ViolationCode;
import com.stocksage.model.dto.AnalysisHorizon;
import com.stocksage.model.dto.InvestmentReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class DeepResearchCompletionPolicyTest {

    private final DeepResearchCompletionPolicy policy = new DeepResearchCompletionPolicy();

    @ParameterizedTest(name = "{0}")
    @MethodSource("evidenceRules")
    void evaluatesEvidenceRuleTable(
            String name,
            EvidenceLedger ledger,
            RunContext context,
            HarnessOutcome expectedOutcome,
            ViolationCode expectedViolation,
            RecoveryAction expectedRecovery
    ) {
        var decision = policy.afterEvidence(context, ledger);

        assertThat(decision.outcome()).isEqualTo(expectedOutcome);
        if (expectedViolation == null) {
            assertThat(decision.violations()).isEmpty();
        } else {
            assertThat(decision.violations())
                    .extracting(HarnessModels.HarnessViolation::code)
                    .contains(expectedViolation);
        }
        if (expectedRecovery == null) {
            assertThat(decision.recoveryActions()).isEmpty();
        } else {
            assertThat(decision.recoveryActions()).contains(expectedRecovery);
        }
    }

    @Test
    void returnsExactlyTheSameDecisionForTheSameLedger() {
        EvidenceLedger ledger = completeLedger();

        var first = policy.afterEvidence(RunContext.deepResearch(), ledger);
        var second = policy.afterEvidence(RunContext.deepResearch(), ledger);

        assertThat(second).isEqualTo(first);
    }

    @Test
    void acceptsOnlyAValidReportBoundToKnownEvidence() {
        InvestmentReport report = validReport();

        var decision = policy.afterReport(
                RunContext.deepResearch(),
                completeLedger(),
                new HarnessModels.SynthesisResult(report, ParseStatus.VALID, List.of())
        );

        assertThat(decision.outcome()).isEqualTo(HarnessOutcome.PASS);
    }

    @Test
    void rejectsReportWithoutAnAnalysisHorizonAsSchemaInvalid() {
        InvestmentReport report = validReport();
        report.setAnalysisHorizon(null);

        var decision = policy.afterReport(
                RunContext.deepResearch(),
                completeLedger(),
                new HarnessModels.SynthesisResult(report, ParseStatus.VALID, List.of())
        );

        assertThat(decision.outcome()).isEqualTo(HarnessOutcome.RECOVER);
        assertThat(decision.violations())
                .extracting(HarnessModels.HarnessViolation::code)
                .containsExactly(ViolationCode.REPORT_SCHEMA_INVALID);
        assertThat(decision.recoveryActions())
                .containsExactly(RecoveryAction.RESYNTHESIZE_REPORT);
    }

    @Test
    void invalidReportCanResynthesizeOnceThenReturnsNotRated() {
        var synthesis = new HarnessModels.SynthesisResult(null, ParseStatus.INVALID_JSON, List.of());

        var first = policy.afterReport(RunContext.deepResearch(), completeLedger(), synthesis);
        var second = policy.afterReport(
                new RunContext("DEEP", Map.of(RecoveryAction.RESYNTHESIZE_REPORT, 1)),
                completeLedger(),
                synthesis
        );

        assertThat(first.outcome()).isEqualTo(HarnessOutcome.RECOVER);
        assertThat(first.recoveryActions()).containsExactly(RecoveryAction.RESYNTHESIZE_REPORT);
        assertThat(second.outcome()).isEqualTo(HarnessOutcome.DEGRADE);
        assertThat(second.recoveryActions()).containsExactly(RecoveryAction.RETURN_NOT_RATED);
    }

    @Test
    void rejectsReportReferencesToKnownButUnusableEvidence() {
        EvidenceEnvelope failedEvidence = new EvidenceEnvelope(
                "e-fundamentals-failed",
                EvidenceDimension.FUNDAMENTALS,
                "fundamentals",
                "AAPL",
                EvidenceStatus.FAILED,
                "tool:fundamentals",
                "test-provider",
                Instant.parse("2026-07-24T00:00:00Z"),
                Instant.parse("2026-07-24T00:00:00Z"),
                "hash-failed",
                true
        );
        EvidenceLedger ledger = ledger(failedEvidence);
        InvestmentReport report = InvestmentReport.builder()
                .ticker("AAPL")
                .recommendation("HOLD")
                .analystSummary("Bounded summary.")
                .dataFreshness("Observed 2026-07-24.")
                .rationale(List.of("A rationale must not rely on a failed call."))
                .riskFactors(List.of("Collection failure."))
                .unknowns(List.of("The missing fundamentals remain unknown."))
                .evidenceItems(List.of(InvestmentReport.EvidenceItem.builder()
                        .dimension("fundamentals")
                        .evidence("Failed evidence")
                        .implication("Must not support a rating")
                        .source("tool:fundamentals")
                        .sourceEvidenceIds(List.of("e-fundamentals-failed"))
                        .build()))
                .build();

        var decision = policy.afterReport(
                RunContext.deepResearch(),
                ledger,
                new HarnessModels.SynthesisResult(report, ParseStatus.VALID, List.of())
        );

        assertThat(decision.outcome()).isEqualTo(HarnessOutcome.RECOVER);
        assertThat(decision.violations())
                .extracting(HarnessModels.HarnessViolation::code)
                .containsExactly(ViolationCode.REPORT_EVIDENCE_REFERENCE_UNUSABLE);
        assertThat(decision.recoveryActions())
                .containsExactly(RecoveryAction.RESYNTHESIZE_REPORT);
    }

    private static Stream<Arguments> evidenceRules() {
        EvidenceLedger complete = completeLedger();
        EvidenceLedger noFundamentals = ledger(
                available(EvidenceDimension.MARKET, "market"));
        EvidenceLedger unresolved = new EvidenceLedger(TargetIdentity.unresolved(), List.of());
        EvidenceLedger mismatched = new EvidenceLedger(
                TargetIdentity.resolved("AAPL"),
                List.of(
                        available(EvidenceDimension.FUNDAMENTALS, "fundamentals"),
                        envelope(EvidenceDimension.MARKET, "market", "MSFT", true, true)
                )
        );
        EvidenceLedger missingProvenance = new EvidenceLedger(
                TargetIdentity.resolved("AAPL"),
                List.of(
                        envelope(EvidenceDimension.FUNDAMENTALS, "fundamentals", "AAPL", false, true),
                        available(EvidenceDimension.MARKET, "market")
                )
        );

        return Stream.of(
                Arguments.of(
                        "complete required evidence",
                        complete,
                        RunContext.deepResearch(),
                        HarnessOutcome.PASS,
                        null,
                        null
                ),
                Arguments.of(
                        "missing fundamentals can recover once",
                        noFundamentals,
                        RunContext.deepResearch(),
                        HarnessOutcome.RECOVER,
                        ViolationCode.FUNDAMENTALS_MISSING,
                        RecoveryAction.RETRY_FUNDAMENTALS
                ),
                Arguments.of(
                        "missing fundamentals degrades after recovery budget",
                        noFundamentals,
                        new RunContext("DEEP", Map.of(RecoveryAction.RETRY_FUNDAMENTALS, 1)),
                        HarnessOutcome.DEGRADE,
                        ViolationCode.FUNDAMENTALS_MISSING,
                        RecoveryAction.RETURN_NOT_RATED
                ),
                Arguments.of(
                        "unresolved target blocks",
                        unresolved,
                        RunContext.deepResearch(),
                        HarnessOutcome.BLOCK,
                        ViolationCode.TARGET_UNRESOLVED,
                        RecoveryAction.RETURN_SAFE_REFUSAL
                ),
                Arguments.of(
                        "mismatched target blocks",
                        mismatched,
                        RunContext.deepResearch(),
                        HarnessOutcome.BLOCK,
                        ViolationCode.TARGET_MISMATCH,
                        RecoveryAction.RETURN_SAFE_REFUSAL
                ),
                Arguments.of(
                        "missing provenance degrades",
                        missingProvenance,
                        RunContext.deepResearch(),
                        HarnessOutcome.DEGRADE,
                        ViolationCode.PROVENANCE_MISSING,
                        RecoveryAction.RETURN_NOT_RATED
                )
        );
    }

    private static InvestmentReport validReport() {
        return InvestmentReport.builder()
                .ticker("AAPL")
                .recommendation("HOLD")
                .analysisHorizon(AnalysisHorizon.LONG_TERM)
                .analystSummary("Balanced evidence.")
                .dataFreshness("Observed 2026-07-24.")
                .rationale(List.of("Revenue and market data are available."))
                .riskFactors(List.of("Valuation risk."))
                .unknowns(List.of("Future guidance."))
                .evidenceItems(List.of(InvestmentReport.EvidenceItem.builder()
                        .dimension("market")
                        .evidence("Market evidence")
                        .implication("Supports a bounded rating")
                        .source("tool:market")
                        .sourceEvidenceIds(List.of("e-MARKET-market"))
                        .build()))
                .build();
    }

    private static EvidenceLedger completeLedger() {
        return ledger(
                available(EvidenceDimension.FUNDAMENTALS, "fundamentals"),
                available(EvidenceDimension.MARKET, "market"),
                available(EvidenceDimension.NEWS, "news")
        );
    }

    private static EvidenceLedger ledger(EvidenceEnvelope... evidence) {
        return new EvidenceLedger(TargetIdentity.resolved("AAPL"), List.of(evidence));
    }

    private static EvidenceEnvelope available(EvidenceDimension dimension, String capability) {
        return envelope(dimension, capability, "AAPL", true, true);
    }

    private static EvidenceEnvelope envelope(
            EvidenceDimension dimension,
            String capability,
            String target,
            boolean provenance,
            boolean approvedReadOnly
    ) {
        return new EvidenceEnvelope(
                "e-" + dimension + "-" + capability,
                dimension,
                capability,
                target,
                EvidenceStatus.AVAILABLE,
                provenance ? "tool:" + capability : "",
                provenance ? "test-provider" : "",
                provenance ? Instant.parse("2026-07-24T00:00:00Z") : null,
                provenance ? Instant.parse("2026-07-24T00:00:00Z") : null,
                provenance ? "hash-" + capability : "",
                approvedReadOnly
        );
    }
}
