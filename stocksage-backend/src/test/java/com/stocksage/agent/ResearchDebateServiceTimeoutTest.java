package com.stocksage.agent;

import com.stocksage.harness.DeepResearchCompletionPolicy;
import com.stocksage.harness.HarnessModels.SynthesisResult;
import com.stocksage.harness.ResearchHarness;
import com.stocksage.model.dto.AnalysisState;
import com.stocksage.tool.ChatStreamEmitter;
import com.stocksage.trace.TraceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResearchDebateServiceTimeoutTest {

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
            mock(ChatStreamEmitter.class),
            mock(ResearchHarness.class),
            new DeepResearchCompletionPolicy()
    );

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "modelStageTimeoutMs", 50L);
    }

    @Test
    void stalledDebateRoundIsCancelledAndReleasesCaller() {
        AtomicBoolean bullCancelled = new AtomicBoolean();
        AtomicBoolean bearCancelled = new AtomicBoolean();
        when(bullResearcher.argue(any(), anyInt()))
                .thenReturn(Flux.<String>never().doOnCancel(() -> bullCancelled.set(true)));
        when(bearResearcher.argue(any(), anyInt()))
                .thenReturn(Flux.<String>never().doOnCancel(() -> bearCancelled.set(true)));

        long startedAt = System.nanoTime();

        assertThatThrownBy(() -> service.runDebate(
                "trace-timeout",
                1L,
                AnalysisState.builder().query("q").build(),
                2,
                2,
                null
        ))
                .isInstanceOf(ResearchDebateService.ModelStageTimeoutException.class)
                .hasMessageContaining("stage=debate-round-2")
                .hasMessageContaining("timeoutMs=50");

        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(2));
        assertThat(bullCancelled).isTrue();
        assertThat(bearCancelled).isTrue();
        verify(researchManager, never()).synthesizeStreamingResult(any(), any(), any(), any());
    }

    @Test
    void stalledManagerSynthesisIsCancelledAndReleasesCaller() {
        AtomicBoolean managerCancelled = new AtomicBoolean();
        when(researchManager.synthesizeStreamingResult(any(), any(), any(), any()))
                .thenReturn(Mono.<SynthesisResult>never().doOnCancel(() -> managerCancelled.set(true)));

        long startedAt = System.nanoTime();

        assertThatThrownBy(() -> service.runDebate(
                "trace-timeout",
                1L,
                AnalysisState.builder().query("q").build(),
                2,
                1,
                null
        ))
                .isInstanceOf(ResearchDebateService.ModelStageTimeoutException.class)
                .hasMessageContaining("stage=manager-synthesis")
                .hasMessageContaining("timeoutMs=50");

        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(2));
        assertThat(managerCancelled).isTrue();
        verify(bullResearcher, never()).argue(any(), anyInt());
        verify(bearResearcher, never()).argue(any(), anyInt());
    }
}
