package com.stocksage.debate;

import static com.stocksage.util.PromptText.untrustedContext;

import static com.stocksage.debate.ManagerJson.NO_OP_EXECUTION_GUARD;
import static com.stocksage.debate.ManagerJson.appendUnknownFields;
import static com.stocksage.debate.ManagerJson.readStrictJson;
import static com.stocksage.debate.ManagerJson.readStrictText;
import static com.stocksage.debate.ManagerJson.truncate;

import com.stocksage.agent.runtime.ModelInvocationAdvisor;
import com.stocksage.debate.DebateModels.ArgumentAssessment;
import com.stocksage.debate.DebateModels.AssessmentParseStatus;
import com.stocksage.debate.DebateModels.DebateVerdict;
import com.stocksage.debate.DebateModels.ManagerAssessment;
import com.stocksage.harness.HarnessModels.ReportRepairFeedback;
import com.stocksage.harness.HarnessModels.SynthesisResult;
import com.stocksage.research.deep.AnalysisState;
import com.stocksage.util.JsonText;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 逐轮控制多空辩论、做匿名逐项语义评分，并把 Java 锁定裁决解释为结构化 {@link InvestmentReport} 的研究经理。
 *
 * <p>上游 {@link ResearchDebateService} 提供已固定的 {@link AnalysisState}；本类要求模型先输出
 * 可流式展示的中文判断，再输出严格 JSON，并把报告证据 ID 确定性绑定回 {@link EvidenceLedger}。
 * Manager 的续停阶段只输出 CONTINUE/STOP，评分阶段不计算总分、胜方或 recommendation；
 * 下游 Java 策略与 Harness 决定是否可评级。
 * 本类不调用新工具，也不会接受账本外来源支撑结论。</p>
 */
@Slf4j
@Service
public class ResearchManager {

    private static final Set<String> CONTINUATION_ROOT_FIELDS = Set.of("provisionalLeaning", "cruxes", "reason");
    private static final Set<String> CRUX_FIELDS = Set.of("id", "impact");
    private static final Set<String> LEANINGS = Set.of("BULL", "BEAR", "BALANCED");

    /** 无工具、低温度的逐轮续停决策客户端。 */
    private final ChatClient continuationChatClient;
    /** 匿名逐论点评分。 */
    private final ManagerScoring scoring;
    /** 报告综合与证据绑定。 */
    private final ReportSynthesizer reportSynthesizer;
    /** 解析模型返回的结构化 JSON。 */
    private final ObjectMapper objectMapper;

    /**
     * 注入研究经理专用 ChatClient 和 JSON 解析器。
     *
     * <p>研究经理输出以 JSON 为契约，因此 ObjectMapper 是把模型文本落到结构化 DTO 的关键依赖。</p>
     */
    public ResearchManager(
                           @Qualifier("researchManagerContinuationChatClient") ChatClient continuationChatClient,
                           @Qualifier("researchManagerScoringChatClient") ChatClient scoringChatClient,
                           @Qualifier("researchManagerChatClient") ChatClient reportChatClient,
                           ObjectMapper objectMapper) {
        this.continuationChatClient = continuationChatClient;
        this.scoring = new ManagerScoring(scoringChatClient, objectMapper);
        this.reportSynthesizer = new ReportSynthesizer(reportChatClient, objectMapper);
        this.objectMapper = objectMapper;
    }

    /** Research Manager 对下一轮的有限控制动作。 */
    public enum DebateContinuation {
        CONTINUE,
        STOP
    }

    /** OPEN 争点对结论的影响，由 Manager 判断；状态本身由后端统计。 */
    public enum CruxImpact {
        /** 走向可能改变当前倾向，且下一轮能用现有证据推进。 */
        CAN_FLIP,
        /** 可能改变倾向，但取决于现有证据之外的信息。 */
        NEEDS_EXTERNAL,
        /** 无论走向如何都不改变倾向。 */
        NO_IMPACT
    }

    /**
     * 每轮完成后的续停决策。模型给出时，decision 由后端按“是否存在 CAN_FLIP 的 OPEN 争点”得出；
     * cruxImpacts 以短编号为键，provisionalLeaning 只用于续停判断，不进入裁决。
     */
    public record DebateContinuationDecision(
            DebateContinuation decision,
            String reason,
            String provisionalLeaning,
            Map<String, CruxImpact> cruxImpacts
    ) {
        public DebateContinuationDecision(DebateContinuation decision, String reason) {
            this(decision, reason, null, Map.of());
        }
    }

