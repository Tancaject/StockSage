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
import static org.mockito.Mockito.verify;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
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
    void captureCommitPersistsPostCommitIndexOutcomeInIndependentTransaction() {
        InvestmentReportVersion indexedSource = reportRepository.saveAndFlush(source(1));

        ResearchMemoryEntry indexed =
                service.capture(indexedSource, verifiedReport());

        assertThat(memoryRepository.findById(indexed.getId()).orElseThrow().getVectorStatus())
                .isEqualTo(ResearchMemoryEntry.VectorStatus.INDEXED);
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
    }

    private InvestmentReportVersion source(int sequence) {
        InvestmentReportVersion source = new InvestmentReportVersion();
        source.setUserId("u-a");
        source.setConversationId((long) sequence);
        source.setTicker("NVDA");
        source.setRecommendation("WATCH");
        source.setReportVersion(sequence);
        source.setDataSnapshotHash(Integer.toHexString(sequence).repeat(64).substring(0, 64));
        source.setContextHash(Integer.toHexString(sequence + 10).repeat(64).substring(0, 64));
        source.setModelTier("STRONG");
        source.setModelName("qwen");
        source.setReportJson("{}");
        source.setGeneratedAt(LocalDateTime.now());
        return source;
    }

    private InvestmentReport verifiedReport() {
        return InvestmentReport.builder()
                .ticker("NVDA")
                .qualityStatus(InvestmentReport.ReportQualityStatus.VERIFIED)
                .recommendation("WATCH")
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
