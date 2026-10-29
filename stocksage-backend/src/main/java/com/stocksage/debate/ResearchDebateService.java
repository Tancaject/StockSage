package com.stocksage.debate;

import com.stocksage.exception.ResearchBudgetExceededException;
import com.stocksage.exception.ResearchCapacityExceededException;

import com.stocksage.agent.runtime.AgentStep;

import com.stocksage.debate.DebateModels.AssessmentParseStatus;
import com.stocksage.debate.DebateModels.DebateTurn;
import com.stocksage.debate.DebateModels.DebateVerdict;
import com.stocksage.debate.DebateModels.LeadingSide;
import com.stocksage.debate.DebateModels.ManagerAssessment;
import com.stocksage.debate.DebateModels.Side;
import com.stocksage.harness.DeepResearchCompletionPolicy;
import com.stocksage.harness.HarnessModels.HarnessDecision;
import com.stocksage.harness.HarnessModels.HarnessOutcome;
import com.stocksage.harness.HarnessModels.HarnessSnapshot;
import com.stocksage.harness.HarnessModels.HarnessViolation;
import com.stocksage.harness.HarnessModels.RecoveryAction;
import com.stocksage.harness.HarnessModels.RunContext;
import com.stocksage.harness.HarnessModels.SynthesisResult;
import com.stocksage.harness.HarnessModels.ViolationCode;
import com.stocksage.harness.ResearchHarness;
import com.stocksage.research.deep.AnalysisState;
import com.stocksage.research.report.InvestmentReport;
import com.stocksage.tool.ChatStreamEmitter;
import com.stocksage.trace.TraceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;


