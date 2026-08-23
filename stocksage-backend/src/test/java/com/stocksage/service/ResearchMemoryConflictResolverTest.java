package com.stocksage.service;

import com.stocksage.model.entity.InvestmentReportVersion;
import com.stocksage.model.entity.ResearchMemoryEntry;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class ResearchMemoryConflictResolverTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 23, 0, 0);

    private final ResearchMemoryConflictResolver resolver = new ResearchMemoryConflictResolver();

    @Test
    void newerDataWinsEvenWhenTheOlderCandidateHasAnotherDirection() {
        ResearchMemoryConflictResolver.Resolution result = resolver.resolve(List.of(
                candidate(1L, NOW.minusDays(30), "SELL",
                        InvestmentReportVersion.ReviewStatus.DRAFT, NOW.minusDays(29)),
                candidate(2L, NOW.minusDays(1), "BUY",
                        InvestmentReportVersion.ReviewStatus.DRAFT, NOW)
        ));

        assertThat(result.groupStatus())
                .isEqualTo(ResearchMemoryConflictResolver.GroupStatus.RESOLVED);
        assertThat(result.winnerId()).isEqualTo(2L);
        assertThat(result.entryDecisions())
                .extracting(
                        ResearchMemoryConflictResolver.EntryDecision::entryId,
                        ResearchMemoryConflictResolver.EntryDecision::status,
                        ResearchMemoryConflictResolver.EntryDecision::reason)
                .containsExactly(
                        tuple(1L,
                                ResearchMemoryConflictResolver.EntryStatus.SUPERSEDED,
                                ResearchMemoryConflictResolver.Reason
                                        .SUPERSEDED_BY_HIGHER_PRIORITY_CANDIDATE),
                        tuple(2L,
                                ResearchMemoryConflictResolver.EntryStatus.CURRENT,
                                ResearchMemoryConflictResolver.Reason.NEWEST_DATA_CUTOFF)
                );
    }

    @Test
    void approvedCandidateWinsAtTheSameDataCutoff() {
        ResearchMemoryConflictResolver.Resolution result = resolver.resolve(List.of(
                candidate(1L, NOW, "SELL",
                        InvestmentReportVersion.ReviewStatus.APPROVED, NOW.minusHours(1)),
                candidate(2L, NOW, "BUY",
                        InvestmentReportVersion.ReviewStatus.DRAFT, NOW)
        ));

        assertThat(result.groupStatus())
                .isEqualTo(ResearchMemoryConflictResolver.GroupStatus.RESOLVED);
        assertThat(result.winnerId()).isEqualTo(1L);
        assertThat(result.entryDecisions())
                .filteredOn(decision -> decision.entryId().equals(1L))
                .singleElement()
                .satisfies(decision -> {
                    assertThat(decision.status())
                            .isEqualTo(ResearchMemoryConflictResolver.EntryStatus.CURRENT);
                    assertThat(decision.reason()).isEqualTo(
                            ResearchMemoryConflictResolver.Reason
                                    .HUMAN_APPROVED_AT_SAME_DATA_CUTOFF);
                });
    }

    @Test
    void equalPriorityOppositeDirectionsRemainUnresolved() {
        ResearchMemoryConflictResolver.Resolution result = resolver.resolve(List.of(
                candidate(1L, NOW, "BUY",
                        InvestmentReportVersion.ReviewStatus.APPROVED, NOW.minusHours(1)),
                candidate(2L, NOW, "SELL",
                        InvestmentReportVersion.ReviewStatus.APPROVED, NOW)
        ));

        assertThat(result.groupStatus())
                .isEqualTo(ResearchMemoryConflictResolver.GroupStatus.UNRESOLVED);
        assertThat(result.winnerId()).isNull();
        assertThat(result.entryDecisions())
                .extracting(
                        ResearchMemoryConflictResolver.EntryDecision::status,
                        ResearchMemoryConflictResolver.EntryDecision::reason)
                .containsOnly(tuple(
                        ResearchMemoryConflictResolver.EntryStatus.CONFLICTED,
                        ResearchMemoryConflictResolver.Reason.EQUAL_PRIORITY_DIRECTION_CONFLICT));
    }

    @Test
    void revokedAndNegativeReviewCandidatesAreIneligible() {
        ResearchMemoryConflictResolver.Candidate eligible = candidate(
                1L, NOW.minusDays(10), "HOLD",
                InvestmentReportVersion.ReviewStatus.DRAFT, NOW.minusDays(9));
        ResearchMemoryConflictResolver.Candidate revoked = candidate(
                2L, NOW, "BUY",
                InvestmentReportVersion.ReviewStatus.APPROVED, NOW);
        revoked.entry().setRevokedAt(NOW);
        ResearchMemoryConflictResolver.Candidate rejected = candidate(
                3L, NOW, "BUY",
                InvestmentReportVersion.ReviewStatus.REJECTED, NOW);
        ResearchMemoryConflictResolver.Candidate needsResearch = candidate(
                4L, NOW, "SELL",
                InvestmentReportVersion.ReviewStatus.NEEDS_RESEARCH, NOW);

        ResearchMemoryConflictResolver.Resolution result = resolver.resolve(
                List.of(eligible, revoked, rejected, needsResearch));

        assertThat(result.groupStatus())
                .isEqualTo(ResearchMemoryConflictResolver.GroupStatus.RESOLVED);
        assertThat(result.winnerId()).isEqualTo(1L);
        assertThat(result.entryDecisions())
                .extracting(
                        ResearchMemoryConflictResolver.EntryDecision::entryId,
                        ResearchMemoryConflictResolver.EntryDecision::reason)
                .containsExactly(
                        tuple(1L,
                                ResearchMemoryConflictResolver.Reason.ONLY_ELIGIBLE_CANDIDATE),
                        tuple(2L,
                                ResearchMemoryConflictResolver.Reason.INELIGIBLE_REVOKED),
                        tuple(3L,
                                ResearchMemoryConflictResolver.Reason.INELIGIBLE_NEGATIVE_REVIEW),
                        tuple(4L,
                                ResearchMemoryConflictResolver.Reason.INELIGIBLE_NEGATIVE_REVIEW)
                );
    }

    private ResearchMemoryConflictResolver.Candidate candidate(
            Long id,
            LocalDateTime dataCutoffAt,
            String recommendation,
            InvestmentReportVersion.ReviewStatus reviewStatus,
            LocalDateTime generatedAt
    ) {
        ResearchMemoryEntry entry = new ResearchMemoryEntry();
        entry.setId(id);
        entry.setCreatedAt(dataCutoffAt.minusDays(1));
        entry.setDataCutoffAt(dataCutoffAt);
        entry.setVectorStatus(ResearchMemoryEntry.VectorStatus.INDEXED);
        return new ResearchMemoryConflictResolver.Candidate(
                entry, recommendation, reviewStatus, generatedAt);
    }
}
