package com.stocksage.harness;

import com.stocksage.evidence.EvidenceLedger;

import com.stocksage.agent.AgentStep;
import com.stocksage.evidence.EvidenceModels.EvidenceDimension;
import com.stocksage.evidence.EvidenceModels.EvidenceEnvelope;
import com.stocksage.evidence.EvidenceModels.EvidenceStatus;
import com.stocksage.harness.HarnessModels.HarnessOutcome;
import com.stocksage.harness.HarnessModels.RunContext;
import com.stocksage.evidence.EvidenceModels.TargetIdentity;
import com.stocksage.trace.TraceService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResearchHarnessTest {

    @Test
    void recordsPolicyDecisionWithoutExposingTargetOrPayload() {
        TraceService traceService = mock(TraceService.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HarnessObserver observer = new HarnessObserver(traceService, registry);
        ResearchHarness harness = new ResearchHarness(observer);
        DeepResearchCompletionPolicy policy = new DeepResearchCompletionPolicy();
        EvidenceLedger ledger = new EvidenceLedger(
                TargetIdentity.resolved("AAPL"),
                List.of(availableMarketEvidence())
        );

        var decision = harness.observeEvidence(
                "trace-1", policy, RunContext.deepResearch(), ledger);

        assertThat(decision.outcome()).isEqualTo(HarnessOutcome.RECOVER);
        assertThat(registry.get("stocksage.harness.decisions")
                .tags("policy", "deep-equity-v1", "phase", "evidence", "outcome", "recover")
                .counter().count()).isEqualTo(1);
        ArgumentCaptor<AgentStep> stepCaptor = ArgumentCaptor.forClass(AgentStep.class);
        verify(traceService).addStep(org.mockito.ArgumentMatchers.eq("trace-1"), stepCaptor.capture());
        AgentStep step = stepCaptor.getValue();
        assertThat(step.getAttributes())
                .containsEntry("policyId", "deep-equity-v1")
                .containsEntry("decision", "RECOVER")
                .containsEntry("stepKind", "harness_decision")
                .containsEntry("recoveryLifecycle", "SUGGESTED")
                .doesNotContainKey("recoveryEffectKey")
                .doesNotContainKeys("query", "userId", "ticker", "traceId");
        assertThat(step.getActionInput()).doesNotContain("AAPL");
        assertThat(step.getObservation()).doesNotContain("AAPL");
    }

    @Test
    void policyFailureBlocksWhileObserverFailureCannotChangeDecision() {
        HarnessObserver observer = mock(HarnessObserver.class);
        ResearchCompletionPolicy failingPolicy = mock(ResearchCompletionPolicy.class);
        when(failingPolicy.policyId()).thenReturn("test-policy");
        when(failingPolicy.afterEvidence(any(), any()))
                .thenThrow(new IllegalStateException("policy bug"));
        ResearchHarness harness = new ResearchHarness(observer);

        var fallback = harness.observeEvidence(
                "trace-1", failingPolicy, RunContext.deepResearch(), EvidenceLedger.empty());

        assertThat(fallback.outcome()).isEqualTo(HarnessOutcome.BLOCK);

        DeepResearchCompletionPolicy workingPolicy = new DeepResearchCompletionPolicy();
        doThrow(new IllegalStateException("metrics down"))
                .when(observer)
                .evidenceDecision(any(), any(), any(), any(), anyLong());

        var observed = harness.observeEvidence(
                "trace-1",
                workingPolicy,
                RunContext.deepResearch(),
                new EvidenceLedger(TargetIdentity.resolved("AAPL"), List.of(availableMarketEvidence()))
        );

        assertThat(observed.outcome()).isEqualTo(HarnessOutcome.RECOVER);
    }

    private EvidenceEnvelope availableMarketEvidence() {
        Instant observedAt = Instant.parse("2026-07-24T00:00:00Z");
        return new EvidenceEnvelope(
                "e-market",
                EvidenceDimension.MARKET,
                "getBars",
                "AAPL",
                EvidenceStatus.AVAILABLE,
                "tool:getBars",
                "test-provider",
                observedAt,
                observedAt,
                "payload-hash",
                true
        );
    }
}
