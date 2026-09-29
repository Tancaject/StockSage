package com.stocksage.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stocksage.cache.ToolResultCache;
import com.stocksage.tool.NewsTools;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DataServiceClientSearchTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void sharedFixturesPreservePublicationPrecisionFallbackAndScopeAcrossVersionedCache() throws Exception {
        ObjectNode fixture = fixture("search-news-v1.json");
        SearchResponse expected = SearchResponse.fromJson(fixture);
        Harness h = harness(fixture.toString());
        h.cache().put(key("news-search", expected), "obsolete response");
        SearchResponse response = SearchResponse.fromJson(mapper.readTree(news(h.client(), expected)));
        assertThat(response.status()).isEqualTo(SearchResponse.Status.SUCCESS);
        assertThat(response.results()).extracting(SearchResponse.Result::publishedTimeKind)
                .containsExactly(SearchResponse.PublicationTimeKind.INSTANT, SearchResponse.PublicationTimeKind.DATE, SearchResponse.PublicationTimeKind.UNKNOWN);
        assertThat(response.results().get(1).publishedAt()).isNull();
        assertThat(response.results().get(2).publishedAt()).isNull();
        assertThat(response.results().get(2).publishedDate()).isNull();
        assertThat(response.results().get(2).date()).isEqualTo(expected.results().get(2).date());
        assertThat(response.fetchedAt()).isEqualTo(expected.fetchedAt());
        assertThat(h.cache()).containsKey(key("news-search:v1", expected));
        assertThat(h.uri().get().getRawQuery()).contains("max_results=" + expected.requestedMaxResults());
        String cachedResponse = h.client().newsSearch(expected.query().toLowerCase(Locale.ROOT),
                expected.requestedMaxResults(), expected.requestedTimelimit(), "advanced".equals(expected.requestedDepth()));
        assertThat(SearchResponse.fromJson(mapper.readTree(cachedResponse))).isEqualTo(response);
        assertThat(h.requests()).hasValue(1);

        ObjectNode legacy = fixture.deepCopy();
        legacy.remove("schemaVersion");
        h.cache().put(key("news-search:v1", expected), legacy.toString());
        SearchResponse invalidCache = SearchResponse.fromJson(mapper.readTree(news(h.client(), expected)));
        assertThat(invalidCache.errorCode()).isEqualTo("INVALID_DATA_SERVICE_RESPONSE");
        assertThat(invalidCache.provider()).isNull();
        assertThat(h.requests()).hasValue(1);

        ObjectNode fallback = fixture("search-fallback-v1.json");
        SearchResponse fallbackExpected = SearchResponse.fromJson(fallback);
        h.body().set(fallback.toString());
        SearchResponse actualFallback = SearchResponse.fromJson(mapper.readTree(news(h.client(), fallbackExpected)));
        assertThat(actualFallback.provider()).isEqualTo("ddg");
        assertThat(actualFallback.requestedDepth()).isEqualTo("advanced");
        assertThat(actualFallback.effectiveDepth()).isNull();
        assertThat(actualFallback.requestedTimelimit()).isEqualTo("y");
        assertThat(actualFallback.effectiveTimelimit()).isNull();
        assertThat(actualFallback.fallbackFrom()).isEqualTo("tavily");
        assertThat(actualFallback.fallbackReason()).isEqualTo(fallbackExpected.fallbackReason());
        assertThat(h.requests()).hasValue(2);
    }

    @Test void contractRejectsInvalidUrlsCoercedContentAndInventedPublicationTimes() throws Exception {
        ObjectNode fixture = fixture("search-news-v1.json");
        ObjectNode scriptUrl = fixture.deepCopy();
        first(scriptUrl).put("link", "javascript:alert(1)");
        ObjectNode booleanTitle = fixture.deepCopy();
        first(booleanTitle).put("title", true);
        ObjectNode noContent = fixture.deepCopy();
        first(noContent).put("title", " ").put("snippet", " ");
        ObjectNode naiveTimestamp = fixture.deepCopy();
        first(naiveTimestamp).put("publishedAt", "2026-09-25T12:00:00");
        ObjectNode dateWithClock = fixture.deepCopy();
        ((ObjectNode) dateWithClock.path("results").get(1)).put("publishedAt", "2026-09-25T00:00:00Z");
        ObjectNode unknownWithDate = fixture.deepCopy();
        ((ObjectNode) unknownWithDate.path("results").get(2)).put("publishedDate", "2026-09-25");
        for (ObjectNode invalid : List.of(scriptUrl, booleanTitle, noContent, naiveTimestamp, dateWithClock, unknownWithDate,
                fixture.deepCopy().put("count", 0), fixture.deepCopy().put("requestedMaxResults", "5"),
                fixture.deepCopy().put("error", "false"), fixture.deepCopy().put("schemaVersion", 4294967297L))) {
            assertThatThrownBy(() -> SearchResponse.fromJson(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void httpRetriesAndProtocolFailuresCountOncePerLogicalSearchWhileStockNewsBypassesOpenCircuit() throws Exception {
        ObjectNode fixture = fixture("search-news-v1.json");
        SearchResponse scope = SearchResponse.fromJson(fixture);
        Harness h = harness(fixture.toString());
        h.status().set(HttpStatus.SERVICE_UNAVAILABLE);
        SearchResponse httpFailure = SearchResponse.fromJson(mapper.readTree(news(h.client(), scope)));
        assertThat(httpFailure.errorCode()).isEqualTo("DATA_SERVICE_HTTP_503");
        assertThat(httpFailure.retryable()).isTrue();
        assertThat(httpFailure.provider()).isNull();
        assertThat(httpFailure.effectiveDepth()).isNull();
        assertThat(h.requests()).hasValue(3);
        assertThat(h.circuit().getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
        assertThat(h.cache()).isEmpty();

        h.status().set(HttpStatus.OK);
        h.body().set("{\"results\":[]}");
        assertThat(SearchResponse.fromJson(mapper.readTree(news(h.client(), scope))).errorCode()).isEqualTo("INVALID_DATA_SERVICE_RESPONSE");
        assertThat(h.requests()).hasValue(4);
        assertThat(h.circuit().getMetrics().getNumberOfFailedCalls()).isEqualTo(2);
        h.body().set(SearchResponse.failure(scope.query(), "news", scope.requestedTimelimit(), scope.requestedDepth(),
                scope.requestedMaxResults(), null, "UPSTREAM_ERROR", "Provider unavailable", null).toJson());
        for (int index = 0; index < 3; index++) assertThat(SearchResponse.fromJson(mapper.readTree(news(h.client(), scope))).error()).isTrue();
        assertThat(h.circuit().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(h.circuit().getMetrics().getNumberOfFailedCalls()).isEqualTo(5);
        assertThat(h.requests()).hasValue(7);
        SearchResponse circuitFailure = SearchResponse.fromJson(mapper.readTree(h.client().webSearch(scope.query(), scope.requestedMaxResults())));
        assertThat(circuitFailure.errorCode()).isEqualTo("CIRCUIT_OPEN");
        assertThat(circuitFailure.searchType()).isEqualTo("web");
        assertThat(circuitFailure.provider()).isNull();
        assertThat(h.requests()).hasValue(7);
        assertThat(h.cache()).isEmpty();

        h.cache().put(key("news-search:v1", scope), fixture.toString());
        assertThat(SearchResponse.fromJson(mapper.readTree(news(h.client(), scope))).status()).isEqualTo(SearchResponse.Status.SUCCESS);
        assertThat(h.requests()).hasValue(7);
        assertThat(h.circuit().getMetrics().getNumberOfFailedCalls()).isEqualTo(5);
        ObjectNode stock = fixture.deepCopy().put("query", "AAPL").put("requestedDays", 30)
                .put("requestedMaxResults", 8).put("requestedTimelimit", "m").put("effectiveTimelimit", "m").put("timelimit", "m")
                .put("requestedDepth", "basic").put("effectiveDepth", "basic").put("depth", "basic")
                .put("input", "AAPL").put("market", "US").put("resolvedCode", "AAPL").put("source", "ibkr").put("routeReason", "US ticker");
        h.body().set(stock.deepCopy().put("query", "MSFT").toString());
        assertThat(SearchResponse.fromJson(mapper.readTree(new NewsTools(h.client()).getStockNews("AAPL", 30))).errorCode())
                .isEqualTo("INVALID_DATA_SERVICE_RESPONSE");
        assertThat(h.requests()).hasValue(8);
        h.body().set(stock.toString());
        SearchResponse stockResponse = SearchResponse.fromJson(mapper.readTree(new NewsTools(h.client()).getStockNews("AAPL", 30)));
        assertThat(stockResponse.status()).isEqualTo(SearchResponse.Status.SUCCESS);
        assertThat(stockResponse.requestedDays()).isEqualTo(30);
        assertThat(stockResponse.effectiveTimelimit()).isEqualTo("m");
        assertThat(stockResponse.query()).isEqualTo("AAPL");
        assertThat(h.requests()).hasValue(9);
        assertThat(h.circuit().getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test void emptyIsCacheableButInvalidRequestsHttpAndTimeoutFailuresRemainExplicit() throws Exception {
        ObjectNode empty = fixture("search-news-v1.json").put("status", "EMPTY").put("count", 0);
        empty.putArray("results");
        SearchResponse scope = SearchResponse.fromJson(empty);
        Harness h = harness(empty.toString());
        assertThat(SearchResponse.fromJson(mapper.readTree(news(h.client(), scope))).status()).isEqualTo(SearchResponse.Status.EMPTY);
        assertThat(h.cache()).hasSize(1);
        assertThat(h.circuit().getMetrics().getNumberOfFailedCalls()).isZero();
        for (String invalid : List.of(h.client().newsSearch(scope.query(), 0), h.client().webSearch(scope.query(), 21), h.client().getStockNews("AAPL", 0))) {
            assertThat(mapper.readTree(invalid).path("errorCode").textValue()).isEqualTo("INVALID_REQUEST");
        }
        assertThat(h.requests()).hasValue(1);

        h.cache().clear();
        h.status().set(HttpStatus.BAD_REQUEST);
        SearchResponse badRequest = SearchResponse.fromJson(mapper.readTree(news(h.client(), scope)));
        assertThat(badRequest.errorCode()).isEqualTo("DATA_SERVICE_HTTP_400");
        assertThat(badRequest.retryable()).isFalse();
        assertThat(h.requests()).hasValue(4);
        h.status().set(HttpStatus.OK);
        h.failure().set(new TimeoutException("simulated HTTP timeout"));
        SearchResponse timeout = SearchResponse.fromJson(mapper.readTree(news(h.client(), scope)));
        assertThat(timeout.errorCode()).isEqualTo("DATA_SERVICE_UNAVAILABLE");
        assertThat(timeout.retryable()).isTrue();
        assertThat(h.requests()).hasValue(7);
        assertThat(h.cache()).isEmpty();
        assertThat(h.circuit().getMetrics().getNumberOfFailedCalls()).isEqualTo(2);
    }

    private String news(DataServiceClient client, SearchResponse scope) {
        return client.newsSearch(scope.query(), scope.requestedMaxResults(), scope.requestedTimelimit(), "advanced".equals(scope.requestedDepth()));
    }

    private String key(String category, SearchResponse scope) {
        return "cache:tool:" + category + ":" + scope.query().trim().toLowerCase(Locale.ROOT) + ":" + scope.requestedMaxResults()
                + ":" + (scope.requestedTimelimit() == null ? "" : scope.requestedTimelimit()) + ":" + scope.requestedDepth();
    }

    private ObjectNode first(ObjectNode response) { return (ObjectNode) response.path("results").get(0); }

    private ObjectNode fixture(String filename) throws Exception {
        return (ObjectNode) mapper.readTree(Files.readString(Path.of("../stocksage-data-service/tests/fixtures", filename)));
    }

    @SuppressWarnings("unchecked")
    private Harness harness(String initialBody) {
        Map<String, String> cached = new HashMap<>();
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> body = new AtomicReference<>(initialBody);
        AtomicReference<HttpStatus> status = new AtomicReference<>(HttpStatus.OK);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<URI> uri = new AtomicReference<>();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(call -> cached.get(call.getArgument(0, String.class)));
        doAnswer(call -> { cached.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(values).set(anyString(), anyString(), any(Duration.class));
        DataServiceClient client = new DataServiceClient("http://unused", 1000, 1000, 1000, 1, new ToolResultCache(redis), mapper);
        ReflectionTestUtils.setField(client, "webClient", WebClient.builder().exchangeFunction(request -> {
            requests.incrementAndGet();
            uri.set(request.url());
            if (failure.get() != null) return Mono.error(failure.get());
            return Mono.just(ClientResponse.create(status.get()).header("Content-Type", "application/json").body(body.get()).build());
        }).build());
        return new Harness(client, cached, requests, body, status, failure, uri,
                (CircuitBreaker) ReflectionTestUtils.getField(client, "searchCircuitBreaker"));
    }

    private record Harness(DataServiceClient client, Map<String, String> cache, AtomicInteger requests,
                           AtomicReference<String> body, AtomicReference<HttpStatus> status, AtomicReference<Throwable> failure,
                           AtomicReference<URI> uri, CircuitBreaker circuit) { }
}
