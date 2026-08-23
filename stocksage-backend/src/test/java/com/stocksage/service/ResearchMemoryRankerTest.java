package com.stocksage.service;

import com.stocksage.agent.intent.AnalysisDepth;
import com.stocksage.agent.intent.TimeSensitivity;
import com.stocksage.model.entity.ResearchMemoryEntry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ResearchMemoryRankerTest {

    private static final Instant NOW_INSTANT = Instant.parse("2026-08-23T00:00:00Z");
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(NOW_INSTANT, ZoneOffset.UTC);

    private final ResearchMemoryProperties properties = new ResearchMemoryProperties();
    private final ResearchMemoryRanker ranker = new ResearchMemoryRanker(
            Clock.fixed(NOW_INSTANT, ZoneOffset.UTC),
            properties
    );

    @Test
    void appliesNinetyDayHalfLifeAndFallsBackToCreatedAt() {
        ResearchMemoryEntry dataCutoffEntry = entry(1L, NOW.minusDays(365));
        dataCutoffEntry.setDataCutoffAt(NOW.minusDays(90));
        ResearchMemoryEntry createdAtFallback = entry(2L, NOW.minusDays(90));

        List<ResearchMemoryRanker.ScoredMemory> result = ranker.rank(
                query(AnalysisDepth.STANDARD, TimeSensitivity.RECENT),
                List.of(dataCutoffEntry, createdAtFallback),
                Map.of(1L, 0.8, 2L, 0.6)
        );

        assertThat(result).filteredOn(scored -> scored.entry().getId().equals(1L))
                .singleElement()
                .satisfies(scored -> {
                    assertThat(scored.referenceAt()).isEqualTo(NOW.minusDays(90));
                    assertThat(scored.ageDays()).isEqualTo(90L);
                    assertThat(scored.freshness()).isCloseTo(0.5, within(1.0e-12));
                    assertThat(scored.effectiveScore()).isCloseTo(0.4, within(1.0e-12));
                });
        assertThat(result).filteredOn(scored -> scored.entry().getId().equals(2L))
                .singleElement()
                .satisfies(scored -> {
                    assertThat(scored.referenceAt()).isEqualTo(NOW.minusDays(90));
                    assertThat(scored.ageDays()).isEqualTo(90L);
                    assertThat(scored.freshness()).isCloseTo(0.5, within(1.0e-12));
                });
    }

    @Test
    void historicalQueryReportsAgeButDoesNotDecayScore() {
        ResearchMemoryEntry oldEntry = entry(1L, NOW.minusDays(365));

        ResearchMemoryRanker.ScoredMemory scored = ranker.rank(
                query(AnalysisDepth.BRIEF, TimeSensitivity.HISTORICAL),
                List.of(oldEntry),
                Map.of(1L, 0.72)
        ).get(0);

        assertThat(scored.ageDays()).isEqualTo(365L);
        assertThat(scored.freshness()).isEqualTo(1.0);
        assertThat(scored.effectiveScore()).isEqualTo(0.72);
    }

    @Test
    void selectsFourSixOrEightByDepthAndHistoricalIntent() {
        List<ResearchMemoryEntry> entries = entries(10);
        Map<Long, Double> scores = scores(entries);

        assertThat(ranker.rank(
                query(AnalysisDepth.BRIEF, TimeSensitivity.RECENT), entries, scores)).hasSize(4);
        assertThat(ranker.rank(
                query(AnalysisDepth.STANDARD, TimeSensitivity.RECENT), entries, scores)).hasSize(6);
        assertThat(ranker.rank(
                query(AnalysisDepth.DEEP, TimeSensitivity.RECENT), entries, scores)).hasSize(8);
        assertThat(ranker.rank(
                query(AnalysisDepth.BRIEF, TimeSensitivity.HISTORICAL), entries, scores)).hasSize(8);
    }

    @Test
    void shortlistCapsDeepResultsAndInsufficientCandidatesAreNotBackfilled() {
        properties.setShortlistK(5);
        List<ResearchMemoryEntry> abundant = entries(10);

        assertThat(ranker.rank(
                query(AnalysisDepth.DEEP, TimeSensitivity.RECENT),
                abundant,
                scores(abundant)
        )).hasSize(5);

        List<ResearchMemoryEntry> insufficient = abundant.subList(0, 2);
        assertThat(ranker.rank(
                query(AnalysisDepth.STANDARD, TimeSensitivity.RECENT),
                insufficient,
                scores(insufficient)
        )).hasSize(2);
    }

    @Test
    void zeroNegativeAndNonFiniteScoresDoNotFillTheFinalQuota() {
        List<ResearchMemoryEntry> entries = entries(5);

        List<ResearchMemoryRanker.ScoredMemory> result = ranker.rank(
                query(AnalysisDepth.STANDARD, TimeSensitivity.RECENT),
                entries,
                Map.of(
                        1L, 0.8,
                        2L, 0.0,
                        3L, -0.2,
                        4L, Double.NaN,
                        5L, Double.POSITIVE_INFINITY)
        );

        assertThat(result).extracting(scored -> scored.entry().getId())
                .containsExactly(1L);
    }

    private ResearchMemoryQuery query(AnalysisDepth depth, TimeSensitivity timeSensitivity) {
        return new ResearchMemoryQuery(
                "u-a", "NVDA", "query", "trace", depth, timeSensitivity);
    }

    private List<ResearchMemoryEntry> entries(int count) {
        List<ResearchMemoryEntry> entries = new ArrayList<>(count);
        for (int index = 1; index <= count; index++) {
            entries.add(entry((long) index, NOW.minusDays(index)));
        }
        return entries;
    }

    private Map<Long, Double> scores(List<ResearchMemoryEntry> entries) {
        Map<Long, Double> scores = new LinkedHashMap<>();
        for (ResearchMemoryEntry entry : entries) {
            scores.put(entry.getId(), 1.0 - entry.getId() / 100.0);
        }
        return scores;
    }

    private ResearchMemoryEntry entry(Long id, LocalDateTime createdAt) {
        ResearchMemoryEntry entry = new ResearchMemoryEntry();
        entry.setId(id);
        entry.setCreatedAt(createdAt);
        return entry;
    }
}
