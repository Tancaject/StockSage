package com.stocksage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.model.dto.InvestmentReport;
import com.stocksage.model.entity.InvestmentReportVersion;
import com.stocksage.model.entity.ResearchMemoryEntry;
import com.stocksage.repository.InvestmentReportVersionRepository;
import com.stocksage.repository.ResearchMemoryEntryRepository;
import com.stocksage.trace.TraceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResearchMemoryServiceTest {

    private final ResearchMemoryEntryRepository repository = mock(ResearchMemoryEntryRepository.class);
    private final InvestmentReportVersionRepository reportVersionRepository =
            mock(InvestmentReportVersionRepository.class);
    private final ResearchMemoryVectorIndex vectorIndex = mock(ResearchMemoryVectorIndex.class);
    private final ResearchMemoryProperties properties = new ResearchMemoryProperties();
    private final TraceService traceService = mock(TraceService.class);
    private final PlatformTransactionManager transactionManager =
            mock(PlatformTransactionManager.class);
    private final ResearchMemoryService service = new ResearchMemoryService(
            repository, reportVersionRepository, vectorIndex, properties,
            new ObjectMapper(), traceService, transactionManager);

    @BeforeEach
    void setUp() {
        properties.setCapture(true);
        properties.setIndex(false);
        properties.setRetrieve(false);
        properties.setInject(false);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            ResearchMemoryEntry entry = invocation.getArgument(0);
            entry.setId(41L);
            return entry;
        });
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.countByIdAndRevokedAtIsNullAndVectorStatusIn(
                anyLong(), anyCollection())).thenReturn(1L);
        when(repository.updateVectorStateIfIndexable(
                anyLong(), anyCollection(), any(), nullable(String.class), any(LocalDateTime.class)))
                .thenReturn(1);
    }

    @Test
    void capturesOnlySourcedReportsAndIsIdempotentByTenantAndSource() {
        InvestmentReportVersion source = source("u-a", "STRONG", "qwen");
        InvestmentReport report = report(List.of("[1] SEC filing"));

        ResearchMemoryEntry captured = service.capture(source, report);
        when(repository.findByUserIdAndSourceTypeAndSourceId(
                "u-a", "INVESTMENT_REPORT_VERSION", "7")).thenReturn(Optional.of(captured));
        ResearchMemoryEntry duplicate = service.capture(source, report);

        assertThat(captured.getUserId()).isEqualTo("u-a");
        assertThat(captured.getSourceCitations()).contains("SEC filing");
        assertThat(captured.getMemoryText()).contains("历史研究结论", "NVDA");
        assertThat(duplicate).isSameAs(captured);
        verify(repository).saveAndFlush(any(ResearchMemoryEntry.class));
    }

    @Test
    void rejectsUnsourcedAndOfflineFallbackReports() {
        assertThat(service.capture(source("u-a", "STRONG", "qwen"), report(List.of()))).isNull();
        assertThat(service.capture(
                source("u-a", "DEMO", "offline-rule-fallback"),
                report(List.of("[1] sample"))
        )).isNull();

        verify(repository, never()).saveAndFlush(any());
    }

    @ParameterizedTest
    @EnumSource(
            value = InvestmentReportVersion.ReviewStatus.class,
            names = {"REJECTED", "NEEDS_RESEARCH"}
    )
    void rejectsMachineVerifiedReportsWithNegativeHumanReview(
            InvestmentReportVersion.ReviewStatus reviewStatus
    ) {
        InvestmentReportVersion source = source("u-a", "STRONG", "qwen");
        source.setReviewStatus(reviewStatus);

        assertThat(service.capture(source, report(List.of("[1] SEC filing")))).isNull();

        verify(repository, never()).saveAndFlush(any());
    }

    @ParameterizedTest
    @EnumSource(
            value = InvestmentReportVersion.ReviewStatus.class,
            names = {"REJECTED", "NEEDS_RESEARCH"}
    )
    void revalidatesStalePersistEventAgainstLockedCurrentHumanReview(
            InvestmentReportVersion.ReviewStatus currentReviewStatus
    ) {
        InvestmentReportVersion staleEventSource = source("u-a", "STRONG", "qwen");
        staleEventSource.setReviewStatus(InvestmentReportVersion.ReviewStatus.DRAFT);
        InvestmentReportVersion currentSource = source("u-a", "STRONG", "qwen");
        currentSource.setReviewStatus(currentReviewStatus);
        when(reportVersionRepository.findByIdAndUserIdForUpdate(7L, "u-a"))
                .thenReturn(Optional.of(currentSource));

        assertThat(service.capture(
                staleEventSource,
                report(List.of("[1] SEC filing"))
        )).isNull();

        verify(reportVersionRepository).findByIdAndUserIdForUpdate(7L, "u-a");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void reportPersistedListenerContainsCommitFailureInsideBestEffortBoundary() {
        InvestmentReportVersion source = source("u-a", "STRONG", "qwen");
        doThrow(new UnexpectedRollbackException("rollback only"))
                .when(transactionManager).commit(any());

        assertThatCode(() -> service.onReportPersisted(new InvestmentReportPersistedEvent(
                source,
                report(List.of("[1] SEC filing"))
        ))).doesNotThrowAnyException();

        ArgumentCaptor<TransactionDefinition> definition =
                ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager).getTransaction(definition.capture());
        assertThat(definition.getValue().getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        verify(transactionManager).commit(any());
    }

    @Test
    void reportPersistedListenerWaitsForSourceCommitWhenPublisherHasATransaction() throws Exception {
        TransactionalEventListener listener = ResearchMemoryService.class
                .getMethod("onReportPersisted", InvestmentReportPersistedEvent.class)
                .getAnnotation(TransactionalEventListener.class);

        assertThat(listener).isNotNull();
        assertThat(listener.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(listener.fallbackExecution()).isTrue();
    }

    @Test
    void vectorFailureLeavesTruthRowFailedWithoutThrowing() {
        properties.setIndex(true);
        InvestmentReportVersion source = source("u-a", "STRONG", "qwen");
        org.mockito.Mockito.doThrow(new IllegalStateException("milvus down"))
                .when(vectorIndex).index(any());

        ResearchMemoryEntry captured = service.capture(source, report(List.of("[1] SEC")));

        assertThat(captured.getVectorStatus()).isEqualTo(ResearchMemoryEntry.VectorStatus.FAILED);
        assertThat(captured.getVectorErrorCode()).isEqualTo("VECTOR_INDEX_FAILED");
    }

    @Test
    void captureDefersVectorIndexUntilDatabaseTransactionCommits() {
        properties.setIndex(true);
        InvestmentReportVersion source = source("u-a", "STRONG", "qwen");

        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            ResearchMemoryEntry captured =
                    service.capture(source, report(List.of("[1] SEC")));

            assertThat(captured.getVectorStatus()).isEqualTo(ResearchMemoryEntry.VectorStatus.PENDING);
            verify(repository).saveAndFlush(captured);
            verify(vectorIndex, never()).index(any());
            List<TransactionSynchronization> synchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
            assertThat(synchronizations).hasSize(1);

            synchronizations.forEach(TransactionSynchronization::afterCommit);

            verify(vectorIndex).index(captured);
            assertThat(captured.getVectorStatus()).isEqualTo(ResearchMemoryEntry.VectorStatus.INDEXED);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void compensationCannotMergeAStaleEntryOverConcurrentRevocation() {
        properties.setIndex(true);
        ResearchMemoryEntry staleCandidate = new ResearchMemoryEntry();
        staleCandidate.setId(41L);
        staleCandidate.setUserId("u-a");
        staleCandidate.setVectorStatus(ResearchMemoryEntry.VectorStatus.PENDING);
        ResearchMemoryEntry authoritative = new ResearchMemoryEntry();
        authoritative.setId(41L);
        authoritative.setUserId("u-a");
        authoritative.setVectorStatus(ResearchMemoryEntry.VectorStatus.PENDING);
        when(repository.findByVectorStatusInOrderByUpdatedAtAsc(
                anyCollection(), any())).thenReturn(List.of(staleCandidate));
        when(repository.findByIdAndUserId(41L, "u-a")).thenReturn(Optional.of(authoritative));
        org.mockito.Mockito.doAnswer(invocation -> {
            assertThat(service.revoke("u-a", 41L)).isTrue();
            return null;
        }).when(vectorIndex).index(staleCandidate);
        when(repository.updateVectorStateIfIndexable(
                anyLong(), anyCollection(), any(), nullable(String.class), any(LocalDateTime.class)))
                .thenReturn(0);

        service.compensateIndex();

        assertThat(authoritative.getVectorStatus()).isEqualTo(ResearchMemoryEntry.VectorStatus.REVOKED);
        assertThat(authoritative.getRevokedAt()).isNotNull();
        verify(vectorIndex).index(staleCandidate);
        verify(repository).updateVectorStateIfIndexable(
                eq(41L), anyCollection(), eq(ResearchMemoryEntry.VectorStatus.INDEXED),
                eq(null), any(LocalDateTime.class));
        verify(repository, never()).save(same(staleCandidate));
        verify(vectorIndex, atLeastOnce()).delete(41L);
    }

    @Test
    void indexCasExceptionDeletesUncommittedVectorAndLeavesTruthPending() {
        properties.setIndex(true);
        InvestmentReportVersion source = source("u-a", "STRONG", "qwen");
        when(repository.updateVectorStateIfIndexable(
                anyLong(), anyCollection(), any(), nullable(String.class), any(LocalDateTime.class)))
                .thenThrow(new IllegalStateException("database unavailable"));

        ResearchMemoryEntry captured =
                service.capture(source, report(List.of("[1] SEC filing")));

        assertThat(captured.getVectorStatus()).isEqualTo(ResearchMemoryEntry.VectorStatus.PENDING);
        verify(vectorIndex).index(captured);
        verify(vectorIndex).delete(41L);
    }

    @Test
    void retrievalUsesTenantScopedTruthLookupAndShadowDoesNotInject() {
        properties.setRetrieve(true);
        properties.setInject(false);
        ResearchMemoryEntry own = new ResearchMemoryEntry();
        own.setId(41L);
        own.setUserId("u-a");
        own.setTicker("NVDA");
        own.setMemoryText("private sourced conclusion");
        own.setSourceCitations("[\"SEC\"]");
        own.setCreatedAt(LocalDateTime.now());
        own.setVectorStatus(ResearchMemoryEntry.VectorStatus.INDEXED);
        when(vectorIndex.search("u-a", "NVDA", "query", 3))
                .thenReturn(List.of(new ResearchMemoryVectorIndex.Hit(41L, 0.9)));
        when(repository.findByIdInAndUserIdAndRevokedAtIsNullAndVectorStatus(
                anyCollection(), eq("u-a"), eq(ResearchMemoryEntry.VectorStatus.INDEXED)))
                .thenReturn(List.of(own));

        ResearchMemoryService.RetrievalResult result =
                service.retrieve("u-a", "NVDA", "query", "trace");

        assertThat(result.hitCount()).isOne();
        assertThat(result.injected()).isFalse();
        assertThat(result.promptContext()).isBlank();
        verify(repository).findByIdInAndUserIdAndRevokedAtIsNullAndVectorStatus(
                org.mockito.ArgumentMatchers.argThat(ids -> ids.equals(java.util.Set.of(41L))),
                eq("u-a"),
                eq(ResearchMemoryEntry.VectorStatus.INDEXED));
    }

    @Test
    void retrievalRejectsVectorHitWithoutAuthoritativeIndexedTruth() {
        properties.setRetrieve(true);
        properties.setInject(true);
        ResearchMemoryEntry pending = new ResearchMemoryEntry();
        pending.setId(41L);
        pending.setUserId("u-a");
        pending.setTicker("NVDA");
        pending.setMemoryText("must not be injected");
        pending.setSourceCitations("[\"SEC\"]");
        pending.setCreatedAt(LocalDateTime.now());
        pending.setVectorStatus(ResearchMemoryEntry.VectorStatus.PENDING);
        when(vectorIndex.search("u-a", "NVDA", "query", 3))
                .thenReturn(List.of(new ResearchMemoryVectorIndex.Hit(41L, 0.9)));
        when(repository.findByIdInAndUserIdAndRevokedAtIsNullAndVectorStatus(
                anyCollection(), eq("u-a"), eq(ResearchMemoryEntry.VectorStatus.INDEXED)))
                .thenReturn(List.of(pending));

        ResearchMemoryService.RetrievalResult result =
                service.retrieve("u-a", "NVDA", "query", "trace");

        assertThat(result.hitCount()).isZero();
        assertThat(result.injected()).isFalse();
        assertThat(result.promptContext()).isBlank();
    }

    @Test
    void revokeIsTenantScopedAndDeletesVectorImmediately() {
        ResearchMemoryEntry own = new ResearchMemoryEntry();
        own.setId(41L);
        own.setUserId("u-a");
        own.setVectorStatus(ResearchMemoryEntry.VectorStatus.INDEXED);
        when(repository.findByIdAndUserId(41L, "u-a")).thenReturn(Optional.of(own));

        assertThat(service.revoke("u-a", 41L)).isTrue();
        assertThat(own.getVectorStatus()).isEqualTo(ResearchMemoryEntry.VectorStatus.REVOKED);
        org.mockito.InOrder revokeOrder = org.mockito.Mockito.inOrder(repository, vectorIndex);
        revokeOrder.verify(repository).saveAndFlush(own);
        revokeOrder.verify(vectorIndex).delete(41L);
    }

    @Test
    void revokeDefersVectorDeleteUntilDatabaseTransactionCommits() {
        ResearchMemoryEntry own = new ResearchMemoryEntry();
        own.setId(41L);
        own.setUserId("u-a");
        own.setVectorStatus(ResearchMemoryEntry.VectorStatus.INDEXED);
        when(repository.findByIdAndUserId(41L, "u-a")).thenReturn(Optional.of(own));

        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThat(service.revoke("u-a", 41L)).isTrue();

            verify(repository).saveAndFlush(own);
            verify(vectorIndex, never()).delete(anyLong());
            List<TransactionSynchronization> synchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
            assertThat(synchronizations).hasSize(1);

            synchronizations.forEach(TransactionSynchronization::afterCommit);

            verify(vectorIndex).delete(41L);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void revokeForReportUsesTenantScopedSourceIdentity() {
        InvestmentReportVersion source = source("u-a", "STRONG", "qwen");
        ResearchMemoryEntry own = new ResearchMemoryEntry();
        own.setId(41L);
        own.setUserId("u-a");
        own.setVectorStatus(ResearchMemoryEntry.VectorStatus.INDEXED);
        when(repository.findByUserIdAndSourceTypeAndSourceId(
                "u-a", "INVESTMENT_REPORT_VERSION", "7")).thenReturn(Optional.of(own));

        assertThat(service.revokeForReport(source)).isTrue();

        assertThat(own.getVectorStatus()).isEqualTo(ResearchMemoryEntry.VectorStatus.REVOKED);
        assertThat(own.getRevokedAt()).isNotNull();
        verify(vectorIndex).delete(41L);
    }

    private InvestmentReportVersion source(String userId, String tier, String model) {
        InvestmentReportVersion source = new InvestmentReportVersion();
        source.setId(7L);
        source.setUserId(userId);
        source.setTicker("NVDA");
        source.setConversationId(3L);
        source.setModelTier(tier);
        source.setModelName(model);
        source.setDataSnapshotHash("a".repeat(64));
        source.setGeneratedAt(LocalDateTime.now());
        when(reportVersionRepository.findByIdAndUserIdForUpdate(7L, userId))
                .thenReturn(Optional.of(source));
        return source;
    }

    private InvestmentReport report(List<String> citations) {
        List<InvestmentReport.EvidenceItem> evidenceItems = citations.isEmpty()
                ? List.of()
                : List.of(InvestmentReport.EvidenceItem.builder()
                .dimension("fundamentals")
                .evidence("Revenue growth remains strong")
                .implication("supports monitored growth")
                .source("SEC filing")
                .sourceEvidenceIds(List.of("e-fundamentals"))
                .build());
        return InvestmentReport.builder()
                .ticker("NVDA")
                .qualityStatus(InvestmentReport.ReportQualityStatus.VERIFIED)
                .recommendation("WATCH")
                .rationale(List.of("Revenue growth remains strong"))
                .riskFactors(List.of("Valuation risk"))
                .citations(citations)
                .evidenceItems(evidenceItems)
                .build();
    }
}
