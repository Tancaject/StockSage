package com.stocksage.service;

import com.stocksage.agent.Coordinator;
import com.stocksage.agent.ExecutionPlan;
import com.stocksage.agent.ModelTier;
import com.stocksage.agent.PlanAction;
import com.stocksage.agent.PlanRoute;
import com.stocksage.agent.RoutingDecisionMetadata;
import com.stocksage.agent.RoutingDecisionSource;
import com.stocksage.model.dto.PlannerEvalCase;
import com.stocksage.model.dto.PlannerEvalMode;
import com.stocksage.model.dto.PlannerEvalRequest;
import com.stocksage.model.dto.PlannerEvalResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlannerEvalServiceTest {

    private final Coordinator coordinator = mock(Coordinator.class);
    private final PlannerEvalService service = new PlannerEvalService(coordinator);

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
        assertThat(response.macroF1()).isEqualTo(1.0);
        assertThat(response.perRoute().get("MARKET").precision()).isEqualTo(1.0);
        assertThat(response.perRoute().get("MARKET").recall()).isEqualTo(1.0);
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
    void liveModeEvaluatesTheSingleRoutingCoordinator() {
        when(coordinator.plan("latest news", 0)).thenReturn(new ExecutionPlan(
                PlanRoute.NEWS,
                "news",
                "plan",
                List.of(PlanAction.NEWS_AGENT, PlanAction.SEARCH_NEWS, PlanAction.FINAL_ANSWER),
                "done",
                ModelTier.STANDARD,
                new RoutingDecisionMetadata(
                        RoutingDecisionSource.ROUTING_LLM,
                        "NEWS",
                        PlanRoute.NEWS,
                        "查询最新新闻",
                        "需要新鲜事件信息",
                        0.94,
                        List.of("confidence-high"),
                        0,
                        "",
                        12
                )
        ));
        PlannerEvalCase evalCase = new PlannerEvalCase(
                "live-1", "latest news", 0, PlanRoute.NEWS,
                List.of(PlanAction.NEWS_AGENT), List.of(), false
        );

        PlannerEvalResponse response = service.evaluate(
                new PlannerEvalRequest(PlannerEvalMode.LIVE_COORDINATOR, List.of(evalCase))
        );

        assertThat(response.status()).isEqualTo("passed");
        assertThat(response.results().get(0).decisionSource()).isEqualTo("ROUTING_LLM");
        assertThat(response.results().get(0).intentSummary()).isEqualTo("查询最新新闻");
        assertThat(response.results().get(0).rationale()).isEqualTo("需要新鲜事件信息");
        assertThat(response.results().get(0).confidence()).isEqualTo(0.94);
        verify(coordinator).plan("latest news", 0);
    }
}
