package com.stocksage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.agent.Coordinator;
import com.stocksage.agent.ExecutionPlan;
import com.stocksage.agent.FundamentalsAgent;
import com.stocksage.agent.MarketAgent;
import com.stocksage.agent.ModelTier;
import com.stocksage.agent.NewsAgent;
import com.stocksage.agent.PlanAction;
import com.stocksage.agent.PlanRoute;
import com.stocksage.capability.CapabilityException;
import com.stocksage.capability.CapabilityGateway;
import com.stocksage.capability.LocalNewsSearchCapabilityAdapter;
import com.stocksage.harness.DeepResearchCompletionPolicy;
import com.stocksage.harness.EvidenceLedger;
import com.stocksage.harness.HarnessModels.HarnessDecision;
import com.stocksage.harness.HarnessModels.EvidenceDimension;
import com.stocksage.harness.HarnessModels.RunContext;
import com.stocksage.harness.HarnessModels.HarnessOutcome;
import com.stocksage.harness.HarnessModels.HarnessSnapshot;
import com.stocksage.harness.HarnessModels.RecoveryAction;
import com.stocksage.harness.HarnessModels.RecoveryLifecycle;
import com.stocksage.model.dto.AnalysisState;
import com.stocksage.model.entity.ResearchTask;
import com.stocksage.skill.SkillExecutionService;
import com.stocksage.skill.SkillExecutionObserver;
import com.stocksage.skill.SkillRegistry;
import com.stocksage.skill.SkillResolver;
import com.stocksage.skill.SkillValidator;
import com.stocksage.tool.ChatStreamEmitter;
import com.stocksage.tool.FundamentalsTools;
import com.stocksage.tool.MarketTools;
import com.stocksage.tool.NewsTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolPrefetchServiceSubmitTest {

    private static final String USER_ID = "user-1";
    private static final Long CONVERSATION_ID = 17L;
    private static final String TRACE_ID = "trace-17";
    private static final String QUERY = "苹果值不值得投资";
    private static final String TICKER = "AAPL";
    private static final String SUBMISSION_KEY = "deep-submit:key";
    private static final String PAYLOAD = "{\"ticker\":\"AAPL\"}";

    @Mock
    private MarketTools marketTools;
    @Mock
    private NewsTools newsTools;
    @Mock
    private FundamentalsTools fundamentalsTools;
    @Mock
    private FundamentalsAgent fundamentalsAgent;
    @Mock
    private MarketAgent marketAgent;
    @Mock
    private NewsAgent newsAgent;
    @Mock
    private TickerResolutionService tickerResolutionService;
    @Mock
    private ReportMarkdownRenderer reportRenderer;
    @Mock
    private DeepEvidenceCollector deepEvidenceCollector;
    @Mock
    private DeepEvidenceReplanService deepEvidenceReplanService;
    @Mock
    private DeepResearchPipeline deepResearchPipeline;
    @Mock
    private InvestmentReportVersionService investmentReportVersionService;
    @Mock
    private ResearchTaskService researchTaskService;
    @Mock
    private ResearchTaskQueue researchTaskQueue;
    @Mock
    private ResearchTaskCheckpointService researchTaskCheckpointService;
    @Mock
    private KnowledgeIngestionService knowledgeIngestionService;
    @Mock
    private SkillExecutionService skillExecutionService;
    @Mock
    private ChatStreamEmitter chatStreamEmitter;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private AsyncTaskExecutor agentTaskExecutor;
    @Mock
    private TaskScheduler researchHeartbeatScheduler;

    @InjectMocks
    private ToolPrefetchService service;

    private final ExecutionPlan deepPlan = new ExecutionPlan(
            PlanRoute.DEEP,
            "DEEP_RESEARCH",
            "research",
            List.of(PlanAction.RESEARCH_MANAGER),
            "",
            ModelTier.STRONG
    );
    private final Coordinator.SelectedModel selectedModel =
            new Coordinator.SelectedModel(ModelTier.STRONG, "test-model");

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "userMaxActive", 3);
        when(tickerResolutionService.resolvePrimaryTicker(QUERY, CONVERSATION_ID)).thenReturn(TICKER);
        lenient().when(deepEvidenceReplanService.replan(
                        any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(call -> call.getArgument(4));
    }

    @Test
    void normalSubmissionCreatesAndEnqueuesWithoutCollectingEvidence() {
        ResearchTask task = task(42L, ResearchTask.Status.PENDING);
        when(researchTaskService.countActiveTasks(USER_ID)).thenReturn(0);
        when(researchTaskService.buildSubmissionKey(USER_ID, TICKER, QUERY, CONVERSATION_ID)).thenReturn(SUBMISSION_KEY);
        when(researchTaskService.buildSubmissionPayload(TICKER, QUERY, TRACE_ID, CONVERSATION_ID))
                .thenReturn(PAYLOAD);
        when(researchTaskService.createIfAbsent(
                SUBMISSION_KEY,
                USER_ID,
                CONVERSATION_ID,
                TICKER,
                ResearchTask.Stage.CREATED,
                PAYLOAD
        )).thenReturn(new ResearchTaskService.TaskCreation(task, true));
        when(reportRenderer.buildTaskAcceptedAnswer(task)).thenReturn("AAPL 深度研究任务 #42 已受理");

        ToolPrefetchService.PreparedToolContext result = prefetch();

        assertThat(result.directAnswer()).contains("已受理");
        assertThat(result.submittedTaskId()).isEqualTo(42L);
        assertThat(result.taskOutcome()).isNull();
        verify(researchTaskService).createIfAbsent(
                SUBMISSION_KEY,
                USER_ID,
                CONVERSATION_ID,
                TICKER,
                ResearchTask.Stage.CREATED,
                PAYLOAD
        );
        verify(researchTaskQueue).enqueue(42L);
        verifyNoSubmissionWorkWasRun();
    }

    @Test
    void quotaExceededDoesNotCreateOrEnqueueTask() {
        when(researchTaskService.countActiveTasks(USER_ID)).thenReturn(3);
        when(reportRenderer.buildQuotaExceededAnswer(3, 3)).thenReturn("当前已有 3 个活跃任务，上限为 3");

        ToolPrefetchService.PreparedToolContext result = prefetch();

        assertThat(result.directAnswer()).contains("3");
        assertThat(result.submittedTaskId()).isNull();
        assertThat(result.taskOutcome()).isEqualTo("BLOCKED");
        verify(researchTaskService, never()).createIfAbsent(
                anyString(), anyString(), any(), anyString(), any(), anyString());
        verifyNoInteractions(researchTaskQueue);
        verifyNoSubmissionWorkWasRun();
    }

    @Test
    void duplicateActiveSubmissionObservesExistingTaskWithoutReenqueue() {
        ResearchTask existing = task(77L, ResearchTask.Status.RUNNING);
        when(researchTaskService.countActiveTasks(USER_ID)).thenReturn(1);
        when(researchTaskService.buildSubmissionKey(USER_ID, TICKER, QUERY, CONVERSATION_ID)).thenReturn(SUBMISSION_KEY);
        when(researchTaskService.buildSubmissionPayload(TICKER, QUERY, TRACE_ID, CONVERSATION_ID))
                .thenReturn(PAYLOAD);
        when(researchTaskService.createIfAbsent(
                SUBMISSION_KEY,
                USER_ID,
                CONVERSATION_ID,
                TICKER,
                ResearchTask.Stage.CREATED,
                PAYLOAD
        )).thenReturn(new ResearchTaskService.TaskCreation(existing, false));
        when(reportRenderer.buildTaskAcceptedAnswer(existing)).thenReturn("继续旁观任务 #77");

        ToolPrefetchService.PreparedToolContext result = prefetch();

        assertThat(result.submittedTaskId()).isEqualTo(77L);
        assertThat(result.directAnswer()).contains("77");
        assertThat(result.taskOutcome()).isNull();
        verifyNoInteractions(researchTaskQueue);
        verifyNoSubmissionWorkWasRun();
    }

    @Test
    void queueUnavailableFallsBackToInlineDeepResearch() throws Exception {
        String identity = "{\"symbol\":\"AAPL\",\"description\":\"" + "x".repeat(3600) + "\"}";
        when(marketTools.resolveStock(TICKER)).thenReturn(identity);
        ResearchTask task = task(88L, ResearchTask.Status.PENDING);
        ResearchTaskLeaseService.Lease lease = new ResearchTaskLeaseService.Lease(
                SUBMISSION_KEY,
                "lease-token",
                ResearchTaskLeaseService.Backend.PROCESS
        );
        AnalysisState state = AnalysisState.builder()
                .query(QUERY)
                .primaryTicker(TICKER)
                .build();
        DeepEvidenceCollector.EvidenceCollection evidence = new DeepEvidenceCollector.EvidenceCollection(
                "## evidence",
                state,
                true,
                true,
                true,
                true,
                false,
                EvidenceLedger.empty(),
                new HarnessDecision(HarnessOutcome.PASS, List.of(), List.of())
        );
        when(researchTaskService.countActiveTasks(USER_ID)).thenReturn(0);
        when(researchTaskService.buildSubmissionKey(USER_ID, TICKER, QUERY, CONVERSATION_ID)).thenReturn(SUBMISSION_KEY);
        when(researchTaskService.buildSubmissionPayload(TICKER, QUERY, TRACE_ID, CONVERSATION_ID))
                .thenReturn(PAYLOAD);
        when(researchTaskService.createIfAbsent(
                SUBMISSION_KEY,
                USER_ID,
                CONVERSATION_ID,
                TICKER,
                ResearchTask.Stage.CREATED,
                PAYLOAD
        )).thenReturn(new ResearchTaskService.TaskCreation(task, true));
        doThrow(new ResearchTaskQueue.QueueUnavailableException("Redis unavailable", new RuntimeException("down")))
                .when(researchTaskQueue).enqueue(88L);
        when(investmentReportVersionService.findReusableReport(USER_ID, CONVERSATION_ID, state))
                .thenReturn(Optional.empty());
        when(researchTaskService.tryAcquire(task)).thenReturn(Optional.of(lease));
        when(researchTaskService.startAttempt(task, lease.token(), ResearchTask.Stage.DATA_PREFETCH))
                .thenReturn(task);
        when(researchTaskService.leaseHeartbeatInterval()).thenReturn(Duration.ofSeconds(3));
        ScheduledFuture<?> heartbeat = org.mockito.Mockito.mock(ScheduledFuture.class);
        AtomicReference<Runnable> heartbeatAction = new AtomicReference<>();
        when(researchHeartbeatScheduler.scheduleAtFixedRate(
                any(Runnable.class), any(Instant.class), any(Duration.class)))
                .thenAnswer(invocation -> {
                    heartbeatAction.set(invocation.getArgument(0));
                    return heartbeat;
                });
        when(researchTaskService.renewLease(lease)).thenReturn(true);
        when(researchTaskService.heartbeatForOwner(task, lease.token())).thenReturn(true);
        when(deepEvidenceCollector.collect(TICKER, QUERY, TRACE_ID, CONVERSATION_ID))
                .thenAnswer(invocation -> {
                    heartbeatAction.get().run();
                    return evidence;
                });
        when(deepResearchPipeline.runResearchDebateWithTask(
                TRACE_ID,
                CONVERSATION_ID,
                USER_ID,
                state,
                selectedModel,
                task,
                lease
        )).thenReturn("inline report");

        ToolPrefetchService.PreparedToolContext result = prefetch();

        assertThat(result.context()).contains(identity);
        verify(marketTools).resolveStock(TICKER);
        verify(deepEvidenceCollector).collect(TICKER, QUERY, TRACE_ID, CONVERSATION_ID);
        verify(deepEvidenceReplanService).replan(
                eq(88L), eq(USER_ID), eq(CONVERSATION_ID), eq(TRACE_ID),
                eq(evidence), any(Runnable.class), any());
        verify(researchTaskService, atLeastOnce()).renewLease(lease);
        verify(researchTaskService, atLeastOnce()).heartbeatForOwner(task, lease.token());
        verify(heartbeat).cancel(false);
        verify(deepResearchPipeline).runResearchDebateWithTask(
                TRACE_ID,
                CONVERSATION_ID,
                USER_ID,
                state,
                selectedModel,
                task,
                lease
        );
    }

    @Test
    void inlineRecoveryPersistsPlannedAndRevalidatedSnapshotsWithOneStableEffectKey()
            throws Exception {
        ResearchTask task = task(89L, ResearchTask.Status.PENDING);
        ResearchTaskLeaseService.Lease lease = new ResearchTaskLeaseService.Lease(
                SUBMISSION_KEY,
                "lease-token",
                ResearchTaskLeaseService.Backend.PROCESS
        );
        AnalysisState state = AnalysisState.builder()
                .query(QUERY)
                .primaryTicker(TICKER)
                .build();
        EvidenceLedger ledger = EvidenceLedger.empty();
        state.setEvidenceLedger(ledger);
        HarnessDecision recoverDecision = new HarnessDecision(
                HarnessOutcome.RECOVER,
                List.of(),
                List.of(RecoveryAction.RETRY_MARKET)
        );
        DeepEvidenceCollector.EvidenceCollection pending =
                new DeepEvidenceCollector.EvidenceCollection(
                        "## pending evidence",
                        state,
                        false,
                        true,
                        true,
                        false,
                        false,
                        ledger,
                        recoverDecision
                );
        HarnessDecision passDecision = new HarnessDecision(
                HarnessOutcome.PASS,
                List.of(),
                List.of()
        );
        DeepEvidenceCollector.EvidenceCollection recovered =
                new DeepEvidenceCollector.EvidenceCollection(
                        "## recovered evidence",
                        state,
                        true,
                        true,
                        true,
                        true,
                        false,
                        ledger,
                        passDecision
                );
        when(researchTaskService.countActiveTasks(USER_ID)).thenReturn(0);
        when(researchTaskService.buildSubmissionKey(USER_ID, TICKER, QUERY, CONVERSATION_ID))
                .thenReturn(SUBMISSION_KEY);
        when(researchTaskService.buildSubmissionPayload(TICKER, QUERY, TRACE_ID, CONVERSATION_ID))
                .thenReturn(PAYLOAD);
        when(researchTaskService.createIfAbsent(
                SUBMISSION_KEY,
                USER_ID,
                CONVERSATION_ID,
                TICKER,
                ResearchTask.Stage.CREATED,
                PAYLOAD
        )).thenReturn(new ResearchTaskService.TaskCreation(task, true));
        doThrow(new ResearchTaskQueue.QueueUnavailableException(
                "Redis unavailable",
                new RuntimeException("down")
        )).when(researchTaskQueue).enqueue(89L);
        when(researchTaskService.tryAcquire(task)).thenReturn(Optional.of(lease));
        when(researchTaskService.startAttempt(task, lease.token(), ResearchTask.Stage.DATA_PREFETCH))
                .thenReturn(task);
        when(researchTaskService.renewLease(lease)).thenReturn(true);
        when(researchTaskService.heartbeatForOwner(task, lease.token())).thenReturn(true);
        when(deepEvidenceCollector.collect(TICKER, QUERY, TRACE_ID, CONVERSATION_ID))
                .thenReturn(pending);
        when(deepEvidenceCollector.recover(
                pending,
                List.of(RecoveryAction.RETRY_MARKET),
                TRACE_ID,
                CONVERSATION_ID
        )).thenReturn(recovered);
        when(investmentReportVersionService.findReusableReport(USER_ID, CONVERSATION_ID, state))
                .thenReturn(Optional.empty());
        when(deepResearchPipeline.runResearchDebateWithTask(
                TRACE_ID,
                CONVERSATION_ID,
                USER_ID,
                state,
                selectedModel,
                task,
                lease
        )).thenReturn("inline report");
        List<HarnessSnapshot> snapshots = new ArrayList<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            AnalysisState checkpointState = invocation.getArgument(2, AnalysisState.class);
            snapshots.add(checkpointState.getHarnessSnapshot());
            return null;
        }).when(researchTaskCheckpointService)
                .saveHarnessSnapshot(89L, lease.token(), state);

        prefetch();

        assertThat(snapshots).hasSize(2);
        assertThat(snapshots.get(0).recoveryLifecycle())
                .isEqualTo(RecoveryLifecycle.PLANNED);
        assertThat(snapshots.get(0).recoveryAttempts()).isEmpty();
        assertThat(snapshots.get(1).recoveryLifecycle())
                .isEqualTo(RecoveryLifecycle.REVALIDATED);
        assertThat(snapshots.get(1).recoveryAttempts())
                .containsEntry(RecoveryAction.RETRY_MARKET, 1);
        assertThat(snapshots)
                .extracting(HarnessSnapshot::recoveryEffectKey)
                .containsOnly(
                        "deep-evidence:89:"
                                + DeepResearchCompletionPolicy.POLICY_ID
                                + "-v"
                                + DeepResearchCompletionPolicy.POLICY_VERSION
                                + ":retry_market-1"
                );
        assertThat(snapshots)
                .allSatisfy(snapshot -> assertThat(snapshot.recoveryActions())
                        .containsExactly(RecoveryAction.RETRY_MARKET));
        verify(deepResearchPipeline).runResearchDebateWithTask(
                TRACE_ID,
                CONVERSATION_ID,
                USER_ID,
                state,
                selectedModel,
                task,
                lease
        );
    }

    @Test
    void inlineEvidenceFailureMarksTheSubmissionTaskFailed() {
        ResearchTask task = task(99L, ResearchTask.Status.PENDING);
        ResearchTaskLeaseService.Lease lease = new ResearchTaskLeaseService.Lease(
                SUBMISSION_KEY, "lease-99", ResearchTaskLeaseService.Backend.PROCESS);
        when(researchTaskService.countActiveTasks(USER_ID)).thenReturn(0);
        when(researchTaskService.buildSubmissionKey(USER_ID, TICKER, QUERY, CONVERSATION_ID))
                .thenReturn(SUBMISSION_KEY);
        when(researchTaskService.buildSubmissionPayload(TICKER, QUERY, TRACE_ID, CONVERSATION_ID))
                .thenReturn(PAYLOAD);
        when(researchTaskService.createIfAbsent(
                SUBMISSION_KEY, USER_ID, CONVERSATION_ID, TICKER,
                ResearchTask.Stage.CREATED, PAYLOAD))
                .thenReturn(new ResearchTaskService.TaskCreation(task, true));
        doThrow(new ResearchTaskQueue.QueueUnavailableException("Redis unavailable", new RuntimeException("down")))
                .when(researchTaskQueue).enqueue(99L);
        when(researchTaskService.tryAcquire(task)).thenReturn(Optional.of(lease));
        when(researchTaskService.startAttempt(task, lease.token(), ResearchTask.Stage.DATA_PREFETCH))
                .thenAnswer(invocation -> {
                    task.setStatus(ResearchTask.Status.RUNNING);
                    task.setLeaseToken(lease.token());
                    return task;
                });
        when(researchTaskService.renewLease(lease)).thenReturn(true);
        when(researchTaskService.heartbeatForOwner(task, lease.token())).thenReturn(true);
        when(deepEvidenceCollector.collect(TICKER, QUERY, TRACE_ID, CONVERSATION_ID))
                .thenThrow(new IllegalStateException("evidence down"));
        when(researchTaskService.markFailedForOwner(task, lease.token(), "evidence down"))
                .thenAnswer(invocation -> {
                    task.setStatus(ResearchTask.Status.FAILED);
                    return true;
                });
        when(reportRenderer.buildResearchFailedAnswer()).thenReturn("research failed");
        when(deepResearchPipeline.taskOutcome(task)).thenReturn("FAILED");

        ToolPrefetchService.PreparedToolContext result = prefetch();

        assertThat(result.submittedTaskId()).isNull();
        assertThat(result.directAnswer()).isEqualTo("research failed");
        assertThat(result.taskOutcome()).isEqualTo("FAILED");
        verify(researchTaskService).markFailedForOwner(task, lease.token(), "evidence down");
        verify(researchTaskService).release(lease);
    }

    @Test
    void inlineLeaseLostAfterAcquireDoesNotStartDatabaseAttemptOrEmitError() {
        ResearchTask task = task(103L, ResearchTask.Status.PENDING);
        ResearchTaskLeaseService.Lease lease = inlineLease(103L);
        stubInlineSubmissionBeforeStart(task, lease);
        when(researchTaskService.renewLease(lease)).thenReturn(false);

        ToolPrefetchService.PreparedToolContext result = prefetch();

        assertThat(result.submittedTaskId()).isEqualTo(103L);
        assertThat(result.directAnswer()).contains("新 owner");
        verify(researchTaskService).renewLease(lease);
        verify(researchTaskService, never()).startAttempt(
                task, lease.token(), ResearchTask.Stage.DATA_PREFETCH);
        verify(researchTaskService, never()).heartbeatForOwner(task, lease.token());
        verify(researchTaskService, never())
                .markSucceededForOwner(any(), anyString(), any(), any());
        verify(researchTaskService, never())
                .markFailedForOwner(any(), anyString(), anyString());
        verify(deepEvidenceCollector, never()).collect(any(), any(), any(), any());
        verify(chatStreamEmitter, never())
                .emit(eq(TRACE_ID), eq(CONVERSATION_ID), eq("error"), anyString());
        verify(researchTaskService).release(lease);
    }

    @Test
    void inlineOwnershipLossSwitchesToObservationWithoutFailureOrErrorEvent() throws Exception {
        ResearchTask task = task(100L, ResearchTask.Status.PENDING);
        ResearchTaskLeaseService.Lease lease = new ResearchTaskLeaseService.Lease(
                SUBMISSION_KEY, "lease-100", ResearchTaskLeaseService.Backend.PROCESS);
        AnalysisState state = AnalysisState.builder()
                .query(QUERY)
                .primaryTicker(TICKER)
                .build();
        DeepEvidenceCollector.EvidenceCollection evidence =
                new DeepEvidenceCollector.EvidenceCollection(
                        "## evidence",
                        state,
                        true,
                        true,
                        true,
                        true,
                        false,
                        EvidenceLedger.empty(),
                        new HarnessDecision(HarnessOutcome.PASS, List.of(), List.of())
                );
        when(researchTaskService.countActiveTasks(USER_ID)).thenReturn(0);
        when(researchTaskService.buildSubmissionKey(USER_ID, TICKER, QUERY, CONVERSATION_ID))
                .thenReturn(SUBMISSION_KEY);
        when(researchTaskService.buildSubmissionPayload(TICKER, QUERY, TRACE_ID, CONVERSATION_ID))
                .thenReturn(PAYLOAD);
        when(researchTaskService.createIfAbsent(
                SUBMISSION_KEY, USER_ID, CONVERSATION_ID, TICKER,
                ResearchTask.Stage.CREATED, PAYLOAD))
                .thenReturn(new ResearchTaskService.TaskCreation(task, true));
        doThrow(new ResearchTaskQueue.QueueUnavailableException(
                "Redis unavailable", new RuntimeException("down")))
                .when(researchTaskQueue).enqueue(100L);
        when(researchTaskService.tryAcquire(task)).thenReturn(Optional.of(lease));
        when(researchTaskService.startAttempt(task, lease.token(), ResearchTask.Stage.DATA_PREFETCH))
                .thenAnswer(invocation -> {
                    task.setStatus(ResearchTask.Status.RUNNING);
                    task.setLeaseToken(lease.token());
                    return task;
                });
        when(researchTaskService.renewLease(lease)).thenReturn(true);
        when(researchTaskService.heartbeatForOwner(task, lease.token())).thenReturn(true);
        when(deepEvidenceCollector.collect(TICKER, QUERY, TRACE_ID, CONVERSATION_ID))
                .thenReturn(evidence);
        when(investmentReportVersionService.findReusableReport(
                USER_ID, CONVERSATION_ID, state)).thenReturn(Optional.empty());
        when(deepResearchPipeline.runResearchDebateWithTask(
                TRACE_ID,
                CONVERSATION_ID,
                USER_ID,
                state,
                selectedModel,
                task,
                lease
        )).thenThrow(new DeepResearchPipeline.OwnershipLostException("lease moved"));
        when(reportRenderer.buildTaskAcceptedAnswer(task))
                .thenReturn("任务 #100 已由新 owner 继续执行");

        ToolPrefetchService.PreparedToolContext result = prefetch();

        assertThat(result.submittedTaskId()).isEqualTo(100L);
        assertThat(result.directAnswer()).contains("新 owner");
        verify(researchTaskService, never())
                .markFailedForOwner(eq(task), eq(lease.token()), anyString());
        verify(chatStreamEmitter, never())
                .emit(eq(TRACE_ID), eq(CONVERSATION_ID), eq("error"), anyString());
        verify(researchTaskService).release(lease);
    }

    @Test
    void inlineRecoveryOwnershipLossBeforePlannedCheckpointHasNoOldOwnerSideEffects() {
        ResearchTask task = task(101L, ResearchTask.Status.PENDING);
        ResearchTaskLeaseService.Lease lease = inlineLease(101L);
        stubInlineSubmission(task, lease);
        AnalysisState state = AnalysisState.builder()
                .query(QUERY)
                .primaryTicker(TICKER)
                .build();
        EvidenceLedger ledger = EvidenceLedger.empty();
        state.setEvidenceLedger(ledger);
        DeepEvidenceCollector.EvidenceCollection pending =
                new DeepEvidenceCollector.EvidenceCollection(
                        "## pending evidence",
                        state,
                        false,
                        true,
                        true,
                        false,
                        false,
                        ledger,
                        new HarnessDecision(
                                HarnessOutcome.RECOVER,
                                List.of(),
                                List.of(RecoveryAction.RETRY_MARKET))
                );
        when(deepEvidenceCollector.collect(TICKER, QUERY, TRACE_ID, CONVERSATION_ID))
                .thenReturn(pending);
        when(researchTaskService.heartbeatForOwner(task, lease.token()))
                .thenReturn(true, false);

        ToolPrefetchService.PreparedToolContext result = prefetch();

        assertThat(result.submittedTaskId()).isEqualTo(101L);
        verify(researchTaskCheckpointService, never())
                .saveHarnessSnapshot(any(), anyString(), any(AnalysisState.class));
        verify(deepEvidenceCollector, never())
                .recover(any(), any(), anyString(), any());
        verify(researchTaskService, never())
                .markSucceededForOwner(any(), anyString(), any(), any());
        verify(researchTaskService, never())
                .markFailedForOwner(any(), anyString(), anyString());
        verify(chatStreamEmitter, never())
                .emit(eq(TRACE_ID), eq(CONVERSATION_ID), eq("error"), anyString());
    }

    @Test
    void inlineNonPassOwnershipLossBeforeEarlySuccessDoesNotCompleteOrEmitError() {
        ResearchTask task = task(102L, ResearchTask.Status.PENDING);
        ResearchTaskLeaseService.Lease lease = inlineLease(102L);
        stubInlineSubmission(task, lease);
        AnalysisState state = AnalysisState.builder()
                .query(QUERY)
                .primaryTicker(TICKER)
                .build();
        EvidenceLedger ledger = EvidenceLedger.empty();
        state.setEvidenceLedger(ledger);
        DeepEvidenceCollector.EvidenceCollection blocked =
                new DeepEvidenceCollector.EvidenceCollection(
                        "## blocked evidence",
                        state,
                        false,
                        false,
                        false,
                        false,
                        false,
                        ledger,
                        new HarnessDecision(HarnessOutcome.BLOCK, List.of(), List.of())
                );
        when(deepEvidenceCollector.collect(TICKER, QUERY, TRACE_ID, CONVERSATION_ID))
                .thenReturn(blocked);
        when(researchTaskService.heartbeatForOwner(task, lease.token()))
                .thenReturn(true, false);
        when(reportRenderer.buildInsufficientEvidenceReport(TICKER, false, false, false))
                .thenReturn("insufficient");

        ToolPrefetchService.PreparedToolContext result = prefetch();

        assertThat(result.submittedTaskId()).isEqualTo(102L);
        verify(researchTaskCheckpointService, never())
                .saveHarnessSnapshot(any(), anyString(), any(AnalysisState.class));
        verify(researchTaskService, never())
                .markSucceededForOwner(any(), anyString(), any(), any());
        verify(researchTaskService, never())
                .markFailedForOwner(any(), anyString(), anyString());
        verify(chatStreamEmitter, never())
                .emit(eq(TRACE_ID), eq(CONVERSATION_ID), eq("error"), anyString());
    }

    @Test
    void inlineCollectorFailureAfterHeartbeatOwnershipLossOnlyObservesTask() {
        ResearchTask task = task(103L, ResearchTask.Status.PENDING);
        ResearchTaskLeaseService.Lease lease = inlineLease(103L);
        stubInlineSubmission(task, lease);
        ScheduledFuture<?> heartbeat = org.mockito.Mockito.mock(ScheduledFuture.class);
        AtomicReference<Runnable> heartbeatAction = new AtomicReference<>();
        when(researchHeartbeatScheduler.scheduleAtFixedRate(
                any(Runnable.class), any(Instant.class), any(Duration.class)))
                .thenAnswer(invocation -> {
                    heartbeatAction.set(invocation.getArgument(0));
                    return heartbeat;
                });
        when(researchTaskService.renewLease(lease)).thenReturn(true, true, false);
        when(deepEvidenceCollector.collect(TICKER, QUERY, TRACE_ID, CONVERSATION_ID))
                .thenAnswer(invocation -> {
                    assertThat(heartbeatAction.get()).isNotNull();
                    heartbeatAction.get().run();
                    throw new IllegalStateException("collector crashed");
                });
        lenient().when(researchTaskService.markFailedForOwner(
                task, lease.token(), "collector crashed")).thenReturn(true);

        ToolPrefetchService.PreparedToolContext result = prefetch();

        assertThat(result.submittedTaskId()).isEqualTo(103L);
        verify(researchTaskService, never())
                .markFailedForOwner(any(), anyString(), anyString());
        verify(researchTaskService, never())
                .markSucceededForOwner(any(), anyString(), any(), any());
        verify(chatStreamEmitter, never())
                .emit(eq(TRACE_ID), eq(CONVERSATION_ID), eq("error"), anyString());
        verify(researchTaskService).release(lease);
    }

    @Test
    void exhaustedNewsSkillDoesNotInvokeTheSameLocalToolAgain() {
        configureOrdinary();
        SkillResolver resolver = mock(SkillResolver.class);
        CapabilityGateway gateway = mock(CapabilityGateway.class);
        SkillExecutionService skills = new SkillExecutionService(
                resolver, gateway, chatStreamEmitter, mock(SkillExecutionObserver.class));
        ReflectionTestUtils.setField(service, "skillExecutionService", skills);
        ReflectionTestUtils.setField(service, "toolPrefetchMaxSearchResults", 5);
        var skill = new SkillRegistry(mock(SkillValidator.class)).find("latest-news-mcp").orElseThrow();
        ExecutionPlan plan = new ExecutionPlan(PlanRoute.NEWS, "news", "", List.of(PlanAction.SEARCH_NEWS),
                "", ModelTier.STANDARD);
        when(resolver.resolve(plan)).thenReturn(Optional.of(skill));
        when(gateway.invoke(eq("mcp.news.search"), anyMap(), any()))
                .thenThrow(new CapabilityException(CapabilityException.Reason.UNAVAILABLE, "MCP down"));
        when(newsTools.searchNews(QUERY, 5)).thenReturn("{\"error\":true}");
        when(gateway.invoke(eq("local.news.searchNews"), anyMap(), any())).thenAnswer(call -> {
            new LocalNewsSearchCapabilityAdapter(newsTools).invoke(call.getArgument(1), call.getArgument(2));
            throw new CapabilityException(CapabilityException.Reason.FAILED, "local down");
        });

        var result = service.prefetch(plan, QUERY, TRACE_ID, CONVERSATION_ID, USER_ID, selectedModel);

        verify(newsTools, times(1)).searchNews(QUERY, 5);
        assertThat(result.taskOutcome()).isEqualTo("FAILED");
        assertThat(result.hasDirectAnswer()).isTrue();
    }

    @Test
    void newsWithoutSelectedSkillStillUsesTheLocalTool() {
        configureOrdinary();
        ExecutionPlan plan = new ExecutionPlan(PlanRoute.NEWS, "news", "", List.of(PlanAction.SEARCH_NEWS),
                "", ModelTier.STANDARD);
        when(skillExecutionService.executePrefetch(eq(plan), eq(QUERY), any(Integer.class),
                eq(TRACE_ID), eq(CONVERSATION_ID), eq(USER_ID)))
                .thenReturn(SkillExecutionService.ExecutionResult.empty());
        when(newsTools.searchNews(QUERY, 0)).thenReturn("{\"provider\":\"tavily\",\"results\":[{\"link\":\"https://example.com/news\",\"title\":\"news evidence\"}]}");

        var result = service.prefetch(plan, QUERY, TRACE_ID, CONVERSATION_ID, USER_ID, selectedModel);

        verify(newsTools).searchNews(QUERY, 0);
        assertThat(result.context()).contains("news evidence");
    }

    @Test
    void ordinaryRoutesRunOnlyTheirPlannedAnalystAfterEvidence() {
        configureOrdinary();
        doAnswer(call -> {
            call.<Runnable>getArgument(0).run();
            return null;
        }).when(agentTaskExecutor).execute(any(Runnable.class));
        when(fundamentalsAgent.analyze(eq(QUERY), anyString())).thenReturn("fundamentals synthesis");
        when(marketAgent.analyze(eq(QUERY), anyString())).thenReturn("market synthesis");
        when(newsAgent.analyze(eq(QUERY), anyString())).thenReturn("news synthesis");
        for (PlanAction action : List.of(PlanAction.FUNDAMENTALS_AGENT, PlanAction.MARKET_AGENT, PlanAction.NEWS_AGENT)) {
            PlanAction tool = action == PlanAction.NEWS_AGENT ? PlanAction.WEB_SEARCH : PlanAction.GET_FINANCIAL_REPORTS;
            lenient().when(newsTools.webSearch(QUERY, 0)).thenReturn("{\"provider\":\"tavily\",\"results\":[{\"link\":\"https://example.com/news\"}]}");
            lenient().when(fundamentalsTools.getFinancialReports(TICKER, "annual", 5)).thenReturn("{\"provider\":\"sec\",\"period\":\"annual\",\"revenue\":123}");
            ExecutionPlan plan = new ExecutionPlan(PlanRoute.valueOf(action.name().replace("_AGENT", "")),
                    "analyst", "", List.of(tool, action), "", ModelTier.STANDARD);
            var result = service.prefetch(plan, QUERY, TRACE_ID, CONVERSATION_ID, USER_ID, selectedModel);
            assertThat(result.context()).contains("## " + action.label(), "synthesis");
        }
        verify(agentTaskExecutor, times(3)).execute(any(Runnable.class));
        verify(fundamentalsAgent).analyze(eq(QUERY), anyString());
        verify(marketAgent).analyze(eq(QUERY), anyString());
        verify(newsAgent).analyze(eq(QUERY), anyString());
    }

    @Test
    void ordinaryEvidenceRejectsWrongTargetsQuarterSubstitutionAndBudgetLoss() {
        org.mockito.Mockito.reset(tickerResolutionService);
        configureOrdinary();
        var mapper = (EvidenceEnvelopeMapper) ReflectionTestUtils.getField(service, "evidenceEnvelopeMapper");
        var read = com.stocksage.agent.ReadRequest.parse(PlanRoute.FUNDAMENTALS, "最近两个季度财报", java.util.Map.of());
        var evidence = new OrdinaryEvidence(TICKER, read, new ObjectMapper(), mapper);
        evidence.add("getFinancialReports", EvidenceDimension.FUNDAMENTALS,
                "{\"provider\":\"sec\",\"symbol\":\"" + TICKER + "\",\"period\":\"annual\",\"revenue\":123}");
        assertThat(evidence.citationIds()).isEmpty();
        assertThat(evidence.context()).contains("REPORT_PERIOD_MISMATCH");

        evidence = new OrdinaryEvidence(TICKER, read, new ObjectMapper(), mapper);
        evidence.add("webSearch", EvidenceDimension.NEWS,
                "{\"provider\":\"tavily\",\"symbol\":\"MSFT\",\"results\":[{\"link\":\"https://example.com/news\"}]}");
        assertThat(evidence.citationIds()).isEmpty();
        assertThat(new com.stocksage.harness.OrdinaryCompletionPolicy().afterEvidence(
                new RunContext("FUNDAMENTALS", java.util.Map.of()), evidence.ledger()).outcome()).isEqualTo(HarnessOutcome.BLOCK);

        evidence = new OrdinaryEvidence(TICKER, read, new ObjectMapper(), mapper);
        evidence.add("webSearch", EvidenceDimension.NEWS,
                "{\"provider\":\"tavily\",\"results\":[{\"link\":\"https://example.com/news\",\"content\":\"" + "a".repeat(3000) + "\"}]}");
        assertThat(evidence.allIncluded()).isFalse();
        assertThat(evidence.context().length()).isLessThan(3000);

        evidence = new OrdinaryEvidence(TICKER, read, new ObjectMapper(), mapper);
        evidence.add("searchNews", EvidenceDimension.NEWS, "{\"provider\":\"tavily\",\"results\":[]}");
        assertThat(new com.stocksage.harness.OrdinaryCompletionPolicy().afterEvidence(
                new RunContext("NEWS", java.util.Map.of()), evidence.ledger()).outcome()).isEqualTo(HarnessOutcome.PASS);
        evidence.add("webSearch", EvidenceDimension.NEWS, "{\"error\":true}");
        assertThat(new com.stocksage.harness.OrdinaryCompletionPolicy().afterEvidence(
                new RunContext("NEWS", java.util.Map.of()), evidence.ledger()).outcome()).isEqualTo(HarnessOutcome.DEGRADE);
        evidence = new OrdinaryEvidence(TICKER, read, new ObjectMapper(), mapper);
        evidence.add("webSearch", EvidenceDimension.NEWS, "{\"provider\":\"tavily\"}");
        assertThat(evidence.hasUsefulResult()).isFalse();
        evidence.add(new com.stocksage.capability.CapabilityResult("local.news.searchNews", "local",
                com.stocksage.capability.CapabilityResult.Status.TRUNCATED,
                "{\"results\":[]}", 14, 1));
        assertThat(evidence.hasUsefulResult()).isFalse();
    }

    private void configureOrdinary() {
        ReflectionTestUtils.setField(service, "evidenceEnvelopeMapper",
                new EvidenceEnvelopeMapper(tickerResolutionService));
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "researchHarness", new com.stocksage.harness.ResearchHarness(mock(com.stocksage.harness.HarnessObserver.class)));
        ReflectionTestUtils.setField(service, "ordinaryCompletionPolicy", new com.stocksage.harness.OrdinaryCompletionPolicy());
        ReflectionTestUtils.setField(service, "traceService", mock(com.stocksage.trace.TraceService.class));
        ReflectionTestUtils.setField(service, "agentPrefetchTimeoutSeconds", 1L);
        lenient().when(tickerResolutionService.normalizeStructuredTicker(anyString())).thenAnswer(call -> call.getArgument(0));
    }

    @Test void ordinaryBarsUseRequestedParametersAndRejectPeriodSubstitution() {
        configureOrdinary();
        when(tickerResolutionService.isLikelySecTicker(TICKER)).thenReturn(true);
        var plan = new ExecutionPlan(PlanRoute.MARKET, "bars", "", List.of(PlanAction.GET_STOCK_KLINE),
                "", ModelTier.STANDARD, null, "AAPL最近一周的小时线");
        when(marketTools.getIbkrHistoricalBars(TICKER, "1w", "1h")).thenReturn("""
                {"symbol":"AAPL","provider":"IBKR_WEB_API","period":"1w","bar":"1h",
                "data":[{"t":1788768000000,"o":100,"h":102,"l":99,"c":101}]}
                """);
        var result = service.prefetch(plan, QUERY, TRACE_ID, CONVERSATION_ID, USER_ID, selectedModel);
        assertThat(result.taskOutcome()).isEqualTo("COMPLETED");
        assertThat(result.outcomeForAnswer("价格101 [E1]")).isEqualTo("COMPLETED");
        assertThat(result.outcomeForAnswer("价格101 [E99]")).isEqualTo("DEGRADED");
        assertThat(result.outcomeForAnswer("价格101")).isEqualTo("DEGRADED");
        verifyNoInteractions(marketAgent, fundamentalsAgent, newsAgent);
        when(marketTools.getIbkrHistoricalBars(TICKER, "1w", "1h")).thenReturn("""
                {"symbol":"AAPL","provider":"IBKR_WEB_API","period":"3m","bar":"1d",
                "data":[{"t":1788768000000,"o":100,"h":102,"l":99,"c":101}]}
                """);
        var mismatch = service.prefetch(plan, QUERY, TRACE_ID, CONVERSATION_ID, USER_ID, selectedModel);
        assertThat(mismatch.taskOutcome()).isEqualTo("FAILED");
        assertThat(mismatch.context()).contains("PERIOD_MISMATCH");
        assertThat(mismatch.context()).doesNotContain("\"c\":101");
        var quarterly = new ExecutionPlan(PlanRoute.FUNDAMENTALS, "reports", "", List.of(PlanAction.GET_FINANCIAL_REPORTS),
                "", ModelTier.STANDARD, null, "AAPL最近两个季度财报");
        when(fundamentalsTools.getFinancialReports(TICKER, "quarterly", 1)).thenReturn("""
                {"provider":"sec","symbol":"AAPL","period":"quarterly","reports":[{"quarter":1,"revenue":1},{"quarter":2,"revenue":2}]}
                """);
        assertThat(service.prefetch(quarterly, QUERY, TRACE_ID, CONVERSATION_ID, USER_ID, selectedModel).taskOutcome())
                .isEqualTo("COMPLETED");
        verify(fundamentalsTools).getFinancialReports(TICKER, "quarterly", 1);
    }

    @Test void unsupportedIntradayStopsBeforeToolCalls() {
        configureOrdinary();
        var plan = new ExecutionPlan(PlanRoute.MARKET, "bars", "", List.of(PlanAction.GET_STOCK_KLINE),
                "", ModelTier.STANDARD, null, "最近一周的小时线");
        var result = service.prefetch(plan, QUERY, TRACE_ID, CONVERSATION_ID, USER_ID, selectedModel);
        assertThat(result.taskOutcome()).isEqualTo("BLOCKED");
        verifyNoInteractions(marketTools, fundamentalsTools, newsTools);
    }

    private ToolPrefetchService.PreparedToolContext prefetch() {
        return service.prefetch(
                deepPlan,
                QUERY,
                TRACE_ID,
                CONVERSATION_ID,
                USER_ID,
                selectedModel
        );
    }

    private ResearchTask task(Long id, ResearchTask.Status status) {
        ResearchTask task = new ResearchTask();
        task.setId(id);
        task.setUserId(USER_ID);
        task.setConversationId(CONVERSATION_ID);
        task.setIdempotencyKey(SUBMISSION_KEY);
        task.setTicker(TICKER);
        task.setStatus(status);
        task.setStage(ResearchTask.Stage.CREATED);
        task.setPayloadJson("");
        return task;
    }

    private ResearchTaskLeaseService.Lease inlineLease(Long taskId) {
        return new ResearchTaskLeaseService.Lease(
                SUBMISSION_KEY,
                "lease-" + taskId,
                ResearchTaskLeaseService.Backend.PROCESS
        );
    }

    private void stubInlineSubmission(
            ResearchTask task,
            ResearchTaskLeaseService.Lease lease
    ) {
        stubInlineSubmissionBeforeStart(task, lease);
        when(researchTaskService.startAttempt(
                task, lease.token(), ResearchTask.Stage.DATA_PREFETCH))
                .thenAnswer(invocation -> {
                    task.setStatus(ResearchTask.Status.RUNNING);
                    task.setLeaseToken(lease.token());
                    return task;
                });
        when(researchTaskService.renewLease(lease)).thenReturn(true);
        when(researchTaskService.heartbeatForOwner(task, lease.token())).thenReturn(true);
    }

    private void stubInlineSubmissionBeforeStart(
            ResearchTask task,
            ResearchTaskLeaseService.Lease lease
    ) {
        when(researchTaskService.countActiveTasks(USER_ID)).thenReturn(0);
        when(researchTaskService.buildSubmissionKey(USER_ID, TICKER, QUERY, CONVERSATION_ID))
                .thenReturn(SUBMISSION_KEY);
        when(researchTaskService.buildSubmissionPayload(
                TICKER, QUERY, TRACE_ID, CONVERSATION_ID)).thenReturn(PAYLOAD);
        when(researchTaskService.createIfAbsent(
                SUBMISSION_KEY, USER_ID, CONVERSATION_ID, TICKER,
                ResearchTask.Stage.CREATED, PAYLOAD))
                .thenReturn(new ResearchTaskService.TaskCreation(task, true));
        doThrow(new ResearchTaskQueue.QueueUnavailableException(
                "Redis unavailable", new RuntimeException("down")))
                .when(researchTaskQueue).enqueue(task.getId());
        when(researchTaskService.tryAcquire(task)).thenReturn(Optional.of(lease));
        when(reportRenderer.buildTaskAcceptedAnswer(task))
                .thenReturn("任务 #" + task.getId() + " 已由新 owner 继续执行");
    }

    private void verifyNoSubmissionWorkWasRun() {
        verifyNoInteractions(
                deepEvidenceCollector,
                deepResearchPipeline,
                fundamentalsAgent,
                marketAgent,
                newsAgent,
                investmentReportVersionService
        );
    }
}
