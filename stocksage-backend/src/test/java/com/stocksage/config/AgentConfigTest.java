package com.stocksage.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.agent.AgentRuntimeConfiguration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentConfigTest {

    @Test
    @SuppressWarnings("unchecked")
    void snapshotMatchesEveryActualClientDefaultAndIsDeeplyImmutable() throws Exception {
        AgentRuntimeConfiguration runtime = new AgentRuntimeConfiguration();
        AgentConfig config = configured(runtime);
        ChatClient.Builder builder = mock(ChatClient.Builder.class, RETURNS_SELF);
        ChatClient client = mock(ChatClient.class);
        when(builder.clone()).thenReturn(builder);
        when(builder.build()).thenReturn(client);

        assertSame(client, config.fundamentalsAgentChatClient(builder));
        assertThrows(IllegalStateException.class, runtime::snapshot);
        config.marketAgentChatClient(builder);
        config.newsAgentChatClient(builder);
        config.bullResearcherChatClient(builder);
        config.bearResearcherChatClient(builder);
        config.researchManagerChatClient(builder);
        config.researchManagerScoringChatClient(builder);
        config.researchManagerContinuationChatClient(builder);
        config.deepEvidenceReplannerChatClient(builder);

        assertTrue(assertThrows(IllegalStateException.class, runtime::snapshot)
                .getMessage().contains("query-rewrite"));
        AiConfig aiConfig = new AiConfig(runtime);
        ReflectionTestUtils.setField(aiConfig, "fastModel", "fast");
        ReflectionTestUtils.setField(aiConfig, "modelRoutingTemperature", 0.6);
        ReflectionTestUtils.setField(aiConfig, "modelRoutingMaxOutputTokens", 3072);
        aiConfig.queryRewriteChatClient(builder);
        aiConfig.contextualGistChatClient(builder);

        ArgumentCaptor<ChatOptions> options = ArgumentCaptor.forClass(ChatOptions.class);
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(builder, times(11)).defaultOptions(options.capture());
        verify(builder, times(11)).defaultSystem(prompts.capture());
        Map<String, Object> snapshot = runtime.snapshot();
        List<String> roles = List.of("fundamentals", "market", "news", "bull", "bear", "manager",
                "scoring", "continuation", "replan", "query-rewrite", "contextual-gist");
        assertEquals(roles.stream().sorted().toList(), new ArrayList<>(snapshot.keySet()));
        for (int i = 0; i < roles.size(); i++) {
            Map<String, Object> captured = (Map<String, Object>) snapshot.get(roles.get(i));
            ChatOptions actual = options.getAllValues().get(i);
            assertEquals(i < 3 ? "standard" : i >= 8 ? "fast" : "strong", actual.getModel());
            assertEquals(i >= 6 && i <= 8 ? 0.0 : 0.6, actual.getTemperature());
            assertEquals(i >= 7 && i <= 8 ? 256 : 3072, actual.getMaxTokens());
            assertEquals(actual.getModel(), captured.get("model"));
            assertEquals(actual.getTemperature(), captured.get("temperature"));
            assertEquals(actual.getMaxTokens(), captured.get("maxTokens"));
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(prompts.getAllValues().get(i).getBytes(StandardCharsets.UTF_8)));
            assertEquals(hash, captured.get("systemPromptHash"));
            assertEquals("CLIENT_DEFAULTS", captured.get("scope"));
            assertEquals(true, captured.get("unknownProviderRevision"));
            assertThrows(UnsupportedOperationException.class, () -> captured.put("model", "changed"));
        }
        assertThrows(UnsupportedOperationException.class, () -> snapshot.clear());
        ObjectMapper mapper = new ObjectMapper();
        assertEquals(mapper.valueToTree(snapshot), mapper.readTree(mapper.writeValueAsString(snapshot)));
    }

    @Test
    void failedBuildDoesNotRegisterAClient() {
        AgentRuntimeConfiguration runtime = new AgentRuntimeConfiguration();
        AgentConfig config = configured(runtime);
        ChatClient.Builder builder = mock(ChatClient.Builder.class, RETURNS_SELF);
        when(builder.clone()).thenReturn(builder);
        when(builder.build()).thenThrow(new IllegalStateException("build failed"));
        assertThrows(IllegalStateException.class, () -> config.fundamentalsAgentChatClient(builder));
        assertTrue(assertThrows(IllegalStateException.class, runtime::snapshot)
                .getMessage().contains("fundamentals"));
        doReturn(mock(ChatClient.class)).when(builder).build();
        assertDoesNotThrow(() -> config.fundamentalsAgentChatClient(builder));
        assertThrows(IllegalStateException.class, () -> config.fundamentalsAgentChatClient(builder));
    }

    private AgentConfig configured(AgentRuntimeConfiguration runtime) {
        AgentConfig config = new AgentConfig(runtime, mock(com.stocksage.research.ModelInvocationStore.class));
        ReflectionTestUtils.setField(config, "fastModel", "fast");
        ReflectionTestUtils.setField(config, "standardModel", "standard");
        ReflectionTestUtils.setField(config, "strongModel", "strong");
        ReflectionTestUtils.setField(config, "modelRoutingTemperature", 0.6);
        ReflectionTestUtils.setField(config, "managerScoreTemperature", 0.0);
        ReflectionTestUtils.setField(config, "modelRoutingMaxOutputTokens", 3072);
        return config;
    }
}
