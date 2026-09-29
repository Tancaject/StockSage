package com.stocksage.knowledge;

import com.stocksage.model.entity.InvestmentReportVersion;
import com.stocksage.model.entity.ResearchMemoryEntry;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 对同一研究记忆冲突组执行确定性赢家解析。
 *
 * <p>调用方负责按 userId、ticker 和 analysis horizon 形成单一冲突组。本解析器不访问数据库、
 * 不调用模型，也不修改传入实体；它只根据撤销状态、来源审核状态和稳定时间字段返回决策。</p>
 */
public final class ResearchMemoryConflictResolver {

    /**
     * 解析一个冲突组。
     *
     * <p>优先级依次为：可用性、dataCutoffAt（空时回退 createdAt）、APPROVED、generatedAt、
     * entry id。若最高优先级候选在数据时点和审核等级完全相同但建议方向不同，则组保持未消解，
     * 不通过 generatedAt 或 id 静默选边。</p>
     *
     * @param candidates 同一冲突组内的持久化候选；null 等价于空列表
     * @return 组状态、可选赢家和每条 entry 的确定性决策
     */
    public Resolution resolve(List<Candidate> candidates) {
        List<Candidate> ordered = normalizeCandidates(candidates);
        if (ordered.isEmpty()) {
            return new Resolution(GroupStatus.NO_ELIGIBLE_CANDIDATE, null, List.of());
        }

        Map<Long, Reason> ineligibleReasons = new LinkedHashMap<>();
        List<Candidate> eligible = new ArrayList<>();
        for (Candidate candidate : ordered) {
            Reason ineligibleReason = ineligibleReason(candidate);
            if (ineligibleReason == null) {
                eligible.add(candidate);
            } else {
                ineligibleReasons.put(candidate.entry().getId(), ineligibleReason);
            }
        }

        if (eligible.isEmpty()) {
            return new Resolution(
                    GroupStatus.NO_ELIGIBLE_CANDIDATE,
                    null,
                    decisionsWithoutWinner(ordered, ineligibleReasons)
            );
        }

        LocalDateTime newestDataCutoff = eligible.stream()
                .map(this::effectiveDataCutoff)
                .max(Comparator.nullsFirst(Comparator.naturalOrder()))
                .orElse(null);
        List<Candidate> freshest = eligible.stream()
                .filter(candidate -> Objects.equals(effectiveDataCutoff(candidate), newestDataCutoff))
                .toList();

        int highestReviewRank = freshest.stream()
                .mapToInt(this::reviewRank)
                .max()
                .orElse(0);
        List<Candidate> highestPriority = freshest.stream()
                .filter(candidate -> reviewRank(candidate) == highestReviewRank)
                .toList();

        Set<RecommendationDirection> directions = new HashSet<>();
        highestPriority.forEach(candidate -> directions.add(direction(candidate.recommendation())));
        if (directions.size() > 1) {
            return unresolved(ordered, highestPriority, ineligibleReasons);
        }

        Candidate winner = highestPriority.stream()
                .max(Comparator
                        .comparing(Candidate::generatedAt,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(candidate -> candidate.entry().getId()))
                .orElseThrow();
        Reason winnerReason = winnerReason(eligible, freshest, highestPriority, winner);
        return resolved(ordered, highestPriority, winner, winnerReason, ineligibleReasons);
    }

    /** 复制、按 entry id 排序并拒绝重复或未持久化的候选。 */
    private List<Candidate> normalizeCandidates(List<Candidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<Candidate> ordered = new ArrayList<>(candidates.size());
        Set<Long> entryIds = new HashSet<>();
        for (Candidate candidate : candidates) {
            Candidate required = Objects.requireNonNull(candidate, "candidate is required");
            Long entryId = required.entry().getId();
            if (entryId == null) {
                throw new IllegalArgumentException("persisted research memory entry id is required");
            }
            if (!entryIds.add(entryId)) {
                throw new IllegalArgumentException("duplicate research memory entry id: " + entryId);
            }
            ordered.add(required);
        }
        ordered.sort(Comparator.comparing(candidate -> candidate.entry().getId()));
        return List.copyOf(ordered);
    }

    /** 返回候选无资格参与选举的原因；null 表示可参与。 */
    private Reason ineligibleReason(Candidate candidate) {
        ResearchMemoryEntry entry = candidate.entry();
        if (entry.getRevokedAt() != null
                || entry.getVectorStatus() == ResearchMemoryEntry.VectorStatus.REVOKED) {
            return Reason.INELIGIBLE_REVOKED;
        }
        InvestmentReportVersion.ReviewStatus reviewStatus = effectiveReviewStatus(candidate);
        if (reviewStatus == InvestmentReportVersion.ReviewStatus.REJECTED
                || reviewStatus == InvestmentReportVersion.ReviewStatus.NEEDS_RESEARCH) {
            return Reason.INELIGIBLE_NEGATIVE_REVIEW;
        }
        if (direction(candidate.recommendation()) == null) {
            return Reason.INELIGIBLE_RECOMMENDATION;
        }
        return null;
    }

    /** 报告审核状态缺失时沿用现有兼容边界，按 DRAFT 处理。 */
    private InvestmentReportVersion.ReviewStatus effectiveReviewStatus(Candidate candidate) {
        return candidate.sourceReviewStatus() == null
                ? InvestmentReportVersion.ReviewStatus.DRAFT
                : candidate.sourceReviewStatus();
    }

    /** APPROVED 在相同数据时点下高于其他可用审核状态。 */
    private int reviewRank(Candidate candidate) {
        return effectiveReviewStatus(candidate) == InvestmentReportVersion.ReviewStatus.APPROVED ? 1 : 0;
    }

    /** dataCutoffAt 缺失时使用真相行创建时间，二者都缺失时按最旧处理。 */
    private LocalDateTime effectiveDataCutoff(Candidate candidate) {
        ResearchMemoryEntry entry = candidate.entry();
        return entry.getDataCutoffAt() == null ? entry.getCreatedAt() : entry.getDataCutoffAt();
    }

    /** 把稳定五档建议归并为冲突判断所需的三种方向。 */
    private RecommendationDirection direction(String recommendation) {
        if (recommendation == null) {
            return null;
        }
        return switch (recommendation.trim().toUpperCase(Locale.ROOT)) {
            case "BUY", "OVERWEIGHT" -> RecommendationDirection.BULLISH;
            case "HOLD" -> RecommendationDirection.NEUTRAL;
            case "UNDERWEIGHT", "SELL" -> RecommendationDirection.BEARISH;
            default -> null;
        };
    }

    /** 为当前赢家给出实际生效的最高优先级原因。 */
    private Reason winnerReason(
            List<Candidate> eligible,
            List<Candidate> freshest,
            List<Candidate> highestPriority,
            Candidate winner
    ) {
        if (eligible.size() == 1) {
            return Reason.ONLY_ELIGIBLE_CANDIDATE;
        }
        if (freshest.size() < eligible.size()) {
            return Reason.NEWEST_DATA_CUTOFF;
        }
        if (highestPriority.size() < freshest.size()) {
            return Reason.HUMAN_APPROVED_AT_SAME_DATA_CUTOFF;
        }
        boolean generatedAtBreaksTie = highestPriority.stream()
                .anyMatch(candidate -> !Objects.equals(candidate.generatedAt(), winner.generatedAt()));
        return generatedAtBreaksTie
                ? Reason.NEWEST_GENERATED_AT
                : Reason.HIGHEST_ID_TIE_BREAK;
    }

    /** 构造已消解组的逐条决策。 */
    private Resolution resolved(
            List<Candidate> ordered,
            List<Candidate> highestPriority,
            Candidate winner,
            Reason winnerReason,
            Map<Long, Reason> ineligibleReasons
    ) {
        Set<Long> topIds = highestPriority.stream()
                .map(candidate -> candidate.entry().getId())
                .collect(java.util.stream.Collectors.toSet());
        List<EntryDecision> decisions = new ArrayList<>(ordered.size());
        for (Candidate candidate : ordered) {
            Long entryId = candidate.entry().getId();
            Reason ineligible = ineligibleReasons.get(entryId);
            if (ineligible != null) {
                decisions.add(new EntryDecision(entryId, EntryStatus.SUPERSEDED, ineligible));
            } else if (entryId.equals(winner.entry().getId())) {
                decisions.add(new EntryDecision(entryId, EntryStatus.CURRENT, winnerReason));
            } else {
                Reason reason = topIds.contains(entryId)
                        ? Reason.SUPERSEDED_BY_CURRENT_WINNER
                        : Reason.SUPERSEDED_BY_HIGHER_PRIORITY_CANDIDATE;
                decisions.add(new EntryDecision(entryId, EntryStatus.SUPERSEDED, reason));
            }
        }
        return new Resolution(
                GroupStatus.RESOLVED,
                winner.entry().getId(),
                decisions
        );
    }

    /** 构造最高优先级候选方向冲突时的逐条决策。 */
    private Resolution unresolved(
            List<Candidate> ordered,
            List<Candidate> highestPriority,
            Map<Long, Reason> ineligibleReasons
    ) {
        Set<Long> conflictedIds = highestPriority.stream()
                .map(candidate -> candidate.entry().getId())
                .collect(java.util.stream.Collectors.toSet());
        List<EntryDecision> decisions = new ArrayList<>(ordered.size());
        for (Candidate candidate : ordered) {
            Long entryId = candidate.entry().getId();
            Reason ineligible = ineligibleReasons.get(entryId);
            if (ineligible != null) {
                decisions.add(new EntryDecision(entryId, EntryStatus.SUPERSEDED, ineligible));
            } else if (conflictedIds.contains(entryId)) {
                decisions.add(new EntryDecision(
                        entryId,
                        EntryStatus.CONFLICTED,
                        Reason.EQUAL_PRIORITY_DIRECTION_CONFLICT
                ));
            } else {
                decisions.add(new EntryDecision(
                        entryId,
                        EntryStatus.SUPERSEDED,
                        Reason.SUPERSEDED_BY_HIGHER_PRIORITY_CANDIDATE
                ));
            }
        }
        return new Resolution(GroupStatus.UNRESOLVED, null, decisions);
    }

    /** 当组内没有合格候选时，所有条目均保留为不可用历史。 */
    private List<EntryDecision> decisionsWithoutWinner(
            List<Candidate> ordered,
            Map<Long, Reason> ineligibleReasons
    ) {
        return ordered.stream()
                .map(candidate -> new EntryDecision(
                        candidate.entry().getId(),
                        EntryStatus.SUPERSEDED,
                        ineligibleReasons.get(candidate.entry().getId())
                ))
                .toList();
    }

    /** 解析器输入；recommendation 和 generatedAt 来自对应的来源报告版本。 */
    public record Candidate(
            ResearchMemoryEntry entry,
            String recommendation,
            InvestmentReportVersion.ReviewStatus sourceReviewStatus,
            LocalDateTime generatedAt
    ) {
        public Candidate {
            Objects.requireNonNull(entry, "research memory entry is required");
        }
    }

    /** 冲突组解析结果。winnerId 仅在 RESOLVED 时存在。 */
    public record Resolution(
            GroupStatus groupStatus,
            Long winnerId,
            List<EntryDecision> entryDecisions
    ) {
        public Resolution {
            Objects.requireNonNull(groupStatus, "group status is required");
            entryDecisions = entryDecisions == null ? List.of() : List.copyOf(entryDecisions);
            if (groupStatus == GroupStatus.RESOLVED && winnerId == null) {
                throw new IllegalArgumentException("resolved group requires a winner");
            }
            if (groupStatus != GroupStatus.RESOLVED && winnerId != null) {
                throw new IllegalArgumentException("unresolved group cannot expose a winner");
            }
        }
    }

    /** 单条记忆相对于当前冲突组的业务状态。 */
    public record EntryDecision(Long entryId, EntryStatus status, Reason reason) {
        public EntryDecision {
            Objects.requireNonNull(entryId, "entry id is required");
            Objects.requireNonNull(status, "entry status is required");
            Objects.requireNonNull(reason, "resolution reason is required");
        }
    }

    /** 冲突组是否产生唯一赢家。 */
    public enum GroupStatus {
        RESOLVED,
        UNRESOLVED,
        NO_ELIGIBLE_CANDIDATE
    }

    /** 每条候选最终写入 MySQL 的业务解析状态。 */
    public enum EntryStatus {
        CURRENT,
        SUPERSEDED,
        CONFLICTED
    }

    /** 五档建议归并后的方向，仅用于确定性冲突判断。 */
    public enum RecommendationDirection {
        BULLISH,
        NEUTRAL,
        BEARISH
    }

    /** 机器可读的稳定解析原因。 */
    public enum Reason {
        ONLY_ELIGIBLE_CANDIDATE,
        NEWEST_DATA_CUTOFF,
        HUMAN_APPROVED_AT_SAME_DATA_CUTOFF,
        NEWEST_GENERATED_AT,
        HIGHEST_ID_TIE_BREAK,
        SUPERSEDED_BY_CURRENT_WINNER,
        SUPERSEDED_BY_HIGHER_PRIORITY_CANDIDATE,
        INELIGIBLE_REVOKED,
        INELIGIBLE_NEGATIVE_REVIEW,
        INELIGIBLE_RECOMMENDATION,
        EQUAL_PRIORITY_DIRECTION_CONFLICT
    }
}