    /**
     * 基于当前完整辩论逐轮判断 CONTINUE/STOP；空输出、未知动作和额外字段均失败关闭。
     */
    public Mono<DebateContinuationDecision> decideDebateContinuation(
            AnalysisState state,
            int completedRound,
            int maxRounds,
            Runnable executionGuard
    ) {
        Runnable guard = executionGuard == null ? NO_OP_EXECUTION_GUARD : executionGuard;
        StringBuilder buffer = new StringBuilder();
        Map<String, String> aliases = DebateReferences.sideAliases(state.getDebateTurns());
        List<DebateReferences.Crux> cruxes = DebateReferences.cruxes(state.getDebateTurns(), completedRound);
        Set<String> openAliases = cruxes.stream()
                .filter(crux -> crux.status() == DebateReferences.CruxStatus.OPEN)
                .map(crux -> DebateReferences.alias(crux.thesis().pointId(), aliases))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        var request = continuationChatClient.prompt()
                .user(buildContinuationPrompt(state, completedRound, maxRounds, cruxes, aliases));
        if (state.getModelInvocationContext() != null) {
            request.advisors(a -> a.param(ModelInvocationAdvisor.CONTEXT_KEY, state.getModelInvocationContext()));
        }
        return request.stream()
                .content()
                .doOnNext(token -> {
                    guard.run();
                    if (token != null) {
                        buffer.append(token);
                    }
                })
                .then(Mono.fromCallable(() -> {
                    guard.run();
                    return parseContinuationDecision(buffer.toString(), openAliases);
                }));
    }

    /**
     * 对全部第一轮根论点逐项评分，不输出胜方、总分或 recommendation。
     *
     * <p>Position A/B 的映射由输入哈希稳定决定，使不同任务在两个位置上均衡分布，恢复时又能
     * 复现同一输入。模型输出只在后端缓冲并解析，不向前端展示内部评分 JSON。</p>
     */
    public Mono<ManagerAssessment> scoreDebate(
            AnalysisState state,
            String inputHash,
            Runnable executionGuard
    ) {
        return scoreDebate(state, inputHash, executionGuard, null, null);
    }

    /**
     * @param onlyPosition 只为 "A" 或 "B" 位置评分（拆分评分）；null 表示全部根论点
     * @param repairIssues 上一次评分的结构校验问题；首次评分传 null
     */
    public Mono<ManagerAssessment> scoreDebate(
            AnalysisState state,
            String inputHash,
            Runnable executionGuard,
            String onlyPosition,
            List<String> repairIssues
    ) {
        return scoring.scoreDebate(state, inputHash, executionGuard, onlyPosition, repairIssues);
    }

    /** 合并拆分评分的两半；任一半无效则整体无效，问题清单合并。 */
    public static ManagerAssessment mergeAssessments(ManagerAssessment positionA, ManagerAssessment positionB) {
        List<ArgumentAssessment> merged = new ArrayList<>(positionA.assessments());
        merged.addAll(positionB.assessments());
        List<String> issues = new ArrayList<>(positionA.issues());
        issues.addAll(positionB.issues());
        AssessmentParseStatus status = positionA.parseStatus() != AssessmentParseStatus.VALID ? positionA.parseStatus()
                : positionB.parseStatus() != AssessmentParseStatus.VALID ? positionB.parseStatus()
                : issues.isEmpty() ? AssessmentParseStatus.VALID : AssessmentParseStatus.INVALID_SCHEMA;
        return new ManagerAssessment(positionA.contractId(), positionA.version(), positionA.inputHash(),
                positionA.positionAIsBull(), merged, status, issues);
    }

    /**
     * 流式综合并返回报告解析状态。
     *
     * <p>JSON 在流结束后完整解析；通过报告验收后再由后端渲染，避免展示未验收的模型文本。</p>
     *
     * @param state 已准备好的研究状态
     * @param executionGuard 每个 token 前的执行权检查器
     * @return 报告、解析状态和字段问题
     */
    public Mono<SynthesisResult> synthesizeStreamingResult(
            AnalysisState state,
            DebateVerdict verdict,
            Runnable executionGuard
    ) {
        return reportSynthesizer.synthesizeStreamingResult(state, verdict, executionGuard);
    }

    /** 已解析的历史报告没有原始模型文本，只摘取本次需要修复的报告字段。 */
    public ReportRepairFeedback reportRepairFeedback(SynthesisResult synthesis) {
        return reportSynthesizer.reportRepairFeedback(synthesis);
    }

