package com.stocksage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.agent.Coordinator;
import com.stocksage.agent.ExecutionPlan;
import com.stocksage.agent.FundamentalsAgent;
import com.stocksage.agent.MarketAgent;
import com.stocksage.agent.ModelTier;
import com.stocksage.agent.NewsAgent;
import com.stocksage.agent.PlanAction;
import com.stocksage.model.dto.AnalysisState;
import com.stocksage.model.entity.ResearchTask;
import com.stocksage.skill.SkillExecutionService;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
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
    private DeepResearchPipeline deepResearchPipeline;
    @Mock
    private InvestmentReportVersionService investmentReportVersionService;
    @Mock
    private ResearchTaskService researchTaskService;
    @Mock
    private ResearchTaskQueue researchTaskQueue;
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
        ReflectionTestUtils.setField(service, "toolPrefetchEnabled", true);
        ReflectionTestUtils.setField(service, "userMaxActive", 3);
        when(tickerResolutionService.resolvePrimaryTicker(QUERY, CONVERSATION_ID)).thenReturn(TICKER);
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
        verifyNoInteractions(researchTaskQueue);
        verifyNoSubmissionWorkWasRun();
    }

    @Test
    void queueUnavailableFallsBackToInlineDeepResearch() throws Exception {
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
                true
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

        prefetch();

        verify(deepEvidenceCollector).collect(TICKER, QUERY, TRACE_ID, CONVERSATION_ID);
        verify(researchTaskService).renewLease(lease);
        verify(researchTaskService).heartbeatForOwner(task, lease.token());
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
        when(deepEvidenceCollector.collect(TICKER, QUERY, TRACE_ID, CONVERSATION_ID))
                .thenThrow(new IllegalStateException("evidence down"));
        when(researchTaskService.markFailedForOwner(task, lease.token(), "evidence down"))
                .thenReturn(true);

        assertThatThrownBy(this::prefetch)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("evidence down");

        verify(researchTaskService).markFailedForOwner(task, lease.token(), "evidence down");
        verify(researchTaskService).release(lease);
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
