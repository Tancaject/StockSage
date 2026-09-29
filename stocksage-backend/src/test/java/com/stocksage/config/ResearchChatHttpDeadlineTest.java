package com.stocksage.config;

import com.stocksage.agent.AgentRuntimeConfiguration;
import com.stocksage.agent.ModelInvocationAdvisor;
import com.stocksage.exception.ResearchBudgetExceededException;
import com.stocksage.model.dto.ModelInvocationContext;
import com.stocksage.research.ModelInvocationStore;
import com.stocksage.tool.ToolCallContext;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeRerankAutoConfiguration;
import com.stocksage.rag.DashScopeReranker;
import org.springframework.ai.document.Document;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.client.HttpClientAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ResearchChatHttpDeadlineTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void actualAutoConfiguredModelsAbortSlowBodyAndRestoreScope(boolean rerank) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var release = new CountDownLatch(1);
        var requests = new AtomicInteger();
        server.createContext(rerank ? "/api/v1/services/rerank/text-rerank/text-rerank" : "/v1/chat/completions", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                requests.incrementAndGet();
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, 1000);
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        var store = mock(ModelInvocationStore.class);
        when(store.begin(any(ModelInvocationContext.class), anyString(), anyMap())).thenReturn("http-invocation");
        try {
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class,
                            RestClientAutoConfiguration.class, ChatClientAutoConfiguration.class,
                            SpringAiRetryAutoConfiguration.class, OpenAiChatAutoConfiguration.class,
                            DashScopeRerankAutoConfiguration.class))
                    .withUserConfiguration(ResearchHttpDeadlineConfiguration.class, AiConfig.class, AgentConfig.class,
                            DashScopeReranker.class)
                    .withBean(AgentRuntimeConfiguration.class)
                    .withBean(ModelInvocationStore.class, () -> store)
                    .withBean(ToolCallingManager.class, () -> mock(ToolCallingManager.class))
                    .withPropertyValues("spring.ai.openai.base-url=http://127.0.0.1:" + server.getAddress().getPort(),
                            "spring.ai.openai.api-key=local-test-key", "spring.ai.retry.max-attempts=1",
                            "spring.ai.dashscope.base-url=http://127.0.0.1:" + server.getAddress().getPort(),
                            "spring.ai.dashscope.api-key=local-test-key", "spring.ai.dashscope.enabled=true",
                            "spring.ai.model.rerank=dashscope",
                            "spring.http.client.read-timeout=15s")
                    .run(context -> {
                        assertThat(context.getStartupFailure()).isNull();
                        var client = context.getBean("fundamentalsAgentChatClient", ChatClient.class);
                        var invocation = new ModelInvocationContext(71L, 1, "owner", "trace", "snapshot",
                                System.currentTimeMillis() + 2000);
                        long started = System.nanoTime();
                        assertThatThrownBy(() -> {
                            if (rerank) {
                                ToolCallContext.withRunDeadline(new ToolCallContext.RunDeadline(invocation.runId(),
                                        invocation.deadlineEpochMs()), () -> context.getBean(DashScopeReranker.class)
                                        .rerank("query", java.util.List.of(new Document("candidate")), 1));
                            } else {
                                client.prompt().user("slow local response")
                                        .advisors(advisor -> advisor.param(ModelInvocationAdvisor.CONTEXT_KEY, invocation))
                                        .call().content();
                            }
                        }).isInstanceOf(ResearchBudgetExceededException.class);
                        assertThat(requests).hasValue(1);
                        assertThat(Duration.ofNanos(System.nanoTime() - started).toMillis()).isBetween(1500L, 6000L);
                        if (rerank) verifyNoInteractions(store);
                        else {
                            verify(store).begin(eq(invocation), anyString(), anyMap());
                            verify(store).finish(eq("http-invocation"), eq("FAILED"), anyMap());
                            verify(store, never()).finish(anyString(), eq("SUCCEEDED"), anyMap());
                        }
                        assertThat(ToolCallContext.currentRunDeadline()).isNull();
                    });
        } finally {
            release.countDown();
            server.stop(0);
        }
    }
}
