package com.stocksage.config;

import com.stocksage.tool.ToolCallContext;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.client.HttpClientAutoConfiguration;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResearchHttpDeadlineConfigurationTest {
    @Test
    void absentDeadlineUsesOriginalTransportAndExpiryBeforeExecuteMakesNoRequest() throws Exception {
        var original = mock(ClientHttpRequestFactory.class);
        var factory = ResearchHttpDeadlineConfiguration.withDeadline(original, null);
        var uri = URI.create("http://unused.test/chat");
        factory.createRequest(uri, HttpMethod.GET);
        verify(original).createRequest(uri, HttpMethod.GET);
        clearInvocations(original);
        var deadline = mock(ToolCallContext.RunDeadline.class);
        var failure = new com.stocksage.exception.ResearchBudgetExceededException(71L,
                com.stocksage.exception.ResearchBudgetExceededException.Reason.DEADLINE);
        when(deadline.remainingMillis()).thenReturn(1000L);
        ToolCallContext.withRunDeadline(deadline, () -> {
            try {
                var request = factory.createRequest(uri, HttpMethod.POST);
                request.getBody().write(new byte[]{1});
                when(deadline.remainingMillis()).thenThrow(failure);
                assertThatThrownBy(request::execute).isSameAs(failure);
            } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
            doReturn(1000L).when(deadline).remainingMillis();
            return null;
        });
        verifyNoInteractions(original);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void bootWiringTimesOutBeforeHeadersAndDuringBodyAndKeepsShorterLocalTimeout(int variant) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        server.createContext("/ok", exchange -> {
            exchange.sendResponseHeaders(200, 2);
            exchange.getResponseBody().write("ok".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            exchange.close();
        });
        server.createContext("/slow", exchange -> {
            try {
                if (variant != 0) {
                    exchange.sendResponseHeaders(200, 10);
                    exchange.getResponseBody().write('x');
                    exchange.getResponseBody().flush();
                }
                entered.countDown();
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class))
                    .withUserConfiguration(ResearchHttpDeadlineConfiguration.class)
                    .run(context -> {
                        assertThat(context.getStartupFailure()).isNull();
                        var factory = context.getBean(ClientHttpRequestFactoryBuilder.class).build(
                                ClientHttpRequestFactorySettings.defaults().withReadTimeout(Duration.ofSeconds(variant == 2 ? 1 : 10)));
                        var client = RestClient.builder().requestFactory(factory)
                                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build();
                        assertThat(ToolCallContext.withRunDeadline(new ToolCallContext.RunDeadline(71L,
                                System.currentTimeMillis() + 10_000),
                                () -> client.get().uri("/ok").retrieve().body(String.class))).isEqualTo("ok");
                        long started = System.nanoTime();
                        assertThatThrownBy(() -> ToolCallContext.withRunDeadline(new ToolCallContext.RunDeadline(71L,
                                System.currentTimeMillis() + (variant == 2 ? 10_000 : 1000)),
                                () -> client.get().uri("/slow").retrieve().body(String.class)))
                                .isInstanceOf(RestClientException.class);
                        assertThat(entered.getCount()).isZero();
                        assertThat(Duration.ofNanos(System.nanoTime() - started).toMillis()).isBetween(700L, 4000L);
                        assertThat(ToolCallContext.currentRunDeadline()).isNull();
                    });
        } finally { release.countDown(); server.stop(0); }
    }

    @Test
    void customFactoryBuilderIsNotSilentlyReplaced() {
        var custom = mock(ClientHttpRequestFactoryBuilder.class);
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class))
                .withUserConfiguration(ResearchHttpDeadlineConfiguration.class)
                .withBean("clientHttpRequestFactoryBuilder", ClientHttpRequestFactoryBuilder.class, () -> custom)
                .run(context -> assertThat(context.getBean(ClientHttpRequestFactoryBuilder.class)).isSameAs(custom));
    }
}
