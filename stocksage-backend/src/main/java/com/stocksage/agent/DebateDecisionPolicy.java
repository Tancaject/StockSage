package com.stocksage.agent;

import com.stocksage.evidence.EvidenceLedger;
import com.stocksage.evidence.EvidenceFreshness;
import com.stocksage.evidence.EvidenceModels.EvidenceDimension;
import com.stocksage.evidence.EvidenceModels.EvidenceEnvelope;
import com.stocksage.model.dto.AnalysisHorizon;
import com.stocksage.model.dto.AnalysisState;
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
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.stocksage.model.dto.DebateModels.MANAGER_ASSESSMENT_CONTRACT_ID;
import static com.stocksage.model.dto.DebateModels.MANAGER_ASSESSMENT_CONTRACT_VERSION;

/**
 * 根据结构化论点和 Research Manager 的逐项语义评分确定性计算最终研究倾向。
 *
 * <p>Manager 只能给每条根论点的有限维度打分，不能提供胜方、双方总分或 recommendation。
 * 本策略负责证据资格、时效性、Top-3 聚合和评级阈值，使相同输入始终得到相同裁决。</p>
 */
@Component
public class DebateDecisionPolicy {

    /** 持久化到 verdict/checkpoint 的稳定策略 ID。 */
    public static final String POLICY_ID = "debate-decision-v1";

    /** 算分权重、时效窗口或评级门槛变化时必须递增。 */
    public static final int POLICY_VERSION = 2;

    /** 输入哈希契约；变化时即使业务策略版本不变也不会误复用旧 verdict。 */
    private static final String INPUT_CONTRACT_ID = "debate-decision-input-v2-time-facts";

    private static final int REQUIRED_ARGUMENTS_PER_SIDE = 3;
    private static final double DIRECTIONAL_SCORE = 65.0;
    private static final double DIRECTIONAL_GAP = 10.0;
    private static final double STRONG_SCORE = 80.0;
    private static final double STRONG_GAP = 20.0;
    private static final double UNKNOWN_AS_OF_SCORE = 0.5;

    private static final Set<AssessmentReasonCode> DISQUALIFYING_REASONS = Set.of(
            AssessmentReasonCode.UNSUPPORTED,
            AssessmentReasonCode.IRRELEVANT,
            AssessmentReasonCode.DUPLICATE
    );

    /** @return 当前确定性裁决策略 ID */
    public String policyId() {
        return POLICY_ID;
    }

    /** @return 当前确定性裁决策略版本 */
    public int policyVersion() {
        return POLICY_VERSION;
    }

