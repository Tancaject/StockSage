package com.stocksage.agent;

import com.stocksage.model.dto.AnalysisState;
import com.stocksage.model.dto.InvestmentReport;
import com.stocksage.tool.ChatStreamEmitter;
import com.stocksage.trace.TraceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResearchDebateServiceResumeTest {

    private final BullResearcher bullResearcher = mock(BullResearcher.class);
    private final BearResearcher bearResearcher = mock(BearResearcher.class);
    private final ResearchManager researchManager = mock(ResearchManager.class);
    private final DebateRoundPlanner debateRoundPlanner = mock(DebateRoundPlanner.class);
    private final ResearchDebateService service = new ResearchDebateService(
            bullResearcher,
            bearResearcher,
            researchManager,
            debateRoundPlanner,
            mock(TraceService.class),
            mock(ChatStreamEmitter.class)
    );

    @BeforeEach
    void setUp() {
        when(bullResearcher.argue(any(), anyInt()))
                .thenAnswer(invocation -> Flux.just("bull-r" + invocation.getArgument(1, Integer.class)));
        when(bearResearcher.argue(any(), anyInt()))
                .thenAnswer(invocation -> Flux.just("bear-r" + invocation.getArgument(1, Integer.class)));
        InvestmentReport report = InvestmentReport.builder().analystSummary("done").build();
        when(researchManager.synthesizeStreaming(any(), any(), any(), any())).thenReturn(Mono.just(report));
    }

    @Test
    void resumeFromRound3SkipsPlannerAndEarlierRounds() {
        AnalysisState state = stateWithCompletedRounds(2);
        List<Integer> checkpoints = new ArrayList<>();

        AnalysisState done = service.runDebate(null, null, state, 3, 3,
                (current, rounds, planned) -> checkpoints.add(rounds));

        verify(debateRoundPlanner, never()).decide(any(), anyInt());
        verify(bullResearcher, never()).argue(any(), eq(1));
        verify(bullResearcher, never()).argue(any(), eq(2));
        verify(bullResearcher).argue(any(), eq(3));
        assertThat(checkpoints).containsExactly(3);
        assertThat(done.getDebateTurns()).hasSize(6);
    }

    @Test
    void freshRunStillPlansConcurrentlyWithRound1() {
        when(debateRoundPlanner.decide(any(), anyInt()))
                .thenReturn(new DebateRoundPlanner.RoundDecision(2, "two rounds"));
        List<Integer> checkpoints = new ArrayList<>();

        AnalysisState done = service.runDebate(null, null, AnalysisState.builder().query("q").build(),
                1, 0, (current, rounds, planned) -> checkpoints.add(rounds));

        verify(debateRoundPlanner).decide(any(), anyInt());
        verify(bullResearcher).argue(any(), eq(1));
        verify(bullResearcher).argue(any(), eq(2));
        assertThat(checkpoints).containsExactly(1, 2);
        assertThat(done.getDebateTurns()).hasSize(4);
    }

    private AnalysisState stateWithCompletedRounds(int rounds) {
        AnalysisState state = AnalysisState.builder().query("q").build();
        for (int round = 1; round <= rounds; round++) {
            state.getDebateTurns().add(new AnalysisState.DebateTurn(
                    round, AnalysisState.DebateTurn.Side.BULL, "old-bull-r" + round));
            state.getDebateTurns().add(new AnalysisState.DebateTurn(
                    round, AnalysisState.DebateTurn.Side.BEAR, "old-bear-r" + round));
        }
        return state;
    }
}
