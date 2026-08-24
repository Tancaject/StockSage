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
import com.stocksage.model.dto.DebateModels.AssessmentParseStatus;
import com.stocksage.model.dto.DebateModels.DebateTurn;
import com.stocksage.model.dto.DebateModels.DebateVerdict;
import com.stocksage.model.dto.DebateModels.LeadingSide;
import com.stocksage.model.dto.DebateModels.ManagerAssessment;
import com.stocksage.model.dto.DebateModels.Side;
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
import java.util.concurrent.atomic.AtomicBoolean;
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

    /** 调用方未提供执行权检查器时使用的无操作实现。 */
    private static final Runnable NO_OP_EXECUTION_GUARD = () -> {
    };

    /** 每轮完成后的可选持久化钩子，由后台研究任务保存可恢复状态。 */
    @FunctionalInterface
    public interface RoundCheckpointer {

        /** 每轮双方定稿后保存完整状态，避免恢复时重烧已经完成的模型轮次。 */
        void onRoundCompleted(AnalysisState state, int roundsCompleted, int plannedRounds);
    }

    /** 只基于现有证据生成看多论证。 */
    private final BullResearcher bullResearcher;
    /** 只基于现有证据生成看空论证。 */
    private final BearResearcher bearResearcher;
    /** 把分析师与辩论证据综合为结构化报告。 */
    private final ResearchManager researchManager;
    /** 把模型 JSON 严格转换为带 Evidence ID 的结构化论点。 */
    private final DebateContractParser debateContractParser;
    /** 根据 Manager 逐项评分确定性计算胜方和评级。 */
    private final DebateDecisionPolicy debateDecisionPolicy;
    /** 用轻量模型选择 1 到配置上限的辩论轮数。 */
    private final DebateRoundPlanner debateRoundPlanner;
    /** 持久化每个规划、辩论和综合步骤。 */
    private final TraceService traceService;
    /** 把逐 token 辩论与恢复提示写入 SSE/Trace 事件流。 */
    private final ChatStreamEmitter chatStreamEmitter;
    /** 在报告阶段调用纯完成策略并记录决策。 */
    private final ResearchHarness researchHarness;
    /** DEEP 证据/报告的权威完成规则。 */
    private final DeepResearchCompletionPolicy completionPolicy;

    /** 可配置辩论轮数上限，RoundPlanner 还会硬限制到五轮。 */
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

    /**
     * 从指定轮次运行或恢复完整辩论、报告综合与报告 Harness。
     *
     * <p>首次运行会并行执行 Round 1 的 Bull、Bear 和轮次规划；恢复运行严格沿用 checkpoint 的
     * {@code fixedPlannedRounds}，不重新规划历史。每轮完成后才写回状态并 checkpoint，随后执行
     * Research Manager、最多一次报告修复和 NOT_RATED 安全降级。</p>
     *
     * @param traceId 当前任务链路 ID；为空时跳过 Trace/SSE
     * @param conversationId 会话 ID
     * @param state 已包含分析师报告、证据账本和可选历史轮次的状态
     * @param startRound 首个待执行轮次；首次运行传 1
     * @param fixedPlannedRounds 恢复时的既定总轮数；首次规划时传非正数
     * @param checkpointer 每轮双方定稿后的 checkpoint 回调
     * @param executionGuard 每个模型 token 前的非阻塞执行权检查器
     * @param harnessCheckpointer Harness 生命周期与报告产物的原子持久化回调
     * @return 原状态对象，已补充辩论、报告和 Harness 快照
     */
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

            List<DebateTurn> parsedRound;
            try {
                parsedRound = applyRound(
                        workingState, 1, round1.getT1(), round1.getT2());
            } catch (DebateContractParser.DebateContractException error) {
                return failClosedDebate(
                        workingState,
                        traceId,
                        conversationId,
                        harnessCheckpointer,
                        ViolationCode.DEBATE_CONTRACT_INVALID,
                        "辩论结构或证据引用未通过契约校验：" + error.code().name(),
                        round1Start
                );
            }
            addTraceStep(traceId, "Bull Researcher", "Round 1",
                    renderTurn(parsedRound.get(0)), round1Start);
            addTraceStep(traceId, "Bear Researcher", "Round 1",
                    renderTurn(parsedRound.get(1)), round1Start);

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
            List<DebateTurn> parsedRound;
            try {
                parsedRound = applyRound(
                        workingState, round, roundResult.getT1(), roundResult.getT2());
            } catch (DebateContractParser.DebateContractException error) {
                return failClosedDebate(
                        workingState,
                        traceId,
                        conversationId,
                        harnessCheckpointer,
                        ViolationCode.DEBATE_CONTRACT_INVALID,
                        "辩论结构或证据引用未通过契约校验：" + error.code().name(),
                        roundStart
                );
            }
            if (checkpointer != null) {
                checkpointer.onRoundCompleted(workingState, round, rounds);
            }
            addTraceStep(traceId, "Bull Researcher", "Round " + round,
                    renderTurn(parsedRound.get(0)), roundStart);
            addTraceStep(traceId, "Bear Researcher", "Round " + round,
                    renderTurn(parsedRound.get(1)), roundStart);
        }

        // Research Manager 改为流式：自然语言综合判断逐 token 推送到推理面板（"manager-synthesis" 分组），
        // JSON 部分在后端 buffer 直至流结束再解析为结构化 InvestmentReport，
        // 让用户在 Manager 思考期间持续看到中文综合判断生成而非静默等待。
        long managerStart = System.currentTimeMillis();
        log.info("Research Manager started (streaming), traceId={}", traceId);
        HarnessSnapshot checkpointSnapshot = workingState.getHarnessSnapshot();
        // REVALIDATED 却缺报告说明历史 checkpoint 不完整；失败关闭为 NOT_RATED，绝不重新评级。
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

        DebateVerdict verdict = currentOrScoreVerdict(
                workingState,
                traceId,
                conversationId,
                guard,
                harnessCheckpointer,
                managerStart
        );
        ManagerAssessment managerAssessment = workingState.getManagerAssessment();
        if (managerAssessment == null
                || managerAssessment.parseStatus() != AssessmentParseStatus.VALID
                || !managerAssessment.issues().isEmpty()) {
            return failClosedDebate(
                    workingState,
                    traceId,
                    conversationId,
                    harnessCheckpointer,
                    ViolationCode.DEBATE_ASSESSMENT_INVALID,
                    "研究经理逐论点评分未通过结构校验。",
                    managerStart
            );
        }
        if (verdict.leadingSide() == LeadingSide.INSUFFICIENT) {
            return failClosedDebate(
                    workingState,
                    traceId,
                    conversationId,
                    harnessCheckpointer,
                    ViolationCode.DEBATE_DECISION_INSUFFICIENT,
                    "有效根论点或证据不足，本轮不生成投资评级。",
                    managerStart
            );
        }

        boolean resumingReportRecovery = isReportRecoveryCheckpoint(checkpointSnapshot);
        Map<RecoveryAction, Integer> reportRecoveryAttempts =
                reportRecoveryAttempts(checkpointSnapshot);
        if (resumingReportRecovery) {
            // PLANNED 已预留唯一一次 Manager 修复预算，恢复时必须延续同一 effect key。
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
                        workingState, verdict, traceId, conversationId, chatStreamEmitter)
                : researchManager.synthesizeStreamingResult(
                        workingState, verdict, traceId, conversationId, chatStreamEmitter, guard);
        SynthesisResult synthesisResult = awaitModelStage(
                synthesis,
                "manager-synthesis",
                traceId
        );
        // ResearchManager 只负责生成；是否允许评级由 Harness 第二阶段独立决定。
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
                            workingState, verdict, traceId, conversationId, chatStreamEmitter)
                    : researchManager.synthesizeStreamingResult(
                            workingState, verdict, traceId, conversationId, chatStreamEmitter, guard);
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
            // 决策与对应报告一起 checkpoint，接管者不能只看到 REVALIDATED 却拿不到被验收的产物。
            checkpointHarnessState(harnessCheckpointer, workingState);
        }
        addTraceStep(traceId, "Research Manager", "Synthesize InvestmentReport",
                report.getAnalystSummary(), managerStart);
        log.info("Research Manager completed, traceId={}, durationMs={}",
                traceId, System.currentTimeMillis() - managerStart);
        return workingState;
    }

    /**
     * 用当前完成策略和证据账本重新验收持久化报告。
     *
     * <p>checkpoint 中的 {@code VERIFIED} 只是历史数据，不是当前权威。若产物不再通过，本方法可消费
     * 唯一一次报告修复预算，但不会重放 Bull/Bear。只有完整双方发言证明至少完成一轮时才允许修复；
     * 否则失败关闭为 NOT_RATED。</p>
     *
     * @param traceId 当前任务链路 ID
     * @param conversationId 会话 ID
     * @param state 从 checkpoint 恢复的分析状态
     * @param executionGuard Manager token 前的执行权检查器
     * @param harnessCheckpointer 保存最新 Harness/报告的回调
     * @return 重新验收、修复或安全降级后的状态
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

        // 持久化 PLANNED 已预留唯一一次 Manager 修复；旧 writer 留下的陈旧产物不能取消该副作用。
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

        boolean currentVerdict = debateDecisionPolicy.isCurrentVerdict(
                workingState, checkpointedReport.getDecisionAudit());
        HarnessDecision reportDecision = researchHarness.evaluateReport(
                traceId,
                completionPolicy,
                new RunContext("DEEP", previousAttempts),
                workingState.getEvidenceLedger(),
                new SynthesisResult(
                        checkpointedReport,
                        hasCurrentPolicyMetadata(checkpointedReport) && currentVerdict
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

    /**
     * 执行 checkpoint 中已计划或当前验收新触发的唯一一次 Manager 修复。
     *
     * <p>修复前先持久化 PLANNED 并清空无效旧报告；崩溃接管后仍会沿用同一 effect key。</p>
     */
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
        // 无效历史报告不能留在 PLANNED checkpoint；此处崩溃时，接管者看到“缺产物 + 已预留修复”。
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

    /** 从快照读取报告恢复预算；无快照时返回空不可变表。 */
    private Map<RecoveryAction, Integer> reportRecoveryAttempts(HarnessSnapshot snapshot) {
        return snapshot == null ? Map.of() : snapshot.recoveryAttempts();
    }

    /** 恢复 PLANNED 副作用时确保对应预算至少记为已使用一次。 */
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

    /** 在不可变副本中增加指定恢复动作次数。 */
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

    /** 从持久化 PLANNED 快照重建本次报告修复决策。 */
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

    /** @return 报告是否由当前 ID/版本的完成策略验收 */
    private boolean hasCurrentPolicyMetadata(InvestmentReport report) {
        return report != null
                && completionPolicy.policyId().equals(report.getCompletionPolicyId())
                && Integer.valueOf(completionPolicy.policyVersion())
                .equals(report.getCompletionPolicyVersion());
    }

    /**
     * 计算从 Round 1 开始连续、且 Bull/Bear 双方都已完成的轮数。
     *
     * <p>只完成单方或中间缺轮都不算，防止基于残缺辩论直接修复报告。</p>
     */
    private int completedDebateRounds(AnalysisState state) {
        if (state == null || state.getDebateTurns() == null) {
            return 0;
        }
        Map<Integer, EnumSet<Side>> sidesByRound = new HashMap<>();
        for (DebateTurn turn : state.getDebateTurns()) {
            if (turn == null || turn.round() <= 0 || turn.side() == null
                    || turn.points() == null || turn.points().isEmpty()) {
                continue;
            }
            sidesByRound.computeIfAbsent(
                    turn.round(),
                    ignored -> EnumSet.noneOf(Side.class)
            ).add(turn.side());
        }
        int completed = 0;
        while (sidesByRound.getOrDefault(
                completed + 1,
                EnumSet.noneOf(Side.class)
        ).containsAll(EnumSet.allOf(Side.class))) {
            completed++;
        }
        return completed;
    }

    /** 把最终报告决策写回 HarnessSnapshot，并保留已执行修复的稳定 effect key。 */
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

    /** @return 快照是否表示尚待执行/重验的报告修复 */
    private boolean isReportRecoveryCheckpoint(HarnessSnapshot snapshot) {
        return snapshot != null
                && snapshot.phase() == HarnessPhase.REPORT
                && snapshot.recoveryLifecycle() == RecoveryLifecycle.PLANNED;
    }

    /** @return 快照声称修复已重验、但实际报告产物缺失的故障状态 */
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

    /** 为“REVALIDATED 但缺产物”构造 NOT_RATED 的失败关闭决策。 */
    private HarnessDecision failSafeMissingReportDecision(HarnessSnapshot snapshot) {
        List<HarnessViolation> violations = snapshot.violations().stream()
                .map(code -> new HarnessViolation(code, null))
                .toList();
        if (violations.isEmpty()) {
            violations = List.of(new HarnessViolation(ViolationCode.REPORT_SCHEMA_INVALID, null));
        }
        return new HarnessDecision(HarnessOutcome.DEGRADE, violations, List.of());
    }

    /** 写入报告修复的 PLANNED 或 REVALIDATED 生命周期快照。 */
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

    /** 调用可选持久化回调；无后台任务/测试场景可不提供。 */
    private void checkpointHarnessState(
            Consumer<AnalysisState> harnessCheckpointer,
            AnalysisState state
    ) {
        if (harnessCheckpointer != null) {
            harnessCheckpointer.accept(state);
        }
    }

    /** 优先复用 checkpoint 中的 effect key，否则按本次运行生成稳定键。 */
    private String existingOrStableReportEffectKey(
            HarnessSnapshot snapshot,
            String traceId,
            Long conversationId
    ) {
        return snapshot.recoveryEffectKey().isBlank()
                ? stableReportRecoveryEffectKey(traceId, conversationId)
                : snapshot.recoveryEffectKey();
    }

    /**
     * 为单次 DEEP 运行的唯一报告修复生成稳定逻辑副作用键。
     *
     * <p>键不包含用户正文，供崩溃接管识别“同一次修复”。</p>
     */
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

    /** 根据 Harness 违规构造无 recommendation、无引用的安全 NOT_RATED 报告。 */
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
                .dataSnapshotHash(state.getDataSnapshotHash())
                .contextHash(state.getContextHash())
                .decisionAudit(debateDecisionPolicy.isCurrentVerdict(state)
                        ? state.getDebateVerdict()
                        : null)
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

    /** 收集研究员完整输出；只展示 JSON 边界前的摘要，结构化契约留在后端解析。 */
    private Mono<String> streamArgument(String traceId, Long conversationId,
                                         boolean bull, int round, Flux<String> tokens,
                                         Runnable executionGuard) {
        String section = "debate-" + (bull ? "bull" : "bear") + "-" + round;
        String label = (bull ? "看多方" : "看空方") + " · 第 " + round + " 轮";
        StringBuilder buffer = new StringBuilder();
        AtomicBoolean jsonStarted = new AtomicBoolean(false);
        return tokens
                .doOnNext(token -> {
                    if (token == null || token.isEmpty()) {
                        return;
                    }
                    executionGuard.run();
                    int previousLength = buffer.length();
                    buffer.append(token);
                    if (jsonStarted.get()) {
                        return;
                    }
                    int fenceIndex = buffer.indexOf("```");
                    int braceIndex = buffer.indexOf("{");
                    int jsonAt = fenceIndex >= 0 && (braceIndex < 0 || fenceIndex < braceIndex)
                            ? fenceIndex
                            : braceIndex;
                    if (jsonAt < 0) {
                        chatStreamEmitter.emitSection(traceId, conversationId, "thought",
                                section, label, token);
                        return;
                    }
                    if (jsonAt > previousLength) {
                        String visiblePart = token.substring(0, jsonAt - previousLength);
                        if (!visiblePart.isEmpty()) {
                            chatStreamEmitter.emitSection(traceId, conversationId, "thought",
                                    section, label, visiblePart);
                        }
                    }
                    jsonStarted.set(true);
                })
                .then(Mono.fromCallable(buffer::toString));
    }

    /**
     * 为一个模型阶段施加执行层硬超时并同步等待结果。
     *
     * @param stage Bull/Bear/Planner/Manager 的组合 Mono
     * @param stageName 日志使用的稳定阶段名
     * @param traceId 当前链路 ID
     * @return 阶段结果
     * @throws ModelStageTimeoutException 超过配置硬截止时间
     */
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

    /** 模型阶段超过执行层硬截止时间时抛出的稳定异常。 */
    static final class ModelStageTimeoutException extends RuntimeException {

        ModelStageTimeoutException(String stageName, long timeoutMs, String traceId) {
            super("research model stage timed out: stage=%s, timeoutMs=%d, traceId=%s"
                    .formatted(stageName, timeoutMs, traceId == null ? "" : traceId));
        }
    }

    /** 双方都通过严格 JSON/证据契约后，才原子追加本轮结构化论点。 */
    private List<DebateTurn> applyRound(
            AnalysisState state,
            int round,
            String bull,
            String bear
    ) {
        DebateTurn bullTurn = debateContractParser.parse(bull, round, Side.BULL, state);
        DebateTurn bearTurn = debateContractParser.parse(bear, round, Side.BEAR, state);
        state.getDebateTurns().add(bullTurn);
        state.getDebateTurns().add(bearTurn);
        // 新轮次改变评分输入；checkpoint 不得携带与当前辩论不匹配的旧评分或旧裁决。
        state.setManagerAssessment(null);
        state.setDebateVerdict(null);
        return List.of(bullTurn, bearTurn);
    }

    /**
     * 复用仍绑定当前输入的裁决；否则只调用一次 Manager 评分，再由 Java 策略锁定结果并 checkpoint。
     */
    private DebateVerdict currentOrScoreVerdict(
            AnalysisState state,
            String traceId,
            Long conversationId,
            Runnable executionGuard,
            Consumer<AnalysisState> harnessCheckpointer,
            long startedAt
    ) {
        if (debateDecisionPolicy.isCurrentVerdict(state)) {
            DebateVerdict current = state.getDebateVerdict();
            addTraceStep(
                    traceId,
                    "Research Manager Scoring",
                    "Reuse current assessment",
                    verdictTraceSummary(current, state.getManagerAssessment()),
                    startedAt
            );
            return current;
        }

        String inputHash = debateDecisionPolicy.computeInputHash(state);
        ManagerAssessment assessment = awaitModelStage(
                researchManager.scoreDebate(state, inputHash, executionGuard),
                "manager-scoring",
                traceId
        );
        state.setManagerAssessment(assessment);
        DebateVerdict verdict = debateDecisionPolicy.decide(state, assessment);
        state.setDebateVerdict(verdict);
        // 评分与裁决必须先于报告生成落盘；崩溃恢复时相同输入直接复用，不重复打分。
        checkpointHarnessState(harnessCheckpointer, state);
        addTraceStep(
                traceId,
                "Research Manager Scoring",
                "Anonymous per-thesis assessment",
                verdictTraceSummary(verdict, assessment),
                startedAt
        );
        chatStreamEmitter.emit(
                traceId,
                conversationId,
                "observation",
                verdict.leadingSide() == LeadingSide.INSUFFICIENT
                        ? "逐论点评分完成，但有效证据不足，本轮不评级。"
                        : "逐论点评分完成，确定性决策已锁定，正在生成研究报告。"
        );
        return verdict;
    }

    /** Trace 只记录有限状态、分数和原因代码，不记录模型隐藏推理。 */
    private String verdictTraceSummary(
            DebateVerdict verdict,
            ManagerAssessment assessment
    ) {
        if (verdict == null) {
            return "verdict=missing";
        }
        List<String> reasonCodes = assessment == null
                ? List.of()
                : assessment.assessments().stream()
                .flatMap(item -> item.reasonCodes().stream())
                .map(Enum::name)
                .distinct()
                .sorted()
                .toList();
        return "parseStatus=%s; bullScore=%.2f; bearScore=%.2f; leadingSide=%s; "
                .concat("recommendation=%s; reasonCodes=%s")
                .formatted(
                        assessment == null ? "MISSING" : assessment.parseStatus(),
                        verdict.bullScore(),
                        verdict.bearScore(),
                        verdict.leadingSide(),
                        verdict.recommendation(),
                        reasonCodes
                );
    }

    /** 对辩论契约、Manager 评分或有效证据不足执行统一失败关闭。 */
    private AnalysisState failClosedDebate(
            AnalysisState state,
            String traceId,
            Long conversationId,
            Consumer<AnalysisState> harnessCheckpointer,
            ViolationCode violationCode,
            String userMessage,
            long startedAt
    ) {
        if (violationCode == ViolationCode.DEBATE_CONTRACT_INVALID) {
            state.setManagerAssessment(null);
            state.setDebateVerdict(null);
        }
        HarnessDecision decision = new HarnessDecision(
                HarnessOutcome.DEGRADE,
                List.of(new HarnessViolation(violationCode, null)),
                List.of(RecoveryAction.RETURN_NOT_RATED)
        );
        InvestmentReport notRated = buildNotRatedReport(state, decision);
        notRated.setAnalystSummary(userMessage);
        notRated.setRationale(List.of(userMessage));
        state.setInvestmentReport(notRated);
        HarnessSnapshot previousSnapshot = state.getHarnessSnapshot();
        applyFinalReportSnapshot(
                state,
                decision,
                reportRecoveryAttempts(previousSnapshot),
                previousSnapshot,
                traceId,
                conversationId
        );
        checkpointHarnessState(harnessCheckpointer, state);
        chatStreamEmitter.emit(traceId, conversationId, "observation", userMessage);
        String traceAction = violationCode == ViolationCode.DEBATE_CONTRACT_INVALID
                ? "Research Debate Contract"
                : "Research Manager Scoring";
        addTraceStep(
                traceId,
                traceAction,
                violationCode.name(),
                userMessage,
                startedAt
        );
        log.warn("Research debate failed closed, traceId={}, violation={}",
                traceId, violationCode);
        return state;
    }

    /** Trace 只保存结构化论点摘要，不复制模型的自由文本前言或内部 JSON。 */
    private String renderTurn(DebateTurn turn) {
        if (turn == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        turn.points().forEach(point -> builder.append('[').append(point.pointId()).append("] ")
                .append(point.type()).append(" | ").append(point.claim())
                .append(" | evidenceIds=")
                .append(point.evidenceRefs().stream()
                        .map(ref -> ref.evidenceId())
                        .distinct()
                        .toList())
                .append(" | respondsTo=").append(point.respondsToPointIds())
                .append('\n'));
        return builder.toString().strip();
    }

    /** @return 文本非空且非纯空白 */
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
