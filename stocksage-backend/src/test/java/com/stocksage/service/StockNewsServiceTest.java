package com.stocksage.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stocksage.client.DataServiceClient;
import com.stocksage.client.SearchResponse;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StockNewsServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final DataServiceClient client = mock(DataServiceClient.class);
    private final StockNewsService service = new StockNewsService(client, mapper);

    @Test
    void typedSuccessPreservesDisplayFieldsAndActualSearchAndPublicationMetadata() throws Exception {
        ObjectNode fixture = fixture();
        fixture.put("requestedDays", 7);
        fixture.put("input", "AAPL");
        fixture.put("market", "US");
        fixture.put("resolvedCode", "AAPL");
        when(client.getStockNews("AAPL", 7)).thenReturn(fixture.toString());

        JsonNode response = mapper.valueToTree(service.getNews(" aapl ", 0));

        verify(client).getStockNews("AAPL", 7);
        assertThat(response.path("status").textValue()).isEqualTo("SUCCESS");
        assertThat(response.has("error")).isFalse();
        assertThat(response.path("empty").booleanValue()).isFalse();
        assertThat(response.path("count").intValue()).isEqualTo(3);
        for (String field : new String[]{"provider", "requestedDays", "requestedTimelimit", "effectiveTimelimit",
                "requestedDepth", "effectiveDepth", "fetchedAt", "input", "market", "resolvedCode"}) {
            assertThat(response.get(field)).as(field).isEqualTo(fixture.get(field));
        }
        JsonNode items = response.path("items");
        assertThat(items.findValuesAsText("publishedTimeKind")).containsExactlyInAnyOrder("INSTANT", "DATE", "UNKNOWN");
        for (int index = 0; index < items.size(); index++) {
            JsonNode expected = fixture.path("results").get(index);
            JsonNode actual = items.get(index);
            assertThat(actual.path("url")).isEqualTo(expected.path("link"));
            for (String field : new String[]{"title", "date", "snippet", "publishedTimeKind",
                    "publishedAt", "publishedDate", "publishedTimeBasis"}) {
                assertThat(actual.get(field)).as(field).isEqualTo(expected.get(field));
            }
        }
    }

    @Test
    void typedEmptyRemainsAnEmptyStateWithoutAnError() throws Exception {
        ObjectNode fixture = fixture();
        fixture.put("status", "EMPTY");
        fixture.put("count", 0);
        fixture.putArray("results");
        when(client.getStockNews("AAPL", 7)).thenReturn(fixture.toString());

        JsonNode response = mapper.valueToTree(service.getNews("AAPL", 7));

        assertThat(response.path("status").textValue()).isEqualTo("EMPTY");
        assertThat(response.has("error")).isFalse();
        assertThat(response.path("empty").booleanValue()).isTrue();
        assertThat(response.path("items").isEmpty()).isTrue();
        assertThat(response.path("fetchedAt")).isEqualTo(fixture.path("fetchedAt"));
    }

    @Test
    void typedErrorPreservesFailureMetadataAndReturnsAnActionableDisplayError() {
        SearchResponse failure = SearchResponse.failure("AAPL", "news", "w", "basic", 5, 7,
                "UPSTREAM_TIMEOUT", "新闻数据源请求超时", true);
        when(client.getStockNews("AAPL", 7)).thenReturn(failure.toJson());

        JsonNode response = mapper.valueToTree(service.getNews("AAPL", 7));

        assertThat(response.path("status").textValue()).isEqualTo("ERROR");
        assertThat(response.path("error").textValue()).contains("data-service /api/stock/news", "新闻数据源请求超时", "重试");
        assertThat(response.path("errorCode").textValue()).isEqualTo("UPSTREAM_TIMEOUT");
        assertThat(response.path("message").textValue()).isEqualTo(failure.message());
        assertThat(response.path("retryable").booleanValue()).isTrue();
        assertThat(response.path("provider").isNull()).isTrue();
        assertThat(response.path("effectiveTimelimit").isNull()).isTrue();
        assertThat(response.path("fetchedAt").textValue()).isEqualTo(failure.fetchedAt());
        assertThat(response.path("items").isEmpty()).isTrue();
    }

    @Test
    void unversionedResponseIsAnExplicitErrorInsteadOfAnEmptySuccess() {
        when(client.getStockNews("AAPL", 7)).thenReturn("{\"results\":[]}");

        JsonNode response = mapper.valueToTree(service.getNews("AAPL", 7));

        assertThat(response.path("status").textValue()).isEqualTo("ERROR");
        assertThat(response.path("error").textValue()).contains("Java 新闻服务", "接口版本", "重试");
    }

    private ObjectNode fixture() throws Exception {
        return (ObjectNode) mapper.readTree(Files.readString(
                Path.of("../stocksage-data-service/tests/fixtures/search-news-v1.json")));
    }
}