    /** 构造逐轮续停决策提示词。 */
    private String buildContinuationPrompt(
            AnalysisState state,
            int completedRound,
            int maxRounds,
            List<DebateReferences.Crux> cruxes,
            Map<String, String> aliases
    ) {
        StringBuilder table = new StringBuilder();
        for (DebateReferences.Crux crux : cruxes) {
            table.append('[').append(DebateReferences.alias(crux.thesis().pointId(), aliases)).append("] 持方=")
                    .append(crux.owner()).append(" 状态=").append(crux.status())
                    .append(" 最近交锋=第").append(crux.lastRound()).append("轮 往来=")
                    .append(crux.moves().isEmpty() ? "无" : crux.moves().stream()
                            .map(id -> DebateReferences.alias(id, aliases))
                            .collect(java.util.stream.Collectors.joining(",")))
                    .append(" | ").append(truncate(crux.thesis().claim(), 160)).append('\n');
        }
        return ResearchPrompts.evidenceBlock(state) + """
                %s
                当前已完成轮次：%d
                服务端硬上限：%d

                全部论点索引与近期辩论细节：
                %s

                争点表（每个第一轮根论点一行，状态由后端沿 respondsTo 链统计，是确定性事实）：
                %s
                状态含义：OPEN=交锋停在本轮，对方下一轮可以回应。STALLED=连续两轮仍在往来但没有带来新证据，
                CONCEDED=持方被反驳后未回应，HELD=持方回应后对方未再反驳，DEADLOCK=双方同轮发言后都未继续，
                UNCHALLENGED=无人反驳。只有 OPEN 争点参与续停判断。

                先确定：如果现在停止辩论，结论会偏向哪一方（provisionalLeaning：BULL、BEAR 或 BALANCED）。
                再对每个 OPEN 争点判断 impact：
                - CAN_FLIP：该争点的走向可能改变上述倾向，且下一轮反驳能用现有证据推进它。
                - NEEDS_EXTERNAL：可能改变倾向，但取决于现有证据之外的信息（未来政策、尚未披露的数据等），继续辩论无法解决。
                - NO_IMPACT：无论走向如何都不改变倾向。
                只要存在 CAN_FLIP 争点，后端就会继续下一轮，所以只在确有依据时标 CAN_FLIP。
                cruxes 必须恰好覆盖全部 OPEN 争点编号，每个一次；没有 OPEN 争点时填空数组。

                只输出严格 JSON，例如：
                {"provisionalLeaning":"BALANCED","cruxes":[{"id":"BULL-1.1","impact":"CAN_FLIP"}],"reason":"不超过 300 字"}
                """.formatted(
                ResearchPrompts.roleSection(ResearchPrompts.CONTINUATION_ROLE),
                completedRound,
                maxRounds,
                untrustedContext(ResearchPrompts.renderDebateHistory(state == null ? null : state.getDebateTurns(), 14_000)),
                untrustedContext(table.toString().stripTrailing()));
    }

    /** 严格解析续停输出；decision 由后端按是否存在 CAN_FLIP 的 OPEN 争点得出，不提供任何默认动作。 */
    private DebateContinuationDecision parseContinuationDecision(String content, Set<String> openAliases) {
        if (content == null || content.isBlank()) {
            throw new DebateContinuationException("Research Manager continuation output is empty");
        }
        try {
            JsonNode root = readStrictJson(objectMapper, JsonText.extractObject(content));
            List<String> issues = new ArrayList<>();
            appendUnknownFields(root, CONTINUATION_ROOT_FIELDS, issues, "continuation");
            String leaning = readStrictText(root, "provisionalLeaning", issues);
            String reason = readStrictText(root, "reason", issues);
            if (!LEANINGS.contains(leaning)) {
                issues.add("provisionalLeaning");
            }
            if (reason.length() > 300) {
                issues.add("reason");
            }
            Map<String, CruxImpact> impacts = new LinkedHashMap<>();
            JsonNode cruxNodes = root.path("cruxes");
            if (!cruxNodes.isArray()) {
                issues.add("cruxes");
            } else {
                for (JsonNode node : cruxNodes) {
                    if (!node.isObject()) {
                        issues.add("cruxSchema");
                        continue;
                    }
                    appendUnknownFields(node, CRUX_FIELDS, issues, "crux");
                    String id = node.path("id").asText("").strip().toUpperCase(java.util.Locale.ROOT);
                    if (!openAliases.contains(id) || impacts.containsKey(id)) {
                        issues.add("cruxId:" + id);
                        continue;
                    }
                    try {
                        impacts.put(id, CruxImpact.valueOf(node.path("impact").asText("")));
                    } catch (IllegalArgumentException error) {
                        issues.add("cruxImpact:" + id);
                    }
                }
                openAliases.stream().filter(id -> !impacts.containsKey(id))
                        .forEach(id -> issues.add("cruxMissing:" + id));
            }
            if (!issues.isEmpty()) {
                throw new DebateContinuationException(
                        "Research Manager continuation schema is invalid: "
                                + String.join(",", issues));
            }
            DebateContinuation decision = impacts.containsValue(CruxImpact.CAN_FLIP)
                    ? DebateContinuation.CONTINUE : DebateContinuation.STOP;
            return new DebateContinuationDecision(decision, reason, leaning, impacts);
        } catch (DebateContinuationException error) {
            throw error;
        } catch (Exception error) {
            log.warn("Failed to parse Research Manager continuation JSON, errorType={}",
                    error.getClass().getSimpleName());
            throw new DebateContinuationException(
                    "Research Manager continuation JSON is invalid", error);
        }
    }

    /** 续停输出不满足严格契约时使用的稳定异常。 */
    public static final class DebateContinuationException extends IllegalStateException {

        DebateContinuationException(String message) {
            super(message);
        }

        DebateContinuationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
