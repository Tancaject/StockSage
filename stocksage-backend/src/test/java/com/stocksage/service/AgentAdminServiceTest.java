package com.stocksage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.agent.ModelTier;
import com.stocksage.agent.PlanRoute;
import com.stocksage.capability.CapabilityDescriptor;
import com.stocksage.capability.CapabilityRegistry;
import com.stocksage.mcp.McpCapabilityProvider;
import com.stocksage.mcp.McpProperties;
import com.stocksage.model.entity.AgentTrace;
import com.stocksage.repository.AgentTraceRepository;
import com.stocksage.skill.SkillDefinition;
import com.stocksage.skill.SkillRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentAdminServiceTest {

    @Test
    void exposesSanitizedSkillsAndNonProbingRuntimeSnapshot() throws Exception {
        SkillRegistry skills = mock(SkillRegistry.class);
        CapabilityRegistry capabilities = mock(CapabilityRegistry.class);
        McpCapabilityProvider mcp = mock(McpCapabilityProvider.class);
        AgentTraceRepository traceRepository = mock(AgentTraceRepository.class);
        McpProperties properties = new McpProperties();
        properties.setEnabled(false);
        when(skills.list()).thenReturn(List.of(new SkillDefinition(
                "latest-news-mcp", 1, "news", true, Set.of(PlanRoute.NEWS),
                SkillDefinition.ExecutionMode.INLINE_DETERMINISTIC,
                ModelTier.STANDARD,
                new SkillDefinition.SkillPolicy(
                        Set.of(CapabilityDescriptor.RiskLevel.EXTERNAL_READ), 3, 20),
                List.of(new SkillDefinition.SkillStep(
                        SkillDefinition.StepType.CAPABILITY,
                        "mcp.news.search", false, "local.news.searchNews")),
                List.of("local-latest-news")
        )));
        when(capabilities.descriptors()).thenReturn(List.of(new CapabilityDescriptor(
                "mcp.news.search",
                CapabilityDescriptor.ProviderType.MCP,
                "provider-internal",
                "native-internal",
                CapabilityDescriptor.RiskLevel.EXTERNAL_READ,
                8000,
                262144,
                true
        )));
        when(mcp.statusSnapshot()).thenReturn(new McpCapabilityProvider.Status(
                false, false, 0, Map.of(), "https://secret.example/token=bad", Instant.now()
        ));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        Counter.builder("stocksage.agent.tool.executions")
                .tag("kind", "spring_ai_tool").tag("status", "SUCCESS")
                .register(meterRegistry).increment(2);
        Counter.builder("stocksage.agent.tool.executions")
                .tag("kind", "capability").tag("status", "FAILED")
                .register(meterRegistry).increment();
        AgentTrace fastTrace = successfulTrace("trace-fast", 400);
        AgentTrace slowTrace = successfulTrace("trace-slow", 1200);
        when(traceRepository.findAll(org.mockito.ArgumentMatchers.any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(slowTrace, fastTrace)));
        AgentAdminService service = new AgentAdminService(
                skills, capabilities, mcp, properties, meterRegistry, traceRepository, "latest-news-mcp"
        );

        AgentAdminService.SkillsSnapshot skillSnapshot = service.skills();
        AgentAdminService.RuntimeSnapshot runtime = service.runtime();
        String json = new ObjectMapper().writeValueAsString(runtime);

        assertThat(skillSnapshot.skills()).singleElement().satisfies(skill -> {
            assertThat(skill.currentDefault()).isTrue();
            assertThat(skill.fallbackSkillIds()).containsExactly("local-latest-news");
        });
        assertThat(runtime.mcp().state()).isEqualTo(AgentAdminService.McpState.DISABLED);
        assertThat(runtime.schemaVersion()).isEqualTo("agent_runtime_v2");
        assertThat(runtime.window().kind()).isEqualTo("PROCESS_LIFETIME");
        assertThat(runtime.toolExecution().attempts()).isEqualTo(3);
        assertThat(runtime.toolExecution().successRate()).isEqualTo(2.0 / 3.0);
        assertThat(runtime.agentE2e().sampleCount()).isEqualTo(2);
        assertThat(runtime.agentE2e().p95DurationMs()).isEqualTo(1200.0);
        assertThat(runtime.agentE2e().window().kind()).isEqualTo("RECENT_SUCCESSFUL_TRACES");
        assertThat(runtime.capabilities()).singleElement()
                .extracting(AgentAdminService.CapabilityView::metricsStatus)
                .isEqualTo("NO_DATA");
        assertThat(json).doesNotContain("secret.example", "provider-internal", "native-internal", "token=bad");
    }

    private static AgentTrace successfulTrace(String traceId, long durationMs) {
        AgentTrace trace = new AgentTrace();
        trace.setTraceId(traceId);
        trace.setStatus("success");
        trace.setDurationMs(durationMs);
        trace.setCreatedAt(LocalDateTime.now().minusSeconds(1));
        return trace;
    }
}
