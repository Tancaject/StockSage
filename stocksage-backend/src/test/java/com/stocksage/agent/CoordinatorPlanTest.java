package com.stocksage.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.service.TickerResolutionService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(plan.actionLabels()).contains("Knowledge Retrieval", "Final Answer");
        assertThat(plan.actions()).doesNotContain(PlanAction.BULL_RESEARCHER);
        assertThat(plan.modelTier()).isEqualTo(ModelTier.FAST);
    }

    @Test
    void tickerQueryRoutesToMarket() {
        ExecutionPlan plan = coordinator.planDeterministically("NVDA 最近 K 线走势如何？", 0);
        assertThat(plan.actionLabels()).contains("Market Agent", "searchStocks", "getStockKLine", "Final Answer");
        assertThat(plan.modelTier()).isEqualTo(ModelTier.STANDARD);
    }

    @Test
    void filingQuestionRoutesToFundamentals() {
        ExecutionPlan plan = coordinator.planDeterministically("苹果的风险因素有哪些？", 0);
        assertThat(plan.actionLabels()).contains("Fundamentals Agent", "searchStocks",
                "getFinancialReports", "searchCompanyReports", "Knowledge Retrieval", "Final Answer");
    }

    @Test
    void investmentQuestionRoutesToDeepWithStrongTier() {
        ExecutionPlan plan = coordinator.planDeterministically("苹果值不值得长期投资？", 0);
        assertThat(plan.actionLabels()).containsAll(List.of(
                "Fundamentals Agent", "Market Agent", "News Agent",
                "Bull Researcher", "Bear Researcher", "Research Manager", "Final Answer"));
        assertThat(plan.modelTier()).isEqualTo(ModelTier.STRONG);
    }

    @Test
    void newsQuestionRoutesToNews() {
        ExecutionPlan plan = coordinator.planDeterministically("美联储今天有什么最新消息？", 0);
        assertThat(plan.actionLabels()).contains("News Agent", "searchStocks",
                "searchNews", "webSearch", "getMarketOverview", "Final Answer");
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
