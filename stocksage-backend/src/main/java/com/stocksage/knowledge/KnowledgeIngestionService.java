package com.stocksage.knowledge;

import com.stocksage.tool.ToolCallContext;
import com.stocksage.exception.ResearchBudgetExceededException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.model.dto.KnowledgeIngestionResult;
import com.stocksage.model.entity.VectorDocument;
import com.stocksage.rag.Bm25IndexUpdate;
import com.stocksage.rag.Bm25IndexUpdate.IndexedDocument;
import com.stocksage.repository.DocIndexRepository;
import com.stocksage.repository.DocIndexRepository.DocIndexEntry;
import com.stocksage.repository.VectorDocumentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 所有知识来源共用的摄取服务。
 *
 * <p>输入可以来自本地文档、SEC EDGAR 公告或对话触发的搜索片段。
 * 本服务负责归一化元数据、写入向量检索文档、保留关系型副本供父级切片查询，
 * 并记录来源哈希，以便后续摄取时跳过未变化文件。</p>
 *
 * <p>边界：向量库和 MySQL 不是同一事务资源；来源索引、稳定 chunkId 和下一次重摄取清理
 * 提供幂等补偿基础，但单次数据库事务成功不等价于跨存储原子提交。</p>
 */
@Slf4j
@Service
public class KnowledgeIngestionService {

    /**
     * DashScope text-embedding-v4 每批最多 10 条；本地模型也保持该上限，便于安全切换供应商。
     */
    private static final int EMBEDDING_BATCH_SIZE = 10;

    /** 写入并检索 Milvus 等 Spring AI 向量库。 */
    private final VectorStore vectorStore;
    /** 保存每个来源的内容哈希、chunkId 列表和 TTL。 */
    private final DocIndexRepository docIndexRepository;
    /** 保存全文和元数据镜像，供父块扩展、Lucene BM25 重建及审计。 */
    private final VectorDocumentRepository vectorDocumentRepository;
    /** 将文档元数据序列化为关系库 JSON。 */
    private final ObjectMapper objectMapper;
    /** 在 MySQL 事务提交后通知 Lucene 派生索引更新。 */
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate cleanupTransaction;

    @Autowired
    public KnowledgeIngestionService(
            VectorStore vectorStore,
            DocIndexRepository docIndexRepository,
            VectorDocumentRepository vectorDocumentRepository,
            ObjectMapper objectMapper,
            ApplicationEventPublisher eventPublisher,
            PlatformTransactionManager transactionManager) {
        this.vectorStore = vectorStore;
        this.docIndexRepository = docIndexRepository;
        this.vectorDocumentRepository = vectorDocumentRepository;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.cleanupTransaction = transactionManager == null ? null : new TransactionTemplate(transactionManager);
        if (cleanupTransaction != null) {
            cleanupTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        }
    }

    /** 保留无 Spring 容器单元测试使用的构造入口。 */
    public KnowledgeIngestionService(
            VectorStore vectorStore,
            DocIndexRepository docIndexRepository,
            VectorDocumentRepository vectorDocumentRepository,
            ObjectMapper objectMapper) {
        this(vectorStore, docIndexRepository, vectorDocumentRepository, objectMapper, null, null);
    }

    /** 可选语义去重的向量相似度阈值。 */
    @Value("${stocksage.rag.dedup-similarity-threshold:0.85}")
    private double dedupSimilarityThreshold;

    /**
     * 摄取单段文本，并默认开启语义去重。
     *
     * @param sourceId 来源稳定 ID
     * @param content 正文
     * @param metadata 检索元数据
     * @param sourceType 来源类型
     * @param ttl 临时来源有效期；null 表示永久
     * @return 摄取、跳过和重复计数
     */
    @Transactional
    public KnowledgeIngestionResult ingestText(
            String sourceId,
            String content,
            Map<String, Object> metadata,
            String sourceType,
            Duration ttl) {
        return ingestText(sourceId, content, metadata, sourceType, ttl, true);
    }

