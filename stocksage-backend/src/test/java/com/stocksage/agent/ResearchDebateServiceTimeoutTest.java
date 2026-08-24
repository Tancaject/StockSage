package com.stocksage.agent;

import com.stocksage.harness.DeepResearchCompletionPolicy;
import com.stocksage.harness.HarnessModels.SynthesisResult;
import com.stocksage.harness.ResearchHarness;
import com.stocksage.model.dto.AnalysisHorizon;
import com.stocksage.model.dto.AnalysisState;
import com.stocksage.model.dto.DebateModels.ArgumentAssessment;
import com.stocksage.model.dto.DebateModels.AssessmentParseStatus;
import com.stocksage.model.dto.DebateModels.AssessmentReasonCode;
import com.stocksage.model.dto.DebateModels.DebatePoint;
import com.stocksage.model.dto.DebateModels.DebateTurn;
import com.stocksage.model.dto.DebateModels.DebateVerdict;
import com.stocksage.model.dto.DebateModels.EvidenceRef;
import com.stocksage.model.dto.DebateModels.LeadingSide;
import com.stocksage.model.dto.DebateModels.ManagerAssessment;
import com.stocksage.model.dto.DebateModels.PointType;
import com.stocksage.model.dto.DebateModels.Side;
import com.stocksage.tool.ChatStreamEmitter;
import com.stocksage.trace.TraceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
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
    private final DebateContractParser debateContractParser = mock(DebateContractParser.class);
    private final DebateDecisionPolicy debateDecisionPolicy = mock(DebateDecisionPolicy.class);
    private final DebateRoundPlanner debateRoundPlanner = mock(DebateRoundPlanner.class);
    private final ResearchDebateService service = new ResearchDebateService(
            bullResearcher,
            bearResearcher,
            researchManager,
            debateContractParser,
            debateDecisionPolicy,
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
        verify(researchManager, never())
                .synthesizeStreamingResult(any(), any(), any(), any(), any());
    }

    @Test
    void stalledManagerSynthesisIsCancelledAndReleasesCaller() {
        AtomicBoolean managerCancelled = new AtomicBoolean();
        AnalysisState state = structuredRoundOneState();
        ManagerAssessment assessment = managerAssessment(state);
        DebateVerdict verdict = verdict(state, assessment);
        when(debateDecisionPolicy.computeInputHash(state)).thenReturn("fixture-input-0");
        when(researchManager.scoreDebate(any(), any(), any(Runnable.class)))
                .thenReturn(Mono.just(assessment));
        when(debateDecisionPolicy.decide(state, assessment)).thenReturn(verdict);
        when(researchManager.synthesizeStreamingResult(
                any(), any(), any(), any(), any()))
                .thenReturn(Mono.<SynthesisResult>never().doOnCancel(() -> managerCancelled.set(true)));

        long startedAt = System.nanoTime();

        assertThatThrownBy(() -> service.runDebate(
                "trace-timeout",
                1L,
                state,
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

    private AnalysisState structuredRoundOneState() {
        AnalysisState state = AnalysisState.builder()
                .query("q")
                .primaryTicker("AAPL")
                .dataSnapshotHash("fixture-snapshot")
                .build();
        state.getDebateTurns().add(thesisTurn(Side.BULL, "bull"));
        state.getDebateTurns().add(thesisTurn(Side.BEAR, "bear"));
        return state;
    }

    private DebateTurn thesisTurn(Side side, String prefix) {
        return new DebateTurn(1, side, java.util.stream.IntStream.rangeClosed(1, 3)
                .mapToObj(index -> new DebatePoint(
                        prefix + "-thesis-" + index,
                        PointType.THESIS,
                        prefix + " claim " + index,
                        AnalysisHorizon.MEDIUM_TERM,
                        List.of(new EvidenceRef(prefix + "-evidence-" + index,
                                prefix + " evidence excerpt " + index)),
                        prefix + " reasoning " + index,
                        prefix + " assumption " + index,
                        prefix + " invalidation " + index,
                        List.of()
                ))
                .toList());
    }

    private ManagerAssessment managerAssessment(AnalysisState state) {
        List<ArgumentAssessment> assessments = state.getDebateTurns().stream()
                .flatMap(turn -> turn.points().stream())
                .map(point -> new ArgumentAssessment(
                        point.pointId(), 3, 3, 3, 3, 3,
                        List.of(point.evidenceRefs().get(0).evidenceId()),
                        List.of(),
                        List.of(AssessmentReasonCode.SUPPORTED),
                        "fixture assessment"
                ))
                .toList();
        return new ManagerAssessment(
                com.stocksage.model.dto.DebateModels.MANAGER_ASSESSMENT_CONTRACT_ID,
                com.stocksage.model.dto.DebateModels.MANAGER_ASSESSMENT_CONTRACT_VERSION,
                "fixture-input-0",
                true,
                assessments,
                AssessmentParseStatus.VALID,
                List.of()
        );
    }

    private DebateVerdict verdict(
            AnalysisState state,
            ManagerAssessment assessment
    ) {
        return new DebateVerdict(
                DebateDecisionPolicy.POLICY_ID,
                DebateDecisionPolicy.POLICY_VERSION,
                assessment.inputHash(),
                state.getDataSnapshotHash(),
                72,
                72,
                LeadingSide.BALANCED,
                0,
                "HOLD",
                AnalysisHorizon.MEDIUM_TERM,
                List.of(),
                List.of(),
                assessment.assessments()
        );
    }
}
