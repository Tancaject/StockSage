package com.stocksage.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.harness.HarnessModels.EvidenceDimension;
import com.stocksage.harness.HarnessModels.EvidenceEnvelope;
import com.stocksage.harness.HarnessModels.EvidenceStatus;
import com.stocksage.harness.HarnessModels.TargetIdentity;
import com.stocksage.model.dto.AnalysisState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

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
}
