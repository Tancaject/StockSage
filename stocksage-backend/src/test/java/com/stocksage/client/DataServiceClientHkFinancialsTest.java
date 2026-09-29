package com.stocksage.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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

class DataServiceClientHkFinancialsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void sharedPythonFixturePreservesItemsDecimalPrecisionAndActualFreshnessAcrossCacheAndTool() throws Exception {
        ObjectNode fixture = fixture();
        amountRow(fixture).put("AMOUNT", "9007199254740993.123456789");
        HkFinancialsResponse expected = HkFinancialsResponse.fromJson(fixture);
        Map<String, String> cached = new HashMap<>();
        String oldKey = "cache:tool:financial-report:v1:0700.hk:annual:2";
        cached.put(oldKey, "obsolete untyped HK response");
        AtomicInteger requests = new AtomicInteger();
        DataServiceClient client = client(cached, requests, new AtomicReference<>(fixture.toString()), new AtomicReference<>(HttpStatus.OK));
        String first = client.getFinancialReports("0700.HK", "annual", 2);
        HkFinancialsResponse response = HkFinancialsResponse.fromJson(mapper.readTree(first));
        assertThat(response.status()).isEqualTo(HkFinancialsResponse.Status.PARTIAL);
        assertThat(response.statementCount()).isEqualTo(9);
        assertThat(response.statements().balanceSheet().data()).hasSize(4);
        assertThat(response.statements().cashFlow().status()).isEqualTo(HkFinancialsResponse.Status.ERROR);
        assertThat(response.statements().cashFlow().data()).isEmpty();
        assertThat(response.statements().balanceSheet().data()).anySatisfy(row -> {
            assertThat(row.amount()).isEqualByComparingTo("9007199254740993.123456789");
            assertThat(row.amountUnit()).isEqualTo("UNKNOWN");
        });
        assertThat(response.statements().balanceSheet().data()).anySatisfy(row -> {
            assertThat(row.reportDate()).isEqualTo("2025-12-31");
            assertThat(row.hasValues()).isFalse();
        });
        assertThat(response.asOf()).isEqualTo("2024-12-31");
        assertThat(response.fetchedAt()).isEqualTo(expected.fetchedAt());
        assertThat(response.currency()).isNull();
        assertThat(response.numericPrecision()).isEqualTo("PROVIDER_VALUE");
        assertThat(response.indicators().data()).anySatisfy(row -> assertThat(row.currency()).isEqualTo("provider currency label"));
        assertThatThrownBy(() -> response.statements().balanceSheet().data().clear()).isInstanceOf(UnsupportedOperationException.class);
        String tool = new FundamentalsTools(client, mock(EdgarIngestionService.class), mapper).getFinancialReports("0700.HK", "annual", 2);
        assertThat(amountRow((ObjectNode) mapper.readTree(tool)).path("AMOUNT").textValue()).isEqualTo("9007199254740993.123456789");
        assertThat(client.getFinancialReports("0700.HK", "annual", 2)).isEqualTo(first);
        assertThat(requests).hasValue(1);
        String newKey = "cache:tool:financial-report:v2:0700.hk:annual:2";
        assertThat(cached).containsKeys(oldKey, newKey);