    /**
     * 摄取单段文本。
     *
     * <p>空文本会直接返回零切片结果；非空文本会包装成一个 Document 后进入统一摄取路径。</p>
     *
     * @param semanticDedupEnabled 是否调用向量相似搜索去重
     * @return 摄取结果
     */
    @Transactional
    public KnowledgeIngestionResult ingestText(
            String sourceId,
            String content,
            Map<String, Object> metadata,
            String sourceType,
            Duration ttl,
            boolean semanticDedupEnabled) {
        String normalizedContent = content == null ? "" : content.trim();
        if (normalizedContent.isBlank()) {
            return KnowledgeIngestionResult.ingested(sourceId, 0, 0, 0);
        }
        return ingestDocuments(
                sourceId,
                sha256(normalizedContent),
                List.of(new Document(normalizedContent, metadata == null ? Map.of() : metadata)),
                sourceType,
                ttl,
                semanticDedupEnabled
        );
    }

    /**
     * 主要摄取路径。
     *
     * <p>来源级去重由内容哈希处理；切片级去重使用精确哈希和可选语义搜索。
     * 父级切片会持久化以供后续扩展，但会刻意跳过向量化。</p>
     *
     * @param sourceId 来源稳定 ID
     * @param contentHash 来源内容/切片策略哈希
     * @param parsedDocuments 已切分文档
     * @param sourceType 来源类型
     * @param ttl 临时来源有效期
     * @return 摄取结果；此重载默认关闭跨来源语义去重
     */
    @Transactional
    public KnowledgeIngestionResult ingestDocuments(
            String sourceId,
            String contentHash,
            List<Document> parsedDocuments,
            String sourceType,
            Duration ttl) {
        return ingestDocuments(sourceId, contentHash, parsedDocuments, sourceType, ttl, false);
    }

