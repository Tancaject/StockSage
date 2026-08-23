package com.stocksage.service;

import com.stocksage.agent.intent.AnalysisDepth;
import com.stocksage.agent.intent.TimeSensitivity;
import com.stocksage.model.entity.ResearchMemoryEntry;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 对已通过数据库真源门禁的研究记忆执行时间衰减和确定性重排。
 *
 * <p>本类不访问数据库、向量库或模型。调用方负责先完成租户、撤销、索引状态和冲突
 * 赢家过滤，再把候选实体及其语义分数交给本类。</p>
 */
public final class ResearchMemoryRanker {

    private static final Comparator<ScoredMemory> SCORE_ORDER =
            Comparator.comparingDouble(ScoredMemory::effectiveScore).reversed()
                    .thenComparing(Comparator.comparingDouble(ScoredMemory::freshness).reversed())
                    .thenComparing(Comparator.comparingDouble(ScoredMemory::semanticScore).reversed())
                    .thenComparing(
                            ScoredMemory::referenceAt,
                            Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(
                            scored -> scored.entry().getId(),
                            Comparator.nullsLast(Comparator.reverseOrder()));

    private final Clock clock;
    private final ResearchMemoryProperties properties;

    /**
     * @param clock 提供可替换的当前时间
     * @param properties 衰减阈值和各阶段候选上限
     */
    public ResearchMemoryRanker(Clock clock, ResearchMemoryProperties properties) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    /**
     * 计算候选有效分，应用最低分、短名单和查询自适应最终数量上限。
     *
     * @param query 当前研究记忆查询；为 null 时按默认深度和时效处理
     * @param entries 已通过数据库与冲突门禁的候选实体
     * @param semanticScores 以记忆 ID 为键的向量语义分数
     * @return 有效分降序的最终记忆；候选不足时不会补入低分或无关记录
     */
    public List<ScoredMemory> rank(
            ResearchMemoryQuery query,
            List<ResearchMemoryEntry> entries,
            Map<Long, Double> semanticScores
    ) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        Map<Long, Double> safeScores = semanticScores == null ? Map.of() : semanticScores;
        ResearchMemoryQuery safeQuery = query == null
                ? new ResearchMemoryQuery(null, null, null, null, null, null)
                : query;
        LocalDateTime now = LocalDateTime.now(clock);
        boolean historical = safeQuery.timeSensitivity() == TimeSensitivity.HISTORICAL;

        List<ScoredMemory> shortlist = entries.stream()
                .filter(Objects::nonNull)
                .map(entry -> score(entry, semanticScore(entry, safeScores), now, historical))
                .filter(scored -> scored.effectiveScore() > 0.0)
                .filter(scored -> scored.effectiveScore() >= properties.getMinEffectiveScore())
                .sorted(SCORE_ORDER)
                .limit(properties.getShortlistK())
                .toList();

        return shortlist.stream()
                .limit(finalTopK(safeQuery))
                .toList();
    }

    private ScoredMemory score(
            ResearchMemoryEntry entry,
            double semanticScore,
            LocalDateTime now,
            boolean historical
    ) {
        LocalDateTime referenceAt = entry.getDataCutoffAt() != null
                ? entry.getDataCutoffAt()
                : entry.getCreatedAt();
        long ageDays = ageDays(referenceAt, now);
        double freshness = historical
                ? 1.0
                : Math.pow(2.0, -(double) ageDays / properties.getDecayHalfLifeDays());
        double nonNegativeSemanticScore = Math.max(0.0, semanticScore);
        double effectiveScore = nonNegativeSemanticScore * freshness;
        return new ScoredMemory(
                entry,
                semanticScore,
                freshness,
                effectiveScore,
                ageDays,
                referenceAt);
    }

    private double semanticScore(
            ResearchMemoryEntry entry,
            Map<Long, Double> semanticScores
    ) {
        if (entry.getId() == null) {
            return 0.0;
        }
        Double score = semanticScores.get(entry.getId());
        return score == null || !Double.isFinite(score) ? 0.0 : score;
    }

    private long ageDays(LocalDateTime referenceAt, LocalDateTime now) {
        if (referenceAt == null || referenceAt.isAfter(now)) {
            return 0L;
        }
        return Math.max(0L, Duration.between(referenceAt, now).toDays());
    }

    /** 返回本次查询实际使用的最终记忆上限，供排序与 Trace 共用。 */
    int finalTopK(ResearchMemoryQuery query) {
        if (query.timeSensitivity() == TimeSensitivity.HISTORICAL) {
            return properties.getDeepTopK();
        }
        AnalysisDepth depth = query.analysisDepth();
        return switch (depth) {
            case BRIEF -> properties.getBriefTopK();
            case DEEP -> properties.getDeepTopK();
            case STANDARD, UNSPECIFIED -> properties.getTopK();
        };
    }

    /**
     * 一条记忆在本次查询中的动态评分结果。
     *
     * @param entry 通过真源与冲突门禁的记忆实体
     * @param semanticScore 向量检索返回的原始语义分数
     * @param freshness 本次查询使用的新鲜度因子
     * @param effectiveScore 语义分数与新鲜度的乘积
     * @param ageDays 相对于当前时钟的非负完整天数
     * @param referenceAt 数据截止时间；缺失时为创建时间，两者都缺失时为 null
     */
    public record ScoredMemory(
            ResearchMemoryEntry entry,
            double semanticScore,
            double freshness,
            double effectiveScore,
            long ageDays,
            LocalDateTime referenceAt
    ) {
        public ScoredMemory {
            Objects.requireNonNull(entry, "entry");
        }
    }
}
