package com.stocksage.repository;

import com.stocksage.model.entity.VectorDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 向量文档切片仓储。
 *
 * <p>MySQL 中保存的是可读文本和元数据，向量本身由 Milvus/向量存储维护。
 * 该仓储负责根据文档名或向量 ID 做去重、回查和删除，保证知识库索引与关系型元数据保持同步。</p>
 */
public interface VectorDocumentRepository extends JpaRepository<VectorDocument, Long> {

    /** 判断某个文档名是否已经入库，用于避免重复摄取同一份资料。 */
    boolean existsByDocName(String docName);

    /** 根据向量库返回的 vectorId 找回对应文本切片和元数据。 */
    Optional<VectorDocument> findByVectorId(String vectorId);

    /** 批量删除指定 vectorId 对应的文本切片，通常与向量库删除操作配套执行。 */
    void deleteByVectorIdIn(Collection<String> vectorIds);

    /**
     * 读取某 ticker【最新一份 10-K】的"业务(Item 1. Business)"父级切片，供公司关系抽取使用。
     *
     * <p>命名的竞争对手/供应商基本都出在 Item 1. Business 的 Competition / 供应制造小节；
     * Item 1A 风险因素多为泛指、点不出具体公司，过"证据逐字命中"闸门后几乎全丢却最耗时，故不取。
     * 同时只锁定 filing_type=10-K 且 filing_date 最新的那个 accession，避免把历年年报与季报
     * （10-Q 的 "Item 1. Financial Statements" 会被 'Item 1.%' 误伤）一并卷入——既保证内容正确，
     * 又把切片数从几百压到个位/十位级，让抽取在数分钟内可控完成。
     * 只取父块（is_parent=true）以拿到完整上下文。元数据是 JSON 字段，用 JSON_EXTRACT 过滤。</p>
     */
    @Query(value = "SELECT * FROM vector_documents v "
            + "WHERE JSON_UNQUOTE(JSON_EXTRACT(v.metadata, '$.ticker')) = :ticker "
            + "AND JSON_UNQUOTE(JSON_EXTRACT(v.metadata, '$.is_parent')) = 'true' "
            + "AND JSON_UNQUOTE(JSON_EXTRACT(v.metadata, '$.filing_type')) = '10-K' "
            + "AND JSON_UNQUOTE(JSON_EXTRACT(v.metadata, '$.section')) = 'Item 1. Business' "
            + "AND JSON_UNQUOTE(JSON_EXTRACT(v.metadata, '$.accession')) = ("
            + "  SELECT JSON_UNQUOTE(JSON_EXTRACT(v2.metadata, '$.accession')) "
            + "  FROM vector_documents v2 "
            + "  WHERE JSON_UNQUOTE(JSON_EXTRACT(v2.metadata, '$.ticker')) = :ticker "
            + "  AND JSON_UNQUOTE(JSON_EXTRACT(v2.metadata, '$.is_parent')) = 'true' "
            + "  AND JSON_UNQUOTE(JSON_EXTRACT(v2.metadata, '$.filing_type')) = '10-K' "
            + "  AND JSON_UNQUOTE(JSON_EXTRACT(v2.metadata, '$.section')) = 'Item 1. Business' "
            + "  ORDER BY JSON_UNQUOTE(JSON_EXTRACT(v2.metadata, '$.filing_date')) DESC "
            + "  LIMIT 1) "
            + "ORDER BY v.id", nativeQuery = true)
    List<VectorDocument> findLatest10KBusinessParents(@Param("ticker") String ticker);
}
