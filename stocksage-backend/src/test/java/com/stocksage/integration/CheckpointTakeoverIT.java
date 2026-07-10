package com.stocksage.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.agent.BearResearcher;
import com.stocksage.agent.BullResearcher;
import com.stocksage.agent.DebateRoundPlanner;
import com.stocksage.agent.ModelTier;
import com.stocksage.agent.ResearchDebateService;
import com.stocksage.agent.ResearchManager;
import com.stocksage.model.dto.AnalysisState;
import com.stocksage.model.dto.InvestmentReport;
import com.stocksage.model.entity.ResearchTask;
import com.stocksage.model.entity.ResearchTaskCheckpoint;
import com.stocksage.repository.ResearchTaskCheckpointRepository;
import com.stocksage.service.ConversationMessageService;
import com.stocksage.service.DeepEvidenceCollector;
import com.stocksage.service.DeepResearchPipeline;
import com.stocksage.service.InvestmentReportVersionService;
import com.stocksage.service.OfflineDemoSampleService;
import com.stocksage.service.ReportMarkdownRenderer;
import com.stocksage.service.ResearchTaskCheckpointService;
import com.stocksage.service.ResearchTaskLeaseService;
import com.stocksage.service.ResearchTaskService;
import com.stocksage.tool.ChatStreamEmitter;
import com.stocksage.trace.TraceService;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用两个独立执行线程模拟两个后端实例，验证崩溃后由持久化 checkpoint 接管。
 *
 * <p>这里刻意不启动 Spring/Testcontainers：Redis Stream 的 XCLAIM 语义由队列 IT 覆盖，
 * 本测试只锁定最昂贵也最容易回归的语义——第二个 worker 必须从第 3 轮继续，不能重烧前两轮。</p>
 */
class CheckpointTakeoverIT {

    private static final long TASK_ID = 42L;

    @Test
    void workerCrashMidDebateIsResumedFromCheckpointWithoutRedoingRounds() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        AtomicReference<ResearchTaskCheckpoint> persistedCheckpoint = new AtomicReference<>();
        ResearchTaskCheckpointRepository checkpointRepository = inMemoryCheckpointRepository(persistedCheckpoint);
        ResearchTaskCheckpointService checkpointService =
                new ResearchTaskCheckpointService(checkpointRepository, objectMapper);

        BullResearcher bullResearcher = mock(BullResearcher.class);
        BearResearcher bearResearcher = mock(BearResearcher.class);
        ResearchManager researchManager = mock(ResearchManager.class);
        DebateRoundPlanner roundPlanner = mock(DebateRoundPlanner.class);
        when(bullResearcher.argue(any(), anyInt()))
                .thenAnswer(call -> Flux.just("bull-r" + call.getArgument(1, Integer.class)));
        when(bearResearcher.argue(any(), anyInt()))
                .thenAnswer(call -> Flux.just("bear-r" + call.getArgument(1, Integer.class)));
        when(roundPlanner.decide(any(), anyInt()))
                .thenReturn(new DebateRoundPlanner.RoundDecision(3, "takeover fixture"));
        InvestmentReport report = InvestmentReport.builder()
                .ticker("AAPL")
                .recommendation("HOLD")
                .analystSummary("resumed report")
                .build();
        when(researchManager.synthesizeStreaming(any(), any(), any(), any()))
                .thenReturn(Mono.just(report));

        ResearchDebateService debateService = spy(new ResearchDebateService(
                bullResearcher,
                bearResearcher,
                researchManager,
                roundPlanner,
                mock(TraceService.class),
                mock(ChatStreamEmitter.class)
        ));
        ResearchTaskService researchTaskService = mock(ResearchTaskService.class);
        ResearchTaskLeaseService.Lease firstLease = lease("instance-a-token");
        ResearchTaskLeaseService.Lease takeoverLease = lease("instance-b-token");
        AtomicReference<String> firstWorkerThread = new AtomicReference<>();
        AtomicReference<String> takeoverWorkerThread = new AtomicReference<>();

