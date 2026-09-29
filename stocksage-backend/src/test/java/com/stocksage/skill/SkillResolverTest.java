package com.stocksage.skill;

import com.stocksage.agent.ExecutionPlan;
import com.stocksage.agent.ModelTier;
import com.stocksage.agent.PlanAction;
import com.stocksage.agent.PlanRoute;
import com.stocksage.agent.Coordinator;
import com.stocksage.capability.CapabilityAdapter;
import com.stocksage.capability.CapabilityInvocationContext;
import com.stocksage.capability.CapabilityRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.service.TickerResolutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SkillResolverTest {

    private SkillResolver resolver;

    @BeforeEach
    void setUp() {
        CapabilityRegistry capabilities = new CapabilityRegistry(List.of(
                adapter("local.news.searchNews"),
                adapter("local.news.webSearch"),
                adapter("mcp.news.search")
        ));
        SkillRegistry registry = new SkillRegistry(new SkillValidator(capabilities));
        resolver = new SkillResolver(registry, "latest-news-mcp");
    }

    @Test
    void resolvesNewsSkillFromExplicitRouteWhenTaskTypeIsChinese() {
        ExecutionPlan plan = new ExecutionPlan(
                PlanRoute.NEWS,
                "新闻与事件分析",
                "识别为新闻意图",
                List.of(PlanAction.NEWS_AGENT, PlanAction.SEARCH_NEWS, PlanAction.FINAL_ANSWER),
                "",
                ModelTier.STANDARD
        );

        assertThat(resolver.resolve(plan))
                .get()
                .extracting(SkillDefinition::id)
                .isEqualTo("latest-news-mcp");
    }

    @Test
    void doesNotInferNewsRouteFromFreeFormTaskType() {
        ExecutionPlan plan = new ExecutionPlan(
                PlanRoute.DIRECT,
                "NEWS",
                "",
                List.of(PlanAction.KNOWLEDGE_RETRIEVAL, PlanAction.FINAL_ANSWER),
                "",
                ModelTier.STANDARD
        );

        assertThat(resolver.resolve(plan)).isEmpty();
    }

    @Test
    void resolvesNewsSkillForDeterministicCoordinatorFallbackPlan() {
        ObjectMapper objectMapper = new ObjectMapper();
        Coordinator coordinator = new Coordinator(
                null, null,
                objectMapper,
                new TickerResolutionService(null, null, objectMapper)
        );

        ExecutionPlan plan = coordinator.planDeterministically("美联储今天有什么最新消息？", 0);

        assertThat(plan.route()).isEqualTo(PlanRoute.NEWS);
        assertThat(resolver.resolve(plan))
                .get()
                .extracting(SkillDefinition::id)
                .isEqualTo("latest-news-mcp");
    }

    private CapabilityAdapter adapter(String id) {
        return new CapabilityAdapter() {
            @Override
            public String capabilityId() {
                return id;
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String invoke(Map<String, Object> arguments, CapabilityInvocationContext context) {
                return "";
            }
        };
    }
}
