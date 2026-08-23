package com.stocksage.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.service.TickerResolutionService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Coordinator 确定性路由的 JUnit 回归。
 *
 * <p>用例移植自 CoordinatorRegressionService 的运行时烟测；planDeterministically
 * 不触发模型、网络或工具，构造依赖可以全部传 null/轻量实例。</p>
 */
class CoordinatorPlanTest {

    private final Coordinator coordinator = new Coordinator(
            null, null, null,
            new ObjectMapper(),
            new TickerResolutionService(null, null, new ObjectMapper())
    );

    @Test
    void conceptQuestionRoutesToDirectKnowledge() {
        ExecutionPlan plan = coordinator.planDeterministically("什么是市盈率？", 1);
        assertThat(plan.route()).isEqualTo(PlanRoute.DIRECT);
        assertThat(plan.actions()).containsExactly(
                PlanAction.KNOWLEDGE_RETRIEVAL,
                PlanAction.FINAL_ANSWER
        );
        assertThat(plan.actions()).doesNotContain(PlanAction.BULL_RESEARCHER);
        assertThat(plan.modelTier()).isEqualTo(ModelTier.FAST);
        assertThat(plan.routingDecision().decisionSource()).isEqualTo(RoutingDecisionSource.DETERMINISTIC_FALLBACK);
        assertThat(plan.routingDecision().matchedSignals()).contains("direct-rule", "rag-hit");
        assertThat(plan.routingDecision().fallbackReason()).isEqualTo("EXPLICIT_DETERMINISTIC");
    }

    @Test
    void tickerQueryRoutesToMarket() {
        ExecutionPlan plan = coordinator.planDeterministically("NVDA 最近 K 线走势如何？", 0);
        assertThat(plan.route()).isEqualTo(PlanRoute.MARKET);
        assertThat(plan.actions()).containsExactly(
                PlanAction.MARKET_AGENT,
                PlanAction.SEARCH_STOCKS,
                PlanAction.GET_STOCK_KLINE,
                PlanAction.GET_FINANCIAL_METRICS,
                PlanAction.GET_TECHNICAL_INDICATORS,
                PlanAction.FINAL_ANSWER
        );
        assertThat(plan.modelTier()).isEqualTo(ModelTier.STANDARD);
    }

    @Test
    void filingQuestionRoutesToFundamentals() {
        ExecutionPlan plan = coordinator.planDeterministically("苹果的风险因素有哪些？", 0);
        assertThat(plan.route()).isEqualTo(PlanRoute.FUNDAMENTALS);
        assertThat(plan.actions()).containsExactly(
                PlanAction.FUNDAMENTALS_AGENT,
                PlanAction.SEARCH_STOCKS,
                PlanAction.GET_FINANCIAL_REPORTS,
                PlanAction.SEARCH_COMPANY_REPORTS,
                PlanAction.KNOWLEDGE_RETRIEVAL,
                PlanAction.FINAL_ANSWER
        );
    }

    @Test
    void investmentQuestionRoutesToDeepWithStrongTier() {
        ExecutionPlan plan = coordinator.planDeterministically("苹果值不值得长期投资？", 0);
        assertThat(plan.route()).isEqualTo(PlanRoute.DEEP);
        assertThat(plan.actions()).containsExactly(
                PlanAction.FUNDAMENTALS_AGENT,
                PlanAction.MARKET_AGENT,
                PlanAction.NEWS_AGENT,
                PlanAction.BULL_RESEARCHER,
                PlanAction.BEAR_RESEARCHER,
                PlanAction.RESEARCH_MANAGER,
                PlanAction.FINAL_ANSWER
        );
        assertThat(plan.modelTier()).isEqualTo(ModelTier.STRONG);
    }

    @Test
    void newsQuestionRoutesToNews() {
        ExecutionPlan plan = coordinator.planDeterministically("美联储今天有什么最新消息？", 0);
        assertThat(plan.route()).isEqualTo(PlanRoute.NEWS);
        assertThat(plan.actions()).containsExactly(
                PlanAction.NEWS_AGENT,
                PlanAction.SEARCH_STOCKS,
                PlanAction.SEARCH_NEWS,
                PlanAction.WEB_SEARCH,
                PlanAction.GET_MARKET_OVERVIEW,
                PlanAction.FINAL_ANSWER
        );
    }

