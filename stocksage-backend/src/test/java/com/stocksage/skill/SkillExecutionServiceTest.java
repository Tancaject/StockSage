package com.stocksage.skill;

import com.stocksage.agent.ExecutionPlan;
import com.stocksage.agent.ModelTier;
import com.stocksage.agent.PlanAction;
import com.stocksage.agent.PlanRoute;
import com.stocksage.capability.CapabilityDescriptor;
import com.stocksage.capability.CapabilityException;
import com.stocksage.capability.CapabilityGateway;
import com.stocksage.capability.CapabilityInvocationContext;
import com.stocksage.capability.CapabilityResult;
import com.stocksage.tool.ChatStreamEmitter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkillExecutionServiceTest {

    @Mock
    private SkillResolver resolver;
    @Mock
    private CapabilityGateway gateway;
    @Mock
    private ChatStreamEmitter emitter;
    @Mock
    private SkillExecutionObserver observer;

    private SkillExecutionService service;
    private SkillDefinition skill;
    private ExecutionPlan plan;

    @BeforeEach
    void setUp() {
        service = new SkillExecutionService(resolver, gateway, emitter, observer);
        skill = new SkillDefinition(
                "latest-news-mcp", 1, "news", true, Set.of(PlanRoute.NEWS),
                SkillDefinition.ExecutionMode.INLINE_DETERMINISTIC,
                ModelTier.STANDARD,
                new SkillDefinition.SkillPolicy(
                        Set.of(CapabilityDescriptor.RiskLevel.READ_ONLY,
                                CapabilityDescriptor.RiskLevel.EXTERNAL_READ),
                        3,
                        20
                ),
                List.of(new SkillDefinition.SkillStep(
                        SkillDefinition.StepType.CAPABILITY,
                        "mcp.news.search",
                        false,
                        "local.news.searchNews",
                        null,
                        Set.of()
                )),
                List.of("local-latest-news")
        );
        plan = new ExecutionPlan(
                PlanRoute.NEWS, "NEWS", "news",
                List.of(PlanAction.SEARCH_NEWS), "", ModelTier.STANDARD);
        when(resolver.resolve(plan)).thenReturn(Optional.of(skill));
    }

    @Test
    void usesMcpEvidenceWhenTheApprovedRemoteCapabilityIsAvailable() {
        when(gateway.invoke(eq("mcp.news.search"), anyMap(), any(CapabilityInvocationContext.class)))
                .thenReturn(new CapabilityResult(
                        "mcp.news.search", "news-mcp", CapabilityResult.Status.SUCCESS,
                        "remote evidence", 15, 20));

        SkillExecutionService.ExecutionResult result = execute();

        assertThat(result.handled(PlanAction.SEARCH_NEWS)).isTrue();
        assertThat(result.fallbackUsed()).isFalse();
        assertThat(result.context()).contains("remote evidence", "untrusted evidence");
        verify(observer).record(eq("latest-news-mcp"),
                eq(SkillExecutionObserver.Outcome.SUCCESS), anyLong());
    }

    @Test
    void fallsBackToTheExistingLocalNewsToolWhenMcpIsUnavailable() {
        when(gateway.invoke(eq("mcp.news.search"), anyMap(), any(CapabilityInvocationContext.class)))
                .thenThrow(new CapabilityException(CapabilityException.Reason.UNAVAILABLE, "down"));
        when(gateway.invoke(eq("local.news.searchNews"), anyMap(), any(CapabilityInvocationContext.class)))
                .thenReturn(new CapabilityResult(
                        "local.news.searchNews", "local", CapabilityResult.Status.SUCCESS,
                        "local evidence", 14, 8));

        SkillExecutionService.ExecutionResult result = execute();

        assertThat(result.handled(PlanAction.SEARCH_NEWS)).isTrue();
        assertThat(result.fallbackUsed()).isTrue();
        assertThat(result.context()).contains("local evidence", "Fallback: true");
        verify(emitter).emit("trace", 7L, "observation",
                "MCP 能力不可用，已降级到本地能力 local.news.searchNews");
        verify(observer).record(eq("latest-news-mcp"),
                eq(SkillExecutionObserver.Outcome.FALLBACK_SUCCESS), anyLong());
    }

    @Test
    void keepsTheLegacyNewsPathAvailableWhenBothOptionalCapabilitiesFail() {
        when(gateway.invoke(eq("mcp.news.search"), anyMap(), any(CapabilityInvocationContext.class)))
                .thenThrow(new CapabilityException(CapabilityException.Reason.UNAVAILABLE, "mcp down"));
        when(gateway.invoke(eq("local.news.searchNews"), anyMap(), any(CapabilityInvocationContext.class)))
                .thenThrow(new CapabilityException(CapabilityException.Reason.FAILED, "local down"));

        SkillExecutionService.ExecutionResult result = execute();

        assertThat(result.handled(PlanAction.SEARCH_NEWS)).isFalse();
        assertThat(result.context()).isBlank();
        verify(emitter).emit("trace", 7L, "observation",
                "本地降级能力暂不可用，继续使用原有 NEWS 执行路径");
        verify(observer).record(eq("latest-news-mcp"),
                eq(SkillExecutionObserver.Outcome.LEGACY_PATH), anyLong());
    }

    private SkillExecutionService.ExecutionResult execute() {
        return service.executePrefetch(plan, "NVDA 最新消息", 5, "trace", 7L, "user");
    }
}
