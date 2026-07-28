package com.stocksage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.model.dto.InvestmentReport;
import com.stocksage.model.entity.InvestmentReportVersion;
import com.stocksage.model.entity.ResearchMemoryEntry;
import com.stocksage.repository.ResearchMemoryEntryRepository;
import com.stocksage.trace.TraceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResearchMemoryServiceTest {

    private final ResearchMemoryEntryRepository repository = mock(ResearchMemoryEntryRepository.class);
    private final ResearchMemoryVectorIndex vectorIndex = mock(ResearchMemoryVectorIndex.class);
    private final ResearchMemoryProperties properties = new ResearchMemoryProperties();
    private final TraceService traceService = mock(TraceService.class);
    private final ResearchMemoryService service = new ResearchMemoryService(
            repository, vectorIndex, properties, new ObjectMapper(), traceService);

    @BeforeEach
    void setUp() {
        properties.setCapture(true);
        properties.setIndex(false);
        properties.setRetrieve(false);
        properties.setInject(false);
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            ResearchMemoryEntry entry = invocation.getArgument(0);
            entry.setId(41L);
            return entry;
        });
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
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
        when(repository.findByIdInAndUserIdAndRevokedAtIsNull(anyCollection(), eq("u-a")))
                .thenReturn(List.of(own));

        ResearchMemoryService.RetrievalResult result =
                service.retrieve("u-a", "NVDA", "query", "trace");

        assertThat(result.hitCount()).isOne();
        assertThat(result.injected()).isFalse();
        assertThat(result.promptContext()).isBlank();
        verify(repository).findByIdInAndUserIdAndRevokedAtIsNull(
                org.mockito.ArgumentMatchers.argThat(ids -> ids.equals(java.util.Set.of(41L))), eq("u-a"));
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
