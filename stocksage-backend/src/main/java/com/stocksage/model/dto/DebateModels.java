package com.stocksage.model.dto;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 结构化多空辩论、Research Manager 评分和确定性裁决共享的有限契约。
 *
 * <p>这些对象会进入 {@link AnalysisState} checkpoint，因此只保存可审计的论点、证据引用、
 * 有限评分和最终裁决，不保存隐藏思维过程。所有列表都会在构造时复制为不可变快照。</p>
 */
public final class DebateModels {

    /** Research Manager 结构化评分契约的稳定标识。 */
    public static final String MANAGER_ASSESSMENT_CONTRACT_ID = "manager-assessment-v1";

    /** Research Manager 结构化评分契约版本。 */
    public static final int MANAGER_ASSESSMENT_CONTRACT_VERSION = 1;

    private DebateModels() {
    }

    /** 辩论立场。 */
    public enum Side {
        BULL,
        BEAR
    }

    /** 第一轮建立根论点，后续轮次只能提交指向既有论点的反驳。 */
    public enum PointType {
        THESIS,
        REBUTTAL
    }

    /**
     * 论点对 Evidence Ledger 中一条证据的显式引用。
     *
     * @param evidenceId 当前研究运行内的证据 ID
     * @param excerpt 来自对应证据正文的连续摘录
     */
    public record EvidenceRef(String evidenceId, String excerpt) {

        public EvidenceRef {
            evidenceId = safe(evidenceId);
            excerpt = safe(excerpt);
        }
    }

    /**
     * 一条可独立核验的根论点或反驳。
     *
     * @param pointId 后端分配的稳定 ID
     * @param type 根论点或反驳
     * @param claim 可证伪的结论
     * @param horizon 结论适用期限
     * @param evidenceRefs 论点显式引用的证据
     * @param reasoning 证据到结论的可展示推理摘要
     * @param assumption 结论成立所依赖的假设
     * @param invalidationCondition 可观察的失效条件
     * @param respondsToPointIds 反驳所回应的历史论点 ID；根论点为空
     */
    public record DebatePoint(
            String pointId,
            PointType type,
            String claim,
            AnalysisHorizon horizon,
            List<EvidenceRef> evidenceRefs,
            String reasoning,
            String assumption,
            String invalidationCondition,
            List<String> respondsToPointIds
    ) {

        public DebatePoint {
            pointId = safe(pointId);
            type = type == null ? PointType.THESIS : type;
            claim = safe(claim);
            horizon = horizon == null ? AnalysisHorizon.UNSPECIFIED : horizon;
            evidenceRefs = immutableValues(evidenceRefs);
            reasoning = safe(reasoning);
            assumption = safe(assumption);
            invalidationCondition = safe(invalidationCondition);
            respondsToPointIds = immutableStrings(respondsToPointIds);
        }
    }

    /**
     * 一方在一轮中的完整结构化提交。
     *
     * @param round 从 1 开始的轮次
     * @param side 发言方
     * @param points 本轮已经通过解析的论点
     */
    public record DebateTurn(int round, Side side, List<DebatePoint> points) {

        public DebateTurn {
            round = Math.max(0, round);
            side = side == null ? Side.BULL : side;
            points = immutableValues(points);
        }
    }

    /** Manager 对论点评分时可输出的有限原因代码。 */
    public enum AssessmentReasonCode {
        SUPPORTED,
        PARTIALLY_SUPPORTED,
        UNSUPPORTED,
        IRRELEVANT,
        LOGIC_GAP,
        REBUTTAL_SURVIVED,
        REBUTTAL_SUCCEEDED,
        ASSUMPTION_DEPENDENT,
        CRITICAL_UNKNOWN,
        STALE_EVIDENCE,
        DUPLICATE
    }