    /**
     * 摄取一批已解析的文档切片。
     *
     * <p>该重载允许调用方选择是否启用语义去重，适合网页临时摄取与本地文档入库共用。</p>
     *
     * @param sourceId 来源稳定 ID
     * @param contentHash 来源内容/切片策略哈希
     * @param parsedDocuments 已切分文档
     * @param sourceType 来源类型
     * @param ttl 临时来源有效期；null 表示永久
     * @param semanticDedupEnabled 是否执行向量语义去重
     * @return 摄取、跳过和重复计数
     */
    @Transactional
    public KnowledgeIngestionResult ingestDocuments(
            String sourceId,
            String contentHash,
            List<Document> parsedDocuments,
            String sourceType,
            Duration ttl,
            boolean semanticDedupEnabled) {
        ToolCallContext.checkRunDeadline();
        String normalizedSourceId = normalize(sourceId, "unknown-source");
        String normalizedSourceType = normalize(sourceType, "manual");
        // 契约版本使旧版可能包含零向量的来源在下一次摄取时重新生成，旧版仍保留到成功提交。
        String normalizedHash = sha256("finite-embedding-v1:" + normalize(contentHash, sha256(normalizedSourceId)));
        List<Document> candidates = parsedDocuments == null ? List.of() : parsedDocuments;
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = ttl == null ? null : now.plus(ttl);

        // 来源哈希是成本最低的保护：如果来源未变化且 TTL 未过期，
        // 就不需要触碰 Milvus 或 MySQL。
        docIndexRepository.ensureSource(normalizedSourceId, normalizedSourceType);
        Optional<DocIndexEntry> existing = docIndexRepository.lockByFilePath(normalizedSourceId);
        ToolCallContext.checkRunDeadline();
        if (existing.isEmpty()) throw new IllegalStateException("RAG source registration disappeared: " + normalizedSourceId);
        removeUncommittedVectors(existing.get());
        if (existing.isPresent()
                && normalizedHash.equals(existing.get().fileHash())
                && !existing.get().isExpired(now)) {
            log.debug("Skipping unchanged RAG source: {}", normalizedSourceId);
            return KnowledgeIngestionResult.skippedUnchanged(normalizedSourceId, existing.get().chunkIds().size());
        }

        List<String> deletedChunkIds = existing
                .map(DocIndexEntry::chunkIds)
                .orElseGet(List::of);

        List<Document> documentsToAdd = new ArrayList<>();
        List<VectorDocument> metadataRows = new ArrayList<>();
        List<String> chunkIds = new ArrayList<>();
        Set<String> candidateChunkHashes = new LinkedHashSet<>();
        int duplicateCount = 0;

        for (int i = 0; i < candidates.size(); i++) {
            ToolCallContext.checkRunDeadline();
            Document candidate = candidates.get(i);
            String text = candidate == null ? "" : normalize(candidate.getText(), "");
            if (text.isBlank()) {
                continue;
            }

            String exactChunkHash = sha256(text);
            if (!candidateChunkHashes.add(exactChunkHash)
                    || (semanticDedupEnabled && isSemanticDuplicate(text, normalizedSourceId))) {
                duplicateCount++;
                continue;
            }

            Map<String, Object> metadata = new LinkedHashMap<>(candidate.getMetadata());
            String chunkId = resolveChunkId(metadata, buildChunkId(normalizedSourceId, normalizedHash, i));
            if (shouldVectorize(metadata) && metadata.containsKey("doc_id")) {
                chunkId = buildChunkId(normalizedSourceId, normalizedHash + ":" + chunkId, i);
            }
            // 这些归一化字段是 RagService、评估脚本和引用格式化共同依赖的检索契约。
            metadata.put("doc_id", chunkId);
            metadata.put("source_id", normalizedSourceId);
            metadata.put("source_type", normalizedSourceType);
            metadata.put("file_hash", normalizedHash);
            metadata.put("chunk_index", i);
            metadata.put("ingested_at", now.toString());
            if (expiresAt != null) {
                metadata.put("expires_at", expiresAt.toString());
            }

            if (shouldVectorize(metadata)) {
                documentsToAdd.add(new Document(chunkId, text, metadata));
            }
            chunkIds.add(chunkId);
            metadataRows.add(toVectorDocument(metadata, text, i));
        }

        // 写入不同版本的向量，不覆盖旧版；关系库提交后才切换可检索版本。
        addToVectorStoreInBatches(documentsToAdd);
        ToolCallContext.checkRunDeadline();
        if (!deletedChunkIds.isEmpty()) {
            vectorDocumentRepository.deleteByVectorIdIn(deletedChunkIds);
            vectorDocumentRepository.flush();
        }
        if (!metadataRows.isEmpty()) {
            vectorDocumentRepository.saveAll(metadataRows);
        }
        docIndexRepository.save(new DocIndexEntry(
                normalizedSourceId,
                normalizedHash,
                chunkIds,
                normalizedSourceType,
                now,
                expiresAt
        ));
        publishBm25Update(deletedChunkIds, metadataRows);
        cleanupAfterCommit(normalizedSourceId);

        log.info(
                "RAG source indexed: sourceId={}, sourceType={}, parsed={}, added={}, duplicates={}, semanticDedup={}, expiresAt={}",
                normalizedSourceId,
                normalizedSourceType,
                candidates.size(),
                documentsToAdd.size(),
                duplicateCount,
                semanticDedupEnabled,
                expiresAt
        );
        return KnowledgeIngestionResult.ingested(
                normalizedSourceId,
                candidates.size(),
                documentsToAdd.size(),
                duplicateCount
        );
    }