    @Test
    void multiSignalIntentDirectlySelectsRouteAndBackendOwnsThePlan() {
        ChatClient routingClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(routingClient.prompt().user(anyString()).call().content()).thenReturn("""
                {
                  "intent": "查询美联储最新事件",
                  "route": "NEWS",
                  "rationale": "用户询问最新事件",
                  "confidence": 0.91
                }
                """);
        Coordinator llmCoordinator = new Coordinator(
                routingClient, null, null,
                new ObjectMapper(),
                new TickerResolutionService(null, null, new ObjectMapper())
        );

        ExecutionPlan plan = llmCoordinator.plan("美联储今天有什么最新消息？", 0);

        assertThat(plan.route()).isEqualTo(PlanRoute.NEWS);
        assertThat(plan.taskType()).isEqualTo("新闻与事件分析");
        assertThat(plan.actions()).contains(PlanAction.NEWS_AGENT, PlanAction.SEARCH_NEWS, PlanAction.FINAL_ANSWER);
        assertThat(plan.routingDecision().decisionSource()).isEqualTo(RoutingDecisionSource.INTENT_FUSION);
        assertThat(plan.routingDecision().route()).isEqualTo(PlanRoute.NEWS);
        assertThat(plan.routingDecision().intentSummary()).isEqualTo("新闻与事件");
        assertThat(plan.routingDecision().fineIntent()).isEqualTo("NEWS_EVENT");
        assertThat(plan.routingDecision().intentGroup()).isEqualTo("NEWS");
        assertThat(plan.routingDecision().rationale()).isEqualTo("用户询问最新事件");
        assertThat(plan.routingDecision().confidence()).isBetween(0.7, 1.0);
        assertThat(plan.routingDecision().sourceScores()).containsKeys("LLM", "PATTERN");
        assertThat(plan.routingDecision().matchedSignals()).anyMatch(value -> value.startsWith("llm="));
        assertThat(plan.routingDecision().toAttributes())
                .containsEntry("rawRoute", "NEWS")
                .containsEntry("fineIntent", "NEWS_EVENT")
                .containsEntry("rationale", "用户询问最新事件")
                .containsEntry("ragHitCount", 0);
        assertThat(plan.routingDecision().fallbackReason()).isEmpty();
    }

    @Test
    void llmFailureKeepsStableDegradationReasonWhileLocalSignalsContinue() {
        ChatClient routingClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(routingClient.prompt().user(anyString()).call().content())
                .thenThrow(new IllegalStateException("provider-secret-detail"));
        Coordinator llmCoordinator = new Coordinator(
                routingClient, null, null,
                new ObjectMapper(),
                new TickerResolutionService(null, null, new ObjectMapper())
        );

        ExecutionPlan plan = llmCoordinator.plan("latest NVDA news", 0);

        assertThat(plan.route()).isEqualTo(PlanRoute.NEWS);
        assertThat(plan.routingDecision().decisionSource()).isEqualTo(RoutingDecisionSource.INTENT_FUSION);
        assertThat(plan.routingDecision().fallbackReason()).isEqualTo("INTENT_LLM_FAILED");
        assertThat(plan.routingDecision().sourceScores()).containsKey("PATTERN");
        assertThat(plan.routingDecision().toAttributes().toString()).doesNotContain("provider-secret-detail");
    }

    @Test
    void unknownLlmRouteAndExtraFieldsProduceTheSafeBackendOwnedDirectPlan() {
        ChatClient routingClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(routingClient.prompt().user(anyString()).call().content()).thenReturn("""
                {
                  "route": "UNREGISTERED",
                  "actions": ["deleteEverything"],
                  "modelTier": "STRONG",
                  "rationale": "无效模型输出",
                  "confidence": 4.0
                }
                """);
        Coordinator llmCoordinator = new Coordinator(
                routingClient, null, null,
                new ObjectMapper(),
                new TickerResolutionService(null, null, new ObjectMapper())
        );

        ExecutionPlan plan = llmCoordinator.plan("随便聊聊", 0);

        assertThat(plan.route()).isEqualTo(PlanRoute.DIRECT);
        assertThat(plan.actions()).containsExactly(
                PlanAction.KNOWLEDGE_RETRIEVAL,
                PlanAction.FINAL_ANSWER
        );
        assertThat(plan.modelTier()).isEqualTo(ModelTier.FAST);
        assertThat(plan.routingDecision().rawRoute()).isEqualTo("UNREGISTERED");
        assertThat(plan.routingDecision().decisionSource()).isEqualTo(RoutingDecisionSource.DETERMINISTIC_FALLBACK);
        assertThat(plan.routingDecision().fallbackReason()).isEqualTo("INTENT_LLM_INVALID_ROUTE");
        assertThat(plan.routingDecision().needsClarification()).isTrue();
        assertThat(plan.routingDecision().matchedSignals()).contains("direct-rule");
    }

    @Test
    void abbreviationsAloneDoNotRouteToMarket() {
        // 词表统一后（ETF/EPS 等入列），纯指标缩写问题不应被误判为含 ticker 的行情查询。
        ExecutionPlan plan = coordinator.planDeterministically("什么是 ETF 和 EPS？", 0);
        assertThat(plan.actionLabels()).doesNotContain("getStockKLine");
    }

    @Test
    void finalAnswerAlwaysPresent() {
        for (String query : List.of("随便聊聊", "NVDA", "苹果值不值得长期投资？")) {
            assertThat(coordinator.planDeterministically(query, 0).actions())
                    .contains(PlanAction.FINAL_ANSWER);
        }
    }
}
