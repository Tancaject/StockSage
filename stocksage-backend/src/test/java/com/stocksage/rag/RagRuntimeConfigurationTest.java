package com.stocksage.rag;

import com.alibaba.cloud.ai.dashscope.rerank.DashScopeRerankModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.repository.VectorDocumentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RagRuntimeConfigurationTest {

    @Test
    void unsupportedEmbeddingBeanRemainsUnknownWithoutCallingProvider() {
        var embedding = mock(org.springframework.ai.embedding.EmbeddingModel.class);
        RagService service = new RagService(mock(VectorStore.class), mock(QueryRewriter.class),
                mock(DashScopeReranker.class), mock(KeywordSearchService.class),
                mock(VectorDocumentRepository.class), new ObjectMapper(), embedding);
        assertEquals("UNKNOWN", ((Map<?, ?>) service.runtimeConfiguration().get("embedding"))
                .get("configurationStatus"));
        verifyNoInteractions(embedding);
    }

    @Test
    @SuppressWarnings("unchecked")
    void snapshotUsesBoundValuesAndEffectiveCandidateLimitWithoutResolvingProvider() throws Exception {
        ObjectProvider<DashScopeRerankModel> provider = mock(ObjectProvider.class);
        DashScopeReranker reranker = new DashScopeReranker(provider, 2);
        ReflectionTestUtils.setField(reranker, "enabled", true);
        ReflectionTestUtils.setField(reranker, "modelName", "test-reranker");
        ObjectMapper mapper = new ObjectMapper();
        RagService service = new RagService(mock(VectorStore.class), mock(QueryRewriter.class), reranker,
                mock(KeywordSearchService.class), mock(VectorDocumentRepository.class), mapper,
                new LocalEmbeddingModel("http://localhost:1", "actual-local", Duration.ofSeconds(9), 128));
        ReflectionTestUtils.setField(service, "topK", 7);
        ReflectionTestUtils.setField(service, "rerankCandidateTopK", 3);
        ReflectionTestUtils.setField(service, "similarityThreshold", 0.42);
        ReflectionTestUtils.setField(service, "hybridSearchEnabled", true);
        ReflectionTestUtils.setField(service, "keywordTopK", 11);
        ReflectionTestUtils.setField(service, "rrfK", 37);
        ReflectionTestUtils.setField(service, "metadataFilterEnabled", false);

        Map<String, Object> snapshot = service.runtimeConfiguration();
        var retrieval = new java.util.LinkedHashMap<>(snapshot);
        var embedding = new java.util.LinkedHashMap<>((Map<String, Object>) retrieval.remove("embedding"));
        var identity = (Map<String, Object>) embedding.remove("providerIdentity");
        assertEquals("OLLAMA_EMBED", identity.get("protocol"));
        assertEquals("API_CONSTRUCTION", identity.get("scope"));
        assertEquals(1, ((Map<?, ?>) identity.get("base")).get("port"));
        assertEquals(Map.of("provider", "ollama", "model", "actual-local", "dimensions", 128,
                "timeoutMillis", 9000L, "maxConcurrentCalls", 2, "unknownProviderRevision", true), embedding);
        retrieval.remove("queryRewrite");
        retrieval.remove("keywordSearch");
        assertEquals(Map.of("topK", 7, "candidateTopK", 7, "configuredRerankCandidateTopK", 3,
                "similarityThreshold", 0.42, "hybridSearchEnabled", true, "keywordTopK", 11,
                "rrfK", 37, "metadataFilterEnabled", false,
                "reranker", Map.of("enabled", true, "model", "test-reranker", "returnDocuments", true,
                        "topN", 7, "maxConcurrentCalls", 2)), retrieval);
        assertEquals("actual-local", mapper.readTree(mapper.writeValueAsString(snapshot))
                .path("embedding").path("model").asText());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.put("topK", 99));
        assertThrows(UnsupportedOperationException.class,
                () -> ((Map<String, Object>) snapshot.get("reranker")).put("topN", 99));

        ReflectionTestUtils.setField(service, "rerankCandidateTopK", 20);
        assertEquals(20, service.runtimeConfiguration().get("candidateTopK"));
        ReflectionTestUtils.setField(reranker, "enabled", false);
        assertEquals(7, service.runtimeConfiguration().get("candidateTopK"));
        assertEquals(true, ((Map<String, Object>) snapshot.get("reranker")).get("enabled"));
        verifyNoInteractions(provider);
    }
}