    /**
     * 删除已经过期的临时 RAG 来源。
     *
     * @return 本轮清理的来源数
     */
    @Transactional
    public int deleteExpiredDocuments() {
        List<DocIndexEntry> expired = docIndexRepository.findExpired(LocalDateTime.now());
        List<String> deletedChunkIds = new ArrayList<>();
        int cleaned = 0;
        for (DocIndexEntry candidate : expired) {
            DocIndexEntry entry = docIndexRepository.lockByFilePath(candidate.filePath()).orElseThrow();
            if (!entry.isExpired(LocalDateTime.now()) || entry.chunkIds().isEmpty()) continue;
            vectorDocumentRepository.deleteByVectorIdIn(entry.chunkIds());
            deletedChunkIds.addAll(entry.chunkIds());
            // 保留空来源记录作为可恢复的清理标记；向量删除失败不会丢失清理范围。
            docIndexRepository.save(new DocIndexEntry(entry.filePath(), "", List.of(), entry.sourceType(),
                    LocalDateTime.now(), entry.expiresAt()));
            cleanupAfterCommit(entry.filePath());
            cleaned++;
        }
        publishBm25Update(deletedChunkIds, List.of());
        return cleaned;
    }

    /** 清理只针对当前已提交版本之外的向量；调用时必须持有来源行锁。 */
    private void removeUncommittedVectors(DocIndexEntry entry) {
        FilterExpressionBuilder filters = new FilterExpressionBuilder();
        vectorStore.delete(filters.and(filters.eq("source_id", entry.filePath()),
                filters.ne("file_hash", entry.fileHash())).build());
    }

    private void cleanupAfterCommit(String sourceId) {
        var deadline = ToolCallContext.currentRunDeadline();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    if (deadline != null) deadline.remainingMillis();
                }

