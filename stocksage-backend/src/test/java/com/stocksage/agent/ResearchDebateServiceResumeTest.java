package com.stocksage.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.harness.DeepResearchCompletionPolicy;
import com.stocksage.harness.EvidenceLedger;
import com.stocksage.harness.HarnessModels.EvidenceDimension;
import com.stocksage.harness.HarnessModels.EvidenceEnvelope;
import com.stocksage.harness.HarnessModels.EvidenceStatus;
import com.stocksage.harness.HarnessModels.HarnessDecision;
import com.stocksage.harness.HarnessModels.HarnessPhase;
import com.stocksage.harness.HarnessModels.HarnessOutcome;
import com.stocksage.harness.HarnessModels.HarnessSnapshot;
import com.stocksage.harness.HarnessModels.RecoveryAction;
import com.stocksage.harness.HarnessModels.RecoveryLifecycle;
import com.stocksage.harness.HarnessModels.RunContext;
import com.stocksage.harness.HarnessModels.SynthesisResult;
import com.stocksage.harness.HarnessModels.TargetIdentity;
import com.stocksage.harness.HarnessObserver;
import com.stocksage.harness.ResearchHarness;
import com.stocksage.model.dto.AnalysisState;
import com.stocksage.model.dto.InvestmentReport;
import com.stocksage.service.DeepResearchPipeline;
import com.stocksage.tool.ChatStreamEmitter;
import com.stocksage.trace.TraceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResearchDebateServiceResumeTest {

    private final BullResearcher bullResearcher = mock(BullResearcher.class);
    private final BearResearcher bearResearcher = mock(BearResearcher.class);
    private final ResearchManager researchManager = mock(ResearchManager.class);
    private final DebateRoundPlanner debateRoundPlanner = mock(DebateRoundPlanner.class);
    private final ChatStreamEmitter chatStreamEmitter = mock(ChatStreamEmitter.class);
    private final ResearchHarness researchHarness = mock(ResearchHarness.class);
    private final DeepResearchCompletionPolicy completionPolicy = new DeepResearchCompletionPolicy();
    private final ResearchDebateService service = new ResearchDebateService(
            bullResearcher,
            bearResearcher,
            researchManager,
            debateRoundPlanner,
            mock(TraceService.class),
            chatStreamEmitter,
            researchHarness,
            completionPolicy
    );

    @BeforeEach
    void setUp() {
        when(bullResearcher.argue(any(), anyInt()))
                .thenAnswer(invocation -> Flux.just("bull-r" + invocation.getArgument(1, Integer.class)));
        when(bearResearcher.argue(any(), anyInt()))
                .thenAnswer(invocation -> Flux.just("bear-r" + invocation.getArgument(1, Integer.class)));
        InvestmentReport report = InvestmentReport.builder().analystSummary("done").build();
        when(researchManager.synthesizeStreamingResult(any(), any(), any(), any()))
                .thenReturn(Mono.just(new SynthesisResult(
                        report,
                        com.stocksage.harness.HarnessModels.ParseStatus.VALID,
                        List.of()
                )));
        when(researchHarness.evaluateReport(any(), any(), any(), any(), any()))
                .thenReturn(new HarnessDecision(HarnessOutcome.PASS, List.of(), List.of()));
    }

    @Test
    void resumeFromRound3SkipsPlannerAndEarlierRounds() {
        AnalysisState state = stateWithCompletedRounds(2);
        List<Integer> checkpoints = new ArrayList<>();

        AnalysisState done = service.runDebate(null, null, state, 3, 3,
                (current, rounds, planned) -> checkpoints.add(rounds));

        verify(debateRoundPlanner, never()).decide(any(), anyInt());
        verify(bullResearcher, never()).argue(any(), eq(1));
        verify(bullResearcher, never()).argue(any(), eq(2));
        verify(bullResearcher).argue(any(), eq(3));
        assertThat(checkpoints).containsExactly(3);
        assertThat(done.getDebateTurns()).hasSize(6);
    }

    @Test
    void freshRunStillPlansConcurrentlyWithRound1() {
        when(debateRoundPlanner.decide(any(), anyInt()))
                .thenReturn(new DebateRoundPlanner.RoundDecision(2, "two rounds"));
        List<Integer> checkpoints = new ArrayList<>();

        AnalysisState done = service.runDebate(null, null, AnalysisState.builder().query("q").build(),
                1, 0, (current, rounds, planned) -> checkpoints.add(rounds));

        verify(debateRoundPlanner).decide(any(), anyInt());
        verify(bullResearcher).argue(any(), eq(1));
        verify(bullResearcher).argue(any(), eq(2));
        assertThat(checkpoints).containsExactly(1, 2);
        assertThat(done.getDebateTurns()).hasSize(4);
    }

    @Test
    void ownershipGuardCancelsArgumentStreamBeforeASecondTokenIsEmitted() {
        AtomicBoolean ownershipLost = new AtomicBoolean(false);
        AtomicBoolean upstreamCancelled = new AtomicBoolean(false);
        Flux<String> tokens = Flux.<String, Integer>generate(
                        () -> 0,
                        (index, sink) -> {
                            sink.next(index == 0 ? "first" : "second");
                            return index + 1;
                        })
                .doOnCancel(() -> upstreamCancelled.set(true));
        when(bullResearcher.argue(any(), eq(1))).thenReturn(tokens);
        when(bearResearcher.argue(any(), eq(1))).thenReturn(Flux.never());
        doAnswer(invocation -> {
            if ("first".equals(invocation.getArgument(5, String.class))) {
                ownershipLost.set(true);
            }
            return null;
        }).when(chatStreamEmitter).emitSection(any(), any(), any(), any(), any(), any());

        Runnable guard = () -> {
            if (ownershipLost.get()) {
                throw new DeepResearchPipeline.OwnershipLostException("lost");
            }
        };

        assertThatThrownBy(() -> service.runDebate(
                null, null, AnalysisState.builder().query("q").build(),
                1, 1, null, guard))
                .isInstanceOf(DeepResearchPipeline.OwnershipLostException.class);

        verify(chatStreamEmitter).emitSection(any(), any(), any(), any(), any(), eq("first"));
        verify(chatStreamEmitter, never()).emitSection(any(), any(), any(), any(), any(), eq("second"));
        assertThat(upstreamCancelled).isTrue();
    }

    @Test
    void pendingReportRecoveryUsesTheReservedAttemptAndCannotRepairTwiceAfterTakeover() {
        AnalysisState state = stateWithCompletedRounds(1);
        HarnessDecision pendingDecision = new HarnessDecision(
                HarnessOutcome.RECOVER,
                List.of(),
                List.of(RecoveryAction.RESYNTHESIZE_REPORT)
        );
        state.setHarnessSnapshot(HarnessSnapshot.recovery(
                DeepResearchCompletionPolicy.POLICY_ID,
                Integer.toString(DeepResearchCompletionPolicy.POLICY_VERSION),
                HarnessPhase.REPORT,
                pendingDecision,
                Map.of(RecoveryAction.RETRY_FUNDAMENTALS, 1),
                RecoveryLifecycle.PLANNED,
                List.of(RecoveryAction.RESYNTHESIZE_REPORT),
                "deep-report:trace-1:deep-equity-v1-v3:resynthesize_report-1"
        ));

        InvestmentReport invalidReport = InvestmentReport.builder()
                .analystSummary("still invalid")
                .build();
        when(researchManager.synthesizeStreamingResult(any(), any(), any(), any()))
                .thenReturn(Mono.just(new SynthesisResult(
                        invalidReport,
                        com.stocksage.harness.HarnessModels.ParseStatus.INVALID_SCHEMA,
                        List.of()
                )));
        HarnessDecision exhaustedDecision = new HarnessDecision(
                HarnessOutcome.DEGRADE,
                List.of(),
                List.of()
        );
        when(researchHarness.evaluateReport(
                any(),
                any(),
                eq(new RunContext(
                        "DEEP",
                        Map.of(
                                RecoveryAction.RETRY_FUNDAMENTALS, 1,
                                RecoveryAction.RESYNTHESIZE_REPORT, 1
                        )
                )),
                any(),
                any()
        )).thenReturn(exhaustedDecision);
        List<HarnessSnapshot> checkpoints = new ArrayList<>();
        List<InvestmentReport> checkpointedReports = new ArrayList<>();

        AnalysisState done = service.runDebate(
                "trace-1",
                20L,
                state,
                2,
                1,
                null,
                null,
                current -> {
                    checkpoints.add(current.getHarnessSnapshot());
                    checkpointedReports.add(current.getInvestmentReport());
                }
        );

        verify(researchManager, times(1))
                .synthesizeStreamingResult(any(), any(), any(), any());
        verify(researchHarness, times(1)).evaluateReport(
                any(),
                any(),
                eq(new RunContext(
                        "DEEP",
                        Map.of(
                                RecoveryAction.RETRY_FUNDAMENTALS, 1,
                                RecoveryAction.RESYNTHESIZE_REPORT, 1
                        )
                )),
                any(),
                any()
        );
        assertThat(checkpoints).hasSize(1);
        assertThat(checkpoints.get(0).recoveryLifecycle())
                .isEqualTo(RecoveryLifecycle.REVALIDATED);
        assertThat(checkpoints.get(0).recoveryAttempts())
                .containsEntry(RecoveryAction.RESYNTHESIZE_REPORT, 1)
                .containsEntry(RecoveryAction.RETRY_FUNDAMENTALS, 1);
        assertThat(checkpoints.get(0).recoveryEffectKey())
                .isEqualTo("deep-report:trace-1:deep-equity-v1-v3:resynthesize_report-1");
        assertThat(checkpointedReports).hasSize(1);
        assertThat(checkpointedReports.get(0).getQualityStatus())
                .isEqualTo(InvestmentReport.ReportQualityStatus.NOT_RATED);
        assertThat(done.getInvestmentReport().getQualityStatus())
                .isEqualTo(InvestmentReport.ReportQualityStatus.NOT_RATED);
    }

    @Test
    void revalidatedReportWithoutArtifactFailsClosedWithoutCallingManagerAgain() {
        AnalysisState state = stateWithCompletedRounds(1);
        HarnessDecision revalidatedDecision = new HarnessDecision(
                HarnessOutcome.PASS,
                List.of(),
                List.of()
        );
        state.setHarnessSnapshot(HarnessSnapshot.recovery(
                DeepResearchCompletionPolicy.POLICY_ID,
                Integer.toString(DeepResearchCompletionPolicy.POLICY_VERSION),
                HarnessPhase.REPORT,
                revalidatedDecision,
                Map.of(
                        RecoveryAction.RETRY_MARKET, 1,
                        RecoveryAction.RESYNTHESIZE_REPORT, 1
                ),
                RecoveryLifecycle.REVALIDATED,
                List.of(RecoveryAction.RESYNTHESIZE_REPORT),
                "deep-report:trace-1:deep-equity-v1-v3:resynthesize_report-1"
        ));
        List<HarnessSnapshot> checkpoints = new ArrayList<>();
        List<InvestmentReport> checkpointedReports = new ArrayList<>();

        AnalysisState done = service.runDebate(
                "trace-1",
                20L,
                state,
                2,
                1,
                null,
                null,
                current -> {
                    checkpoints.add(current.getHarnessSnapshot());
                    checkpointedReports.add(current.getInvestmentReport());
                }
        );

        verify(researchManager, never())
                .synthesizeStreamingResult(any(), any(), any(), any());
        verify(researchHarness, never())
                .evaluateReport(any(), any(), any(), any(), any());
        assertThat(checkpoints).hasSize(1);
        assertThat(checkpoints.get(0).outcome()).isEqualTo(HarnessOutcome.DEGRADE);
        assertThat(checkpoints.get(0).recoveryLifecycle())
                .isEqualTo(RecoveryLifecycle.REVALIDATED);
        assertThat(checkpoints.get(0).recoveryAttempts())
                .containsEntry(RecoveryAction.RESYNTHESIZE_REPORT, 1)
                .containsEntry(RecoveryAction.RETRY_MARKET, 1);
        assertThat(checkpoints.get(0).recoveryEffectKey())
                .isEqualTo("deep-report:trace-1:deep-equity-v1-v3:resynthesize_report-1");
        assertThat(checkpointedReports).hasSize(1);
        assertThat(checkpointedReports.get(0).getQualityStatus())
                .isEqualTo(InvestmentReport.ReportQualityStatus.NOT_RATED);
        assertThat(done.getInvestmentReport().getQualityStatus())
                .isEqualTo(InvestmentReport.ReportQualityStatus.NOT_RATED);
    }

    @Test
    void legacyReportRecoverySnapshotWithoutLifecycleAlsoUsesOnlyOneReservedAttempt()
            throws Exception {
        AnalysisState state = stateWithCompletedRounds(1);
        state.setHarnessSnapshot(new ObjectMapper().readValue(
                """
                {
                  "policyId":"deep-equity-v1",
                  "policyVersion":"2",
                  "phase":"REPORT",
                  "outcome":"RECOVER",
                  "violations":["REPORT_SCHEMA_INVALID"],
                  "recoveryAttempts":{}
                }
                """,
                HarnessSnapshot.class
        ));
        assertThat(state.getHarnessSnapshot().recoveryLifecycle())
                .isEqualTo(RecoveryLifecycle.PLANNED);
        assertThat(state.getHarnessSnapshot().recoveryActions()).isEmpty();
        HarnessDecision passDecision =
                new HarnessDecision(HarnessOutcome.PASS, List.of(), List.of());
        when(researchHarness.evaluateReport(
                any(),
                any(),
                eq(new RunContext(
                        "DEEP",
                        Map.of(RecoveryAction.RESYNTHESIZE_REPORT, 1)
                )),
                any(),
                any()
        )).thenReturn(passDecision);
        List<HarnessSnapshot> checkpoints = new ArrayList<>();

        AnalysisState done = service.runDebate(
                "",
                20L,
                state,
                2,
                1,
                null,
                null,
                current -> checkpoints.add(current.getHarnessSnapshot())
        );

        verify(researchManager, times(1))
                .synthesizeStreamingResult(any(), any(), any(), any());
        assertThat(checkpoints).hasSize(1);
        assertThat(checkpoints.get(0).recoveryLifecycle())
                .isEqualTo(RecoveryLifecycle.REVALIDATED);
        assertThat(checkpoints.get(0).recoveryEffectKey())
                .isEqualTo("deep-report:conversation-20:deep-equity-v1-v2:resynthesize_report-1");
        assertThat(done.getInvestmentReport().getQualityStatus())
                .isEqualTo(InvestmentReport.ReportQualityStatus.VERIFIED);
    }

    @Test
    void checkpointedCurrentReportPassesCurrentPolicyAndRefreshesCheckpointWithoutManager() {
        AnalysisState state = stateWithCompletedRounds(1);
        state.setPrimaryTicker("AAPL");
        state.setEvidenceLedger(completeLedger());
        InvestmentReport report = validReport(
                "e-MARKET-market",
                DeepResearchCompletionPolicy.POLICY_ID,
                DeepResearchCompletionPolicy.POLICY_VERSION
        );
        state.setInvestmentReport(report);
        List<HarnessSnapshot> checkpoints = new ArrayList<>();

        AnalysisState done = serviceWithRealHarness().revalidateCheckpointedReport(
                "trace-current",
                20L,
                state,
                null,
                current -> checkpoints.add(current.getHarnessSnapshot())
        );

        verify(researchManager, never())
                .synthesizeStreamingResult(any(), any(), any(), any());
        verify(bullResearcher, never()).argue(any(), anyInt());
        verify(bearResearcher, never()).argue(any(), anyInt());
        assertThat(checkpoints).hasSize(1);
        assertThat(done.getInvestmentReport()).isSameAs(report);
        assertThat(done.getInvestmentReport().getQualityStatus())
                .isEqualTo(InvestmentReport.ReportQualityStatus.VERIFIED);
        assertThat(done.getHarnessSnapshot().outcome()).isEqualTo(HarnessOutcome.PASS);
        assertThat(done.getHarnessSnapshot().policyId())
                .isEqualTo(DeepResearchCompletionPolicy.POLICY_ID);
        assertThat(done.getHarnessSnapshot().policyVersion())
                .isEqualTo(Integer.toString(DeepResearchCompletionPolicy.POLICY_VERSION));
    }

    @Test
    void checkpointedOldPolicyMetadataConsumesOneManagerRepairWithoutReplayingDebate() {
        AnalysisState state = stateWithCompletedRounds(1);
        state.setPrimaryTicker("AAPL");
        state.setEvidenceLedger(completeLedger());
        state.setInvestmentReport(validReport("e-MARKET-market", "deep-equity-v0", 1));
        InvestmentReport repaired = validReport(
                "e-MARKET-market",
                DeepResearchCompletionPolicy.POLICY_ID,
                DeepResearchCompletionPolicy.POLICY_VERSION
        );
        when(researchManager.synthesizeStreamingResult(any(), any(), any(), any()))
                .thenReturn(Mono.just(new SynthesisResult(
                        repaired,
                        com.stocksage.harness.HarnessModels.ParseStatus.VALID,
                        List.of()
                )));
        List<HarnessSnapshot> checkpoints = new ArrayList<>();
        List<InvestmentReport> checkpointedReports = new ArrayList<>();
        HarnessObserver observer = mock(HarnessObserver.class);

        AnalysisState done = serviceWithRealHarness(observer).revalidateCheckpointedReport(
                "trace-old-policy",
                20L,
                state,
                null,
                current -> {
                    checkpoints.add(current.getHarnessSnapshot());
                    checkpointedReports.add(current.getInvestmentReport());
                }
        );

        verify(researchManager, times(1))
                .synthesizeStreamingResult(any(), any(), any(), any());
        verify(bullResearcher, never()).argue(any(), anyInt());
        verify(bearResearcher, never()).argue(any(), anyInt());
        verify(debateRoundPlanner, never()).decide(any(), anyInt());
        ArgumentCaptor<HarnessDecision> observedDecisions =
                ArgumentCaptor.forClass(HarnessDecision.class);
        verify(observer, times(2)).reportDecision(
                eq("trace-old-policy"),
                eq(completionPolicy),
                observedDecisions.capture(),
                eq(state.getEvidenceLedger()),
                anyLong()
        );
        assertThat(observedDecisions.getAllValues())
                .extracting(HarnessDecision::outcome)
                .containsExactly(HarnessOutcome.RECOVER, HarnessOutcome.PASS);
        assertThat(checkpoints).hasSize(2);
        assertThat(checkpointedReports.get(0)).isNull();
        assertThat(checkpoints.get(0).recoveryLifecycle())
                .isEqualTo(RecoveryLifecycle.PLANNED);
        assertThat(checkpoints.get(1).recoveryLifecycle())
                .isEqualTo(RecoveryLifecycle.REVALIDATED);
        assertThat(checkpointedReports.get(1)).isSameAs(repaired);
        assertThat(done.getInvestmentReport().getQualityStatus())
                .isEqualTo(InvestmentReport.ReportQualityStatus.VERIFIED);
        assertThat(done.getInvestmentReport().getCompletionPolicyId())
                .isEqualTo(DeepResearchCompletionPolicy.POLICY_ID);
    }

    @Test
    void checkpointedUnknownEvidenceIsRepairedOnceWithoutReplayingDebate() {
        AnalysisState state = stateWithCompletedRounds(1);
        state.setPrimaryTicker("AAPL");
        state.setEvidenceLedger(completeLedger());
        state.setInvestmentReport(validReport(
                "unknown-evidence",
                DeepResearchCompletionPolicy.POLICY_ID,
                DeepResearchCompletionPolicy.POLICY_VERSION
        ));
        InvestmentReport repaired = validReport(
                "e-MARKET-market",
                DeepResearchCompletionPolicy.POLICY_ID,
                DeepResearchCompletionPolicy.POLICY_VERSION
        );
        when(researchManager.synthesizeStreamingResult(any(), any(), any(), any()))
                .thenReturn(Mono.just(new SynthesisResult(
                        repaired,
                        com.stocksage.harness.HarnessModels.ParseStatus.VALID,
                        List.of()
                )));

        AnalysisState done = serviceWithRealHarness().revalidateCheckpointedReport(
                "trace-unknown",
                20L,
                state,
                null,
                current -> {
                }
        );

        verify(researchManager, times(1))
                .synthesizeStreamingResult(any(), any(), any(), any());
        verify(bullResearcher, never()).argue(any(), anyInt());
        verify(bearResearcher, never()).argue(any(), anyInt());
        assertThat(done.getInvestmentReport().getQualityStatus())
                .isEqualTo(InvestmentReport.ReportQualityStatus.VERIFIED);
        assertThat(done.getHarnessSnapshot().recoveryAttempts())
                .containsEntry(RecoveryAction.RESYNTHESIZE_REPORT, 1);
    }

    @Test
    void revalidatedDegradedReportDoesNotReceiveAnotherRepairAndKeepsAllAttempts() {
        AnalysisState state = stateWithCompletedRounds(1);
        state.setPrimaryTicker("AAPL");
        state.setEvidenceLedger(completeLedger());
        state.setInvestmentReport(validReport(
                "unknown-evidence",
                DeepResearchCompletionPolicy.POLICY_ID,
                DeepResearchCompletionPolicy.POLICY_VERSION
        ));
        String effectKey =
                "deep-report:trace-exhausted:deep-equity-v1-v3:resynthesize_report-1";
        state.setHarnessSnapshot(HarnessSnapshot.recovery(
                DeepResearchCompletionPolicy.POLICY_ID,
                Integer.toString(DeepResearchCompletionPolicy.POLICY_VERSION),
                HarnessPhase.REPORT,
                new HarnessDecision(HarnessOutcome.DEGRADE, List.of(), List.of()),
                Map.of(
                        RecoveryAction.RETRY_FUNDAMENTALS, 1,
                        RecoveryAction.RESYNTHESIZE_REPORT, 1
                ),
                RecoveryLifecycle.REVALIDATED,
                List.of(RecoveryAction.RESYNTHESIZE_REPORT),
                effectKey
        ));

        AnalysisState done = serviceWithRealHarness().revalidateCheckpointedReport(
                "trace-exhausted",
                20L,
                state,
                null,
                current -> {
                }
        );

        verify(researchManager, never())
                .synthesizeStreamingResult(any(), any(), any(), any());
        assertThat(done.getInvestmentReport().getQualityStatus())
                .isEqualTo(InvestmentReport.ReportQualityStatus.NOT_RATED);
        assertThat(done.getHarnessSnapshot().outcome()).isEqualTo(HarnessOutcome.DEGRADE);
        assertThat(done.getHarnessSnapshot().recoveryAttempts())
                .containsEntry(RecoveryAction.RETRY_FUNDAMENTALS, 1)
                .containsEntry(RecoveryAction.RESYNTHESIZE_REPORT, 1);
        assertThat(done.getHarnessSnapshot().recoveryEffectKey()).isEqualTo(effectKey);
    }

    @Test
    void checkpointedInvalidReportWithoutCompleteDebateRoundFailsClosed() {
        AnalysisState state = AnalysisState.builder()
                .query("q")
                .primaryTicker("AAPL")
                .evidenceLedger(completeLedger())
                .investmentReport(validReport(
                        "unknown-evidence",
                        DeepResearchCompletionPolicy.POLICY_ID,
                        DeepResearchCompletionPolicy.POLICY_VERSION
                ))
                .build();

        AnalysisState done = serviceWithRealHarness().revalidateCheckpointedReport(
                "trace-no-rounds",
                20L,
                state,
                null,
                current -> {
                }
        );

        verify(researchManager, never())
                .synthesizeStreamingResult(any(), any(), any(), any());
        verify(bullResearcher, never()).argue(any(), anyInt());
        verify(bearResearcher, never()).argue(any(), anyInt());
        assertThat(done.getInvestmentReport().getQualityStatus())
                .isEqualTo(InvestmentReport.ReportQualityStatus.NOT_RATED);
        assertThat(done.getHarnessSnapshot().outcome()).isEqualTo(HarnessOutcome.DEGRADE);
    }

    private ResearchDebateService serviceWithRealHarness() {
        return serviceWithRealHarness(mock(HarnessObserver.class));
    }

    private ResearchDebateService serviceWithRealHarness(HarnessObserver observer) {
        return new ResearchDebateService(
                bullResearcher,
                bearResearcher,
                researchManager,
                debateRoundPlanner,
                mock(TraceService.class),
                chatStreamEmitter,
                new ResearchHarness(observer),
                completionPolicy
        );
    }

    private InvestmentReport validReport(
            String evidenceId,
            String policyId,
            Integer policyVersion
    ) {
        return InvestmentReport.builder()
                .ticker("AAPL")
                .recommendation("HOLD")
                .analystSummary("Balanced evidence.")
                .dataFreshness("Observed 2026-07-31.")
                .rationale(List.of("Revenue and market data are available."))
                .riskFactors(List.of("Valuation risk."))
                .unknowns(List.of("Future guidance."))
                .evidenceItems(List.of(InvestmentReport.EvidenceItem.builder()
                        .dimension("market")
                        .evidence("Market evidence")
                        .implication("Supports a bounded rating")
                        .source("tool:market")
                        .sourceEvidenceIds(List.of(evidenceId))
                        .build()))
                .qualityStatus(InvestmentReport.ReportQualityStatus.VERIFIED)
                .completionPolicyId(policyId)
                .completionPolicyVersion(policyVersion)
                .build();
    }

    private EvidenceLedger completeLedger() {
        return new EvidenceLedger(
                TargetIdentity.resolved("AAPL"),
                List.of(
                        availableEvidence(EvidenceDimension.FUNDAMENTALS, "fundamentals"),
                        availableEvidence(EvidenceDimension.MARKET, "market"),
                        availableEvidence(EvidenceDimension.NEWS, "news")
                )
        );
    }

    private EvidenceEnvelope availableEvidence(
            EvidenceDimension dimension,
            String capability
    ) {
        return new EvidenceEnvelope(
                "e-" + dimension + "-" + capability,
                dimension,
                capability,
                "AAPL",
                EvidenceStatus.AVAILABLE,
                "tool:" + capability,
                "test-provider",
                Instant.parse("2026-07-31T00:00:00Z"),
                Instant.parse("2026-07-31T00:00:00Z"),
                "hash-" + capability,
                true
        );
    }

    private AnalysisState stateWithCompletedRounds(int rounds) {
        AnalysisState state = AnalysisState.builder().query("q").build();
        for (int round = 1; round <= rounds; round++) {
            state.getDebateTurns().add(new AnalysisState.DebateTurn(
                    round, AnalysisState.DebateTurn.Side.BULL, "old-bull-r" + round));
            state.getDebateTurns().add(new AnalysisState.DebateTurn(
                    round, AnalysisState.DebateTurn.Side.BEAR, "old-bear-r" + round));
        }
        return state;
    }
}
