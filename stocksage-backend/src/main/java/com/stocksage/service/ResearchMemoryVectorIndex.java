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
 * 延迟初始化、按租户过滤的 Milvus 研究记忆索引。
 *
 * <p>{@link ResearchMemoryService} 负责数据库真源和补偿，本类只把单条记忆转为向量 Document，
 * 执行 tenant/ticker 过滤检索与确定性 ID 删除。index/retrieve 均未启用时不会创建集合。</p>
 */
@Component
public class ResearchMemoryVectorIndex {

    /** 延迟取得可选 Milvus 客户端，避免关闭功能时启动依赖外部服务。 */
    private final ObjectProvider<MilvusServiceClient> clientProvider;
    /** Spring AI 文本嵌入模型。 */
    private final EmbeddingModel embeddingModel;
    /** 集合名和功能开关配置。 */
    private final ResearchMemoryProperties properties;
    /** Milvus 数据库名。 */
    private final String databaseName;
    /** 必须与嵌入模型输出一致的向量维度。 */
    private final int embeddingDimension;
    /** 首次使用后安全发布的 MilvusVectorStore。 */
    private volatile MilvusVectorStore vectorStore;

    /**
     * 注入 Milvus、嵌入模型和集合配置，不在构造阶段连接外部服务。
     *
     * @param clientProvider 可选 Milvus 客户端提供器
     * @param embeddingModel 嵌入模型
     * @param properties 研究记忆配置
     * @param databaseName Milvus 数据库名
     * @param embeddingDimension 向量维度
     */
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

    /**
     * 以 {@code rm-{entryId}} 确定性 ID 写入一条记忆向量。
     *
     * @param entry 已持久化且待索引的记忆实体
     */
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
        // 调用 MilvusVectorStore.add 触发嵌入并写入；单条批次低于 provider 每批 10 条上限。
        store().add(List.of(document));
    }

    /**
     * 在哈希 tenant_key 内按可选 ticker 检索相似记忆。
     *
     * @param userId 当前用户 ID
     * @param ticker 可选 ticker
     * @param query 当前问题
     * @param topK 初始候选数，限制在 1 至 100；最终注入数量由服务层策略控制
     * @return 记忆行 ID 与相似度；最终可见性仍由数据库回查决定
     */
    public List<Hit> search(String userId, String ticker, String query, int topK) {
        StringBuilder filter = new StringBuilder("tenant_key == '")
                .append(tenantKey(userId)).append("'");
        String normalizedTicker = safeTicker(ticker);
        if (!normalizedTicker.isBlank()) {
            filter.append(" && ticker == '").append(normalizedTicker).append("'");
        }
        return store().similaritySearch(SearchRequest.builder()
                        .query(query)
                        .topK(Math.max(1, Math.min(100, topK)))
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

    /**
     * 按确定性向量 ID 删除记忆。
     *
     * @param entryId 数据库记忆 ID；null 时无操作
     */
    public void delete(Long entryId) {
        if (entryId != null) {
            store().delete(List.of(vectorId(entryId)));
        }
    }

    /** 双重检查延迟创建版本化 Milvus 集合。 */
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
                // 调用 Spring AI Milvus builder 创建 COSINE/IVF_FLAT 集合并初始化 schema。
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

    /** 从命中文档元数据恢复数据库 entry_id。 */
    private Long parseEntryId(Document document) {
        Object value = document.getMetadata().get("entry_id");
        try {
            return value == null ? null : Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** 构造幂等向量主键。 */
    private String vectorId(Long entryId) {
        return "rm-" + entryId;
    }

    /** 收敛可安全写入过滤表达式的 ticker 字符集。 */
    private String safeTicker(String ticker) {
        String value = ticker == null ? "" : ticker.trim().toUpperCase();
        return value.matches("[A-Z0-9.\\-]{1,16}") ? value : "";
    }

    /** 对用户 ID 做 SHA-256，避免把原始账号标识写入向量元数据。 */
    static String tenantKey(String userId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((userId == null ? "" : userId.trim()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception error) {
            throw new IllegalStateException("SHA256_UNAVAILABLE", error);
        }
    }

    /**
     * 向量检索命中项。
     *
     * @param entryId 对应的数据库记忆 ID
     * @param score Milvus 返回的相似度
     */
    public record Hit(Long entryId, double score) {
    }
}
