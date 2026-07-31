package com.stocksage.agent;

import com.stocksage.harness.DeepResearchCompletionPolicy;
import com.stocksage.harness.HarnessModels.HarnessDecision;
import com.stocksage.harness.HarnessModels.HarnessPhase;
import com.stocksage.harness.HarnessModels.HarnessOutcome;
import com.stocksage.harness.HarnessModels.HarnessSnapshot;
import com.stocksage.harness.HarnessModels.HarnessViolation;
import com.stocksage.harness.HarnessModels.RecoveryAction;
import com.stocksage.harness.HarnessModels.RecoveryLifecycle;
import com.stocksage.harness.HarnessModels.RunContext;
import com.stocksage.harness.HarnessModels.SynthesisResult;
import com.stocksage.harness.HarnessModels.ViolationCode;
import com.stocksage.harness.ResearchHarness;
import com.stocksage.model.dto.AnalysisState;
import com.stocksage.model.dto.AnalysisState.DebateTurn;
import com.stocksage.model.dto.InvestmentReport;
import com.stocksage.tool.ChatStreamEmitter;
import com.stocksage.trace.TraceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuple3;

import java.time.Duration;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 编排深度研究中的多空辩论循环。
 *
 * <p>分析师报告已经写入 AnalysisState。本服务负责决定辩论轮数、生成多头和空头论证、
 * 记录链路步骤，并调用 ResearchManager 生成最终结构化报告。</p>
 *
 * <p>为压缩深度研究的端到端耗时，辩论按如下方式调度：</p>
 * <ul>
 *   <li>轮次规划器与第 1 轮辩论<b>并发</b>执行——第 1 轮无论如何都会发生，因此规划器延迟被完全掩盖；</li>
 *   <li>每一轮内 Bull 与 Bear <b>并行</b>生成（“同时交卷”式辩论），轮间仍串行，
 *       使第 2 轮起双方都能读到上一轮对方的完整观点；</li>
 *   <li>Bull/Bear 的论证<b>流式</b>实时推送到前端推理面板，让用户看着辩论展开而非空等。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResearchDebateService {

    private static final Runnable NO_OP_EXECUTION_GUARD = () -> {
    };

    @FunctionalInterface
    public interface RoundCheckpointer {

        /** 每轮双方定稿后保存完整状态，避免恢复时重烧已经完成的模型轮次。 */
        void onRoundCompleted(AnalysisState state, int roundsCompleted, int plannedRounds);
    }

    private final BullResearcher bullResearcher;
    private final BearResearcher bearResearcher;
    private final ResearchManager researchManager;
    private final DebateRoundPlanner debateRoundPlanner;
    private final TraceService traceService;
    private final ChatStreamEmitter chatStreamEmitter;
    private final ResearchHarness researchHarness;
    private final DeepResearchCompletionPolicy completionPolicy;

    @Value("${stocksage.agent.debate.max-rounds:5}")
    private int maxRounds;

    /**
     * 单个模型阶段的外层硬截止时间。
     *
     * <p>底层客户端通常已有连接或读超时，但宿主休眠、半断开的长连接或供应商流式响应不结束时，
     * 这些超时不一定能及时释放 research worker。这里的 Reactor timeout 是执行层最后一道保险：
     * 它会取消仍未完成的上游订阅，让任务进入既有的重试/接管流程，而不是永久占住 worker。</p>
     */
    @Value("${stocksage.agent.debate.model-stage-timeout-ms:300000}")
    private long modelStageTimeoutMs = 300_000L;

    /**
     * 不记录链路追踪、不流式推送地运行一轮研究辩论。
     *
     * <p>主要供内部调用或测试使用；需要追踪面板展示和流式推送时请使用带 traceId 的重载。</p>
     */
    public AnalysisState runDebate(AnalysisState state) {
        return runDebate(null, null, state);
    }

    /**
     * 基于传入的证据状态运行辩论，并返回补充了辩论轮次和综合报告的同一个状态对象。
     *
     * @param traceId        链路 id；为空时静默跳过追踪与流式推送
     * @param conversationId 会话 id，用于流式数据块关联
     * @param state          已收集分析师证据的研究状态
     */
    public AnalysisState runDebate(String traceId, Long conversationId, AnalysisState state) {
        return runDebate(traceId, conversationId, state, 1, 0, null);
    }

    /**
     * 从指定轮次继续辩论；既定总轮数来自 checkpoint，恢复时不再让 planner 改写历史决策。
     */
    public AnalysisState runDebate(
            String traceId,
            Long conversationId,
            AnalysisState state,
            int startRound,
            int fixedPlannedRounds,
            RoundCheckpointer checkpointer
    ) {
        return runDebate(
                traceId, conversationId, state, startRound, fixedPlannedRounds,
                checkpointer, null, null);
    }

    /**
     * 从指定轮次继续辩论，并在每个流式 token 推送前检查当前执行权。
     *
     * <p>检查器必须是纯内存、非阻塞检查；抛出的异常会取消模型 token 流并原样传给调用方。
     * 传 {@code null} 与旧重载行为一致。</p>
     */
    public AnalysisState runDebate(
            String traceId,
            Long conversationId,
            AnalysisState state,
            int startRound,
            int fixedPlannedRounds,
            RoundCheckpointer checkpointer,
            Runnable executionGuard
    ) {
        return runDebate(
                traceId,
                conversationId,
                state,
                startRound,
                fixedPlannedRounds,
                checkpointer,
                executionGuard,
                null
        );
    }

    public AnalysisState runDebate(
            String traceId,
            Long conversationId,
            AnalysisState state,
            int startRound,
            int fixedPlannedRounds,
            RoundCheckpointer checkpointer,
            Runnable executionGuard,
            Consumer<AnalysisState> harnessCheckpointer
    ) {
        Runnable guard = executionGuard == null ? NO_OP_EXECUTION_GUARD : executionGuard;
        AnalysisState workingState = state == null ? AnalysisState.builder().build() : state;

        int firstRound;
        int rounds;

        if (startRound == 1 && fixedPlannedRounds <= 0) {
            // 第 1 轮辩论无论如何都会发生（轮数 >= 1），因此让轮次规划器与第 1 轮并发执行，
            // 把规划器延迟完全藏到第 1 轮之后；轮内 Bull/Bear 也并行流式生成。
            long round1Start = System.currentTimeMillis();
            log.info("Research Debate started, traceId={}, round=1 with planner running concurrently, "
                            + "hasFundamentals={}, hasMarket={}, hasNews={}",
                    traceId,
                    isPresent(workingState.getFundamentalsReport()),
                    isPresent(workingState.getMarketReport()),
                    isPresent(workingState.getNewsReport()));

            Mono<String> bullRound1 = streamArgument(traceId, conversationId, true, 1,
                    bullResearcher.argue(workingState, 1), guard);
            Mono<String> bearRound1 = streamArgument(traceId, conversationId, false, 1,
                    bearResearcher.argue(workingState, 1), guard);
            Mono<DebateRoundPlanner.RoundDecision> plannerMono = Mono.fromCallable(
                            () -> debateRoundPlanner.decide(workingState, maxRounds))
                    .subscribeOn(Schedulers.boundedElastic());

            Tuple3<String, String, DebateRoundPlanner.RoundDecision> round1 =
                    awaitModelStage(
                            Mono.zip(bullRound1, bearRound1, plannerMono),
                            "debate-round-1",
                            traceId
                    );

            applyRound(workingState, 1, round1.getT1(), round1.getT2());
            addTraceStep(traceId, "Bull Researcher", "Round 1", round1.getT1(), round1Start);
            addTraceStep(traceId, "Bear Researcher", "Round 1", round1.getT2(), round1Start);

            DebateRoundPlanner.RoundDecision roundDecision = round1.getT3();
            rounds = roundDecision.rounds();
            if (checkpointer != null) {
                checkpointer.onRoundCompleted(workingState, 1, rounds);
            }
            addTraceStep(traceId, "Debate Round Planner",
                    "maxRounds=" + Math.max(1, Math.min(maxRounds, 5)),
                    "Selected rounds=" + rounds + ". Reason: " + roundDecision.reason(), round1Start);
            log.info("Research Debate round 1 completed, traceId={}, totalRounds={}, roundReason={}",
                    traceId, rounds, roundDecision.reason());
            firstRound = 2;
        } else {
            firstRound = Math.max(1, startRound);
            rounds = Math.max(1, fixedPlannedRounds);
            log.info("Research Debate resumed, traceId={}, startRound={}, totalRounds={}",
                    traceId, firstRound, rounds);
        }

        // 轮间串行（双方都能读到上一轮对方的完整观点），轮内 Bull/Bear 并行。
        for (int round = firstRound; round <= rounds; round++) {
            long roundStart = System.currentTimeMillis();
            log.info("Research Debate round started, traceId={}, round={}", traceId, round);
            Mono<String> bullN = streamArgument(traceId, conversationId, true, round,
                    bullResearcher.argue(workingState, round), guard);
            Mono<String> bearN = streamArgument(traceId, conversationId, false, round,
                    bearResearcher.argue(workingState, round), guard);
            Tuple2<String, String> roundResult = awaitModelStage(
                    Mono.zip(bullN, bearN),
                    "debate-round-" + round,
                    traceId
            );
            applyRound(workingState, round, roundResult.getT1(), roundResult.getT2());
            if (checkpointer != null) {
                checkpointer.onRoundCompleted(workingState, round, rounds);
            }
            addTraceStep(traceId, "Bull Researcher", "Round " + round, roundResult.getT1(), roundStart);
            addTraceStep(traceId, "Bear Researcher", "Round " + round, roundResult.getT2(), roundStart);
        }

        // Research Manager 改为流式：自然语言综合判断逐 token 推送到推理面板（"manager-synthesis" 分组），
        // JSON 部分在后端 buffer 直至流结束再解析为结构化 InvestmentReport，
        // 让用户在 Manager 思考期间持续看到中文综合判断生成而非静默等待。
        long managerStart = System.currentTimeMillis();
        log.info("Research Manager started (streaming), traceId={}", traceId);
        HarnessSnapshot checkpointSnapshot = workingState.getHarnessSnapshot();
        if (isRevalidatedReportMissingArtifact(checkpointSnapshot, workingState)) {
            HarnessDecision failSafeDecision = failSafeMissingReportDecision(checkpointSnapshot);
            InvestmentReport notRated = buildNotRatedReport(workingState, failSafeDecision);
            workingState.setInvestmentReport(notRated);
            applyFinalReportSnapshot(
                    workingState,
                    failSafeDecision,
                    reportRecoveryAttempts(checkpointSnapshot),
                    checkpointSnapshot,
                    traceId,
                    conversationId
            );
            checkpointHarnessState(harnessCheckpointer, workingState);
            addTraceStep(traceId, "Research Manager", "Restore revalidated report",
                    notRated.getAnalystSummary(), managerStart);
            log.warn("Revalidated report checkpoint had no report artifact; returning NOT_RATED, traceId={}",
                    traceId);
            return workingState;
        }
        boolean resumingReportRecovery = isReportRecoveryCheckpoint(checkpointSnapshot);
        Map<RecoveryAction, Integer> reportRecoveryAttempts =
                reportRecoveryAttempts(checkpointSnapshot);
        if (resumingReportRecovery) {
            reportRecoveryAttempts = withMinimumRecoveryAttempt(
                    reportRecoveryAttempts,
                    RecoveryAction.RESYNTHESIZE_REPORT,
                    1
            );
        }
        String reportRecoveryEffectKey = resumingReportRecovery
                ? existingOrStableReportEffectKey(checkpointSnapshot, traceId, conversationId)
                : "";
        if (resumingReportRecovery) {
            chatStreamEmitter.emit(traceId, conversationId, "observation",
                    "从报告断点恢复：正在执行既定的一次重新综合并重新验收。");
        }
        Mono<SynthesisResult> synthesis = executionGuard == null
                ? researchManager.synthesizeStreamingResult(
                        workingState, traceId, conversationId, chatStreamEmitter)
                : researchManager.synthesizeStreamingResult(
                        workingState, traceId, conversationId, chatStreamEmitter, guard);
        SynthesisResult synthesisResult = awaitModelStage(
                synthesis,
                "manager-synthesis",
                traceId
        );
        HarnessDecision reportDecision = researchHarness.evaluateReport(
                traceId,
                completionPolicy,
                new RunContext("DEEP", reportRecoveryAttempts),
                workingState.getEvidenceLedger(),
                synthesisResult
        );
        boolean checkpointRevalidatedReport = false;
        if (resumingReportRecovery) {
            applyReportRecoverySnapshot(
                    workingState,
                    reportDecision,
                    reportRecoveryAttempts,
                    RecoveryLifecycle.REVALIDATED,
                    reportRecoveryEffectKey
            );
            checkpointRevalidatedReport = true;
        } else if (reportDecision.outcome() == HarnessOutcome.RECOVER
                && reportDecision.recoveryActions().contains(RecoveryAction.RESYNTHESIZE_REPORT)) {
            reportRecoveryEffectKey = stableReportRecoveryEffectKey(traceId, conversationId);
            applyReportRecoverySnapshot(
                    workingState,
                    reportDecision,
                    reportRecoveryAttempts,
                    RecoveryLifecycle.PLANNED,
                    reportRecoveryEffectKey
            );
            checkpointHarnessState(harnessCheckpointer, workingState);
            chatStreamEmitter.emit(traceId, conversationId, "observation",
                    "报告结构或证据引用未通过校验，正在重新综合一次。");
            Mono<SynthesisResult> repair = executionGuard == null
                    ? researchManager.synthesizeStreamingResult(
                            workingState, traceId, conversationId, chatStreamEmitter)
                    : researchManager.synthesizeStreamingResult(
                            workingState, traceId, conversationId, chatStreamEmitter, guard);
            synthesisResult = awaitModelStage(
                    repair,
                    "manager-repair",
                    traceId
            );
            Map<RecoveryAction, Integer> completedReportRecoveryAttempts =
                    incrementRecoveryAttempt(
                            reportRecoveryAttempts,
                            RecoveryAction.RESYNTHESIZE_REPORT
                    );
            reportDecision = researchHarness.evaluateReport(
                    traceId,
                    completionPolicy,
                    new RunContext("DEEP", completedReportRecoveryAttempts),
                    workingState.getEvidenceLedger(),
                    synthesisResult
            );
            applyReportRecoverySnapshot(
                    workingState,
                    reportDecision,
                    completedReportRecoveryAttempts,
                    RecoveryLifecycle.REVALIDATED,
                    reportRecoveryEffectKey
            );
            checkpointRevalidatedReport = true;
        }
        InvestmentReport report;
        if (reportDecision.outcome() == HarnessOutcome.PASS
                && synthesisResult != null && synthesisResult.report() != null) {
            report = synthesisResult.report();
            report.setQualityStatus(InvestmentReport.ReportQualityStatus.VERIFIED);
            report.setCompletionPolicyId(completionPolicy.policyId());
            report.setCompletionPolicyVersion(completionPolicy.policyVersion());
        } else {
            report = buildNotRatedReport(workingState, reportDecision);
        }
        workingState.setInvestmentReport(report);
        if (checkpointRevalidatedReport) {
            // Persist the decision and its resulting report together. A takeover must never observe
            // REVALIDATED without the artifact that was actually revalidated.
            checkpointHarnessState(harnessCheckpointer, workingState);
        }
        addTraceStep(traceId, "Research Manager", "Synthesize InvestmentReport",
                report.getAnalystSummary(), managerStart);
        log.info("Research Manager completed, traceId={}, durationMs={}",
                traceId, System.currentTimeMillis() - managerStart);
        return workingState;
    }

    /**
     * Revalidates a synthesized report loaded from a durable checkpoint against the current
     * completion policy and evidence ledger.
     *
     * <p>A checkpoint's {@code VERIFIED} label is historical data, not current authority. If the
     * artifact no longer passes, this method may consume the single report-repair budget, but it
     * never replays Bull/Bear rounds. A repair is allowed only when complete debate turns prove
     * that at least one round already finished; otherwise recovery fails closed to NOT_RATED.</p>
     */
    public AnalysisState revalidateCheckpointedReport(
            String traceId,
            Long conversationId,
            AnalysisState state,
            Runnable executionGuard,
            Consumer<AnalysisState> harnessCheckpointer
    ) {
        AnalysisState workingState = state == null ? AnalysisState.builder().build() : state;
        InvestmentReport checkpointedReport = workingState.getInvestmentReport();
        if (checkpointedReport == null) {
            return workingState;
        }

        HarnessSnapshot previousSnapshot = workingState.getHarnessSnapshot();
        Map<RecoveryAction, Integer> previousAttempts =
                reportRecoveryAttempts(previousSnapshot);

        // A persisted PLANNED decision reserves the one Manager repair. Do not let a stale
        // artifact (if an older writer happened to retain one) cancel that durable effect.
        if (isReportRecoveryCheckpoint(previousSnapshot)) {
            HarnessDecision plannedDecision = recoveryDecisionFrom(previousSnapshot);
            return executeCheckpointedReportRepair(
                    traceId,
                    conversationId,
                    workingState,
                    plannedDecision,
                    previousAttempts,
                    executionGuard,
                    harnessCheckpointer
            );
        }

        HarnessDecision reportDecision = researchHarness.evaluateReport(
                traceId,
                completionPolicy,
                new RunContext("DEEP", previousAttempts),
                workingState.getEvidenceLedger(),
                new SynthesisResult(
                        checkpointedReport,
                        hasCurrentPolicyMetadata(checkpointedReport)
                                ? com.stocksage.harness.HarnessModels.ParseStatus.VALID
                                : com.stocksage.harness.HarnessModels.ParseStatus.INVALID_SCHEMA,
                        List.of()
                )
        );

        if (reportDecision.outcome() == HarnessOutcome.PASS) {
            checkpointedReport.setQualityStatus(InvestmentReport.ReportQualityStatus.VERIFIED);
            checkpointedReport.setCompletionPolicyId(completionPolicy.policyId());
            checkpointedReport.setCompletionPolicyVersion(completionPolicy.policyVersion());
            workingState.setInvestmentReport(checkpointedReport);
            applyFinalReportSnapshot(
                    workingState,
                    reportDecision,
                    previousAttempts,
                    previousSnapshot,
                    traceId,
                    conversationId
            );
            checkpointHarnessState(harnessCheckpointer, workingState);
            return workingState;
        }

        if (reportDecision.outcome() == HarnessOutcome.RECOVER
                && reportDecision.recoveryActions()
                .contains(RecoveryAction.RESYNTHESIZE_REPORT)) {
            return executeCheckpointedReportRepair(
                    traceId,
                    conversationId,
                    workingState,
                    reportDecision,
                    previousAttempts,
                    executionGuard,
                    harnessCheckpointer
            );
        }

        workingState.setInvestmentReport(buildNotRatedReport(workingState, reportDecision));
        applyFinalReportSnapshot(
                workingState,
                reportDecision,
                previousAttempts,
                previousSnapshot,
                traceId,
                conversationId
        );
        checkpointHarnessState(harnessCheckpointer, workingState);
        return workingState;
    }

    private AnalysisState executeCheckpointedReportRepair(
            String traceId,
            Long conversationId,
            AnalysisState state,
            HarnessDecision reportDecision,
            Map<RecoveryAction, Integer> previousAttempts,
            Runnable executionGuard,
            Consumer<AnalysisState> harnessCheckpointer
    ) {
        int completedRounds = completedDebateRounds(state);
        if (completedRounds <= 0) {
            HarnessDecision failClosed = new HarnessDecision(
                    HarnessOutcome.DEGRADE,
                    reportDecision.violations(),
                    List.of(RecoveryAction.RETURN_NOT_RATED)
            );
            HarnessSnapshot previousSnapshot = state.getHarnessSnapshot();
            state.setInvestmentReport(buildNotRatedReport(state, failClosed));
            applyFinalReportSnapshot(
                    state,
                    failClosed,
                    previousAttempts,
                    previousSnapshot,
                    traceId,
                    conversationId
            );
            checkpointHarnessState(harnessCheckpointer, state);
            log.warn("Checkpointed report failed validation without completed debate rounds; "
                    + "returning NOT_RATED, traceId={}", traceId);
            return state;
        }

        HarnessSnapshot previousSnapshot = state.getHarnessSnapshot();
        String effectKey = previousSnapshot == null
                ? stableReportRecoveryEffectKey(traceId, conversationId)
                : existingOrStableReportEffectKey(
                        previousSnapshot, traceId, conversationId);
        applyReportRecoverySnapshot(
                state,
                reportDecision,
                previousAttempts,
                RecoveryLifecycle.PLANNED,
                effectKey
        );
        // The invalid historical artifact must not survive the PLANNED checkpoint. If the process
        // crashes now, takeover sees a missing artifact plus the reserved Manager-only repair.
        state.setInvestmentReport(null);
        checkpointHarnessState(harnessCheckpointer, state);

        return runDebate(
                traceId,
                conversationId,
                state,
                completedRounds + 1,
                completedRounds,
                null,
                executionGuard,
                harnessCheckpointer
        );
    }

    private Map<RecoveryAction, Integer> reportRecoveryAttempts(HarnessSnapshot snapshot) {
        return snapshot == null ? Map.of() : snapshot.recoveryAttempts();
    }

    private Map<RecoveryAction, Integer> withMinimumRecoveryAttempt(
            Map<RecoveryAction, Integer> attempts,
            RecoveryAction action,
            int minimum
    ) {
        java.util.EnumMap<RecoveryAction, Integer> merged =
                new java.util.EnumMap<>(RecoveryAction.class);
        if (attempts != null) {
            merged.putAll(attempts);
        }
        merged.merge(action, Math.max(0, minimum), Math::max);
        return Map.copyOf(merged);
    }

    private Map<RecoveryAction, Integer> incrementRecoveryAttempt(
            Map<RecoveryAction, Integer> attempts,
            RecoveryAction action
    ) {
        java.util.EnumMap<RecoveryAction, Integer> incremented =
                new java.util.EnumMap<>(RecoveryAction.class);
        if (attempts != null) {
            incremented.putAll(attempts);
        }
        incremented.merge(action, 1, Integer::sum);
        return Map.copyOf(incremented);
    }

    private HarnessDecision recoveryDecisionFrom(HarnessSnapshot snapshot) {
        List<HarnessViolation> violations = snapshot.violations().stream()
                .map(code -> new HarnessViolation(code, null))
                .toList();
        if (violations.isEmpty()) {
            violations = List.of(new HarnessViolation(ViolationCode.REPORT_SCHEMA_INVALID, null));
        }
        return new HarnessDecision(
                HarnessOutcome.RECOVER,
                violations,
                List.of(RecoveryAction.RESYNTHESIZE_REPORT)
        );
    }

    private boolean hasCurrentPolicyMetadata(InvestmentReport report) {
        return report != null
                && completionPolicy.policyId().equals(report.getCompletionPolicyId())
                && Integer.valueOf(completionPolicy.policyVersion())
                .equals(report.getCompletionPolicyVersion());
    }

    private int completedDebateRounds(AnalysisState state) {
        if (state == null || state.getDebateTurns() == null) {
            return 0;
        }
        Map<Integer, EnumSet<DebateTurn.Side>> sidesByRound = new HashMap<>();
        for (DebateTurn turn : state.getDebateTurns()) {
            if (turn == null || turn.round() <= 0 || turn.side() == null) {
                continue;
            }
            sidesByRound.computeIfAbsent(
                    turn.round(),
                    ignored -> EnumSet.noneOf(DebateTurn.Side.class)
            ).add(turn.side());
        }
        int completed = 0;
        while (sidesByRound.getOrDefault(
                completed + 1,
                EnumSet.noneOf(DebateTurn.Side.class)
        ).containsAll(EnumSet.allOf(DebateTurn.Side.class))) {
            completed++;
        }
        return completed;
    }

    private void applyFinalReportSnapshot(
            AnalysisState state,
            HarnessDecision decision,
            Map<RecoveryAction, Integer> recoveryAttempts,
            HarnessSnapshot previousSnapshot,
            String traceId,
            Long conversationId
    ) {
        boolean repaired = recoveryAttempts
                .getOrDefault(RecoveryAction.RESYNTHESIZE_REPORT, 0) > 0;
        state.setHarnessSnapshot(HarnessSnapshot.recovery(
                completionPolicy.policyId(),
                Integer.toString(completionPolicy.policyVersion()),
                HarnessPhase.REPORT,
                decision,
                recoveryAttempts,
                repaired ? RecoveryLifecycle.REVALIDATED : RecoveryLifecycle.NONE,
                repaired ? List.of(RecoveryAction.RESYNTHESIZE_REPORT) : List.of(),
                repaired
                        ? previousSnapshot == null
                        ? stableReportRecoveryEffectKey(traceId, conversationId)
                        : existingOrStableReportEffectKey(
                                previousSnapshot, traceId, conversationId)
                        : ""
        ));
    }

    private boolean isReportRecoveryCheckpoint(HarnessSnapshot snapshot) {
        return snapshot != null
                && snapshot.phase() == HarnessPhase.REPORT
                && snapshot.recoveryLifecycle() == RecoveryLifecycle.PLANNED;
    }

    private boolean isRevalidatedReportMissingArtifact(
            HarnessSnapshot snapshot,
            AnalysisState state
    ) {
        return snapshot != null
                && snapshot.phase() == HarnessPhase.REPORT
                && snapshot.recoveryLifecycle() == RecoveryLifecycle.REVALIDATED
                && snapshot.recoveryAttempts()
                .getOrDefault(RecoveryAction.RESYNTHESIZE_REPORT, 0) > 0
                && (state == null || state.getInvestmentReport() == null);
    }

    private HarnessDecision failSafeMissingReportDecision(HarnessSnapshot snapshot) {
        List<HarnessViolation> violations = snapshot.violations().stream()
                .map(code -> new HarnessViolation(code, null))
                .toList();
        if (violations.isEmpty()) {
            violations = List.of(new HarnessViolation(ViolationCode.REPORT_SCHEMA_INVALID, null));
        }
        return new HarnessDecision(HarnessOutcome.DEGRADE, violations, List.of());
    }

    private void applyReportRecoverySnapshot(
            AnalysisState state,
            HarnessDecision decision,
            Map<RecoveryAction, Integer> recoveryAttempts,
            RecoveryLifecycle recoveryLifecycle,
            String recoveryEffectKey
    ) {
        state.setHarnessSnapshot(HarnessSnapshot.recovery(
                completionPolicy.policyId(),
                Integer.toString(completionPolicy.policyVersion()),
                HarnessPhase.REPORT,
                decision,
                recoveryAttempts,
                recoveryLifecycle,
                List.of(RecoveryAction.RESYNTHESIZE_REPORT),
                recoveryEffectKey
        ));
    }

    private void checkpointHarnessState(
            Consumer<AnalysisState> harnessCheckpointer,
            AnalysisState state
    ) {
        if (harnessCheckpointer != null) {
            harnessCheckpointer.accept(state);
        }
    }

    private String existingOrStableReportEffectKey(
            HarnessSnapshot snapshot,
            String traceId,
            Long conversationId
    ) {
        return snapshot.recoveryEffectKey().isBlank()
                ? stableReportRecoveryEffectKey(traceId, conversationId)
                : snapshot.recoveryEffectKey();
    }

    private String stableReportRecoveryEffectKey(String traceId, Long conversationId) {
        String runKey = traceId == null || traceId.isBlank()
                ? conversationId == null ? "unscoped" : "conversation-" + conversationId
                : traceId.strip();
        return "deep-report:"
                + runKey
                + ":"
                + completionPolicy.policyId()
                + "-v"
                + completionPolicy.policyVersion()
                + ":resynthesize_report-1";
    }

    private InvestmentReport buildNotRatedReport(
            AnalysisState state,
            HarnessDecision decision
    ) {
        List<String> violationCodes = decision == null
                ? List.of("REPORT_VALIDATION_FAILED")
                : decision.violations().stream()
                .map(violation -> violation.code().name())
                .toList();
        return InvestmentReport.builder()
                .ticker(state.getPrimaryTicker())
                .qualityStatus(InvestmentReport.ReportQualityStatus.NOT_RATED)
                .completionPolicyId(completionPolicy.policyId())
                .completionPolicyVersion(completionPolicy.policyVersion())
                .recommendation(null)
                .analystSummary("研究经理输出未通过结构或证据引用校验，本轮不生成投资评级。")
                .rationale(List.of("报告校验未通过：" + String.join(", ", violationCodes)))
                .riskFactors(List.of("关键证据与最终结论尚未形成可审计映射。"))
                .unknowns(List.of("需要重新获取或人工核验报告证据引用。"))
                .dataFreshness("本轮报告未通过完成策略验收。")
                .citations(List.of())
                .evidenceItems(List.of())
                .build();
    }

    /**
     * 把一位研究员的流式论证收集成完整文本，同时把每个 token 作为分组推理块实时推送到 SSE。
     *
     * <p>每个研究员用稳定的 {@code section} 标识，前端据此把同一研究员同一轮的连续 token
     * 聚合成一个推理块——即便 Bull/Bear 的 token 在网络上交错到达也能各自归位。</p>
     */
    private Mono<String> streamArgument(String traceId, Long conversationId,
                                        boolean bull, int round, Flux<String> tokens,
                                        Runnable executionGuard) {
        String section = "debate-" + (bull ? "bull" : "bear") + "-" + round;
        String label = (bull ? "看多方" : "看空方") + " · 第 " + round + " 轮";
        StringBuilder buffer = new StringBuilder();
        return tokens
                .doOnNext(token -> {
                    if (token != null && !token.isEmpty()) {
                        executionGuard.run();
                        buffer.append(token);
                        chatStreamEmitter.emitSection(traceId, conversationId, "thought",
                                section, label, token);
                    }
                })
                .then(Mono.fromCallable(buffer::toString));
    }

    private <T> T awaitModelStage(Mono<T> stage, String stageName, String traceId) {
        long timeoutMs = Math.max(1L, modelStageTimeoutMs);
        ModelStageTimeoutException timeout = new ModelStageTimeoutException(
                stageName,
                timeoutMs,
                traceId
        );
        return stage
                .timeout(Duration.ofMillis(timeoutMs), Mono.error(timeout))
                .doOnError(ModelStageTimeoutException.class, error ->
                        log.warn("Research model stage timed out, traceId={}, stage={}, timeoutMs={}",
                                traceId, stageName, timeoutMs))
                .block();
    }

    static final class ModelStageTimeoutException extends RuntimeException {

        ModelStageTimeoutException(String stageName, long timeoutMs, String traceId) {
            super("research model stage timed out: stage=%s, timeoutMs=%d, traceId=%s"
                    .formatted(stageName, timeoutMs, traceId == null ? "" : traceId));
        }
    }

    /**
     * 一轮辩论结束后才把双方观点写回状态，确保轮内 Bull/Bear 并行时读到的是上一轮的结果，
     * 而不是对方本轮尚未定稿的中间文本。
     *
     * <p>写入两层：</p>
     * <ul>
     *   <li>{@code debateTurns}：完整发言流水，是下一轮 Bull/Bear 看到全部历史的依据；</li>
     *   <li>{@code bullThesis / bearThesis / debateRounds}：保留向后兼容，
     *       供 ResearchManager 现有 prompt 与历史下游消费者继续使用。</li>
     * </ul>
     */
    private void applyRound(AnalysisState state, int round, String bull, String bear) {
        state.getDebateTurns().add(new DebateTurn(round, DebateTurn.Side.BULL, bull));
        state.getDebateTurns().add(new DebateTurn(round, DebateTurn.Side.BEAR, bear));
        state.setBullThesis(bull);
        state.setBearThesis(bear);
        state.getDebateRounds().add("Round " + round + " Bull:\n" + bull);
        state.getDebateRounds().add("Round " + round + " Bear:\n" + bear);
    }

    private boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 向追踪服务追加一条辩论或汇总步骤。
     *
     * <p>traceId 为空时静默跳过，使该服务既能在有前端追踪的聊天流程里使用，
     * 也能在无追踪的批处理或测试场景中复用。</p>
     */
    private void addTraceStep(String traceId, String action, String input, String observation, long startTimeMs) {
        if (traceId == null || traceId.isBlank()) {
            return;
        }
        traceService.addStep(traceId, AgentStep.builder()
                .thought(action + " completed.")
                .action(action)
                .actionInput(input)
                .observation(observation)
                .durationMs(System.currentTimeMillis() - startTimeMs)
                .tokenCount(0)
                .build());
    }
}
