package com.stocksage.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.client.DataServiceClient;
import com.stocksage.model.dto.KnowledgeIngestionResult;
import com.stocksage.knowledge.KnowledgeIngestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ScheduledRagCollectorTest {

    private DataServiceClient dataServiceClient;
    private KnowledgeIngestionService ingestionService;
    private ScheduledRagCollector collector;

    @BeforeEach
    void setUp() {
        dataServiceClient = mock(DataServiceClient.class);
        ingestionService = mock(KnowledgeIngestionService.class);
        collector = new ScheduledRagCollector(dataServiceClient, new ObjectMapper(), ingestionService);
        ReflectionTestUtils.setField(collector, "keywordsCsv", "market outlook");
        ReflectionTestUtils.setField(collector, "maxResults", 5);
        ReflectionTestUtils.setField(collector, "ttlDays", 7L);
        ReflectionTestUtils.setField(collector, "minContentLength", 1);
    }

    @Test
    void booleanFalseErrorIngestsWithExistingSourceKeyAndTtl() {
        when(dataServiceClient.webSearch("market outlook", 5)).thenReturn(searchResponse("false"));
        String sourceKey = "scheduled:" + KnowledgeIngestionService.sha256(
                "market outlook|https://example.com/news|Market update");
        KnowledgeIngestionResult result = KnowledgeIngestionResult.ingested(sourceKey, 1, 1, 0);
        when(ingestionService.ingestText(eq(sourceKey), anyString(), anyMap(),
                eq("scheduled"), eq(Duration.ofDays(7)), eq(true))).thenReturn(result);

        Map<String, Object> summary = collector.collectNow();

        assertEquals(1, summary.get("sourcesProcessed"));
        assertEquals(1, summary.get("chunksIngested"));
        assertEquals(List.of(result), summary.get("results"));
        verify(ingestionService).ingestText(sourceKey, """
                Title: Market update
                Query: market outlook
                Source: https://example.com/news

                Summary:
                Market summary from the search provider.
                """.trim(), Map.of(
                "source", "https://example.com/news",
                "doc_type", "scheduled_market_update",
                "title", "Market update",
                "knowledge_category", "market_update",
                "query", "market outlook",
                "link", "https://example.com/news",
                "origin", "scheduled_web_search"), "scheduled", Duration.ofDays(7), true);
    }

    @Test
    void booleanTrueAndLegacyStringErrorsSkipIngestionEvenWithResults() {
        when(dataServiceClient.webSearch("market outlook", 5)).thenReturn(
                searchResponse("true"), searchResponse("\"upstream unavailable\""));

        for (int attempt = 0; attempt < 2; attempt++) {
            Map<String, Object> summary = collector.collectNow();
            assertEquals(0, summary.get("sourcesProcessed"));
            assertEquals(0, summary.get("chunksIngested"));
            assertEquals(List.of(), summary.get("results"));
        }
        verifyNoInteractions(ingestionService);
    }

    private String searchResponse(String error) {
        return """
                {"error":%s,"results":[{
                  "title":"Market update",
                  "link":"https://example.com/news",
                  "snippet":"Market summary from the search provider.",
                  "date":"2026-09-25",
                  "source":"Example News"
                }]}
                """.formatted(error);
    }
}
