package com.stocksage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:research-memory-service-tx;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "stocksage.research-memory.compensation-initial-delay-ms=3600000"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        ResearchMemoryService.class,
        ResearchMemoryServiceTransactionTest.Dependencies.class
})
class ResearchMemoryServiceTransactionTest {

    @Autowired
    private ResearchMemoryService service;

    @Autowired
    private ResearchMemoryEntryRepository memoryRepository;

    @Autowired
    private ResearchMemoryConflictGroupRepository conflictGroupRepository;

    @Autowired
    private InvestmentReportVersionRepository reportRepository;

    @Autowired
    private ResearchMemoryVectorIndex vectorIndex;

    @Autowired
    private ResearchMemoryProperties properties;

    @BeforeEach
    void setUp() {
        reset(vectorIndex);
        properties.setCapture(true);
        properties.setIndex(true);
        properties.setRetrieve(false);
        properties.setInject(false);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void captureCommitReconcilesConflictWinnerAndPersistsPostCommitIndexOutcome() {
        InvestmentReportVersion indexedSource = reportRepository.saveAndFlush(source(1));

        ResearchMemoryEntry indexed =
                service.capture(indexedSource, verifiedReport());

        ResearchMemoryEntry persistedIndexed = memoryRepository.findById(indexed.getId()).orElseThrow();
        assertThat(persistedIndexed.getVectorStatus())
                .isEqualTo(ResearchMemoryEntry.VectorStatus.INDEXED);
        assertThat(persistedIndexed.getResolutionStatus())
                .isEqualTo(ResearchMemoryEntry.ResolutionStatus.CURRENT);
        assertThat(persistedIndexed.getAnalysisHorizon()).isEqualTo(AnalysisHorizon.MEDIUM_TERM);
        assertThat(persistedIndexed.getRecommendation()).isEqualTo("HOLD");
        ResearchMemoryConflictGroup initialGroup = conflictGroupRepository
                .findByUserIdAndConflictKey(
                        "u-a", "REPORT_RECOMMENDATION|NVDA|MEDIUM_TERM")
                .orElseThrow();
        assertThat(initialGroup.getResolutionStatus())
                .isEqualTo(ResearchMemoryConflictGroup.ResolutionStatus.RESOLVED);
        assertThat(initialGroup.getWinnerEntryId()).isEqualTo(indexed.getId());
        verify(vectorIndex).index(any(ResearchMemoryEntry.class));

        InvestmentReportVersion failedSource = reportRepository.saveAndFlush(source(2));
        doThrow(new IllegalStateException("milvus unavailable"))
                .when(vectorIndex).index(any(ResearchMemoryEntry.class));

        ResearchMemoryEntry failed =
                service.capture(failedSource, verifiedReport());

        ResearchMemoryEntry persistedFailed =
                memoryRepository.findById(failed.getId()).orElseThrow();
        assertThat(persistedFailed.getVectorStatus())
                .isEqualTo(ResearchMemoryEntry.VectorStatus.FAILED);
        assertThat(persistedFailed.getVectorErrorCode()).isEqualTo("VECTOR_INDEX_FAILED");
        assertThat(persistedFailed.getResolutionStatus())
                .isEqualTo(ResearchMemoryEntry.ResolutionStatus.CURRENT);

        ResearchMemoryEntry superseded = memoryRepository.findById(indexed.getId()).orElseThrow();
        assertThat(superseded.getResolutionStatus())
                .isEqualTo(ResearchMemoryEntry.ResolutionStatus.SUPERSEDED);
        assertThat(superseded.getSupersededById()).isEqualTo(failed.getId());
        assertThat(superseded.getSupersededAt()).isNotNull();

        ResearchMemoryConflictGroup reconciledGroup = conflictGroupRepository
                .findByUserIdAndConflictKey(
                        "u-a", "REPORT_RECOMMENDATION|NVDA|MEDIUM_TERM")
                .orElseThrow();
        assertThat(reconciledGroup.getResolutionStatus())
                .isEqualTo(ResearchMemoryConflictGroup.ResolutionStatus.RESOLVED);
        assertThat(reconciledGroup.getWinnerEntryId()).isEqualTo(failed.getId());
        verify(vectorIndex, times(2)).index(any(ResearchMemoryEntry.class));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void negativeReviewReelectsOlderMemoryButUserRevokeBlocksUntilNewerEvidenceArrives() {
        properties.setIndex(false);
        String userId = "u-block";
        InvestmentReportVersion olderSource = reportRepository.saveAndFlush(source(
                userId, 11, LocalDateTime.of(2026, 1, 11, 0, 0)));
        InvestmentReportVersion newerSource = reportRepository.saveAndFlush(source(
                userId, 12, LocalDateTime.of(2026, 2, 12, 0, 0)));
        ResearchMemoryEntry older = service.capture(olderSource, verifiedReport());
        ResearchMemoryEntry newer = service.capture(newerSource, verifiedReport());

        newerSource = reportRepository.findById(newerSource.getId()).orElseThrow();
        newerSource.setReviewStatus(InvestmentReportVersion.ReviewStatus.REJECTED);
        newerSource = reportRepository.saveAndFlush(newerSource);
        assertThat(service.revokeForReport(newerSource)).isTrue();
        ResearchMemoryConflictGroup reelected = conflictGroupRepository
                .findByUserIdAndConflictKey(
                        userId, "REPORT_RECOMMENDATION|NVDA|MEDIUM_TERM")
                .orElseThrow();
        assertThat(reelected.getResolutionStatus())
                .isEqualTo(ResearchMemoryConflictGroup.ResolutionStatus.RESOLVED);
        assertThat(reelected.getWinnerEntryId()).isEqualTo(older.getId());

        newerSource = reportRepository.findById(newerSource.getId()).orElseThrow();
        newerSource.setReviewStatus(InvestmentReportVersion.ReviewStatus.IN_REVIEW);
        newerSource = reportRepository.saveAndFlush(newerSource);
        assertThat(service.reconcileForReport(newerSource)).isTrue();
        ResearchMemoryEntry restored = memoryRepository.findById(newer.getId()).orElseThrow();
        assertThat(restored.getRevokedAt()).isNull();
        assertThat(restored.getVectorStatus()).isEqualTo(ResearchMemoryEntry.VectorStatus.PENDING);
        ResearchMemoryConflictGroup restoredGroup = conflictGroupRepository
                .findByUserIdAndConflictKey(
                        userId, "REPORT_RECOMMENDATION|NVDA|MEDIUM_TERM")
                .orElseThrow();
        assertThat(restoredGroup.getWinnerEntryId()).isEqualTo(newer.getId());

        assertThat(service.revoke(userId, newer.getId())).isTrue();
        ResearchMemoryConflictGroup blocked = conflictGroupRepository
                .findByUserIdAndConflictKey(
                        userId, "REPORT_RECOMMENDATION|NVDA|MEDIUM_TERM")
                .orElseThrow();
        assertThat(blocked.getResolutionStatus())
                .isEqualTo(ResearchMemoryConflictGroup.ResolutionStatus.BLOCKED);
        assertThat(blocked.getWinnerEntryId()).isNull();
        assertThat(blocked.getBlockedBeforeAt()).isNotNull();

        assertThat(service.reconcileForReport(newerSource)).isTrue();
        ResearchMemoryEntry stillUserRevoked = memoryRepository.findById(newer.getId()).orElseThrow();
        assertThat(stillUserRevoked.getRevokedAt()).isNotNull();
        assertThat(stillUserRevoked.getVectorStatus())
                .isEqualTo(ResearchMemoryEntry.VectorStatus.REVOKED);
        assertThat(conflictGroupRepository.findById(blocked.getId()).orElseThrow()
                .getResolutionStatus()).isEqualTo(
                        ResearchMemoryConflictGroup.ResolutionStatus.BLOCKED);

        InvestmentReportVersion replacementSource = reportRepository.saveAndFlush(source(
                userId, 13, LocalDateTime.now().plusDays(1)));
        ResearchMemoryEntry replacement = service.capture(replacementSource, verifiedReport());
        ResearchMemoryConflictGroup unblocked = conflictGroupRepository
                .findByUserIdAndConflictKey(
                        userId, "REPORT_RECOMMENDATION|NVDA|MEDIUM_TERM")
                .orElseThrow();
        assertThat(unblocked.getResolutionStatus())
                .isEqualTo(ResearchMemoryConflictGroup.ResolutionStatus.RESOLVED);
        assertThat(unblocked.getWinnerEntryId()).isEqualTo(replacement.getId());
        assertThat(unblocked.getBlockedBeforeAt()).isNull();
        assertThat(memoryRepository.findById(replacement.getId()).orElseThrow()
                .getResolutionStatus()).isEqualTo(ResearchMemoryEntry.ResolutionStatus.CURRENT);
    }

    private InvestmentReportVersion source(int sequence) {
        return source("u-a", sequence,
                LocalDateTime.of(2026, 1, 1, 0, 0).plusDays(sequence));
    }

    private InvestmentReportVersion source(
            String userId,
            int sequence,
            LocalDateTime generatedAt
    ) {
        InvestmentReportVersion source = new InvestmentReportVersion();
        source.setUserId(userId);
        source.setConversationId((long) sequence);
        source.setTicker("NVDA");
        source.setRecommendation("HOLD");
        source.setReportVersion(sequence);
        source.setDataSnapshotHash(Integer.toHexString(sequence).repeat(64).substring(0, 64));
        source.setContextHash(Integer.toHexString(sequence + 10).repeat(64).substring(0, 64));
        source.setModelTier("STRONG");
        source.setModelName("qwen");
        source.setReportJson("{}");
        source.setGeneratedAt(generatedAt);
        return source;
    }

    private InvestmentReport verifiedReport() {
        return InvestmentReport.builder()
                .ticker("NVDA")
                .analysisHorizon(AnalysisHorizon.MEDIUM_TERM)
                .qualityStatus(InvestmentReport.ReportQualityStatus.VERIFIED)
                .recommendation("HOLD")
                .rationale(List.of("Revenue growth remains strong"))
                .riskFactors(List.of("Valuation risk"))
                .citations(List.of("[1] SEC filing"))
                .evidenceItems(List.of(InvestmentReport.EvidenceItem.builder()
                        .dimension("fundamentals")
                        .evidence("Revenue growth remains strong")
                        .implication("supports monitored growth")
                        .source("SEC filing")
                        .sourceEvidenceIds(List.of("e-fundamentals"))
                        .build()))
                .build();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Dependencies {

        @Bean
        @Primary
        ResearchMemoryVectorIndex researchMemoryVectorIndex() {
            return mock(ResearchMemoryVectorIndex.class);
        }

        @Bean
        ResearchMemoryProperties researchMemoryProperties() {
            return new ResearchMemoryProperties();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        TraceService traceService() {
            return mock(TraceService.class);
        }
    }
}
