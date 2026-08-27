package com.stocksage.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.agent.BearResearcher;
import com.stocksage.agent.BullResearcher;
import com.stocksage.agent.DebateContractParser;
import com.stocksage.agent.DebateDecisionPolicy;
import com.stocksage.agent.DebateRoundPlanner;
import com.stocksage.agent.ModelTier;
import com.stocksage.agent.ResearchDebateService;
import com.stocksage.agent.ResearchManager;
import com.stocksage.harness.DeepResearchCompletionPolicy;
import com.stocksage.harness.HarnessModels.HarnessDecision;
import com.stocksage.harness.HarnessModels.HarnessOutcome;
import com.stocksage.harness.HarnessModels.ParseStatus;
import com.stocksage.harness.HarnessModels.SynthesisResult;
import com.stocksage.harness.ResearchHarness;
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
import com.stocksage.model.entity.ResearchTask;
import com.stocksage.model.entity.ResearchTaskCheckpoint;
import com.stocksage.model.entity.User;
import com.stocksage.repository.ResearchTaskCheckpointRepository;
import com.stocksage.repository.UserAccountRepository;
import com.stocksage.service.ConversationMessageService;
import com.stocksage.service.DeepEvidenceCollector;
import com.stocksage.service.DeepResearchPipeline;
import com.stocksage.service.InvestmentReportVersionService;
import com.stocksage.service.OfflineDemoSampleService;
import com.stocksage.service.ReportMarkdownRenderer;
import com.stocksage.service.ResearchTaskCheckpointService;
import com.stocksage.service.ResearchTaskLeaseService;
import com.stocksage.service.ResearchTaskPublicationTransaction;
import com.stocksage.service.ResearchTaskService;
import com.stocksage.tool.ChatStreamEmitter;
import com.stocksage.trace.TraceService;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
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
        DebateContractParser debateContractParser = mock(DebateContractParser.class);
        DebateDecisionPolicy debateDecisionPolicy = mock(DebateDecisionPolicy.class);
        DebateRoundPlanner roundPlanner = mock(DebateRoundPlanner.class);
        when(bullResearcher.argue(any(), anyInt()))
                .thenAnswer(call -> Flux.just("bull-r" + call.getArgument(1, Integer.class)));
        when(bearResearcher.argue(any(), anyInt()))
                .thenAnswer(call -> Flux.just("bear-r" + call.getArgument(1, Integer.class)));
        when(roundPlanner.decide(any(), anyInt()))
                .thenReturn(new DebateRoundPlanner.RoundDecision(3, "takeover fixture"));
        when(debateContractParser.parse(any(), anyInt(), any(Side.class), any()))
                .thenAnswer(call -> structuredTurn(
                        call.getArgument(1, Integer.class),
                        call.getArgument(2, Side.class)));
        when(debateDecisionPolicy.computeInputHash(any())).thenReturn("fixture-input-0");
        when(researchManager.scoreDebate(any(), any(), any(Runnable.class)))
                .thenAnswer(call -> Mono.just(managerAssessment(
                        call.getArgument(0, AnalysisState.class))));
        when(debateDecisionPolicy.decide(any(AnalysisState.class), any(ManagerAssessment.class)))
                .thenAnswer(call -> fixtureVerdict(
                        call.getArgument(0, AnalysisState.class),
                        call.getArgument(1, ManagerAssessment.class)));
        InvestmentReport report = InvestmentReport.builder()
                .ticker("AAPL")
                .recommendation("HOLD")
                .analystSummary("resumed report")
                .build();
        SynthesisResult synthesisResult = new SynthesisResult(report, ParseStatus.VALID, List.of());
        when(researchManager.synthesizeStreamingResult(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(synthesisResult));
        when(researchManager.synthesizeStreamingResult(
                any(), any(), any(), any(), any(), any(Runnable.class)))
                .thenReturn(Mono.just(synthesisResult));
        ResearchHarness researchHarness = mock(ResearchHarness.class);
        when(researchHarness.evaluateReport(any(), any(), any(), any(), any()))
                .thenReturn(new HarnessDecision(HarnessOutcome.PASS, List.of(), List.of()));

        ResearchDebateService debateService = spy(new ResearchDebateService(
                bullResearcher,
                bearResearcher,
                researchManager,
                debateContractParser,
                debateDecisionPolicy,
                roundPlanner,
                mock(TraceService.class),
                mock(ChatStreamEmitter.class),
                researchHarness,
                new DeepResearchCompletionPolicy()
        ));
        ResearchTaskService researchTaskService = mock(ResearchTaskService.class);
        ResearchTaskLeaseService.Lease firstLease = lease("instance-a-token");
        ResearchTaskLeaseService.Lease takeoverLease = lease("instance-b-token");
        when(researchTaskService.renewLease(takeoverLease)).thenReturn(true);
        when(checkpointRepository.lockOwnedRunningTask(TASK_ID, firstLease.token()))
                .thenReturn(Optional.of(TASK_ID));
        when(checkpointRepository.lockOwnedRunningTask(TASK_ID, takeoverLease.token()))
                .thenReturn(Optional.of(TASK_ID));
        when(checkpointRepository.lockSucceededTask(TASK_ID)).thenReturn(Optional.of(TASK_ID));
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
                                        TASK_ID, firstLease.token(), state, roundsCompleted, plannedRounds);
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
            // ResearchTaskWorker performs the stale RUNNING owner-token CAS before entering the pipeline.
            task.setStatus(ResearchTask.Status.RUNNING);
            task.setLeaseToken(takeoverLease.token());
            when(researchTaskService.heartbeatForOwner(task, takeoverLease.token())).thenReturn(true);
            DeepEvidenceCollector evidenceCollector = mock(DeepEvidenceCollector.class);
            InvestmentReportVersionService reportVersionService = mock(InvestmentReportVersionService.class);
            ReportMarkdownRenderer reportRenderer = mock(ReportMarkdownRenderer.class);
            ConversationMessageService conversationMessageService = mock(ConversationMessageService.class);
            ChatStreamEmitter streamEmitter = mock(ChatStreamEmitter.class);
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
                    .markSucceededForOwner(
                            task,
                            takeoverLease.token(),
                            99L,
                            ResearchTask.ResultKind.FULL_REPORT
                     );
            UserAccountRepository userAccountRepository = mock(UserAccountRepository.class);
            User publicationUser = new User();
            publicationUser.setUserId("u_001");
            when(userAccountRepository.lockByUserIdForUpdate("u_001"))
                    .thenReturn(Optional.of(publicationUser));

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
                    new ResearchTaskPublicationTransaction(userAccountRepository),
                    streamEmitter,
                    mock(com.stocksage.trace.TraceService.class)
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
                    eq("trace-42"), eq(20L), any(AnalysisState.class), eq(3), eq(3),
                    any(), any(Runnable.class), any());
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

    private DebateTurn structuredTurn(int round, Side side) {
        String prefix = side == Side.BULL ? "bull" : "bear";
        PointType type = round == 1 ? PointType.THESIS : PointType.REBUTTAL;
        int pointCount = round == 1 ? 3 : 2;
        String opponent = side == Side.BULL ? "bear" : "bull";
        List<DebatePoint> points = java.util.stream.IntStream.rangeClosed(1, pointCount)
                .mapToObj(index -> new DebatePoint(
                        prefix + "-" + type.name().toLowerCase() + "-r" + round + "-" + index,
                        type,
                        prefix + " claim " + round + "-" + index,
                        AnalysisHorizon.MEDIUM_TERM,
                        List.of(new EvidenceRef(
                                "fixture-evidence-" + index,
                                "fixture evidence excerpt " + index)),
                        prefix + " reasoning " + index,
                        prefix + " assumption " + index,
                        prefix + " invalidation " + index,
                        round == 1
                                ? List.of()
                                : List.of(opponent + "-thesis-r1-" + index)
                ))
                .toList();
        return new DebateTurn(round, side, points);
    }

    private ManagerAssessment managerAssessment(AnalysisState state) {
        List<ArgumentAssessment> assessments = state.getDebateTurns().stream()
                .filter(turn -> turn.round() == 1)
                .flatMap(turn -> turn.points().stream())
                .map(point -> new ArgumentAssessment(
                        point.pointId(), 3, 3, 3, 3, 3,
                        List.of(point.evidenceRefs().get(0).evidenceId()),
                        List.of(),
                        List.of(AssessmentReasonCode.SUPPORTED),
                        "fixture assessment"
                ))
                .toList();
        return new ManagerAssessment(
                DebateModels.MANAGER_ASSESSMENT_CONTRACT_ID,
                DebateModels.MANAGER_ASSESSMENT_CONTRACT_VERSION,
                "fixture-input-0",
                true,
                assessments,
                AssessmentParseStatus.VALID,
                List.of()
        );
    }

    private DebateVerdict fixtureVerdict(
            AnalysisState state,
            ManagerAssessment assessment
    ) {
        return new DebateVerdict(
                DebateDecisionPolicy.POLICY_ID,
                DebateDecisionPolicy.POLICY_VERSION,
                assessment.inputHash(),
                state.getDataSnapshotHash(),
                72,
                72,
                LeadingSide.BALANCED,
                0,
                "HOLD",
                AnalysisHorizon.MEDIUM_TERM,
                List.of(),
                List.of(),
                assessment.assessments()
        );
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
