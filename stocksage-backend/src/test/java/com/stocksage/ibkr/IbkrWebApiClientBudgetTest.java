package com.stocksage.ibkr;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.exception.ResearchBudgetExceededException;
import com.stocksage.tool.ToolCallContext;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class IbkrWebApiClientBudgetTest {
    @Test
    void deadlineCancelsHttpAndPreventsFurtherRequestsWithoutBecomingProviderError() {
        var properties = new IbkrProperties();
        properties.setEnabled(true);
        properties.setTimeoutMs(10_000);
        var mapper = new ObjectMapper();
        var client = new IbkrWebApiClient(properties, mapper);
        var requests = new AtomicInteger();
        var cancelled = new AtomicBoolean();
        ReflectionTestUtils.setField(client, "webClient", WebClient.builder().exchangeFunction(request -> {
            requests.incrementAndGet();
            return Mono.<org.springframework.web.reactive.function.client.ClientResponse>never()
                    .doOnCancel(() -> cancelled.set(true));
        }).build());
        var service = new IbkrReadOnlyService(properties, client, new IbkrInstrumentResolver(), mapper);
        assertThatThrownBy(() -> ToolCallContext.withRunDeadline(
                new ToolCallContext.RunDeadline(42L, System.currentTimeMillis() + 500),
                () -> service.getHistoricalBars("NVDA", "6m", "1d")))
                .isInstanceOf(ResearchBudgetExceededException.class);
        assertThat(requests).hasValue(1);
        assertThat(cancelled).isTrue();
        assertThatThrownBy(() -> ToolCallContext.withRunDeadline(
                new ToolCallContext.RunDeadline(42L, 1L), () -> client.postEmpty("/tickle")))
                .isInstanceOf(ResearchBudgetExceededException.class);
        assertThat(requests).hasValue(1);
        assertThat(ToolCallContext.currentRunDeadline()).isNull();
    }
}
