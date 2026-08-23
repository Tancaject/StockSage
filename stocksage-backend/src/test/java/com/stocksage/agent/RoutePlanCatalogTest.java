package com.stocksage.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RoutePlanCatalogTest {

    private final RoutePlanCatalog catalog = new RoutePlanCatalog();

    @Test
    void preservesTheRegisteredActionOrderForEveryRoute() {
        Map<PlanRoute, java.util.List<PlanAction>> expected = Map.of(
                PlanRoute.DIRECT, java.util.List.of(
                        PlanAction.KNOWLEDGE_RETRIEVAL,
                        PlanAction.FINAL_ANSWER),
                PlanRoute.MARKET, java.util.List.of(
                        PlanAction.MARKET_AGENT,
                        PlanAction.SEARCH_STOCKS,
                        PlanAction.GET_STOCK_KLINE,
                        PlanAction.GET_FINANCIAL_METRICS,
                        PlanAction.GET_TECHNICAL_INDICATORS,
                        PlanAction.FINAL_ANSWER),
                PlanRoute.FUNDAMENTALS, java.util.List.of(
                        PlanAction.FUNDAMENTALS_AGENT,
                        PlanAction.SEARCH_STOCKS,
                        PlanAction.GET_FINANCIAL_REPORTS,
                        PlanAction.SEARCH_COMPANY_REPORTS,
                        PlanAction.KNOWLEDGE_RETRIEVAL,
                        PlanAction.FINAL_ANSWER),
                PlanRoute.NEWS, java.util.List.of(
                        PlanAction.NEWS_AGENT,
                        PlanAction.SEARCH_STOCKS,
                        PlanAction.SEARCH_NEWS,
                        PlanAction.WEB_SEARCH,
                        PlanAction.GET_MARKET_OVERVIEW,
                        PlanAction.FINAL_ANSWER),
                PlanRoute.DEEP, java.util.List.of(
                        PlanAction.FUNDAMENTALS_AGENT,
                        PlanAction.MARKET_AGENT,
                        PlanAction.NEWS_AGENT,
                        PlanAction.BULL_RESEARCHER,
                        PlanAction.BEAR_RESEARCHER,
                        PlanAction.RESEARCH_MANAGER,
                        PlanAction.FINAL_ANSWER)
        );

        expected.forEach((route, actions) -> assertThat(catalog.resolve(route, "", 0).actions())
                .as(route.name())
                .containsExactlyElementsOf(actions));
    }

    @Test
    void modelTierRemainsServerOwned() {
        assertThat(catalog.resolve(PlanRoute.DIRECT, "什么是市盈率", 0).modelTier())
                .isEqualTo(ModelTier.FAST);
        assertThat(catalog.resolve(PlanRoute.DIRECT, "请详细分析并比较风险与估值", 0).modelTier())
                .isEqualTo(ModelTier.STANDARD);
        assertThat(catalog.resolve(PlanRoute.DEEP, "", 0).modelTier())
                .isEqualTo(ModelTier.STRONG);
    }
}
