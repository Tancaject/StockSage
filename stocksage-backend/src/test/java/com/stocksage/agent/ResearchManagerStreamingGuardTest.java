package com.stocksage.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.harness.EvidenceLedger;
import com.stocksage.harness.HarnessModels.EvidenceDimension;
import com.stocksage.harness.HarnessModels.EvidenceEnvelope;
import com.stocksage.harness.HarnessModels.EvidenceStatus;
import com.stocksage.model.dto.AnalysisState;
import com.stocksage.model.dto.InvestmentReport;
import com.stocksage.harness.HarnessModels.ParseStatus;
import com.stocksage.harness.HarnessModels.TargetIdentity;
import com.stocksage.service.DeepResearchPipeline;
import com.stocksage.tool.ChatStreamEmitter;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResearchManagerStreamingGuardTest {

    @Test
    void ownershipGuardCancelsManagerStreamBeforeASecondTokenIsEmitted() {
        AtomicBoolean ownershipLost = new AtomicBoolean(false);
        AtomicBoolean upstreamCancelled = new AtomicBoolean(false);
        Flux<String> tokens = Flux.<String, Integer>generate(
                        () -> 0,
                        (index, sink) -> {
                            sink.next(index == 0 ? "first" : "second");
                            return index + 1;
                        })
                .doOnCancel(() -> upstreamCancelled.set(true));
        ChatClient chatClient = chatClientReturning(tokens);
        ChatStreamEmitter emitter = mock(ChatStreamEmitter.class);
        doAnswer(invocation -> {
            if ("first".equals(invocation.getArgument(5, String.class))) {
                ownershipLost.set(true);
            }
            return null;
        }).when(emitter).emitSection(any(), any(), any(), any(), any(), any());
        ResearchManager manager = new ResearchManager(chatClient, new ObjectMapper());
        Runnable guard = () -> {
            if (ownershipLost.get()) {
                throw new DeepResearchPipeline.OwnershipLostException("lost");
            }
        };

        assertThatThrownBy(() -> manager.synthesizeStreaming(
                AnalysisState.builder().query("q").build(), "trace", 1L, emitter, guard).block())
                .isInstanceOf(DeepResearchPipeline.OwnershipLostException.class);

        verify(emitter).emitSection(any(), any(), any(), any(), any(), eq("first"));
        verify(emitter, never()).emitSection(any(), any(), any(), any(), any(), eq("second"));
        assertThat(upstreamCancelled).isTrue();
    }

    @Test
    void guardRunsAgainBeforeParsingBufferedJson() throws Exception {
        ObjectMapper objectMapper = spy(new ObjectMapper());
        ResearchManager manager = new ResearchManager(
                chatClientReturning(Flux.just("{\"recommendation\":\"BUY\"}")), objectMapper);
        AtomicInteger checks = new AtomicInteger();
        Runnable guard = () -> {
            if (checks.incrementAndGet() > 1) {
                throw new DeepResearchPipeline.OwnershipLostException("lost before parse");
            }
        };

        assertThatThrownBy(() -> manager.synthesizeStreaming(
                AnalysisState.builder().query("q").build(), "trace", 1L,
                mock(ChatStreamEmitter.class), guard).block())
                .isInstanceOf(DeepResearchPipeline.OwnershipLostException.class);

        verify(objectMapper, never()).readTree(anyString());
    }

    @Test
    void originalStreamingOverloadStillParsesAReportWithoutAGuard() {
        ResearchManager manager = new ResearchManager(
                chatClientReturning(Flux.just(
                        "{\"recommendation\":\"BUY\",\"analystSummary\":\"ok\"}")),
                new ObjectMapper());

        InvestmentReport report = manager.synthesizeStreaming(
                AnalysisState.builder().query("q").build(), "trace", 1L,
                mock(ChatStreamEmitter.class)).block();

        assertThat(report).isNotNull();
        assertThat(report.getRecommendation()).isEqualTo("BUY");
    }

    @Test
    void invalidJsonIsReportedAsParseFailureInsteadOfFabricatingHold() {
        ResearchManager manager = new ResearchManager(
                chatClientReturning(Flux.just("not-json")),
                new ObjectMapper());

        var result = manager.synthesizeStreamingResult(
                AnalysisState.builder().query("q").build(),
                "trace",
                1L,
                mock(ChatStreamEmitter.class)
        ).block();

        assertThat(result).isNotNull();
        assertThat(result.parseStatus()).isEqualTo(ParseStatus.INVALID_JSON);
        assertThat(result.report()).isNull();
    }

    @Test
    void bindsEvidenceSourcesAndCitationsFromUsableLedgerIds() {
        Instant observedAt = Instant.parse("2026-07-28T02:00:00Z");
        EvidenceEnvelope sec = new EvidenceEnvelope(
                "e-sec",
                EvidenceDimension.FUNDAMENTALS,
                "getStructuredFinancials",
                "AAPL",
                EvidenceStatus.AVAILABLE,
                "https://data.sec.gov/api/xbrl/companyfacts/CIK0000320193.json",
                "SEC EDGAR XBRL",
                observedAt,
                Instant.parse("2025-02-01T00:00:00Z"),
                "a".repeat(64),
                true
        );
        EvidenceEnvelope news = new EvidenceEnvelope(
                "e-news",
                EvidenceDimension.NEWS,
                "searchNews",
                "AAPL",
                EvidenceStatus.AVAILABLE,
                "https://example.com/aapl-news",
                "tavily",
                observedAt,
                null,
                "b".repeat(64),
                true
        );
        EvidenceEnvelope failed = new EvidenceEnvelope(
                "e-failed",
                EvidenceDimension.NEWS,
                "webSearch",
                "AAPL",
                EvidenceStatus.FAILED,
                "https://example.com/failed",
                "ddg",
                observedAt,
                null,
                "c".repeat(64),
                true
        );
        AnalysisState state = AnalysisState.builder()
                .query("Should I buy AAPL?")
                .primaryTicker("AAPL")
                .evidenceLedger(new EvidenceLedger(
                        TargetIdentity.resolved("AAPL"),
                        List.of(sec, news, failed)
                ))
                .build();
        String modelOutput = """
                {
                  "recommendation":"BUY",
                  "rationale":["supported"],
                  "riskFactors":["risk"],
                  "analystSummary":"summary",
                  "evidenceItems":[
                    {
                      "dimension":"fundamentals",
                      "evidence":"revenue evidence",
                      "implication":"positive",
                      "source":"fabricated model label",
                      "sourceEvidenceIds":["unknown-id","e-sec"]
                    },
                    {
                      "dimension":"news",
                      "evidence":"news evidence",
                      "implication":"positive",
                      "source":"another fabricated label",
                      "sourceEvidenceIds":["e-news"]
                    },
                    {
                      "dimension":"news",
                      "evidence":"failed evidence",
                      "implication":"none",
                      "source":"must not survive",
                      "sourceEvidenceIds":["e-failed"]
                    }
                  ],
                  "dataFreshness":"bounded",
                  "citations":["generic model citation"]
                }
                """;
        ResearchManager manager = new ResearchManager(
                chatClientReturning(Flux.just(modelOutput)),
                new ObjectMapper()
        );

        InvestmentReport report = manager.synthesizeStreaming(
                state,
                "trace",
                1L,
                mock(ChatStreamEmitter.class)
        ).block();

        assertThat(report).isNotNull();
        assertThat(report.getEvidenceItems()).hasSize(2);
        assertThat(report.getEvidenceItems().get(0).getSourceEvidenceIds())
                .containsExactly("e-sec");
        assertThat(report.getEvidenceItems().get(0).getSource()).isEqualTo(
                "provider=SEC EDGAR XBRL; "
                        + "sourceRef=https://data.sec.gov/api/xbrl/companyfacts/"
                        + "CIK0000320193.json; asOf=2025-02-01T00:00:00Z");
        assertThat(report.getEvidenceItems().get(1).getSource()).isEqualTo(
                "provider=tavily; sourceRef=https://example.com/aapl-news; "
                        + "asOf=unknown");
        assertThat(report.getCitations()).containsExactly(
                "provider=SEC EDGAR XBRL; "
                        + "sourceRef=https://data.sec.gov/api/xbrl/companyfacts/"
                        + "CIK0000320193.json; asOf=2025-02-01T00:00:00Z",
                "provider=tavily; sourceRef=https://example.com/aapl-news; "
                        + "asOf=unknown"
        );
        assertThat(report.getCitations())
                .doesNotContain("generic model citation");

        String prompt = ReflectionTestUtils.invokeMethod(
                manager,
                "buildPrompt",
                state
        );
        assertThat(prompt)
                .contains("provider=SEC EDGAR XBRL")
                .contains("sourceRef=https://example.com/aapl-news")
                .contains("asOf=unknown")
                .doesNotContain("e-failed");
    }

    private ChatClient chatClientReturning(Flux<String> tokens) {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt().user(anyString()).stream().content()).thenReturn(tokens);
        return chatClient;
    }
}
