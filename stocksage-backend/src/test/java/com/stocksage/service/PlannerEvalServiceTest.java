package com.stocksage.service;

import com.stocksage.agent.Coordinator;
import com.stocksage.agent.ExecutionPlan;
import com.stocksage.agent.ModelTier;
import com.stocksage.agent.PlanAction;
import com.stocksage.agent.PlanRoute;
import com.stocksage.agent.RoutingDecisionMetadata;
import com.stocksage.agent.RoutingDecisionSource;
import com.stocksage.agent.intent.FineIntent;
import com.stocksage.agent.intent.IntentSignalSource;
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

        assertThat(response.schemaVersion()).isEqualTo("planner_eval_v2");
        assertThat(response.status()).isEqualTo("passed");
        assertThat(response.routeAccuracy()).isEqualTo(1.0);
        assertThat(response.macroF1()).isEqualTo(1.0);
        assertThat(response.perRoute().get("MARKET").precision()).isEqualTo(1.0);
        assertThat(response.perRoute().get("MARKET").recall()).isEqualTo(1.0);
        assertThat(response.requiredActionRecall()).isEqualTo(1.0);
        assertThat(response.forbiddenActionRate()).isZero();
        assertThat(response.executableRate()).isEqualTo(1.0);
        assertThat(response.results().get(0).passed()).isTrue();
        assertThat(response.results().get(0).expectedClarification()).isNull();
        assertThat(response.results().get(0).actualClarification()).isNull();
        assertThat(response.results().get(0).clarificationMatched()).isNull();
        assertThat(response.results().get(0).signalDiagnostics()).isEmpty();
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
        when(coordinator.plan("latest news", 0, List.of())).thenReturn(new ExecutionPlan(
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
        verify(coordinator).plan("latest news", 0, List.of());
    }

    @Test
    void liveModeStrictlyChecksContextIntentSourceAndNoFallback() {
        List<String> recentTurns = List.of(
                "user: 先看看英伟达最近一季财报",
                "assistant: 已为你总结 NVDA 最近一季财报"
        );
        when(coordinator.plan("那它今天有什么新消息？", 0, recentTurns)).thenReturn(new ExecutionPlan(
                PlanRoute.NEWS,
                "news",
                "plan",
                List.of(PlanAction.NEWS_AGENT, PlanAction.SEARCH_NEWS, PlanAction.FINAL_ANSWER),
                "done",
                ModelTier.STANDARD,
                routingMetadata(RoutingDecisionSource.INTENT_FUSION, "NEWS", PlanRoute.NEWS, "NEWS_EVENT", ""),
                "NVDA 那它今天有什么新消息？"
        ));
        PlannerEvalCase evalCase = new PlannerEvalCase(
                "context-news-1", "那它今天有什么新消息？", 0, PlanRoute.NEWS,
                List.of(PlanAction.NEWS_AGENT), List.of(PlanAction.RESEARCH_MANAGER), true,
                recentTurns, "NEWS_EVENT", "INTENT_FUSION", true, "NVDA"
        );

        PlannerEvalResponse response = service.evaluate(
                new PlannerEvalRequest(PlannerEvalMode.LIVE_COORDINATOR, List.of(evalCase))
        );

        assertThat(response.status()).isEqualTo("passed");
        assertThat(response.intentEvaluatedCases()).isEqualTo(1);
        assertThat(response.intentAccuracy()).isEqualTo(1.0);
        assertThat(response.contextCases()).isEqualTo(1);
        assertThat(response.contextCaseAccuracy()).isEqualTo(1.0);
        assertThat(response.contextResolutionCases()).isEqualTo(1);
        assertThat(response.contextResolutionAccuracy()).isEqualTo(1.0);
        assertThat(response.nonFallbackRouteAccuracy()).isEqualTo(1.0);
        assertThat(response.llmSignalAccuracy()).isEqualTo(1.0);
        assertThat(response.fallbackRate()).isZero();
        assertThat(response.invalidRawRouteRate()).isZero();
        assertThat(response.results().get(0).fineIntentMatched()).isTrue();
        assertThat(response.results().get(0).decisionSourceMatched()).isTrue();
        assertThat(response.results().get(0).noFallbackMatched()).isTrue();
        assertThat(response.results().get(0).actualResolvedQuery()).contains("NVDA");
        assertThat(response.results().get(0).contextResolutionMatched()).isTrue();
    }

    @Test
    void contextCaseFailsWhenResolvedQueryDoesNotCarryTheHistoryTicker() {
        List<String> recentTurns = List.of("user: 关注 NVDA", "assistant: 已记录 NVDA");
        when(coordinator.plan("它今天多少钱？", 0, recentTurns)).thenReturn(new ExecutionPlan(
                PlanRoute.MARKET,
                "market",
                "plan",
                List.of(PlanAction.MARKET_AGENT, PlanAction.FINAL_ANSWER),
                "done",
                ModelTier.STANDARD,
                routingMetadata(RoutingDecisionSource.INTENT_FUSION, "MARKET", PlanRoute.MARKET, "MARKET_DATA", ""),
                "它今天多少钱？"
        ));
        PlannerEvalCase evalCase = new PlannerEvalCase(
                "context-resolution-missing", "它今天多少钱？", 0, PlanRoute.MARKET,
                List.of(PlanAction.MARKET_AGENT), List.of(), true,
                recentTurns, "MARKET_DATA", "INTENT_FUSION", true, "NVDA"
        );

        PlannerEvalResponse response = service.evaluate(
                new PlannerEvalRequest(PlannerEvalMode.LIVE_COORDINATOR, List.of(evalCase))
        );

        assertThat(response.routeAccuracy()).isEqualTo(1.0);
        assertThat(response.contextCaseAccuracy()).isEqualTo(1.0);
        assertThat(response.contextResolutionAccuracy()).isZero();
        assertThat(response.status()).isEqualTo("failed");
        assertThat(response.results().get(0).contextResolutionMatched()).isFalse();
    }

    @Test
    void correctFallbackRouteCannotPassANoFallbackCase() {
        when(coordinator.plan("它今天多少钱？", 0, List.of("user: 关注 NVDA"))).thenReturn(new ExecutionPlan(
                PlanRoute.MARKET,
                "market",
                "plan",
                List.of(PlanAction.MARKET_AGENT, PlanAction.FINAL_ANSWER),
                "done",
                ModelTier.STANDARD,
                routingMetadata(
                        RoutingDecisionSource.DETERMINISTIC_FALLBACK,
                        "MARKET",
                        PlanRoute.MARKET,
                        "MARKET_DATA",
                        "ROUTING_LLM_FAILED"
                )
        ));
        PlannerEvalCase evalCase = new PlannerEvalCase(
                "context-market-fallback", "它今天多少钱？", 0, PlanRoute.MARKET,
                List.of(PlanAction.MARKET_AGENT), List.of(), true,
                List.of("user: 关注 NVDA"), "MARKET_DATA", null, true
        );

        PlannerEvalResponse response = service.evaluate(
                new PlannerEvalRequest(PlannerEvalMode.LIVE_COORDINATOR, List.of(evalCase))
        );

        assertThat(response.routeAccuracy()).isEqualTo(1.0);
        assertThat(response.status()).isEqualTo("failed");
        assertThat(response.fallbackRate()).isEqualTo(1.0);
        assertThat(response.nonFallbackCases()).isZero();
        assertThat(response.results().get(0).noFallbackMatched()).isFalse();
    }

    @Test
    void separatesFinalFusionAccuracyFromRawLlmSignalAccuracyAndValidity() {
        when(coordinator.plan("结合上下文查最新消息", 0, List.of())).thenReturn(new ExecutionPlan(
                PlanRoute.NEWS,
                "news",
                "plan",
                List.of(PlanAction.NEWS_AGENT, PlanAction.FINAL_ANSWER),
                "done",
                ModelTier.STANDARD,
                routingMetadata(RoutingDecisionSource.INTENT_FUSION, "MARKET", PlanRoute.NEWS, "NEWS_EVENT", "")
        ));
        when(coordinator.plan("另一个最新消息", 0, List.of())).thenReturn(new ExecutionPlan(
                PlanRoute.NEWS,
                "news",
                "plan",
                List.of(PlanAction.NEWS_AGENT, PlanAction.FINAL_ANSWER),
                "done",
                ModelTier.STANDARD,
                routingMetadata(RoutingDecisionSource.INTENT_FUSION, "NOT_A_ROUTE", PlanRoute.NEWS, "NEWS_EVENT", "")
        ));
        PlannerEvalCase wrongRaw = new PlannerEvalCase(
                "raw-wrong", "结合上下文查最新消息", 0, PlanRoute.NEWS,
                List.of(PlanAction.NEWS_AGENT), List.of(), false
        );
        PlannerEvalCase invalidRaw = new PlannerEvalCase(
                "raw-invalid", "另一个最新消息", 0, PlanRoute.NEWS,
                List.of(PlanAction.NEWS_AGENT), List.of(), false
        );

        PlannerEvalResponse response = service.evaluate(
                new PlannerEvalRequest(PlannerEvalMode.LIVE_COORDINATOR, List.of(wrongRaw, invalidRaw))
        );

        assertThat(response.routeAccuracy()).isEqualTo(1.0);
        assertThat(response.nonFallbackRouteAccuracy()).isEqualTo(1.0);
        assertThat(response.llmSignalCases()).isEqualTo(1);
        assertThat(response.llmSignalAccuracy()).isZero();
        assertThat(response.invalidRawRouteCases()).isEqualTo(1);
        assertThat(response.invalidRawRouteRate()).isEqualTo(0.5);
    }

    @Test
    void executionGuardDoesNotCountSemanticRawRouteAgainstExecutionRoute() {
        RoutingDecisionMetadata guarded = new RoutingDecisionMetadata(
                RoutingDecisionSource.INTENT_FUSION,
                "DEEP",
                PlanRoute.DIRECT,
                "标的比较",
                "一次只支持一个标的",
                0.95,
                List.of("comparison"),
                0,
                "",
                3,
                "COMPARISON",
                "RESEARCH",
                "CURRENT",
                "DEEP",
                java.util.Map.of(),
                java.util.Map.of("LLM", 0.95),
                true,
                List.of(Coordinator.MULTI_TARGET_UNSUPPORTED)
        );
        when(coordinator.plan("Compare AAPL and MSFT", 0, List.of())).thenReturn(new ExecutionPlan(
                PlanRoute.DIRECT, "clarify", "plan", List.of(PlanAction.FINAL_ANSWER),
                "done", ModelTier.FAST, guarded
        ));

        PlannerEvalResponse response = service.evaluate(new PlannerEvalRequest(
                PlannerEvalMode.LIVE_COORDINATOR,
                List.of(new PlannerEvalCase(
                        "guarded", "Compare AAPL and MSFT", 0, PlanRoute.DIRECT,
                        List.of(PlanAction.FINAL_ANSWER), List.of(PlanAction.MARKET_AGENT), true))
        ));

        assertThat(response.routeAccuracy()).isEqualTo(1.0);
        assertThat(response.llmSignalCases()).isZero();
        assertThat(response.results().get(0).executionGuarded()).isTrue();
    }

    @Test
    void clarificationLabelsAffectPassAndExposeObservedSignalsWithoutGuessingAnUnlabeledMatch() {
        var signal = new RoutingDecisionMetadata.SignalSnapshot(
                IntentSignalSource.LLM, PlanRoute.NEWS, FineIntent.NEWS_EVENT, 0.4);
        var metadata = clarificationMetadata(List.of("FUSION_LOW_CONFIDENCE"), List.of(signal));
        for (String query : List.of("labeled-match", "labeled-mismatch", "unlabeled")) {
            when(coordinator.plan(query, 0, List.of())).thenReturn(new ExecutionPlan(
                    PlanRoute.DIRECT, "clarify", "plan", List.of(PlanAction.FINAL_ANSWER),
                    "done", ModelTier.FAST, metadata));
        }

        var response = service.evaluate(new PlannerEvalRequest(PlannerEvalMode.LIVE_COORDINATOR, List.of(
                clarificationCase("labeled-match", true), clarificationCase("labeled-mismatch", false),
                clarificationCase("unlabeled", null))));

        var matched = response.results().get(0);
        assertThat(matched.expectedClarification()).isTrue();
        assertThat(matched.actualClarification()).isTrue();
        assertThat(matched.clarificationMatched()).isTrue();
        assertThat(matched.passed()).isTrue();
        assertThat(matched.signalDiagnostics()).containsExactly(signal);
        assertThat(matched.reasonCodes()).containsExactly("FUSION_LOW_CONFIDENCE");
        assertThat(response.results().get(1).clarificationMatched()).isFalse();
        assertThat(response.results().get(1).passed()).isFalse();
        assertThat(response.results().get(2).actualClarification()).isTrue();
        assertThat(response.results().get(2).clarificationMatched()).isNull();
        assertThat(response.results().get(2).passed()).isTrue();
    }

    @Test
    void missingRoutingAndPlannerFailureLeaveClarificationUnknownAndCannotPassALabel() {
        when(coordinator.plan("missing-routing", 0, List.of())).thenReturn(new ExecutionPlan(
                PlanRoute.DIRECT, "direct", "plan", List.of(PlanAction.FINAL_ANSWER), "done", ModelTier.FAST));
        when(coordinator.plan("failed-planner", 0, List.of())).thenThrow(new IllegalStateException("failed"));

        var response = service.evaluate(new PlannerEvalRequest(PlannerEvalMode.LIVE_COORDINATOR, List.of(
                clarificationCase("missing-routing", false), clarificationCase("failed-planner", false))));

        assertThat(response.results()).allSatisfy(result -> {
            assertThat(result.expectedClarification()).isFalse();
            assertThat(result.actualClarification()).isNull();
            assertThat(result.clarificationMatched()).isNull();
            assertThat(result.passed()).isFalse();
            assertThat(result.signalDiagnostics()).isEmpty();
            assertThat(result.reasonCodes()).isEmpty();
        });
        assertThat(response.results().get(1).errorCode()).isEqualTo("PLANNER_EXECUTION_FAILED");
    }

    @Test
    void targetRewriteGuardAlsoExcludesTheRawSemanticRouteFromExecutionAccuracy() {
        var metadata = clarificationMetadata(List.of(Coordinator.TARGET_REWRITE_MISMATCH), List.of());
        when(coordinator.plan("rewrite-guard", 0, List.of())).thenReturn(new ExecutionPlan(
                PlanRoute.DIRECT, "clarify", "plan", List.of(PlanAction.FINAL_ANSWER), "done", ModelTier.FAST,
                metadata));

        var response = service.evaluate(new PlannerEvalRequest(PlannerEvalMode.LIVE_COORDINATOR,
                List.of(clarificationCase("rewrite-guard", true))));

        assertThat(response.results().get(0).executionGuarded()).isTrue();
        assertThat(response.results().get(0).reasonCodes()).containsExactly(Coordinator.TARGET_REWRITE_MISMATCH);
        assertThat(response.results().get(0).passed()).isTrue();
        assertThat(response.llmSignalCases()).isZero();
    }

    private PlannerEvalCase clarificationCase(String query, Boolean expectedClarification) {
        return new PlannerEvalCase(query, query, 0, PlanRoute.DIRECT, List.of(PlanAction.FINAL_ANSWER), List.of(),
                true, List.of(), null, null, false, null, expectedClarification);
    }

    private RoutingDecisionMetadata clarificationMetadata(List<String> reasons,
                                                          List<RoutingDecisionMetadata.SignalSnapshot> signals) {
        return new RoutingDecisionMetadata(RoutingDecisionSource.INTENT_FUSION, "NEWS", PlanRoute.DIRECT,
                "clarify", "bounded", 0.4, List.of(), 0, "", 1,
                "NEWS_EVENT", "NEWS", "RECENT", "STANDARD", java.util.Map.of(), java.util.Map.of(),
                true, reasons, signals);
    }

    @Test
    void deterministicModeSkipsLiveOnlyCasesAndReportsTheirCount() {
        when(coordinator.planDeterministically("what is PE", 0)).thenReturn(new ExecutionPlan(
                PlanRoute.DIRECT,
                "direct",
                "plan",
                List.of(PlanAction.KNOWLEDGE_RETRIEVAL, PlanAction.FINAL_ANSWER),
                "done",
                ModelTier.FAST
        ));
        PlannerEvalCase legacyCase = new PlannerEvalCase(
                "direct-legacy", "what is PE", 0, PlanRoute.DIRECT,
                List.of(PlanAction.KNOWLEDGE_RETRIEVAL), List.of(), false
        );
        PlannerEvalCase liveOnlyCase = new PlannerEvalCase(
                "market-context", "它今天多少钱？", 0, PlanRoute.MARKET,
                List.of(PlanAction.MARKET_AGENT), List.of(), false,
                List.of("user: 关注 NVDA"), "MARKET_DATA", null, false
        );

        PlannerEvalResponse response = service.evaluate(
                new PlannerEvalRequest(PlannerEvalMode.DETERMINISTIC, List.of(legacyCase, liveOnlyCase))
        );

        assertThat(response.requestedCases()).isEqualTo(2);
        assertThat(response.totalCases()).isEqualTo(1);
        assertThat(response.skippedLiveOnlyCases()).isEqualTo(1);
        assertThat(response.status()).isEqualTo("passed");
        verify(coordinator).planDeterministically("what is PE", 0);
    }

    private RoutingDecisionMetadata routingMetadata(
            RoutingDecisionSource source,
            String rawRoute,
            PlanRoute route,
            String fineIntent,
            String fallbackReason
    ) {
        return new RoutingDecisionMetadata(
                source,
                rawRoute,
                route,
                "bounded intent",
                "bounded rationale",
                0.92,
                List.of("test"),
                0,
                fallbackReason,
                4,
                fineIntent,
                route.name(),
                "CURRENT",
                "STANDARD",
                java.util.Map.of(),
                java.util.Map.of("llm", 0.92),
                false,
                List.of("TEST")
        );
    }
}
