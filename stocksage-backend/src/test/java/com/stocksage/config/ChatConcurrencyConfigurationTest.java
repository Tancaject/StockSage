package com.stocksage.config;

import com.stocksage.agent.AgentRuntimeConfiguration;
import com.stocksage.agent.ModelInvocationAdvisor;
import com.stocksage.model.dto.ModelInvocationContext;
import com.stocksage.exception.ResearchCapacityExceededException;
import com.stocksage.research.ModelInvocationStore;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ChatConcurrencyConfigurationTest {
    @ParameterizedTest
    @ValueSource(strings = {"HEADERS", "FIRST_TOKEN", "NEXT_TOKEN"})
    void actualAutoConfiguredBuildersShareOneAdmissionAcrossDefaultAgentAndBackgroundClients(String waitingFor) {
        RestClient.Builder rest = RestClient.builder();
        var server = MockRestServiceServer.bindTo(rest).build();
        server.expect(requestTo("https://chat.example.test/v1/chat/completions"))
                .andRespond(withSuccess("""
                        {"id":"r1","object":"chat.completion","created":1,"model":"test",
                         "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}
                        """, MediaType.APPLICATION_JSON));
        var requests = new AtomicInteger();
        var cancellations = new AtomicInteger();
        var bodySubscribed = new CountDownLatch(1);
        var bodyCancelled = new CountDownLatch(1);
        var tokenReceived = new CountDownLatch(1);
        WebClient.Builder web = WebClient.builder().exchangeFunction(request -> {
            requests.incrementAndGet();
            if (waitingFor.equals("HEADERS")) return Mono.<ClientResponse>never()
                    .doOnSubscribe(ignored -> bodySubscribed.countDown())
                    .doOnCancel(() -> { cancellations.incrementAndGet(); bodyCancelled.countDown(); });
            Flux<org.springframework.core.io.buffer.DataBuffer> body = Flux.never();
            if (waitingFor.equals("NEXT_TOKEN")) {
                var chunk = org.springframework.core.io.buffer.DefaultDataBufferFactory.sharedInstance.wrap(
                        ("data: {\"id\":\"r1\",\"object\":\"chat.completion.chunk\",\"created\":1,"
                                + "\"model\":\"test\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"hello\"}}]}\n\n")
                                // OpenAiChatModel keeps a two-chunk lookahead for usage metadata.
                                .repeat(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                body = Flux.concat(Flux.just(chunk), body);
            }
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", "text/event-stream")
                    .body(body
                            .doOnSubscribe(ignored -> bodySubscribed.countDown())
                            .doOnCancel(() -> { cancellations.incrementAndGet(); bodyCancelled.countDown(); })).build());
        });
        var store = mock(ModelInvocationStore.class);
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ChatClientAutoConfiguration.class,
                        SpringAiRetryAutoConfiguration.class, OpenAiChatAutoConfiguration.class))
                .withUserConfiguration(AiConfig.class, AgentConfig.class)
                .withBean(AgentRuntimeConfiguration.class)
                .withBean(ModelInvocationStore.class, () -> store)
                .withBean(RestClient.Builder.class, () -> rest)
                .withBean(WebClient.Builder.class, () -> web)
                .withBean(ToolCallingManager.class, () -> mock(ToolCallingManager.class))
                .withPropertyValues("spring.ai.openai.base-url=https://chat.example.test",
                        "spring.ai.openai.api-key=test-key", "spring.ai.retry.max-attempts=1",
                        "stocksage.chat.max-concurrent-calls=1")
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();
                    var primary = context.getBean("chatClient", ChatClient.class);
                    var active = primary.prompt().user("hold").stream().content().subscribe(token -> tokenReceived.countDown());
                    try {
                        assertThat(bodySubscribed.await(2, TimeUnit.SECONDS)).isTrue();
                        if (waitingFor.equals("NEXT_TOKEN")) assertThat(tokenReceived.await(2, TimeUnit.SECONDS)).isTrue();
                        assertThat(requests).hasValue(1);
                        for (String name : new String[]{"fundamentalsAgentChatClient", "contextualGistChatClient", "queryRewriteChatClient"}) {
                            var other = context.getBean(name, ChatClient.class);
                            assertThatThrownBy(() -> other.prompt().user("rejected")
                                    .advisors(a -> a.param(ModelInvocationAdvisor.CONTEXT_KEY,
                                            new ModelInvocationContext(42L, 1, "owner", "trace", "snapshot",
                                                    System.currentTimeMillis() + 60_000)))
                                    .call().content())
                                    .isInstanceOf(ResearchCapacityExceededException.class);
                        }
                        verifyNoInteractions(store);
                    } finally { active.dispose(); }
                    assertThat(bodyCancelled.await(2, TimeUnit.SECONDS)).isTrue();
                    assertThat(cancellations).hasValue(1);
                    assertThat(context.getBean("fundamentalsAgentChatClient", ChatClient.class)
                            .prompt().user("accepted after cancel").call().content()).isEqualTo("ok");
                    server.verify();
                });
    }
}
