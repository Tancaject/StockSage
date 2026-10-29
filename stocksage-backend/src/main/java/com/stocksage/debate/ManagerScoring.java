package com.stocksage.debate;

import static com.stocksage.util.PromptText.untrustedContext;

import static com.stocksage.debate.ManagerJson.NO_OP_EXECUTION_GUARD;
import static com.stocksage.debate.ManagerJson.appendUnknownFields;
import static com.stocksage.debate.ManagerJson.readStrictJson;
import static com.stocksage.debate.ManagerJson.readStrictStringArray;
import static com.stocksage.debate.ManagerJson.truncate;

import com.stocksage.agent.runtime.ModelInvocationAdvisor;
import com.stocksage.debate.DebateModels.ArgumentAssessment;
import com.stocksage.debate.DebateModels.AssessmentParseStatus;
import com.stocksage.debate.DebateModels.AssessmentReasonCode;
import com.stocksage.debate.DebateModels.DebatePoint;
import com.stocksage.debate.DebateModels.DebateTurn;
import com.stocksage.debate.DebateModels.ManagerAssessment;
import com.stocksage.debate.DebateModels.PointType;
import com.stocksage.debate.DebateModels.Side;
import com.stocksage.research.deep.AnalysisState;
import com.stocksage.util.JsonText;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Research Manager 的匿名逐论点评分：构造评分提示词、调用评分模型并严格解析评分契约。
 *
 * <p>只产出有界的逐项分数，不计算总分、胜方或 recommendation；裁决由 {@link DebateDecisionPolicy} 决定。</p>
 */
@Slf4j
final class ManagerScoring {

    private static final Set<String> SCORING_ROOT_FIELDS = Set.of("assessments");
    private static final Set<String> ASSESSMENT_FIELDS = Set.of(
            "pointId",
            "evidenceSupport",
            "questionRelevance",
            "logicalCoherence",
            "rebuttalSurvival",
            "uncertaintyHandling",
            "acceptedEvidenceIds",
            "decisiveRebuttalIds",
            "reasonCodes",
            "explanation"
    );

    /** 无工具、低温度的逐论点评分客户端。 */
    private final ChatClient scoringChatClient;
    /** 解析模型返回的结构化 JSON。 */
    private final ObjectMapper objectMapper;

    ManagerScoring(ChatClient scoringChatClient, ObjectMapper objectMapper) {
        this.scoringChatClient = scoringChatClient;
        this.objectMapper = objectMapper;
    }