        ObjectNode legacy = fixture.deepCopy();
        legacy.remove("schemaVersion");
        cached.put(newKey, legacy.toString());
        var invalid = mapper.readTree(client.getFinancialReports("0700.HK", "annual", 2));
        assertThat(invalid.path("errorCode").textValue()).isEqualTo("INVALID_DATA_SERVICE_RESPONSE");
        assertThat(invalid.has("provider")).isFalse();
        assertThat(requests).hasValue(1);
    }

    @Test void typedContractRejectsCoercionTargetAndWindowContradictionsWithoutCountingItemsAsPeriods() throws Exception {
        ObjectNode fixture = fixture();
        ObjectNode numeric = fixture.deepCopy();
        amountRow(numeric).put("AMOUNT", 1.25);
        ObjectNode bool = fixture.deepCopy();
        amountRow(bool).put("AMOUNT", true);
        ObjectNode exponent = fixture.deepCopy();
        amountRow(exponent).put("AMOUNT", "1e3");
        ObjectNode foreign = fixture.deepCopy();
        amountRow(foreign).put("SECUCODE", "00005.HK");
        ObjectNode numericSecurityCode = fixture.deepCopy();
        amountRow(numericSecurityCode).put("SECURITY_CODE", 700);
        ObjectNode missingReportDate = fixture.deepCopy();
        amountRow(missingReportDate).remove("REPORT_DATE");
        ObjectNode tooManyPeriods = fixture.deepCopy();
        rows(tooManyPeriods).add(amountRow(tooManyPeriods).deepCopy().put("REPORT_DATE", "2023-12-31").putNull("START_DATE"));
        tooManyPeriods.put("statementCount", 10);
        for (ObjectNode invalid : List.of(numeric, bool, exponent, foreign, numericSecurityCode, missingReportDate, tooManyPeriods,
                fixture.deepCopy().put("asOf", "2025-12-31"), fixture.deepCopy().put("statementCount", 2),
                fixture.deepCopy().put("status", "SUCCESS"), fixture.deepCopy().put("errorCode", "UPSTREAM_ERROR"),
                fixture.deepCopy().put("schemaVersion", 4294967297L))) {
            assertThatThrownBy(() -> HkFinancialsResponse.fromJson(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        ObjectNode repeatedPeriodItem = fixture.deepCopy().put("statementCount", 10);
        rows(repeatedPeriodItem).add(amountRow(repeatedPeriodItem).deepCopy().put("AMOUNT", "0"));
        assertThat(HkFinancialsResponse.fromJson(repeatedPeriodItem).statements().balanceSheet().data()).hasSize(5);
        ObjectNode reportPeriods = fixture.deepCopy().put("period", "report_period").put("requestedPeriod", "quarterly");
        assertThat(HkFinancialsResponse.fromJson(reportPeriods).period()).isEqualTo("report_period");
        assertThatThrownBy(() -> HkFinancialsResponse.fromJson(reportPeriods.put("period", "quarterly")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void failedTablesLegacyScopeAndHttpErrorsAreUncachedWithoutInventingProviderIdentity() throws Exception {
        ObjectNode fixture = fixture();
        ObjectNode failed = fixture.deepCopy().put("status", "ERROR").put("error", true).putNull("asOf")
                .put("statementCount", 0).put("errorCode", "UPSTREAM_ERROR");
        for (var table : failed.path("statements")) makeFailed((ObjectNode) table);
        makeFailed((ObjectNode) failed.path("indicators"));
        Map<String, String> cached = new HashMap<>();
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> body = new AtomicReference<>(failed.toString());
        AtomicReference<HttpStatus> httpStatus = new AtomicReference<>(HttpStatus.OK);
        DataServiceClient client = client(cached, requests, body, httpStatus);
        HkFinancialsResponse response = HkFinancialsResponse.fromJson(mapper.readTree(client.getFinancialReports("0700.HK", "annual", 2)));
        assertThat(response.status()).isEqualTo(HkFinancialsResponse.Status.ERROR);
        assertThat(response.asOf()).isNull();
        assertThat(response.statementCount()).isZero();
        assertThat(cached).isEmpty();

        ObjectNode legacy = fixture.deepCopy();
        legacy.remove("schemaVersion");
        body.set(legacy.toString());
        assertThat(mapper.readTree(client.getFinancialReports("0700.HK", "annual", 2)).path("errorCode").textValue())
                .isEqualTo("INVALID_DATA_SERVICE_RESPONSE");
        body.set(fixture.toString());
        assertThat(mapper.readTree(client.getFinancialReports("0700.HK", "annual", 3)).path("errorCode").textValue())
                .isEqualTo("INVALID_DATA_SERVICE_RESPONSE");
        assertThat(cached).isEmpty();
        httpStatus.set(HttpStatus.SERVICE_UNAVAILABLE);
        var httpFailure = mapper.readTree(client.getFinancialReports("0700.HK", "annual", 2));
        assertThat(httpFailure.path("errorCode").textValue()).isEqualTo("DATA_SERVICE_HTTP_503");
        assertThat(httpFailure.path("retryable").booleanValue()).isTrue();
        assertThat(httpFailure.has("provider")).isFalse();
        assertThat(cached).isEmpty();
        assertThat(requests).hasValue(4);
    }

    private void makeFailed(ObjectNode table) {
        table.put("status", "ERROR").put("errorCode", "UPSTREAM_ERROR");
        table.set("data", mapper.createArrayNode());
    }

    private ArrayNode rows(ObjectNode response) { return (ArrayNode) response.path("statements").path("balanceSheet").path("data"); }

    private ObjectNode amountRow(ObjectNode response) {
        for (var row : rows(response)) if (row.hasNonNull("AMOUNT")) return (ObjectNode) row;
        throw new AssertionError("Fixture requires a usable statement amount");
    }

    private ObjectNode fixture() throws Exception {
        return (ObjectNode) mapper.readTree(Files.readString(Path.of("../stocksage-data-service/tests/fixtures/hk-financials-v1.json")));
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
