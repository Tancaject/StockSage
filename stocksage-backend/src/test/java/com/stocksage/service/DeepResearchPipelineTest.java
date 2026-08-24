package com.stocksage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.agent.ResearchDebateService;
import com.stocksage.agent.ModelTier;
import com.stocksage.harness.DeepResearchCompletionPolicy;
import com.stocksage.harness.EvidenceLedger;
import com.stocksage.harness.HarnessModels.EvidenceDimension;
import com.stocksage.harness.HarnessModels.EvidenceEnvelope;
import com.stocksage.harness.HarnessModels.EvidenceStatus;
import com.stocksage.harness.HarnessModels.HarnessDecision;
import com.stocksage.harness.HarnessModels.HarnessOutcome;
import com.stocksage.harness.HarnessModels.HarnessPhase;
import com.stocksage.harness.HarnessModels.HarnessSnapshot;
import com.stocksage.harness.HarnessModels.RecoveryAction;
import com.stocksage.harness.HarnessModels.RecoveryLifecycle;
import com.stocksage.harness.HarnessModels.RunContext;
import com.stocksage.harness.HarnessModels.TargetIdentity;
import com.stocksage.harness.HarnessModels.ViolationCode;
import com.stocksage.model.dto.AnalysisHorizon;
import com.stocksage.model.dto.AnalysisState;
import com.stocksage.model.dto.DebateModels.DebatePoint;
import com.stocksage.model.dto.DebateModels.DebateTurn;
import com.stocksage.model.dto.DebateModels.EvidenceRef;
import com.stocksage.model.dto.DebateModels.PointType;
import com.stocksage.model.dto.DebateModels.Side;
import com.stocksage.model.dto.InvestmentReport;
import com.stocksage.model.entity.ResearchTask;
import com.stocksage.model.entity.User;
import com.stocksage.repository.UserAccountRepository;
import com.stocksage.tool.ChatStreamEmitter;
import com.stocksage.trace.TraceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DeepResearchPipelineTest {

    private static final String CURRENT_POLICY_TAG = DeepResearchCompletionPolicy.POLICY_ID
            + "-v" + DeepResearchCompletionPolicy.POLICY_VERSION;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final InvestmentReportVersionService investmentReportVersionService = mock(InvestmentReportVersionService.class);
    private final ResearchTaskService researchTaskService = mock(ResearchTaskService.class);
    private final ResearchDebateService researchDebateService = mock(ResearchDebateService.class);
    private final OfflineDemoSampleService offlineDemoSampleService = mock(OfflineDemoSampleService.class);
    private final ResearchTaskCheckpointService checkpointService = mock(ResearchTaskCheckpointService.class);
    private final DeepEvidenceCollector evidenceCollector = mock(DeepEvidenceCollector.class);
    private final ReportMarkdownRenderer reportRenderer = mock(ReportMarkdownRenderer.class);
    private final ConversationMessageService conversationMessageService = mock(ConversationMessageService.class);
    private final UserAccountRepository userAccountRepository = mock(UserAccountRepository.class);
    private final ResearchTaskPublicationTransaction publicationTransaction =
            new ResearchTaskPublicationTransaction(userAccountRepository);
    private final ChatStreamEmitter chatStreamEmitter = mock(ChatStreamEmitter.class);
    private final TraceService traceService = mock(TraceService.class);
    private final TaskScheduler researchHeartbeatScheduler = mock(TaskScheduler.class);
    private final DeepResearchPipeline pipeline = new DeepResearchPipeline(
            researchTaskService,
            researchDebateService,
            investmentReportVersionService,
            offlineDemoSampleService,
            objectMapper,
            researchHeartbeatScheduler,
            checkpointService,
            evidenceCollector,
            reportRenderer,
            conversationMessageService,
            publicationTransaction,
            chatStreamEmitter,
            traceService
    );

    @BeforeEach
    void setUpHeartbeatInterval() {
        when(researchTaskService.leaseHeartbeatInterval()).thenReturn(Duration.ofSeconds(60));
        when(researchTaskService.renewLease(any())).thenReturn(true);
        when(researchTaskService.heartbeatForOwner(any(), any())).thenReturn(true);
        User publicationUser = new User();
        publicationUser.setUserId("u_001");
        when(userAccountRepository.lockByUserIdForUpdate(any()))
                .thenReturn(Optional.of(publicationUser));
        when(evidenceCollector.reevaluateCheckpoint(
                any(AnalysisState.class), any(Map.class), any()))
                .thenAnswer(call -> evidence(
                        call.getArgument(0),
                        new HarnessDecision(HarnessOutcome.PASS, List.of(), List.of()),
                        true
                ));
    }

    @Test
    void schedulesLeaseHeartbeatUsingTtlAwareInterval() {
        ResearchTask task = pendingTask(6L);
        ResearchTaskLeaseService.Lease lease = lease();
        Duration heartbeatInterval = Duration.ofMillis(3_333);
        when(checkpointService.load(6L)).thenReturn(Optional.empty());
        when(researchTaskService.startAttempt(task, lease.token(), ResearchTask.Stage.DATA_PREFETCH)).thenReturn(task);
        when(researchTaskService.leaseHeartbeatInterval()).thenReturn(heartbeatInterval);
        when(evidenceCollector.collect("AAPL", "q", "trace-1", 20L))
                .thenReturn(new DeepEvidenceCollector.EvidenceCollection(
                        "ctx", AnalysisState.builder().query("q").primaryTicker("AAPL").build(),
                        false, true, false, false));
        when(reportRenderer.buildInsufficientEvidenceReport("AAPL", true, false, false))
                .thenReturn("insufficient");

        pipeline.runFullPipeline(task, lease);

        verify(researchHeartbeatScheduler).scheduleAtFixedRate(
                any(Runnable.class), any(Instant.class), eq(heartbeatInterval));
    }

    @Test
    void lostRedisLeaseSkipsDbHeartbeatAndStopsBeforeCheckpointOrTerminalWrites() {
        ResearchTask task = pendingTask(60L);
        ResearchTaskLeaseService.Lease lease = lease();
        AtomicReference<Runnable> heartbeatAction = new AtomicReference<>();
        when(researchHeartbeatScheduler.scheduleAtFixedRate(
                any(Runnable.class), any(Instant.class), any(Duration.class)))
                .thenAnswer(call -> {
                    heartbeatAction.set(call.getArgument(0));
                    return mock(ScheduledFuture.class);
                });
        when(researchTaskService.startAttempt(
                task, lease.token(), ResearchTask.Stage.DATA_PREFETCH)).thenReturn(task);
        when(checkpointService.load(60L)).thenReturn(Optional.empty());
        when(evidenceCollector.collect("AAPL", "q", "trace-1", 20L))
                .thenAnswer(call -> {
                    clearInvocations(researchTaskService);
                    when(researchTaskService.renewLease(lease)).thenReturn(false);
                    heartbeatAction.get().run();
                    return new DeepEvidenceCollector.EvidenceCollection(
                            "ctx",
                            AnalysisState.builder().query("q").primaryTicker("AAPL").build(),
                            true,
                            true,
                            true,
                            true
                    );
                });

        assertThatThrownBy(() -> pipeline.runFullPipeline(task, lease))
                .isInstanceOf(DeepResearchPipeline.OwnershipLostException.class);

        verify(researchTaskService).renewLease(lease);
        verify(researchTaskService, never()).heartbeatForOwner(any(), any());
        verify(checkpointService, never()).saveEvidence(any(), any(), any());
        verify(investmentReportVersionService, never())
                .persistReportVersionWithMetadata(any(), any(), any(), any(), any());
        verify(researchTaskService, never()).markSucceededForOwner(any(), any(), any());
        verify(researchTaskService, never()).markFailedForOwner(any(), any(), any());
        verify(chatStreamEmitter, never()).emit(any(), any(), eq("task-final"), any());
        verify(chatStreamEmitter, never()).emit(any(), any(), eq("error"), any());
    }

    @Test
    void runningTaskWithDifferentTokenStopsBeforeCheckpointReload() {
        ResearchTask task = pendingTask(601L);
        task.setStatus(ResearchTask.Status.RUNNING);
        task.setLeaseToken("previous-owner");
        ResearchTaskLeaseService.Lease lease = lease();

        assertThatThrownBy(() -> pipeline.runFullPipeline(task, lease))
                .isInstanceOf(DeepResearchPipeline.OwnershipLostException.class);

        verify(checkpointService, never()).load(601L);
        verify(researchTaskService, never()).startAttempt(any(), any(), any());
        verifyNoInteractions(evidenceCollector, conversationMessageService);
        verify(chatStreamEmitter, never()).emit(any(), any(), any(), any());
    }

    @Test
    void postAttemptFailureDoesNotEmitErrorWhenOwnerFencedFailureUpdateRejects() {
        ResearchTask task = pendingTask(602L);
        ResearchTaskLeaseService.Lease lease = lease();
        when(researchTaskService.startAttempt(
                task, lease.token(), ResearchTask.Stage.DATA_PREFETCH)).thenReturn(task);
        when(checkpointService.load(602L)).thenReturn(Optional.empty());
        when(evidenceCollector.collect("AAPL", "q", "trace-1", 20L))
                .thenThrow(new IllegalStateException("evidence failed"));
        when(researchTaskService.markFailedForOwner(task, lease.token(), "evidence failed"))
                .thenReturn(false);

        assertThatThrownBy(() -> pipeline.runFullPipeline(task, lease))
                .isInstanceOf(DeepResearchPipeline.OwnershipLostException.class)
                .hasMessageContaining("failure update");

        verify(researchTaskService).markFailedForOwner(task, lease.token(), "evidence failed");
        verify(chatStreamEmitter, never()).emit(any(), any(), eq("error"), any());
        verify(chatStreamEmitter, never()).emit(any(), any(), eq("task-final"), any());
    }

    @Test
    void completedCheckpointCleanupFailureStillEmitsSuccessfulTaskFinal() {
        ResearchTask task = pendingTask(603L);
        ResearchTaskLeaseService.Lease lease = lease();
        AnalysisState state = AnalysisState.builder().query("q").primaryTicker("AAPL").build();
        when(researchTaskService.startAttempt(
                task, lease.token(), ResearchTask.Stage.DATA_PREFETCH)).thenReturn(task);
        when(checkpointService.load(603L)).thenReturn(Optional.empty());
        when(evidenceCollector.collect("AAPL", "q", "trace-1", 20L))
                .thenReturn(new DeepEvidenceCollector.EvidenceCollection(
                        "ctx", state, false, true, false, false));
        when(reportRenderer.buildInsufficientEvidenceReport("AAPL", true, false, false))
                .thenReturn("insufficient");
        doThrow(new ResearchTaskCheckpointService.CheckpointCleanupRejectedException(603L))
                .when(checkpointService).deleteForCompletedTask(603L);

        pipeline.runFullPipeline(task, lease);

        verify(researchTaskService).markSucceededForOwner(
                task, lease.token(), null, ResearchTask.ResultKind.INSUFFICIENT_EVIDENCE);
        verify(chatStreamEmitter).emit("trace-1", 20L, "task-final", "insufficient");
        verify(researchTaskService, never()).markFailedForOwner(any(), any(), any());
    }

    @Test
    void preAttemptFailureMarksAndReportsTaskOnlyWhenItIsStillPending() {
        ResearchTask task = pendingTask(61L);
        ResearchTaskLeaseService.Lease lease = lease();
        when(checkpointService.load(61L)).thenReturn(Optional.empty());
        when(researchTaskService.startAttempt(
                task, lease.token(), ResearchTask.Stage.DATA_PREFETCH))
                .thenThrow(new IllegalArgumentException("invalid setup"));
        when(researchTaskService.markFailedIfPending(task, "invalid setup")).thenReturn(true);

        pipeline.runFullPipeline(task, lease);

        verify(researchTaskService).markFailedIfPending(task, "invalid setup");
        verify(chatStreamEmitter).emit(
                eq("trace-1"), eq(20L), eq("error"), any(String.class));
    }

    @Test
    void startFenceRejectionDoesNotOverwriteOrReportAnotherOwnersRunningTask() {
        ResearchTask task = pendingTask(62L);
        ResearchTaskLeaseService.Lease lease = lease();
        when(checkpointService.load(62L)).thenReturn(Optional.empty());
        when(researchTaskService.startAttempt(
                task, lease.token(), ResearchTask.Stage.DATA_PREFETCH))
                .thenThrow(new IllegalStateException("fencing rejected"));
        when(researchTaskService.markFailedIfPending(task, "fencing rejected")).thenReturn(false);

        assertThatThrownBy(() -> pipeline.runFullPipeline(task, lease))
                .isInstanceOf(DeepResearchPipeline.OwnershipLostException.class)
                .hasMessageContaining("left pending state")
                .hasCauseInstanceOf(IllegalStateException.class);

        verify(researchTaskService).markFailedIfPending(task, "fencing rejected");
        verify(researchTaskService, never()).markFailed(task, "fencing rejected");
        verifyNoInteractions(chatStreamEmitter);
    }

    @Test
    void freshTaskRunsFullPipelineAndEmitsTaskFinal() {
        ResearchTask task = pendingTask(7L);
        ResearchTaskLeaseService.Lease lease = lease();
        AnalysisState evidenceState = AnalysisState.builder().query("q").primaryTicker("AAPL").build();
        AnalysisState completed = AnalysisState.builder()
                .query("q")
                .primaryTicker("AAPL")
                .investmentReport(InvestmentReport.builder()
                        .ticker("AAPL")
                        .recommendation("BUY")
                        .qualityStatus(InvestmentReport.ReportQualityStatus.VERIFIED)
                        .build())
                .build();
        InvestmentReport canonicalReport = InvestmentReport.builder()
                .ticker("AAPL")
                .recommendation("HOLD")
                .analystSummary("canonical stored report")
                .qualityStatus(InvestmentReport.ReportQualityStatus.VERIFIED)
                .build();
        when(checkpointService.load(7L)).thenReturn(Optional.empty());
        when(researchTaskService.startAttempt(task, lease.token(), ResearchTask.Stage.DATA_PREFETCH)).thenReturn(task);
        when(evidenceCollector.collect("AAPL", "q", "trace-1", 20L))
                .thenReturn(new DeepEvidenceCollector.EvidenceCollection("ctx", evidenceState, true, true, true, true));
        when(investmentReportVersionService.findReusableReport("u_001", 20L, evidenceState))
                .thenReturn(Optional.empty());
        when(researchDebateService.runDebate(
                eq("trace-1"), eq(20L), eq(evidenceState), eq(1), eq(0),
                any(), any(Runnable.class), any()))
                .thenReturn(completed);
        when(investmentReportVersionService.persistReportVersionWithMetadata(
                "u_001", 20L, completed, ModelTier.STRONG.name(), null))
                .thenAnswer(call -> {
                    completed.setInvestmentReport(canonicalReport);
                    return new InvestmentReportVersionService.PersistedReportVersion(
                            canonicalReport, 99L, true);
                });
        when(reportRenderer.buildFinalAnswerBrief(completed)).thenAnswer(call ->
                completed.getInvestmentReport() == canonicalReport
                        ? "canonical brief"
                        : "stale brief");
        doAnswer(call -> {
            task.setStatus(ResearchTask.Status.SUCCEEDED);
            task.setStage(ResearchTask.Stage.COMPLETE);
            task.setResultReportVersionId(99L);
            task.setResultKind(ResearchTask.ResultKind.FULL_REPORT);
            return null;
        }).when(researchTaskService).markSucceededForOwner(
                task,
                lease.token(),
                99L,
                ResearchTask.ResultKind.FULL_REPORT
        );

        pipeline.runFullPipeline(task, lease);

        verify(checkpointService).saveEvidence(7L, lease.token(), evidenceState);
        verify(researchTaskService).markStageForOwner(task, lease.token(), ResearchTask.Stage.AGENT_DEBATE);
        verify(checkpointService).saveSynthesis(7L, lease.token(), completed);
        InOrder publicationOrder = inOrder(
                investmentReportVersionService,
                reportRenderer,
                conversationMessageService,
                researchTaskService
        );
        publicationOrder.verify(investmentReportVersionService)
                .persistReportVersionWithMetadata(
                        "u_001", 20L, completed, ModelTier.STRONG.name(), null);
        publicationOrder.verify(reportRenderer).buildFinalAnswerBrief(completed);
        publicationOrder.verify(conversationMessageService)
                .persistAssistantReport(20L, "u_001", "canonical brief", "trace-1");
        publicationOrder.verify(researchTaskService).markSucceededForOwner(
                task, lease.token(), 99L, ResearchTask.ResultKind.FULL_REPORT);
        verify(researchTaskService).markSucceededForOwner(
                task, lease.token(), 99L, ResearchTask.ResultKind.FULL_REPORT);
        verify(checkpointService).deleteForCompletedTask(7L);
        verify(chatStreamEmitter).emit("trace-1", 20L, "task-final", "canonical brief");
        verify(traceService).endTrace(
                eq("trace-1"),
                eq("success"),
                eq(0),
                any(Long.class)
        );
    }

    @Test
    void insufficientEvidenceSucceedsWithoutReportVersionOrDebate() {
        ResearchTask task = pendingTask(8L);
        ResearchTaskLeaseService.Lease lease = lease();
        AnalysisState state = AnalysisState.builder().query("q").primaryTicker("AAPL").build();
        when(checkpointService.load(8L)).thenReturn(Optional.empty());
        when(researchTaskService.startAttempt(task, lease.token(), ResearchTask.Stage.DATA_PREFETCH)).thenReturn(task);
        when(evidenceCollector.collect("AAPL", "q", "trace-1", 20L))
                .thenReturn(new DeepEvidenceCollector.EvidenceCollection("ctx", state, false, true, false, false));
        when(reportRenderer.buildInsufficientEvidenceReport("AAPL", true, false, false))
                .thenReturn("insufficient");

        pipeline.runFullPipeline(task, lease);

        verify(researchDebateService, never()).runDebate(
                any(), any(), any(), any(Integer.class), any(Integer.class),
                any(), any(Runnable.class), any());
        verify(investmentReportVersionService, never()).persistReportVersionWithMetadata(any(), any(), any(), any(), any());
        verify(conversationMessageService).persistAssistantReport(20L, "u_001", "insufficient", "trace-1");
        verify(researchTaskService).markSucceededForOwner(
                task, lease.token(), null, ResearchTask.ResultKind.INSUFFICIENT_EVIDENCE);
        verify(chatStreamEmitter).emit("trace-1", 20L, "task-final", "insufficient");
    }

    @Test
    void checkpointAfterTwoRoundsPassesCurrentEvidenceGateThenResumesAtRoundThree() {
        ResearchTask task = pendingTask(9L);
        ResearchTaskLeaseService.Lease lease = lease();
        AnalysisState state = AnalysisState.builder().query("q").primaryTicker("AAPL").build();
        AnalysisState completed = AnalysisState.builder()
                .query("q")
                .primaryTicker("AAPL")
                .investmentReport(InvestmentReport.builder()
                        .ticker("AAPL")
                        .recommendation("HOLD")
                        .qualityStatus(InvestmentReport.ReportQualityStatus.VERIFIED)
                        .build())
                .build();
        when(checkpointService.load(9L)).thenReturn(Optional.of(
                new ResearchTaskCheckpointService.CheckpointState(
                        ResearchTask.Stage.AGENT_DEBATE, 2, 3, state)));
        when(researchTaskService.startAttempt(task, lease.token(), ResearchTask.Stage.DATA_PREFETCH)).thenReturn(task);
        when(researchDebateService.runDebate(
                eq("trace-1"), eq(20L), eq(state), eq(3), eq(3),
                any(), any(Runnable.class), any()))
                .thenReturn(completed);
        when(investmentReportVersionService.persistReportVersionWithMetadata(
                "u_001", 20L, completed, ModelTier.STRONG.name(), null))
                .thenReturn(new InvestmentReportVersionService.PersistedReportVersion(
                        completed.getInvestmentReport(), 100L, false));
        when(reportRenderer.buildFinalAnswerBrief(completed)).thenReturn("resumed");

        pipeline.runFullPipeline(task, lease);

        verify(evidenceCollector, never()).collect(any(), any(), any(), any());
        verify(evidenceCollector).reevaluateCheckpoint(state, Map.of(), "trace-1");
        verify(researchDebateService).runDebate(
                eq("trace-1"), eq(20L), eq(state), eq(3), eq(3),
                any(), any(Runnable.class), any());
        verify(researchTaskService).markSucceededForOwner(
                task, lease.token(), 100L, ResearchTask.ResultKind.FULL_REPORT);
    }

    @Test
    void agentDebateCheckpointWithUnapprovedCapabilityCannotBypassCurrentEvidenceGate() {
        ResearchTask task = runningTask(910L);
        ResearchTaskLeaseService.Lease lease = lease();
        EvidenceLedger ledger = completeLedger(
                availableEvidence(
                        "e-market",
                        EvidenceDimension.MARKET,
                        "AAPL",
                        "tool:bars",
                        false
                )
        );
        AnalysisState state = checkpointState("AAPL", ledger, false);
        HarnessDecision blocked = new DeepResearchCompletionPolicy().afterEvidence(
                RunContext.deepResearch(), ledger);

        assertThat(blocked.outcome()).isEqualTo(HarnessOutcome.BLOCK);
        assertThat(blocked.violations())
                .anyMatch(violation -> violation.code() == ViolationCode.UNAPPROVED_CAPABILITY);

        when(checkpointService.load(910L)).thenReturn(Optional.of(
                new ResearchTaskCheckpointService.CheckpointState(
                        ResearchTask.Stage.AGENT_DEBATE, 1, 3, state)));
        when(evidenceCollector.reevaluateCheckpoint(state, Map.of(), "trace-1"))
                .thenReturn(checkpointEvidence(state, blocked));
        when(reportRenderer.buildInsufficientEvidenceReport("AAPL", true, true, true))
                .thenReturn("blocked");

        pipeline.runFullPipeline(task, lease);

        verify(evidenceCollector).reevaluateCheckpoint(state, Map.of(), "trace-1");
        verify(researchDebateService, never()).runDebate(
                any(), any(), any(), any(Integer.class), any(Integer.class),
                any(), any(Runnable.class), any());
        verify(researchDebateService, never()).revalidateCheckpointedReport(
                any(), any(), any(), any(Runnable.class), any());
        verify(investmentReportVersionService, never())
                .persistReportVersionWithMetadata(any(), any(), any(), any(), any());
        verify(researchTaskService).markSucceededForOwner(
                task, lease.token(), null, ResearchTask.ResultKind.POLICY_BLOCKED);
    }

    @Test
    void reportSynthesisCheckpointWithMissingProvenanceCannotBypassCurrentEvidenceGate() {
        ResearchTask task = runningTask(911L);
        ResearchTaskLeaseService.Lease lease = lease();
        EvidenceLedger ledger = new EvidenceLedger(
                TargetIdentity.resolved("AAPL"),
                List.of(
                        new EvidenceEnvelope(
                                "e-fundamentals",
                                EvidenceDimension.FUNDAMENTALS,
                                "financials",
                                "AAPL",
                                EvidenceStatus.AVAILABLE,
                                "",
                                "test",
                                Instant.parse("2026-07-24T00:00:00Z"),
                                Instant.parse("2026-07-24T00:00:00Z"),
                                "fundamentals-hash",
                                true
                        ),
                        availableEvidence(
                                "e-market",
                                EvidenceDimension.MARKET,
                                "AAPL",
                                "tool:bars",
                                true
                        )
                )
        );
        AnalysisState state = checkpointState("AAPL", ledger, true);
        HarnessDecision degraded = new DeepResearchCompletionPolicy().afterEvidence(
                RunContext.deepResearch(), ledger);

        assertThat(degraded.outcome()).isEqualTo(HarnessOutcome.DEGRADE);
        assertThat(degraded.violations())
                .anyMatch(violation -> violation.code() == ViolationCode.PROVENANCE_MISSING);

        when(checkpointService.load(911L)).thenReturn(Optional.of(
                new ResearchTaskCheckpointService.CheckpointState(
                        ResearchTask.Stage.REPORT_SYNTHESIS, 2, 2, state)));
        when(evidenceCollector.reevaluateCheckpoint(state, Map.of(), "trace-1"))
                .thenReturn(checkpointEvidence(state, degraded));
        when(reportRenderer.buildInsufficientEvidenceReport("AAPL", true, true, true))
                .thenReturn("not rated");

        pipeline.runFullPipeline(task, lease);

        verify(evidenceCollector).reevaluateCheckpoint(state, Map.of(), "trace-1");
        verify(researchDebateService, never()).revalidateCheckpointedReport(
                any(), any(), any(), any(Runnable.class), any());
        verify(researchDebateService, never()).runDebate(
                any(), any(), any(), any(Integer.class), any(Integer.class),
                any(), any(Runnable.class), any());
        verify(investmentReportVersionService, never())
                .persistReportVersionWithMetadata(any(), any(), any(), any(), any());
        verify(researchTaskService).markSucceededForOwner(
                task, lease.token(), null, ResearchTask.ResultKind.INSUFFICIENT_EVIDENCE);
    }

    @Test
    void reportSynthesisCheckpointWithCrossTargetEvidenceCannotBypassCurrentEvidenceGate() {
        ResearchTask task = runningTask(912L);
        ResearchTaskLeaseService.Lease lease = lease();
        EvidenceLedger ledger = completeLedger(
                availableEvidence(
                        "e-market",
                        EvidenceDimension.MARKET,
                        "MSFT",
                        "tool:bars",
                        true
                )
        );
        AnalysisState state = checkpointState("AAPL", ledger, true);
        HarnessDecision blocked = new DeepResearchCompletionPolicy().afterEvidence(
                RunContext.deepResearch(), ledger);

        assertThat(blocked.outcome()).isEqualTo(HarnessOutcome.BLOCK);
        assertThat(blocked.violations())
                .anyMatch(violation -> violation.code() == ViolationCode.TARGET_MISMATCH);

        when(checkpointService.load(912L)).thenReturn(Optional.of(
                new ResearchTaskCheckpointService.CheckpointState(
                        ResearchTask.Stage.REPORT_SYNTHESIS, 2, 2, state)));
        when(evidenceCollector.reevaluateCheckpoint(state, Map.of(), "trace-1"))
                .thenReturn(checkpointEvidence(state, blocked));
        when(reportRenderer.buildInsufficientEvidenceReport("AAPL", true, true, true))
                .thenReturn("blocked");

        pipeline.runFullPipeline(task, lease);

        verify(evidenceCollector).reevaluateCheckpoint(state, Map.of(), "trace-1");
        verify(researchDebateService, never()).revalidateCheckpointedReport(
                any(), any(), any(), any(Runnable.class), any());
        verify(investmentReportVersionService, never())
                .persistReportVersionWithMetadata(any(), any(), any(), any(), any());
        verify(researchTaskService).markSucceededForOwner(
                task, lease.token(), null, ResearchTask.ResultKind.POLICY_BLOCKED);
    }

    @Test
    void takeoverExecutesPlannedEvidenceRecoveryAndRevalidatesBeforeDebate() {
        ResearchTask task = runningTask(901L);
        ResearchTaskLeaseService.Lease lease = lease();
        HarnessDecision recoverDecision = recoverFundamentalsDecision();
        AnalysisState state = AnalysisState.builder().query("q").primaryTicker("AAPL").build();
        state.setHarnessSnapshot(HarnessSnapshot.recovery(
                "deep-equity-v1",
                Integer.toString(DeepResearchCompletionPolicy.POLICY_VERSION),
                HarnessPhase.EVIDENCE,
                recoverDecision,
                Map.of(),
                RecoveryLifecycle.PLANNED,
                List.of(RecoveryAction.RETRY_FUNDAMENTALS),
                "deep-evidence:901:" + CURRENT_POLICY_TAG + ":retry_fundamentals-1"
        ));
        HarnessDecision passDecision =
                new HarnessDecision(HarnessOutcome.PASS, List.of(), List.of());
        // Even if re-evaluation sees a transient PASS, the durable PLANNED effect must not be skipped.
        DeepEvidenceCollector.EvidenceCollection reevaluated = evidence(state, passDecision, true);
        DeepEvidenceCollector.EvidenceCollection recovered = evidence(state, passDecision, true);
        AnalysisState completed = verifiedState("AAPL");
        List<HarnessSnapshot> persistedSnapshots = new ArrayList<>();

        when(checkpointService.load(901L)).thenReturn(Optional.of(
                new ResearchTaskCheckpointService.CheckpointState(
                        ResearchTask.Stage.DATA_PREFETCH, 0, 0, state)));
        when(evidenceCollector.reevaluateCheckpoint(state, Map.of(), "trace-1"))
                .thenReturn(reevaluated);
        when(evidenceCollector.recover(
                any(DeepEvidenceCollector.EvidenceCollection.class),
                eq(List.of(RecoveryAction.RETRY_FUNDAMENTALS)),
                eq(Map.of()),
                eq("trace-1"),
                eq(20L)
        )).thenReturn(recovered);
        org.mockito.Mockito.doAnswer(call -> {
            persistedSnapshots.add(call.getArgument(2, AnalysisState.class).getHarnessSnapshot());
            return null;
        }).when(checkpointService).saveHarnessSnapshot(eq(901L), eq(lease.token()), any());
        when(investmentReportVersionService.findReusableReport("u_001", 20L, state))
                .thenReturn(Optional.empty());
        when(researchDebateService.runDebate(
                eq("trace-1"), eq(20L), eq(state), eq(1), eq(0),
                any(), any(Runnable.class), any()))
                .thenReturn(completed);
        when(investmentReportVersionService.persistReportVersionWithMetadata(
                "u_001", 20L, completed, ModelTier.STRONG.name(), null))
                .thenReturn(new InvestmentReportVersionService.PersistedReportVersion(
                        completed.getInvestmentReport(), 9010L, false));
        when(reportRenderer.buildFinalAnswerBrief(completed)).thenReturn("resumed");

        pipeline.runFullPipeline(task, lease);

        assertThat(persistedSnapshots).hasSize(2);
        assertThat(persistedSnapshots.get(0).recoveryLifecycle())
                .isEqualTo(RecoveryLifecycle.PLANNED);
        assertThat(persistedSnapshots.get(1).recoveryLifecycle())
                .isEqualTo(RecoveryLifecycle.REVALIDATED);
        assertThat(persistedSnapshots.get(0).recoveryEffectKey())
                .isEqualTo(persistedSnapshots.get(1).recoveryEffectKey())
                .isEqualTo("deep-evidence:901:" + CURRENT_POLICY_TAG
                        + ":retry_fundamentals-1");
        assertThat(persistedSnapshots.get(1).recoveryAttempts())
                .containsEntry(RecoveryAction.RETRY_FUNDAMENTALS, 1);

        InOrder order = inOrder(evidenceCollector, checkpointService, researchDebateService);
        order.verify(evidenceCollector).reevaluateCheckpoint(state, Map.of(), "trace-1");
        order.verify(checkpointService).saveHarnessSnapshot(901L, lease.token(), state);
        order.verify(evidenceCollector).recover(
                any(DeepEvidenceCollector.EvidenceCollection.class),
                eq(List.of(RecoveryAction.RETRY_FUNDAMENTALS)),
                eq(Map.of()),
                eq("trace-1"),
                eq(20L)
        );
        order.verify(checkpointService).saveHarnessSnapshot(901L, lease.token(), state);
        order.verify(researchDebateService).runDebate(
                eq("trace-1"), eq(20L), eq(state), eq(1), eq(0),
                any(), any(Runnable.class), any());
    }

    @Test
    void evidenceRecoverySuspendsAndRestoresPlannedReportRepairWithoutResettingBudgets() {
        ResearchTask task = runningTask(913L);
        ResearchTaskLeaseService.Lease lease = lease();
        EvidenceEnvelope marketEvidence = availableEvidence(
                "e-market",
                EvidenceDimension.MARKET,
                "AAPL",
                "tool:bars",
                true
        );
        EvidenceLedger incompleteLedger = new EvidenceLedger(
                TargetIdentity.resolved("AAPL"),
                List.of(marketEvidence)
        );
        EvidenceLedger recoveredLedger = completeLedger(marketEvidence);
        AnalysisState state = checkpointState("AAPL", incompleteLedger, true);
        addCompletedRoundOne(state);
        state.setDataSnapshotHash("old-data-hash");
        state.setContextHash("old-context-hash");
        String reportEffectKey =
                "deep-report:trace-1:" + CURRENT_POLICY_TAG + ":resynthesize_report-1";
        HarnessDecision reportPlan = new HarnessDecision(
                HarnessOutcome.RECOVER,
                List.of(new com.stocksage.harness.HarnessModels.HarnessViolation(
                        ViolationCode.REPORT_SCHEMA_INVALID, null)),
                List.of(RecoveryAction.RESYNTHESIZE_REPORT)
        );
        state.setHarnessSnapshot(HarnessSnapshot.recovery(
                DeepResearchCompletionPolicy.POLICY_ID,
                Integer.toString(DeepResearchCompletionPolicy.POLICY_VERSION),
                HarnessPhase.REPORT,
                reportPlan,
                Map.of(RecoveryAction.RETRY_NEWS, 1),
                RecoveryLifecycle.PLANNED,
                List.of(RecoveryAction.RESYNTHESIZE_REPORT),
                reportEffectKey
        ));

        Map<RecoveryAction, Integer> previousAttempts =
                Map.of(RecoveryAction.RETRY_NEWS, 1);
        HarnessDecision evidenceRecovery = new DeepResearchCompletionPolicy().afterEvidence(
                new RunContext("DEEP", previousAttempts),
                incompleteLedger
        );
        assertThat(evidenceRecovery.outcome()).isEqualTo(HarnessOutcome.RECOVER);
        assertThat(evidenceRecovery.recoveryActions())
                .containsExactly(RecoveryAction.RETRY_FUNDAMENTALS);
        DeepEvidenceCollector.EvidenceCollection pending =
                checkpointEvidence(state, evidenceRecovery);
        HarnessDecision evidencePass =
                new HarnessDecision(HarnessOutcome.PASS, List.of(), List.of());
        List<HarnessSnapshot> persistedEvidenceSnapshots = new ArrayList<>();
        InvestmentReportVersionService hasher = reportHasher();

        when(checkpointService.load(913L)).thenReturn(Optional.of(
                new ResearchTaskCheckpointService.CheckpointState(
                        ResearchTask.Stage.REPORT_SYNTHESIS, 1, 1, state)));
        when(evidenceCollector.reevaluateCheckpoint(
                state, previousAttempts, "trace-1"))
                .thenReturn(pending);
        when(evidenceCollector.recover(
                pending,
                List.of(RecoveryAction.RETRY_FUNDAMENTALS),
                previousAttempts,
                "trace-1",
                20L
        )).thenAnswer(invocation -> {
            state.setEvidenceLedger(recoveredLedger);
            return checkpointEvidence(state, evidencePass);
        });
        doAnswer(invocation -> {
            persistedEvidenceSnapshots.add(
                    invocation.getArgument(2, AnalysisState.class).getHarnessSnapshot());
            return null;
        }).when(checkpointService).saveHarnessSnapshot(913L, lease.token(), state);
        doAnswer(invocation -> {
            hasher.prepareHashes(invocation.getArgument(0, AnalysisState.class));
            return null;
        }).when(investmentReportVersionService).prepareHashes(state);
        when(researchDebateService.revalidateCheckpointedReport(
                eq("trace-1"), eq(20L), eq(state), any(Runnable.class), any()))
                .thenAnswer(invocation -> {
                    HarnessSnapshot restored = state.getHarnessSnapshot();
                    assertThat(restored.phase()).isEqualTo(HarnessPhase.REPORT);
                    assertThat(restored.recoveryLifecycle())
                            .isEqualTo(RecoveryLifecycle.PLANNED);
                    assertThat(restored.recoveryEffectKey()).isEqualTo(reportEffectKey);
                    assertThat(restored.recoveryAttempts())
                            .containsEntry(RecoveryAction.RETRY_NEWS, 1)
                            .containsEntry(RecoveryAction.RETRY_FUNDAMENTALS, 1);
                    return state;
                });
        when(investmentReportVersionService.persistReportVersionWithMetadata(
                "u_001", 20L, state, ModelTier.STRONG.name(), null))
                .thenReturn(new InvestmentReportVersionService.PersistedReportVersion(
                        state.getInvestmentReport(), 9130L, false));
        when(reportRenderer.buildFinalAnswerBrief(state)).thenReturn("recovered report");

        pipeline.runFullPipeline(task, lease);

        assertThat(persistedEvidenceSnapshots).hasSize(2);
        assertThat(persistedEvidenceSnapshots)
                .allSatisfy(snapshot -> {
                    assertThat(snapshot.phase()).isEqualTo(HarnessPhase.EVIDENCE);
                    assertThat(snapshot.suspendedRecovery()).isNotNull();
                    assertThat(snapshot.suspendedRecovery().recoveryLifecycle())
                            .isEqualTo(RecoveryLifecycle.PLANNED);
                    assertThat(snapshot.suspendedRecovery().recoveryEffectKey())
                            .isEqualTo(reportEffectKey);
                });
        assertThat(persistedEvidenceSnapshots.get(1).recoveryAttempts())
                .containsEntry(RecoveryAction.RETRY_NEWS, 1)
                .containsEntry(RecoveryAction.RETRY_FUNDAMENTALS, 1);
        assertThat(state.getDataSnapshotHash())
                .isEqualTo(hasher.computeDataSnapshotHash(state))
                .isNotEqualTo("old-data-hash");
        assertThat(state.getContextHash())
                .isEqualTo(hasher.computeContextHash(state))
                .isNotEqualTo("old-context-hash");
        verify(researchTaskService).markSucceededForOwner(
                task, lease.token(), 9130L, ResearchTask.ResultKind.FULL_REPORT);
    }

    @Test
    void crashAfterRecoveryEffectBeforeRevalidationRepeatsSameLogicalEffectOnTakeover() {
        ResearchTask firstAttempt = pendingTask(904L);
        ResearchTask takeover = runningTask(904L);
        ResearchTaskLeaseService.Lease lease = lease();
        HarnessDecision recoverDecision = recoverFundamentalsDecision();
        HarnessDecision passDecision =
                new HarnessDecision(HarnessOutcome.PASS, List.of(), List.of());
        AnalysisState state = AnalysisState.builder().query("q").primaryTicker("AAPL").build();
        DeepEvidenceCollector.EvidenceCollection pending = evidence(state, recoverDecision, false);
        DeepEvidenceCollector.EvidenceCollection recovered = evidence(state, passDecision, true);
        InvestmentReport reusable = InvestmentReport.builder()
                .ticker("AAPL")
                .recommendation("HOLD")
                .qualityStatus(InvestmentReport.ReportQualityStatus.VERIFIED)
                .build();
        List<HarnessSnapshot> persistedSnapshots = new ArrayList<>();

        when(researchTaskService.startAttempt(
                firstAttempt, lease.token(), ResearchTask.Stage.DATA_PREFETCH))
                .thenReturn(firstAttempt);
        when(checkpointService.load(904L))
                .thenReturn(Optional.empty())
                .thenAnswer(call -> Optional.of(
                        new ResearchTaskCheckpointService.CheckpointState(
                                ResearchTask.Stage.DATA_PREFETCH, 0, 0, state)));
        when(evidenceCollector.collect("AAPL", "q", "trace-1", 20L))
                .thenReturn(pending);
        when(evidenceCollector.reevaluateCheckpoint(state, Map.of(), "trace-1"))
                .thenReturn(pending);
        when(evidenceCollector.recover(
                pending,
                List.of(RecoveryAction.RETRY_FUNDAMENTALS),
                Map.of(),
                "trace-1",
                20L
        )).thenThrow(new SimulatedCrashAfterRecoveryEffect())
                .thenReturn(recovered);
        org.mockito.Mockito.doAnswer(call -> {
            persistedSnapshots.add(call.getArgument(2, AnalysisState.class).getHarnessSnapshot());
            return null;
        }).when(checkpointService).saveHarnessSnapshot(eq(904L), eq(lease.token()), any());
        when(investmentReportVersionService.findReusableReport("u_001", 20L, state))
                .thenReturn(Optional.of(reusable));
        when(reportRenderer.buildFinalAnswerBrief(state)).thenReturn("reused");

        assertThatThrownBy(() -> pipeline.runFullPipeline(firstAttempt, lease))
                .isInstanceOf(SimulatedCrashAfterRecoveryEffect.class);

        pipeline.runFullPipeline(takeover, lease);

        assertThat(persistedSnapshots).hasSize(3);
        assertThat(persistedSnapshots.get(0).recoveryLifecycle())
                .isEqualTo(RecoveryLifecycle.PLANNED);
        assertThat(persistedSnapshots.get(1).recoveryLifecycle())
                .isEqualTo(RecoveryLifecycle.PLANNED);
        assertThat(persistedSnapshots.get(2).recoveryLifecycle())
                .isEqualTo(RecoveryLifecycle.REVALIDATED);
        assertThat(persistedSnapshots)
                .extracting(HarnessSnapshot::recoveryEffectKey)
                .containsOnly("deep-evidence:904:" + CURRENT_POLICY_TAG
                        + ":retry_fundamentals-1");
        verify(evidenceCollector, times(2)).recover(
                pending,
                List.of(RecoveryAction.RETRY_FUNDAMENTALS),
                Map.of(),
                "trace-1",
                20L
        );
        verify(researchDebateService, never()).runDebate(
                any(), any(), any(), any(Integer.class), any(Integer.class),
                any(), any(Runnable.class), any());
    }

    @Test
    void revalidatedEvidenceCheckpointDoesNotRepeatRecoveryAfterTakeover() {
        ResearchTask task = runningTask(902L);
        ResearchTaskLeaseService.Lease lease = lease();
        HarnessDecision passDecision =
                new HarnessDecision(HarnessOutcome.PASS, List.of(), List.of());
        AnalysisState state = AnalysisState.builder().query("q").primaryTicker("AAPL").build();
        state.setHarnessSnapshot(HarnessSnapshot.recovery(
                "deep-equity-v1",
                Integer.toString(DeepResearchCompletionPolicy.POLICY_VERSION),
                HarnessPhase.EVIDENCE,
                passDecision,
                Map.of(RecoveryAction.RETRY_FUNDAMENTALS, 1),
                RecoveryLifecycle.REVALIDATED,
                List.of(RecoveryAction.RETRY_FUNDAMENTALS),
                "deep-evidence:902:" + CURRENT_POLICY_TAG + ":retry_fundamentals-1"
        ));
        DeepEvidenceCollector.EvidenceCollection revalidated = evidence(state, passDecision, true);
        AnalysisState completed = verifiedState("AAPL");

        when(checkpointService.load(902L)).thenReturn(Optional.of(
                new ResearchTaskCheckpointService.CheckpointState(
                        ResearchTask.Stage.DATA_PREFETCH, 0, 0, state)));
        when(evidenceCollector.reevaluateCheckpoint(
                state,
                Map.of(RecoveryAction.RETRY_FUNDAMENTALS, 1),
                "trace-1"
        )).thenReturn(revalidated);
        when(investmentReportVersionService.findReusableReport("u_001", 20L, state))
                .thenReturn(Optional.empty());
        when(researchDebateService.runDebate(
                eq("trace-1"), eq(20L), eq(state), eq(1), eq(0),
                any(), any(Runnable.class), any()))
                .thenReturn(completed);
        when(investmentReportVersionService.persistReportVersionWithMetadata(
                "u_001", 20L, completed, ModelTier.STRONG.name(), null))
                .thenReturn(new InvestmentReportVersionService.PersistedReportVersion(
                        completed.getInvestmentReport(), 9020L, false));
        when(reportRenderer.buildFinalAnswerBrief(completed)).thenReturn("revalidated");

        pipeline.runFullPipeline(task, lease);

        verify(evidenceCollector, never()).recover(
                any(DeepEvidenceCollector.EvidenceCollection.class),
                any(),
                any(Map.class),
                any(),
                any()
        );
        verify(checkpointService, never()).saveHarnessSnapshot(any(), any(), any());
        verify(researchDebateService).runDebate(
                eq("trace-1"), eq(20L), eq(state), eq(1), eq(0),
                any(), any(Runnable.class), any());
    }

    @Test
    void legacyRecoverSnapshotWithoutLifecycleFailsSafeInsteadOfSkippingEvidenceGate()
            throws Exception {
        ResearchTask task = runningTask(903L);
        ResearchTaskLeaseService.Lease lease = lease();
        AnalysisState state = objectMapper.readValue(
                """
                {
                  "query":"q",
                  "primaryTicker":"AAPL",
                  "harnessSnapshot":{
                    "policyId":"deep-equity-v1",
                    "policyVersion":"2",
                    "phase":"EVIDENCE",
                    "outcome":"RECOVER",
                    "violations":["FUNDAMENTALS_MISSING"],
                    "recoveryAttempts":{}
                  }
                }
                """,
                AnalysisState.class
        );
        HarnessDecision blocked =
                new HarnessDecision(HarnessOutcome.BLOCK, List.of(), List.of());
        DeepEvidenceCollector.EvidenceCollection failSafe = evidence(state, blocked, false);

        assertThat(state.getHarnessSnapshot().recoveryLifecycle())
                .isEqualTo(RecoveryLifecycle.PLANNED);
        assertThat(state.getHarnessSnapshot().recoveryActions()).isEmpty();
        assertThat(state.getHarnessSnapshot().recoveryEffectKey()).isEmpty();

        when(checkpointService.load(903L)).thenReturn(Optional.of(
                new ResearchTaskCheckpointService.CheckpointState(
                        ResearchTask.Stage.DATA_PREFETCH, 0, 0, state)));
        when(evidenceCollector.reevaluateCheckpoint(state, Map.of(), "trace-1"))
                .thenReturn(failSafe);
        when(reportRenderer.buildInsufficientEvidenceReport("AAPL", false, false, false))
                .thenReturn("blocked");

        pipeline.runFullPipeline(task, lease);

        verify(evidenceCollector, never()).recover(
                any(DeepEvidenceCollector.EvidenceCollection.class),
                any(),
                any(Map.class),
                any(),
                any()
        );
        verify(researchDebateService, never()).runDebate(
                any(), any(), any(), any(Integer.class), any(Integer.class),
                any(), any(Runnable.class), any());
        verify(researchTaskService).markSucceededForOwner(
                task, lease.token(), null, ResearchTask.ResultKind.POLICY_BLOCKED);
    }

    @Test
    void checkpointedReportMustBeRevalidatedBeforeItCanPersistAsFullReport() {
        ResearchTask task = runningTask(905L);
        ResearchTaskLeaseService.Lease lease = lease();
        AnalysisState state = verifiedState("AAPL");
        addCompletedRoundOne(state);

        when(checkpointService.load(905L)).thenReturn(Optional.of(
                new ResearchTaskCheckpointService.CheckpointState(
                        ResearchTask.Stage.REPORT_SYNTHESIS, 1, 1, state)));
        when(researchDebateService.revalidateCheckpointedReport(
                eq("trace-1"), eq(20L), eq(state), any(Runnable.class), any()))
                .thenReturn(state);
        when(investmentReportVersionService.persistReportVersionWithMetadata(
                "u_001", 20L, state, ModelTier.STRONG.name(), null))
                .thenReturn(new InvestmentReportVersionService.PersistedReportVersion(
                        state.getInvestmentReport(), 9050L, false));
        when(reportRenderer.buildFinalAnswerBrief(state)).thenReturn("revalidated");

        pipeline.runFullPipeline(task, lease);

        InOrder order = inOrder(researchDebateService, investmentReportVersionService);
        order.verify(researchDebateService).revalidateCheckpointedReport(
                eq("trace-1"), eq(20L), eq(state), any(Runnable.class), any());
        order.verify(investmentReportVersionService).persistReportVersionWithMetadata(
                "u_001", 20L, state, ModelTier.STRONG.name(), null);
        verify(researchDebateService, never()).runDebate(
                any(), any(), any(), any(Integer.class), any(Integer.class),
                any(), any(Runnable.class), any());
        verify(researchTaskService).markSucceededForOwner(
                task, lease.token(), 9050L, ResearchTask.ResultKind.FULL_REPORT);
    }

    @Test
    void failedCheckpointedReportRevalidationCannotPersistFullReport() {
        ResearchTask task = runningTask(906L);
        ResearchTaskLeaseService.Lease lease = lease();
        AnalysisState state = verifiedState("AAPL");
        addCompletedRoundOne(state);

        when(checkpointService.load(906L)).thenReturn(Optional.of(
                new ResearchTaskCheckpointService.CheckpointState(
                        ResearchTask.Stage.REPORT_SYNTHESIS, 1, 1, state)));
        when(researchDebateService.revalidateCheckpointedReport(
                eq("trace-1"), eq(20L), eq(state), any(Runnable.class), any()))
                .thenAnswer(invocation -> {
                    state.getInvestmentReport().setQualityStatus(
                            InvestmentReport.ReportQualityStatus.NOT_RATED);
                    return state;
                });
        when(reportRenderer.buildFinalAnswerBrief(state)).thenReturn("not rated");

        pipeline.runFullPipeline(task, lease);

        verify(researchDebateService).revalidateCheckpointedReport(
                eq("trace-1"), eq(20L), eq(state), any(Runnable.class), any());
        verify(researchDebateService, never()).runDebate(
                any(), any(), any(), any(Integer.class), any(Integer.class),
                any(), any(Runnable.class), any());
        verify(investmentReportVersionService, never())
                .persistReportVersionWithMetadata(any(), any(), any(), any(), any());
        verify(researchTaskService).markSucceededForOwner(
                task, lease.token(), null, ResearchTask.ResultKind.INSUFFICIENT_EVIDENCE);
    }

    @Test
    void inlinePlannedReportRecoveryTakeoverResumesManagerWithoutRestartingDebate() {
        ResearchTask task = runningTask(907L);
        ResearchTaskLeaseService.Lease lease = lease();
        AnalysisState state = AnalysisState.builder()
                .query("q")
                .primaryTicker("AAPL")
                .build();
        HarnessDecision plannedDecision = new HarnessDecision(
                HarnessOutcome.RECOVER,
                List.of(),
                List.of(RecoveryAction.RESYNTHESIZE_REPORT)
        );
        AnalysisState completed = verifiedState("AAPL");

        when(researchDebateService.runDebate(
                eq("trace-1"), eq(20L), eq(state), eq(1), eq(0),
                any(ResearchDebateService.RoundCheckpointer.class),
                any(Runnable.class),
                any()
        )).thenAnswer(invocation -> {
            addCompletedRoundOne(state);
            ResearchDebateService.RoundCheckpointer roundCheckpointer =
                    invocation.getArgument(5);
            roundCheckpointer.onRoundCompleted(state, 1, 1);
            state.setHarnessSnapshot(HarnessSnapshot.recovery(
                    "deep-equity-v1",
                    Integer.toString(DeepResearchCompletionPolicy.POLICY_VERSION),
                    HarnessPhase.REPORT,
                    plannedDecision,
                    Map.of(),
                    RecoveryLifecycle.PLANNED,
                    List.of(RecoveryAction.RESYNTHESIZE_REPORT),
                    "deep-report:trace-1:" + CURRENT_POLICY_TAG + ":resynthesize_report-1"
            ));
            @SuppressWarnings("unchecked")
            Consumer<AnalysisState> harnessCheckpointer = invocation.getArgument(7);
            harnessCheckpointer.accept(state);
            throw new SimulatedCrashAfterRecoveryEffect();
        });

        assertThatThrownBy(() -> pipeline.runResearchDebateWithTask(
                "trace-1",
                20L,
                "u_001",
                state,
                null,
                task,
                lease
        )).isInstanceOf(SimulatedCrashAfterRecoveryEffect.class);

        when(checkpointService.load(907L)).thenReturn(Optional.of(
                new ResearchTaskCheckpointService.CheckpointState(
                        ResearchTask.Stage.AGENT_DEBATE, 1, 1, state)));
        when(researchDebateService.runDebate(
                eq("trace-1"), eq(20L), eq(state), eq(2), eq(1),
                any(ResearchDebateService.RoundCheckpointer.class),
                any(Runnable.class),
                any()
        )).thenReturn(completed);
        when(investmentReportVersionService.persistReportVersionWithMetadata(
                "u_001", 20L, completed, ModelTier.STRONG.name(), null))
                .thenReturn(new InvestmentReportVersionService.PersistedReportVersion(
                        completed.getInvestmentReport(), 9070L, false));
        when(reportRenderer.buildFinalAnswerBrief(completed)).thenReturn("resumed");

        pipeline.runFullPipeline(task, lease);

        verify(checkpointService).saveEvidence(907L, lease.token(), state);
        verify(checkpointService).saveDebateRound(907L, lease.token(), state, 1, 1);
        verify(checkpointService).saveHarnessSnapshot(907L, lease.token(), state);
        verify(researchDebateService).runDebate(
                eq("trace-1"), eq(20L), eq(state), eq(2), eq(1),
                any(ResearchDebateService.RoundCheckpointer.class),
                any(Runnable.class),
                any());
        verify(researchTaskService).markSucceededForOwner(
                task, lease.token(), 9070L, ResearchTask.ResultKind.FULL_REPORT);
    }

    @Test
    void legacyInlineReportCheckpointInfersCompletedRoundsFromPayloadBeforeTakeover() {
        ResearchTask task = runningTask(908L);
        ResearchTaskLeaseService.Lease lease = lease();
        AnalysisState state = AnalysisState.builder()
                .query("q")
                .primaryTicker("AAPL")
                .build();
        addCompletedRoundOne(state);
        state.setHarnessSnapshot(HarnessSnapshot.recovery(
                "deep-equity-v1",
                "2",
                HarnessPhase.REPORT,
                new HarnessDecision(
                        HarnessOutcome.RECOVER,
                        List.of(),
                        List.of(RecoveryAction.RESYNTHESIZE_REPORT)
                ),
                Map.of(),
                RecoveryLifecycle.PLANNED,
                List.of(RecoveryAction.RESYNTHESIZE_REPORT),
                "deep-report:trace-1:" + CURRENT_POLICY_TAG + ":resynthesize_report-1"
        ));
        HarnessDecision evidencePass =
                new HarnessDecision(HarnessOutcome.PASS, List.of(), List.of());
        AnalysisState completed = verifiedState("AAPL");

        when(checkpointService.load(908L)).thenReturn(Optional.of(
                new ResearchTaskCheckpointService.CheckpointState(
                        ResearchTask.Stage.DATA_PREFETCH, 0, 0, state)));
        when(evidenceCollector.reevaluateCheckpoint(state, Map.of(), "trace-1"))
                .thenReturn(evidence(state, evidencePass, true));
        when(investmentReportVersionService.findReusableReport(
                "u_001", 20L, state)).thenReturn(Optional.empty());
        when(researchDebateService.runDebate(
                eq("trace-1"), eq(20L), eq(state), eq(2), eq(1),
                any(ResearchDebateService.RoundCheckpointer.class),
                any(Runnable.class),
                any()
        )).thenReturn(completed);
        when(investmentReportVersionService.persistReportVersionWithMetadata(
                "u_001", 20L, completed, ModelTier.STRONG.name(), null))
                .thenReturn(new InvestmentReportVersionService.PersistedReportVersion(
                        completed.getInvestmentReport(), 9080L, false));
        when(reportRenderer.buildFinalAnswerBrief(completed)).thenReturn("resumed");

        pipeline.runFullPipeline(task, lease);

        verify(researchDebateService).runDebate(
                eq("trace-1"), eq(20L), eq(state), eq(2), eq(1),
                any(ResearchDebateService.RoundCheckpointer.class),
                any(Runnable.class),
                any());
        verify(researchDebateService, never()).runDebate(
                eq("trace-1"), eq(20L), eq(state), eq(1), eq(0),
                any(ResearchDebateService.RoundCheckpointer.class),
                any(Runnable.class),
                any());
        verify(researchTaskService).markSucceededForOwner(
                task, lease.token(), 9080L, ResearchTask.ResultKind.FULL_REPORT);
    }

    @Test
    void persistsOfflineFallbackReportAndCompletesTaskWhenDebateFails() {
        AnalysisState state = AnalysisState.builder()
                .primaryTicker("NVDA")
                .query("Should I buy NVDA?")
                .build();
        InvestmentReport fallback = InvestmentReport.builder()
                .ticker("NVDA")
                .recommendation("HOLD")
                .modelTier("DEMO")
                .modelName("offline-rule-fallback")
                .citations(List.of("[offline sample] deterministic fixture"))
                .analystSummary("offline demo fallback report")
                .build();
        ResearchTask task = new ResearchTask();
        task.setId(7L);
        ResearchTaskLeaseService.Lease lease = new ResearchTaskLeaseService.Lease(
                "investment-report:nvda",
                "owner-token",
                ResearchTaskLeaseService.Backend.PROCESS
        );
        when(offlineDemoSampleService.buildFallbackReport(state)).thenReturn(Optional.of(fallback));
        when(investmentReportVersionService.persistReportVersionWithMetadata(
                eq("u_001"),
                eq(20L),
                eq(state),
                eq("DEMO"),
                eq("offline-rule-fallback")
        )).thenReturn(new InvestmentReportVersionService.PersistedReportVersion(fallback, 99L, false));

        Optional<String> reportJson = pipeline.tryPersistOfflineFallbackReport(
                "u_001",
                20L,
                state,
                task,
                lease,
                new IllegalStateException("DashScope key missing")
        );

        assertThat(reportJson).isPresent();
        assertThat(reportJson.orElseThrow()).contains("offline-rule-fallback");
        assertThat(state.getInvestmentReport()).isSameAs(fallback);
        verify(researchTaskService).markStageForOwner(task, "owner-token", ResearchTask.Stage.REPORT_PERSIST);
        verify(researchTaskService).markSucceededForOwner(
                task, "owner-token", 99L, ResearchTask.ResultKind.OFFLINE_FALLBACK);
    }

    private DeepEvidenceCollector.EvidenceCollection evidence(
            AnalysisState state,
            HarnessDecision decision,
            boolean sufficient
    ) {
        return new DeepEvidenceCollector.EvidenceCollection(
                "ctx",
                state,
                sufficient,
                sufficient,
                sufficient,
                sufficient,
                sufficient,
                state == null || state.getEvidenceLedger() == null
                        ? EvidenceLedger.empty()
                        : state.getEvidenceLedger(),
                decision
        );
    }

    private DeepEvidenceCollector.EvidenceCollection checkpointEvidence(
            AnalysisState state,
            HarnessDecision decision
    ) {
        EvidenceLedger ledger = state.getEvidenceLedger();
        return new DeepEvidenceCollector.EvidenceCollection(
                "ctx",
                state,
                decision.outcome() == HarnessOutcome.PASS,
                ledger.target().isResolved(),
                ledger.hasUsable(EvidenceDimension.FUNDAMENTALS),
                ledger.hasUsable(EvidenceDimension.MARKET),
                ledger.hasUsable(EvidenceDimension.NEWS),
                ledger,
                decision
        );
    }

    private AnalysisState checkpointState(
            String ticker,
            EvidenceLedger ledger,
            boolean withReport
    ) {
        AnalysisState state = AnalysisState.builder()
                .query("q")
                .primaryTicker(ticker)
                .evidenceLedger(ledger)
                .build();
        if (withReport) {
            state.setInvestmentReport(InvestmentReport.builder()
                    .ticker(ticker)
                    .recommendation("HOLD")
                    .qualityStatus(InvestmentReport.ReportQualityStatus.VERIFIED)
                    .build());
        }
        return state;
    }

    private EvidenceLedger completeLedger(EvidenceEnvelope marketEvidence) {
        return new EvidenceLedger(
                TargetIdentity.resolved("AAPL"),
                List.of(
                        availableEvidence(
                                "e-fundamentals",
                                EvidenceDimension.FUNDAMENTALS,
                                "AAPL",
                                "tool:financials",
                                true
                        ),
                        marketEvidence
                )
        );
    }

    private EvidenceEnvelope availableEvidence(
            String evidenceId,
            EvidenceDimension dimension,
            String target,
            String source,
            boolean approvedReadOnly
    ) {
        Instant observedAt = Instant.parse("2026-07-24T00:00:00Z");
        return new EvidenceEnvelope(
                evidenceId,
                dimension,
                dimension.name().toLowerCase(),
                target,
                EvidenceStatus.AVAILABLE,
                source,
                "test",
                observedAt,
                observedAt,
                evidenceId + "-hash",
                approvedReadOnly
        );
    }

    private HarnessDecision recoverFundamentalsDecision() {
        return new HarnessDecision(
                HarnessOutcome.RECOVER,
                List.of(),
                List.of(RecoveryAction.RETRY_FUNDAMENTALS)
        );
    }

    private AnalysisState verifiedState(String ticker) {
        return AnalysisState.builder()
                .query("q")
                .primaryTicker(ticker)
                .investmentReport(InvestmentReport.builder()
                        .ticker(ticker)
                        .recommendation("HOLD")
                        .qualityStatus(InvestmentReport.ReportQualityStatus.VERIFIED)
                        .build())
                .build();
    }

    private void addCompletedRoundOne(AnalysisState state) {
        state.getDebateTurns().add(thesisTurn(Side.BULL, "bull"));
        state.getDebateTurns().add(thesisTurn(Side.BEAR, "bear"));
    }

    private DebateTurn thesisTurn(Side side, String prefix) {
        List<DebatePoint> points = java.util.stream.IntStream.rangeClosed(1, 3)
                .mapToObj(index -> new DebatePoint(
                        prefix + "-thesis-" + index,
                        PointType.THESIS,
                        prefix + " claim " + index,
                        AnalysisHorizon.MEDIUM_TERM,
                        List.of(new EvidenceRef(prefix + "-evidence-" + index,
                                prefix + " evidence excerpt " + index)),
                        prefix + " reasoning " + index,
                        prefix + " assumption " + index,
                        prefix + " invalidation " + index,
                        List.of()
                ))
                .toList();
        return new DebateTurn(1, side, points);
    }

    private InvestmentReportVersionService reportHasher() {
        return new InvestmentReportVersionService(
                mock(com.stocksage.repository.InvestmentReportVersionRepository.class),
                mock(com.stocksage.repository.InvestmentReportReviewRepository.class),
                objectMapper,
                mock(ApplicationEventPublisher.class),
                mock(ResearchMemoryService.class),
                new DeepResearchCompletionPolicy()
        );
    }

    private ResearchTask pendingTask(Long id) {
        ResearchTask task = new ResearchTask();
        task.setId(id);
        task.setUserId("u_001");
        task.setConversationId(20L);
        task.setTicker("AAPL");
        task.setPayloadJson("{\"ticker\":\"AAPL\",\"query\":\"q\",\"traceId\":\"trace-1\",\"conversationId\":20}");
        return task;
    }

    private ResearchTask runningTask(Long id) {
        ResearchTask task = pendingTask(id);
        task.setStatus(ResearchTask.Status.RUNNING);
        task.setLeaseToken("owner-token");
        return task;
    }

    private ResearchTaskLeaseService.Lease lease() {
        return new ResearchTaskLeaseService.Lease(
                "deep-submit:aapl",
                "owner-token",
                ResearchTaskLeaseService.Backend.PROCESS
        );
    }

    private static final class SimulatedCrashAfterRecoveryEffect extends Error {
    }
}
