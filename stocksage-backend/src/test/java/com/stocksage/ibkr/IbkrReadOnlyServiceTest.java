package com.stocksage.ibkr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

        String result = service.getHistoricalBars("NVDA", "6m", "1d");

        JsonNode payload = objectMapper.readTree(result);
        assertThat(payload.path("error").asBoolean()).isTrue();
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

        String result = service.getHistoricalBars("NVDA", "6m", "1d");

        JsonNode payload = objectMapper.readTree(result);
        assertThat(payload.path("error").asBoolean()).isTrue();
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

        String result = service.getHistoricalBars("SNDK", "24h", "5min");

        JsonNode payload = objectMapper.readTree(result);
        assertThat(payload.path("error").asBoolean()).isTrue();
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

        String result = service.getHistoricalBars("SNDK", "1y", "1d");

        JsonNode payload = objectMapper.readTree(result);
        assertThat(payload.path("error").asBoolean()).isTrue();
        assertThat(payload.path("message").asText())
                .contains("timed out")
                .contains("10000ms")
                .contains("Gateway");
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