import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 编排深度研究中的多空辩论循环。
 *
 * <p>分析师报告已经写入 AnalysisState。本服务负责生成多头和空头论证、执行 Research Manager 的续停决策、
 * 记录链路步骤，并调用 ResearchManager 生成最终结构化报告。</p>
 *
 * <p>为压缩深度研究的端到端耗时，辩论按如下方式调度：</p>
 * <ul>
 *   <li>每一轮内 Bull 与 Bear <b>并行</b>生成（“同时交卷”式辩论），轮间仍串行，
 *       使第 2 轮起双方都能读到上一轮对方的完整观点；</li>
 *   <li>每轮定稿后按争点（第一轮根论点及其反驳链）判断：到达配置上限或没有仍在交锋的争点就停止；
 *       否则由 Research Manager 标出各交锋争点能否改变结论，存在可推进且可能翻转结论的争点才继续，
 *       判断期间推测启动下一轮，判断为停止就取消；</li>
 *   <li>供应商临时错误时该次调用重发一次；发言或评分未通过结构校验时带问题清单修正一次；</li>
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

        /** 每轮双方和续停决策定稿后保存完整状态，第三个参数是当前授权到的轮次上界。 */
        void onRoundCompleted(AnalysisState state, int roundsCompleted, int authorizedThroughRound);
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
    /** 持久化每个辩论、续停和综合步骤。 */
    private final TraceService traceService;
    /** 把逐 token 辩论与恢复提示写入 SSE/Trace 事件流。 */
    private final ChatStreamEmitter chatStreamEmitter;
    /** 在报告阶段调用纯完成策略并记录决策。 */
    private final ResearchHarness researchHarness;
    /** DEEP 证据/报告的权威完成规则。 */
    private final DeepResearchCompletionPolicy completionPolicy;
    /** 报告阶段的 Harness 快照、修复预算与 NOT_RATED 降级。 */
    private final ReportPhaseState reportPhase;

    /** 可配置辩论轮数硬上限；实际轮数由争点状态和续停判断决定。 */
    @Value("${stocksage.agent.debate.max-rounds:8}")
    private int maxRounds = 8;

    /** SINGLE：一次评完；COMPARE：另按 Position A/B 拆两路并行评分，只记录对比，不参与裁决。 */
    @Value("${stocksage.agent.debate.scoring-mode:SINGLE}")
    private String scoringMode = "SINGLE";

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
     * 从指定轮次运行或恢复完整辩论、报告综合与报告 Harness。
     *
     * <p>首次运行直接执行 Round 1 的 Bull/Bear；此后每轮由 Research Manager 根据完整辩论
     * 自适应判断 CONTINUE/STOP。恢复运行只沿用 checkpoint 已持久化的单轮授权，不重新判断历史决策。
     * 每轮双方和续停决策完成后才写回 checkpoint，随后执行
     * Research Manager、最多一次报告修复和 NOT_RATED 安全降级。</p>
     *
     * @param traceId 当前任务链路 ID；为空时跳过 Trace/SSE
     * @param conversationId 会话 ID
     * @param state 已包含分析师报告、证据账本和可选历史轮次的状态
     * @param startRound 首个待执行轮次；首次运行传 1
     * @param authorizedThroughRound checkpoint 当前授权到的轮次；首次运行传 0
     * @param checkpointer 每轮双方和续停决策定稿后的 checkpoint 回调
     * @param executionGuard 每个模型 token 前的非阻塞执行权检查器
     * @param harnessCheckpointer Harness 生命周期与报告产物的原子持久化回调
     * @return 原状态对象，已补充辩论、报告和 Harness 快照
     */
    public AnalysisState runDebate(
            String traceId,
            Long conversationId,
            AnalysisState state,
            int startRound,
            int authorizedThroughRound,
            RoundCheckpointer checkpointer,
            Runnable executionGuard,
            Consumer<AnalysisState> harnessCheckpointer
    ) {
        Runnable guard = executionGuard == null ? NO_OP_EXECUTION_GUARD : executionGuard;
        AnalysisState workingState = state == null ? AnalysisState.builder().build() : state;

        AnalysisState failedClosed = runDebateRounds(traceId, conversationId, workingState, startRound,
                authorizedThroughRound, checkpointer, guard, harnessCheckpointer);
        if (failedClosed != null) {
            return failedClosed;
        }
        return synthesizeReport(traceId, conversationId, workingState, guard, harnessCheckpointer);
    }

    /**
     * 逐轮运行 Bull/Bear 并做续停判断，直到 STOP、硬上限或授权轮次用尽。
     *
     * @return 发言契约修正后仍不合格时失败关闭的状态；正常结束返回 null
     */
    private AnalysisState runDebateRounds(
            String traceId,
            Long conversationId,
            AnalysisState workingState,
            int startRound,
            int authorizedThroughRound,
            RoundCheckpointer checkpointer,
            Runnable guard,
            Consumer<AnalysisState> harnessCheckpointer
    ) {
        int cappedMaxRounds = Math.max(1, maxRounds);
        int firstRound = Math.max(1, startRound);
        int currentAuthorization = Math.max(0, authorizedThroughRound);
        log.info("Research Debate started or resumed, traceId={}, startRound={}, "
                        + "authorizedThroughRound={}, hardLimit={}",
                traceId, firstRound, currentAuthorization, cappedMaxRounds);

        // 首轮总会执行；恢复后的后续轮次必须已经由上一轮 Manager 决策授权。
        PendingRound pending = null;
        try {
            for (int round = firstRound;
                 round <= cappedMaxRounds && (round == 1 || round <= currentAuthorization);
                 round++) {
                // 上一轮已推测启动的本轮直接复用；否则现在开始。
                PendingRound current = pending != null && pending.round() == round
                        ? pending
                        : startRound(traceId, conversationId, workingState, round, guard, false);
                pending = null;
                long roundStart = current.startedAt();
                Tuple2<String, String> roundResult = awaitModelStage(
                        Mono.fromFuture(current.result()),
                        "debate-round-" + round,
                        traceId
                );
                AtomicInteger contractRepairs = new AtomicInteger();
                List<DebateTurn> parsedRound;
                try {
                    parsedRound = parseRoundWithRepair(workingState, round, roundResult.getT1(), roundResult.getT2(),
                            traceId, conversationId, guard, current.retries(), contractRepairs, roundStart);
                } catch (DebateContractParser.DebateContractException error) {
                    return failClosedDebate(
                            workingState,
                            traceId,
                            conversationId,
                            harnessCheckpointer,
                            ViolationCode.DEBATE_CONTRACT_INVALID,
                            "辩论结构或证据引用未通过契约校验：" + error.getMessage(),
                            roundStart
                    );
                }
                addTraceStep(traceId, "Bull Researcher", "Round " + round,
                        renderTurn(parsedRound.get(0)), roundStart);
                addTraceStep(traceId, "Bear Researcher", "Round " + round,
                        renderTurn(parsedRound.get(1)), roundStart);
                long roundGeneratedAt = System.currentTimeMillis();

                int newEvidence = DebateReferences.newEvidenceIds(workingState.getDebateTurns(), round).size();
                List<DebateReferences.Crux> cruxes = DebateReferences.cruxes(workingState.getDebateTurns(), round);
                boolean anyOpenCrux = cruxes.stream()
                        .anyMatch(crux -> crux.status() == DebateReferences.CruxStatus.OPEN);
                boolean hardLimitReached = round >= cappedMaxRounds;
                ResearchManager.DebateContinuationDecision continuation;
                String decisionSource;
                String nextRoundSpeculation = "NONE";
                if (hardLimitReached) {
                    continuation = new ResearchManager.DebateContinuationDecision(
                            ResearchManager.DebateContinuation.STOP,
                            "已达到服务端辩论硬上限 " + cappedMaxRounds + " 轮。"
                    );
                    decisionSource = "HARD_LIMIT";
                } else if (!anyOpenCrux) {
                    continuation = new ResearchManager.DebateContinuationDecision(
                            ResearchManager.DebateContinuation.STOP,
                            "第 " + round + " 轮后没有仍在交锋的争点：各争点已无人继续回应或在原地打转。"
                    );
                    decisionSource = "NO_OPEN_CRUX";
                } else {
                    // 下一轮与续停判断同时开始；确认继续前不向前端推送，判断停止就取消。
                    pending = startRound(traceId, conversationId, workingState, round + 1, guard, true);
                    int completedRound = round;
                    try {
                        continuation = awaitModelStage(
                                Mono.defer(() -> researchManager.decideDebateContinuation(
                                                workingState, completedRound, cappedMaxRounds, guard))
                                        .retryWhen(transientRetry(traceId, conversationId,
                                                "续停判断", new AtomicInteger(), null)),
                                "manager-debate-control",
                                traceId
                        );
                        decisionSource = "MODEL";
                    } catch (RuntimeException error) {
                        ResearchBudgetExceededException.rethrowIfPresent(error);
                        ResearchCapacityExceededException.rethrowIfPresent(error);
                        rethrowIfOwnershipLost(error);
                        // 续停只是控制决策：失败时按确定性规则处理，不让整场辩论失败关闭。
                        log.warn("Research Manager continuation decision failed, using fallback, traceId={}, round={}, errorType={}",
                                traceId, round, error.getClass().getSimpleName());
                        continuation = round == 1
                                ? new ResearchManager.DebateContinuationDecision(ResearchManager.DebateContinuation.CONTINUE,
                                "续停判断失败；开局陈述后至少进行一轮反驳。")
                                : new ResearchManager.DebateContinuationDecision(ResearchManager.DebateContinuation.STOP,
                                "续停判断失败；按已完成的辩论进入评分。");
                        decisionSource = "FALLBACK";
                    }
                    if (continuation.decision() == ResearchManager.DebateContinuation.CONTINUE) {
                        pending.gate().release();
                        nextRoundSpeculation = "CONFIRMED";
                    } else {
                        pending.result().cancel(true);
                        pending.gate().discard();
                        pending = null;
                        nextRoundSpeculation = "CANCELLED";
                    }
                }

                currentAuthorization = continuation.decision()
                        == ResearchManager.DebateContinuation.CONTINUE
                        ? round + 1
                        : round;
                if (checkpointer != null) {
                    checkpointer.onRoundCompleted(workingState, round, currentAuthorization);
                }
                Map<String, Object> roundAttributes = new java.util.LinkedHashMap<>();
                roundAttributes.put("kind", "debate-round");
                roundAttributes.put("round", round);
                roundAttributes.put("maxRounds", cappedMaxRounds);
                roundAttributes.put("generationMs", roundGeneratedAt - roundStart);
                roundAttributes.put("decisionMs", System.currentTimeMillis() - roundGeneratedAt);
                roundAttributes.put("newEvidence", newEvidence);
                roundAttributes.put("citedEvidence", DebateReferences.citedEvidenceIds(workingState.getDebateTurns()).size());
                roundAttributes.put("usableEvidence", DebateReferences.usableEvidenceCount(workingState));
                roundAttributes.put("decision", continuation.decision().name());
                roundAttributes.put("decisionSource", decisionSource);
                Map<String, String> pointAliases = DebateReferences.sideAliases(workingState.getDebateTurns());
                Map<String, Object> cruxStates = new java.util.LinkedHashMap<>();
                for (DebateReferences.Crux crux : cruxes) {
                    String alias = DebateReferences.alias(crux.thesis().pointId(), pointAliases);
                    ResearchManager.CruxImpact impact = continuation.cruxImpacts().get(alias);
                    cruxStates.put(alias, impact == null ? crux.status().name() : crux.status() + "/" + impact);
                }
                roundAttributes.put("cruxes", cruxStates);
                roundAttributes.put("provisionalLeaning", continuation.provisionalLeaning());
                roundAttributes.put("nextRoundSpeculation", nextRoundSpeculation);
                roundAttributes.put("transientRetries", current.retries().get());
                roundAttributes.put("contractRepairs", contractRepairs.get());
                addTraceStep(
                        traceId,
                        hardLimitReached ? "Research Debate Hard Limit" : "Research Manager Debate Control",
                        "Round " + round + " / max=" + cappedMaxRounds,
                        "decision=" + continuation.decision() + "; source=" + decisionSource
                                + "; reason=" + continuation.reason(),
                        roundStart,
                        roundAttributes
                );
                chatStreamEmitter.emit(
                        traceId,
                        conversationId,
                        "observation",
                        continuation.decision() == ResearchManager.DebateContinuation.CONTINUE
                                ? "Research Manager 判断仍有可能改变结论的争点，继续下一轮反驳。"
                                : "进入评分与报告阶段。"
                );
                log.info("Research Debate round completed, traceId={}, round={}, decision={}, source={}, newEvidence={}, reason={}",
                        traceId, round, continuation.decision(), decisionSource, newEvidence, continuation.reason());
                if (continuation.decision() == ResearchManager.DebateContinuation.STOP) {
                    break;
                }
            }
        } finally {
            // 异常或提前返回时，已推测启动但未被采用的下一轮必须取消，避免占用模型调用。
            if (pending != null) {
                pending.result().cancel(true);
                pending.gate().discard();
            }
        }
        return null;
    }

    /** 辩论结束后：逐论点评分并锁定裁决，再综合报告、做报告 Harness 验收和最多一次修复。 */
    private AnalysisState synthesizeReport(
            String traceId,
            Long conversationId,
            AnalysisState workingState,
            Runnable guard,
            Consumer<AnalysisState> harnessCheckpointer
    ) {
        long managerStart = System.currentTimeMillis();
        log.info("Research Manager started (streaming), traceId={}", traceId);
        HarnessSnapshot checkpointSnapshot = workingState.getHarnessSnapshot();
        // 修复已完成却缺报告说明历史 checkpoint 不完整；失败关闭为 NOT_RATED，绝不重新评级。
        if (reportPhase.isRevalidatedReportMissingArtifact(checkpointSnapshot, workingState)) {
            HarnessDecision failSafeDecision = reportPhase.failSafeMissingReportDecision(checkpointSnapshot);
            InvestmentReport notRated = reportPhase.buildNotRatedReport(workingState, failSafeDecision);
            workingState.setInvestmentReport(reportPhase.withCoverageUnknowns(workingState, notRated));
            reportPhase.applyFinalReportSnapshot(
                    workingState,
                    failSafeDecision,
                    reportPhase.reportRecoveryAttempts(checkpointSnapshot)
            );
            reportPhase.checkpointHarnessState(harnessCheckpointer, workingState);
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

        boolean resumingReportRecovery = reportPhase.isReportRecoveryCheckpoint(checkpointSnapshot);
        Map<RecoveryAction, Integer> reportRecoveryAttempts =
                reportPhase.reportRecoveryAttempts(checkpointSnapshot);
        if (resumingReportRecovery) {
            // 待执行修复的预算已在落盘时计入；旧 checkpoint 未计入时补成 1。
            reportRecoveryAttempts = reportPhase.withMinimumRecoveryAttempt(
                    reportRecoveryAttempts,
                    RecoveryAction.RESYNTHESIZE_REPORT,
                    1
            );
        }
        if (resumingReportRecovery) {
            chatStreamEmitter.emit(traceId, conversationId, "observation",
                    "从报告断点恢复：正在执行既定的一次重新综合并重新验收。");
        }
        SynthesisResult synthesisResult = awaitModelStage(
                synthesis(workingState, verdict, traceId, conversationId, guard),
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
            reportPhase.applyFinalReportSnapshot(
                    workingState,
                    reportDecision,
                    reportRecoveryAttempts
            );
            checkpointRevalidatedReport = true;
        } else if (reportDecision.outcome() == HarnessOutcome.RECOVER
                && reportDecision.recoveryActions().contains(RecoveryAction.RESYNTHESIZE_REPORT)) {
            workingState.setReportRepairFeedback(researchManager.reportRepairFeedback(synthesisResult));
            Map<RecoveryAction, Integer> completedReportRecoveryAttempts =
                    reportPhase.incrementRecoveryAttempt(
                            reportRecoveryAttempts,
                            RecoveryAction.RESYNTHESIZE_REPORT
                    );
            // 先落盘已计数的修复，再调用模型；崩溃接管后只会执行这同一次修复。
            reportPhase.applyReportRecoverySnapshot(
                    workingState,
                    reportDecision,
                    completedReportRecoveryAttempts
            );
            reportPhase.checkpointHarnessState(harnessCheckpointer, workingState);
            chatStreamEmitter.emit(traceId, conversationId, "observation",
                    "报告结构或证据引用未通过校验，正在重新综合一次。");
            synthesisResult = awaitModelStage(
                    synthesis(workingState, verdict, traceId, conversationId, guard),
                    "manager-repair",
                    traceId
            );
            reportDecision = researchHarness.evaluateReport(
                    traceId,
                    completionPolicy,
                    new RunContext("DEEP", completedReportRecoveryAttempts),
                    workingState.getEvidenceLedger(),
                    synthesisResult
            );
            reportPhase.applyFinalReportSnapshot(
                    workingState,
                    reportDecision,
                    completedReportRecoveryAttempts
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
            report = reportPhase.buildNotRatedReport(workingState, reportDecision);
        }
        workingState.setInvestmentReport(reportPhase.withCoverageUnknowns(workingState, report));
        if (checkpointRevalidatedReport) {
            // 决策与对应报告一起 checkpoint，接管者不能只看到修复已完成却拿不到被验收的产物。
            reportPhase.checkpointHarnessState(harnessCheckpointer, workingState);
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
                reportPhase.reportRecoveryAttempts(previousSnapshot);

        // 辩论契约或 Manager 决策已经失败关闭时，报告修复不得把 NOT_RATED 重新变成评级。
        if (reportPhase.hasFailClosedDebateViolation(previousSnapshot)) {
            HarnessDecision failClosed = new HarnessDecision(
                    HarnessOutcome.DEGRADE,
                    previousSnapshot.violations().stream()
                            .map(code -> new HarnessViolation(code, null))
                            .toList(),
                    List.of(RecoveryAction.RETURN_NOT_RATED)
            );
            InvestmentReport notRated = checkpointedReport;
            if (checkpointedReport.getQualityStatus()
                    != InvestmentReport.ReportQualityStatus.NOT_RATED
                    || checkpointedReport.getRecommendation() != null) {
                notRated = reportPhase.buildNotRatedReport(workingState, failClosed);
            }
            notRated.setQualityStatus(InvestmentReport.ReportQualityStatus.NOT_RATED);
            notRated.setRecommendation(null);
            notRated.setCompletionPolicyId(completionPolicy.policyId());
            notRated.setCompletionPolicyVersion(completionPolicy.policyVersion());
            workingState.setInvestmentReport(reportPhase.withCoverageUnknowns(workingState, notRated));
            reportPhase.applyFinalReportSnapshot(
                    workingState,
                    failClosed,
                    previousAttempts
            );
            reportPhase.checkpointHarnessState(harnessCheckpointer, workingState);
            return workingState;
        }

        // 已落盘的待修复快照占用了唯一一次 Manager 修复；旧 writer 留下的陈旧产物不能取消它。
        if (reportPhase.isReportRecoveryCheckpoint(previousSnapshot)) {
            HarnessDecision plannedDecision = reportPhase.recoveryDecisionFrom(previousSnapshot);
            return executeCheckpointedReportRepair(
                    traceId,
                    conversationId,
                    workingState,
                    plannedDecision,
                    reportPhase.withMinimumRecoveryAttempt(previousAttempts, RecoveryAction.RESYNTHESIZE_REPORT, 1),
                    executionGuard,
                    harnessCheckpointer
            );
        }

        boolean currentVerdict = debateDecisionPolicy.isCurrentVerdict(
                workingState, checkpointedReport.getDecisionAudit());
        SynthesisResult checkpointedSynthesis = new SynthesisResult(
                checkpointedReport,
                reportPhase.hasCurrentPolicyMetadata(checkpointedReport) && currentVerdict
                        ? com.stocksage.harness.HarnessModels.ParseStatus.VALID
                        : com.stocksage.harness.HarnessModels.ParseStatus.INVALID_SCHEMA,
                currentVerdict ? List.of() : List.of("decisionAudit")
        );
        HarnessDecision reportDecision = researchHarness.evaluateReport(
                traceId,
                completionPolicy,
                new RunContext("DEEP", previousAttempts),
                workingState.getEvidenceLedger(),
                checkpointedSynthesis
        );

        if (reportDecision.outcome() == HarnessOutcome.PASS) {
            checkpointedReport.setQualityStatus(InvestmentReport.ReportQualityStatus.VERIFIED);
            checkpointedReport.setCompletionPolicyId(completionPolicy.policyId());
            checkpointedReport.setCompletionPolicyVersion(completionPolicy.policyVersion());
            workingState.setInvestmentReport(reportPhase.withCoverageUnknowns(workingState, checkpointedReport));
            reportPhase.applyFinalReportSnapshot(
                    workingState,
                    reportDecision,
                    previousAttempts
            );
            reportPhase.checkpointHarnessState(harnessCheckpointer, workingState);
            return workingState;
        }

        if (reportDecision.outcome() == HarnessOutcome.RECOVER
                && reportDecision.recoveryActions()
                .contains(RecoveryAction.RESYNTHESIZE_REPORT)) {
            workingState.setReportRepairFeedback(researchManager.reportRepairFeedback(checkpointedSynthesis));
            return executeCheckpointedReportRepair(
                    traceId,
                    conversationId,
                    workingState,
                    reportDecision,
                    reportPhase.incrementRecoveryAttempt(previousAttempts, RecoveryAction.RESYNTHESIZE_REPORT),
                    executionGuard,
                    harnessCheckpointer
            );
        }

        workingState.setInvestmentReport(reportPhase.withCoverageUnknowns(workingState, reportPhase.buildNotRatedReport(workingState, reportDecision)));
        reportPhase.applyFinalReportSnapshot(
                workingState,
                reportDecision,
                previousAttempts
        );
        reportPhase.checkpointHarnessState(harnessCheckpointer, workingState);
        return workingState;
    }

    /**
     * 执行 checkpoint 中已计划或当前验收新触发的唯一一次 Manager 修复。
     *
     * <p>修复前先持久化已计数的 RECOVER 快照并清空无效旧报告；崩溃接管后继续这同一次修复。</p>
     */
    private AnalysisState executeCheckpointedReportRepair(
            String traceId,
            Long conversationId,
            AnalysisState state,
            HarnessDecision reportDecision,
            Map<RecoveryAction, Integer> repairAttempts,
            Runnable executionGuard,
            Consumer<AnalysisState> harnessCheckpointer
    ) {
        int completedRounds = reportPhase.completedDebateRounds(state);
        if (completedRounds <= 0) {
            HarnessDecision failClosed = new HarnessDecision(
                    HarnessOutcome.DEGRADE,
                    reportDecision.violations(),
                    List.of(RecoveryAction.RETURN_NOT_RATED)
            );
            state.setInvestmentReport(reportPhase.withCoverageUnknowns(state, reportPhase.buildNotRatedReport(state, failClosed)));
            reportPhase.applyFinalReportSnapshot(
                    state,
                    failClosed,
                    repairAttempts
            );
            reportPhase.checkpointHarnessState(harnessCheckpointer, state);
            log.warn("Checkpointed report failed validation without completed debate rounds; "
                    + "returning NOT_RATED, traceId={}", traceId);
            return state;
        }

        reportPhase.applyReportRecoverySnapshot(
                state,
                reportDecision,
                repairAttempts
        );
        // 无效历史报告不能留在待修复 checkpoint；此处崩溃时，接管者看到“缺产物 + 已计数修复”。
        state.setInvestmentReport(null);
        reportPhase.checkpointHarnessState(harnessCheckpointer, state);

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

    /** 一轮 Bull/Bear 的进行中结果；推测执行的轮次在确认继续前，推送都被 gate 截住。 */
    private record PendingRound(int round, CompletableFuture<Tuple2<String, String>> result,
                                StreamGate gate, long startedAt, AtomicInteger retries) {
    }

    /** 同时开始一轮 Bull/Bear；speculative 为真时推送先被截住。toFuture 立即订阅，调用随即发出。 */
    private PendingRound startRound(String traceId, Long conversationId, AnalysisState state, int round,
                                    Runnable guard, boolean speculative) {
        log.info("Research Debate round started, traceId={}, round={}, speculative={}", traceId, round, speculative);
        StreamGate gate = new StreamGate(!speculative);
        AtomicInteger retries = new AtomicInteger();
        Mono<String> bull = argument(traceId, conversationId, true, round, state, null, guard, gate, retries);
        Mono<String> bear = argument(traceId, conversationId, false, round, state, null, guard, gate, retries);
        return new PendingRound(round, Mono.zip(bull, bear).toFuture(), gate, System.currentTimeMillis(), retries);
    }

    /**
     * 一方的一次发言：提示词在订阅时才构造；供应商临时错误时整段重发一次，另一方不受影响。
     *
     * @param repairNote 契约修正说明；首次生成传 null
     */
    private Mono<String> argument(String traceId, Long conversationId, boolean bull, int round, AnalysisState state,
                                  String repairNote, Runnable guard, StreamGate gate, AtomicInteger retries) {
        AtomicInteger attempt = new AtomicInteger();
        String role = (bull ? "看多方" : "看空方") + "第 " + round + " 轮";
        return Mono.defer(() -> {
                    int current = attempt.incrementAndGet();
                    Flux<String> tokens = bull
                            ? bullResearcher.argue(state, round, repairNote)
                            : bearResearcher.argue(state, round, repairNote);
                    String sectionSuffix = (repairNote == null ? "" : "-fix") + (current > 1 ? "-r" + current : "");
                    String labelNote = (repairNote == null ? "" : "（修正）") + (current > 1 ? "（重试）" : "");
                    return streamArgument(traceId, conversationId, bull, round, tokens, guard, gate,
                            sectionSuffix, labelNote);
                })
                .retryWhen(transientRetry(traceId, conversationId, role, retries, gate));
    }

    /** 只对供应商临时错误重试一次；耗尽后抛出原始异常，保持上层原有的异常处理。 */
    private reactor.util.retry.Retry transientRetry(String traceId, Long conversationId, String label,
                                                    AtomicInteger retries, StreamGate gate) {
        return reactor.util.retry.Retry.max(1)
                .filter(TransientModelErrors::isTransient)
                .doBeforeRetry(signal -> {
                    retries.incrementAndGet();
                    log.warn("Research model call failed transiently, retrying once, traceId={}, stage={}, errorType={}",
                            traceId, label, signal.failure().getClass().getSimpleName());
                    Runnable notice = () -> chatStreamEmitter.emit(traceId, conversationId, "observation",
                            label + "的模型输出中断，正在重试一次。");
                    if (gate == null) {
                        notice.run();
                    } else {
                        gate.dropPending();
                        gate.emit(notice);
                    }
                })
                .onRetryExhaustedThrow((spec, signal) -> signal.failure());
    }

    /** 执行权已被其他 worker 接管时不能降级或重试，原样抛出。 */
    private static void rethrowIfOwnershipLost(RuntimeException error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause.getClass().getSimpleName().equals("OwnershipLostException")) {
                throw error;
            }
            if (cause.getCause() == cause) {
                return;
            }
        }
    }

    /** 收集研究员完整输出；只展示 JSON 边界前的摘要，结构化契约留在后端解析。 */
    private Mono<String> streamArgument(String traceId, Long conversationId,
                                         boolean bull, int round, Flux<String> tokens,
                                         Runnable executionGuard, StreamGate gate,
                                         String sectionSuffix, String labelNote) {
        String section = "debate-" + (bull ? "bull" : "bear") + "-" + round + sectionSuffix;
        String label = (bull ? "看多方" : "看空方") + " · 第 " + round + " 轮" + labelNote;
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
                        gate.emit(() -> chatStreamEmitter.emitSection(traceId, conversationId, "thought",
                                section, label, token));
                        return;
                    }
                    if (jsonAt > previousLength) {
                        String visiblePart = token.substring(0, jsonAt - previousLength);
                        if (!visiblePart.isEmpty()) {
                            gate.emit(() -> chatStreamEmitter.emitSection(traceId, conversationId, "thought",
                                    section, label, visiblePart));
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

    /**
     * 解析双方本轮发言；结构不合格的一方带着校验问题重新生成一次，修正后仍不合格才失败关闭。
     * 双方都通过后才原子追加本轮论点。
     */
    private List<DebateTurn> parseRoundWithRepair(
            AnalysisState state,
            int round,
            String bull,
            String bear,
            String traceId,
            Long conversationId,
            Runnable guard,
            AtomicInteger retries,
            AtomicInteger contractRepairs,
            long roundStart
    ) {
        DebateContractParser.ParsedTurn bullParsed = null;
        DebateContractParser.ParsedTurn bearParsed = null;
        DebateContractParser.DebateContractException bullError = null;
        DebateContractParser.DebateContractException bearError = null;
        try {
            bullParsed = debateContractParser.parseTurn(bull, round, Side.BULL, state);
        } catch (DebateContractParser.DebateContractException error) {
            bullError = error;
        }
        try {
            bearParsed = debateContractParser.parseTurn(bear, round, Side.BEAR, state);
        } catch (DebateContractParser.DebateContractException error) {
            bearError = error;
        }
        if (bullError != null || bearError != null) {
            List<String> notes = new ArrayList<>();
            if (bullError != null) {
                notes.add("Bull: " + bullError.getMessage());
                logInvalidTurn(traceId, round, "Bull", bull, bullError);
            }
            if (bearError != null) {
                notes.add("Bear: " + bearError.getMessage());
                logInvalidTurn(traceId, round, "Bear", bear, bearError);
            }
            addTraceStep(traceId, "Research Debate Contract Repair", "Round " + round + " regenerate",
                    String.join("\n", notes), roundStart);
            chatStreamEmitter.emit(traceId, conversationId, "observation",
                    "第 " + round + " 轮有发言未通过结构校验，正在按问题清单重新生成一次。");
            StreamGate open = new StreamGate(true);
            Mono<String> bullFix = bullError == null ? Mono.just("")
                    : argument(traceId, conversationId, true, round, state,
                    "契约校验错误：" + bullError.getMessage(), guard, open, retries);
            Mono<String> bearFix = bearError == null ? Mono.just("")
                    : argument(traceId, conversationId, false, round, state,
                    "契约校验错误：" + bearError.getMessage(), guard, open, retries);
            Tuple2<String, String> fixed = awaitModelStage(Mono.zip(bullFix, bearFix), "debate-repair-" + round, traceId);
            // 修正后仍不合格时由调用方失败关闭。
            if (bullError != null) {
                contractRepairs.incrementAndGet();
                bullParsed = debateContractParser.parseTurn(fixed.getT1(), round, Side.BULL, state);
            }
            if (bearError != null) {
                contractRepairs.incrementAndGet();
                bearParsed = debateContractParser.parseTurn(fixed.getT2(), round, Side.BEAR, state);
            }
        }
        return applyRound(state, round, bullParsed, bearParsed, traceId, roundStart);
    }

    /** 契约失败时在日志留下输出开头，便于定位；原文不进 Trace。 */
    private void logInvalidTurn(String traceId, int round, String side, String output,
                                DebateContractParser.DebateContractException error) {
        String raw = output == null ? "" : output;
        log.warn("Research debate turn failed contract validation, traceId={}, round={}, side={}, error={}, outputHead={}",
                traceId, round, side, error.getMessage(),
                raw.substring(0, Math.min(raw.length(), 1500)).replace('\n', ' '));
    }

    /** 双方都通过严格 JSON/证据契约后，才原子追加本轮结构化论点。 */
    private List<DebateTurn> applyRound(
            AnalysisState state,
            int round,
            DebateContractParser.ParsedTurn bullParsed,
            DebateContractParser.ParsedTurn bearParsed,
            String traceId,
            long roundStart
    ) {
        DebateTurn bullTurn = bullParsed.turn();
        DebateTurn bearTurn = bearParsed.turn();
        List<String> repairs = new ArrayList<>();
        bullParsed.repairs().forEach(repair -> repairs.add("Bull " + repair));
        bearParsed.repairs().forEach(repair -> repairs.add("Bear " + repair));
        if (!repairs.isEmpty()) {
            // 记录被删除的无效反驳引用；保留下来的论点均通过契约校验。
            addTraceStep(traceId, "Research Debate Contract Repair", "Round " + round,
                    String.join("\n", repairs), roundStart);
        }
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
        long scoringStart = System.currentTimeMillis();
        AtomicLong splitFinishedAt = new AtomicLong();
        AtomicInteger splitRepairs = new AtomicInteger();
        // 对比模式下拆分评分与正式评分同时进行，只记录对比，不影响裁决。
        CompletableFuture<ManagerAssessment> splitScoring = "COMPARE".equalsIgnoreCase(scoringMode)
                ? Mono.zip(splitHalf(state, inputHash, executionGuard, "A", traceId, conversationId, splitRepairs),
                        splitHalf(state, inputHash, executionGuard, "B", traceId, conversationId, splitRepairs))
                .map(halves -> ResearchManager.mergeAssessments(halves.getT1(), halves.getT2()))
                .doOnSuccess(ignored -> splitFinishedAt.set(System.currentTimeMillis()))
                .toFuture()
                : null;
        ManagerAssessment assessment;
        long singleScoringMs;
        try {
            assessment = awaitModelStage(
                    scoring(state, inputHash, executionGuard, null, null, traceId, conversationId),
                    "manager-scoring",
                    traceId
            );
            singleScoringMs = System.currentTimeMillis() - scoringStart;
            if (!isValidAssessment(assessment)) {
                addTraceStep(traceId, "Research Manager Scoring Repair", "Retry with validation issues",
                        "parseStatus=" + assessment.parseStatus() + "; issues(" + assessment.issues().size() + ")="
                                + assessment.issues().stream().limit(20).toList(), startedAt);
                chatStreamEmitter.emit(traceId, conversationId, "observation",
                        "逐论点评分未通过结构校验，正在按问题清单修正一次。");
                assessment = awaitModelStage(
                        scoring(state, inputHash, executionGuard, null, assessment.issues(), traceId, conversationId),
                        "manager-scoring-repair",
                        traceId
                );
            }
        } catch (RuntimeException error) {
            if (splitScoring != null) {
                splitScoring.cancel(true);
            }
            throw error;
        }
        state.setManagerAssessment(assessment);
        DebateVerdict verdict = debateDecisionPolicy.decide(state, assessment);
        if (splitScoring != null) {
            recordScoringComparison(state, traceId, verdict, assessment, singleScoringMs, splitScoring, scoringStart, splitFinishedAt, splitRepairs);
        }
        state.setDebateVerdict(verdict);
        // 评分与裁决必须先于报告生成落盘；崩溃恢复时相同输入直接复用，不重复打分。
        reportPhase.checkpointHarnessState(harnessCheckpointer, state);
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

    /** 报告综合；供应商临时错误沿用既定重试。 */
    private Mono<SynthesisResult> synthesis(AnalysisState state, DebateVerdict verdict, String traceId,
                                            Long conversationId, Runnable guard) {
        return Mono.defer(() -> researchManager.synthesizeStreamingResult(state, verdict, guard))
                .retryWhen(transientRetry(traceId, conversationId, "Research Manager 报告", new AtomicInteger(), null));
    }

    /** 一次评分调用（可只评一个位置、可带修正问题），供应商临时错误时重发一次。 */
    private Mono<ManagerAssessment> scoring(AnalysisState state, String inputHash, Runnable guard,
                                            String onlyPosition, List<String> repairIssues,
                                            String traceId, Long conversationId) {
        return Mono.defer(() -> researchManager.scoreDebate(state, inputHash, guard, onlyPosition, repairIssues))
                .retryWhen(transientRetry(traceId, conversationId, "逐论点评分", new AtomicInteger(), null));
    }

    private static boolean isValidAssessment(ManagerAssessment assessment) {
        return assessment != null && assessment.parseStatus() == AssessmentParseStatus.VALID
                && assessment.issues().isEmpty();
    }

    /**
     * 记录"一次评完"与"按位置拆两路并行"的对比：裁决是否一致、每个根论点总分差和耗时。
     * 拆分结果不写入 state，失败只记一条诊断。
     */
    /** 拆分评分的一半，与正式评分一样不合格时带问题清单修正一次，对比才公平。 */
    private Mono<ManagerAssessment> splitHalf(AnalysisState state, String inputHash, Runnable executionGuard,
                                              String position, String traceId, Long conversationId,
                                              AtomicInteger splitRepairs) {
        return scoring(state, inputHash, executionGuard, position, null, traceId, conversationId)
                .flatMap(half -> {
                    if (isValidAssessment(half)) {
                        return Mono.just(half);
                    }
                    splitRepairs.incrementAndGet();
                    return scoring(state, inputHash, executionGuard, position, half.issues(), traceId, conversationId);
                });
    }

    private void recordScoringComparison(AnalysisState state, String traceId, DebateVerdict singleVerdict,
                                         ManagerAssessment single, long singleScoringMs,
                                         CompletableFuture<ManagerAssessment> splitScoring, long scoringStart,
                                         AtomicLong splitFinishedAt, AtomicInteger splitRepairs) {
        Map<String, Object> attributes = new java.util.LinkedHashMap<>();
        attributes.put("kind", "scoring-comparison");
        attributes.put("singleValid", isValidAssessment(single));
        attributes.put("singleMs", singleScoringMs);
        attributes.put("singleLeadingSide", singleVerdict.leadingSide().name());
        attributes.put("singleRecommendation", String.valueOf(singleVerdict.recommendation()));
        attributes.put("singleBullScore", singleVerdict.bullScore());
        attributes.put("singleBearScore", singleVerdict.bearScore());
        String observation;
        try {
            ManagerAssessment split = awaitModelStage(Mono.fromFuture(splitScoring), "manager-scoring-split", traceId);
            // 拆分评分可能比一次评分先结束，耗时按它自己的完成时刻算。
            long splitMs = splitFinishedAt.get() - scoringStart;
            DebateVerdict splitVerdict = debateDecisionPolicy.decide(state, split);
            Map<String, Integer> singleTotals = new HashMap<>();
            single.assessments().forEach(item -> singleTotals.put(item.pointId(), totalScore(item)));
            int maxDiff = 0;
            int compared = 0;
            for (var item : split.assessments()) {
                Integer base = singleTotals.get(item.pointId());
                if (base != null) {
                    compared++;
                    maxDiff = Math.max(maxDiff, Math.abs(base - totalScore(item)));
                }
            }
            attributes.put("splitValid", isValidAssessment(split));
            attributes.put("splitMs", splitMs);
            attributes.put("splitRepairs", splitRepairs.get());
            attributes.put("splitLeadingSide", splitVerdict.leadingSide().name());
            attributes.put("splitRecommendation", String.valueOf(splitVerdict.recommendation()));
            attributes.put("splitBullScore", splitVerdict.bullScore());
            attributes.put("splitBearScore", splitVerdict.bearScore());
            attributes.put("comparedTheses", compared);
            attributes.put("maxThesisTotalDiff", maxDiff);
            attributes.put("sameLeadingSide", splitVerdict.leadingSide() == singleVerdict.leadingSide());
            attributes.put("sameRecommendation",
                    java.util.Objects.equals(splitVerdict.recommendation(), singleVerdict.recommendation()));
            observation = "single=" + singleVerdict.leadingSide() + "/" + singleVerdict.recommendation()
                    + " " + singleScoringMs + "ms; split=" + splitVerdict.leadingSide() + "/" + splitVerdict.recommendation()
                    + " " + splitMs + "ms; maxThesisTotalDiff=" + maxDiff;
        } catch (RuntimeException error) {
            ResearchBudgetExceededException.rethrowIfPresent(error);
            ResearchCapacityExceededException.rethrowIfPresent(error);
            rethrowIfOwnershipLost(error);
            attributes.put("splitError", error.getClass().getSimpleName());
            observation = "split scoring failed: " + error.getClass().getSimpleName();
        }
        addTraceStep(traceId, "Research Manager Scoring Comparison", "single vs split by position",
                observation, scoringStart, attributes);
    }

    private static int totalScore(com.stocksage.debate.DebateModels.ArgumentAssessment item) {
        return item.evidenceSupport() + item.questionRelevance() + item.logicalCoherence()
                + item.rebuttalSurvival() + item.uncertaintyHandling();
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
        // 评分无效时把结构校验的具体问题写进 Trace，否则失败关闭后无从定位。
        List<String> issues = assessment == null ? List.of() : assessment.issues();
        return "parseStatus=%s; bullScore=%.2f; bearScore=%.2f; leadingSide=%s; "
                .concat("recommendation=%s; reasonCodes=%s")
                .formatted(
                        assessment == null ? "MISSING" : assessment.parseStatus(),
                        verdict.bullScore(),
                        verdict.bearScore(),
                        verdict.leadingSide(),
                        verdict.recommendation(),
                        reasonCodes
                ) + (issues.isEmpty() ? "" : "; issues(" + issues.size() + ")="
                        + issues.stream().limit(20).toList());
    }

    /** 对辩论契约、Manager 决策/评分或有效证据不足执行统一失败关闭。 */
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
        InvestmentReport notRated = reportPhase.buildNotRatedReport(state, decision);
        notRated.setAnalystSummary(userMessage);
        notRated.setRationale(List.of(userMessage));
        state.setInvestmentReport(reportPhase.withCoverageUnknowns(state, notRated));
        HarnessSnapshot previousSnapshot = state.getHarnessSnapshot();
        reportPhase.applyFinalReportSnapshot(
                state,
                decision,
                reportPhase.reportRecoveryAttempts(previousSnapshot)
        );
        reportPhase.checkpointHarnessState(harnessCheckpointer, state);
        chatStreamEmitter.emit(traceId, conversationId, "observation", userMessage);
        String traceAction = violationCode == ViolationCode.DEBATE_CONTRACT_INVALID
                ? "Research Debate Contract"
                : "Research Manager Assessment";
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

    /**
     * 向追踪服务追加一条辩论或汇总步骤。
     *
     * <p>traceId 为空时静默跳过，使该服务既能在有前端追踪的聊天流程里使用，
     * 也能在无追踪的批处理或测试场景中复用。</p>
     */
    private void addTraceStep(String traceId, String action, String input, String observation, long startTimeMs) {
        addTraceStep(traceId, action, input, observation, startTimeMs, null);
    }

    /** @param attributes 供评测汇总的结构化指标，例如每轮新证据数与续停来源 */
    private void addTraceStep(String traceId, String action, String input, String observation, long startTimeMs,
                              Map<String, Object> attributes) {
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
                .attributes(attributes)
                .build());
    }
}
