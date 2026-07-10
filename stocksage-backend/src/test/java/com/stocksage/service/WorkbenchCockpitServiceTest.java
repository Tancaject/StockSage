package com.stocksage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.model.dto.InvestmentReport;
import com.stocksage.model.dto.InvestmentReportVersionSummary;
import com.stocksage.model.dto.WorkbenchCockpitResponse;
import com.stocksage.model.entity.ResearchTask;
import com.stocksage.repository.ResearchTaskRepository;
import com.stocksage.tool.MarketTools;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkbenchCockpitServiceTest {

    private final MarketTools marketTools = mock(MarketTools.class);
    private final InvestmentReportVersionService reportVersionService = mock(InvestmentReportVersionService.class);
    private final ResearchTaskRepository researchTaskRepository = mock(ResearchTaskRepository.class);
    private final OfflineDemoSampleService offlineDemoSampleService = mock(OfflineDemoSampleService.class);
    private final WorkbenchCockpitService service = new WorkbenchCockpitService(
            marketTools,
            reportVersionService,
            researchTaskRepository,
            new KLinePayloadMapper(new ObjectMapper()),
            offlineDemoSampleService,
            new ObjectMapper()
    );

    @Test
    void aggregatesUsTickerChartReportEvidenceAndRedactedTaskTimeline() throws Exception {
        when(marketTools.getIbkrHistoricalBars("NVDA", "1y", "1d"))
                .thenReturn("""
                        {
                          "symbol": "NVDA",
                          "bar": "1d",
                          "data": {"provider": "IBKR", "bars": [
                            {"time": "2026-06-04", "o": 119.0, "h": 121.0, "l": 118.5, "c": 120.2, "v": 1000}
                          ]}
                        }
                        """);
        InvestmentReport.EvidenceItem evidence = InvestmentReport.EvidenceItem.builder()
                .dimension("Margins")
                .evidence("Gross margin expanded year over year.")
                .implication("Supports operating leverage.")
                .source("10-Q")
                .build();
        InvestmentReportVersionSummary summary = new InvestmentReportVersionSummary(
                7L,
                20L,
                "NVDA",
                3,
                "WATCH",
                "d".repeat(64),
                "c".repeat(64),
                "STRONG",
                "qwen3.6-max",
                LocalDateTime.parse("2026-06-04T12:00:00"),
                LocalDateTime.parse("2026-06-04T12:01:00"),
                "Should I buy NVDA?",
                "Watch with valuation discipline."
        );
        when(reportVersionService.findLatestReportDetail("u_001", "NVDA"))
                .thenReturn(Optional.of(new InvestmentReportVersionService.ReportDetail(
                        summary,
                        List.of(evidence),
                        List.of("[1] NVDA 10-Q")
                )));
        ResearchTask task = researchTask();
        task.setLeaseToken("secret-owner-token");
        when(researchTaskRepository.findByUserIdAndTickerOrderByCreatedAtDesc(
                eq("u_001"),
                eq("NVDA"),
                any(Pageable.class)
        )).thenReturn(List.of(task));
        when(offlineDemoSampleService.findCockpitSample("NVDA")).thenReturn(Optional.empty());

        var response = service.getCockpit("u_001", "nvda", "daily", 120);

        assertThat(response.ticker()).isEqualTo("NVDA");
        assertThat(response.chartStatus()).isEqualTo("READY");
        assertThat(response.chart()).containsEntry("sourceTool", "getIbkrHistoricalBars");
        assertThat(response.latestReport().modelName()).isEqualTo("qwen3.6-max");
        assertThat(response.evidencePreview()).hasSize(1);
        assertThat(response.evidencePreview().get(0).source()).isEqualTo("10-Q");
        assertThat(response.taskTimeline()).hasSize(1);
        assertThat(response.taskTimeline().get(0).status()).isEqualTo("RUNNING");
        String serialized = new ObjectMapper().findAndRegisterModules().writeValueAsString(response);
        assertThat(serialized)
                .doesNotContain("leaseToken")
                .doesNotContain("secret-owner-token");
    }

    @Test
    void returnsDegradedChartStatusWhenMarketToolHasNoCandles() {
        when(marketTools.getStockKLine("600519", "daily", 120))
                .thenReturn("{\"code\":\"600519\",\"data\":[]}");
        when(reportVersionService.findLatestReportDetail("u_001", "600519"))
                .thenReturn(Optional.empty());
        when(researchTaskRepository.findByUserIdAndTickerOrderByCreatedAtDesc(
                eq("u_001"),
                eq("600519"),
                any(Pageable.class)
        )).thenReturn(List.of());
        when(offlineDemoSampleService.findCockpitSample("600519")).thenReturn(Optional.empty());

        var response = service.getCockpit("u_001", "600519", "daily", 120);

        assertThat(response.chart()).isNull();
        assertThat(response.chartStatus()).isEqualTo("DEGRADED");
        assertThat(response.chartMessage()).contains("暂无可用 K 线数据");
        assertThat(response.latestReport()).isNull();
        assertThat(response.evidencePreview()).isEmpty();
    }

    @Test
    void surfacesIbkrGatewayAuthErrorWhenHistoricalBarsReturnErrorPayload() {
        when(marketTools.getIbkrHistoricalBars("NVDA", "1y", "1d"))
                .thenReturn("""
                        {
                          "error": true,
                          "provider": "IBKR_WEB_API",
                          "message": "IBKR Client Portal Gateway session is not authenticated (401 Unauthorized). Log in at https://localhost:5000, complete any required 2FA, then retry."
                        }
                        """);
        when(reportVersionService.findLatestReportDetail("u_001", "NVDA"))
                .thenReturn(Optional.empty());
        when(researchTaskRepository.findByUserIdAndTickerOrderByCreatedAtDesc(
                eq("u_001"),
                eq("NVDA"),
                any(Pageable.class)
        )).thenReturn(List.of());
        when(offlineDemoSampleService.findCockpitSample("NVDA")).thenReturn(Optional.empty());

        var response = service.getCockpit("u_001", "nvda", "daily", 120);

        assertThat(response.chart()).isNull();
        assertThat(response.chartStatus()).isEqualTo("DEGRADED");
        assertThat(response.chartMessage())
                .contains("Gateway session is not authenticated")
                .contains("https://localhost:5000");
    }

    @Test
    void surfacesIbkrGatewayTimeoutWhenCockpitHistoryTimesOut() {
        when(marketTools.getIbkrHistoricalBars("SNDK", "1y", "1d"))
                .thenReturn("""
                        {
                          "error": true,
                          "provider": "IBKR_WEB_API",
                          "message": "IBKR Client Portal Gateway timed out after 10000ms while calling historicalBars."
                        }
                        """);
        when(reportVersionService.findLatestReportDetail("u_001", "SNDK"))
                .thenReturn(Optional.empty());
        when(researchTaskRepository.findByUserIdAndTickerOrderByCreatedAtDesc(
                eq("u_001"),
                eq("SNDK"),
                any(Pageable.class)
        )).thenReturn(List.of());
        when(offlineDemoSampleService.findCockpitSample("SNDK")).thenReturn(Optional.empty());

        var response = service.getCockpit("u_001", "sndk", "daily", 120);

        assertThat(response.chart()).isNull();
        assertThat(response.chartStatus()).isEqualTo("DEGRADED");
        assertThat(response.chartMessage())
                .contains("K 线暂不可用")
                .contains("timed out")
                .contains("10000ms");
    }

    @Test
    void surfacesIbkrIntradayHistoryErrorPayload() {
        when(marketTools.getIbkrHistoricalBars("SNDK", "24h", "5min"))
                .thenReturn("""
                        {
                          "error": true,
                          "provider": "IBKR_WEB_API",
                          "message": "IBKR Client Portal Gateway returned 500 Internal Server Error from the history endpoint. Retry or use the daily range."
                        }
                        """);

        var response = service.getIntradayChart("sndk");

        verify(marketTools).getIbkrHistoricalBars("SNDK", "24h", "5min");
        assertThat(response).containsEntry("error", true);
        assertThat((String) response.get("message"))
                .contains("日内 K 线暂不可用")
                .contains("Gateway returned 500")
                .contains("daily range");
    }

    @Test
    void usesOfflineSampleWhenLiveCockpitInputsAreUnavailable() {
        WorkbenchCockpitResponse sample = sampleCockpit();
        when(marketTools.getIbkrHistoricalBars("NVDA", "1y", "1d"))
                .thenReturn("{\"symbol\":\"NVDA\",\"data\":{\"bars\":[]}}");
        when(reportVersionService.findLatestReportDetail("u_001", "NVDA"))
                .thenReturn(Optional.empty());
        when(researchTaskRepository.findByUserIdAndTickerOrderByCreatedAtDesc(
                eq("u_001"),
                eq("NVDA"),
                any(Pageable.class)
        )).thenReturn(List.of());
        when(offlineDemoSampleService.findCockpitSample("NVDA")).thenReturn(Optional.of(sample));

        var response = service.getCockpit("u_001", "nvda", "daily", 120);

        assertThat(response.chartStatus()).isEqualTo("SAMPLE");
        assertThat(response.chartMessage()).contains("offline sample");
        assertThat(response.chart()).containsEntry("sourceTool", "offlineDemoSample");
        assertThat(response.latestReport().modelName()).isEqualTo("offline-rule-fallback");
        assertThat(response.taskTimeline()).hasSize(1);
        assertThat(response.taskTimeline().get(0).status()).isEqualTo("SUCCEEDED");
        assertThat(response.evidencePreview()).hasSize(1);
    }

    private ResearchTask researchTask() {
        ResearchTask task = new ResearchTask();
        task.setId(9L);
        task.setUserId("u_001");
        task.setTicker("NVDA");
        task.setConversationId(20L);
        task.setIdempotencyKey("rt:nvda:v1");
        task.setStatus(ResearchTask.Status.RUNNING);
        task.setStage(ResearchTask.Stage.AGENT_DEBATE);
        task.setAttempts(2);
        task.setResultReportVersionId(7L);
        task.setCreatedAt(LocalDateTime.parse("2026-06-04T11:00:00"));
        task.setStartedAt(LocalDateTime.parse("2026-06-04T11:05:00"));
        task.setHeartbeatAt(LocalDateTime.parse("2026-06-04T11:06:00"));
        task.setUpdatedAt(LocalDateTime.parse("2026-06-04T11:06:00"));
        return task;
    }

    private WorkbenchCockpitResponse sampleCockpit() {
        return new WorkbenchCockpitResponse(
                "NVDA",
                java.util.Map.of(
                        "chartType", "candlestick",
                        "sourceTool", "offlineDemoSample",
                        "symbol", "NVDA",
                        "points", List.of(java.util.Map.of(
                                "date", "2026-06-04",
                                "open", 119,
                                "high", 121,
                                "low", 118,
                                "close", 120,
                                "volume", 1000
                        ))
                ),
                "SAMPLE",
                "Loaded offline sample cockpit.",
                new WorkbenchCockpitResponse.ReportSummary(
                        null,
                        null,
                        "NVDA",
                        1,
                        "HOLD",
                        "d".repeat(64),
                        "c".repeat(64),
                        "DEMO",
                        "offline-rule-fallback",
                        LocalDateTime.parse("2026-06-05T10:00:00"),
                        LocalDateTime.parse("2026-06-05T10:00:00"),
                        "Offline sample report",
                        "Offline demo fallback report.",
                        List.of("[demo] Offline NVDA sample")
                ),
                List.of(new WorkbenchCockpitResponse.TaskSummary(
                        null,
                        null,
                        "NVDA",
                        "SUCCEEDED",
                        "COMPLETE",
                        1,
                        null,
                        LocalDateTime.parse("2026-06-05T10:00:00"),
                        LocalDateTime.parse("2026-06-05T10:00:00"),
                        LocalDateTime.parse("2026-06-05T10:00:00"),
                        LocalDateTime.parse("2026-06-05T10:00:00"),
                        LocalDateTime.parse("2026-06-05T10:00:00"),
                        null
                )),
                List.of(new WorkbenchCockpitResponse.EvidencePreview(
                        "Revenue",
                        "Sample revenue trend remains positive.",
                        "Supports a demo-only HOLD stance.",
                        "offline-sample"
                )),
                LocalDateTime.parse("2026-06-05T10:00:00")
        );
    }
}
