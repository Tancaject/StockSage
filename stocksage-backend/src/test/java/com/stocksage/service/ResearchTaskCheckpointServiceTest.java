package com.stocksage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.model.dto.AnalysisState;
import com.stocksage.model.entity.ResearchTask;
import com.stocksage.model.entity.ResearchTaskCheckpoint;
import com.stocksage.repository.ResearchTaskCheckpointRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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
        state.getDebateTurns().add(new AnalysisState.DebateTurn(
                1, AnalysisState.DebateTurn.Side.BULL, "bull-r1"));

        ResearchTaskCheckpoint saved = service.toEntity(42L, state,
                ResearchTask.Stage.AGENT_DEBATE, 1, 3);
        when(repository.findByTaskId(42L)).thenReturn(Optional.of(saved));

        ResearchTaskCheckpointService.CheckpointState loaded = service.load(42L).orElseThrow();
        assertThat(loaded.stageCompleted()).isEqualTo(ResearchTask.Stage.AGENT_DEBATE);
        assertThat(loaded.debateRoundsCompleted()).isEqualTo(1);
        assertThat(loaded.plannedRounds()).isEqualTo(3);
        assertThat(loaded.state().getDebateTurns()).hasSize(1);
        assertThat(loaded.state().getDebateTurns().get(0).fullText()).isEqualTo("bull-r1");
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
}
