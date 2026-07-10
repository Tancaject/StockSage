package com.stocksage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.model.dto.AnalysisState;
import com.stocksage.model.dto.InvestmentReport;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OfflineDemoSampleServiceTest {

    private final OfflineDemoSampleService service =
            new OfflineDemoSampleService(new ObjectMapper().findAndRegisterModules());

    @Test
    void loadsApiIndependentCockpitSampleForDefaultWatchlistTicker() {
        var sample = service.findCockpitSample("nvda");

        assertThat(sample).isPresent();
        assertThat(sample.orElseThrow().ticker()).isEqualTo("NVDA");
        assertThat(sample.orElseThrow().chartStatus()).isEqualTo("SAMPLE");
        assertThat(sample.orElseThrow().chart()).containsEntry("sourceTool", "offlineDemoSample");
        assertThat(sample.orElseThrow().latestReport().modelName()).isEqualTo("offline-rule-fallback");
        assertThat(sample.orElseThrow().evidencePreview()).isNotEmpty();
    }

    @Test
    void buildsDeterministicFallbackReportFromSampleDataset() {
        AnalysisState state = AnalysisState.builder()
                .query("Should I buy NVDA?")
                .primaryTicker("nvda")
                .build();

        InvestmentReport report = service.buildFallbackReport(state).orElseThrow();

        assertThat(report.getTicker()).isEqualTo("NVDA");
        assertThat(report.getRecommendation()).isEqualTo("HOLD");
        assertThat(report.getModelTier()).isEqualTo("DEMO");
        assertThat(report.getModelName()).isEqualTo("offline-rule-fallback");
        assertThat(report.getAnalystSummary()).contains("离线演示回退报告");
        assertThat(report.getDataFreshness()).contains("离线样本");
        assertThat(report.getEvidenceItems()).isNotEmpty();
        assertThat(report.getCitations()).anyMatch(citation -> citation.contains("离线样本"));
    }
}
