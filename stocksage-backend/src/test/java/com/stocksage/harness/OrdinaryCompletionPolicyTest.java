package com.stocksage.harness;

import com.stocksage.evidence.EvidenceTiming;
import com.stocksage.evidence.EvidenceLedger;
import com.stocksage.evidence.EvidenceModels.EvidenceDimension;
import com.stocksage.evidence.EvidenceModels.EvidenceStatus;
import com.stocksage.evidence.EvidenceModels.TargetIdentity;
import com.stocksage.evidence.EvidenceModels.EvidenceEnvelope;

import com.stocksage.agent.intent.TimeSensitivity;
import com.stocksage.harness.HarnessModels.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OrdinaryCompletionPolicyTest {
    private static final Instant OBSERVED = Instant.parse("2026-09-26T00:00:00Z");
    private final OrdinaryCompletionPolicy policy = new OrdinaryCompletionPolicy();

    @Test void appliedNewsWindowPassesAtTheRecordedObservationTime() {
        var evidence = news(OBSERVED.minusSeconds(3600), 0);
        assertThat(evaluate("NEWS", TimeSensitivity.RECENT, evidence).outcome()).isEqualTo(HarnessOutcome.PASS);
    }

    @Test void unknownOrExpiredTimeDegradesWithoutRemovingCitableFacts() {
        for (var evidence : List.of(news(OBSERVED.minusSeconds(3600), 1),
                news(OBSERVED.minusSeconds(8 * 86400), 0),
                envelope(EvidenceDimension.NEWS, EvidenceStatus.AVAILABLE, null))) {
            var decision = evaluate("NEWS", TimeSensitivity.RECENT, evidence);
            assertThat(decision.outcome()).isEqualTo(HarnessOutcome.DEGRADE);
            assertThat(decision.violations()).extracting(HarnessViolation::code)
                    .containsExactly(ViolationCode.EVIDENCE_TIME_REQUIREMENT_UNMET);
            assertThat(ledger(evidence).usableEvidenceIds()).containsExactly("E1");
        }
    }

    @Test void financialPeriodFactsDoNotProveLatestReportCoverage() {
        var evidence = envelope(EvidenceDimension.FUNDAMENTALS, EvidenceStatus.AVAILABLE,
                new EvidenceTiming(1, OBSERVED, null,
                        new EvidenceTiming.Financial("annual", LocalDate.parse("2025-12-31"), LocalDate.parse("2026-02-01")), null));
        assertThat(evaluate("FUNDAMENTALS", TimeSensitivity.UNSPECIFIED, evidence).outcome()).isEqualTo(HarnessOutcome.PASS);
        assertThat(evaluate("FUNDAMENTALS", TimeSensitivity.RECENT, evidence).outcome()).isEqualTo(HarnessOutcome.DEGRADE);
    }

    @Test void emptySearchCompletesWithoutCreatingCitableEvidence() {
        var evidence = envelope(EvidenceDimension.NEWS, EvidenceStatus.NO_RESULTS,
                new EvidenceTiming(1, OBSERVED, null, null,
                        new EvidenceTiming.Search("w", "w", null, null, null, null, null, 0, 0, 0)));
        assertThat(evaluate("NEWS", TimeSensitivity.RECENT, evidence).outcome()).isEqualTo(HarnessOutcome.PASS);
        assertThat(ledger(evidence).usableEvidenceIds()).isEmpty();
    }

    private EvidenceEnvelope news(Instant publishedAt, int unknownCount) {
        return envelope(EvidenceDimension.NEWS, EvidenceStatus.AVAILABLE,
                new EvidenceTiming(1, OBSERVED, null, null,
                        new EvidenceTiming.Search("w", "w", null, publishedAt, publishedAt, null, null, 1, 0, unknownCount)));
    }

    private EvidenceEnvelope envelope(EvidenceDimension dimension, EvidenceStatus status, EvidenceTiming timing) {
        return new EvidenceEnvelope("E1", dimension, "read-only", "AAPL", status,
                "https://example.org/source", "provider", OBSERVED, OBSERVED, "hash", true, timing);
    }

    private EvidenceLedger ledger(EvidenceEnvelope evidence) {
        return new EvidenceLedger(TargetIdentity.resolved("AAPL"), List.of(evidence));
    }

    private HarnessDecision evaluate(String workflow, TimeSensitivity requirement, EvidenceEnvelope evidence) {
        return policy.afterEvidence(new RunContext(workflow, Map.of(), requirement), ledger(evidence));
    }
}
