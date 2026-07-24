package com.stocksage.agent;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntentAwarePlannerTest {

    private final Coordinator coordinator = mock(Coordinator.class);
    private final IntentRecognitionService recognitionService = mock(IntentRecognitionService.class);
    private final IntentPlanAssembler assembler = new IntentPlanAssembler();
    private final IntentRecognitionRequest request =
            new IntentRecognitionRequest("latest NVDA news", List.of(), 0, false, List.of("NVDA"));

    @Test
    void legacyModeDoesNotCallIntentModel() {
        ExecutionPlan legacy = plan(PlanRoute.NEWS);
        when(coordinator.plan("latest NVDA news", 0)).thenReturn(legacy);
        IntentAwarePlanner planner = new IntentAwarePlanner(
                coordinator, recognitionService, assembler, new SimpleMeterRegistry(), "LEGACY");

        assertThat(planner.plan(request)).isSameAs(legacy);
        verify(recognitionService, never()).recognize(request);
    }

    @Test
    void shadowModeRecordsComparisonButKeepsLegacyExecutionPlan() {
        ExecutionPlan legacy = plan(PlanRoute.NEWS);
        when(coordinator.plan("latest NVDA news", 0)).thenReturn(legacy);
        when(recognitionService.recognize(request)).thenReturn(decision(PlanRoute.MARKET));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        IntentAwarePlanner planner = new IntentAwarePlanner(
                coordinator, recognitionService, assembler, registry, "SHADOW");

        ExecutionPlan result = planner.plan(request);

        assertThat(result.route()).isEqualTo(legacy.route());
        assertThat(result.actions()).isEqualTo(legacy.actions());
        assertThat(result.routingDecision().matchedSignals()).contains("intent-shadow-disagree");
        assertThat(registry.get("stocksage.intent.shadow.decisions")
                .tags("outcome", "disagree", "legacy_route", "news")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void activeModeUsesValidatedIntentAndFallsBackDeterministicallyOnFailure() {
        when(recognitionService.recognize(request))
                .thenReturn(decision(PlanRoute.NEWS), (IntentDecision) null);
        when(coordinator.planDeterministically("latest NVDA news", 0)).thenReturn(plan(PlanRoute.NEWS));
        IntentAwarePlanner planner = new IntentAwarePlanner(
                coordinator, recognitionService, assembler, new SimpleMeterRegistry(), "ACTIVE");

        ExecutionPlan active = planner.plan(request);
        ExecutionPlan fallback = planner.plan(request);

        assertThat(active.route()).isEqualTo(PlanRoute.NEWS);
        assertThat(active.routingDecision().decisionSource()).isEqualTo(RoutingDecisionSource.INTENT_LLM);
        assertThat(fallback.route()).isEqualTo(PlanRoute.NEWS);
        verify(coordinator).planDeterministically("latest NVDA news", 0);
    }

    private IntentDecision decision(PlanRoute route) {
        return new IntentDecision(
                IntentType.NEWS_EVENT, List.of(), Map.of("ticker", "NVDA"), "latest",
                true, false, false, route, "fresh news", 0.9
        );
    }

    private ExecutionPlan plan(PlanRoute route) {
        return new ExecutionPlan(
                route, route.name(), "legacy", List.of(PlanAction.FINAL_ANSWER),
                "done", ModelTier.STANDARD
        );
    }
}
