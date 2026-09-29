package com.stocksage.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.agent.AgentRuntimeConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;

class ChatProviderConfigurationTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, 2})
    void configuredBudgetBindsAndRejectsMultipleDefaultCompletions(int responses) {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SpringAiRetryAutoConfiguration.class, OpenAiChatAutoConfiguration.class))
                .withUserConfiguration(AiConfig.class, ClientBuilderConfiguration.class)
                .withBean(AgentRuntimeConfiguration.class)
                .withBean(ToolCallingManager.class, () -> mock(ToolCallingManager.class))
                .withPropertyValues("spring.ai.openai.api-key=test", "spring.ai.openai.chat.options.n=" + responses,
                        "stocksage.research.token-budget.max-tokens=100",
                        "stocksage.research.token-budget.provider-fingerprint=" + "a".repeat(64),
                        "stocksage.research.token-budget.version=test", "stocksage.research.token-budget.source=test-fixture",
                        "stocksage.research.token-budget.valid-from=2020-01-01T00:00:00Z",
                        "stocksage.research.token-budget.valid-until=2100-01-01T00:00:00Z",
                        "stocksage.research.token-budget.models.test-model.max-input-tokens=60",
                        "stocksage.research.token-budget.models.test-model.max-output-tokens=30",
                        "stocksage.research.token-budget.models.test-model.max-reasoning-tokens=10")
                .run(context -> {
                    if (responses == 1) {
                        assertNull(context.getStartupFailure());
                        assertEquals(100L, context.getBean(ModelTokenBudgetProperties.class).maxTokens());
                        assertEquals(10L, context.getBean(ModelTokenBudgetProperties.class).models().get("test-model").maxReasoningTokens());
                    } else {
                        org.assertj.core.api.Assertions.assertThat(context.getStartupFailure())
                                .hasRootCauseMessage("stocksage.research.token-budget requires single-response chat without extra-body or default tools");
                    }
                });
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "{\"prompt_tokens\":2}",
            "{\"prompt_tokens\":2,\"completion_tokens\":1,\"total_tokens\":3}"
    })
    void actualSdkPreservesOnlyNativeUsageForBudgetSettlement(String usageJson) throws Exception {
        RestClient.Builder rest = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        server.expect(requestTo("https://usage.example.test/v1/chat/completions"))
                .andRespond(withSuccess("{\"id\":\"r\",\"model\":\"test-model\",\"choices\":[{\"index\":0,"
                        + "\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],\"usage\":"
                        + usageJson + "}", MediaType.APPLICATION_JSON));
        var store = mock(com.stocksage.research.ModelInvocationStore.class);
        org.mockito.Mockito.when(store.begin(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyMap())).thenReturn("invocation");
        var expected = new ObjectMapper().readTree(usageJson);
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SpringAiRetryAutoConfiguration.class, OpenAiChatAutoConfiguration.class))
                .withUserConfiguration(AiConfig.class, ClientBuilderConfiguration.class)
                .withBean(AgentRuntimeConfiguration.class).withBean(RestClient.Builder.class, () -> rest)
                .withBean(ToolCallingManager.class, () -> mock(ToolCallingManager.class))
                .withPropertyValues("spring.ai.openai.base-url=https://usage.example.test", "spring.ai.openai.api-key=test")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var invocation = new com.stocksage.model.dto.ModelInvocationContext(1L, 1, "owner", "trace", "snapshot",
                            System.currentTimeMillis() + 60_000);
                    assertEquals("ok", ChatClient.builder(context.getBean(ChatModel.class))
                            .defaultAdvisors(new com.stocksage.agent.ModelInvocationAdvisor("bull", store)).build()
                            .prompt().user("q").advisors(a -> a.param(com.stocksage.agent.ModelInvocationAdvisor.CONTEXT_KEY, invocation))
                            .call().content());
                    org.mockito.Mockito.verify(store).finish(org.mockito.ArgumentMatchers.eq("invocation"),
                            org.mockito.ArgumentMatchers.eq("SUCCEEDED"), org.mockito.ArgumentMatchers.argThat(result ->
                                    "PROVIDER".equals(result.get("usageSource")) && Integer.valueOf(2).equals(result.get("inputTokens"))
                                            && result.containsKey("outputTokens") == expected.has("completion_tokens")
                                            && result.containsKey("totalTokens") == expected.has("total_tokens")));
                });
        server.verify();
    }

    @Test
    void constructedApiUsesResolvedOverridesAndProductionRetryPolicy() throws Exception {
        Properties production = new Properties();
        try (var input = getClass().getResourceAsStream("/application.properties")) {
            assertNotNull(input);
            production.load(input);
        }
        String maxAttempts = production.getProperty("spring.ai.retry.max-attempts");
        assertEquals("1", maxAttempts);
        RestClient.Builder rest = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        server.expect(requestTo("https://chat.example.test/compatible/chat/completions"))
                .andExpect(header("Authorization", "Bearer chat-key"))
                .andExpect(header("OpenAI-Organization", "chat-org"))
                .andRespond(withSuccess("""
                        {"id":"response-1","object":"chat.completion","created":1,"model":"test-model",
                         "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
                         "usage":{"prompt_tokens":2,"completion_tokens":1,"total_tokens":3}}
                        """, MediaType.APPLICATION_JSON));
        AtomicInteger failedProviderCalls = new AtomicInteger();
        server.expect(manyTimes(), requestTo("https://chat.example.test/compatible/chat/completions"))
                .andRespond(request -> {
                    failedProviderCalls.incrementAndGet();
                    return withServerError().createResponse(request);
                });
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        SpringAiRetryAutoConfiguration.class, OpenAiChatAutoConfiguration.class))
                .withUserConfiguration(AiConfig.class, ClientBuilderConfiguration.class)
                .withBean(AgentRuntimeConfiguration.class)
                .withBean(RestClient.Builder.class, () -> rest)
                .withBean(ToolCallingManager.class, () -> mock(ToolCallingManager.class))
                .withPropertyValues("spring.ai.retry.max-attempts=" + maxAttempts,
                        "spring.ai.openai.base-url=https://unused.example.test",
                        "spring.ai.openai.api-key=common-key",
                        "spring.ai.openai.chat.base-url=https://chat.example.test/compatible",
                        "spring.ai.openai.chat.api-key=chat-key",
                        "spring.ai.openai.chat.organization-id=chat-org",
                        "spring.ai.openai.chat.completions-path=/chat/completions")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(1, context.getBeansOfType(OpenAiApi.class).size());
                    OpenAiChatModel model = context.getBean(OpenAiChatModel.class);
                    assertSame(context.getBean(OpenAiApi.class), ReflectionTestUtils.getField(model, "openAiApi"));
                    assertEquals("ok", context.getBean("chatClient", ChatClient.class).prompt().user("test").call().content());
                    var provider = context.getBean(AgentRuntimeConfiguration.class).chatProviderSnapshot();
                    assertEquals("chat.example.test", ((Map<?, ?>) provider.get("base")).get("host"));
                    assertEquals("API_CONSTRUCTION", provider.get("scope"));
                    assertThrows(RuntimeException.class, () -> model.call("provider failure"));
                    assertEquals(1, failedProviderCalls.get(),
                            "A synchronous model invocation must not retry HTTP 500 behind the invocation ledger");
                    server.verify();
                });
    }

    @Test
    @SuppressWarnings("unchecked")
    void identityIsImmutableRedactedAndChangesWithDestinationOrPath() throws Exception {
        AgentRuntimeConfiguration runtime = new AgentRuntimeConfiguration();
        assertThrows(IllegalStateException.class, runtime::chatProviderSnapshot);
        runtime.recordChatProvider("https://private-user:private-password@host.test:8443/private-tenant?secret-query=yes#private-fragment",
                "/private-completion?path-secret=yes#path-fragment");
        Map<String, Object> snapshot = runtime.chatProviderSnapshot();
        String json = new ObjectMapper().writeValueAsString(snapshot);
        for (String secret : new String[]{"private-user", "private-password", "private-tenant", "secret-query",
                "private-fragment", "private-completion", "path-secret", "path-fragment"}) {
            assertFalse(json.contains(secret));
        }
        assertThrows(UnsupportedOperationException.class, () -> snapshot.clear());
        assertThrows(UnsupportedOperationException.class, () -> ((Map<String, Object>) snapshot.get("base")).clear());
        assertThrows(IllegalStateException.class, () -> runtime.recordChatProvider("https://other.test", "/chat"));
        AgentRuntimeConfiguration same = new AgentRuntimeConfiguration();
        same.recordChatProvider("https://host.test:8443/private-tenant", "/private-completion");
        assertEquals(snapshot, same.chatProviderSnapshot());
        for (String[] changed : new String[][]{
                {"https://other.test:8443/private-tenant", "/private-completion"},
                {"https://host.test:8443/changed", "/private-completion"},
                {"https://host.test:8443/private-tenant", "/changed"}}) {
            AgentRuntimeConfiguration other = new AgentRuntimeConfiguration();
            other.recordChatProvider(changed[0], changed[1]);
            assertNotEquals(snapshot, other.chatProviderSnapshot());
        }
        AgentRuntimeConfiguration invalid = new AgentRuntimeConfiguration();
        var error = assertThrows(IllegalArgumentException.class,
                () -> invalid.recordChatProvider("https://secret password@host.test", "/chat"));
        assertFalse(error.toString().contains("secret password"));
        assertNull(error.getCause());
        assertThrows(IllegalStateException.class, invalid::chatProviderSnapshot);
    }

    @Configuration(proxyBeanMethods = false)
    static class ClientBuilderConfiguration {
        @Bean
        ChatClient.Builder builder(ChatModel model) { return ChatClient.builder(model); }
    }
}
