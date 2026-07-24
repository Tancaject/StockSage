package com.stocksage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.model.dto.AnalysisState;
import com.stocksage.model.dto.InvestmentReport;
import com.stocksage.model.entity.InvestmentReportVersion;
import com.stocksage.repository.InvestmentReportVersionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InvestmentReportVersionServiceTest {

    private final InvestmentReportVersionRepository repository = mock(InvestmentReportVersionRepository.class);
    private final InvestmentReportVersionService service = new InvestmentReportVersionService(
            repository,
            new ObjectMapper().findAndRegisterModules(),
            mock(ApplicationEventPublisher.class)
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

        InvestmentReport storedReport = InvestmentReport.builder()
                .recommendation("HOLD")
                .rationale(List.of("The stored report is tied to this exact snapshot."))
                .build();
        InvestmentReportVersion existing = new InvestmentReportVersion();
        existing.setUserId("u_001");
        existing.setTicker("NVDA");
        existing.setReportVersion(7);
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
    void reusesExistingReportWhenConcurrentPersistCreatesSameSnapshot() throws Exception {
        AnalysisState state = analysisState("NVDA", "market-v1", "risks-v1", "news-v1", "Should I buy NVDA?");
        state.setInvestmentReport(InvestmentReport.builder()
                .recommendation("BUY")
                .rationale(List.of("Fresh report from this request."))
                .build());
        service.prepareHashes(state);

        InvestmentReport storedReport = InvestmentReport.builder()
                .recommendation("BUY")
                .rationale(List.of("Concurrent request already persisted this snapshot."))
                .build();
        InvestmentReportVersion existing = new InvestmentReportVersion();
        existing.setUserId("u_001");
        existing.setTicker("NVDA");
        existing.setReportVersion(4);
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

    private AnalysisState analysisState(String ticker, String market, String fundamentals, String news, String query) {
        return AnalysisState.builder()
                .primaryTicker(ticker)
                .query(query)
                .marketReport(market)
                .fundamentalsReport(fundamentals)
                .newsReport(news)
                .citations(List.of("source-a", "source-b"))
                .build();
    }
}
