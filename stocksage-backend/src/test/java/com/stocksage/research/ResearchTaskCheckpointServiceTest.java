package com.stocksage.research;

import com.stocksage.knowledge.ResearchMemoryService;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.agent.intent.TimeSensitivity;
import com.stocksage.evidence.EvidenceLedger;
import com.stocksage.evidence.EvidenceTiming;
import com.stocksage.evidence.EvidenceModels.EvidenceDimension;
import com.stocksage.evidence.EvidenceModels.EvidenceEnvelope;
import com.stocksage.evidence.EvidenceModels.EvidenceStatus;
import com.stocksage.harness.DeepResearchCompletionPolicy;
import com.stocksage.harness.HarnessModels.HarnessDecision;
import com.stocksage.harness.HarnessModels.HarnessOutcome;
import com.stocksage.harness.HarnessModels.HarnessPhase;
import com.stocksage.harness.HarnessModels.HarnessSnapshot;
import com.stocksage.evidence.EvidenceModels.TargetIdentity;
import com.stocksage.model.dto.AnalysisHorizon;
import com.stocksage.model.dto.AnalysisState;
import com.stocksage.model.dto.ModelInvocationContext;
import com.stocksage.model.dto.DebateModels.DebatePoint;
import com.stocksage.model.dto.DebateModels.DebateTurn;
import com.stocksage.model.dto.DebateModels.EvidenceRef;
import com.stocksage.model.dto.DebateModels.PointType;
import com.stocksage.model.dto.DebateModels.Side;
import com.stocksage.model.entity.ResearchTask;
import com.stocksage.model.entity.ResearchTaskCheckpoint;
import com.stocksage.repository.ResearchTaskCheckpointRepository;
import com.stocksage.repository.InvestmentReportReviewRepository;
import com.stocksage.repository.InvestmentReportVersionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResearchTaskCheckpointServiceTest {

    private final ResearchTaskCheckpointRepository repository = mock(ResearchTaskCheckpointRepository.class);
    private final ResearchTaskCheckpointService service =
            new ResearchTaskCheckpointService(repository, new ObjectMapper().findAndRegisterModules());

    @Test
    void analysisStateRoundTripsThroughCheckpointJson() throws Exception {
        Instant fetchedAt = Instant.parse("2026-09-26T01:02:03Z");
        Instant latestBar = Instant.parse("2026-09-25T20:00:00Z");
        EvidenceTiming timing = new EvidenceTiming(1, fetchedAt,
                new EvidenceTiming.Market("US", "1h", "INSTANT", null, latestBar, null), null, null);
        AnalysisState state = AnalysisState.builder()
                .query("苹果值不值得投资").primaryTicker("AAPL")
                .timeSensitivity(TimeSensitivity.REAL_TIME)
                .fundamentalsReport("F").marketReport("M").newsReport("N")
                .evidenceLedger(new EvidenceLedger(TargetIdentity.resolved("AAPL"), List.of(
                        new EvidenceEnvelope("e-market", EvidenceDimension.MARKET, "getIbkrHistoricalBars",
                                "AAPL", EvidenceStatus.AVAILABLE, "ibkr://history/AAPL", "IBKR_WEB_API",
                                fetchedAt, latestBar, "market-payload", true, timing))))
                .build();
        state.getDebateTurns().add(new DebateTurn(1, Side.BULL, List.of(
                new DebatePoint(
                        "bull-thesis-1",
                        PointType.THESIS,
                        "bull-r1",
                        AnalysisHorizon.MEDIUM_TERM,
                        List.of(new EvidenceRef("ev-1", "evidence excerpt")),
                        "reasoning",
                        "assumption",
                        "invalidation",
                        List.of()
                )
        )));

        ResearchTaskCheckpoint saved = service.toEntity(42L, state,
                ResearchTask.Stage.AGENT_DEBATE, 1, 2);
        var payload = new ObjectMapper().readTree(saved.getPayloadJson());
        assertThat(payload.path("schemaVersion").intValue()).isEqualTo(1);
        assertThat(payload.path("state").path("query").textValue()).isEqualTo(state.getQuery());
        when(repository.findByTaskId(42L)).thenReturn(Optional.of(saved));

        ResearchTaskCheckpointService.CheckpointState loaded = service.load(42L).orElseThrow();
        assertThat(loaded.stageCompleted()).isEqualTo(ResearchTask.Stage.AGENT_DEBATE);
        assertThat(loaded.debateRoundsCompleted()).isEqualTo(1);
        assertThat(loaded.plannedRounds()).isEqualTo(2);
        assertThat(loaded.state().getDebateTurns()).hasSize(1);
        assertThat(loaded.state().getDebateTurns().get(0).points().get(0).claim())
                .isEqualTo("bull-r1");
        assertThat(loaded.state().getFundamentalsReport()).isEqualTo("F");
        assertThat(loaded.state().getTimeSensitivity()).isEqualTo(TimeSensitivity.REAL_TIME);
        assertThat(loaded.state().getEvidenceLedger().evidence()).singleElement()
                .satisfies(evidence -> {
                    assertThat(evidence.timing()).isEqualTo(timing);
                    assertThat(evidence.timing().market().delayed()).isNull();
                    assertThat(evidence.timing().market().latestDate()).isNull();
                });
    }

    @Test
    void legacyCheckpointWithoutTimingOrTimeSensitivityRemainsReadableAndUnknown() {
        ResearchTaskCheckpoint legacy = new ResearchTaskCheckpoint();
        legacy.setTaskId(7L);
        legacy.setStageCompleted(ResearchTask.Stage.AGENT_DEBATE);
        legacy.setDebateRoundsCompleted(2);
        legacy.setPlannedRounds(3);
        legacy.setPayloadJson("""
                {"primaryTicker":"AAPL","query":"AAPL today",
                 "evidenceLedger":{
                  "target":{"canonicalKey":"AAPL","displaySymbol":"AAPL","status":"RESOLVED"},
                  "evidence":[{"evidenceId":"e-market","dimension":"MARKET",
                    "capabilityId":"getIbkrHistoricalBars","targetKey":"AAPL","status":"AVAILABLE",
                    "sourceRef":"ibkr://history/AAPL","provider":"IBKR_WEB_API",
                    "observedAt":"2026-09-26T01:02:03Z","asOf":"2026-09-25T20:00:00Z",
                    "payloadHash":"legacy-market-payload","approvedReadOnly":true}]}}
                """);
        when(repository.findByTaskId(7L)).thenReturn(Optional.of(legacy));

        ResearchTaskCheckpointService.CheckpointState loaded = service.load(7L).orElseThrow();

        assertThat(loaded.stageCompleted()).isEqualTo(ResearchTask.Stage.AGENT_DEBATE);
        assertThat(loaded.debateRoundsCompleted()).isEqualTo(2);
        assertThat(loaded.plannedRounds()).isEqualTo(3);
        assertThat(loaded.state().getTimeSensitivity()).isEqualTo(TimeSensitivity.UNSPECIFIED);
        assertThat(loaded.state().getEvidenceLedger().evidence()).singleElement()
                .satisfies(evidence -> {
                    assertThat(evidence.asOf()).isEqualTo(Instant.parse("2026-09-25T20:00:00Z"));
                    assertThat(evidence.timing()).isNull();
                });
    }

    @Test
    void preEvidencePackageCheckpointKeepsJsonAndSnapshotHashes() throws Exception {
        // Captured with the pre-move target/classes, Boot's Jackson auto-configuration,
        // checkpoint.toEntity and reportVersion.prepareHashes; not authored from the new DTOs.
        // Only Jackson configuration is loaded: no application beans or external stores.
        try (var context = new AnnotationConfigApplicationContext(JacksonAutoConfiguration.class);
             var fixture = getClass().getResourceAsStream(
                     "/checkpoints/analysis-state-before-evidence-package.json")) {
            assertThat(fixture).isNotNull();
            String historicalJson = new String(fixture.readAllBytes(), StandardCharsets.UTF_8);
            ObjectMapper mapper = context.getBean(ObjectMapper.class);
            ResearchTaskCheckpointService checkpoints = new ResearchTaskCheckpointService(repository, mapper);
            ResearchTaskCheckpoint historical = new ResearchTaskCheckpoint();
            historical.setTaskId(42L);
            historical.setStageCompleted(ResearchTask.Stage.DATA_PREFETCH);
            historical.setDebateRoundsCompleted(0);
            historical.setPlannedRounds(0);
            historical.setPayloadJson(historicalJson);
            when(repository.findByTaskId(42L)).thenReturn(Optional.of(historical));

            var loaded = checkpoints.load(42L).orElseThrow();
            AnalysisState state = loaded.state();
            state.setModelInvocationContext(new ModelInvocationContext(42L, 3, "current-owner", "trace-42", "snapshot-test", System.currentTimeMillis() + 60_000));
            assertThat(state.getTimeSensitivity()).isEqualTo(TimeSensitivity.RECENT);
            assertThat(state.getEvidenceLedger().target()).isEqualTo(TargetIdentity.resolved("AAPL"));
            assertThat(state.getEvidenceLedger().usableEvidenceIds())
                    .containsExactlyInAnyOrder("market-instant", "market-date", "fundamentals", "news");
            var evidence = state.getEvidenceLedger().evidence();
            assertThat(evidence).extracting(EvidenceEnvelope::evidenceId)
                    .containsExactly("market-instant", "market-date", "fundamentals", "news", "rag-legacy");
            assertThat(evidence.get(0).observedAt()).isEqualTo(Instant.parse("2026-09-26T01:02:03.123456789Z"));
            assertThat(evidence.get(0).timing().market().latestInstant())
                    .isEqualTo(Instant.parse("2026-09-25T20:00:00Z"));
            assertThat(evidence.get(0).timing().market().delayed()).isNull();
            assertThat(evidence.get(1).timing().market().latestDate()).isEqualTo(LocalDate.parse("2026-09-25"));
            assertThat(evidence.get(1).timing().market().latestInstant()).isNull();
            assertThat(evidence.get(2).timing().financial()).isEqualTo(new EvidenceTiming.Financial(
                    "annual", LocalDate.parse("2025-09-27"), LocalDate.parse("2025-10-31")));
            assertThat(evidence.get(3).timing().search().unknownCount()).isEqualTo(1);
            assertThat(evidence.get(4).status()).isEqualTo(EvidenceStatus.NO_RESULTS);
            assertThat(evidence.get(4).timing()).isNull();

            InvestmentReportVersionService versions = new InvestmentReportVersionService(
                    mock(InvestmentReportVersionRepository.class), mock(InvestmentReportReviewRepository.class),
                    mapper, mock(ApplicationEventPublisher.class), mock(ResearchMemoryService.class),
                    new DeepResearchCompletionPolicy());
            assertThat(versions.computeDataSnapshotHash(state)).isEqualTo(state.getDataSnapshotHash());
            assertThat(versions.computeContextHash(state)).isEqualTo(state.getContextHash());
            String reserialized = checkpoints.toEntity(42L, state, loaded.stageCompleted(),
                    loaded.debateRoundsCompleted(), loaded.plannedRounds()).getPayloadJson();
            assertThat(mapper.readTree(reserialized).path("schemaVersion").intValue()).isEqualTo(1);
            assertThat(mapper.readTree(reserialized).path("state")).isEqualTo(mapper.readTree(historicalJson));
            assertThat(historicalJson).doesNotContain("com.stocksage.", "\"@class\"", "\"@type\"");
            assertThat(reserialized).doesNotContain("com.stocksage.", "\"@class\"", "\"@type\"");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{not-json", "null", "[]",
            "{\"schemaVersion\":2,\"state\":{}}",
            "{\"schemaVersion\":\"1\",\"state\":{}}",
            "{\"schemaVersion\":1.5,\"state\":{}}",
            "{\"schemaVersion\":4294967297,\"state\":{}}",
            "{\"schemaVersion\":null,\"state\":{}}",
            "{\"schemaVersion\":1}",
            "{\"schemaVersion\":1,\"state\":null}",
            "{\"state\":{}}"
    })
    void unreadablePayloadStopsRecoveryInsteadOfDiscardingDurableDecisions(String payload) {
        ResearchTaskCheckpoint broken = new ResearchTaskCheckpoint();
        broken.setTaskId(7L);
        broken.setStageCompleted(ResearchTask.Stage.DATA_PREFETCH);
        broken.setPayloadJson(payload);
        when(repository.findByTaskId(7L)).thenReturn(Optional.of(broken));

        assertThatThrownBy(() -> service.load(7L))
                .isInstanceOf(ResearchTaskCheckpointService.CheckpointFormatException.class)
                .hasMessageContaining("7", "原快照已保留");
        assertThat(broken.getPayloadJson()).isEqualTo(payload);
    }

    @Test
    void harnessSnapshotPersistenceFailsClosed() {
        AnalysisState state = AnalysisState.builder().query("q").build();
        state.setHarnessSnapshot(HarnessSnapshot.from(
                "deep-equity-v1",
                "1",
                HarnessPhase.EVIDENCE,
                new HarnessDecision(HarnessOutcome.RECOVER, java.util.List.of(), java.util.List.of()),
                java.util.Map.of()
        ));
        when(repository.lockOwnedRunningTask(42L, "lease")).thenReturn(Optional.of(42L));
        when(repository.findByTaskId(42L)).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any()))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> service.saveHarnessSnapshot(42L, "lease", state))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("database unavailable");
    }

    @Test
    void debateRoundPersistenceFailsClosed() {
        AnalysisState state = AnalysisState.builder().query("q").build();
        when(repository.lockOwnedRunningTask(42L, "lease")).thenReturn(Optional.of(42L));
        when(repository.findByTaskId(42L)).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any()))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> service.saveDebateRound(42L, "lease", state, 1, 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("database unavailable");
    }
}
