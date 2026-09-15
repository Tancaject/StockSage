package com.stocksage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.agent.PlanRoute;
import com.stocksage.agent.ReadRequest;
import com.stocksage.harness.HarnessModels.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class EvidenceEnvelopeMapperTest {
    @Test void ordinaryAndDeepRejectMetadataWithoutBusinessDataAndKeepZeroValues() {
        var mapper = new EvidenceEnvelopeMapper(new TickerResolutionService(null, null, new ObjectMapper()));
        String sec = "{\"ticker\":\"AAPL\",\"cik\":\"320193\",\"metric_count\":0,\"metrics\":{}}";
        for (String payload : List.of(sec, sec.replace("{}", "{\"revenue\":{\"data\":[{\"year\":2025,\"value\":null}]}}"),
                "{\"provider\":\"SEC\",\"reports\":[{\"year\":2025,\"quarter\":4}]}")) {
            assertThat(mapper.map(EvidenceDimension.FUNDAMENTALS, "AAPL", "getFinancialReports", payload,
                    EvidenceStatus.AVAILABLE, Instant.now(), true).status()).isEqualTo(EvidenceStatus.EMPTY);
            var ordinary = new OrdinaryEvidence("AAPL", ReadRequest.parse(PlanRoute.FUNDAMENTALS, "财报", Map.of()), new ObjectMapper(), mapper);
            ordinary.add("getFinancialReports", EvidenceDimension.FUNDAMENTALS, payload);
            assertThat(ordinary.hasUsefulResult()).isFalse();
        }
        for (String payload : List.of("{\"data\":{\"data\":[]}}", "{\"data\":[{\"t\":1788000000000}]}",
                "{\"data\":[{\"o\":\"NaN\",\"h\":2,\"l\":0,\"c\":1}]}")) {
            assertThat(mapper.inspectStatus("Market Agent/getIbkrHistoricalBars(3m,1d)", payload)).isEqualTo(EvidenceStatus.EMPTY);
        }
        assertThat(mapper.inspectStatus("getStructuredFinancials", "{\"cik\":\"320193\",\"metrics\":{\"revenue\":{\"data\":[{\"value\":0}]}}}"))
                .isEqualTo(EvidenceStatus.AVAILABLE);
        for (String tool : List.of("getStructuredFinancials", "getFinancialReports")) {
            String metadataOnly = "{\"cik\":\"320193\",\"metrics\":{\"revenue\":{\"data\":[{\"filing_fiscal_year\":2025,\"value\":null}]}}}";
            assertThat(mapper.inspectStatus(tool, metadataOnly)).isEqualTo(EvidenceStatus.EMPTY);
            assertThat(mapper.inspectStatus(tool, metadataOnly.replace("null", "0"))).isEqualTo(EvidenceStatus.AVAILABLE);
            var ordinary = new OrdinaryEvidence("AAPL", ReadRequest.parse(PlanRoute.FUNDAMENTALS, "财报", Map.of()), new ObjectMapper(), mapper);
            ordinary.add(tool, EvidenceDimension.FUNDAMENTALS, metadataOnly);
            assertThat(ordinary.hasUsefulResult()).isFalse();
        }
        assertThat(mapper.inspectStatus("getFinancialReports", "{\"reports\":[{\"year\":2025,\"statements\":{\"profit\":{\"roe\":\"0\"}}}]}"))
                .isEqualTo(EvidenceStatus.AVAILABLE);
        assertThat(mapper.inspectStatus("getTechnicalIndicators", "{\"indicators\":{\"RSI14\":null}}"))
                .isEqualTo(EvidenceStatus.EMPTY);
        assertThat(mapper.inspectStatus("getIbkrHistoricalBars", "{\"message\":\"unavailable\", \"error\": true}"))
                .isEqualTo(EvidenceStatus.FAILED);
        assertThat(mapper.map(EvidenceDimension.NEWS, "AAPL", "searchNews", "{\"provider\":\"tavily\",\"results\":[]}",
                EvidenceStatus.AVAILABLE, Instant.now(), true).status()).isEqualTo(EvidenceStatus.NO_RESULTS);
    }
}
