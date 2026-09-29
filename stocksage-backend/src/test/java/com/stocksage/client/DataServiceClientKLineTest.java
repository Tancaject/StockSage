package com.stocksage.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.cache.ToolResultCache;
import com.stocksage.ibkr.IbkrReadOnlyService;
import com.stocksage.tool.MarketTools;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DataServiceClientKLineTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void pythonFixtureBecomesTypedDataAndVersionedCachePreservesAcquisitionTimeAndToolFields() throws Exception {
        String fixture = Files.readString(Path.of("../stocksage-data-service/tests/fixtures/kline-v1.json"));
        Map<String, String> cached = new HashMap<>();
        cached.put("cache:tool:kline:sh.600519:daily:60", "old schema must not be read");
        AtomicInteger requests = new AtomicInteger();
        DataServiceClient client = client(cached, requests, new AtomicReference<>(fixture), new AtomicReference<>(HttpStatus.OK));

        KLineResponse first = client.getKLine("sh.600519", "daily", 60);
        KLineResponse second = client.getKLine("sh.600519", "daily", 60);
        assertThat(first.status()).isEqualTo(KLineResponse.Status.SUCCESS);
        assertThat(first.data().get(0).volume()).isZero();
        assertThat(first.data().get(1).amount()).isEqualTo(151000.0);
        assertThat(first.primaryProvider()).isEqualTo("akshare");
        assertThat(second).isEqualTo(first);
        assertThat(second.fetchedAt()).isEqualTo("2026-09-25T01:02:03Z");
        assertThat(second.asOf()).isEqualTo("2026-09-24");
        assertThat(cached).containsKey("cache:tool:kline:v1:sh.600519:daily:60");
        String toolJson = new MarketTools(client, mock(IbkrReadOnlyService.class), mapper)
                .getStockKLine("sh.600519", "daily", 60);
        var tool = mapper.readTree(toolJson);
        assertThat(tool.path("schemaVersion").intValue()).isEqualTo(1);
        assertThat(tool.path("primaryProviderError").textValue()).isEqualTo("primary provider timed out");
        assertThat(tool.path("data").get(1).path("turn").doubleValue()).isEqualTo(0.5);
        assertThat(tool.has("additionalProperties")).isFalse();
        assertThat(requests).hasValue(1);
    }

    @Test
    void httpAndProtocolFailuresAreTypedAndNotCached() throws Exception {
        AtomicReference<String> body = new AtomicReference<>("{\"detail\":\"temporarily unavailable\"}");
        AtomicReference<HttpStatus> status = new AtomicReference<>(HttpStatus.SERVICE_UNAVAILABLE);
        Map<String, String> cached = new HashMap<>();
        AtomicInteger requests = new AtomicInteger();
        DataServiceClient client = client(cached, requests, body, status);

        KLineResponse httpFailure = client.getKLine("sh.600519", "daily", 60);
        assertThat(httpFailure.status()).isEqualTo(KLineResponse.Status.ERROR);
        assertThat(httpFailure.errorCode()).isEqualTo("DATA_SERVICE_HTTP_503");
        assertThat(httpFailure.retryable()).isTrue();
        assertThat(httpFailure.provider()).isNull();
        assertThat(httpFailure.resolvedCode()).isNull();
        assertThat(httpFailure.asOf()).isNull();
        assertThat(cached).isEmpty();

        status.set(HttpStatus.OK);
        body.set("{\"data\":[{\"open\":1,\"high\":2,\"low\":0,\"close\":1}]}");
        KLineResponse protocolFailure = client.getKLine("sh.600519", "daily", 60);
        assertThat(protocolFailure.errorCode()).isEqualTo("INVALID_DATA_SERVICE_RESPONSE");
        assertThat(protocolFailure.retryable()).isFalse();
        assertThat(cached).isEmpty();

        String valid = Files.readString(Path.of("../stocksage-data-service/tests/fixtures/kline-v1.json"));
        body.set(valid.replace("2026-09-24", "not-a-date"));
        assertThat(client.getKLine("sh.600519", "daily", 60).errorCode()).isEqualTo("INVALID_DATA_SERVICE_RESPONSE");
        assertThat(cached).isEmpty();
        body.set(valid);
        assertThat(client.getKLine("sh.600519", "daily", 60).status()).isEqualTo(KLineResponse.Status.SUCCESS);
        assertThat(requests).hasValue(4);
    }

    @SuppressWarnings("unchecked")
    private DataServiceClient client(Map<String, String> cached, AtomicInteger requests,
                                     AtomicReference<String> body, AtomicReference<HttpStatus> status) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(call -> cached.get(call.getArgument(0, String.class)));
        doAnswer(call -> { cached.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(values).set(anyString(), anyString(), any(Duration.class));
        DataServiceClient client = new DataServiceClient("http://unused", 1000, 1000, 1000, 1,
                new ToolResultCache(redis), mapper);
        ReflectionTestUtils.setField(client, "webClient", WebClient.builder().exchangeFunction(request -> {
            requests.incrementAndGet();
            return Mono.just(ClientResponse.create(status.get()).header("Content-Type", "application/json")
                    .body(body.get()).build());
        }).build());
        return client;
    }
}
