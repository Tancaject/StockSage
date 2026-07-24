package com.stocksage.agent;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class IntentPlanAssembler {

    public ExecutionPlan assemble(IntentDecision decision, int ragHitCount, long durationMs) {
        PlanRoute route = decision.suggestedRoute();
        List<PlanAction> actions = switch (route) {
            case MARKET -> List.of(PlanAction.MARKET_AGENT, PlanAction.SEARCH_STOCKS,
                    PlanAction.GET_STOCK_KLINE, PlanAction.GET_FINANCIAL_METRICS,
                    PlanAction.GET_TECHNICAL_INDICATORS, PlanAction.FINAL_ANSWER);
            case FUNDAMENTALS -> List.of(PlanAction.FUNDAMENTALS_AGENT, PlanAction.SEARCH_STOCKS,
                    PlanAction.GET_FINANCIAL_REPORTS, PlanAction.SEARCH_COMPANY_REPORTS,
                    PlanAction.KNOWLEDGE_RETRIEVAL, PlanAction.FINAL_ANSWER);
            case NEWS -> List.of(PlanAction.NEWS_AGENT, PlanAction.SEARCH_STOCKS,
                    PlanAction.SEARCH_NEWS, PlanAction.WEB_SEARCH,
                    PlanAction.GET_MARKET_OVERVIEW, PlanAction.FINAL_ANSWER);
            case DEEP -> List.of(PlanAction.FUNDAMENTALS_AGENT, PlanAction.MARKET_AGENT,
                    PlanAction.NEWS_AGENT, PlanAction.BULL_RESEARCHER, PlanAction.BEAR_RESEARCHER,
                    PlanAction.RESEARCH_MANAGER, PlanAction.FINAL_ANSWER);
            case DIRECT -> List.of(PlanAction.KNOWLEDGE_RETRIEVAL, PlanAction.FINAL_ANSWER);
        };
        ModelTier tier = switch (route) {
            case DEEP -> ModelTier.STRONG;
            case DIRECT -> ModelTier.FAST;
            default -> ModelTier.STANDARD;
        };
        RoutingDecisionMetadata metadata = new RoutingDecisionMetadata(
                RoutingDecisionSource.INTENT_LLM,
                decision.primaryIntent().name(),
                decision.secondaryIntents().stream().map(Enum::name).toList(),
                route,
                List.of("validated-intent"),
                ragHitCount,
                "",
                durationMs
        );
        return new ExecutionPlan(
                route,
                decision.primaryIntent().name(),
                "Validated intent decision selected " + route + ".",
                new ArrayList<>(actions),
                "Intent route=" + route + "; actions="
                        + String.join(" -> ", actions.stream().map(PlanAction::label).toList()),
                tier,
                metadata
        );
    }
}
