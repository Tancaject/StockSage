package com.stocksage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.harness.HarnessModels.HarnessDecision;
import com.stocksage.harness.HarnessModels.HarnessOutcome;
import com.stocksage.harness.HarnessModels.HarnessPhase;
import com.stocksage.harness.HarnessModels.HarnessSnapshot;
import com.stocksage.model.dto.AnalysisHorizon;
import com.stocksage.model.dto.AnalysisState;
import com.stocksage.model.dto.DebateModels.DebatePoint;
import com.stocksage.model.dto.DebateModels.DebateTurn;
import com.stocksage.model.dto.DebateModels.EvidenceRef;
import com.stocksage.model.dto.DebateModels.PointType;
import com.stocksage.model.dto.DebateModels.Side;
import com.stocksage.model.entity.ResearchTask;
import com.stocksage.model.entity.ResearchTaskCheckpoint;
import com.stocksage.repository.ResearchTaskCheckpointRepository;
import org.junit.jupiter.api.Test;

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
            new ResearchTaskCheckpointService(repository, new ObjectMapper());

    @Test
    void analysisStateRoundTripsThroughCheckpointJson() {
        AnalysisState state = AnalysisState.builder()
                .query("苹果值不值得投资").primaryTicker("AAPL")
                .fundamentalsReport("F").marketReport("M").newsReport("N")
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
        when(repository.findByTaskId(42L)).thenReturn(Optional.of(saved));

        ResearchTaskCheckpointService.CheckpointState loaded = service.load(42L).orElseThrow();
        assertThat(loaded.stageCompleted()).isEqualTo(ResearchTask.Stage.AGENT_DEBATE);
        assertThat(loaded.debateRoundsCompleted()).isEqualTo(1);
        assertThat(loaded.plannedRounds()).isEqualTo(2);
        assertThat(loaded.state().getDebateTurns()).hasSize(1);
        assertThat(loaded.state().getDebateTurns().get(0).points().get(0).claim())
                .isEqualTo("bull-r1");
        assertThat(loaded.state().getFundamentalsReport()).isEqualTo("F");
    }

    @Test
    void corruptedPayloadReturnsEmptyInsteadOfThrowing() {
        ResearchTaskCheckpoint broken = new ResearchTaskCheckpoint();
        broken.setTaskId(7L);
        broken.setStageCompleted(ResearchTask.Stage.DATA_PREFETCH);
        broken.setPayloadJson("{not-json");
        when(repository.findByTaskId(7L)).thenReturn(Optional.of(broken));

        assertThat(service.load(7L)).isEmpty();
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