    /**
     * Research Manager 对一条第一轮根论点的语义评分。
     *
     * <p>五个评分均为 0 到 4。Manager 不输出双方总分、胜方或投资评级。</p>
     */
    public record ArgumentAssessment(
            String pointId,
            int evidenceSupport,
            int questionRelevance,
            int logicalCoherence,
            int rebuttalSurvival,
            int uncertaintyHandling,
            List<String> acceptedEvidenceIds,
            List<String> decisiveRebuttalIds,
            List<AssessmentReasonCode> reasonCodes,
            String explanation
    ) {

        public ArgumentAssessment {
            pointId = safe(pointId);
            requireScore("evidenceSupport", evidenceSupport);
            requireScore("questionRelevance", questionRelevance);
            requireScore("logicalCoherence", logicalCoherence);
            requireScore("rebuttalSurvival", rebuttalSurvival);
            requireScore("uncertaintyHandling", uncertaintyHandling);
            acceptedEvidenceIds = immutableStrings(acceptedEvidenceIds);
            decisiveRebuttalIds = immutableStrings(decisiveRebuttalIds);
            reasonCodes = immutableValues(reasonCodes);
            explanation = safe(explanation);
        }

        /** 报告门禁读取已接受证据时的明确别名。 */
        public List<String> evidenceIds() {
            return acceptedEvidenceIds;
        }
    }

    /** Manager 评分输出的解析状态。 */
    public enum AssessmentParseStatus {
        VALID,
        INVALID_JSON,
        INVALID_SCHEMA,
        EMPTY_OUTPUT,
        MODEL_FAILURE
    }

    /**
     * Research Manager 的完整结构化评分产物。
     *
     * @param contractId 评分 JSON 契约 ID
     * @param version 评分 JSON 契约版本
     * @param inputHash 评分所读取的证据和辩论输入哈希
     * @param positionAIsBull 盲化输入中位置 A 是否对应 Bull
     * @param assessments 逐根论点评分
     * @param parseStatus 解析状态
     * @param issues 有限字段问题
     */
    public record ManagerAssessment(
            String contractId,
            int version,
            String inputHash,
            boolean positionAIsBull,
            List<ArgumentAssessment> assessments,
            AssessmentParseStatus parseStatus,
            List<String> issues
    ) {

        public ManagerAssessment {
            contractId = safe(contractId);
            inputHash = safe(inputHash);
            assessments = immutableValues(assessments);
            parseStatus = parseStatus == null
                    ? AssessmentParseStatus.MODEL_FAILURE
                    : parseStatus;
            issues = immutableStrings(issues);
        }
    }

    /** Java 决策策略计算出的领先方；证据不足与多空平衡分开记录。 */
    public enum LeadingSide {
        BULL,
        BEAR,
        BALANCED,
        INSUFFICIENT
    }

    /**
     * 可复算、可持久化的最终辩论裁决。
     *
     * <p>双方总分和 recommendation 只能由服务器端 DecisionPolicy 生成，不能由 Manager 提供。</p>
     */
    public record DebateVerdict(
            String policyId,
            int version,
            String inputHash,
            String dataSnapshotHash,
            double bullScore,
            double bearScore,
            LeadingSide leadingSide,
            double scoreMargin,
            String recommendation,
            AnalysisHorizon analysisHorizon,
            List<String> decisivePointIds,
            List<String> unresolvedPointIds,
            List<ArgumentAssessment> assessments
    ) {

        public DebateVerdict {
            policyId = safe(policyId);
            inputHash = safe(inputHash);
            dataSnapshotHash = safe(dataSnapshotHash);
            bullScore = boundedPercent(bullScore);
            bearScore = boundedPercent(bearScore);
            leadingSide = leadingSide == null ? LeadingSide.INSUFFICIENT : leadingSide;
            scoreMargin = boundedPercent(scoreMargin);
            recommendation = safe(recommendation);
            if (recommendation.isBlank()) {
                recommendation = "HOLD";
            }
            analysisHorizon = analysisHorizon == null
                    ? AnalysisHorizon.UNSPECIFIED
                    : analysisHorizon;
            decisivePointIds = immutableStrings(decisivePointIds);
            unresolvedPointIds = immutableStrings(unresolvedPointIds);
            assessments = immutableValues(assessments);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }

    private static List<String> immutableStrings(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String safeValue = safe(value);
            if (!safeValue.isBlank()) {
                normalized.add(safeValue);
            }
        }
        return List.copyOf(normalized);
    }

    private static <T> List<T> immutableValues(List<T> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<T> normalized = new ArrayList<>(values.size());
        for (T value : values) {
            if (value != null) {
                normalized.add(value);
            }
        }
        return List.copyOf(normalized);
    }

    private static void requireScore(String field, int value) {
        if (value < 0 || value > 4) {
            throw new IllegalArgumentException(field + " must be between 0 and 4");
        }
    }

    private static double boundedPercent(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(100.0, value));
    }
}
