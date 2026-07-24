package com.stocksage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.agent.ModelTier;
import com.stocksage.agent.PlanRoute;
import com.stocksage.capability.CapabilityDescriptor;
import com.stocksage.capability.CapabilityRegistry;
import com.stocksage.mcp.McpCapabilityProvider;
import com.stocksage.mcp.McpProperties;
import com.stocksage.skill.SkillDefinition;
import com.stocksage.skill.SkillRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
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
                        "mcp.news.search", false, "local.news.searchNews", null, Set.of())),
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
        AgentAdminService service = new AgentAdminService(
                skills, capabilities, mcp, properties, new SimpleMeterRegistry(), "latest-news-mcp"
        );

        AgentAdminService.SkillsSnapshot skillSnapshot = service.skills();
        AgentAdminService.RuntimeSnapshot runtime = service.runtime();
        String json = new ObjectMapper().writeValueAsString(runtime);

        assertThat(skillSnapshot.skills()).singleElement().satisfies(skill -> {
            assertThat(skill.currentDefault()).isTrue();
            assertThat(skill.fallbackSkillIds()).containsExactly("local-latest-news");
        });
        assertThat(runtime.mcp().state()).isEqualTo(AgentAdminService.McpState.DISABLED);
        assertThat(runtime.capabilities()).singleElement()
                .extracting(AgentAdminService.CapabilityView::metricsStatus)
                .isEqualTo("NO_DATA");
        assertThat(json).doesNotContain("secret.example", "provider-internal", "native-internal", "token=bad");
    }
}