                @Override
                public void afterCommit() {
                    cleanupSourceVectors(sourceId);
                }
            });
        } else {
            cleanupSourceVectors(sourceId);
        }
    }

    private void cleanupSourceVectors(String sourceId) {
        try {
            Runnable cleanup = () -> docIndexRepository.lockByFilePath(sourceId)
                    .ifPresent(this::removeUncommittedVectors);
            if (cleanupTransaction == null) cleanup.run();
            else cleanupTransaction.executeWithoutResult(status -> cleanup.run());
        } catch (RuntimeException failure) {
            // 来源及其当前版本已持久化，下次摄取或维护会重试同一清理，不能谎报提交失败。
            log.warn("RAG vector cleanup pending for source={}; scheduled maintenance will retry", sourceId, failure);
        }
    }

    /** 同时收敛失败写入和提交后尚未删除的旧版；不修改任何当前来源内容。 */
    public void reconcileVectors() {
        for (String sourceId : docIndexRepository.findSourceIds()) {
            cleanupSourceVectors(sourceId);
        }
    }

    /**
     * 发布不可变的 BM25 变更快照；监听器只会在当前事务提交后应用。
     */
    private void publishBm25Update(List<String> deletedChunkIds, List<VectorDocument> upsertedRows) {
        if (eventPublisher == null) {
            return;
        }
        List<String> deletedIds = deletedChunkIds == null ? List.of() : deletedChunkIds;
        List<IndexedDocument> upserts = upsertedRows == null
                ? List.of()
                : upsertedRows.stream()
                        .map(row -> new IndexedDocument(
                                row.getVectorId(),
                                row.getContentFull() != null ? row.getContentFull() : row.getContentPreview(),
                                row.getMetadata()
                        ))
                        .toList();
        if (!deletedIds.isEmpty() || !upserts.isEmpty()) {
            eventPublisher.publishEvent(new Bm25IndexUpdate(deletedIds, upserts));
        }
    }

    /**
     * 使用向量相似度判断新切片是否与已有知识高度重复。
     *
     * <p>语义去重失败时选择保留候选切片，避免检索服务短暂异常导致知识丢失。</p>
     *
     * @param text 候选切片正文
     * @return true 表示已有高于阈值的向量命中
     */
    private boolean isSemanticDuplicate(String text, String sourceId) {
        if (dedupSimilarityThreshold <= 0) {
            return false;
        }
        try {
            SearchRequest request = SearchRequest.builder()
                    .query(text)
                    .topK(1)
                    .similarityThreshold(dedupSimilarityThreshold)
                    .filterExpression(new FilterExpressionBuilder().ne("source_id", sourceId).build())
                    .build();
            // 调用向量库 top-1 相似检索；失败时 fail-open 保留候选知识。
            return vectorStore.similaritySearch(request).stream()
                    .anyMatch(document -> vectorDocumentRepository.findByVectorId(document.getId()).isPresent());
        } catch (Exception e) {
            ResearchBudgetExceededException.rethrowIfPresent(e);
            ToolCallContext.checkRunDeadline();
            log.warn("Semantic dedup check failed; keeping candidate chunk. message={}", e.getMessage());
            return false;
        }
    }

    /**
     * 分小批添加向量，避免批量摄取时超过具体供应商的向量化限制。
     *
     * @param documents 实际需要向量化的子切片
     */
    private void addToVectorStoreInBatches(List<Document> documents) {
        for (int start = 0; start < documents.size(); start += EMBEDDING_BATCH_SIZE) {
            ToolCallContext.checkRunDeadline();
            int end = Math.min(start + EMBEDDING_BATCH_SIZE, documents.size());
            vectorStore.add(new ArrayList<>(documents.subList(start, end)));
        }
    }

    /**
     * 父级切片存入 MySQL 用于上下文扩展，子级切片才是实际向量检索目标。
     *
     * @param metadata 归一化切片元数据
     * @return true 表示应写入向量库
     */
    private boolean shouldVectorize(Map<String, Object> metadata) {
        Object isParent = metadata.get("is_parent");
        return !(isParent instanceof Boolean parent && parent);
    }

    /**
     * 将 Document 元数据和正文保存成 MySQL 镜像行。
     *
     * @return 可供全文/父块查询的 VectorDocument
     */
    private VectorDocument toVectorDocument(Map<String, Object> metadata, String text, int chunkIndex) {
        VectorDocument row = new VectorDocument();
        row.setDocName(String.valueOf(metadata.getOrDefault(
                "title",
                metadata.getOrDefault("source", metadata.getOrDefault("source_id", "unknown"))
        )));
        row.setChunkIndex(chunkIndex);
        row.setContentPreview(text.substring(0, Math.min(512, text.length())));
        row.setContentFull(text);
        row.setMetadata(toJson(metadata));
        row.setVectorId(String.valueOf(metadata.get("doc_id")));
        return row;
    }

    /**
     * 优先使用显式 doc_id，否则使用系统生成的 fallback。
     *
     * @return 稳定切片 ID
     */
    private String resolveChunkId(Map<String, Object> metadata, String fallback) {
        Object explicitDocId = metadata.get("doc_id");
        if (explicitDocId == null) {
            return fallback;
        }
        String normalizedDocId = String.valueOf(explicitDocId).trim();
        return normalizedDocId.isBlank() ? fallback : normalizedDocId;
    }

    /**
     * 根据来源、内容哈希和切片序号构造稳定切片 ID。
     *
     * @return 带 d_ 前缀的短 SHA-256 ID
     */
    private String buildChunkId(String sourceId, String contentHash, int chunkIndex) {
        return "d_" + sha256(sourceId + ":" + contentHash + ":" + chunkIndex).substring(0, 32);
    }

    /**
     * 计算 SHA-256 十六进制字符串。
     *
     * @param value 输入文本；null 按空串处理
     * @return 64 位十六进制摘要
     */
    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to calculate SHA-256", e);
        }
    }

    /**
     * 将元数据序列化为 JSON。
     */
    private String toJson(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize RAG metadata", e);
            return "{}";
        }
    }

    /**
     * 标准化字符串输入。
     */
    private String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