    /** 见 {@link ResearchManager#scoreDebate(AnalysisState, String, Runnable, String, List)}。 */
    Mono<ManagerAssessment> scoreDebate(
            AnalysisState state,
            String inputHash,
            Runnable executionGuard,
            String onlyPosition,
            List<String> repairIssues
    ) {
        Runnable guard = executionGuard == null ? NO_OP_EXECUTION_GUARD : executionGuard;
        boolean positionAIsBull = DebateDecisionPolicy.expectedPositionAIsBull(inputHash);
        StringBuilder buffer = new StringBuilder();
        var request = scoringChatClient.prompt().user(buildScoringPrompt(state, positionAIsBull, onlyPosition, repairIssues));
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
                    ManagerAssessment assessment = parseAssessmentResult(
                            buffer.toString(), state, inputHash, positionAIsBull, onlyPosition);
                    if (assessment.parseStatus() != AssessmentParseStatus.VALID || !assessment.issues().isEmpty()) {
                        // 评分原文不进 Trace；失败时在日志留开头一段，便于定位是哪类结构问题。
                        String raw = buffer.toString();
                        log.warn("Research Manager assessment invalid, position={}, status={}, issues={}, outputHead={}",
                                onlyPosition == null ? "ALL" : onlyPosition, assessment.parseStatus(),
                                assessment.issues().stream().limit(20).toList(),
                                raw.substring(0, Math.min(raw.length(), 1500)).replace('\n', ' '));
                    }
                    return assessment;
                }));
    }

    /**
     * 构造匿名逐论点评分提示词。
     *
     * <p>评分范围和修正说明放在末尾，单次评分、按位置拆分评分和修正评分共享同一段前缀。</p>
     *
     * @param onlyPosition 只为 "A" 或 "B" 位置的根论点评分；null 表示全部
     * @param repairIssues 上一次输出的结构校验问题；首次评分传 null
     */
    private String buildScoringPrompt(AnalysisState state, boolean positionAIsBull,
                                      String onlyPosition, List<String> repairIssues) {
        Side positionASide = positionAIsBull ? Side.BULL : Side.BEAR;
        Side positionBSide = positionAIsBull ? Side.BEAR : Side.BULL;
        Map<String, String> aliases = DebateReferences.positionAliases(
                state == null ? null : state.getDebateTurns(), positionAIsBull);
        String scope = onlyPosition == null
                ? "评分范围：Position A 和 Position B 的全部 THESIS 根论点。"
                : "评分范围：本次只为 Position %s 的 THESIS 根论点（编号以 %s- 开头）评分，assessments 只包含这些根论点；评 rebuttalSurvival 时仍要结合对方的全部回应。"
                        .formatted(onlyPosition, onlyPosition);
        String repair = repairIssues == null || repairIssues.isEmpty() ? "" : """

                === 上一次评分输出未通过结构校验 ===
                问题：%s
                请修正这些问题后重新输出完整的 JSON；评分范围内的每个根论点都要出现且只出现一次。
                """.formatted(untrustedContext(String.join("; ", repairIssues.stream().limit(30)
                        .map(issue -> {
                            String shown = issue;
                            for (Map.Entry<String, String> alias : aliases.entrySet()) {
                                shown = shown.replace(alias.getKey(), alias.getValue());
                            }
                            return shown;
                        }).toList())));
        return ResearchPrompts.evidenceBlock(state) + """
                %s

                Position A 的结构化论点与后续回应：
                %s

                Position B 的结构化论点与后续回应：
                %s

                请对评分范围内的每一条 THESIS 根论点评分。后续 REBUTTAL 只用于判断该根论点是否经受住反驳。
                五项分数均为 0-4：
                - evidenceSupport：claim 是否被引用证据正文直接或可靠支持。
                - questionRelevance：是否直接回答用户问题并具有决策重要性。
                - logicalCoherence：证据、假设和结论之间是否存在逻辑跳跃。
                - rebuttalSurvival：结合所有回应后，该根论点仍成立的程度。
                - uncertaintyHandling：是否诚实暴露关键假设、未知项和失效条件。

                只输出严格 JSON，不得输出 winner、Position 总分、recommendation 或 confidence：
                {
                  "assessments": [
                    {
                      "pointId": "输入中真实存在的 THESIS 编号，如 A-1.2",
                      "evidenceSupport": 0,
                      "questionRelevance": 0,
                      "logicalCoherence": 0,
                      "rebuttalSurvival": 0,
                      "uncertaintyHandling": 0,
                      "acceptedEvidenceIds": ["真正支持该 claim 的 evidenceId"],
                      "decisiveRebuttalIds": ["显著削弱或强化判断的 REBUTTAL 编号"],
                      "reasonCodes": ["SUPPORTED 或 PARTIALLY_SUPPORTED 或 UNSUPPORTED 或 IRRELEVANT 或 LOGIC_GAP 或 REBUTTAL_SURVIVED 或 REBUTTAL_SUCCEEDED 或 ASSUMPTION_DEPENDENT 或 CRITICAL_UNKNOWN 或 STALE_EVIDENCE 或 DUPLICATE"],
                      "explanation": "不超过 200 字的可展示评分理由"
                    }
                  ]
                }

                必须恰好覆盖评分范围内的全部 THESIS 编号，每个只出现一次。证据 ID 或摘录对不上时降低 evidenceSupport，
                acceptedEvidenceIds 只能保留该 THESIS 已引用且确实支持 claim 的 ID；decisiveRebuttalIds 只能从该 THESIS 的
                relatedRebuttals 列表中选择，列表为 (none) 时填空数组。explanation 不超过 200 字。不要输出隐藏思维过程或任何额外字段。

                %s
                """.formatted(
                ResearchPrompts.roleSection(ResearchPrompts.SCORING_ROLE),
                untrustedContext(renderPosition(state, positionASide, 20_000, aliases)),
                untrustedContext(renderPosition(state, positionBSide, 20_000, aliases)),
                scope) + repair;
    }

    /** 把 Manager JSON 解析为有界评分；任何漏评、越界或未知枚举都会标记为无效。 */
    private ManagerAssessment parseAssessmentResult(
            String content,
            AnalysisState state,
            String inputHash,
            boolean positionAIsBull,
            String onlyPosition
    ) {
        if (content == null || content.isBlank()) {
            return assessmentFailure(
                    inputHash, positionAIsBull, AssessmentParseStatus.EMPTY_OUTPUT, "structuredOutput");
        }
        JsonNode root;
        try {
            root = readStrictJson(objectMapper, JsonText.extractObject(content));
        } catch (Exception error) {
            log.warn("Failed to parse Research Manager assessment JSON, errorType={}",
                    error.getClass().getSimpleName());
            return assessmentFailure(
                    inputHash, positionAIsBull, AssessmentParseStatus.INVALID_JSON, "structuredOutput");
        }

        Map<String, DebatePoint> expectedPoints = new LinkedHashMap<>();
        Map<String, DebatePoint> allPoints = new LinkedHashMap<>();
        // 拆分评分时只核对该位置的根论点；位置 A 对应哪一方由输入哈希决定。
        Side onlySide = onlyPosition == null ? null
                : ("A".equals(onlyPosition) == positionAIsBull ? Side.BULL : Side.BEAR);
        Map<String, String> aliasToPointId = DebateReferences.reverse(DebateReferences.positionAliases(
                state == null ? null : state.getDebateTurns(), positionAIsBull));
        if (state != null && state.getDebateTurns() != null) {
            state.getDebateTurns().stream()
                    .filter(turn -> turn != null)
                    .flatMap(turn -> turn.points().stream())
                    .forEach(point -> allPoints.putIfAbsent(point.pointId(), point));
            state.getDebateTurns().stream()
                    .filter(turn -> turn != null && turn.round() == 1 && (onlySide == null || turn.side() == onlySide))
                    .flatMap(turn -> turn.points().stream())
                    .filter(point -> point.type() == PointType.THESIS)
                    .forEach(point -> expectedPoints.putIfAbsent(point.pointId(), point));
        }

        List<String> issues = new ArrayList<>();
        appendUnknownFields(root, SCORING_ROOT_FIELDS, issues, "root");
        List<ArgumentAssessment> assessments = new ArrayList<>();
        Set<String> seenPointIds = new LinkedHashSet<>();
        JsonNode assessmentNodes = root.path("assessments");
        if (expectedPoints.isEmpty()) {
            issues.add("thesisPoints");
        }
        if (!assessmentNodes.isArray()) {
            issues.add("assessments");
        } else {
            for (JsonNode node : assessmentNodes) {
                try {
                    if (!node.isObject()) {
                        issues.add("assessmentSchema");
                        continue;
                    }
                    appendUnknownFields(node, ASSESSMENT_FIELDS, issues, "assessment");
                    String pointId = DebateReferences.resolve(node.path("pointId").asText(""), aliasToPointId);
                    if (!expectedPoints.containsKey(pointId) || !seenPointIds.add(pointId)) {
                        issues.add("pointId:" + pointId);
                        continue;
                    }
                    DebatePoint thesis = expectedPoints.get(pointId);
                    List<String> acceptedEvidenceIds = readStrictStringArray(
                            node.get("acceptedEvidenceIds"), issues,
                            "acceptedEvidenceIds:" + pointId);
                    Set<String> thesisEvidenceIds = thesis.evidenceRefs().stream()
                            .map(ref -> ref.evidenceId())
                            .collect(java.util.stream.Collectors.toSet());
                    if (acceptedEvidenceIds.stream()
                            .anyMatch(id -> !thesisEvidenceIds.contains(id))) {
                        issues.add("acceptedEvidenceId:" + pointId);
                    }
                    List<String> decisiveRebuttalIds = readStrictStringArray(
                            node.get("decisiveRebuttalIds"), issues,
                            "decisiveRebuttalIds:" + pointId).stream()
                            .map(id -> DebateReferences.resolve(id, aliasToPointId))
                            .distinct()
                            .toList();
                    if (decisiveRebuttalIds.stream().anyMatch(id ->
                            !DebateReferences.isRelatedRebuttal(pointId, id, allPoints, new LinkedHashSet<>()))) {
                        issues.add("decisiveRebuttalId:" + pointId);
                    }
                    List<AssessmentReasonCode> reasonCodes = readReasonCodes(
                            node.path("reasonCodes"), issues, pointId);
                    if (reasonCodes.isEmpty()) {
                        issues.add("reasonCodesEmpty:" + pointId);
                    }
                    String explanation = node.path("explanation").asText("").strip();
                    if (!node.has("explanation") || !node.get("explanation").isTextual()
                            || explanation.isBlank() || explanation.length() > 300) {
                        issues.add("explanation:" + pointId);
                    }
                    assessments.add(new ArgumentAssessment(
                            pointId,
                            strictScore(node, "evidenceSupport"),
                            strictScore(node, "questionRelevance"),
                            strictScore(node, "logicalCoherence"),
                            strictScore(node, "rebuttalSurvival"),
                            strictScore(node, "uncertaintyHandling"),
                            acceptedEvidenceIds,
                            decisiveRebuttalIds,
                            reasonCodes,
                            truncate(explanation, 300)
                    ));
                } catch (RuntimeException error) {
                    issues.add("assessmentSchema");
                }
            }
        }
        for (String expectedPointId : expectedPoints.keySet()) {
            if (!seenPointIds.contains(expectedPointId)) {
                issues.add("missingAssessment:" + expectedPointId);
            }
        }

        AssessmentParseStatus status = issues.isEmpty()
                ? AssessmentParseStatus.VALID
                : AssessmentParseStatus.INVALID_SCHEMA;
        return new ManagerAssessment(
                DebateModels.MANAGER_ASSESSMENT_CONTRACT_ID,
                DebateModels.MANAGER_ASSESSMENT_CONTRACT_VERSION,
                inputHash,
                positionAIsBull,
                assessments,
                status,
                issues
        );
    }

    private ManagerAssessment assessmentFailure(
            String inputHash,
            boolean positionAIsBull,
            AssessmentParseStatus status,
            String issue
    ) {
        return new ManagerAssessment(
                DebateModels.MANAGER_ASSESSMENT_CONTRACT_ID,
                DebateModels.MANAGER_ASSESSMENT_CONTRACT_VERSION,
                inputHash,
                positionAIsBull,
                List.of(),
                status,
                List.of(issue)
        );
    }

    private int strictScore(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        int score = value.intValue();
        if (score < 0 || score > 4) {
            throw new IllegalArgumentException(field + " must be between 0 and 4");
        }
        return score;
    }

    private List<AssessmentReasonCode> readReasonCodes(
            JsonNode node,
            List<String> issues,
            String pointId
    ) {
        if (node == null || !node.isArray()) {
            issues.add("reasonCodes:" + pointId);
            return List.of();
        }
        List<AssessmentReasonCode> values = new ArrayList<>();
        for (JsonNode item : node) {
            try {
                AssessmentReasonCode reason = AssessmentReasonCode.valueOf(item.asText(""));
                if (!values.contains(reason)) {
                    values.add(reason);
                } else {
                    issues.add("reasonCodeDuplicate:" + pointId);
                }
            } catch (IllegalArgumentException error) {
                issues.add("reasonCode:" + pointId);
            }
        }
        return List.copyOf(values);
    }

    /**
     * 只渲染一个匿名位置的结构化论点，不泄露 Bull/Bear 名称。
     * 所有 Round 1 根论点必定保留；剩余预算从最新到最旧装入反驳，避免简单头部截断丢掉
     * rebuttalSurvival 真正需要的交锋。
     */
    private String renderPosition(AnalysisState state, Side side, int maxLength, Map<String, String> aliases) {
        if (state == null || state.getDebateTurns() == null) {
            return "(no structured points)";
        }
        Map<String, DebatePoint> allPoints = new LinkedHashMap<>();
        state.getDebateTurns().stream()
                .filter(turn -> turn != null)
                .flatMap(turn -> turn.points().stream())
                .forEach(point -> allPoints.putIfAbsent(point.pointId(), point));
        StringBuilder roots = new StringBuilder("Root THESIS points:\n");
        for (DebateTurn turn : state.getDebateTurns()) {
            if (turn == null || turn.side() != side || turn.round() != 1) {
                continue;
            }
            for (DebatePoint point : turn.points()) {
                if (point.type() == PointType.THESIS) {
                    appendPoint(roots, point, 600, 180, 420, 240, aliases);
                    // 相关性由后端沿 respondsTo 链算好，模型不必自己串链；校验用的是同一规则。
                    String related = allPoints.values().stream()
                            .filter(candidate -> DebateReferences.isRelatedRebuttal(point.pointId(), candidate.pointId(),
                                    allPoints, new LinkedHashSet<>()))
                            .map(candidate -> DebateReferences.alias(candidate.pointId(), aliases))
                            .collect(java.util.stream.Collectors.joining(","));
                    roots.append("  relatedRebuttals=").append(related.isEmpty() ? "(none)" : related).append('\n');
                }
            }
        }
        if (roots.toString().equals("Root THESIS points:\n")) {
            return "(no structured points)";
        }

        StringBuilder rebuttals = new StringBuilder("\nLatest related REBUTTAL points:\n");
        List<DebateTurn> turns = state.getDebateTurns();
        for (int turnIndex = turns.size() - 1; turnIndex >= 0; turnIndex--) {
            DebateTurn turn = turns.get(turnIndex);
            if (turn == null || turn.side() != side || turn.round() <= 1) {
                continue;
            }
            for (int pointIndex = turn.points().size() - 1; pointIndex >= 0; pointIndex--) {
                DebatePoint point = turn.points().get(pointIndex);
                if (point.type() != PointType.REBUTTAL) {
                    continue;
                }
                StringBuilder candidate = new StringBuilder();
                appendPoint(candidate, point, 360, 120, 260, 160, aliases);
                if (roots.length() + rebuttals.length() + candidate.length() > maxLength) {
                    rebuttals.append("...[older rebuttals omitted]\n");
                    return roots.append(rebuttals).toString();
                }
                rebuttals.append(candidate);
            }
        }
        return roots.append(rebuttals).toString();
    }

    /** 有界论点表示；评分根论点保留更多字段预算，反驳更紧凑，报告综合使用共享的索引与近期细节。 */
    private void appendPoint(
            StringBuilder builder,
            DebatePoint point,
            int claimLimit,
            int excerptLimit,
            int reasoningLimit,
            int boundaryLimit,
            Map<String, String> aliases
    ) {
        builder.append('[').append(DebateReferences.alias(point.pointId(), aliases)).append("] ")
                .append(point.type()).append(" | claim=")
                .append(truncate(point.claim(), claimLimit))
                .append(" | horizon=").append(point.horizon()).append('\n');
        point.evidenceRefs().forEach(ref -> builder.append("  evidenceId=")
                .append(ref.evidenceId()).append(" | excerpt=")
                .append(truncate(ref.excerpt(), excerptLimit)).append('\n'));
        if (!point.respondsToPointIds().isEmpty()) {
            builder.append("  respondsTo=")
                    .append(point.respondsToPointIds().stream()
                            .map(id -> DebateReferences.alias(id, aliases))
                            .collect(java.util.stream.Collectors.joining(","))).append('\n');
        }
        builder.append("  reasoning=").append(truncate(point.reasoning(), reasoningLimit))
                .append(" | assumption=").append(truncate(point.assumption(), boundaryLimit))
                .append(" | invalidation=")
                .append(truncate(point.invalidationCondition(), boundaryLimit)).append('\n');
    }
}
