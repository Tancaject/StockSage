package com.stocksage.service;

import com.stocksage.model.entity.ResearchMemoryEntry;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Lazily owns the versioned, tenant-filtered Milvus research-memory collection.
 * It is never initialized while index/retrieve flags remain disabled.
 */
@Component
public class ResearchMemoryVectorIndex {

    private final ObjectProvider<MilvusServiceClient> clientProvider;
    private final EmbeddingModel embeddingModel;
    private final ResearchMemoryProperties properties;
    private final String databaseName;
    private final int embeddingDimension;
    private volatile MilvusVectorStore vectorStore;

    public ResearchMemoryVectorIndex(
            ObjectProvider<MilvusServiceClient> clientProvider,
            EmbeddingModel embeddingModel,
            ResearchMemoryProperties properties,
            @Value("${spring.ai.vectorstore.milvus.database-name:default}") String databaseName,
            @Value("${spring.ai.vectorstore.milvus.embedding-dimension:1024}") int embeddingDimension
    ) {
        this.clientProvider = clientProvider;
        this.embeddingModel = embeddingModel;
        this.properties = properties;
        this.databaseName = databaseName;
        this.embeddingDimension = embeddingDimension;
    }

    public void index(ResearchMemoryEntry entry) {
        Document document = new Document(
                vectorId(entry.getId()),
                entry.getMemoryText(),
                Map.of(
                        "entry_id", entry.getId(),
                        "tenant_key", tenantKey(entry.getUserId()),
                        "ticker", safeTicker(entry.getTicker()),
                        "source_type", entry.getSourceType()
                )
        );
        // One entry per report event; this is always below the provider batch limit of 10.
        store().add(List.of(document));
    }

    public List<Hit> search(String userId, String ticker, String query, int topK) {
        StringBuilder filter = new StringBuilder("tenant_key == '")
                .append(tenantKey(userId)).append("'");
        String normalizedTicker = safeTicker(ticker);
        if (!normalizedTicker.isBlank()) {
            filter.append(" && ticker == '").append(normalizedTicker).append("'");
        }
        return store().similaritySearch(SearchRequest.builder()
                        .query(query)
                        .topK(Math.max(1, Math.min(3, topK)))
                        .similarityThreshold(0.0)
                        .filterExpression(filter.toString())
                        .build())
                .stream()
                .map(document -> new Hit(
                        parseEntryId(document),
                        document.getScore() == null ? 0.0 : document.getScore()
                ))
                .filter(hit -> hit.entryId() != null)
                .toList();
    }

    public void delete(Long entryId) {
        if (entryId != null) {
            store().delete(List.of(vectorId(entryId)));
        }
    }

    private MilvusVectorStore store() {
        MilvusVectorStore current = vectorStore;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (vectorStore == null) {
                MilvusServiceClient client = clientProvider.getIfAvailable();
                if (client == null) {
                    throw new IllegalStateException("MILVUS_CLIENT_UNAVAILABLE");
                }
                MilvusVectorStore created = MilvusVectorStore.builder(client, embeddingModel)
                        .databaseName(databaseName)
                        .collectionName(properties.getCollectionName())
                        .embeddingDimension(embeddingDimension)
                        .indexType(IndexType.IVF_FLAT)
                        .metricType(MetricType.COSINE)
                        .initializeSchema(true)
                        .build();
                try {
                    created.afterPropertiesSet();
                } catch (Exception error) {
                    throw new IllegalStateException("RESEARCH_MEMORY_COLLECTION_INIT_FAILED", error);
                }
                vectorStore = created;
            }
            return vectorStore;
        }
    }

    private Long parseEntryId(Document document) {
        Object value = document.getMetadata().get("entry_id");
        try {
            return value == null ? null : Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String vectorId(Long entryId) {
        return "rm-" + entryId;
    }

    private String safeTicker(String ticker) {
        String value = ticker == null ? "" : ticker.trim().toUpperCase();
        return value.matches("[A-Z0-9.\\-]{1,16}") ? value : "";
    }

    static String tenantKey(String userId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((userId == null ? "" : userId.trim()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception error) {
            throw new IllegalStateException("SHA256_UNAVAILABLE", error);
        }
    }

    public record Hit(Long entryId, double score) {
    }
}
