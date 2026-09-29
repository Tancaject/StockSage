package com.stocksage.ibkr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stocksage.client.DataServiceClient;
import com.stocksage.tool.MarketTools;
import com.stocksage.service.KLinePayloadMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

class IbkrReadOnlyServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void historicalBarsReturnsLoginGuidanceWhenGatewaySessionIsUnauthorized() throws Exception {
        IbkrProperties properties = new IbkrProperties();
        properties.setEnabled(true);
        IbkrWebApiClient client = mock(IbkrWebApiClient.class);
        when(client.get(anyUriFunction())).thenThrow(unauthorized());
        IbkrReadOnlyService service = new IbkrReadOnlyService(
                properties,
                client,
                new IbkrInstrumentResolver(),
                objectMapper
        );

        IbkrHistoricalResponse result = service.getHistoricalBars("NVDA", "6m", "1d");

        JsonNode payload = objectMapper.valueToTree(result);
        assertThat(payload.path("error").asBoolean()).isTrue();
        assertThat(result.errorCode()).isEqualTo("IBKR_AUTH_REQUIRED");
        assertThat(result.retryable()).isFalse();
        assertThat(payload.path("message").asText())
                .contains("Gateway session is not authenticated")
                .contains("https://localhost:5000");
    }

    @Test
    void historicalBarsReturnsLoginGuidanceWhenGatewayRejectsContractSearch() throws Exception {
        IbkrProperties properties = new IbkrProperties();
        properties.setEnabled(true);
        IbkrWebApiClient client = mock(IbkrWebApiClient.class);
        when(client.get(anyUriFunction())).thenThrow(badRequest());
        IbkrReadOnlyService service = new IbkrReadOnlyService(
                properties,
                client,
                new IbkrInstrumentResolver(),
                objectMapper
        );

        IbkrHistoricalResponse result = service.getHistoricalBars("NVDA", "6m", "1d");

        JsonNode payload = objectMapper.valueToTree(result);
        assertThat(payload.path("error").asBoolean()).isTrue();
        assertThat(result.errorCode()).isEqualTo("IBKR_REQUEST_REJECTED");
        assertThat(payload.path("message").asText())
                .contains("Gateway rejected the request")
                .contains("https://localhost:5000");
    }

    @Test
    void historicalBarsReturnsRecoverableGuidanceWhenHistoryEndpointReturnsServerError() throws Exception {
        IbkrProperties properties = new IbkrProperties();
        properties.setEnabled(true);
        IbkrWebApiClient client = mock(IbkrWebApiClient.class);
        when(client.get(anyUriFunction()))
                .thenReturn(objectMapper.readTree("""
                        [
                          {"symbol": "SNDK", "conid": "12345", "description": "NASDAQ"}
                        ]
                        """))
                .thenThrow(internalServerError());
        IbkrReadOnlyService service = new IbkrReadOnlyService(
                properties,
                client,
                new IbkrInstrumentResolver(),
                objectMapper
        );

        IbkrHistoricalResponse result = service.getHistoricalBars("SNDK", "24h", "5min");

        JsonNode payload = objectMapper.valueToTree(result);
        assertThat(payload.path("error").asBoolean()).isTrue();
        assertThat(result.errorCode()).isEqualTo("IBKR_HTTP_500");
        assertThat(result.retryable()).isTrue();
        assertThat(payload.path("message").asText())
                .contains("IBKR Client Portal Gateway returned 500")
                .contains("history endpoint")
                .contains("period=24h")
                .contains("daily range");
    }

    @Test
    void historicalBarsReturnsRecoverableGuidanceWhenGatewayTimesOut() throws Exception {
        IbkrProperties properties = new IbkrProperties();
        properties.setEnabled(true);
        IbkrWebApiClient client = mock(IbkrWebApiClient.class);
        when(client.get(anyUriFunction()))
                .thenReturn(objectMapper.readTree("""
                        [
                          {"symbol": "SNDK", "conid": "12345", "description": "NASDAQ"}
                        ]
                        """))
                .thenThrow(new RuntimeException(new TimeoutException("Did not observe any item within 10000ms")));
        IbkrReadOnlyService service = new IbkrReadOnlyService(
                properties,
                client,
                new IbkrInstrumentResolver(),
                objectMapper
        );

        IbkrHistoricalResponse result = service.getHistoricalBars("SNDK", "1y", "1d");

        JsonNode payload = objectMapper.valueToTree(result);
        assertThat(payload.path("error").asBoolean()).isTrue();
        assertThat(result.errorCode()).isEqualTo("IBKR_TIMEOUT");
        assertThat(result.retryable()).isTrue();
        assertThat(payload.path("message").asText())
                .contains("timed out")
                .contains("10000ms")
                .contains("Gateway");
    }

    @Test
    void historicalContractKeepsPricesUnitsAndTimestampsThroughToolAndChart() throws Exception {
        IbkrProperties properties = new IbkrProperties();
        properties.setEnabled(true);
        IbkrWebApiClient client = mock(IbkrWebApiClient.class);
        JsonNode history = historyFixture();
        List<URI> requests = new ArrayList<>();
        when(client.get(anyUriFunction())).thenAnswer(call -> {
            Function<UriBuilder, URI> uri = call.getArgument(0);
            URI request = uri.apply(UriComponentsBuilder.fromUriString("https://localhost"));
            requests.add(request);
            return request.getPath().endsWith("/search")
                    ? objectMapper.readTree("[{\"symbol\":\"NVDA\",\"conid\":\"12345\",\"description\":\"NASDAQ\"}]")
                    : history;
        });
        IbkrReadOnlyService service = new IbkrReadOnlyService(properties, client, new IbkrInstrumentResolver(), objectMapper);
        String raw = new MarketTools(mock(DataServiceClient.class), service, objectMapper)
                .getIbkrHistoricalBars("NVDA", "24h", "5min");
        IbkrHistoricalResponse result = IbkrHistoricalResponse.fromJson(objectMapper.readTree(raw));
        assertThat(result.status()).isEqualTo(IbkrHistoricalResponse.Status.SUCCESS);
        assertThat(result.currency()).isNull();
        assertThat(result.adjustment()).isNull();
        assertThat(result.delayed()).isNull();
        assertThat(result.asOf()).isEqualTo(Instant.ofEpochMilli(1747229700000L).toString());
        assertThat(result.fetchedAt()).isNotEqualTo(result.asOf());
        assertThat(result.count()).isEqualTo(2);
        assertThat(result.historyMetadata().points()).isEqualTo(1);
        assertThat(result.historyMetadata().priceFactor()).isEqualTo(100.0);
        assertThat(result.historyMetadata().mktDataDelay()).isEqualTo(17);
        assertThat(result.historyMetadata().outsideRth()).isFalse();
        assertThat(result.requestedOutsideRth()).isTrue();
        assertThat(requests).hasSize(2);
        assertThat(requests.get(1).getQuery()).contains("conid=12345", "period=24h", "bar=5min", "outsideRth=true", "source=Trades");
        Map<String, Object> chart = new KLinePayloadMapper(objectMapper)
                .toChartPayload("getIbkrHistoricalBars", raw, new Object[0]).orElseThrow();
        assertThat(chart).containsEntry("period", "5min").containsEntry("window", "24h")
                .containsEntry("timeKind", "EPOCH_MILLIS").containsEntry("volumeUnit", "UNKNOWN");
        Map<?, ?> last = (Map<?, ?>) ((List<?>) chart.get("points")).get(1);
        assertThat(last.get("close")).isEqualTo(101.0);
        assertThat(last.get("volume")).isEqualTo(5.0);
        assertThat(last.get("t")).isEqualTo(1747229700000L);
    }

    @Test
    void malformedRowsOrWrongTargetsCannotBecomeSuccessfulHistory() throws Exception {
        ObjectNode valid = (ObjectNode) historyFixture();
        JsonNode contract = objectMapper.readTree("{\"symbol\":\"NVDA\",\"conid\":12345}");
        IbkrInstrument instrument = IbkrInstrument.us("NVDA", "NVDA");
        assertThat(IbkrHistoricalResponse.fromGateway(valid.deepCopy().put("symbol", "AMD"), instrument, contract, "24h", "5min")
                .errorCode()).isEqualTo("TARGET_MISMATCH");
        assertThat(IbkrHistoricalResponse.fromGateway(objectMapper.readTree("{\"error\":\"Login required\"}"), instrument,
                objectMapper.createObjectNode(), "24h", "5min").errorCode()).isEqualTo("IBKR_UPSTREAM_ERROR");
        ObjectNode partial = valid.deepCopy();
        ((ObjectNode) partial.path("data").get(1)).putNull("c");
        ObjectNode fractionalTime = valid.deepCopy();
        ((ObjectNode) fractionalTime.path("data").get(0)).put("t", 1747229400000.5);
        for (ObjectNode invalid : List.of(partial, fractionalTime, valid.deepCopy().put("currency", true))) {
            IbkrHistoricalResponse response = IbkrHistoricalResponse.fromGateway(invalid, instrument, contract, "24h", "5min");
            assertThat(response.status()).isEqualTo(IbkrHistoricalResponse.Status.ERROR);
            assertThat(response.errorCode()).isEqualTo("INVALID_PROVIDER_DATA");
            assertThat(response.asOf()).isNull();
        }
        ObjectNode empty = valid.deepCopy();
        empty.set("data", objectMapper.createArrayNode());
        assertThat(IbkrHistoricalResponse.fromGateway(empty, instrument, contract, "24h", "5min").status())
                .isEqualTo(IbkrHistoricalResponse.Status.EMPTY);
    }

    @Test
    void disabledOrUnsupportedHistoryDoesNotAccessGateway() {
        IbkrProperties properties = new IbkrProperties();
        IbkrWebApiClient client = mock(IbkrWebApiClient.class);
        IbkrReadOnlyService service = new IbkrReadOnlyService(properties, client, new IbkrInstrumentResolver(), objectMapper);
        properties.setEnabled(false);
        assertThat(service.getHistoricalBars("NVDA", "24h", "5min").errorCode()).isEqualTo("IBKR_DISABLED");
        properties.setEnabled(true);
        assertThat(service.getHistoricalBars("600519", "24h", "5min").status()).isEqualTo(IbkrHistoricalResponse.Status.UNSUPPORTED);
        verifyNoInteractions(client);
    }

    private JsonNode historyFixture() throws Exception {
        try (var input = getClass().getResourceAsStream("/fixtures/ibkr-history.json")) {
            return objectMapper.readTree(input);
        }
    }

    @SuppressWarnings("unchecked")
    private Function<UriBuilder, URI> anyUriFunction() {
        return any(Function.class);
    }

    private WebClientResponseException unauthorized() {
        return WebClientResponseException.create(
                HttpStatus.UNAUTHORIZED.value(),
                "Unauthorized",
                HttpHeaders.EMPTY,
                new byte[0],
                StandardCharsets.UTF_8
        );
    }

    private WebClientResponseException badRequest() {
        return WebClientResponseException.create(
                HttpStatus.BAD_REQUEST.value(),
                "Bad Request",
                HttpHeaders.EMPTY,
                new byte[0],
                StandardCharsets.UTF_8
        );
    }

    private WebClientResponseException internalServerError() {
        return WebClientResponseException.create(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "Internal Server Error",
                HttpHeaders.EMPTY,
                "history backend rejected intraday request".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8
        );
    }
}
