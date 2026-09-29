package com.stocksage.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
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

class DataServiceClientAShareFinancialsTest {
    private final ObjectMapper mapper = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    @Test void sharedPythonPartialFixtureKeepsTypedStatementsDatesAndExactAmountsThroughCacheAndTool() throws Exception {
        ObjectNode fixture = fixture();
        profit(fixture).put("netProfit", "9007199254740993.123456789");
        AShareFinancialsResponse expected = AShareFinancialsResponse.fromJson(fixture);
        Map<String, String> cached = new HashMap<>();
        AtomicInteger requests = new AtomicInteger();
        DataServiceClient client = client(cached, requests, new AtomicReference<>(fixture.toString()));
        String first = client.getFinancialReports(expected.code(), expected.period(), expected.requestedYears());
        AShareFinancialsResponse response = AShareFinancialsResponse.fromJson(mapper.readTree(first));
        assertThat(response.status()).isEqualTo(AShareFinancialsResponse.Status.PARTIAL);
        assertThat(response.error()).isFalse();
        assertThat(response.reports().get(0).status()).isEqualTo(AShareFinancialsResponse.Status.SUCCESS);
        assertThat(response.reports().get(1).status()).isEqualTo(AShareFinancialsResponse.Status.PARTIAL);
        var statements = response.reports().get(0).statements();
        assertThat(statements.operation().data().nrTurnDaysUnit()).isEqualTo("DAY");
        var partial = response.reports().get(1).statements();
        assertThat(partial.operation().status()).isEqualTo(AShareFinancialsResponse.Status.EMPTY);
        assertThat(partial.operation().data()).isNull();
        assertThat(partial.growth().status()).isEqualTo(AShareFinancialsResponse.Status.ERROR);
        assertThat(partial.growth().data()).isNull();
        assertThat(partial.profit().data().netProfit()).isZero();
        assertThat(statements.profit().data().netProfit()).isEqualByComparingTo("9007199254740993.123456789");
        assertThat(statements.profit().data().netProfitUnit()).isEqualTo("YUAN");
        assertThat(response.asOf()).isEqualTo(statements.profit().data().statDate());
        assertThat(response.fetchedAt()).isEqualTo(expected.fetchedAt());
        assertThat(response.currency()).isNull();
        assertThat(response.valueScale()).isEqualTo("PROVIDER_RAW");
        assertThat(response.valueEncoding()).isEqualTo("DECIMAL_STRING");
        assertThat(response.aggregationBasis()).isEqualTo("UNKNOWN");
        assertThatThrownBy(() -> response.reports().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(client.getFinancialReports(expected.code(), expected.period(), expected.requestedYears())).isEqualTo(first);
        String tool = new FundamentalsTools(client, mock(EdgarIngestionService.class), mapper)
                .getFinancialReports(expected.code(), expected.period(), expected.requestedYears());
        assertThat(mapper.readTree(tool).path("reports").get(0).path("statements").path("profit").path("data").path("MBRevenueUnit").textValue())
                .isEqualTo("YUAN");
        assertThat(mapper.readTree(tool).path("reports").get(0).path("statements").path("profit").path("data").path("netProfit").textValue())
                .isEqualTo("9007199254740993.123456789");
        assertThat(requests).hasValue(1);
        assertThat(cached).hasSize(1);
        assertThat(cached.keySet().iterator().next()).startsWith("cache:tool:financial-report:v2:");

        ObjectNode legacy = fixture.deepCopy();
        legacy.remove("schemaVersion");
        cached.replaceAll((key, value) -> legacy.toString());
        var invalidCached = mapper.readTree(client.getFinancialReports(expected.code(), expected.period(), expected.requestedYears()));
        assertThat(invalidCached.path("errorCode").textValue()).isEqualTo("INVALID_DATA_SERVICE_RESPONSE");
        assertThat(invalidCached.has("provider")).isFalse();
        assertThat(requests).hasValue(1);
    }

    @Test void protocolRejectsCoercionsWrongTargetsInventedDatesAndContradictoryScope() throws Exception {
        ObjectNode fixture = fixture();
        ObjectNode numericAmount = fixture.deepCopy();
        profit(numericAmount).put("netProfit", 1.23);
        ObjectNode exponentAmount = fixture.deepCopy();
        profit(exponentAmount).put("netProfit", "1e3");
        ObjectNode booleanAmount = fixture.deepCopy();
        profit(booleanAmount).put("netProfit", true);
        ObjectNode foreignTarget = fixture.deepCopy();
        profit(foreignTarget).put("code", "sh.600000");
        ObjectNode missingDate = fixture.deepCopy();
        profit(missingDate).remove("statDate");
        ObjectNode duplicatePeriod = fixture.deepCopy().put("requestedYears", 3).put("count", 3);
        duplicatePeriod.withArray("reports").add(duplicatePeriod.path("reports").get(0).deepCopy());
        ObjectNode wrongQuarter = fixture.deepCopy();
        ((ObjectNode) wrongQuarter.path("reports").get(0)).put("quarter", 3);
        ObjectNode falseSuccess = fixture.deepCopy().put("status", "SUCCESS");
        ObjectNode numericCode = fixture.deepCopy().put("code", 600519);
        ObjectNode ordinalStatus = fixture.deepCopy().put("status", 1);
        for (ObjectNode invalid : List.of(numericAmount, exponentAmount, booleanAmount, foreignTarget, missingDate, duplicatePeriod,
                wrongQuarter, falseSuccess, numericCode, ordinalStatus, fixture.deepCopy().put("schemaVersion", 4294967297L))) {
            assertThatThrownBy(() -> AShareFinancialsResponse.fromJson(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        ObjectNode zero = fixture.deepCopy();
        profit(zero).put("netProfit", "0");
        assertThat(AShareFinancialsResponse.fromJson(zero).reports().get(0).statements().profit().data().netProfit()).isZero();
    }

    @Test void failedStatementsAndScopeMismatchesRemainUncachedAndNeverBecomeUsableReports() throws Exception {
        ObjectNode fixture = fixture();
        AShareFinancialsResponse expected = AShareFinancialsResponse.fromJson(fixture);
        ObjectNode failed = fixture.deepCopy().put("status", "ERROR").put("error", true).putNull("asOf")
                .put("errorCode", "UPSTREAM_ERROR");
        for (var report : failed.path("reports")) {
            ((ObjectNode) report).put("status", "ERROR").putNull("asOf");
            for (var statement : report.path("statements")) {
                ((ObjectNode) statement).put("status", "ERROR").putNull("data").put("errorCode", "UPSTREAM_ERROR");
            }
        }
        Map<String, String> cached = new HashMap<>();
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> body = new AtomicReference<>(failed.toString());
        DataServiceClient client = client(cached, requests, body);
        var response = AShareFinancialsResponse.fromJson(mapper.readTree(client.getFinancialReports(expected.code(), expected.period(), expected.requestedYears())));
        assertThat(response.status()).isEqualTo(AShareFinancialsResponse.Status.ERROR);
        assertThat(response.asOf()).isNull();
        assertThat(response.reports()).hasSize(2);
        assertThat(response.reports()).allSatisfy(report ->
                assertThat(report.statements().all()).allSatisfy(statement -> assertThat(statement.data()).isNull()));
        assertThat(cached).isEmpty();

        ObjectNode legacy = fixture.deepCopy();
        legacy.remove("schemaVersion");
        body.set(legacy.toString());
        assertThat(mapper.readTree(client.getFinancialReports(expected.code(), expected.period(), expected.requestedYears())).path("errorCode").textValue())
                .isEqualTo("INVALID_DATA_SERVICE_RESPONSE");
        body.set(fixture.toString());
        assertThat(mapper.readTree(client.getFinancialReports(expected.code(), expected.period(), expected.requestedYears() + 1)).path("errorCode").textValue())
                .isEqualTo("INVALID_DATA_SERVICE_RESPONSE");
        assertThat(cached).isEmpty();
        assertThat(requests).hasValue(3);
    }

    private ObjectNode profit(ObjectNode response) { return (ObjectNode) response.path("reports").get(0).path("statements").path("profit").path("data"); }

    private ObjectNode fixture() throws Exception {
        return (ObjectNode) mapper.readTree(Files.readString(Path.of("../stocksage-data-service/tests/fixtures/a-share-financials-v1.json")));
    }

    @SuppressWarnings("unchecked")
    private DataServiceClient client(Map<String, String> cached, AtomicInteger requests, AtomicReference<String> body) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(call -> cached.get(call.getArgument(0, String.class)));
        doAnswer(call -> { cached.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(values).set(anyString(), anyString(), any(Duration.class));
        DataServiceClient client = new DataServiceClient("http://unused", 1000, 1000, 1000, 1, new ToolResultCache(redis), mapper);
        ReflectionTestUtils.setField(client, "webClient", WebClient.builder().exchangeFunction(request -> {
            requests.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json").body(body.get()).build());
        }).build());
        return client;
    }
}
