package com.stocksage.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.client.DataServiceClient;
import com.stocksage.model.dto.KnowledgeIngestionResult;
import com.stocksage.rag.ContextualEnricher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EdgarIngestionServiceTest {

    @Test
    void budgetFailureEscapesFilingAndToolFallbackWithoutPublishing() {
        var failure = new com.stocksage.exception.ResearchBudgetExceededException(71L,
                com.stocksage.exception.ResearchBudgetExceededException.Reason.DEADLINE);
        when(dataServiceClient.getEdgarFilings("AAPL", "10-K", 1)).thenReturn(FILINGS_JSON);
        when(dataServiceClient.getEdgarFilingContent(anyString(), anyString()))
                .thenThrow(new IllegalStateException("wrapped", failure));
        var tools = new com.stocksage.tool.FundamentalsTools(dataServiceClient, service, new ObjectMapper());
        org.junit.jupiter.api.Assertions.assertSame(failure,
                org.junit.jupiter.api.Assertions.assertThrows(com.stocksage.exception.ResearchBudgetExceededException.class,
                        () -> tools.ingestCompanyFilings("AAPL", "10-K", 1)));
        org.mockito.Mockito.verifyNoInteractions(ingestionService);
    }

    private static final String FILINGS_JSON = """
            {"company_name":"Apple Inc.","filings":[
              {"document_url":"http://example.com/doc1","filing_date":"2023-11-03","accession_number":"0000320193-23-000106"}
            ]}
            """;

    private DataServiceClient dataServiceClient;
    private KnowledgeIngestionService ingestionService;
    private ContextualEnricher enricher;
    private EdgarIngestionService service;

    @BeforeEach
    void setUp() {
        dataServiceClient = mock(DataServiceClient.class);
        ingestionService = mock(KnowledgeIngestionService.class);
        enricher = mock(ContextualEnricher.class);
        service = new EdgarIngestionService(dataServiceClient, ingestionService, new ObjectMapper(), enricher);
        ReflectionTestUtils.setField(service, "parentChunkSize", 3000);
        ReflectionTestUtils.setField(service, "parentChunkOverlap", 300);
        ReflectionTestUtils.setField(service, "childChunkSize", 800);
        ReflectionTestUtils.setField(service, "childChunkOverlap", 120);
        ReflectionTestUtils.setField(service, "chunkingVersion", "edgar-v4-contextual-gist-child-vector");
        ReflectionTestUtils.setField(service, "contextualGranularity", "child");

        when(dataServiceClient.getEdgarFilings(anyString(), anyString(), anyInt())).thenReturn(FILINGS_JSON);
        when(ingestionService.ingestDocuments(anyString(), anyString(), anyList(), anyString(), isNull()))
                .thenReturn(KnowledgeIngestionResult.ingested("src", 2, 1, 0));
    }

    // ========== 分块策略 ==========

    @Test
    void currentConfigurationUsesSplitterBoundsAndDoesNotDescribeHistoricalDocuments() {
        ReflectionTestUtils.setField(service, "parentChunkSize", 100);
        ReflectionTestUtils.setField(service, "parentChunkOverlap", 900);
        ReflectionTestUtils.setField(service, "childChunkSize", -1);
        ReflectionTestUtils.setField(service, "childChunkOverlap", -1);
        ReflectionTestUtils.setField(service, "contextualGranularity", "PARENT");
        assertThat(service.runtimeConfiguration())
                .containsEntry("scope", "CURRENT_INGESTION_DEFAULTS")
                .containsEntry("parentSize", 100).containsEntry("parentOverlap", 50)
                .containsEntry("childSize", 1).containsEntry("childOverlap", 0)
                .containsEntry("contextualGranularity", "parent");
        verify(dataServiceClient, never()).getEdgarFilings(anyString(), anyString(), anyInt());
    }

    @Test
    void splitIntoChunksPrefersParagraphBoundary() {
        String firstParagraph = "A ".repeat(110).trim() + ".";
        String secondParagraph = "B ".repeat(110).trim() + ".";
        String text = firstParagraph + "\n\n" + secondParagraph;

        List<String> chunks = EdgarIngestionService.splitIntoChunks(text, firstParagraph.length() + 50, 0);

        assertThat(chunks).containsExactly(firstParagraph, secondParagraph);
    }

    @Test
    void splitIntoChunksMergesTinyTailWhenItFits() {
        String largeParagraph = "A ".repeat(150).trim() + ".";
        String tinyTail = "tail sentence.";
        String text = largeParagraph + "\n\n" + tinyTail;

        List<String> chunks = EdgarIngestionService.splitIntoChunks(text, largeParagraph.length() + 5, 0);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).contains(largeParagraph, tinyTail);
    }

    // ========== Contextual Retrieval 接入 ==========

    private void stubContent(String sectionContent) {
        String contentJson = """
                {"sections":[{"section":"Item 1A Risk Factors","content":"%s"}]}
                """.formatted(sectionContent);
        when(dataServiceClient.getEdgarFilingContent(anyString(), anyString())).thenReturn(contentJson);
    }

    private List<Document> capturedDocuments() {
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass((Class) List.class);
        verify(ingestionService).ingestDocuments(anyString(), anyString(), captor.capture(), eq("edgar"), isNull());
        return captor.getValue();
    }

    private static List<Document> childDocs(List<Document> all) {
        return all.stream()
                .filter(d -> Boolean.FALSE.equals(d.getMetadata().get("is_parent")))
                .toList();
    }

    @Test
    void contextualEnabledInjectsContextLineAndMetadata() {
        stubContent("Apple faces competitive risks in Greater China.");
        when(enricher.isEnabled()).thenReturn(true);
        when(enricher.generateGist(anyString(), anyString(), eq("child"), anyString(), anyString(),
                anyString(), anyString(), anyString()))
                .thenReturn("本段讨论大中华区竞争风险。");

        service.ingestFilings("AAPL", "10-K", 1);

        List<Document> children = childDocs(capturedDocuments());
        assertFalse(children.isEmpty());
        for (Document child : children) {
            assertTrue(child.getText().contains("Section: Item 1A Risk Factors\nContext: 本段讨论大中华区竞争风险。\n\n"),
                    "child embedding text should carry Context line after Section, got:\n" + child.getText());
            assertEquals("本段讨论大中华区竞争风险。", child.getMetadata().get("contextual_gist"));
        }
    }

    @Test
    void contextualDisabledKeepsV3PrefixAndNeverCallsEnricher() {
        stubContent("Apple faces competitive risks in Greater China.");
        when(enricher.isEnabled()).thenReturn(false);

        service.ingestFilings("AAPL", "10-K", 1);

        List<Document> children = childDocs(capturedDocuments());
        assertFalse(children.isEmpty());
        for (Document child : children) {
            assertFalse(child.getText().contains("Context:"));
            assertFalse(child.getMetadata().containsKey("contextual_gist"));
        }
        verify(enricher, never()).generateGist(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void gistFailureFallsBackToStructuralPrefixOnly() {
        stubContent("Apple faces competitive risks in Greater China.");
        when(enricher.isEnabled()).thenReturn(true);
        when(enricher.generateGist(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(null);

        service.ingestFilings("AAPL", "10-K", 1);

        List<Document> children = childDocs(capturedDocuments());
        assertFalse(children.isEmpty());
        for (Document child : children) {
            assertFalse(child.getText().contains("Context:"));
            assertFalse(child.getMetadata().containsKey("contextual_gist"));
            assertTrue(child.getText().contains("Section: Item 1A Risk Factors\n\n"));
        }
    }

    @Test
    void parentGranularityGeneratesOneGistSharedByChildren() {
        // 约 1500 字符 → 1 个父块、2+ 个子块
        stubContent("Apple operates in highly competitive markets worldwide. ".repeat(27).trim());
        ReflectionTestUtils.setField(service, "contextualGranularity", "parent");
        when(enricher.isEnabled()).thenReturn(true);
        when(enricher.generateGist(anyString(), anyString(), eq("parent"), anyString(), anyString(),
                anyString(), anyString(), anyString()))
                .thenReturn("本段概述全球市场竞争格局。");

        service.ingestFilings("AAPL", "10-K", 1);

        List<Document> children = childDocs(capturedDocuments());
        assertTrue(children.size() >= 2, "expect multiple children, got " + children.size());
        for (Document child : children) {
            assertEquals("本段概述全球市场竞争格局。", child.getMetadata().get("contextual_gist"));
        }
        // 父粒度：每个父块只调一次 LLM，而不是每个子块一次
        verify(enricher, times(1)).generateGist(anyString(), anyString(), eq("parent"), anyString(), anyString(),
                anyString(), anyString(), anyString());
    }
}