        ExecutorService instanceA = Executors.newSingleThreadExecutor(
                runnable -> new Thread(runnable, "instance-a-worker"));
        ExecutorService instanceB = Executors.newSingleThreadExecutor(
                runnable -> new Thread(runnable, "instance-b-worker"));
        try {
            Future<Throwable> crashed = instanceA.submit(() -> {
                firstWorkerThread.set(Thread.currentThread().getName());
                try {
                    debateService.runDebate(
                            null,
                            20L,
                            AnalysisState.builder().query("q").primaryTicker("AAPL").build(),
                            1,
                            0,
                            (state, roundsCompleted, plannedRounds) -> {
                                checkpointService.saveDebateRound(
                                        TASK_ID, state, roundsCompleted, plannedRounds);
                                if (roundsCompleted == 2) {
                                    throw new SimulatedCrashException();
                                }
                            }
                    );
                    return null;
                } catch (Throwable error) {
                    // 进程退出不会执行业务 finally；显式释放代表租约过期后接管窗口已经打开。
                    researchTaskService.release(firstLease);
                    return error;
                }
            });

            assertThat(crashed.get()).isInstanceOf(SimulatedCrashException.class);
            ResearchTaskCheckpointService.CheckpointState checkpoint =
                    checkpointService.load(TASK_ID).orElseThrow();
            assertThat(checkpoint.debateRoundsCompleted()).isEqualTo(2);
            assertThat(checkpoint.plannedRounds()).isEqualTo(3);
            assertThat(checkpoint.state().getDebateTurns()).hasSize(4);

            ResearchTask task = pendingTask();
            DeepEvidenceCollector evidenceCollector = mock(DeepEvidenceCollector.class);
            InvestmentReportVersionService reportVersionService = mock(InvestmentReportVersionService.class);
            ReportMarkdownRenderer reportRenderer = mock(ReportMarkdownRenderer.class);
            ConversationMessageService conversationMessageService = mock(ConversationMessageService.class);
            ChatStreamEmitter streamEmitter = mock(ChatStreamEmitter.class);
            when(researchTaskService.startAttempt(
                    task, takeoverLease.token(), ResearchTask.Stage.AGENT_DEBATE))
                    .thenAnswer(call -> {
                        task.setStatus(ResearchTask.Status.RUNNING);
                        task.setStage(ResearchTask.Stage.AGENT_DEBATE);
                        return task;
            });
            when(reportVersionService.persistReportVersionWithMetadata(
                    eq("u_001"), eq(20L), any(AnalysisState.class),
                    eq(ModelTier.STRONG.name()), eq(null)))
                    .thenReturn(new InvestmentReportVersionService.PersistedReportVersion(
                            report, 99L, false));
            when(reportRenderer.buildFinalAnswerBrief(any(AnalysisState.class)))
                    .thenReturn("resumed brief");
            doAnswer(call -> {
                task.setStatus(ResearchTask.Status.SUCCEEDED);
                task.setStage(ResearchTask.Stage.COMPLETE);
                task.setResultReportVersionId(99L);
                return null;
            }).when(researchTaskService)
                    .markSucceededForOwner(task, takeoverLease.token(), 99L);

            DeepResearchPipeline pipeline = new DeepResearchPipeline(
                    researchTaskService,
                    debateService,
                    reportVersionService,
                    mock(OfflineDemoSampleService.class),
                    objectMapper,
                    mock(TaskScheduler.class),
                    checkpointService,
                    evidenceCollector,
                    reportRenderer,
                    conversationMessageService,
                    streamEmitter
            );

            Future<?> takenOver = instanceB.submit(() -> {
                takeoverWorkerThread.set(Thread.currentThread().getName());
                pipeline.runFullPipeline(task, takeoverLease);
            });
            takenOver.get();

            assertThat(firstWorkerThread.get()).isEqualTo("instance-a-worker");
            assertThat(takeoverWorkerThread.get()).isEqualTo("instance-b-worker");
            assertThat(task.getStatus()).isEqualTo(ResearchTask.Status.SUCCEEDED);
            assertThat(task.getResultReportVersionId()).isEqualTo(99L);
            assertThat(persistedCheckpoint.get()).isNull();
            verify(evidenceCollector, never()).collect(any(), any(), any(), any());
            verify(debateService).runDebate(
                    eq("trace-42"), eq(20L), any(AnalysisState.class), eq(3), eq(3), any());
            verify(bullResearcher, times(1)).argue(any(), eq(1));
            verify(bullResearcher, times(1)).argue(any(), eq(2));
            verify(bullResearcher, times(1)).argue(any(), eq(3));
            verify(bearResearcher, times(1)).argue(any(), eq(1));
            verify(bearResearcher, times(1)).argue(any(), eq(2));
            verify(bearResearcher, times(1)).argue(any(), eq(3));
            verify(researchTaskService).release(firstLease);
            verify(researchTaskService).release(takeoverLease);
        } finally {
            instanceA.shutdownNow();
            instanceB.shutdownNow();
        }
    }

    private ResearchTaskCheckpointRepository inMemoryCheckpointRepository(
            AtomicReference<ResearchTaskCheckpoint> persistedCheckpoint
    ) {
        ResearchTaskCheckpointRepository repository = mock(ResearchTaskCheckpointRepository.class);
        when(repository.findByTaskId(TASK_ID))
                .thenAnswer(call -> Optional.ofNullable(persistedCheckpoint.get()));
        when(repository.save(any(ResearchTaskCheckpoint.class)))
                .thenAnswer(call -> {
                    ResearchTaskCheckpoint checkpoint = call.getArgument(0);
                    persistedCheckpoint.set(checkpoint);
                    return checkpoint;
                });
        doAnswer(call -> {
            persistedCheckpoint.set(null);
            return null;
        }).when(repository).deleteByTaskId(TASK_ID);
        return repository;
    }

    private ResearchTask pendingTask() {
        ResearchTask task = new ResearchTask();
        task.setId(TASK_ID);
        task.setUserId("u_001");
        task.setConversationId(20L);
        task.setTicker("AAPL");
        task.setIdempotencyKey("deep-submit:aapl");
        task.setPayloadJson("{\"ticker\":\"AAPL\",\"query\":\"q\","
                + "\"traceId\":\"trace-42\",\"conversationId\":20}");
        task.setStatus(ResearchTask.Status.PENDING);
        task.setStage(ResearchTask.Stage.AGENT_DEBATE);
        return task;
    }

    private ResearchTaskLeaseService.Lease lease(String token) {
        return new ResearchTaskLeaseService.Lease(
                "deep-submit:aapl",
                token,
                ResearchTaskLeaseService.Backend.PROCESS
        );
    }

    private static final class SimulatedCrashException extends RuntimeException {
    }
}
