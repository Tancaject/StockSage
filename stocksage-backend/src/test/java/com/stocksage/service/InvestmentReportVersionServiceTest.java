package com.stocksage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.harness.DeepResearchCompletionPolicy;
import com.stocksage.harness.EvidenceLedger;
import com.stocksage.harness.HarnessModels.EvidenceDimension;
import com.stocksage.harness.HarnessModels.EvidenceEnvelope;
import com.stocksage.harness.HarnessModels.EvidenceStatus;
import com.stocksage.harness.HarnessModels.TargetIdentity;
import com.stocksage.model.dto.AnalysisState;
import com.stocksage.model.dto.InvestmentReport;
import com.stocksage.model.dto.InvestmentReportReviewRequest;
import com.stocksage.model.entity.InvestmentReportReview;
import com.stocksage.model.entity.InvestmentReportVersion;
import com.stocksage.repository.InvestmentReportReviewRepository;
import com.stocksage.repository.InvestmentReportVersionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InvestmentReportVersionServiceTest {

    private final InvestmentReportVersionRepository repository = mock(InvestmentReportVersionRepository.class);
    private final InvestmentReportReviewRepository reviewRepository = mock(InvestmentReportReviewRepository.class);
    private final ResearchMemoryService researchMemoryService = mock(ResearchMemoryService.class);
    private final InvestmentReportVersionService service = new InvestmentReportVersionService(
            repository,
            reviewRepository,
            new ObjectMapper().findAndRegisterModules(),
            mock(ApplicationEventPublisher.class),
            researchMemoryService,
            new DeepResearchCompletionPolicy()
    );

    @Test
    void computesStableHashesForSameSnapshotAndContext() {
        AnalysisState state = analysisState("NVDA", "market-v1", "risks-v1", "news-v1", "Should I buy NVDA?");

        service.prepareHashes(state);
        String firstDataSnapshotHash = state.getDataSnapshotHash();
        String firstContextHash = state.getContextHash();

        service.prepareHashes(state);

        assertThat(state.getDataSnapshotHash()).isEqualTo(firstDataSnapshotHash);
        assertThat(state.getContextHash()).isEqualTo(firstContextHash);
        assertThat(firstDataSnapshotHash).hasSize(64);
        assertThat(firstContextHash).hasSize(64);
    }

    @Test
    void dataSnapshotHashChangesWhenEvidenceChanges() {
        AnalysisState first = analysisState("NVDA", "market-v1", "risks-v1", "news-v1", "Should I buy NVDA?");
        AnalysisState second = analysisState("NVDA", "market-v2", "risks-v1", "news-v1", "Should I buy NVDA?");

        assertThat(service.computeDataSnapshotHash(first))
                .isNotEqualTo(service.computeDataSnapshotHash(second));
    }

    @Test
    void dataSnapshotHashIgnoresVolatileNewsSearchText() {
        AnalysisState first = analysisState("NVDA", "market-v1", "risks-v1", "search result order A", "Should I buy NVDA?");
        AnalysisState second = analysisState("NVDA", "market-v1", "risks-v1", "search result order B", "Should I buy NVDA?");

        assertThat(service.computeDataSnapshotHash(first))
                .isEqualTo(service.computeDataSnapshotHash(second));
    }

    @Test
    void contextHashChangesWhenPromptContextChanges() {
        AnalysisState first = analysisState("NVDA", "market-v1", "risks-v1", "news-v1", "Should I buy NVDA?");
        AnalysisState second = analysisState("NVDA", "market-v1", "risks-v1", "news-v1", "Is NVDA still cheap?");

        service.prepareHashes(first);
        service.prepareHashes(second);

        assertThat(first.getDataSnapshotHash()).isEqualTo(second.getDataSnapshotHash());
        assertThat(first.getContextHash()).isNotEqualTo(second.getContextHash());
    }

    @Test
    void persistsNextTickerVersionWhenSnapshotIsNew() {
        AnalysisState state = analysisState("NVDA", "market-v2", "risks-v1", "news-v1", "Should I buy NVDA?");
        state.setInvestmentReport(InvestmentReport.builder()
                .recommendation("OVERWEIGHT")
                .rationale(List.of("Fresh evidence is stronger than the prior version."))
                .build());
        service.prepareHashes(state);

        InvestmentReportVersion previous = new InvestmentReportVersion();
        previous.setReportVersion(2);
        when(repository.findByUserIdAndTickerAndDataSnapshotHashAndContextHash(
                eq("u_001"),
                eq("NVDA"),
                eq(state.getDataSnapshotHash()),
                eq(state.getContextHash())
        )).thenReturn(Optional.empty());
        when(repository.findTopByUserIdAndTickerOrderByReportVersionDesc("u_001", "NVDA"))
                .thenReturn(Optional.of(previous));
        when(repository.saveAndFlush(any(InvestmentReportVersion.class)))
                .thenAnswer(invocation -> (InvestmentReportVersion) invocation.getArgument(0));

        InvestmentReport persisted = service.persistReportVersion("u_001", 10L, state, "STRONG", "qwen3.6-max");

        assertThat(persisted.getReportVersion()).isEqualTo(3);
        assertThat(persisted.getDataSnapshotHash()).isEqualTo(state.getDataSnapshotHash());
        assertThat(persisted.getContextHash()).isEqualTo(state.getContextHash());
        assertThat(persisted.getReusedFromCache()).isFalse();
        assertThat(persisted.getModelName()).isEqualTo("qwen3.6-max");

        ArgumentCaptor<InvestmentReportVersion> entityCaptor = ArgumentCaptor.forClass(InvestmentReportVersion.class);
        verify(repository).saveAndFlush(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getReportVersion()).isEqualTo(3);
        assertThat(entityCaptor.getValue().getTicker()).isEqualTo("NVDA");
        assertThat(entityCaptor.getValue().getModelName()).isEqualTo("qwen3.6-max");
    }

    @Test
    void persistReportVersionWithMetadataReturnsSavedRowId() {
        AnalysisState state = analysisState("NVDA", "market-v2", "risks-v1", "news-v1", "Should I buy NVDA?");
        state.setInvestmentReport(InvestmentReport.builder()
                .recommendation("BUY")
                .rationale(List.of("Fresh report from this request."))
                .build());
        service.prepareHashes(state);

        when(repository.findByUserIdAndTickerAndDataSnapshotHashAndContextHash(
                eq("u_001"),
                eq("NVDA"),
                eq(state.getDataSnapshotHash()),
                eq(state.getContextHash())
        )).thenReturn(Optional.empty());
        when(repository.findTopByUserIdAndTickerOrderByReportVersionDesc("u_001", "NVDA"))
                .thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(InvestmentReportVersion.class)))
                .thenAnswer(invocation -> {
                    InvestmentReportVersion entity = invocation.getArgument(0);
                    entity.setId(123L);
                    return entity;
                });

        InvestmentReportVersionService.PersistedReportVersion persisted =
                service.persistReportVersionWithMetadata("u_001", 10L, state, "STRONG", "qwen3.6-max");

        assertThat(persisted.reportVersionId()).isEqualTo(123L);
        assertThat(persisted.report().getReportVersion()).isEqualTo(1);
        assertThat(persisted.reused()).isFalse();
    }

    @Test
    void reusesReportOnlyWhenSnapshotAndContextMatch() throws Exception {
        AnalysisState state = analysisState("NVDA", "market-v1", "risks-v1", "news-v1", "Should I buy NVDA?");
        service.prepareHashes(state);

        InvestmentReport storedReport = verifiedReusableReport("NVDA", "e-market");
        InvestmentReportVersion existing = new InvestmentReportVersion();
        existing.setUserId("u_001");
        existing.setTicker("NVDA");
        existing.setReportVersion(7);
        existing.setReviewStatus(InvestmentReportVersion.ReviewStatus.DRAFT);
        existing.setDataSnapshotHash(state.getDataSnapshotHash());
        existing.setContextHash(state.getContextHash());
        existing.setReportJson(new ObjectMapper().findAndRegisterModules().writeValueAsString(storedReport));

        when(repository.findByUserIdAndTickerAndDataSnapshotHashAndContextHash(
                "u_001",
                "NVDA",
                state.getDataSnapshotHash(),
                state.getContextHash()
        )).thenReturn(Optional.of(existing));

        Optional<InvestmentReport> reused = service.findReusableReport("u_001", 10L, state);

        assertThat(reused).isPresent();
        assertThat(reused.orElseThrow().getReportVersion()).isEqualTo(7);
        assertThat(reused.orElseThrow().getReusedFromCache()).isTrue();
    }

    @Test
    void invalidReportSchemaPreventsCacheReuse() throws Exception {
        AnalysisState state = analysisState(
                "NVDA", "market-v1", "risks-v1", "news-v1", "Should I buy NVDA?");
        InvestmentReport storedReport = verifiedReusableReport("NVDA", "e-market");
        storedReport.setRiskFactors(List.of());

        assertThat(findReusableReport(state, storedReport)).isEmpty();
    }

    @Test
    void nullEvidenceItemFailsClosedAsCacheMiss() throws Exception {
        AnalysisState state = analysisState(
                "NVDA", "market-v1", "risks-v1", "news-v1", "Should I buy NVDA?");
        InvestmentReport storedReport = verifiedReusableReport("NVDA", "e-market");
        List<InvestmentReport.EvidenceItem> malformedEvidenceItems = new ArrayList<>();
        malformedEvidenceItems.add(null);
        storedReport.setEvidenceItems(malformedEvidenceItems);

        assertThat(findReusableReport(state, storedReport)).isEmpty();
    }

    @Test
    void malformedCachedReportJsonFailsClosedAsCacheMiss() {
        AnalysisState state = analysisState(
                "NVDA", "market-v1", "risks-v1", "news-v1", "Should I buy NVDA?");

        assertThat(findReusableReportJson(state, "{bad json")).isEmpty();
    }

    @Test
    void reportTickerMismatchPreventsCacheReuse() throws Exception {
        AnalysisState state = analysisState(
                "NVDA", "market-v1", "risks-v1", "news-v1", "Should I buy NVDA?");
        InvestmentReport storedReport = verifiedReusableReport("AAPL", "e-market");

        assertThat(findReusableReport(state, storedReport)).isEmpty();
    }

    @Test
    void unknownEvidenceReferencePreventsCacheReuse() throws Exception {
        AnalysisState state = analysisState(
                "NVDA", "market-v1", "risks-v1", "news-v1", "Should I buy NVDA?");
        InvestmentReport storedReport = verifiedReusableReport("NVDA", "e-unknown");

        assertThat(findReusableReport(state, storedReport)).isEmpty();
    }

    @Test
    void knownButUnusableEvidenceReferencePreventsCacheReuse() throws Exception {
        AnalysisState state = analysisState(
                "NVDA",
                "market-v1",
                "risks-v1",
                "news-v1",
                "Should I buy NVDA?",
                EvidenceStatus.FAILED
        );
        InvestmentReport storedReport = verifiedReusableReport("NVDA", "e-market");

        assertThat(findReusableReport(state, storedReport)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(
            value = InvestmentReportVersion.ReviewStatus.class,
            names = {"REJECTED", "NEEDS_RESEARCH"}
    )
    void negativeHumanReviewPreventsCacheReuse(
            InvestmentReportVersion.ReviewStatus reviewStatus
    ) throws Exception {
        AnalysisState state = analysisState(
                "NVDA", "market-v1", "risks-v1", "news-v1", "Should I buy NVDA?");
        service.prepareHashes(state);

        InvestmentReport storedReport = verifiedReusableReport("NVDA", "e-market");
        InvestmentReportVersion existing = new InvestmentReportVersion();
        existing.setUserId("u_001");
        existing.setTicker("NVDA");
        existing.setReportVersion(7);
        existing.setReviewStatus(reviewStatus);
        existing.setDataSnapshotHash(state.getDataSnapshotHash());
        existing.setContextHash(state.getContextHash());
        existing.setReportJson(
                new ObjectMapper().findAndRegisterModules().writeValueAsString(storedReport));

        when(repository.findByUserIdAndTickerAndDataSnapshotHashAndContextHash(
                "u_001",
                "NVDA",
                state.getDataSnapshotHash(),
                state.getContextHash()
        )).thenReturn(Optional.of(existing));

        assertThat(service.findReusableReport("u_001", 10L, state)).isEmpty();
    }

    @Test
    void reusesExistingReportWhenConcurrentPersistCreatesSameSnapshot() throws Exception {
        AnalysisState state = analysisState("NVDA", "market-v1", "risks-v1", "news-v1", "Should I buy NVDA?");
        state.setInvestmentReport(InvestmentReport.builder()
                .recommendation("BUY")
                .rationale(List.of("Fresh report from this request."))
                .build());
        service.prepareHashes(state);

        InvestmentReport storedReport = verifiedReusableReport("NVDA", "e-market");
        storedReport.setRecommendation("BUY");
        InvestmentReportVersion existing = new InvestmentReportVersion();
        existing.setUserId("u_001");
        existing.setTicker("NVDA");
        existing.setReportVersion(4);
        existing.setReviewStatus(InvestmentReportVersion.ReviewStatus.DRAFT);
        existing.setDataSnapshotHash(state.getDataSnapshotHash());
        existing.setContextHash(state.getContextHash());
        existing.setModelTier("STRONG");
        existing.setModelName("qwen3.6-max");
        existing.setReportJson(new ObjectMapper().findAndRegisterModules().writeValueAsString(storedReport));

        when(repository.findByUserIdAndTickerAndDataSnapshotHashAndContextHash(
                "u_001",
                "NVDA",
                state.getDataSnapshotHash(),
                state.getContextHash()
        )).thenReturn(Optional.empty(), Optional.of(existing));
        when(repository.findTopByUserIdAndTickerOrderByReportVersionDesc("u_001", "NVDA"))
                .thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(InvestmentReportVersion.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate snapshot"));

        InvestmentReport reused = service.persistReportVersion("u_001", 10L, state, "STRONG", "qwen3.6-max");

        assertThat(reused.getReportVersion()).isEqualTo(4);
        assertThat(reused.getReusedFromCache()).isTrue();
        assertThat(reused.getModelName()).isEqualTo("qwen3.6-max");
    }

    @ParameterizedTest
    @EnumSource(
            value = InvestmentReportVersion.ReviewStatus.class,
            names = {"REJECTED", "NEEDS_RESEARCH"}
    )
    void persistPathFailsClosedInsteadOfReusingNegativeHumanReview(
            InvestmentReportVersion.ReviewStatus reviewStatus
    ) throws Exception {
        AnalysisState state = analysisState(
                "NVDA", "market-v1", "risks-v1", "news-v1", "Should I buy NVDA?");
        state.setInvestmentReport(InvestmentReport.builder()
                .recommendation("BUY")
                .qualityStatus(InvestmentReport.ReportQualityStatus.VERIFIED)
                .build());
        service.prepareHashes(state);

        InvestmentReportVersion existing = new InvestmentReportVersion();
        existing.setId(44L);
        existing.setUserId("u_001");
        existing.setTicker("NVDA");
        existing.setReviewStatus(reviewStatus);
        existing.setDataSnapshotHash(state.getDataSnapshotHash());
        existing.setContextHash(state.getContextHash());
        when(repository.findByUserIdAndTickerAndDataSnapshotHashAndContextHash(
                "u_001",
                "NVDA",
                state.getDataSnapshotHash(),
                state.getContextHash()
        )).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.persistReportVersionWithMetadata(
                "u_001", 10L, state, "STRONG", "qwen3.6-max"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("REPORT_REVIEW_DISALLOWS_CACHE_REUSE");

        verify(repository, never()).saveAndFlush(any(InvestmentReportVersion.class));
    }

    @Test
    void listReportVersionsFallsBackWhenStoredJsonCannotBeParsed() {
        InvestmentReportVersion broken = new InvestmentReportVersion();
        broken.setId(99L);
        broken.setConversationId(10L);
        broken.setUserId("u_001");
        broken.setTicker("NVDA");
        broken.setReportVersion(2);
        broken.setRecommendation("HOLD");
        broken.setDataSnapshotHash("d".repeat(64));
        broken.setContextHash("c".repeat(64));
        broken.setModelTier("STRONG");
        broken.setModelName("qwen3.6-max");
        broken.setUserQuery("Should I buy NVDA?");
        broken.setReportJson("{bad json");

        when(repository.findByUserIdAndTickerOrderByReportVersionDesc(
                eq("u_001"),
                eq("NVDA"),
                any(Pageable.class)
        )).thenReturn(List.of(broken));

        var summaries = service.listReportVersions("u_001", "nvda", 5);

        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0).recommendation()).isEqualTo("HOLD");
        assertThat(summaries.get(0).modelName()).isEqualTo("qwen3.6-max");
        assertThat(summaries.get(0).preview()).isBlank();
    }

    @Test
    void returnsOwnedReportDetailWithFullReportAndReviewHistory() throws Exception {
        InvestmentReportVersion reportVersion = reportVersion(
                31L,
                InvestmentReportVersion.ReviewStatus.IN_REVIEW,
                2L
        );
        InvestmentReportReview review = review(
                1L,
                31L,
                InvestmentReportVersion.ReviewStatus.DRAFT,
                InvestmentReportVersion.ReviewStatus.IN_REVIEW,
                "Ready for review"
        );
        when(repository.findByIdAndUserId(31L, "u_001")).thenReturn(Optional.of(reportVersion));
        when(reviewRepository.findByReportVersionIdOrderByCreatedAtAscIdAsc(31L))
                .thenReturn(List.of(review));

        InvestmentReportVersionService.ReportDetail detail =
                service.getReportDetail("u_001", 31L);

        assertThat(detail.summary().reviewStatus())
                .isEqualTo(InvestmentReportVersion.ReviewStatus.IN_REVIEW);
        assertThat(detail.report().getRecommendation()).isEqualTo("HOLD");
        assertThat(detail.evidenceItems()).hasSize(1);
        assertThat(detail.citations()).containsExactly("[1] filing");
        assertThat(detail.reviewHistory()).singleElement()
                .satisfies(item -> {
                    assertThat(item.fromStatus()).isEqualTo(InvestmentReportVersion.ReviewStatus.DRAFT);
                    assertThat(item.toStatus()).isEqualTo(InvestmentReportVersion.ReviewStatus.IN_REVIEW);
                    assertThat(item.reviewerUserId()).isEqualTo("u_001");
                });
    }

    @Test
    void crossUserAndMissingReportsShareTheSameNotFoundResult() {
        when(repository.findByIdAndUserId(31L, "stranger")).thenReturn(Optional.empty());
        when(repository.findByIdAndUserId(999L, "u_001")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getReportDetail("stranger", 31L))
                .isInstanceOf(com.stocksage.exception.ResourceNotFoundException.class)
                .hasMessage("Investment report not found");
        assertThatThrownBy(() -> service.getReportDetail("u_001", 999L))
                .isInstanceOf(com.stocksage.exception.ResourceNotFoundException.class)
                .hasMessage("Investment report not found");
    }

    @Test
    void validReviewTransitionUpdatesCurrentStateAndAppendsHistory() throws Exception {
        InvestmentReportVersion reportVersion = reportVersion(
                31L,
                InvestmentReportVersion.ReviewStatus.DRAFT,
                4L
        );
        List<InvestmentReportReview> history = new ArrayList<>();
        when(repository.findByIdAndUserId(31L, "u_001")).thenReturn(Optional.of(reportVersion));
        when(repository.saveAndFlush(reportVersion)).thenAnswer(invocation -> {
            reportVersion.setLockVersion(5L);
            return reportVersion;
        });
        when(reviewRepository.saveAndFlush(any(InvestmentReportReview.class))).thenAnswer(invocation -> {
            InvestmentReportReview saved = invocation.getArgument(0);
            saved.setId(71L);
            history.add(saved);
            return saved;
        });
        when(reviewRepository.findByReportVersionIdOrderByCreatedAtAscIdAsc(31L))
                .thenAnswer(invocation -> List.copyOf(history));

        InvestmentReportVersionService.ReportDetail detail = service.reviewReport(
                "u_001",
                31L,
                new InvestmentReportReviewRequest(
                        InvestmentReportVersion.ReviewStatus.IN_REVIEW,
                        "  Ready for human review.  ",
                        4L
                )
        );

        assertThat(detail.summary().reviewStatus())
                .isEqualTo(InvestmentReportVersion.ReviewStatus.IN_REVIEW);
        assertThat(detail.summary().reviewerUserId()).isEqualTo("u_001");
        assertThat(detail.summary().reviewComment()).isEqualTo("Ready for human review.");
        assertThat(detail.summary().lockVersion()).isEqualTo(5L);
        assertThat(detail.reviewHistory()).singleElement()
                .satisfies(item -> {
                    assertThat(item.id()).isEqualTo(71L);
                    assertThat(item.fromStatus()).isEqualTo(InvestmentReportVersion.ReviewStatus.DRAFT);
                    assertThat(item.toStatus()).isEqualTo(InvestmentReportVersion.ReviewStatus.IN_REVIEW);
                    assertThat(item.comment()).isEqualTo("Ready for human review.");
                });
    }

    @Test
    void rejectsInvalidReviewTransitionWithoutWritingHistory() throws Exception {
        InvestmentReportVersion reportVersion = reportVersion(
                31L,
                InvestmentReportVersion.ReviewStatus.DRAFT,
                0L
        );
        when(repository.findByIdAndUserId(31L, "u_001")).thenReturn(Optional.of(reportVersion));

        assertThatThrownBy(() -> service.reviewReport(
                "u_001",
                31L,
                new InvestmentReportReviewRequest(
                        InvestmentReportVersion.ReviewStatus.APPROVED,
                        null,
                        0L
                )
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid report review transition");

        verify(repository, never()).saveAndFlush(reportVersion);
        verify(reviewRepository, never()).saveAndFlush(any(InvestmentReportReview.class));
    }

    @Test
    void rejectedAndNeedsResearchRequireNonBlankComment() throws Exception {
        InvestmentReportVersion reportVersion = reportVersion(
                31L,
                InvestmentReportVersion.ReviewStatus.IN_REVIEW,
                1L
        );
        when(repository.findByIdAndUserId(31L, "u_001")).thenReturn(Optional.of(reportVersion));

        assertThatThrownBy(() -> service.reviewReport(
                "u_001",
                31L,
                new InvestmentReportReviewRequest(
                        InvestmentReportVersion.ReviewStatus.REJECTED,
                        "   ",
                        1L
                )
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-blank comment");

        verify(reviewRepository, never()).saveAndFlush(any(InvestmentReportReview.class));
    }

    @ParameterizedTest
    @EnumSource(
            value = InvestmentReportVersion.ReviewStatus.class,
            names = {"REJECTED", "NEEDS_RESEARCH"}
    )
    void negativeHumanReviewRevokesExistingResearchMemory(
            InvestmentReportVersion.ReviewStatus reviewStatus
    ) throws Exception {
        InvestmentReportVersion reportVersion = reportVersion(
                31L,
                InvestmentReportVersion.ReviewStatus.IN_REVIEW,
                1L
        );
        when(repository.findByIdAndUserId(31L, "u_001")).thenReturn(Optional.of(reportVersion));
        when(repository.saveAndFlush(reportVersion)).thenAnswer(invocation -> {
            reportVersion.setLockVersion(2L);
            return reportVersion;
        });
        when(reviewRepository.saveAndFlush(any(InvestmentReportReview.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(reviewRepository.findByReportVersionIdOrderByCreatedAtAscIdAsc(31L))
                .thenReturn(List.of());

        service.reviewReport(
                "u_001",
                31L,
                new InvestmentReportReviewRequest(reviewStatus, "Evidence is not acceptable.", 1L)
        );

        verify(researchMemoryService).revokeForReport(reportVersion);
    }

    @Test
    void approvalReconcilesWithoutRevokingMachineVerifiedResearchMemory() throws Exception {
        InvestmentReportVersion reportVersion = reportVersion(
                31L,
                InvestmentReportVersion.ReviewStatus.IN_REVIEW,
                1L
        );
        when(repository.findByIdAndUserId(31L, "u_001")).thenReturn(Optional.of(reportVersion));
        when(repository.saveAndFlush(reportVersion)).thenAnswer(invocation -> {
            reportVersion.setLockVersion(2L);
            return reportVersion;
        });
        when(reviewRepository.saveAndFlush(any(InvestmentReportReview.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(reviewRepository.findByReportVersionIdOrderByCreatedAtAscIdAsc(31L))
                .thenReturn(List.of());

        service.reviewReport(
                "u_001",
                31L,
                new InvestmentReportReviewRequest(
                        InvestmentReportVersion.ReviewStatus.APPROVED,
                        "Human review agrees with the machine-verified report.",
                        1L
                )
        );

        verify(researchMemoryService, never()).revokeForReport(any());
        verify(researchMemoryService).reconcileForReport(reportVersion);
    }

    @Test
    void staleExpectedLockVersionReturnsConflictWithoutWritingHistory() throws Exception {
        InvestmentReportVersion reportVersion = reportVersion(
                31L,
                InvestmentReportVersion.ReviewStatus.DRAFT,
                3L
        );
        when(repository.findByIdAndUserId(31L, "u_001")).thenReturn(Optional.of(reportVersion));

        assertThatThrownBy(() -> service.reviewReport(
                "u_001",
                31L,
                new InvestmentReportReviewRequest(
                        InvestmentReportVersion.ReviewStatus.IN_REVIEW,
                        null,
                        2L
                )
        ))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        verify(repository, never()).saveAndFlush(reportVersion);
        verify(reviewRepository, never()).saveAndFlush(any(InvestmentReportReview.class));
    }

    private InvestmentReportVersion reportVersion(
            Long id,
            InvestmentReportVersion.ReviewStatus status,
            Long lockVersion
    ) throws Exception {
        InvestmentReport report = InvestmentReport.builder()
                .recommendation("HOLD")
                .evidenceItems(List.of(InvestmentReport.EvidenceItem.builder()
                        .dimension("fundamentals")
                        .evidence("Revenue grew.")
                        .implication("Demand remains healthy.")
                        .source("10-Q")
                        .build()))
                .citations(List.of("[1] filing"))
                .build();
        InvestmentReportVersion entity = new InvestmentReportVersion();
        entity.setId(id);
        entity.setUserId("u_001");
        entity.setTicker("NVDA");
        entity.setReportVersion(3);
        entity.setRecommendation("HOLD");
        entity.setDataSnapshotHash("d".repeat(64));
        entity.setContextHash("c".repeat(64));
        entity.setReportJson(new ObjectMapper().findAndRegisterModules().writeValueAsString(report));
        entity.setReviewStatus(status);
        entity.setLockVersion(lockVersion);
        return entity;
    }

    private InvestmentReportReview review(
            Long id,
            Long reportVersionId,
            InvestmentReportVersion.ReviewStatus fromStatus,
            InvestmentReportVersion.ReviewStatus toStatus,
            String comment
    ) {
        InvestmentReportReview review = new InvestmentReportReview();
        review.setId(id);
        review.setReportVersionId(reportVersionId);
        review.setReviewer("u_001");
        review.setFromStatus(fromStatus);
        review.setToStatus(toStatus);
        review.setComment(comment);
        return review;
    }

    private AnalysisState analysisState(String ticker, String market, String fundamentals, String news, String query) {
        return analysisState(ticker, market, fundamentals, news, query, EvidenceStatus.AVAILABLE);
    }

    private AnalysisState analysisState(
            String ticker,
            String market,
            String fundamentals,
            String news,
            String query,
            EvidenceStatus marketStatus
    ) {
        Instant observedAt = Instant.parse("2026-07-24T00:00:00Z");
        EvidenceLedger ledger = new EvidenceLedger(
                TargetIdentity.resolved(ticker),
                List.of(
                        new EvidenceEnvelope(
                                "e-fundamentals", EvidenceDimension.FUNDAMENTALS,
                                "financials", ticker, EvidenceStatus.AVAILABLE,
                                "tool:financials", "test", observedAt, observedAt,
                                fundamentals, true),
                        new EvidenceEnvelope(
                                "e-market", EvidenceDimension.MARKET,
                                "bars", ticker, marketStatus,
                                "tool:bars", "test", observedAt, observedAt,
                                market, true)
                )
        );
        return AnalysisState.builder()
                .primaryTicker(ticker)
                .query(query)
                .marketReport(market)
                .fundamentalsReport(fundamentals)
                .newsReport(news)
                .citations(List.of("source-a", "source-b"))
                .evidenceLedger(ledger)
                .build();
    }

    private InvestmentReport verifiedReusableReport(String ticker, String evidenceId) {
        return InvestmentReport.builder()
                .ticker(ticker)
                .recommendation("HOLD")
                .analystSummary("The evidence supports a bounded hold recommendation.")
                .dataFreshness("Evidence observed at 2026-07-24T00:00:00Z.")
                .rationale(List.of("The current valuation balances growth and execution risk."))
                .riskFactors(List.of("Demand or margins may weaken."))
                .unknowns(List.of("Future guidance remains uncertain."))
                .qualityStatus(InvestmentReport.ReportQualityStatus.VERIFIED)
                .completionPolicyId(DeepResearchCompletionPolicy.POLICY_ID)
                .completionPolicyVersion(DeepResearchCompletionPolicy.POLICY_VERSION)
                .evidenceItems(List.of(InvestmentReport.EvidenceItem.builder()
                        .dimension("market")
                        .evidence("Stored evidence is bound to the current ledger.")
                        .implication("The recommendation remains bounded.")
                        .source("tool:market")
                        .sourceEvidenceIds(List.of(evidenceId))
                        .build()))
                .build();
    }

    private Optional<InvestmentReport> findReusableReport(
            AnalysisState state,
            InvestmentReport storedReport
    ) throws Exception {
        return findReusableReportJson(
                state,
                new ObjectMapper().findAndRegisterModules().writeValueAsString(storedReport)
        );
    }

    private Optional<InvestmentReport> findReusableReportJson(
            AnalysisState state,
            String reportJson
    ) {
        service.prepareHashes(state);
        InvestmentReportVersion existing = new InvestmentReportVersion();
        existing.setUserId("u_001");
        existing.setTicker("NVDA");
        existing.setReportVersion(7);
        existing.setReviewStatus(InvestmentReportVersion.ReviewStatus.DRAFT);
        existing.setDataSnapshotHash(state.getDataSnapshotHash());
        existing.setContextHash(state.getContextHash());
        existing.setReportJson(reportJson);

        when(repository.findByUserIdAndTickerAndDataSnapshotHashAndContextHash(
                "u_001",
                "NVDA",
                state.getDataSnapshotHash(),
                state.getContextHash()
        )).thenReturn(Optional.of(existing));

        return service.findReusableReport("u_001", 10L, state);
    }
}
