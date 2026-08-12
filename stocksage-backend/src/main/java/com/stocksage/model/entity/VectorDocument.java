package com.stocksage.model.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 向量库文档的关系型镜像。
 *
 * <p>Milvus 保存用于检索的向量；该表保存全文和 JSON 元数据，
 * 使子向量命中后可以回补父级切片。</p>
 */
@Data
@Entity
@Table(name = "vector_documents")
public class VectorDocument {

    /** 关系型数据库自增主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 原始文档名称，用于去重、展示和回溯资料来源。 */
    @Column(name = "doc_name", length = 256, nullable = false)
    private String docName;

    /** 当前切片在原始文档中的序号。 */
    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;

    /** 文本预览，供列表页或调试日志快速展示。 */
    @Column(name = "content_preview", length = 512)
    private String contentPreview;

    /** 完整切片文本，RAG 最终上下文回填时读取该字段。 */
    @Column(name = "content_full", columnDefinition = "TEXT")
    private String contentFull;

    /** JSON 格式元数据，例如来源类型、ticker、发布日期或父级切片信息。 */
    @Column(columnDefinition = "JSON")
    private String metadata;

    /** 向量库中的向量 ID，字段名沿用历史 redis_key 以兼容已有表结构。 */
    @Column(name = "redis_key", length = 128)
    private String vectorId;

    /** 文档切片写入 MySQL 的时间。 */
    private LocalDateTime createdAt;

    /**
     * 新增记录前补齐创建时间。
     *
     * <p>该时间用于过期清理和排查知识摄取批次，不依赖调用方手动传入。</p>
     * 该方法由 JPA 自动调用。
     */
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
