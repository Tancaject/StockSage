package com.stocksage.service;

import com.stocksage.capability.CapabilityResult;
import com.stocksage.capability.LocalNewsSearchCapabilityAdapter;
import com.stocksage.harness.DeepResearchCompletionPolicy;
import com.stocksage.harness.EvidenceLedger;
import com.stocksage.harness.HarnessModels.EvidenceDimension;
import com.stocksage.harness.HarnessModels.EvidenceEnvelope;
import com.stocksage.harness.HarnessModels.EvidenceStatus;
import com.stocksage.harness.HarnessModels.HarnessDecision;
import com.stocksage.harness.HarnessModels.HarnessOutcome;
import com.stocksage.harness.HarnessModels.RunContext;
import com.stocksage.harness.HarnessModels.TargetIdentity;
import com.stocksage.harness.ResearchHarness;
import com.stocksage.model.dto.AnalysisState;
import com.stocksage.tool.ChatStreamEmitter;
import com.stocksage.tool.FundamentalsTools;
import com.stocksage.tool.MarketTools;
import com.stocksage.tool.NewsTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DeepEvidenceCollectorTest {

    private final MarketTools marketTools = mock(MarketTools.class);
    private final NewsTools newsTools = mock(NewsTools.class);
    private final FundamentalsTools fundamentalsTools = mock(FundamentalsTools.class);
    private final TickerResolutionService tickerResolutionService = mock(TickerResolutionService.class);
    private final ChatStreamEmitter chatStreamEmitter = mock(ChatStreamEmitter.class);
    private final ResearchHarness researchHarness = mock(ResearchHarness.class);
    private final DeepResearchCompletionPolicy completionPolicy = new DeepResearchCompletionPolicy();

    private DeepEvidenceCollector collector;

    @BeforeEach
    void setUp() {
        collector = new DeepEvidenceCollector(
                marketTools,
                newsTools,
                fundamentalsTools,
                tickerResolutionService,
                chatStreamEmitter,
                new TaskExecutorAdapter(Runnable::run),
                researchHarness,
                completionPolicy
        );
        ReflectionTestUtils.setField(collector, "toolPrefetchMaxSearchResults", 5);
        ReflectionTestUtils.setField(collector, "toolPrefetchPerToolTimeoutSeconds", 1L);
        ReflectionTestUtils.setField(collector, "autoEdgarIngestEnabled", false);
        when(researchHarness.observeEvidence(any(), eq(completionPolicy), any(), any()))
                .thenAnswer(invocation -> completionPolicy.afterEvidence(
                        invocation.getArgument(2), invocation.getArgument(3)));
    }

    @Test
    void mapsEveryToolCallToStructuredEvidenceAndUsesPolicyDecision() {
        when(tickerResolutionService.isLikelySecTicker("AAPL")).thenReturn(true);
        when(tickerResolutionService.resolveSectorForTicker("AAPL")).thenReturn("");
        when(fundamentalsTools.getFinancialReports("AAPL", "annual", 5))
                .thenReturn("""
                        {"cik":"320193","metrics":{"revenue":{"data":[{"value":100}]}}}
                        """);
        when(fundamentalsTools.searchCompanyReports("AAPL", "财报", 5))
                .thenReturn("");
        when(fundamentalsTools.getStructuredFinancials("AAPL"))
                .thenReturn("""
                        {
                          "cik":"320193",
                          "metrics":{"revenue":{"data":[
                            {"filed":"2024-02-02","value":90},
                            {"filed":"2025-02-01","value":100}
                          ]}}
                        }
                        """);
        when(marketTools.getIbkrHistoricalBars("AAPL", "3m", "1d"))
                .thenReturn("""
                        {
                          "provider":"IBKR_WEB_API",
                          "conid":"265598",
                          "period":"3m",
                          "bar":"1d",
                          "data":{"data":[{"t":1753488000000},{"t":1753574400000}]}
                        }
                        """);
        when(marketTools.getIbkrHistoricalBars("AAPL", "6m", "1d"))
                .thenReturn("""
                        {
                          "provider":"IBKR_WEB_API",
                          "conid":"265598",
                          "period":"6m",
                          "bar":"1d",
                          "data":{"data":[{"t":1753574400000}]}
                        }
                        """);
        when(newsTools.getStockNews("AAPL", 7)).thenReturn("");
        when(newsTools.searchNews("AAPL Should I buy AAPL?", 5, true))
                .thenReturn("""
                        {
                          "provider":"tavily",
                          "results":[
                            {
                              "link":"https://example.com/aapl-earnings",
                              "date":"2026-07-26T12:30:00Z"
                            }
                          ]
                        }
                        """);
        when(newsTools.webSearch("AAPL Should I buy AAPL?", 5, true))
                .thenReturn("{\"provider\":\"ddg\",\"results\":[]}");

        DeepEvidenceCollector.EvidenceCollection result =
                collector.collect("AAPL", "Should I buy AAPL?", "trace-1", 20L);

        EvidenceLedger ledger = result.evidenceLedger();
        assertThat(result.sufficientForRecommendation()).isTrue();
        assertThat(result.newsOk()).isTrue();
        assertThat(result.state().getEvidenceLedger()).isSameAs(ledger);
        assertThat(ledger.evidence()).hasSize(9);
        assertThat(ledger.evidence())
                .filteredOn(item -> item.dimension() == EvidenceDimension.FUNDAMENTALS)
                .hasSize(3);
        assertThat(ledger.evidence())
                .filteredOn(item -> item.dimension() == EvidenceDimension.MARKET)
                .hasSize(3);
        assertThat(ledger.evidence())
                .filteredOn(item -> item.dimension() == EvidenceDimension.NEWS)
                .extracting(item -> item.status())
                .containsExactly(
                        EvidenceStatus.EMPTY,
                        EvidenceStatus.AVAILABLE,
                        EvidenceStatus.NO_RESULTS
                );
        assertThat(ledger.evidence())
                .allMatch(item -> !item.evidenceId().isBlank())
                .allMatch(item -> !item.payloadHash().isBlank())
                .allMatch(item -> item.observedAt() != null)
                .allMatch(item -> item.targetKey().equals("AAPL"));
        assertThat(ledger.evidence())
                .filteredOn(item -> item.status() == EvidenceStatus.AVAILABLE)
                .allMatch(item -> !item.provider().isBlank())
                .allMatch(item -> !item.sourceRef().isBlank());

        var financialReports = ledger.evidence().stream()
                .filter(item -> item.capabilityId().equals("getFinancialReports"))
                .findFirst()
                .orElseThrow();
        assertThat(financialReports.provider()).isEqualTo("SEC EDGAR XBRL");
        assertThat(financialReports.sourceRef()).isEqualTo(
                "https://data.sec.gov/api/xbrl/companyfacts/CIK0000320193.json");
        assertThat(financialReports.asOf()).isNull();

        var structuredFinancials = ledger.evidence().stream()
                .filter(item -> item.dimension() == EvidenceDimension.FUNDAMENTALS)
                .filter(item -> item.capabilityId().equals("getStructuredFinancials"))
                .findFirst()
                .orElseThrow();
        assertThat(structuredFinancials.provider()).isEqualTo("SEC EDGAR XBRL");
        assertThat(structuredFinancials.sourceRef()).isEqualTo(
                "https://data.sec.gov/api/xbrl/companyfacts/CIK0000320193.json");
        assertThat(structuredFinancials.asOf()).isEqualTo(
                Instant.parse("2025-02-01T00:00:00Z"));

        var ibkrBars = ledger.evidence().stream()
                .filter(item -> item.capabilityId().startsWith("getIbkrHistoricalBars"))
                .findFirst()
                .orElseThrow();
        assertThat(ibkrBars.provider()).isEqualTo("IBKR_WEB_API");
        assertThat(ibkrBars.sourceRef())
                .isEqualTo("ibkr://client-portal/iserver/marketdata/history"
                        + "?conid=265598&period=3m&bar=1d");
        assertThat(ibkrBars.asOf()).isEqualTo(
                Instant.ofEpochMilli(1753574400000L));

        var search = ledger.evidence().stream()
                .filter(item -> item.capabilityId().equals("searchNews"))
                .findFirst()
                .orElseThrow();
        assertThat(search.provider()).isEqualTo("tavily");
        assertThat(search.sourceRef()).isEqualTo(
                "https://example.com/aapl-earnings");
        assertThat(search.asOf()).isEqualTo(
                Instant.parse("2026-07-26T12:30:00Z"));

        ArgumentCaptor<EvidenceLedger> ledgerCaptor = ArgumentCaptor.forClass(EvidenceLedger.class);
        verify(researchHarness).observeEvidence(
                eq("trace-1"),
                eq(completionPolicy),
                eq(RunContext.deepResearch()),
                ledgerCaptor.capture()
        );
        assertThat(ledgerCaptor.getValue()).isSameAs(ledger);
    }

    @Test
    void focusedNewsQueryUsesResolvedQuestionWithoutTickerSpecialCases() {
        String focus = "HBM 出口限制对毛利率的影响\n" + "以及竞争格局变化 ".repeat(30);
        when(newsTools.getStockNews("MU", 7)).thenReturn("");
        when(newsTools.searchNews(any(), eq(5), eq(true))).thenReturn("");
        when(newsTools.webSearch(any(), eq(5), eq(true))).thenReturn("");

        ReflectionTestUtils.invokeMethod(collector, "buildNewsSnapshot", "MU", focus);

        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(newsTools).searchNews(queryCaptor.capture(), eq(5), eq(true));
        String query = queryCaptor.getValue();
        assertThat(query)
                .startsWith("MU HBM 出口限制对毛利率的影响")
                .hasSize(180)
                .doesNotContain("\n", "\r", "Micron MU earnings");
        verify(newsTools).webSearch(query, 5, true);
    }

    @Test
    void unresolvedTargetStillProducesAndObservesAnEmptyLedger() {
        DeepEvidenceCollector.EvidenceCollection result =
                collector.collect("", "Compare the business", "trace-2", 21L);

        assertThat(result.sufficientForRecommendation()).isFalse();
        assertThat(result.tickerResolved()).isFalse();
        assertThat(result.evidenceLedger().target().isResolved()).isFalse();
        assertThat(result.evidenceLedger().evidence()).isEmpty();
        verify(researchHarness).observeEvidence(
                eq("trace-2"),
                eq(completionPolicy),
                eq(RunContext.deepResearch()),
                eq(result.evidenceLedger())
        );
        verify(marketTools, org.mockito.Mockito.never())
                .getIbkrHistoricalBars(any(), any(), any());
    }

    @Test
    void prefersAhPayloadProviderAndKeepsUnknownBusinessTimeNull() {
        when(tickerResolutionService.isLikelySecTicker("0700.HK")).thenReturn(false);
        when(tickerResolutionService.resolveSectorForTicker("0700.HK")).thenReturn("");
        when(fundamentalsTools.getFinancialReports("0700.HK", "annual", 5))
                .thenReturn("""
                        {
                          "provider":"akshare",
                          "source":"baostock",
                          "symbol":"00700",
                          "statements":[{"reportDate":"2025-12-31"}]
                        }
                        """);
        when(fundamentalsTools.searchCompanyReports("0700.HK", "财报", 5))
                .thenReturn("");
        when(marketTools.getStockKLine("0700.HK", "daily", 60))
                .thenReturn("""
                        {
                          "provider":"akshare",
                          "source":"baostock",
                          "resolvedCode":"00700",
                          "data":[{"Date":"2026-07-24"},{"Date":"2026-07-25"}]
                        }
                        """);
        when(marketTools.getFinancialMetrics("0700.HK"))
                .thenReturn("""
                        {"provider":"akshare","symbol":"00700","metrics":{"pe":20}}
                        """);
        when(marketTools.getTechnicalIndicators("0700.HK", "MA,MACD,RSI"))
                .thenReturn("""
                        {"provider":"akshare","symbol":"00700","indicators":{"RSI14":55}}
                        """);
        when(newsTools.getStockNews("0700.HK", 7))
                .thenReturn("{\"provider\":\"tavily\",\"results\":[]}");
        when(newsTools.searchNews(any(), any(Integer.class), eq(true)))
                .thenReturn("{\"provider\":\"tavily\",\"results\":[]}");
        when(newsTools.webSearch(any(), any(Integer.class), eq(true)))
                .thenReturn("{\"provider\":\"ddg\",\"results\":[]}");

        EvidenceLedger ledger = collector.collect(
                "0700.HK",
                "Analyze Tencent",
                "trace-hk",
                30L
        ).evidenceLedger();

        var kline = ledger.evidence().stream()
                .filter(item -> item.capabilityId().equals("getStockKLine(daily,60)"))
                .findFirst()
                .orElseThrow();
        assertThat(kline.provider()).isEqualTo("akshare");
        assertThat(kline.sourceRef()).isEqualTo(
                "provider://akshare/getStockKLine-daily-60?target=00700");
        assertThat(kline.asOf()).isEqualTo(
                Instant.parse("2026-07-25T00:00:00Z"));

        var metrics = ledger.evidence().stream()
                .filter(item -> item.capabilityId().equals("getFinancialMetrics"))
                .findFirst()
                .orElseThrow();
        assertThat(metrics.provider()).isEqualTo("akshare");
        assertThat(metrics.sourceRef()).isEqualTo(
                "provider://akshare/getFinancialMetrics?target=00700");
        assertThat(metrics.asOf()).isNull();
    }

    @Test
    void recoveryRecollectsOnlyTheMissingRequiredDimension() {
        when(tickerResolutionService.isLikelySecTicker("AAPL")).thenReturn(true);
        when(tickerResolutionService.resolveSectorForTicker("AAPL")).thenReturn("");
        when(fundamentalsTools.getFinancialReports("AAPL", "annual", 5))
                .thenReturn(
                        "",
                        "{\"cik\":\"320193\",\"metrics\":{\"revenue\":{\"data\":[{\"filed\":\"2025-02-01\"}]}}}"
                );
        when(fundamentalsTools.searchCompanyReports("AAPL", "财报", 5)).thenReturn("");
        when(fundamentalsTools.getStructuredFinancials("AAPL")).thenReturn("");
        when(marketTools.getIbkrHistoricalBars("AAPL", "3m", "1d"))
                .thenReturn("""
                        {"provider":"IBKR_WEB_API","conid":"265598","period":"3m",
                         "bar":"1d","data":{"data":[{"t":1753574400000}]}}
                        """);
        when(marketTools.getIbkrHistoricalBars("AAPL", "6m", "1d"))
                .thenReturn("""
                        {"provider":"IBKR_WEB_API","conid":"265598","period":"6m",
                         "bar":"1d","data":{"data":[{"t":1753574400000}]}}
                        """);
        when(newsTools.getStockNews("AAPL", 7)).thenReturn("");
        when(newsTools.searchNews(any(), any(Integer.class), eq(true))).thenReturn("");
        when(newsTools.webSearch(any(), any(Integer.class), eq(true))).thenReturn("");

        DeepEvidenceCollector.EvidenceCollection first =
                collector.collect("AAPL", "Should I buy AAPL?", "trace-3", 22L);
        DeepEvidenceCollector.EvidenceCollection recovered = collector.recover(
                first,
                first.harnessDecision().recoveryActions(),
                "trace-3",
                22L
        );

        assertThat(first.harnessDecision().outcome()).isEqualTo(
                com.stocksage.harness.HarnessModels.HarnessOutcome.RECOVER);
        assertThat(recovered.harnessDecision().outcome()).isEqualTo(
                com.stocksage.harness.HarnessModels.HarnessOutcome.PASS);
        verify(fundamentalsTools, times(2))
                .getFinancialReports("AAPL", "annual", 5);
        verify(marketTools, times(1))
                .getIbkrHistoricalBars("AAPL", "3m", "1d");
    }

    @Test
    void focusedNewsAppendRejectsErrorsAndDeduplicatesUsableEvidence() {
        HarnessDecision pass = new HarnessDecision(HarnessOutcome.PASS, List.of(), List.of());
        when(researchHarness.observeEvidence(any(), eq(completionPolicy), any(), any()))
                .thenReturn(pass);
        EvidenceEnvelope baseline = new EvidenceEnvelope(
                "baseline-news",
                EvidenceDimension.NEWS,
                "searchNews",
                "AAPL",
                EvidenceStatus.AVAILABLE,
                "https://example.com/baseline",
                "test",
                Instant.parse("2026-08-27T00:00:00Z"),
                Instant.parse("2026-08-26T00:00:00Z"),
                "baseline-hash",
                true
        );
        EvidenceLedger ledger = new EvidenceLedger(TargetIdentity.resolved("AAPL"), List.of(baseline));
        AnalysisState state = AnalysisState.builder()
                .query("Should I buy AAPL?")
                .primaryTicker("AAPL")
                .newsReport("baseline news")
                .evidenceLedger(ledger)
                .build();
        DeepEvidenceCollector.EvidenceCollection existing = new DeepEvidenceCollector.EvidenceCollection(
                "context", state, true, true, true, true, true, ledger, pass);
        CapabilityResult error = new CapabilityResult(
                LocalNewsSearchCapabilityAdapter.ID,
                "local",
                CapabilityResult.Status.SUCCESS,
                "{\"error\":\"provider unavailable\"}",
                32,
                1
        );
        assertThat(collector.appendFocusedNews(existing, error, "trace-focused"))
                .isSameAs(existing);

        when(tickerResolutionService.normalizeStructuredTicker("MSFT")).thenReturn("MSFT");
        CapabilityResult wrongTarget = new CapabilityResult(
                LocalNewsSearchCapabilityAdapter.ID,
                "local",
                CapabilityResult.Status.SUCCESS,
                "{\"provider\":\"tavily\",\"ticker\":\"MSFT\",\"results\":[{\"link\":\"https://example.com/msft\"}]}",
                80,
                1
        );
        assertThat(collector.appendFocusedNews(existing, wrongTarget, "trace-focused"))
                .isSameAs(existing);

        CapabilityResult usable = new CapabilityResult(
                LocalNewsSearchCapabilityAdapter.ID,
                "local",
                CapabilityResult.Status.SUCCESS,
                """
                        {"provider":"tavily","results":[{
                          "link":"https://example.com/aapl-regulation",
                          "date":"2026-08-27T01:00:00Z"
                        }]}
                        """,
                140,
                2
        );
        DeepEvidenceCollector.EvidenceCollection appended =
                collector.appendFocusedNews(existing, usable, "trace-focused");
        DeepEvidenceCollector.EvidenceCollection duplicate =
                collector.appendFocusedNews(appended, usable, "trace-focused");

        assertThat(appended.evidenceLedger().evidence()).hasSize(2);
        assertThat(appended.state().getNewsReport()).contains("focusedNewsSearch");
        assertThat(duplicate).isSameAs(appended);
    }

    @Test
    void ambiguousQueryBlocksBeforeCallingEvidenceTools() {
        when(tickerResolutionService.hasConflictingExplicitTicker("比较 AAPL 和 MSFT", "AAPL"))
                .thenReturn(true);

        DeepEvidenceCollector.EvidenceCollection result = collector.collect(
                "AAPL", "比较 AAPL 和 MSFT", "trace-ambiguous", 40L);

        assertThat(result.harnessDecision().outcome()).isEqualTo(HarnessOutcome.BLOCK);
        assertThat(result.harnessDecision().violations())
                .extracting(violation -> violation.code().name())
                .containsExactly("TARGET_AMBIGUOUS");
        verifyNoInteractions(fundamentalsTools, marketTools, newsTools);
    }

    @Test
    void oldCheckpointWithSecondTargetIsRevalidatedAsAmbiguous() {
        when(tickerResolutionService.hasConflictingExplicitTicker("AAPL vs MSFT", "AAPL"))
                .thenReturn(true);
        EvidenceLedger ledger = new EvidenceLedger(TargetIdentity.resolved("AAPL"), List.of());
        AnalysisState state = AnalysisState.builder()
                .query("AAPL vs MSFT")
                .primaryTicker("AAPL")
                .evidenceLedger(ledger)
                .build();

        DeepEvidenceCollector.EvidenceCollection result = collector.reevaluateCheckpoint(
                state, java.util.Map.of(), "trace-old-ambiguous");

        assertThat(result.harnessDecision().outcome()).isEqualTo(HarnessOutcome.BLOCK);
        assertThat(result.harnessDecision().violations())
                .extracting(violation -> violation.code().name())
                .containsExactly("TARGET_AMBIGUOUS");
        verifyNoInteractions(fundamentalsTools, marketTools, newsTools);
    }

    @Test
    void focusedNewsAcceptsEquivalentHkTickerFormats() {
        when(tickerResolutionService.normalizeStructuredTicker("HK:0700")).thenReturn("0700.HK");
        when(tickerResolutionService.normalizeStructuredTicker("00700.HK")).thenReturn("0700.HK");
        HarnessDecision pass = new HarnessDecision(HarnessOutcome.PASS, List.of(), List.of());
        when(researchHarness.observeEvidence(any(), eq(completionPolicy), any(), any())).thenReturn(pass);
        EvidenceLedger ledger = new EvidenceLedger(TargetIdentity.resolved("0700.HK"), List.of());
        AnalysisState state = AnalysisState.builder()
                .query("腾讯监管")
                .primaryTicker("HK:0700")
                .evidenceLedger(ledger)
                .build();
        CapabilityResult result = new CapabilityResult(
                LocalNewsSearchCapabilityAdapter.ID,
                "local",
                CapabilityResult.Status.SUCCESS,
                "{\"provider\":\"tavily\",\"ticker\":\"00700.HK\",\"results\":[{\"link\":\"https://example.com/tencent\"}]}",
                80,
                1
        );

        DeepEvidenceCollector.EvidenceCollection appended = collector.appendFocusedNews(
                new DeepEvidenceCollector.EvidenceCollection(
                        "", state, true, true, true, true, true, ledger, pass),
                result,
                "trace-hk-focused");

        assertThat(appended.evidenceLedger().evidence()).hasSize(1);
        assertThat(appended.evidenceLedger().evidence().get(0).targetKey()).isEqualTo("0700.HK");
    }
}
