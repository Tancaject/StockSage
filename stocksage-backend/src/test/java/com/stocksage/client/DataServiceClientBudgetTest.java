package com.stocksage.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.cache.ToolResultCache;
import com.stocksage.exception.ResearchBudgetExceededException;
import com.stocksage.tool.ToolCallContext;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DataServiceClientBudgetTest {
    private static final String HEADER = "X-StockSage-Remaining-Ms";

    @Test
    void ordinaryRequestsHaveNoBudgetHeaderAndResearchRetriesRecalculateRemainingTime() {
        List<String> headers = new ArrayList<>();
        AtomicInteger requests = new AtomicInteger();
        DataServiceClient client = client(request -> {
            headers.add(request.headers().getFirst(HEADER));
            int number = requests.incrementAndGet();
            return number == 2
                    ? Mono.delay(Duration.ofMillis(60)).thenReturn(response(HttpStatus.SERVICE_UNAVAILABLE, "{}"))
                    : Mono.just(response(HttpStatus.OK, "{\"ok\":true}"));
        }, 60_000);
        assertThat(client.getEdgarFilingContent("https://example.com/report", "10-K")).contains("ok");
        assertThat(headers).containsExactly((String) null);
        ToolCallContext.withRunDeadline(deadline(10_000),
                () -> client.getEdgarFilingContent("https://example.com/report", "10-K"));
        assertThat(requests).hasValue(3);
        assertThat(Long.parseLong(headers.get(1))).isBetween(1L, 10_000L);
        assertThat(Long.parseLong(headers.get(2))).isPositive().isLessThan(Long.parseLong(headers.get(1)));
        assertThat(ToolCallContext.currentRunDeadline()).isNull();
    }

    @Test
    void headerCarriesRunBudgetIndependentlyOfTheLocalTimeoutAndHonorsTheProtocolMaximum() {
        AtomicReference<String> header = new AtomicReference<>();
        ExchangeFunction exchange = request -> {
            header.set(request.headers().getFirst(HEADER));
            return Mono.just(response(HttpStatus.OK, "{}"));
        };
        DataServiceClient local = client(exchange, 750);
        ToolCallContext.withRunDeadline(deadline(60_000),
                () -> local.getEdgarFilingContent("https://example.com/report", "10-K"));
        assertThat(Long.parseLong(header.get())).isBetween(751L, 60_000L);
        DataServiceClient capped = client(exchange, 3_600_000);
        ToolCallContext.withRunDeadline(deadline(3_600_000),
                () -> capped.getEdgarFilingContent("https://example.com/report", "10-K"));
        assertThat(header.get()).isEqualTo("1800000");

        AtomicInteger cancellations = new AtomicInteger();
        DataServiceClient shortLocal = client(request -> {
            header.set(request.headers().getFirst(HEADER));
            return Mono.<ClientResponse>never().doOnCancel(cancellations::incrementAndGet);
        }, 30);
        String result = ToolCallContext.withRunDeadline(deadline(60_000),
                () -> shortLocal.getEdgarFilingContent("https://example.com/report", "10-K"));
        assertThat(result).contains("\"error\":true");
        assertThat(cancellations).hasValue(3);
        assertThat(Long.parseLong(header.get())).isGreaterThan(30L);
    }

    @Test
    void expiredRequestCancelsTheHttpPublisherAndNeverRetries() {
        AtomicInteger requests = new AtomicInteger();
        AtomicBoolean cancelled = new AtomicBoolean();
        DataServiceClient client = client(request -> {
            requests.incrementAndGet();
            return Mono.<ClientResponse>never().doOnCancel(() -> cancelled.set(true));
        }, 60_000);
        long started = System.nanoTime();
        assertThatThrownBy(() -> ToolCallContext.withRunDeadline(deadline(400),
                () -> client.getEdgarFilingContent("https://example.com/report", "10-K")))
                .isInstanceOf(ResearchBudgetExceededException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        assertThat(requests).hasValue(1);
        assertThat(cancelled).isTrue();
        assertThat(ToolCallContext.currentRunDeadline()).isNull();
    }

    @Test
    void pythonBudgetRejectionEscapesEveryEndpointContractWithoutRetryOrSearchCircuitFailure() {
        AtomicInteger requests = new AtomicInteger();
        DataServiceClient client = client(request -> {
            requests.incrementAndGet();
            return Mono.just(response(HttpStatus.GATEWAY_TIMEOUT, "{\"code\":\"RESEARCH_BUDGET_EXCEEDED\"}"));
        }, 60_000);
        List<Supplier<?>> calls = List.of(
                () -> client.getEdgarFilingContent("https://example.com/report", "10-K"),
                () -> client.getKLine("0700.HK", "daily", 30),
                () -> client.getFinancialReports("0700.HK", "annual", 3),
                () -> client.getEdgarXbrl("AAPL"),
                () -> client.newsSearch("AAPL", 5),
                () -> client.parsePdf(Path.of("budget-fixture.pdf"), 1000, 100));
        for (Supplier<?> call : calls) {
            int before = requests.get();
            assertThatThrownBy(() -> ToolCallContext.withRunDeadline(deadline(60_000), call::get))
                    .isInstanceOf(ResearchBudgetExceededException.class)
                    .hasMessageContaining("DEADLINE");
            assertThat(requests).hasValue(before + 1);
        }
        CircuitBreaker circuit = (CircuitBreaker) ReflectionTestUtils.getField(client, "searchCircuitBreaker");
        assertThat(circuit.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    private ToolCallContext.RunDeadline deadline(long remainingMs) {
        return new ToolCallContext.RunDeadline(42L, System.currentTimeMillis() + remainingMs);
    }

    private DataServiceClient client(ExchangeFunction exchange, long timeoutMs) {
        ToolResultCache cache = mock(ToolResultCache.class);
        when(cache.getOrFetch(any(), any(), any(), any(), any()))
                .thenAnswer(call -> call.getArgument(3, Supplier.class).get());
        DataServiceClient client = new DataServiceClient("http://localhost", timeoutMs, timeoutMs, timeoutMs,
                1, cache, new ObjectMapper());
        ReflectionTestUtils.setField(client, "webClient", WebClient.builder().exchangeFunction(exchange).build());
        return client;
    }

    private ClientResponse response(HttpStatus status, String body) {
        return ClientResponse.create(status).header("Content-Type", "application/json").body(body).build();
    }
}
