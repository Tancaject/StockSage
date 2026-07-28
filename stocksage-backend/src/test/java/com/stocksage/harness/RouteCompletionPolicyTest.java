package com.stocksage.harness;

import com.stocksage.harness.HarnessModels.EvidenceDimension;
import com.stocksage.harness.HarnessModels.EvidenceEnvelope;
import com.stocksage.harness.HarnessModels.EvidenceStatus;
import com.stocksage.harness.HarnessModels.HarnessOutcome;
import com.stocksage.harness.HarnessModels.RunContext;
import com.stocksage.harness.HarnessModels.TargetIdentity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RouteCompletionPolicyTest {

    @Test
    void marketRequiresProvenancedMarketEvidence() {
        MarketCompletionPolicy policy = new MarketCompletionPolicy();

        assertThat(policy.afterEvidence(
                RunContext.deepResearch(),
                ledger(available(EvidenceDimension.MARKET, EvidenceStatus.AVAILABLE))
        ).outcome()).isEqualTo(HarnessOutcome.PASS);
        assertThat(policy.afterEvidence(
                RunContext.deepResearch(),
                ledger()
        ).outcome()).isEqualTo(HarnessOutcome.RECOVER);
    }

    @Test
    void newsDistinguishesValidNoResultsFromProviderFailure() {
        NewsCompletionPolicy policy = new NewsCompletionPolicy();

        assertThat(policy.afterEvidence(
                RunContext.deepResearch(),
                ledger(available(EvidenceDimension.NEWS, EvidenceStatus.NO_RESULTS))
        ).outcome()).isEqualTo(HarnessOutcome.PASS);
        assertThat(policy.afterEvidence(
                RunContext.deepResearch(),
                ledger(available(EvidenceDimension.NEWS, EvidenceStatus.FAILED))
        ).outcome()).isEqualTo(HarnessOutcome.RECOVER);
    }

    @Test
    void ragRequiresRetrievalProvenance() {
        RagCompletionPolicy policy = new RagCompletionPolicy();

        assertThat(policy.afterEvidence(
                RunContext.deepResearch(),
                ledger(available(EvidenceDimension.RAG, EvidenceStatus.AVAILABLE))
        ).outcome()).isEqualTo(HarnessOutcome.PASS);
    }

    private EvidenceLedger ledger(EvidenceEnvelope... evidence) {
        return new EvidenceLedger(TargetIdentity.resolved("AAPL"), List.of(evidence));
    }

    private EvidenceEnvelope available(EvidenceDimension dimension, EvidenceStatus status) {
        Instant now = Instant.parse("2026-07-24T00:00:00Z");
        return new EvidenceEnvelope(
                "e-" + dimension.name(),
                dimension,
                "test-capability",
                "AAPL",
                status,
                "tool:test",
                "test-provider",
                now,
                now,
                "payload-hash",
                true
        );
    }
}
