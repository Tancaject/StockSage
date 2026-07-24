package com.stocksage.service;

import com.stocksage.agent.Coordinator;
import com.stocksage.agent.ExecutionPlan;
import com.stocksage.agent.ModelTier;
import com.stocksage.agent.IntentDecision;
import com.stocksage.agent.IntentPlanAssembler;
import com.stocksage.agent.IntentRecognitionService;
import com.stocksage.agent.IntentType;
import com.stocksage.agent.PlanAction;
import com.stocksage.agent.PlanRoute;
import com.stocksage.model.dto.PlannerEvalCase;
import com.stocksage.model.dto.PlannerEvalMode;
import com.stocksage.model.dto.PlannerEvalRequest;
import com.stocksage.model.dto.PlannerEvalResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PlannerEvalServiceTest {

    private final Coordinator coordinator = mock(Coordinator.class);
    private final IntentRecognitionService intentRecognitionService = mock(IntentRecognitionService.class);
    private final PlannerEvalService service =
            new PlannerEvalService(coordinator, intentRecognitionService, new IntentPlanAssembler());

    @Test
    void deterministicModeUsesTypedRouteAndActionAssertions() {
        when(coordinator.planDeterministically("NVDA price", 0)).thenReturn(new ExecutionPlan(
                PlanRoute.MARKET,
                "market",
                "plan",
                List.of(PlanAction.MARKET_AGENT, PlanAction.GET_STOCK_KLINE, PlanAction.FINAL_ANSWER),
                "done",
                ModelTier.STANDARD
        ));
        PlannerEvalCase evalCase = new PlannerEvalCase(
                "market-1", "NVDA price", 0, PlanRoute.MARKET,
                List.of(PlanAction.MARKET_AGENT, PlanAction.GET_STOCK_KLINE),
                List.of(PlanAction.BULL_RESEARCHER),
                true
        );

        PlannerEvalResponse response = service.evaluate(
                new PlannerEvalRequest(PlannerEvalMode.DETERMINISTIC, List.of(evalCase))
        );

        assertThat(response.schemaVersion()).isEqualTo("planner_eval_v1");
        assertThat(response.status()).isEqualTo("passed");
        assertThat(response.routeAccuracy()).isEqualTo(1.0);
        assertThat(response.requiredActionRecall()).isEqualTo(1.0);
        assertThat(response.forbiddenActionRate()).isZero();
        assertThat(response.executableRate()).isEqualTo(1.0);
        assertThat(response.results().get(0).passed()).isTrue();
        verify(coordinator).planDeterministically("NVDA price", 0);
    }

    @Test
    void reportsMissingAndForbiddenActionsWithoutComparingLabels() {
        when(coordinator.planDeterministically("bad plan", 0)).thenReturn(new ExecutionPlan(
                PlanRoute.DIRECT,
                "任意展示文案",
                "plan",
                List.of(PlanAction.BULL_RESEARCHER, PlanAction.FINAL_ANSWER),
                "done",
                ModelTier.FAST
        ));
        PlannerEvalCase evalCase = new PlannerEvalCase(
                "direct-1", "bad plan", 0, PlanRoute.DIRECT,
                List.of(PlanAction.KNOWLEDGE_RETRIEVAL),
                List.of(PlanAction.BULL_RESEARCHER),
                true
        );

        PlannerEvalResponse response = service.evaluate(
                new PlannerEvalRequest(PlannerEvalMode.DETERMINISTIC, List.of(evalCase))
        );

        assertThat(response.status()).isEqualTo("failed");
        assertThat(response.criticalFailures()).isEqualTo(1);
        assertThat(response.results().get(0).missingRequiredActions())
                .containsExactly(PlanAction.KNOWLEDGE_RETRIEVAL);
        assertThat(response.results().get(0).matchedForbiddenActions())
                .containsExactly(PlanAction.BULL_RESEARCHER);
    }

    @Test
    void intentShadowEvaluatesTheIndependentRecognizer() {
        PlannerEvalCase evalCase = new PlannerEvalCase(
                "shadow-1", "latest news", 0, PlanRoute.NEWS,
                List.of(PlanAction.NEWS_AGENT), List.of(), false
        );
        when(intentRecognitionService.recognize(any())).thenReturn(new IntentDecision(
                IntentType.NEWS_EVENT, List.of(), Map.of(), "latest",
                true, false, false, PlanRoute.NEWS, "fresh news", 0.9
        ));

        PlannerEvalResponse response = service.evaluate(
                new PlannerEvalRequest(PlannerEvalMode.INTENT_SHADOW, List.of(evalCase))
        );

        assertThat(response.status()).isEqualTo("passed");
        assertThat(response.executableRate()).isEqualTo(1.0);
        assertThat(response.results().get(0).actualRoute()).isEqualTo(PlanRoute.NEWS);
        verifyNoInteractions(coordinator);
    }
}
