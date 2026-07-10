package com.stocksage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.model.entity.VectorDocument;
import com.stocksage.repository.DocIndexRepository;
import com.stocksage.repository.DocIndexRepository.DocIndexEntry;
import com.stocksage.repository.VectorDocumentRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeIngestionServiceTest {

    @Test
    void preservesExplicitParentDocIdForParentChildLookup() {
        VectorStore vectorStore = mock(VectorStore.class);
        DocIndexRepository docIndexRepository = mock(DocIndexRepository.class);
        VectorDocumentRepository vectorDocumentRepository = mock(VectorDocumentRepository.class);
        KnowledgeIngestionService service = new KnowledgeIngestionService(
                vectorStore,
                docIndexRepository,
                vectorDocumentRepository,
                new ObjectMapper()
        );

        when(docIndexRepository.findByFilePath("edgar:AAPL:10-K:2025-10-31")).thenReturn(Optional.empty());

        Document parent = new Document(
                "parent filing context",
                Map.of("doc_id", "parent_aapl_risk", "is_parent", true)
        );
        Document child = new Document(
                "risk factor child chunk",
                Map.of("parent_vector_id", "parent_aapl_risk", "is_parent", false)
        );

        service.ingestDocuments(
                "edgar:AAPL:10-K:2025-10-31",
                "content-hash",
                List.of(parent, child),
                "edgar",
                null
        );

        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<List<Document>> vectorDocsCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(vectorStore).add(vectorDocsCaptor.capture());
        List<Document> vectorDocs = vectorDocsCaptor.getValue();
        assertThat(vectorDocs)
                .extracting(Document::getText)
                .containsExactly("risk factor child chunk");
        assertThat(vectorDocs.get(0).getMetadata())
                .containsEntry("parent_vector_id", "parent_aapl_risk");

        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<Iterable<VectorDocument>> rowsCaptor = ArgumentCaptor.forClass((Class) Iterable.class);
        verify(vectorDocumentRepository).saveAll(rowsCaptor.capture());
        List<VectorDocument> rows = StreamSupport.stream(rowsCaptor.getValue().spliterator(), false).toList();

        assertThat(rows)
                .extracting(VectorDocument::getVectorId)
                .contains("parent_aapl_risk");
        VectorDocument parentRow = rows.stream()
                .filter(row -> "parent_aapl_risk".equals(row.getVectorId()))
                .findFirst()
                .orElseThrow();
        assertThat(parentRow.getMetadata()).contains("\"doc_id\":\"parent_aapl_risk\"");

        ArgumentCaptor<DocIndexEntry> indexCaptor = ArgumentCaptor.forClass(DocIndexEntry.class);
        verify(docIndexRepository).save(indexCaptor.capture());
        assertThat(indexCaptor.getValue().chunkIds()).contains("parent_aapl_risk");
    }
}
