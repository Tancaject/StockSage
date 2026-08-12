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

    /**
     * 判断某个文档名是否已有切片记录，供回归种子等简单去重路径使用。
     *
     * @param docName 原始文档名称
     * @return 至少存在一个同名切片时为 true
     */
    boolean existsByDocName(String docName);

    /**
     * 根据向量库返回的 ID 找回 MySQL 中的文本切片和元数据。
     *
     * @param vectorId 向量存储中的稳定 ID
     * @return 对应文本镜像；索引不一致或已删除时为空
     */
    Optional<VectorDocument> findByVectorId(String vectorId);

    /**
     * 批量删除指定向量 ID 的文本镜像。
     *
     * <p>调用方在事务中将其与向量库和 {@code doc_index} 清理配套执行。</p>
     *
     * @param vectorIds 要删除的向量 ID 集合
     */
    void deleteByVectorIdIn(Collection<String> vectorIds);

    /**
     * 读取某 ticker 最新一份 10-K 的“Item 1. Business”父级切片。
     *
     * <p>原生 SQL 从 JSON 元数据中选择 filing_date 最新的 accession，只返回父块并按主键排序。
     * 这样关系抽取既获得完整业务上下文，也不会混入历年年报、10-Q 或泛化风险章节。</p>
     *
     * @param ticker 已归一化的证券代码
     * @return 最新年报业务章节的父级切片；知识库无匹配资料时为空列表
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
