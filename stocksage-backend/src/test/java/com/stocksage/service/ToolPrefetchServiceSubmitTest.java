package com.stocksage.service;

import com.stocksage.knowledge.KnowledgeIngestionService;
import com.stocksage.knowledge.SearchResultIngestionService;

import com.stocksage.research.DeepResearchPipeline;
import com.stocksage.research.ResearchSubmissionService;
import com.stocksage.research.DeepEvidenceCollector;
import com.stocksage.research.DeepEvidenceReplanService;
import com.stocksage.research.ResearchTaskService;
import com.stocksage.research.ResearchTaskObservationService;
import com.stocksage.research.ResearchTaskLeaseService;
import com.stocksage.research.ResearchTaskCheckpointService;
import com.stocksage.research.ResearchTaskPublicationTransaction;
import com.stocksage.research.ResearchTaskQueue;
import com.stocksage.research.InvestmentReportVersionService;
import com.stocksage.research.ReportMarkdownRenderer;

import com.stocksage.evidence.adapter.EvidenceEnvelopeMapper;

import com.stocksage.conversation.ConversationMessageService;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.agent.Coordinator;
import com.stocksage.agent.ExecutionPlan;
import com.stocksage.agent.RoutingDecisionMetadata;
import com.stocksage.agent.intent.TimeSensitivity;
import com.stocksage.agent.FundamentalsAgent;
import com.stocksage.agent.MarketAgent;
import com.stocksage.agent.ModelTier;
import com.stocksage.agent.NewsAgent;
import com.stocksage.agent.PlanAction;
import com.stocksage.agent.PlanRoute;
import com.stocksage.research.ResearchDebateService;
import com.stocksage.capability.CapabilityException;
import com.stocksage.capability.CapabilityGateway;
import com.stocksage.capability.LocalNewsSearchCapabilityAdapter;
import com.stocksage.harness.DeepResearchCompletionPolicy;
import com.stocksage.evidence.EvidenceLedger;
import com.stocksage.harness.HarnessModels.HarnessDecision;
import com.stocksage.evidence.EvidenceModels.EvidenceDimension;
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
import com.stocksage.trace.TraceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
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
    private DeepResearchPipeline deepResearchPipeline;
    @Mock
    private InvestmentReportVersionService investmentReportVersionService;
    @Mock
    private ResearchTaskService researchTaskService;
    @Mock
    private ResearchTaskObservationService researchTaskObservationService;
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
    private AsyncTaskExecutor backgroundTaskExecutor;
    @Mock
    private TaskScheduler researchHeartbeatScheduler;
    @Mock
    private TraceService traceService;

    private ToolPrefetchService service;
    private SearchResultIngestionService searchResultIngestionService;

    private final ExecutionPlan deepPlan = new ExecutionPlan(
            PlanRoute.DEEP,
            "DEEP_RESEARCH",
            "research",
            List.of(PlanAction.RESEARCH_MANAGER),
            "",
            ModelTier.STRONG,
            new RoutingDecisionMetadata(null, "DEEP", PlanRoute.DEEP, "", "", 1.0,
                    List.of(), 0, "", 0, "", "", "REAL_TIME", "",
                    java.util.Map.of(), java.util.Map.of(), false, List.of())
    );
    private final Coordinator.SelectedModel selectedModel =
            new Coordinator.SelectedModel(ModelTier.STRONG, "test-model");

    @BeforeEach
    void setUp() {
        lenient().when(fundamentalsAgent.selectMethod(anyString(), anySet(), anySet(), anyString(), any(), anyLong()))
                .thenReturn(new com.stocksage.evolution.FundamentalsMethodRegistry.Selection(
                        com.stocksage.evolution.AgentPolicyBundle.baseline(), java.util.Map.of("reason", "NOT_AUTHORIZED")));
        deepResearchPipeline = spy(new DeepResearchPipeline(
                researchTaskService,
                mock(ResearchDebateService.class),
                investmentReportVersionService,
                mock(OfflineDemoSampleService.class),
                objectMapper,
                researchHeartbeatScheduler,
                researchTaskCheckpointService,
                deepEvidenceCollector,
                deepEvidenceReplanService,
                reportRenderer,
                mock(ConversationMessageService.class),
                mock(ResearchTaskPublicationTransaction.class),
                chatStreamEmitter,
                traceService,
                mock(com.stocksage.research.ResearchRunManifestService.class),
                mock(com.stocksage.research.ResearchEvidenceSnapshotService.class, invocation -> "snapshot-test")));
        searchResultIngestionService = new SearchResultIngestionService(knowledgeIngestionService, new ObjectMapper(), backgroundTaskExecutor);
        var researchSubmissionService = new ResearchSubmissionService(researchTaskService,
                researchTaskObservationService, researchTaskQueue, reportRenderer, deepResearchPipeline,
                marketTools, chatStreamEmitter);
        var registry = mock(com.stocksage.capability.CapabilityRegistry.class);
        for (var adapter : List.of(new LocalNewsSearchCapabilityAdapter(newsTools),
                new com.stocksage.capability.LocalWebSearchCapabilityAdapter(newsTools))) {
            var descriptor = new com.stocksage.capability.CapabilityDescriptor(adapter.capabilityId(),
                    com.stocksage.capability.CapabilityDescriptor.ProviderType.LOCAL, "local", adapter.capabilityId(),
                    com.stocksage.capability.CapabilityDescriptor.RiskLevel.READ_ONLY, 8000, 262144, true);
            lenient().when(registry.require(adapter.capabilityId())).thenReturn(
                    new com.stocksage.capability.CapabilityRegistry.RegisteredCapability(descriptor, adapter));
        }
        var ordinaryGateway = new CapabilityGateway(registry, new com.stocksage.capability.CapabilityPolicy(),
                mock(com.stocksage.capability.CapabilityInvocationObserver.class),
                new org.springframework.core.task.support.TaskExecutorAdapter(Runnable::run), new ObjectMapper());
        service = new ToolPrefetchService(
                marketTools, ordinaryGateway, fundamentalsTools,
                fundamentalsAgent, marketAgent, newsAgent,
                tickerResolutionService,
                new EvidenceEnvelopeMapper(tickerResolutionService), researchSubmissionService, searchResultIngestionService,
                skillExecutionService, chatStreamEmitter, objectMapper, agentTaskExecutor,
                mock(com.stocksage.harness.ResearchHarness.class),
                mock(com.stocksage.harness.OrdinaryCompletionPolicy.class), traceService);
        ReflectionTestUtils.setField(researchSubmissionService, "userMaxActive", 3);
        ReflectionTestUtils.setField(service, "toolPrefetchMaxSearchResults", 5);
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
        when(researchTaskService.buildSubmissionPayload(TICKER, QUERY, TRACE_ID, CONVERSATION_ID, TimeSensitivity.REAL_TIME))
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
        verify(researchTaskService).buildSubmissionPayload(
                TICKER, QUERY, TRACE_ID, CONVERSATION_ID, TimeSensitivity.REAL_TIME);
        verifyNoInteractions(deepResearchPipeline);
        verifyNoSubmissionWorkWasRun();
    }

    @Test
    void duplicateCompletedRunReadsItsOriginalResultWithoutQuotaOrReexecution() {
        ResearchTask completed = task(77L, ResearchTask.Status.SUCCEEDED);
        completed.setResultKind(ResearchTask.ResultKind.FULL_REPORT);
        when(researchTaskService.buildRunSubmissionKey(USER_ID, "submission-1")).thenReturn("run-key");
        when(researchTaskService.findSubmission("run-key")).thenReturn(java.util.Optional.of(completed));
        when(researchTaskObservationService.terminalContent(completed, completed.getUserId()))
                .thenReturn("original report");

        var result = service.prefetch(deepPlan, QUERY, TRACE_ID, CONVERSATION_ID, USER_ID,
                selectedModel, "submission-1");

        assertThat(result.directAnswer()).isEqualTo("original report");
        assertThat(result.submittedTaskId()).isNull();
        assertThat(result.eventTraceId()).isEqualTo(TRACE_ID);
        assertThat(result.taskOutcome()).isEqualTo("COMPLETED");
        verify(researchTaskService, never()).countActiveTasks(anyString());
        verify(researchTaskService, never()).createIfAbsent(anyString(), anyString(), any(), anyString(), any(), anyString());
        verifyNoInteractions(researchTaskQueue);
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
        verifyNoInteractions(deepResearchPipeline);
        verifyNoSubmissionWorkWasRun();
    }

    @Test
    void duplicateActiveSubmissionObservesExistingTaskWithoutReenqueue() {
        ResearchTask existing = task(77L, ResearchTask.Status.RUNNING);
        when(researchTaskService.countActiveTasks(USER_ID)).thenReturn(1);
        when(researchTaskService.buildSubmissionKey(USER_ID, TICKER, QUERY, CONVERSATION_ID)).thenReturn(SUBMISSION_KEY);
        when(researchTaskService.buildSubmissionPayload(TICKER, QUERY, TRACE_ID, CONVERSATION_ID, TimeSensitivity.REAL_TIME))
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
        when(researchTaskService.buildSubmissionPayload(TICKER, QUERY, TRACE_ID, CONVERSATION_ID, TimeSensitivity.REAL_TIME))
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
        when(deepEvidenceCollector.collect(TICKER, QUERY, TRACE_ID, CONVERSATION_ID, TimeSensitivity.REAL_TIME, USER_ID))
                .thenAnswer(invocation -> {
                    heartbeatAction.get().run();
                    return evidence;
                });
        doReturn("inline report").when(deepResearchPipeline).runResearchDebateWithTask(
                TRACE_ID,
                CONVERSATION_ID,
                USER_ID,
                state,
                selectedModel,
                task,
                lease
        );

        ToolPrefetchService.PreparedToolContext result = prefetch();

        assertThat(result.context()).contains(identity);
        verify(marketTools).resolveStock(TICKER);
        verify(deepEvidenceCollector).collect(TICKER, QUERY, TRACE_ID, CONVERSATION_ID, TimeSensitivity.REAL_TIME, USER_ID);
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
        when(researchTaskService.buildSubmissionPayload(TICKER, QUERY, TRACE_ID, CONVERSATION_ID, TimeSensitivity.REAL_TIME))
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
        when(deepEvidenceCollector.collect(TICKER, QUERY, TRACE_ID, CONVERSATION_ID, TimeSensitivity.REAL_TIME, USER_ID))
                .thenReturn(pending);
        when(deepEvidenceCollector.recover(
                pending,
                List.of(RecoveryAction.RETRY_MARKET),
                java.util.Map.of(),
                TRACE_ID,
                CONVERSATION_ID,
                USER_ID
        )).thenReturn(recovered);
        when(investmentReportVersionService.findReusableReport(USER_ID, CONVERSATION_ID, state))
                .thenReturn(Optional.empty());
        doReturn("inline report").when(deepResearchPipeline).runResearchDebateWithTask(
                TRACE_ID,
                CONVERSATION_ID,
                USER_ID,
                state,
                selectedModel,
                task,
                lease
        );
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
        when(researchTaskService.buildSubmissionPayload(TICKER, QUERY, TRACE_ID, CONVERSATION_ID, TimeSensitivity.REAL_TIME))
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
        when(deepEvidenceCollector.collect(TICKER, QUERY, TRACE_ID, CONVERSATION_ID, TimeSensitivity.REAL_TIME, USER_ID))
                .thenThrow(new IllegalStateException("evidence down"));
        when(researchTaskService.markFailedForOwner(task, lease.token(), "evidence down"))
                .thenAnswer(invocation -> {
                    task.setStatus(ResearchTask.Status.FAILED);
                    return true;
                });
        when(reportRenderer.buildResearchFailedAnswer()).thenReturn("research failed");

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
        verify(deepEvidenceCollector, never()).collect(any(), any(), any(), any(), any(), any());
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
        when(researchTaskService.buildSubmissionPayload(TICKER, QUERY, TRACE_ID, CONVERSATION_ID, TimeSensitivity.REAL_TIME))
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
        when(deepEvidenceCollector.collect(TICKER, QUERY, TRACE_ID, CONVERSATION_ID, TimeSensitivity.REAL_TIME, USER_ID))
                .thenReturn(evidence);
        when(investmentReportVersionService.findReusableReport(
                USER_ID, CONVERSATION_ID, state)).thenReturn(Optional.empty());
        doThrow(new DeepResearchPipeline.OwnershipLostException("lease moved"))
                .when(deepResearchPipeline).runResearchDebateWithTask(
                TRACE_ID,
                CONVERSATION_ID,
                USER_ID,
                state,
                selectedModel,
                task,
                lease
        );
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
        when(deepEvidenceCollector.collect(TICKER, QUERY, TRACE_ID, CONVERSATION_ID, TimeSensitivity.REAL_TIME, USER_ID))
                .thenReturn(pending);
        when(researchTaskService.heartbeatForOwner(task, lease.token()))
                .thenReturn(true, false);

        ToolPrefetchService.PreparedToolContext result = prefetch();

        assertThat(result.submittedTaskId()).isEqualTo(101L);
        verify(researchTaskCheckpointService, never())
                .saveHarnessSnapshot(any(), anyString(), any(AnalysisState.class));
        verify(deepEvidenceCollector, never())
                .recover(any(), any(), anyMap(), anyString(), any(), eq(USER_ID));
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
        when(deepEvidenceCollector.collect(TICKER, QUERY, TRACE_ID, CONVERSATION_ID, TimeSensitivity.REAL_TIME, USER_ID))
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
        when(deepEvidenceCollector.collect(TICKER, QUERY, TRACE_ID, CONVERSATION_ID, TimeSensitivity.REAL_TIME, USER_ID))
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
    void ordinarySearchCannotBypassCapabilityPolicyOrReuseTruncatedResults() {
        configureOrdinary();
        var gateway = mock(CapabilityGateway.class);
        ReflectionTestUtils.setField(service, "capabilityGateway", gateway);
        var plan = new ExecutionPlan(PlanRoute.NEWS, "news", "", List.of(PlanAction.WEB_SEARCH),
                "", ModelTier.STANDARD);
        for (var reason : List.of(CapabilityException.Reason.DENIED, CapabilityException.Reason.UNKNOWN)) {
            var rejected = new CapabilityException(reason, "policy rejected");
            org.mockito.Mockito.doThrow(rejected).when(gateway).invoke(anyString(), anyMap(), any());
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    service.prefetch(plan, QUERY, TRACE_ID, CONVERSATION_ID, USER_ID, selectedModel))
                    .isSameAs(rejected);
        }
        org.mockito.Mockito.doAnswer(call -> {
            var context = (com.stocksage.capability.CapabilityInvocationContext) call.getArgument(2);
            assertThat(context.userId()).isEqualTo(USER_ID);
            assertThat(context.conversationId()).isEqualTo(CONVERSATION_ID);
            assertThat(context.traceId()).isEqualTo(TRACE_ID);
            assertThat(context.skillId()).isNull();
            assertThat(context.allowedCapabilities()).containsExactly("local.news.webSearch");
            return new com.stocksage.capability.CapabilityResult("local.news.webSearch", "local",
                    com.stocksage.capability.CapabilityResult.Status.TRUNCATED,
                    "{\"results\":[{\"link\":\"https://example.com\",\"title\":\"incomplete evidence\"}]}", 100, 1);
        }).when(gateway).invoke(anyString(), anyMap(), any());
        var result = service.prefetch(plan, QUERY, TRACE_ID, CONVERSATION_ID, USER_ID, selectedModel);
        assertThat(result.taskOutcome()).isEqualTo("FAILED");
        assertThat(result.sourceEvidenceContext()).doesNotContain("incomplete evidence");
        verifyNoInteractions(newsTools, backgroundTaskExecutor, knowledgeIngestionService);
    }

    @Test
    void newsWithoutSelectedSkillUsesTheRegisteredLocalCapability() {
        configureOrdinary();
        ExecutionPlan plan = new ExecutionPlan(PlanRoute.NEWS, "news", "", List.of(PlanAction.SEARCH_NEWS),
                "", ModelTier.STANDARD);
        when(skillExecutionService.executePrefetch(eq(plan), eq(QUERY), any(Integer.class),
                eq(TRACE_ID), eq(CONVERSATION_ID), eq(USER_ID)))
                .thenReturn(SkillExecutionService.ExecutionResult.empty());
        when(newsTools.searchNews(QUERY, 5)).thenReturn("{\"provider\":\"tavily\",\"results\":[{\"link\":\"https://example.com/news\",\"title\":\"news evidence\"}]}");

        var result = service.prefetch(plan, QUERY, TRACE_ID, CONVERSATION_ID, USER_ID, selectedModel);

        verify(newsTools).searchNews(QUERY, 5);
        assertThat(result.context()).contains("news evidence", "请求时间要求：UNSPECIFIED");
    }

    @Test
    void providerCapacityRejectionInsideAnalystFutureIsNotMisreportedAsEmptyModelOutput() {
        configureOrdinary();
        when(fundamentalsTools.getFinancialReports(TICKER, "annual", 5)).thenReturn(
                "{\"provider\":\"sec\",\"period\":\"annual\",\"metrics\":{\"revenue\":{\"data\":[{\"value\":123}]}}}");
        doAnswer(call -> { call.getArgument(0, Runnable.class).run(); return null; })
                .when(agentTaskExecutor).execute(any(Runnable.class));
        var capacity = new com.stocksage.exception.ResearchCapacityExceededException("provider-admission", null);
        when(fundamentalsAgent.analyzeObserved(eq(QUERY), anyString(), any(com.stocksage.evolution.AgentPolicyBundle.class)))
                .thenThrow(new IllegalStateException("wrapped", capacity));
        var plan = new ExecutionPlan(PlanRoute.FUNDAMENTALS, "analyst", "",
                List.of(PlanAction.GET_FINANCIAL_REPORTS, PlanAction.FUNDAMENTALS_AGENT), "", ModelTier.STANDARD);
        var result = service.prefetch(plan, QUERY, TRACE_ID, CONVERSATION_ID, USER_ID, selectedModel);
        assertThat(result.taskOutcome()).isEqualTo("DEGRADED");
        assertThat(result.context()).contains("在线分析容量不足", "领域分析未执行");
        assertThat(result.sourceEvidenceContext()).contains("123");
        assertThat(result.citationIds()).isNotEmpty();
        var trace = (TraceService) ReflectionTestUtils.getField(service, "traceService");
        verify(trace).addStep(eq(TRACE_ID), org.mockito.ArgumentMatchers.argThat(step ->
                "CAPACITY_REJECTED".equals(step.getAttributes().get("analystStatus"))));
    }

    @Test
    void rejectedOptionalAnalystPreservesEvidenceAndReportsCapacityDegradation() {
        configureOrdinary();
        when(fundamentalsTools.getFinancialReports(TICKER, "annual", 5)).thenReturn(
                "{\"provider\":\"sec\",\"period\":\"annual\",\"metrics\":{\"revenue\":{\"data\":[{\"value\":123}]}}}");
        doThrow(new java.util.concurrent.RejectedExecutionException("online queue full"))
                .when(agentTaskExecutor).execute(any(Runnable.class));
        var plan = new ExecutionPlan(PlanRoute.FUNDAMENTALS, "analyst", "",
                List.of(PlanAction.GET_FINANCIAL_REPORTS, PlanAction.FUNDAMENTALS_AGENT), "", ModelTier.STANDARD);
        var result = service.prefetch(plan, QUERY, TRACE_ID, CONVERSATION_ID, USER_ID, selectedModel);
        assertThat(result.taskOutcome()).isEqualTo("DEGRADED");
        assertThat(result.context()).contains("在线分析容量不足", "领域分析未执行", "123");
        assertThat(result.sourceEvidenceContext()).contains("123");
        assertThat(result.citationIds()).isNotEmpty();
        verify(fundamentalsAgent, never()).analyze(anyString(), anyString());
        verifyNoInteractions(backgroundTaskExecutor);
        var trace = (TraceService) ReflectionTestUtils.getField(service, "traceService");
        verify(trace).addStep(eq(TRACE_ID), org.mockito.ArgumentMatchers.argThat(step ->
                "CAPACITY_REJECTED".equals(step.getAttributes().get("analystStatus"))));
    }

    @Test
    void searchIngestionPreservesSourceIdentityMetadataAndRetention() throws Exception {
        org.mockito.Mockito.reset(tickerResolutionService);
        ReflectionTestUtils.setField(searchResultIngestionService, "searchIngestEnabled", true);
        ReflectionTestUtils.setField(searchResultIngestionService, "searchIngestTtlDays", 7L);
        AtomicReference<Runnable> pending = new AtomicReference<>();
        doAnswer(call -> { pending.set(call.getArgument(0)); return null; })
                .when(backgroundTaskExecutor).execute(any(Runnable.class));
        String snippet = "原始搜索摘要".repeat(30);
        String raw = new ObjectMapper().writeValueAsString(java.util.Map.of("results", List.of(
                java.util.Map.of("title", "市场新闻", "link", "https://example.com/news", "snippet", snippet))));
        searchResultIngestionService.submit("webSearch", raw, QUERY);
        verifyNoInteractions(knowledgeIngestionService);
        pending.get().run();
        verify(knowledgeIngestionService).ingestText(
                eq("chat:" + KnowledgeIngestionService.sha256(QUERY + "|https://example.com/news|市场新闻")),
                org.mockito.ArgumentMatchers.contains(snippet),
                eq(java.util.Map.of("source", "https://example.com/news", "doc_type", "chat_search_result",
                        "title", "市场新闻", "knowledge_category", "market_update", "query", QUERY,
                        "tool", "webSearch", "origin", "chat_driven")),
                eq("chat_driven"), eq(Duration.ofDays(7)), eq(true));
        verifyNoInteractions(agentTaskExecutor);
    }

    @Test
    void rejectedSearchIngestionDoesNotDiscardEvidenceOrConsumeOnlineCapacity() {
        configureOrdinary();
        ReflectionTestUtils.setField(searchResultIngestionService, "searchIngestEnabled", true);
        when(newsTools.webSearch(QUERY, 5)).thenReturn(
                "{\"provider\":\"tavily\",\"results\":[{\"link\":\"https://example.com/news\",\"snippet\":\"available source\"}]}");
        doThrow(new java.util.concurrent.RejectedExecutionException("background queue full"))
                .when(backgroundTaskExecutor).execute(any(Runnable.class));
        var plan = new ExecutionPlan(PlanRoute.NEWS, "news", "", List.of(PlanAction.WEB_SEARCH), "", ModelTier.STANDARD);
        var result = service.prefetch(plan, QUERY, TRACE_ID, CONVERSATION_ID, USER_ID, selectedModel);
        assertThat(result.sourceEvidenceContext()).contains("available source");
        assertThat(result.taskOutcome()).isNotIn("FAILED", "BLOCKED");
        verify(backgroundTaskExecutor).execute(any(Runnable.class));
        verifyNoInteractions(agentTaskExecutor, knowledgeIngestionService);
    }

    @Test
    void ordinaryRoutesRunOnlyTheirPlannedAnalystAfterEvidence() {
        configureOrdinary();
        doAnswer(call -> {
            call.<Runnable>getArgument(0).run();
            return null;
        }).when(agentTaskExecutor).execute(any(Runnable.class));
        when(fundamentalsAgent.analyzeObserved(eq(QUERY), anyString(), any(com.stocksage.evolution.AgentPolicyBundle.class)))
                .thenReturn(observed("fundamentals synthesis"));
        when(marketAgent.analyze(eq(QUERY), anyString())).thenReturn("market synthesis");
        when(newsAgent.analyze(eq(QUERY), anyString())).thenReturn("news synthesis");
        for (PlanAction action : List.of(PlanAction.FUNDAMENTALS_AGENT, PlanAction.MARKET_AGENT, PlanAction.NEWS_AGENT)) {
            PlanAction tool = action == PlanAction.NEWS_AGENT ? PlanAction.WEB_SEARCH : PlanAction.GET_FINANCIAL_REPORTS;
            lenient().when(newsTools.webSearch(QUERY, 5)).thenReturn("{\"provider\":\"tavily\",\"results\":[{\"link\":\"https://example.com/news\"}]}");
            lenient().when(fundamentalsTools.getFinancialReports(TICKER, "annual", 5)).thenReturn("{\"provider\":\"sec\",\"period\":\"annual\",\"metrics\":{\"revenue\":{\"data\":[{\"value\":123}]}}}");
            ExecutionPlan plan = new ExecutionPlan(PlanRoute.valueOf(action.name().replace("_AGENT", "")),
                    "analyst", "", List.of(tool, action), "", ModelTier.STANDARD);
            var result = service.prefetch(plan, QUERY, TRACE_ID, CONVERSATION_ID, USER_ID, selectedModel);
            assertThat(result.context()).contains("## " + action.label(), "synthesis");
            var section = result.analystSection();
            assertThat(result.context().substring(section.start(), section.end()))
                    .startsWith("## " + action.label() + "\n").endsWith("synthesis\n\n");
        }
        verify(agentTaskExecutor, times(3)).execute(any(Runnable.class));
        verify(fundamentalsAgent).analyzeObserved(eq(QUERY), anyString(), eq(com.stocksage.evolution.AgentPolicyBundle.baseline()));
        verify(fundamentalsAgent, times(1)).selectMethod(eq(USER_ID), anySet(), anySet(), anyString(), any(), anyLong());
        verify(marketAgent).analyze(eq(QUERY), anyString());
        verify(newsAgent).analyze(eq(QUERY), anyString());
        verifyNoInteractions(backgroundTaskExecutor);
        var trace = (TraceService) ReflectionTestUtils.getField(service, "traceService");
        verify(trace).addStep(eq(TRACE_ID), org.mockito.ArgumentMatchers.argThat(step ->
                "Ordinary Evidence".equals(step.getAction())
                        && "FUNDAMENTALS".equals(step.getAttributes().get("route"))
                        && com.stocksage.evolution.AgentPolicyBundle.baseline().identity()
                        .equals(step.getAttributes().get("methodBundle"))));
        when(fundamentalsAgent.analyzeObserved(eq(QUERY), anyString(), any(com.stocksage.evolution.AgentPolicyBundle.class)))
                .thenReturn(observed("x".repeat(4501)));
        var truncated = service.prefetch(new ExecutionPlan(PlanRoute.FUNDAMENTALS, "analyst", "",
                        List.of(PlanAction.GET_FINANCIAL_REPORTS, PlanAction.FUNDAMENTALS_AGENT), "", ModelTier.STANDARD),
                QUERY, TRACE_ID, CONVERSATION_ID, USER_ID, selectedModel);
        assertThat(truncated.analystSection()).isNull();
        assertThat(truncated.taskOutcome()).isEqualTo("DEGRADED");
    }

    private static FundamentalsAgent.Analysis observed(String text) {
        return new FundamentalsAgent.Analysis(text, com.stocksage.agent.FundamentalsPrompts.system(
                com.stocksage.evolution.AgentPolicyBundle.baseline().method()), "unit task",
                com.stocksage.evolution.AgentPolicyBundle.baseline().identity(), java.util.Map.of("usageSource", "NO_DATA"));
    }

    @Test
    void ordinaryEntryPinsCandidateAcrossWithdrawalAndRecordsFreshBaselineInvocations(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path controls) throws Exception {
        configureOrdinary();
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; }).when(agentTaskExecutor).execute(any(Runnable.class));
        var json = new ObjectMapper();
        var fixture = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(java.nio.file.Files.readString(
                java.nio.file.Path.of("../stocksage-data-service/tests/fixtures/sec-financials-v1.json")));
        var revenue = (com.fasterxml.jackson.databind.node.ObjectNode) fixture.path("metrics").path("Revenue").deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode) revenue.path("data")).add(
                ((com.fasterxml.jackson.databind.node.ObjectNode) revenue.path("data").get(0)).deepCopy()
                        .put("start", "2024-02-01").put("end", "2025-01-31"));
        fixture.putObject("metrics").set("Revenue", revenue);
        fixture.put("ticker", TICKER).put("metric_count", 1).put("requestedPeriod", "annual").put("requestedYears", 2);
        when(fundamentalsTools.getFinancialReports(TICKER, "annual", 2)).thenReturn(fixture.toString());
        var runtime = mock(com.stocksage.agent.AgentRuntimeConfiguration.class);
        when(runtime.snapshot()).thenReturn(java.util.Map.of("fundamentals", java.util.Map.of(
                "model", "test-standard", "temperature", 0.7, "maxTokens", 4096)));
        when(runtime.chatProviderSnapshot()).thenReturn(java.util.Map.of("protocol", "TEST_DOUBLE"));
        var finalInvocation = java.util.Map.<String, Object>of("kind", "model-invocation", "scope", "final-answer", "modelName", "test-standard");
        String memory = com.stocksage.evolution.FundamentalsRuntimeIdentity.memoryHash("", "", List.of());
        var identity = com.stocksage.evolution.FundamentalsRuntimeIdentity.conditions(
                com.stocksage.evolution.FundamentalsRuntimeIdentity.modelConfiguration(runtime, List.of(finalInvocation), 1, 24000),
                "a".repeat(64), memory);
        var candidate = com.stocksage.evolution.AgentPolicyBundle.create("unit-ordinary-candidate", "baseline-v1", "先核对两个已提供年度的期间，再比较数值。");
        var approved = new com.stocksage.evolution.ApprovedMethodArtifact(candidate, "b".repeat(64), "c".repeat(64), "d".repeat(64), identity,
                new com.stocksage.evolution.ApprovedMethodArtifact.Scope(List.of("period-comparison"), List.of("dated-values"),
                        List.of("evidence-reading"), "仅比较本轮已提供的完整年度数据"), "e".repeat(64), 1);
        var registry = new com.stocksage.evolution.FundamentalsMethodRegistry("baseline-v1");
        // Signed startup loading is checked separately; this exercises the actual ordinary caller and model boundary.
        ReflectionTestUtils.setField(registry, "staged", approved);
        ReflectionTestUtils.setField(registry, "controlDirectory", controls);
        ReflectionTestUtils.setField(registry, "activation", new com.stocksage.evolution.MethodActivation("f".repeat(64), "unit-drill", "DRILL",
                java.util.Set.of(USER_ID), java.time.Instant.now().minusSeconds(30), java.time.Instant.now().plusSeconds(300)));
        var model = mock(org.springframework.ai.chat.model.ChatModel.class);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenAnswer(call -> {
            var prompt = call.<org.springframework.ai.chat.prompt.Prompt>getArgument(0);
            assertThat(prompt.getOptions().getModel()).isEqualTo("test-standard");
            if (calls.incrementAndGet() == 2) java.nio.file.Files.createFile(controls.resolve("REVOKE." + candidate.bundleId()));
            String text = prompt.getInstructions().get(0).getText().contains(candidate.method()) ? "candidate [E1]" : "baseline [E1]";
            return new org.springframework.ai.chat.model.ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(
                    new org.springframework.ai.chat.messages.AssistantMessage(text))));
        });
        var client = org.springframework.ai.chat.client.ChatClient.builder(model).defaultOptions(org.springframework.ai.openai.OpenAiChatOptions.builder()
                .model("test-standard").temperature(0.7).maxTokens(4096).build()).build();
        ReflectionTestUtils.setField(service, "fundamentalsAgent", new FundamentalsAgent(client, registry, runtime));
        var request = new com.stocksage.evolution.FundamentalsRuntimeIdentity.Request(QUERY, finalInvocation, memory, 24000, true);
        var plan = new ExecutionPlan(PlanRoute.FUNDAMENTALS, "comparison", "", List.of(PlanAction.GET_FINANCIAL_REPORTS,
                PlanAction.FUNDAMENTALS_AGENT), "", ModelTier.STANDARD, null, QUERY,
                new com.stocksage.agent.ReadRequest("3m", "1d", "annual", 2, false, ""));
        for (int i = 0; i < 4; i++) {
            var result = service.prefetch(plan, QUERY, "drill-run-" + i, CONVERSATION_ID, USER_ID,
                    new Coordinator.SelectedModel(ModelTier.STANDARD, "test-standard"), null, request);
            assertThat(result.context()).contains(i < 2 ? "candidate [E1]" : "baseline [E1]");
        }
        assertThat(calls.get()).isEqualTo(4);
        var trace = (TraceService) ReflectionTestUtils.getField(service, "traceService");
        var steps = org.mockito.ArgumentCaptor.forClass(com.stocksage.agent.AgentStep.class);
        verify(trace, times(4)).addStep(anyString(), steps.capture());
        for (int i = 0; i < 4; i++) {
            var attributes = steps.getAllValues().get(i).getAttributes();
            assertThat(attributes).containsEntry("analystStatus", "COMPLETED").containsKey("analystCompletedAt")
                    .containsKey("analystUsage").containsKey("analystInvocation");
            var selection = (java.util.Map<?, ?>) attributes.get("methodSelection");
            assertThat(selection.get("reason")).isEqualTo(i < 2 ? "APPROVED_SCOPE" : "BUNDLE_WITHDRAWN");
            assertThat(selection.get("comparisonIdentity")).isEqualTo(identity);
            assertThat(((java.util.Map<?, ?>) attributes.get("methodBundle")).get("bundleId"))
                    .isEqualTo(i < 2 ? candidate.bundleId() : "baseline-v1");
        }
    }

    @Test
    void ordinaryEvidenceRejectsWrongTargetsQuarterSubstitutionAndBudgetLoss() {
        org.mockito.Mockito.reset(tickerResolutionService);
        configureOrdinary();
        var mapper = (EvidenceEnvelopeMapper) ReflectionTestUtils.getField(service, "evidenceEnvelopeMapper");
        var read = com.stocksage.agent.ReadRequest.parse(PlanRoute.FUNDAMENTALS, "最近两个季度财报", java.util.Map.of());
        var evidence = new OrdinaryEvidence(TICKER, read, new ObjectMapper(), mapper);
        evidence.add("getFinancialReports", EvidenceDimension.FUNDAMENTALS,
                "{\"provider\":\"sec\",\"symbol\":\"" + TICKER + "\",\"period\":\"annual\",\"metrics\":{\"revenue\":{\"data\":[{\"value\":123}]}}}");
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

    @Test
    void boundedContextKeepsKlineUnitsAndBusinessTimeBeforeOptionalPayload() throws Exception {
        org.mockito.Mockito.reset(tickerResolutionService);
        configureOrdinary();
        var json = new ObjectMapper();
        var payload = json.createObjectNode();
        payload.put("optionalProviderDetail", "x".repeat(2050));
        payload.put("schemaVersion", 1);
        payload.put("status", "SUCCESS");
        payload.put("symbol", "0700.HK");
        payload.put("currency", "HKD");
        payload.put("volumeUnit", "SHARE");
        payload.put("adjustment", "FORWARD_ADJUSTED");
        payload.put("timeKind", "DATE");
        payload.put("asOf", "2026-09-24");
        payload.put("fetchedAt", "2026-09-25T01:00:00Z");
        var metadata = payload.putObject("historyMetadata");
        metadata.put("volumeFactor", 100);
        metadata.put("priceFactor", 100);
        metadata.put("mdAvailability", "D");
        metadata.put("mktDataDelay", 500);
        metadata.put("outsideRth", true);
        var evidence = new OrdinaryEvidence("0700.HK",
                com.stocksage.agent.ReadRequest.parse(PlanRoute.MARKET, "最近日线", java.util.Map.of()), json,
                (EvidenceEnvelopeMapper) ReflectionTestUtils.getField(service, "evidenceEnvelopeMapper"));
        String compact = ReflectionTestUtils.invokeMethod(evidence, "compact", payload);
        var actual = json.readTree(compact);
        assertThat(actual.path("contextReduced").asBoolean()).isTrue();
        assertThat(actual.has("optionalProviderDetail")).isFalse();
        payload.remove("optionalProviderDetail");
        payload.fields().forEachRemaining(field -> assertThat(actual.get(field.getKey())).isEqualTo(field.getValue()));
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
        assertThat(result.taskOutcome()).isEqualTo("DEGRADED");
        assertThat(result.context()).contains("TIME_CONTRACT_MISSING");
        assertThat(result.citationIds()).containsExactly("E1");
        assertThat(result.outcomeForAnswer("价格101 [E1]")).isEqualTo("DEGRADED");
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
                .isEqualTo("DEGRADED");
        verify(fundamentalsTools).getFinancialReports(TICKER, "quarterly", 1);
    }

    @Test void completedEvidenceStillRequiresValidFinalCitations() {
        org.mockito.Mockito.reset(tickerResolutionService);
        var context = new ToolPrefetchService.PreparedToolContext("evidence", "", null, TRACE_ID,
                "COMPLETED", List.of("E1"));
        assertThat(context.outcomeForAnswer("证据支持的事实 [E1]")).isEqualTo("COMPLETED");
        assertThat(context.outcomeForAnswer("未知引用 [E99]")).isEqualTo("DEGRADED");
        assertThat(context.outcomeForAnswer("缺少引用")).isEqualTo("DEGRADED");
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
        task.setAttempts(1);
        task.setBudgetDeadlineEpochMs(System.currentTimeMillis() + 3_600_000L);
        task.setMaxModelCalls(100);
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
                TICKER, QUERY, TRACE_ID, CONVERSATION_ID, TimeSensitivity.REAL_TIME)).thenReturn(PAYLOAD);
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
        verify(deepResearchPipeline, never()).runInlineFallback(
                any(), any(), any(), any(), any(), any(), any(), any(), any());
        verifyNoInteractions(
                deepEvidenceCollector,
                fundamentalsAgent,
                marketAgent,
                newsAgent,
                investmentReportVersionService
        );
    }
}
