package com.stocksage.knowledge;

import com.stocksage.research.InvestmentReportPersistedEvent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.agent.AgentStep;
import com.stocksage.agent.intent.AnalysisDepth;
import com.stocksage.agent.intent.TimeSensitivity;
import com.stocksage.model.dto.AnalysisHorizon;
import com.stocksage.model.dto.InvestmentReport;
import com.stocksage.model.entity.InvestmentReportVersion;
import com.stocksage.model.entity.ResearchMemoryConflictGroup;
import com.stocksage.model.entity.ResearchMemoryEntry;
import com.stocksage.repository.InvestmentReportVersionRepository;
import com.stocksage.repository.ResearchMemoryConflictGroupRepository;
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

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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

    private static final Instant NOW_INSTANT = Instant.parse("2026-08-23T00:00:00Z");
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(NOW_INSTANT, ZoneOffset.UTC);

    private final ResearchMemoryEntryRepository repository = mock(ResearchMemoryEntryRepository.class);
    private final ResearchMemoryConflictGroupRepository conflictGroupRepository =
            mock(ResearchMemoryConflictGroupRepository.class);
    private final InvestmentReportVersionRepository reportVersionRepository =
            mock(InvestmentReportVersionRepository.class);
    private final ResearchMemoryVectorIndex vectorIndex = mock(ResearchMemoryVectorIndex.class);
    private final ResearchMemoryProperties properties = new ResearchMemoryProperties();
    private final TraceService traceService = mock(TraceService.class);
    private final PlatformTransactionManager transactionManager =
            mock(PlatformTransactionManager.class);
    private final ResearchMemoryService service = new ResearchMemoryService(
            repository, conflictGroupRepository, reportVersionRepository, vectorIndex, properties,
            new ObjectMapper(), traceService, transactionManager,
            Clock.fixed(NOW_INSTANT, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        properties.setCapture(true);
        properties.setIndex(false);
        properties.setRetrieve(false);
        properties.setInject(false);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        when(conflictGroupRepository.ensureAndLock(
                anyString(), anyString(), any(LocalDateTime.class)))
                .thenAnswer(invocation -> {
                    ResearchMemoryConflictGroup group = new ResearchMemoryConflictGroup();
                    group.setId(1L);
                    group.setUserId(invocation.getArgument(0));
                    group.setConflictKey(invocation.getArgument(1));
                    group.setResolutionStatus(
                            ResearchMemoryConflictGroup.ResolutionStatus.UNRESOLVED);
                    return group;
                });
        when(conflictGroupRepository.saveAndFlush(any(ResearchMemoryConflictGroup.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.findConflictCandidatesForUpdate(anyString(), anyString()))
                .thenReturn(List.of());
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
        when(repository.findById(41L)).thenReturn(Optional.of(authoritative));
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
    void indexCasExceptionKeepsOrphanVectorSafelyGatedByPendingTruth() {
        properties.setIndex(true);
        InvestmentReportVersion source = source("u-a", "STRONG", "qwen");
        when(repository.updateVectorStateIfIndexable(
                anyLong(), anyCollection(), any(), nullable(String.class), any(LocalDateTime.class)))
                .thenThrow(new IllegalStateException("database unavailable"));

        ResearchMemoryEntry captured =
                service.capture(source, report(List.of("[1] SEC filing")));

        assertThat(captured.getVectorStatus()).isEqualTo(ResearchMemoryEntry.VectorStatus.PENDING);
        verify(vectorIndex).index(captured);
        verify(vectorIndex, never()).delete(41L);
    }

    @Test
    void retrievalUsesTenantScopedTruthLookupWithoutPromptInjection() {
        properties.setRetrieve(true);
        properties.setInject(false);
        String conflictKey = "REPORT_RECOMMENDATION|NVDA|MEDIUM_TERM";
        ResearchMemoryEntry own = indexedMemory(
                41L, conflictKey, AnalysisHorizon.MEDIUM_TERM,
                ResearchMemoryEntry.ResolutionStatus.CURRENT,
                NOW, "private sourced conclusion", "HOLD");
        when(vectorIndex.search("u-a", "NVDA", "query", 30))
                .thenReturn(List.of(new ResearchMemoryVectorIndex.Hit(41L, 0.9)));
        when(repository.findByIdInAndUserIdAndRevokedAtIsNullAndVectorStatus(
                anyCollection(), eq("u-a"), eq(ResearchMemoryEntry.VectorStatus.INDEXED)))
                .thenReturn(List.of(own));
        when(conflictGroupRepository.findByUserIdAndConflictKeyIn(
                eq("u-a"), anyCollection()))
                .thenReturn(List.of(resolvedGroup(conflictKey, 41L)));

        ResearchMemoryService.RetrievalResult result =
                service.retrieve("u-a", "NVDA", "query", "trace");

        assertThat(result.hitCount()).isOne();
        assertThat(result.injected()).isFalse();
        assertThat(result.promptContext()).isBlank();
        verify(vectorIndex).search("u-a", "NVDA", "query", 30);
        verify(repository).findByIdInAndUserIdAndRevokedAtIsNullAndVectorStatus(
                org.mockito.ArgumentMatchers.argThat(ids -> ids.equals(java.util.Set.of(41L))),
                eq("u-a"),
                eq(ResearchMemoryEntry.VectorStatus.INDEXED));
        verify(conflictGroupRepository)
                .findByUserIdAndConflictKeyIn(eq("u-a"), anyCollection());
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
        when(vectorIndex.search("u-a", "NVDA", "query", 30))
                .thenReturn(List.of(new ResearchMemoryVectorIndex.Hit(41L, 0.9)));
        when(repository.findByIdInAndUserIdAndRevokedAtIsNullAndVectorStatus(
                anyCollection(), eq("u-a"), eq(ResearchMemoryEntry.VectorStatus.INDEXED)))
                .thenReturn(List.of(pending));

        ResearchMemoryService.RetrievalResult result =
                service.retrieve("u-a", "NVDA", "query", "trace");

        assertThat(result.hitCount()).isZero();
        assertThat(result.injected()).isFalse();
        assertThat(result.promptContext()).isBlank();
        verify(vectorIndex).search("u-a", "NVDA", "query", 30);
    }

    @Test
    void qualificationUsesCurrentSourceReviewWithoutPromotingHistoricalConclusions() {
        properties.setRetrieve(true);
        properties.setInject(true);
        ResearchMemoryEntry entry = indexedMemory(41L, null, AnalysisHorizon.LONG_TERM,
                ResearchMemoryEntry.ResolutionStatus.CURRENT, NOW.minusDays(10), "historical thesis", "BUY");
        InvestmentReportVersion report = new InvestmentReportVersion();
        report.setId(41L);
        report.setUserId("u-a");
        report.setGeneratedAt(NOW.minusDays(10));
        report.setReviewStatus(InvestmentReportVersion.ReviewStatus.DRAFT);
        when(vectorIndex.search("u-a", "NVDA", "query", 30))
                .thenReturn(List.of(new ResearchMemoryVectorIndex.Hit(41L, 0.9)));
        when(repository.findByIdInAndUserIdAndRevokedAtIsNullAndVectorStatus(
                anyCollection(), eq("u-a"), eq(ResearchMemoryEntry.VectorStatus.INDEXED)))
                .thenReturn(List.of(entry));
        when(reportVersionRepository.findByIdInAndUserId(anyCollection(), eq("u-a")))
                .thenReturn(List.of(report));
        when(repository.findByUserIdAndRevokedAtIsNullOrderByCreatedAtDesc(eq("u-a"), any()))
                .thenReturn(List.of(entry));

        var draft = service.retrieve("u-a", "NVDA", "query", "trace");
        assertThat(draft.promptContext()).contains("HISTORICAL_GENERATED_CONCLUSION", "来源报告=41",
                "审核=DRAFT", "当前证据=false", "不是原始来源事实或用户确认事实", "historical thesis");
        var qualification = service.listForUser("u-a", 10).get(0).qualification();
        assertThat(qualification.sourceReportId()).isEqualTo(41L);
        assertThat(qualification.reportGeneratedAt()).isEqualTo(NOW.minusDays(10));
        assertThat(qualification.currentEvidence()).isFalse();
        ArgumentCaptor<AgentStep> step = ArgumentCaptor.forClass(AgentStep.class);
        verify(traceService).addStep(eq("trace"), step.capture());
        @SuppressWarnings("unchecked")
        var hits = (List<Map<String, Object>>) step.getValue().getAttributes().get("hits");
        assertThat(hits.get(0).get("qualification")).isEqualTo(qualification);

        report.setReviewStatus(InvestmentReportVersion.ReviewStatus.APPROVED);
        assertThat(service.retrieve("u-a", "NVDA", "query", "").promptContext())
                .contains("审核=APPROVED", "HISTORICAL_GENERATED_CONCLUSION", "当前证据=false");
        for (var rejected : List.of(InvestmentReportVersion.ReviewStatus.REJECTED,
                InvestmentReportVersion.ReviewStatus.NEEDS_RESEARCH)) {
            report.setReviewStatus(rejected);
            assertThat(service.retrieve("u-a", "NVDA", "query", "").promptContext()).isBlank();
            assertThat(service.listForUser("u-a", 10).get(0).qualification().reviewStatus())
                    .isEqualTo(rejected.name());
        }
        when(reportVersionRepository.findByIdInAndUserId(anyCollection(), eq("u-a")))
                .thenReturn(List.of());
        var unknown = service.listForUser("u-a", 10).get(0).qualification();
        assertThat(unknown.reviewStatus()).isEqualTo("UNKNOWN");
        assertThat(unknown.reportGeneratedAt()).isNull();
        assertThat(service.retrieve("u-a", "NVDA", "query", "").promptContext())
                .contains("审核=UNKNOWN", "报告生成时间=未知", "当前证据=false");
    }

    @Test
    void retrievalUsesThirtyCandidatesCurrentWinnersDecayOrderAndUnifiedTrace() {
        properties.setRetrieve(true);
        properties.setInject(true);
        String longTermKey = "REPORT_RECOMMENDATION|NVDA|LONG_TERM";
        String shortTermKey = "REPORT_RECOMMENDATION|NVDA|SHORT_TERM";
        ResearchMemoryEntry olderWinner = indexedMemory(
                41L, longTermKey, AnalysisHorizon.LONG_TERM,
                ResearchMemoryEntry.ResolutionStatus.CURRENT,
                NOW.minusDays(180), "old winner text", "HOLD");
        ResearchMemoryEntry freshWinner = indexedMemory(
                42L, shortTermKey, AnalysisHorizon.SHORT_TERM,
                ResearchMemoryEntry.ResolutionStatus.CURRENT,
                NOW, "fresh winner text", "BUY");
        ResearchMemoryEntry superseded = indexedMemory(
                43L, shortTermKey, AnalysisHorizon.SHORT_TERM,
                ResearchMemoryEntry.ResolutionStatus.SUPERSEDED,
                NOW, "superseded opposite text", "SELL");
        when(vectorIndex.search("u-a", "NVDA", "query", 30)).thenReturn(List.of(
                new ResearchMemoryVectorIndex.Hit(41L, 0.95),
                new ResearchMemoryVectorIndex.Hit(42L, 0.70),
                new ResearchMemoryVectorIndex.Hit(43L, 0.99)
        ));
        when(repository.findByIdInAndUserIdAndRevokedAtIsNullAndVectorStatus(
                anyCollection(), eq("u-a"), eq(ResearchMemoryEntry.VectorStatus.INDEXED)))
                .thenReturn(List.of(superseded, olderWinner, freshWinner));
        when(conflictGroupRepository.findByUserIdAndConflictKeyIn(
                eq("u-a"), anyCollection()))
                .thenReturn(List.of(
                        resolvedGroup(longTermKey, 41L),
                        resolvedGroup(shortTermKey, 42L)
                ));

        ResearchMemoryService.RetrievalResult result = service.retrieve(new ResearchMemoryQuery(
                "u-a", "NVDA", "query", "trace",
                AnalysisDepth.STANDARD, TimeSensitivity.RECENT));

        assertThat(result.injected()).isTrue();
        assertThat(result.hitCount()).isEqualTo(2);
        assertThat(result.promptContext())
                .contains("fresh winner text", "old winner text")
                .doesNotContain("superseded opposite text");
        assertThat(result.promptContext().indexOf("fresh winner text"))
                .isLessThan(result.promptContext().indexOf("old winner text"));
        verify(vectorIndex).search("u-a", "NVDA", "query", 30);
        ArgumentCaptor<AgentStep> stepCaptor = ArgumentCaptor.forClass(AgentStep.class);
        verify(traceService).addStep(eq("trace"), stepCaptor.capture());
        assertThat(stepCaptor.getValue().getAttributes())
                .containsEntry("candidateK", 30)
                .containsEntry("shortlistK", 12)
                .containsEntry("finalTopK", 6)
                .containsEntry("candidateCount", 3)
                .containsEntry("truthCount", 3)
                .containsEntry("currentWinnerCount", 2)
                .containsEntry("unresolvedGroupCount", 0)
                .containsEntry("injected", true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> traceHits = (List<Map<String, Object>>)
                stepCaptor.getValue().getAttributes().get("hits");
        assertThat(traceHits).extracting(hit -> hit.get("entryId"))
                .containsExactly(42L, 41L);
        assertThat(traceHits.get(1)).satisfies(hit -> {
            assertThat(((Number) hit.get("freshness")).doubleValue()).isCloseTo(0.25,
                    org.assertj.core.data.Offset.offset(1.0e-12));
            assertThat(((Number) hit.get("effectiveScore")).doubleValue()).isCloseTo(0.2375,
                    org.assertj.core.data.Offset.offset(1.0e-12));
        });
    }

    @Test
    void unresolvedConflictInjectsOnlySafeWarning() {
        properties.setRetrieve(true);
        properties.setInject(true);
        String conflictKey = "REPORT_RECOMMENDATION|NVDA|MEDIUM_TERM";
        ResearchMemoryEntry bullish = indexedMemory(
                41L, conflictKey, AnalysisHorizon.MEDIUM_TERM,
                ResearchMemoryEntry.ResolutionStatus.CONFLICTED,
                NOW, "bullish conflicting body", "BUY");
        ResearchMemoryEntry bearish = indexedMemory(
                42L, conflictKey, AnalysisHorizon.MEDIUM_TERM,
                ResearchMemoryEntry.ResolutionStatus.CONFLICTED,
                NOW, "bearish conflicting body", "SELL");
        when(vectorIndex.search("u-a", "NVDA", "query", 30)).thenReturn(List.of(
                new ResearchMemoryVectorIndex.Hit(41L, 0.95),
                new ResearchMemoryVectorIndex.Hit(42L, 0.94)
        ));
        when(repository.findByIdInAndUserIdAndRevokedAtIsNullAndVectorStatus(
                anyCollection(), eq("u-a"), eq(ResearchMemoryEntry.VectorStatus.INDEXED)))
                .thenReturn(List.of(bullish, bearish));
        when(conflictGroupRepository.findByUserIdAndConflictKeyIn(
                eq("u-a"), anyCollection()))
                .thenReturn(List.of(unresolvedGroup(conflictKey)));

        ResearchMemoryService.RetrievalResult result = service.retrieve(new ResearchMemoryQuery(
                "u-a", "NVDA", "query", "trace",
                AnalysisDepth.STANDARD, TimeSensitivity.RECENT));

        assertThat(result.hitCount()).isZero();
        assertThat(result.injected()).isTrue();
        assertThat(result.promptContext())
                .contains("未消解冲突", "系统已排除相反结论")
                .doesNotContain("bullish conflicting body", "bearish conflicting body");
        verify(vectorIndex).search("u-a", "NVDA", "query", 30);
    }

    @Test
    void deepPromptFitsEightCompleteRowsInsideTheConfiguredBudget() {
        properties.setRetrieve(true);
        properties.setInject(true);
        properties.setMaxPromptChars(4800);
        List<ResearchMemoryVectorIndex.Hit> hits = new ArrayList<>();
        List<ResearchMemoryEntry> entries = new ArrayList<>();
        List<ResearchMemoryConflictGroup> groups = new ArrayList<>();
        for (int index = 1; index <= 8; index++) {
            long id = 40L + index;
            String ticker = "T" + index;
            String conflictKey = "REPORT_RECOMMENDATION|" + ticker + "|LONG_TERM";
            ResearchMemoryEntry entry = indexedMemory(
                    id, conflictKey, AnalysisHorizon.LONG_TERM,
                    ResearchMemoryEntry.ResolutionStatus.CURRENT,
                    NOW.minusDays(index), "body-" + index + "-" + "x".repeat(3000), "HOLD");
            entry.setTicker(ticker);
            entry.setSourceCitations("[\"" + "s".repeat(500) + "\"]");
            hits.add(new ResearchMemoryVectorIndex.Hit(id, 1.0 - index / 100.0));
            entries.add(entry);
            groups.add(resolvedGroup(conflictKey, id));
        }
        when(vectorIndex.search("u-a", "", "portfolio history", 30)).thenReturn(hits);
        when(repository.findByIdInAndUserIdAndRevokedAtIsNullAndVectorStatus(
                anyCollection(), eq("u-a"), eq(ResearchMemoryEntry.VectorStatus.INDEXED)))
                .thenReturn(entries);
        when(conflictGroupRepository.findByUserIdAndConflictKeyIn(
                eq("u-a"), anyCollection())).thenReturn(groups);

        ResearchMemoryService.RetrievalResult result = service.retrieve(new ResearchMemoryQuery(
                "u-a", "", "portfolio history", "trace",
                AnalysisDepth.DEEP, TimeSensitivity.RECENT));

        assertThat(result.injected()).isTrue();
        assertThat(result.hitCount()).isEqualTo(8);
        assertThat(result.promptContext().length()).isLessThanOrEqualTo(4800);
        for (int index = 1; index <= 8; index++) {
            String marker = "[M" + index + "]";
            assertThat(result.promptContext()).containsOnlyOnce(marker);
        }
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
        source.setReviewStatus(InvestmentReportVersion.ReviewStatus.REJECTED);
        ResearchMemoryEntry own = new ResearchMemoryEntry();
        own.setId(41L);
        own.setUserId("u-a");
        own.setVectorStatus(ResearchMemoryEntry.VectorStatus.INDEXED);
        when(repository.findByUserIdAndSourceTypeAndSourceId(
                "u-a", "INVESTMENT_REPORT_VERSION", "7")).thenReturn(Optional.of(own));

        assertThat(service.revokeForReport(source)).isTrue();

        assertThat(own.getVectorStatus()).isEqualTo(ResearchMemoryEntry.VectorStatus.REVOKED);
        assertThat(own.getRevokedAt()).isNotNull();
        verify(vectorIndex, never()).delete(41L);
    }

    private ResearchMemoryEntry indexedMemory(
            Long id,
            String conflictKey,
            AnalysisHorizon horizon,
            ResearchMemoryEntry.ResolutionStatus resolutionStatus,
            LocalDateTime referenceAt,
            String memoryText,
            String recommendation
    ) {
        ResearchMemoryEntry entry = new ResearchMemoryEntry();
        entry.setId(id);
        entry.setUserId("u-a");
        entry.setTicker("NVDA");
        entry.setSourceType("INVESTMENT_REPORT_VERSION");
        entry.setSourceId(String.valueOf(id));
        entry.setSourceCitations("[\"SEC filing\"]");
        entry.setMemoryText(memoryText);
        entry.setAnalysisHorizon(horizon);
        entry.setRecommendation(recommendation);
        entry.setConflictKey(conflictKey);
        entry.setResolutionStatus(resolutionStatus);
        entry.setDataCutoffAt(referenceAt);
        entry.setCreatedAt(referenceAt);
        entry.setVectorStatus(ResearchMemoryEntry.VectorStatus.INDEXED);
        return entry;
    }

    private ResearchMemoryConflictGroup resolvedGroup(String conflictKey, Long winnerId) {
        ResearchMemoryConflictGroup group = new ResearchMemoryConflictGroup();
        group.setUserId("u-a");
        group.setConflictKey(conflictKey);
        group.setWinnerEntryId(winnerId);
        group.setResolutionStatus(ResearchMemoryConflictGroup.ResolutionStatus.RESOLVED);
        return group;
    }

    private ResearchMemoryConflictGroup unresolvedGroup(String conflictKey) {
        ResearchMemoryConflictGroup group = new ResearchMemoryConflictGroup();
        group.setUserId("u-a");
        group.setConflictKey(conflictKey);
        group.setResolutionStatus(ResearchMemoryConflictGroup.ResolutionStatus.UNRESOLVED);
        return group;
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
                .recommendation("HOLD")
                .rationale(List.of("Revenue growth remains strong"))
                .riskFactors(List.of("Valuation risk"))
                .citations(citations)
                .evidenceItems(evidenceItems)
                .build();
    }
}
