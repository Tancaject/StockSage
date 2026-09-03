package com.stocksage.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.harness.HarnessModels.EvidenceDimension;
import com.stocksage.harness.HarnessModels.EvidenceEnvelope;
import com.stocksage.harness.HarnessModels.EvidenceStatus;
import com.stocksage.harness.HarnessModels.HarnessDecision;
import com.stocksage.harness.HarnessModels.HarnessOutcome;
import com.stocksage.harness.HarnessModels.HarnessPhase;
import com.stocksage.harness.HarnessModels.HarnessSnapshot;
import com.stocksage.harness.HarnessModels.RecoveryAction;
import com.stocksage.harness.HarnessModels.RecoveryLifecycle;
import com.stocksage.harness.HarnessModels.SuspendedRecovery;
import com.stocksage.harness.HarnessModels.TargetIdentity;
import com.stocksage.harness.HarnessModels.ViolationCode;
import com.stocksage.model.dto.AnalysisState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisStateHarnessSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void legacyCheckpointWithoutLedgerStillDeserializesToEmptyLedger() throws Exception {
        AnalysisState state = objectMapper.readValue(
                "{\"query\":\"q\",\"primaryTicker\":\"AAPL\"}",
                AnalysisState.class
        );

        assertThat(state.getEvidenceLedger()).isNotNull();
        assertThat(state.getEvidenceLedger().evidence()).isEmpty();
        assertThat(state.getEvidenceLedger().target().isResolved()).isFalse();
    }

    @Test
    void structuredLedgerRoundTripsWithoutRawToolPayload() throws Exception {
        Instant observedAt = Instant.parse("2026-07-24T00:00:00Z");
        EvidenceLedger ledger = new EvidenceLedger(
                TargetIdentity.resolved("AAPL"),
                List.of(new EvidenceEnvelope(
                        "e-fundamentals",
                        EvidenceDimension.FUNDAMENTALS,
                        "getFinancialReports",
                        "AAPL",
                        EvidenceStatus.AVAILABLE,
                        "tool:getFinancialReports",
                        "stocksage-local-tools",
                        observedAt,
                        observedAt,
                        "payload-hash",
                        true
                ))
        );
        AnalysisState original = AnalysisState.builder()
                .query("Should I buy AAPL?")
                .primaryTicker("AAPL")
                .evidenceLedger(ledger)
                .build();

        String json = objectMapper.writeValueAsString(original);
        AnalysisState restored = objectMapper.readValue(json, AnalysisState.class);

        assertThat(restored.getEvidenceLedger()).isEqualTo(ledger);
        assertThat(json).contains("\"payloadHash\":\"payload-hash\"");
        assertThat(json).doesNotContain("rawPayload");
    }

    @Test
    void pendingRecoveryEffectRoundTripsWithStableKeyAndReservedAction() throws Exception {
        String policyTag = DeepResearchCompletionPolicy.POLICY_ID
                + "-v" + DeepResearchCompletionPolicy.POLICY_VERSION;
        AnalysisState original = AnalysisState.builder()
                .query("Should I buy AAPL?")
                .primaryTicker("AAPL")
                .build();
        original.setHarnessSnapshot(HarnessSnapshot.recovery(
                "deep-equity-v1",
                Integer.toString(DeepResearchCompletionPolicy.POLICY_VERSION),
                HarnessPhase.EVIDENCE,
                new HarnessDecision(
                        HarnessOutcome.RECOVER,
                        List.of(),
                        List.of(RecoveryAction.RETRY_FUNDAMENTALS)
                ),
                Map.of(),
                RecoveryLifecycle.PLANNED,
                List.of(RecoveryAction.RETRY_FUNDAMENTALS),
                "deep-evidence:42:" + policyTag + ":retry_fundamentals-1",
                new SuspendedRecovery(
                        HarnessPhase.REPORT,
                        HarnessOutcome.RECOVER,
                        List.of(ViolationCode.REPORT_SCHEMA_INVALID),
                        RecoveryLifecycle.PLANNED,
                        List.of(RecoveryAction.RESYNTHESIZE_REPORT),
                        "deep-report:trace-42:" + policyTag + ":resynthesize_report-1"
                )
        ));
        original.setEvidenceReplanState(new AnalysisState.EvidenceReplanState(
                1,
                AnalysisState.EvidenceReplanStatus.PLANNED,
                AnalysisState.EvidenceReplanAction.FOCUSED_NEWS_SEARCH,
                "AAPL regulatory risk",
                "FRESHNESS_GAP",
                "deep-replan:42:v1:focused-news:hash",
                List.of(),
                null
        ));

        AnalysisState restored = objectMapper.readValue(
                objectMapper.writeValueAsString(original),
                AnalysisState.class
        );

        assertThat(restored.getHarnessSnapshot().recoveryLifecycle())
                .isEqualTo(RecoveryLifecycle.PLANNED);
        assertThat(restored.getHarnessSnapshot().recoveryActions())
                .containsExactly(RecoveryAction.RETRY_FUNDAMENTALS);
        assertThat(restored.getHarnessSnapshot().recoveryEffectKey())
                .isEqualTo("deep-evidence:42:" + policyTag + ":retry_fundamentals-1");
        assertThat(restored.getHarnessSnapshot().suspendedRecovery()).isNotNull();
        assertThat(restored.getHarnessSnapshot().suspendedRecovery().recoveryLifecycle())
                .isEqualTo(RecoveryLifecycle.PLANNED);
        assertThat(restored.getHarnessSnapshot().suspendedRecovery().recoveryActions())
                .containsExactly(RecoveryAction.RESYNTHESIZE_REPORT);
        assertThat(restored.getHarnessSnapshot().suspendedRecovery().recoveryEffectKey())
                .isEqualTo("deep-report:trace-42:" + policyTag + ":resynthesize_report-1");
        assertThat(restored.getHarnessSnapshot().hasPendingEvidenceRecovery()).isTrue();
        assertThat(restored.getEvidenceReplanState())
                .isEqualTo(original.getEvidenceReplanState());
    }
}