    /**
     * 对当前证据元数据和完整结构化辩论生成顺序无关的稳定 SHA-256。
     *
     * <p>ManagerAssessment 和 DebateVerdict 都绑定这个值；重新收集证据或修改任一论点后，旧评分
     * 和旧裁决会立即失效。</p>
     */
    public String computeInputHash(AnalysisState state) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, INPUT_CONTRACT_ID);
        append(canonical, state == null ? "" : state.getQuery());
        append(canonical, state == null ? "" : state.getPrimaryTicker());
        append(canonical, state == null || state.getTimeSensitivity() == null
                ? "UNSPECIFIED" : state.getTimeSensitivity().name());
        append(canonical, state == null ? "" : state.getDataSnapshotHash());
        append(canonical, state == null ? "" : state.getContextHash());

        EvidenceLedger ledger = safeLedger(state);
        append(canonical, ledger.target().canonicalKey());
        append(canonical, ledger.target().displaySymbol());
        append(canonical, ledger.target().status().name());
        ledger.evidence().stream()
                .filter(item -> item != null)
                .sorted(Comparator.comparing(EvidenceEnvelope::evidenceId)
                        .thenComparing(item -> item.dimension().name())
                        .thenComparing(EvidenceEnvelope::capabilityId))
                .forEach(item -> appendEvidence(canonical, item));

        safeTurns(state).stream()
                .sorted(Comparator.comparingInt(DebateTurn::round)
                        .thenComparing(turn -> turn.side().name()))
                .forEach(turn -> appendTurn(canonical, turn));
        return sha256(canonical.toString());
    }

    /**
     * 判断 checkpoint/report 中的裁决是否仍绑定当前证据、辩论、Manager 评分和策略版本。
     */
    public boolean isCurrentVerdict(AnalysisState state, DebateVerdict verdict) {
        if (state == null || verdict == null
                || !POLICY_ID.equals(verdict.policyId())
                || verdict.version() != POLICY_VERSION) {
            return false;
        }
        String inputHash = computeInputHash(state);
        if (!inputHash.equals(verdict.inputHash())
                || !safe(state.getDataSnapshotHash()).equals(verdict.dataSnapshotHash())) {
            return false;
        }
        ManagerAssessment assessment = state.getManagerAssessment();
        return isUsableAssessment(assessment, inputHash)
                && assessment.assessments().equals(verdict.assessments())
                // 重新计算整个裁决，拒绝只保留相同 assessment 却篡改总分、胜方、评级或期限的 checkpoint。
                && decide(state, assessment).equals(verdict);
    }

    /** 检查 AnalysisState 自身保存的 verdict。 */
    public boolean isCurrentVerdict(AnalysisState state) {
        return state != null && isCurrentVerdict(state, state.getDebateVerdict());
    }

    /** 参数顺序兼容面向 verdict 的调用点。 */
    public boolean isCurrentVerdict(DebateVerdict verdict, AnalysisState state) {
        return isCurrentVerdict(state, verdict);
    }

    /** 使用 AnalysisState 中已经保存的 Manager 评分计算裁决。 */
    public DebateVerdict decide(AnalysisState state) {
        return decide(state, state == null ? null : state.getManagerAssessment());
    }

    /**
     * 计算唯一权威裁决；非法 Manager 契约、证据不足或任一方少于三条有效根论点时返回 HOLD。
     */
    public DebateVerdict decide(AnalysisState state, ManagerAssessment managerAssessment) {
        String inputHash = computeInputHash(state);
        String dataSnapshotHash = state == null ? "" : safe(state.getDataSnapshotHash());
        List<RootPoint> roots = rootTheses(state);
        List<String> allRootIds = roots.stream()
                .map(root -> root.point().pointId())
                .filter(id -> !id.isBlank())
                .distinct()
                .sorted()
                .toList();

        if (!isUsableAssessment(managerAssessment, inputHash)) {
            return insufficientVerdict(
                    inputHash,
                    dataSnapshotHash,
                    allRootIds,
                    managerAssessment == null ? List.of() : managerAssessment.assessments()
            );
        }

        Map<String, Long> pointIdCounts = countRootPointIds(roots);
        Map<String, Long> claimCounts = countNormalizedClaims(roots);
        Map<String, Long> assessmentCounts = managerAssessment.assessments().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        ArgumentAssessment::pointId,
                        LinkedHashMap::new,
                        java.util.stream.Collectors.counting()
                ));
        Map<String, ArgumentAssessment> assessmentsByPoint = new LinkedHashMap<>();
        for (ArgumentAssessment assessment : managerAssessment.assessments()) {
            assessmentsByPoint.putIfAbsent(assessment.pointId(), assessment);
        }

        EvidenceLedger ledger = safeLedger(state);
        Set<String> usableEvidenceIds = ledger.usableEvidenceIds();
        Map<String, EvidenceEnvelope> evidenceById = evidenceById(ledger);
        // 以本轮证据实际采集时点冻结时效性计算。checkpoint 重放同一输入时可复算出同一结果；
        // 新研究运行会产生新的 observedAt，并由报告快照的业务日桶使旧缓存失效。
        Instant evaluatedAt = freshnessEvaluationTime(ledger);
        Map<String, EvidenceFreshness.Assessment> temporalAssessments = new HashMap<>();
        evidenceById.forEach((id, evidence) -> temporalAssessments.put(id,
                EvidenceFreshness.assess(evidence, state.getTimeSensitivity(), evaluatedAt)));

        List<ScoredArgument> eligible = new ArrayList<>();
        LinkedHashSet<String> unresolved = new LinkedHashSet<>(allRootIds);
        for (RootPoint root : roots) {
            DebatePoint point = root.point();
            ArgumentAssessment assessment = assessmentsByPoint.get(point.pointId());
            if (!isEligible(
                    root,
                    assessment,
                    pointIdCounts,
                    claimCounts,
                    assessmentCounts,
                    usableEvidenceIds
            )) {
                continue;
            }
            double freshness = freshnessScore(
                    assessment.acceptedEvidenceIds(),
                    evidenceById,
                    point.horizon(),
                    evaluatedAt,
                    temporalAssessments
            );
            double score = argumentScore(assessment, freshness);
            eligible.add(new ScoredArgument(root, assessment, score, freshness));
            unresolved.remove(point.pointId());
            if (assessment.reasonCodes().contains(AssessmentReasonCode.CRITICAL_UNKNOWN)
                    || assessment.acceptedEvidenceIds().stream().anyMatch(id ->
                    temporalAssessments.get(id).status() == EvidenceFreshness.Status.UNKNOWN
                            || temporalAssessments.get(id).status() == EvidenceFreshness.Status.STALE)) {
                unresolved.add(point.pointId());
            }
        }

        List<ScoredArgument> bullTop = topThree(eligible, Side.BULL);
        List<ScoredArgument> bearTop = topThree(eligible, Side.BEAR);
        double bullScore = aggregateTopThree(bullTop);
        double bearScore = aggregateTopThree(bearTop);

        if (bullTop.size() < REQUIRED_ARGUMENTS_PER_SIDE
                || bearTop.size() < REQUIRED_ARGUMENTS_PER_SIDE) {
            return new DebateVerdict(
                    POLICY_ID,
                    POLICY_VERSION,
                    inputHash,
                    dataSnapshotHash,
                    bullScore,
                    bearScore,
                    LeadingSide.INSUFFICIENT,
                    roundTwoDecimals(Math.abs(bullScore - bearScore)),
                    "HOLD",
                    resolveHorizon(concat(bullTop, bearTop)),
                    List.of(),
                    List.copyOf(unresolved),
                    managerAssessment.assessments()
            );
        }

        double margin = roundTwoDecimals(Math.abs(bullScore - bearScore));
        Side scoreLeader = bullScore >= bearScore ? Side.BULL : Side.BEAR;
        double leaderScore = Math.max(bullScore, bearScore);
        List<ScoredArgument> leaderTop = scoreLeader == Side.BULL ? bullTop : bearTop;

        LeadingSide leadingSide;
        String recommendation;
        List<String> decisivePointIds;
        if (leaderScore < DIRECTIONAL_SCORE || margin < DIRECTIONAL_GAP) {
            leadingSide = LeadingSide.BALANCED;
            recommendation = "HOLD";
            decisivePointIds = List.of();
            concat(bullTop, bearTop).stream()
                    .map(item -> item.root().point().pointId())
                    .forEach(unresolved::add);
        } else {
            leadingSide = scoreLeader == Side.BULL ? LeadingSide.BULL : LeadingSide.BEAR;
            decisivePointIds = leaderTop.stream()
                    .map(item -> item.root().point().pointId())
                    .toList();
            boolean strong = leaderScore >= STRONG_SCORE
                    && margin >= STRONG_GAP
                    && decisiveEvidenceDimensions(leaderTop, evidenceById) >= 2
                    && leaderTop.stream().allMatch(item -> item.freshnessScore() >= 2.0)
                    && leaderTop.stream().noneMatch(item -> item.assessment().reasonCodes()
                            .stream().anyMatch(reason -> reason == AssessmentReasonCode.CRITICAL_UNKNOWN
                                    || reason == AssessmentReasonCode.STALE_EVIDENCE));
            if (scoreLeader == Side.BULL) {
                recommendation = strong ? "BUY" : "OVERWEIGHT";
            } else {
                recommendation = strong ? "SELL" : "UNDERWEIGHT";
            }
        }

        return new DebateVerdict(
                POLICY_ID,
                POLICY_VERSION,
                inputHash,
                dataSnapshotHash,
                bullScore,
                bearScore,
                leadingSide,
                margin,
                recommendation,
                resolveHorizon(leadingSide == LeadingSide.BALANCED
                        ? concat(bullTop, bearTop)
                        : leaderTop),
                decisivePointIds,
                List.copyOf(unresolved),
                managerAssessment.assessments()
        );
    }

    private boolean isUsableAssessment(ManagerAssessment assessment, String inputHash) {
        return assessment != null
                && MANAGER_ASSESSMENT_CONTRACT_ID.equals(assessment.contractId())
                && assessment.version() == MANAGER_ASSESSMENT_CONTRACT_VERSION
                && inputHash.equals(assessment.inputHash())
                && assessment.positionAIsBull() == expectedPositionAIsBull(inputHash)
                && assessment.parseStatus() == AssessmentParseStatus.VALID
                && assessment.issues().isEmpty();
    }

    /** 与 Manager 的匿名 A/B 排列规则保持一致，防止 checkpoint 篡改位置映射。 */
    private boolean expectedPositionAIsBull(String inputHash) {
        if (inputHash == null || inputHash.isBlank()) {
            return true;
        }
        int value = Character.digit(inputHash.charAt(inputHash.length() - 1), 16);
        return value < 0 || value % 2 == 0;
    }

    private List<RootPoint> rootTheses(AnalysisState state) {
        List<RootPoint> roots = new ArrayList<>();
        for (DebateTurn turn : safeTurns(state)) {
            if (turn.round() != 1) {
                continue;
            }
            for (DebatePoint point : turn.points()) {
                if (point.type() == PointType.THESIS) {
                    roots.add(new RootPoint(turn.side(), point));
                }
            }
        }
        roots.sort(Comparator.comparing((RootPoint root) -> root.side().name())
                .thenComparing(root -> root.point().pointId()));
        return List.copyOf(roots);
    }

    private boolean isEligible(
            RootPoint root,
            ArgumentAssessment assessment,
            Map<String, Long> pointIdCounts,
            Map<String, Long> claimCounts,
            Map<String, Long> assessmentCounts,
            Set<String> usableEvidenceIds
    ) {
        DebatePoint point = root.point();
        if (point.pointId().isBlank() || point.claim().isBlank()
                || point.evidenceRefs().isEmpty()
                || pointIdCounts.getOrDefault(point.pointId(), 0L) != 1L
                || claimCounts.getOrDefault(claimKey(root), 0L) != 1L
                || assessment == null
                || assessmentCounts.getOrDefault(point.pointId(), 0L) != 1L
                || assessment.reasonCodes().isEmpty()
                || assessment.reasonCodes().stream().anyMatch(DISQUALIFYING_REASONS::contains)
                || assessment.acceptedEvidenceIds().isEmpty()) {
            return false;
        }

        Set<String> citedEvidenceIds = new HashSet<>();
        for (EvidenceRef ref : point.evidenceRefs()) {
            if (!ref.evidenceId().isBlank() && !ref.excerpt().isBlank()) {
                citedEvidenceIds.add(ref.evidenceId());
            }
        }
        return !citedEvidenceIds.isEmpty()
                && assessment.acceptedEvidenceIds().stream()
                .allMatch(id -> citedEvidenceIds.contains(id) && usableEvidenceIds.contains(id));
    }

    private double argumentScore(ArgumentAssessment assessment, double freshnessScore) {
        double weighted = assessment.evidenceSupport() * 40.0
                + assessment.questionRelevance() * 20.0
                + assessment.logicalCoherence() * 10.0
                + assessment.rebuttalSurvival() * 15.0
                + assessment.uncertaintyHandling() * 5.0
                + freshnessScore * 10.0;
        return roundTwoDecimals(weighted / 4.0);
    }

    private double freshnessScore(
            List<String> acceptedEvidenceIds,
            Map<String, EvidenceEnvelope> evidenceById,
            AnalysisHorizon horizon,
            Instant evaluatedAt,
            Map<String, EvidenceFreshness.Assessment> temporalAssessments
    ) {
        if (acceptedEvidenceIds.isEmpty()) {
            return 0.0;
        }
        double total = 0.0;
        double temporalCap = 4.0;
        int count = 0;
        for (String evidenceId : acceptedEvidenceIds) {
            EvidenceEnvelope evidence = evidenceById.get(evidenceId);
            if (evidence == null) {
                continue;
            }
            temporalCap = Math.min(temporalCap, switch (temporalAssessments.get(evidenceId).status()) {
                case STALE -> 0.0;
                case UNKNOWN -> UNKNOWN_AS_OF_SCORE;
                case FRESH, NOT_APPLICABLE -> 4.0;
            });
            total += freshnessScore(evidence, horizon, evaluatedAt);
            count++;
        }
        // 同一论点依赖的未知或过期事实不能被其他新鲜引用平均掉。
        return count == 0 ? 0.0 : Math.min(temporalCap, total / count);
    }

    /** 使用账本中最新采集时点；旧证据缺失时点时保留未知，不用墙钟改变重放结果。 */
    private Instant freshnessEvaluationTime(EvidenceLedger ledger) {
        return ledger.evidence().stream()
                .map(EvidenceEnvelope::observedAt)
                .filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    /**
     * 业务年龄评分保持原窗口；能力时效判定另行限制未知或过期证据的分数。
     */
    private double freshnessScore(
            EvidenceEnvelope evidence,
            AnalysisHorizon horizon,
            Instant evaluatedAt
    ) {
        if (evidence.asOf() == null || evaluatedAt == null) {
            return UNKNOWN_AS_OF_SCORE;
        }
        if (evidence.asOf().isAfter(evaluatedAt.plus(Duration.ofDays(1)))) {
            return 0.0;
        }
        long ageDays = Math.max(0L, ChronoUnit.DAYS.between(evidence.asOf(), evaluatedAt));
        FreshnessWindow window = freshnessWindow(evidence.dimension(), horizon);
        double score;
        if (ageDays <= window.fullCreditDays()) {
            score = 4.0;
        } else if (ageDays >= window.staleDays()) {
            score = 0.0;
        } else {
            double remaining = window.staleDays() - ageDays;
            double span = window.staleDays() - window.fullCreditDays();
            score = 4.0 * remaining / span;
        }
        // 未声明期限时不能把宽松的长期窗口伪装成满分时效性。
        return horizon == null || horizon == AnalysisHorizon.UNSPECIFIED
                ? Math.min(2.0, score)
                : score;
    }

    private FreshnessWindow freshnessWindow(
            EvidenceDimension dimension,
            AnalysisHorizon horizon
    ) {
        AnalysisHorizon safeHorizon = horizon == null
                ? AnalysisHorizon.UNSPECIFIED
                : horizon;
        EvidenceDimension safeDimension = dimension == null
                ? EvidenceDimension.NEWS
                : dimension;
        return switch (safeDimension) {
            case MARKET -> switch (safeHorizon) {
                case SHORT_TERM -> new FreshnessWindow(3, 30);
                case MEDIUM_TERM -> new FreshnessWindow(14, 90);
                case LONG_TERM -> new FreshnessWindow(30, 180);
                case UNSPECIFIED -> new FreshnessWindow(7, 60);
            };
            case NEWS -> switch (safeHorizon) {
                case SHORT_TERM -> new FreshnessWindow(7, 45);
                case MEDIUM_TERM -> new FreshnessWindow(30, 180);
                case LONG_TERM -> new FreshnessWindow(60, 365);
                case UNSPECIFIED -> new FreshnessWindow(14, 90);
            };
            case FUNDAMENTALS, RAG -> switch (safeHorizon) {
                case SHORT_TERM -> new FreshnessWindow(120, 550);
                case MEDIUM_TERM -> new FreshnessWindow(180, 550);
                case LONG_TERM -> new FreshnessWindow(365, 730);
                case UNSPECIFIED -> new FreshnessWindow(120, 550);
            };
        };
    }

    private List<ScoredArgument> topThree(List<ScoredArgument> arguments, Side side) {
        return arguments.stream()
                .filter(item -> item.root().side() == side)
                .sorted(Comparator.comparingDouble(ScoredArgument::score).reversed()
                        .thenComparing(item -> item.root().point().pointId()))
                .limit(REQUIRED_ARGUMENTS_PER_SIDE)
                .toList();
    }

    private double aggregateTopThree(List<ScoredArgument> top) {
        double sum = top.stream().mapToDouble(ScoredArgument::score).sum();
        return roundTwoDecimals(sum / REQUIRED_ARGUMENTS_PER_SIDE);
    }

    private int decisiveEvidenceDimensions(
            List<ScoredArgument> decisive,
            Map<String, EvidenceEnvelope> evidenceById
    ) {
        Set<EvidenceDimension> dimensions = new HashSet<>();
        for (ScoredArgument argument : decisive) {
            for (String evidenceId : argument.assessment().acceptedEvidenceIds()) {
                EvidenceEnvelope envelope = evidenceById.get(evidenceId);
                if (envelope != null) {
                    dimensions.add(envelope.dimension());
                }
            }
        }
        return dimensions.size();
    }

    private AnalysisHorizon resolveHorizon(List<ScoredArgument> arguments) {
        EnumMap<AnalysisHorizon, Double> totals = new EnumMap<>(AnalysisHorizon.class);
        for (ScoredArgument argument : arguments) {
            AnalysisHorizon horizon = argument.root().point().horizon();
            if (horizon != null && horizon != AnalysisHorizon.UNSPECIFIED) {
                totals.merge(horizon, argument.score(), Double::sum);
            }
        }
        if (totals.isEmpty()) {
            return AnalysisHorizon.UNSPECIFIED;
        }
        List<Map.Entry<AnalysisHorizon, Double>> ranked = totals.entrySet().stream()
                .sorted(Map.Entry.<AnalysisHorizon, Double>comparingByValue().reversed()
                        .thenComparing(entry -> entry.getKey().name()))
                .toList();
        if (ranked.size() > 1
                && Math.abs(ranked.get(0).getValue() - ranked.get(1).getValue()) < 0.01) {
            return AnalysisHorizon.UNSPECIFIED;
        }
        return ranked.get(0).getKey();
    }

    private Map<String, EvidenceEnvelope> evidenceById(EvidenceLedger ledger) {
        Map<String, EvidenceEnvelope> byId = new LinkedHashMap<>();
        for (EvidenceEnvelope envelope : ledger.evidence()) {
            if (envelope != null && !envelope.evidenceId().isBlank()) {
                byId.putIfAbsent(envelope.evidenceId(), envelope);
            }
        }
        return Map.copyOf(byId);
    }

    private Map<String, Long> countRootPointIds(List<RootPoint> roots) {
        Map<String, Long> counts = new HashMap<>();
        for (RootPoint root : roots) {
            counts.merge(root.point().pointId(), 1L, Long::sum);
        }
        return Map.copyOf(counts);
    }

    private Map<String, Long> countNormalizedClaims(List<RootPoint> roots) {
        Map<String, Long> counts = new HashMap<>();
        for (RootPoint root : roots) {
            counts.merge(claimKey(root), 1L, Long::sum);
        }
        return Map.copyOf(counts);
    }

    private String claimKey(RootPoint root) {
        return root.side().name() + "|" + root.point().claim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{P}\\p{Z}\\s]+", "");
    }

    private DebateVerdict insufficientVerdict(
            String inputHash,
            String dataSnapshotHash,
            List<String> unresolvedPointIds,
            List<ArgumentAssessment> assessments
    ) {
        return new DebateVerdict(
                POLICY_ID,
                POLICY_VERSION,
                inputHash,
                dataSnapshotHash,
                0.0,
                0.0,
                LeadingSide.INSUFFICIENT,
                0.0,
                "HOLD",
                AnalysisHorizon.UNSPECIFIED,
                List.of(),
                unresolvedPointIds,
                assessments
        );
    }

    private EvidenceLedger safeLedger(AnalysisState state) {
        return state == null || state.getEvidenceLedger() == null
                ? EvidenceLedger.empty()
                : state.getEvidenceLedger();
    }

    private List<DebateTurn> safeTurns(AnalysisState state) {
        return state == null || state.getDebateTurns() == null
                ? List.of()
                : state.getDebateTurns().stream().filter(java.util.Objects::nonNull).toList();
    }

    private void appendEvidence(StringBuilder canonical, EvidenceEnvelope evidence) {
        append(canonical, evidence.evidenceId());
        append(canonical, evidence.dimension().name());
        append(canonical, evidence.capabilityId());
        append(canonical, evidence.targetKey());
        append(canonical, evidence.status().name());
        append(canonical, evidence.sourceRef());
        append(canonical, evidence.provider());
        append(canonical, evidence.observedAt());
        append(canonical, evidence.asOf());
        append(canonical, evidence.payloadHash());
        append(canonical, evidence.approvedReadOnly());
        append(canonical, evidence.timing() == null ? "UNKNOWN" : evidence.timing().toString());
    }

    private void appendTurn(StringBuilder canonical, DebateTurn turn) {
        append(canonical, turn.round());
        append(canonical, turn.side().name());
        turn.points().stream()
                .sorted(Comparator.comparing(DebatePoint::pointId)
                        .thenComparing(point -> point.type().name()))
                .forEach(point -> appendPoint(canonical, point));
    }

    private void appendPoint(StringBuilder canonical, DebatePoint point) {
        append(canonical, point.pointId());
        append(canonical, point.type().name());
        append(canonical, point.claim());
        append(canonical, point.horizon().name());
        append(canonical, point.reasoning());
        append(canonical, point.assumption());
        append(canonical, point.invalidationCondition());
        point.evidenceRefs().stream()
                .sorted(Comparator.comparing(EvidenceRef::evidenceId)
                        .thenComparing(EvidenceRef::excerpt))
                .forEach(ref -> {
                    append(canonical, ref.evidenceId());
                    append(canonical, ref.excerpt());
                });
        point.respondsToPointIds().stream().sorted().forEach(id -> append(canonical, id));
    }

    private void append(StringBuilder canonical, Object value) {
        String normalized = value == null ? "" : value.toString();
        canonical.append(normalized.length()).append(':').append(normalized).append('|');
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private double roundTwoDecimals(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private String safe(String value) {
        return value == null ? "" : value.strip();
    }

    private List<ScoredArgument> concat(
            List<ScoredArgument> first,
            List<ScoredArgument> second
    ) {
        List<ScoredArgument> result = new ArrayList<>(first.size() + second.size());
        result.addAll(first);
        result.addAll(second);
        return List.copyOf(result);
    }

    private record RootPoint(Side side, DebatePoint point) {
    }

    private record ScoredArgument(
            RootPoint root,
            ArgumentAssessment assessment,
            double score,
            double freshnessScore
    ) {
    }

    private record FreshnessWindow(long fullCreditDays, long staleDays) {
    }
}
