package com.stocksage.agent;

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

    @Value("${stocksage.agent.debate.max-rounds:5}")
    private int maxRounds;

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
                    bullResearcher.argue(workingState, 1));
            Mono<String> bearRound1 = streamArgument(traceId, conversationId, false, 1,
                    bearResearcher.argue(workingState, 1));
            Mono<DebateRoundPlanner.RoundDecision> plannerMono = Mono.fromCallable(
                            () -> debateRoundPlanner.decide(workingState, maxRounds))
                    .subscribeOn(Schedulers.boundedElastic());

            Tuple3<String, String, DebateRoundPlanner.RoundDecision> round1 =
                    Mono.zip(bullRound1, bearRound1, plannerMono).block();

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
                    bullResearcher.argue(workingState, round));
            Mono<String> bearN = streamArgument(traceId, conversationId, false, round,
                    bearResearcher.argue(workingState, round));
            Tuple2<String, String> roundResult = Mono.zip(bullN, bearN).block();
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
        InvestmentReport report = researchManager
                .synthesizeStreaming(workingState, traceId, conversationId, chatStreamEmitter)
                .block();
        workingState.setInvestmentReport(report);
        addTraceStep(traceId, "Research Manager", "Synthesize InvestmentReport",
                report.getAnalystSummary(), managerStart);
        log.info("Research Manager completed, traceId={}, durationMs={}",
                traceId, System.currentTimeMillis() - managerStart);
        return workingState;
    }

    /**
     * 把一位研究员的流式论证收集成完整文本，同时把每个 token 作为分组推理块实时推送到 SSE。
     *
     * <p>每个研究员用稳定的 {@code section} 标识，前端据此把同一研究员同一轮的连续 token
     * 聚合成一个推理块——即便 Bull/Bear 的 token 在网络上交错到达也能各自归位。</p>
     */
    private Mono<String> streamArgument(String traceId, Long conversationId,
                                        boolean bull, int round, Flux<String> tokens) {
        String section = "debate-" + (bull ? "bull" : "bear") + "-" + round;
        String label = (bull ? "看多方" : "看空方") + " · 第 " + round + " 轮";
        StringBuilder buffer = new StringBuilder();
        return tokens
                .doOnNext(token -> {
                    if (token != null && !token.isEmpty()) {
                        buffer.append(token);
                        chatStreamEmitter.emitSection(traceId, conversationId, "thought",
                                section, label, token);
                    }
                })
                .then(Mono.fromCallable(buffer::toString));
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
