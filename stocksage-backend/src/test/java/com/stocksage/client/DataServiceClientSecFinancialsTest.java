package com.stocksage.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stocksage.cache.ToolResultCache;
import com.stocksage.knowledge.EdgarIngestionService;
import com.stocksage.tool.FundamentalsTools;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DataServiceClientSecFinancialsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void sharedPythonFixtureKeepsFactPeriodsUnitsAndAcquisitionTimeThroughCacheAndTool() throws Exception {
        Map<String, String> cached = new HashMap<>();
        cached.put("cache:tool:edgar-xbrl:test", "old schema must not be used");
        AtomicInteger requests = new AtomicInteger();
        DataServiceClient client = client(cached, requests, new AtomicReference<>(fixture()), new AtomicReference<>(HttpStatus.OK));
        SecFinancialsResponse first = client.getEdgarXbrl("TEST");
        assertThat(first.status()).isEqualTo(SecFinancialsResponse.Status.SUCCESS);
        assertThat(first.metrics().keySet()).containsExactly("Revenue", "TotalAssets", "EPS");
        var revenue = first.metrics().get("Revenue").data().get(0);
        assertThat(revenue.value()).isEqualByComparingTo("105");
        assertThat(revenue.fiscalYear()).isNull();
        assertThat(revenue.filingFiscalYear()).isEqualTo(2025);
        assertThat(revenue.end()).isEqualTo("2026-01-31");
        assertThat(revenue.filed()).isEqualTo("2026-04-01");
        assertThat(first.asOf()).isEqualTo("2026-01-31");
        assertThat(first.metrics().get("EPS").unit()).isEqualTo("USD/shares");
        assertThatThrownBy(() -> first.metrics().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(client.getEdgarXbrl("TEST")).isEqualTo(first);
        assertThat(cached).containsKey("cache:tool:edgar-xbrl:v1:test");
        String toolJson = new FundamentalsTools(client, mock(EdgarIngestionService.class), mapper).getStructuredFinancials("TEST");
        assertThat(mapper.readTree(toolJson).path("fetchedAt").textValue()).isEqualTo("2026-09-25T01:02:03Z");
        assertThat(mapper.readTree(toolJson).path("metrics").path("Revenue").path("data").get(0).path("source_url").textValue())
                .isEqualTo("https://data.sec.gov/api/xbrl/companyfacts/CIK0000000001.json");
        assertThat(requests).hasValue(1);
    }

    @Test void httpAndMalformedFactFailuresRemainUncachedAndRecoveryKeepsDecimalPrecision() throws Exception {
        Map<String, String> cached = new HashMap<>();
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> body = new AtomicReference<>("{\"detail\":\"unavailable\"}");
        AtomicReference<HttpStatus> status = new AtomicReference<>(HttpStatus.SERVICE_UNAVAILABLE);
        DataServiceClient client = client(cached, requests, body, status);
        SecFinancialsResponse failure = client.getEdgarXbrl("TEST");
        assertThat(failure.status()).isEqualTo(SecFinancialsResponse.Status.ERROR);
        assertThat(failure.errorCode()).isEqualTo("DATA_SERVICE_HTTP_503");
        assertThat(failure.retryable()).isTrue();
        assertThat(failure.cik()).isNull();
        assertThat(failure.companyName()).isNull();
        assertThat(failure.asOf()).isNull();
        assertThat(failure.metrics()).isEmpty();
        assertThat(requests).hasValue(3);

        status.set(HttpStatus.OK);
        body.set(fixture().replace("2026-01-31", "not-a-date"));
        assertThat(client.getEdgarXbrl("TEST").errorCode()).isEqualTo("INVALID_DATA_SERVICE_RESPONSE");
        assertThat(requests).hasValue(4);
        assertThat(cached).isEmpty();
        body.set(fixture().replace("\"value\": 105", "\"value\": 9007199254740993.123456789"));
        SecFinancialsResponse recovered = client.getEdgarXbrl("TEST");
        assertThat(recovered.status()).isEqualTo(SecFinancialsResponse.Status.SUCCESS);
        assertThat(recovered.metrics().get("Revenue").data().get(0).value()).isEqualByComparingTo("9007199254740993.123456789");
        assertThat(client.getEdgarXbrl("TEST").metrics().get("Revenue").data().get(0).value())
                .isEqualByComparingTo("9007199254740993.123456789");
        assertThat(requests).hasValue(5);
    }

    @Test void contractRejectsCoercedFactsAndInconsistentSourceIdentity() throws Exception {
        ObjectNode fixture = (ObjectNode) mapper.readTree(fixture());
        ObjectNode numericCik = fixture.deepCopy().put("cik", 1234567890);
        ObjectNode booleanAccession = fixture.deepCopy();
        fact(booleanAccession).put("accn", true);
        ObjectNode stringValue = fixture.deepCopy();
        fact(stringValue).put("value", "105");
        ObjectNode booleanValue = fixture.deepCopy();
        fact(booleanValue).put("value", true);
        ObjectNode foreignSource = fixture.deepCopy();
        fact(foreignSource).put("source_url", "https://data.sec.gov/api/xbrl/companyfacts/CIK0000000002.json");
        for (ObjectNode invalid : List.of(numericCik, booleanAccession, stringValue, booleanValue, foreignSource,
                fixture.deepCopy().put("metric_count", 1), fixture.deepCopy().put("schemaVersion", 4294967297L))) {
            assertThatThrownBy(() -> SecFinancialsResponse.fromJson(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        ObjectNode zero = fixture.deepCopy();
        fact(zero).put("value", 0);
        assertThat(SecFinancialsResponse.fromJson(zero).metrics().get("Revenue").data().get(0).value()).isZero();
        ObjectNode scoped = fixture.deepCopy().put("requestedPeriod", "annual").put("requestedYears", 1);
        var facts = (com.fasterxml.jackson.databind.node.ArrayNode) scoped.path("metrics").path("Revenue").path("data");
        facts.add(fact(scoped).deepCopy().put("start", "2025-02-02"));
        assertThat(SecFinancialsResponse.fromJson(scoped).metrics().get("Revenue").data()).hasSize(2);
        facts.add(fact(scoped).deepCopy().put("start", "2024-02-01").put("end", "2025-01-31"));
        assertThatThrownBy(() -> SecFinancialsResponse.fromJson(scoped)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void financialReportCacheValidatesSecScopeOnWriteAndRead() throws Exception {
        ObjectNode annual = financialReportFixture();
        Map<String, String> cached = new HashMap<>();
        cached.put("cache:tool:financial-report:v1:test:annual:2", "obsolete response");
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> body = new AtomicReference<>(annual.toString());
        DataServiceClient client = client(cached, requests, body, new AtomicReference<>(HttpStatus.OK));
        String first = client.getFinancialReports("TEST", "annual", 2);
        SecFinancialsResponse response = SecFinancialsResponse.fromJson(mapper.readTree(first));
        assertThat(response.requestedPeriod()).isEqualTo("annual");
        assertThat(response.requestedYears()).isEqualTo(2);
        assertThat(response.source()).isEqualTo("ibkr");
        assertThat(response.provider()).isEqualTo("SEC_EDGAR");
        assertThat(client.getFinancialReports("TEST", "annual", 2)).isEqualTo(first);
        assertThat(requests).hasValue(1);
        String newKey = "cache:tool:financial-report:v2:test:annual:2";
        assertThat(cached).containsKey(newKey);

        ObjectNode legacy = annual.deepCopy();
        legacy.remove("schemaVersion");
        cached.put(newKey, legacy.toString());
        var invalidCached = mapper.readTree(client.getFinancialReports("TEST", "annual", 2));
        assertThat(invalidCached.path("errorCode").textValue()).isEqualTo("INVALID_DATA_SERVICE_RESPONSE");
        assertThat(invalidCached.has("provider")).isFalse();
        assertThat(invalidCached.has("market")).isFalse();
        assertThat(requests).hasValue(1);

    }

    @Test void legacyAndMismatchedUsReportsAreNotCachedAndUnsupportedQuarterlyStaysExplicit() throws Exception {
        ObjectNode annual = financialReportFixture();
        Map<String, String> cached = new HashMap<>();
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<HttpStatus> httpStatus = new AtomicReference<>(HttpStatus.OK);
        DataServiceClient client = client(cached, requests, body, httpStatus);
        ObjectNode legacy = annual.deepCopy();
        legacy.remove("schemaVersion");
        for (ObjectNode invalid : List.of(legacy, annual.deepCopy().put("requestedYears", 1))) {
            body.set(invalid.toString());
            assertThat(mapper.readTree(client.getFinancialReports("TEST", "annual", 2)).path("errorCode").textValue())
                    .isEqualTo("INVALID_DATA_SERVICE_RESPONSE");
            assertThat(cached).isEmpty();
        }

        ObjectNode unsupported = annual.deepCopy().put("status", "UNSUPPORTED").put("error", true)
                .put("requestedPeriod", "quarterly").put("metric_count", 0).putNull("asOf")
                .putNull("company_name").putNull("cik").put("errorCode", "UNSUPPORTED_PERIOD").put("retryable", false);
        unsupported.set("metrics", mapper.createObjectNode());
        body.set(unsupported.toString());
        var result = SecFinancialsResponse.fromJson(mapper.readTree(client.getFinancialReports("TEST", "quarterly", 2)));
        assertThat(result.status()).isEqualTo(SecFinancialsResponse.Status.UNSUPPORTED);
        assertThat(result.period()).isEqualTo("annual");
        assertThat(result.requestedPeriod()).isEqualTo("quarterly");
        assertThat(result.metrics()).isEmpty();
        assertThat(cached).isEmpty();
        httpStatus.set(HttpStatus.SERVICE_UNAVAILABLE);
        var httpFailure = mapper.readTree(client.getFinancialReports("TEST", "annual", 2));
        assertThat(httpFailure.path("error").booleanValue()).isTrue();
        assertThat(httpFailure.path("errorCode").textValue()).isEqualTo("DATA_SERVICE_HTTP_503");
        assertThat(httpFailure.path("retryable").booleanValue()).isTrue();
        assertThat(httpFailure.has("provider")).isFalse();
        assertThat(requests).hasValue(4);
        assertThat(cached).isEmpty();
    }

    private ObjectNode financialReportFixture() throws Exception {
        return ((ObjectNode) mapper.readTree(fixture())).put("requestedPeriod", "annual").put("requestedYears", 2)
                .put("input", "TEST").put("resolvedCode", "TEST").put("source", "ibkr").put("routeReason", "US ticker");
    }

    private ObjectNode fact(ObjectNode response) { return (ObjectNode) response.path("metrics").path("Revenue").path("data").get(0); }

    private String fixture() throws Exception {
        return Files.readString(Path.of("../stocksage-data-service/tests/fixtures/sec-financials-v1.json"));
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
        DataServiceClient client = new DataServiceClient("http://unused", 1000, 1000, 1000, 1, new ToolResultCache(redis), mapper);
        ReflectionTestUtils.setField(client, "webClient", WebClient.builder().exchangeFunction(request -> {
            requests.incrementAndGet();
            return Mono.just(ClientResponse.create(status.get()).header("Content-Type", "application/json").body(body.get()).build());
        }).build());
        return client;
    }
}
