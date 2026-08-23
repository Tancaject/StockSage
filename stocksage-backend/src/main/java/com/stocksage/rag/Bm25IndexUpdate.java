package com.stocksage.rag;

import java.util.List;

/**
 * MySQL 文档事务提交后，对 Lucene BM25 派生索引执行的变更快照。
 *
 * <p>事件只携带不可变数据，不携带仍受 JPA 管理的实体，避免提交后监听器读取到
 * 已关闭会话或后续可变状态。</p>
 */
public record Bm25IndexUpdate(
        List<String> deletedDocumentIds,
        List<IndexedDocument> upsertedDocuments) {

    public Bm25IndexUpdate {
        deletedDocumentIds = deletedDocumentIds == null ? List.of() : List.copyOf(deletedDocumentIds);
        upsertedDocuments = upsertedDocuments == null ? List.of() : List.copyOf(upsertedDocuments);
    }

    /** 单个 Lucene 文档所需的稳定快照。 */
    public record IndexedDocument(String documentId, String content, String metadataJson) {
        public IndexedDocument {
            documentId = documentId == null ? "" : documentId.trim();
            content = content == null ? "" : content;
            metadataJson = metadataJson == null ? "{}" : metadataJson;
        }
    }
}
