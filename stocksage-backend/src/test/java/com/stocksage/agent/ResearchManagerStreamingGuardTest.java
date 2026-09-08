package com.stocksage.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.harness.EvidenceLedger;
import com.stocksage.harness.HarnessModels.EvidenceDimension;
import com.stocksage.harness.HarnessModels.EvidenceEnvelope;
import com.stocksage.harness.HarnessModels.EvidenceStatus;
import com.stocksage.harness.HarnessModels.ParseStatus;
import com.stocksage.harness.HarnessModels.TargetIdentity;
import com.stocksage.model.dto.AnalysisHorizon;
import com.stocksage.model.dto.AnalysisState;
import com.stocksage.model.dto.DebateModels;
import com.stocksage.model.dto.DebateModels.ArgumentAssessment;
import com.stocksage.model.dto.DebateModels.AssessmentParseStatus;
import com.stocksage.model.dto.DebateModels.AssessmentReasonCode;
import com.stocksage.model.dto.DebateModels.DebatePoint;
import com.stocksage.model.dto.DebateModels.DebateTurn;
import com.stocksage.model.dto.DebateModels.DebateVerdict;
import com.stocksage.model.dto.DebateModels.EvidenceRef;
import com.stocksage.model.dto.DebateModels.LeadingSide;
import com.stocksage.model.dto.DebateModels.ManagerAssessment;
import com.stocksage.model.dto.DebateModels.PointType;
import com.stocksage.model.dto.DebateModels.Side;
import com.stocksage.model.dto.InvestmentReport;
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
    void validDebateContinuationDecisionIsAccepted() {
        ResearchManager manager = new ResearchManager(
                chatClientReturning(Flux.just(
                        "{\"decision\":\"CONTINUE\",\"reason\":\"仍有关键反驳待验证\"}")),
                chatClientReturning(Flux.empty()),
                chatClientReturning(Flux.empty()),
                new ObjectMapper()
        );

        ResearchManager.DebateContinuationDecision decision = manager
                .decideDebateContinuation(
                        AnalysisState.builder().query("q").build(),
                        1,
                        5,
                        () -> {
                        })
                .block();

        assertThat(decision).isNotNull();
        assertThat(decision.decision())
                .isEqualTo(ResearchManager.DebateContinuation.CONTINUE);
        assertThat(decision.reason()).isEqualTo("仍有关键反驳待验证");
    }

    @Test
    void invalidDebateContinuationDoesNotDefaultToStop() {
        List<String> invalidOutputs = List.of(
                "",
                "not-json",
                "{\"reason\":\"missing decision\"}",
                "{\"decision\":\"STOP\",\"reason\":\"\"}",
                "{\"decision\":\"WAIT\",\"reason\":\"unknown decision\"}",
                "{\"decision\":\"STOP\",\"reason\":\"done\",\"rounds\":1}"
        );

        for (String output : invalidOutputs) {
            ResearchManager manager = new ResearchManager(
                    chatClientReturning(Flux.just(output)),
                    chatClientReturning(Flux.empty()),
                    chatClientReturning(Flux.empty()),
                    new ObjectMapper()
            );

            assertThatThrownBy(() -> manager.decideDebateContinuation(
                    AnalysisState.builder().query("q").build(),
                    1,
                    5,
                    () -> {
                    }).block())
                    .isInstanceOf(RuntimeException.class);
        }
    }

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
        ResearchManager manager = new ResearchManager(
                chatClient, chatClient, chatClient, new ObjectMapper());
        AnalysisState state = AnalysisState.builder().query("q").build();
        DebateVerdict verdict = lockedVerdict(
                state, "HOLD", AnalysisHorizon.MEDIUM_TERM);
        Runnable guard = () -> {
            if (ownershipLost.get()) {
                throw new DeepResearchPipeline.OwnershipLostException("lost");
            }
        };

        assertThatThrownBy(() -> manager.synthesizeStreamingResult(
                state, verdict, "trace", 1L, emitter, guard).block())
                .isInstanceOf(DeepResearchPipeline.OwnershipLostException.class);

        verify(emitter).emitSection(any(), any(), any(), any(), any(), eq("first"));
        verify(emitter, never()).emitSection(any(), any(), any(), any(), any(), eq("second"));
        assertThat(upstreamCancelled).isTrue();
    }

    @Test
    void guardRunsAgainBeforeParsingBufferedJson() throws Exception {
        ObjectMapper objectMapper = spy(new ObjectMapper());
        ResearchManager manager = new ResearchManager(
                chatClientReturning(Flux.just("{\"analystSummary\":\"ok\"}")),
                chatClientReturning(Flux.just("{\"analystSummary\":\"ok\"}")),
                chatClientReturning(Flux.just("{\"analystSummary\":\"ok\"}")),
                objectMapper);
        AnalysisState state = AnalysisState.builder().query("q").build();
        DebateVerdict verdict = lockedVerdict(
                state, "HOLD", AnalysisHorizon.MEDIUM_TERM);
        AtomicInteger checks = new AtomicInteger();
        Runnable guard = () -> {
            if (checks.incrementAndGet() > 1) {
                throw new DeepResearchPipeline.OwnershipLostException("lost before parse");
            }
        };

        assertThatThrownBy(() -> manager.synthesizeStreamingResult(
                state, verdict, "trace", 1L,
                mock(ChatStreamEmitter.class), guard).block())
                .isInstanceOf(DeepResearchPipeline.OwnershipLostException.class);

        verify(objectMapper, never()).readTree(anyString());
    }

    @Test
    void streamingResultParsesAReportWithoutAGuard() {
        ChatClient chatClient = chatClientReturning(Flux.just(
                "{\"analystSummary\":\"ok\"}"));
        ResearchManager manager = new ResearchManager(
                chatClient, chatClient, chatClient, new ObjectMapper());
        AnalysisState state = AnalysisState.builder().query("q").build();
        DebateVerdict verdict = lockedVerdict(
                state, "BUY", AnalysisHorizon.LONG_TERM);

        var result = manager.synthesizeStreamingResult(
                state, verdict, "trace", 1L,
                mock(ChatStreamEmitter.class)).block();

        assertThat(result).isNotNull();
        InvestmentReport report = result.report();
        assertThat(report).isNotNull();
        assertThat(report.getRecommendation()).isEqualTo("BUY");
    }

    @Test
    void invalidJsonIsReportedAsParseFailureInsteadOfFabricatingHold() {
        ChatClient chatClient = chatClientReturning(Flux.just("not-json"));
        ResearchManager manager = new ResearchManager(
                chatClient, chatClient, chatClient, new ObjectMapper());
        AnalysisState state = AnalysisState.builder().query("q").build();
        DebateVerdict verdict = lockedVerdict(
                state, "HOLD", AnalysisHorizon.MEDIUM_TERM);

        var result = manager.synthesizeStreamingResult(
                state,
                verdict,
                "trace",
                1L,
                mock(ChatStreamEmitter.class)
        ).block();

        assertThat(result).isNotNull();
        assertThat(result.parseStatus()).isEqualTo(ParseStatus.INVALID_JSON);
        assertThat(result.report()).isNull();
    }

    @Test
    void validPerThesisScoringContractIsAccepted() {
        AnalysisState state = AnalysisState.builder().query("q").build();
        lockedVerdict(state, "HOLD", AnalysisHorizon.MEDIUM_TERM);
        ChatClient scoringClient = chatClientReturning(
                Flux.just(scoringJson(state, false)));
        ResearchManager manager = new ResearchManager(
                chatClientReturning(Flux.empty()),
                scoringClient,
                chatClientReturning(Flux.empty()),
                new ObjectMapper()
        );

        ManagerAssessment assessment = manager.scoreDebate(
                state, "fixture-input-0").block();

        assertThat(assessment).isNotNull();
        assertThat(assessment.parseStatus()).isEqualTo(AssessmentParseStatus.VALID);
        assertThat(assessment.issues()).isEmpty();
        assertThat(assessment.assessments()).hasSize(6);
    }

    @Test
    void scoringContractRejectsManagerWinnerField() {
        AnalysisState state = AnalysisState.builder().query("q").build();
        lockedVerdict(state, "HOLD", AnalysisHorizon.MEDIUM_TERM);
        ChatClient scoringClient = chatClientReturning(
                Flux.just(scoringJson(state, true)));
        ResearchManager manager = new ResearchManager(
                chatClientReturning(Flux.empty()),
                scoringClient,
                chatClientReturning(Flux.empty()),
                new ObjectMapper()
        );

        ManagerAssessment assessment = manager.scoreDebate(
                state, "fixture-input-0").block();

        assertThat(assessment).isNotNull();
        assertThat(assessment.parseStatus())
                .isEqualTo(AssessmentParseStatus.INVALID_SCHEMA);
        assertThat(assessment.issues()).contains("root.unknownField:winner");
    }

    @Test
    void analysisHorizonComesFromLockedVerdictInsteadOfManagerJson() {
        Instant observedAt = Instant.parse("2026-07-28T02:00:00Z");
        EvidenceEnvelope market = new EvidenceEnvelope(
                "e-market",
                EvidenceDimension.MARKET,
                "getMarketData",
                "AAPL",
                EvidenceStatus.AVAILABLE,
                "https://example.com/aapl-market",
                "market-provider",
                observedAt,
                observedAt,
                "d".repeat(64),
                true
        );
        AnalysisState state = AnalysisState.builder()
                .query("Should I buy AAPL?")
                .primaryTicker("AAPL")
                .evidenceLedger(new EvidenceLedger(
                        TargetIdentity.resolved("AAPL"),
                        List.of(market)
                ))
                .build();
        String modelOutput = """
                {
                  "rationale":["bounded"],
                  "riskFactors":["risk"],
                  "analystSummary":"summary",
                  "evidenceItems":[{
                    "dimension":"market",
                    "evidence":"market evidence",
                    "implication":"neutral",
                    "source":"",
                    "sourceEvidenceIds":["e-market"]
                  }],
                  "bullFactors":[],
                  "bearFactors":[],
                  "suitableFor":[],
                  "notSuitableFor":[],
                  "unknowns":["bounded unknown"],
                  "dataFreshness":"bounded"
                }
                """;
        ChatClient chatClient = chatClientReturning(Flux.just(modelOutput));
        ResearchManager manager = new ResearchManager(
                chatClient, chatClient, chatClient, new ObjectMapper());
        DebateVerdict verdict = lockedVerdict(
                state, "HOLD", AnalysisHorizon.MEDIUM_TERM);

        var result = manager.synthesizeStreamingResult(
                state,
                verdict,
                "trace",
                1L,
                mock(ChatStreamEmitter.class)
        ).block();

        assertThat(result).isNotNull();
        assertThat(result.parseStatus()).isEqualTo(ParseStatus.VALID);
        assertThat(result.validationIssues()).doesNotContain("analysisHorizon");
        assertThat(result.report().getAnalysisHorizon()).isEqualTo(AnalysisHorizon.MEDIUM_TERM);
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
                  "bullFactors":[],
                  "bearFactors":[],
                  "suitableFor":[],
                  "notSuitableFor":[],
                  "unknowns":["bounded unknown"],
                  "dataFreshness":"bounded"
                }
                """;
        ChatClient chatClient = chatClientReturning(Flux.just(modelOutput));
        ResearchManager manager = new ResearchManager(
                chatClient, chatClient, chatClient, new ObjectMapper());
        DebateVerdict verdict = lockedVerdict(
                state, "HOLD", AnalysisHorizon.MEDIUM_TERM);

        var result = manager.synthesizeStreamingResult(
                state,
                verdict,
                "trace",
                1L,
                mock(ChatStreamEmitter.class)
        ).block();

        assertThat(result).isNotNull();
        assertThat(result.parseStatus()).isEqualTo(ParseStatus.VALID);
        InvestmentReport report = result.report();
        assertThat(report.getRecommendation()).isEqualTo("HOLD");
        assertThat(report.getAnalysisHorizon()).isEqualTo(AnalysisHorizon.MEDIUM_TERM);
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
                state,
                verdict
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

    private String scoringJson(AnalysisState state, boolean includeWinner) {
        String assessments = state.getDebateTurns().stream()
                .filter(turn -> turn.round() == 1)
                .flatMap(turn -> turn.points().stream())
                .map(point -> """
                        {
                          "pointId":"%s",
                          "evidenceSupport":3,
                          "questionRelevance":3,
                          "logicalCoherence":3,
                          "rebuttalSurvival":3,
                          "uncertaintyHandling":3,
                          "acceptedEvidenceIds":["%s"],
                          "decisiveRebuttalIds":[],
                          "reasonCodes":["SUPPORTED"],
                          "explanation":"fixture assessment"
                        }
                        """.formatted(
                                point.pointId(),
                                point.evidenceRefs().get(0).evidenceId()))
                .collect(java.util.stream.Collectors.joining(","));
        return includeWinner
                ? "{\"winner\":\"A\",\"assessments\":[" + assessments + "]}"
                : "{\"assessments\":[" + assessments + "]}";
    }

    private DebateVerdict lockedVerdict(
            AnalysisState state,
            String recommendation,
            AnalysisHorizon horizon
    ) {
        if (state.getPrimaryTicker() == null) {
            state.setPrimaryTicker("AAPL");
        }
        if (state.getDataSnapshotHash() == null) {
            state.setDataSnapshotHash("fixture-snapshot");
        }
        if (state.getContextHash() == null) {
            state.setContextHash("fixture-context");
        }
        if (state.getDebateTurns().isEmpty()) {
            state.getDebateTurns().add(thesisTurn(Side.BULL, "bull", horizon));
            state.getDebateTurns().add(thesisTurn(Side.BEAR, "bear", horizon));
        }

        List<ArgumentAssessment> assessments = state.getDebateTurns().stream()
                .filter(turn -> turn.round() == 1)
                .flatMap(turn -> turn.points().stream())
                .map(point -> new ArgumentAssessment(
                        point.pointId(),
                        3,
                        3,
                        3,
                        3,
                        3,
                        List.of(point.evidenceRefs().get(0).evidenceId()),
                        List.of(),
                        List.of(AssessmentReasonCode.SUPPORTED),
                        "fixture assessment"
                ))
                .toList();
        String inputHash = "fixture-input-0";
        ManagerAssessment managerAssessment = new ManagerAssessment(
                DebateModels.MANAGER_ASSESSMENT_CONTRACT_ID,
                DebateModels.MANAGER_ASSESSMENT_CONTRACT_VERSION,
                inputHash,
                true,
                assessments,
                AssessmentParseStatus.VALID,
                List.of()
        );
        state.setManagerAssessment(managerAssessment);

        LeadingSide leadingSide = switch (recommendation) {
            case "BUY", "OVERWEIGHT" -> LeadingSide.BULL;
            case "SELL", "UNDERWEIGHT" -> LeadingSide.BEAR;
            default -> LeadingSide.BALANCED;
        };
        DebateVerdict verdict = new DebateVerdict(
                DebateDecisionPolicy.POLICY_ID,
                DebateDecisionPolicy.POLICY_VERSION,
                inputHash,
                state.getDataSnapshotHash(),
                leadingSide == LeadingSide.BULL ? 90.0 : 70.0,
                leadingSide == LeadingSide.BEAR ? 90.0 : 70.0,
                leadingSide,
                leadingSide == LeadingSide.BALANCED ? 0.0 : 20.0,
                recommendation,
                horizon,
                leadingSide == LeadingSide.BALANCED
                        ? List.of()
                        : state.getDebateTurns().stream()
                        .filter(turn -> turn.round() == 1
                                && (leadingSide == LeadingSide.BULL
                                ? turn.side() == Side.BULL
                                : turn.side() == Side.BEAR))
                        .flatMap(turn -> turn.points().stream())
                        .map(DebatePoint::pointId)
                        .toList(),
                List.of(),
                assessments
        );
        state.setDebateVerdict(verdict);
        return verdict;
    }

    private DebateTurn thesisTurn(Side side, String prefix, AnalysisHorizon horizon) {
        List<DebatePoint> points = java.util.stream.IntStream.rangeClosed(1, 3)
                .mapToObj(index -> new DebatePoint(
                        prefix + "-thesis-" + index,
                        PointType.THESIS,
                        prefix + " claim " + index,
                        horizon,
                        List.of(new EvidenceRef(
                                prefix + "-evidence-" + index,
                                prefix + " evidence excerpt " + index)),
                        prefix + " reasoning " + index,
                        prefix + " assumption " + index,
                        prefix + " invalidation " + index,
                        List.of()
                ))
                .toList();
        return new DebateTurn(1, side, points);
    }
}
