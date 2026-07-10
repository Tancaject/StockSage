package com.stocksage.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * RAG 评估适配器返回的一条检索上下文。
 *
 * <p>它同时携带原始元数据和归一化顶层字段，
 * 使 Python 评估脚本无需重新解析后端特有的文档元数据，
 * 即可对来源、公告、章节和排序行为打分。</p>
 *
 * @param rank 当前上下文在最终列表中的排序，从 1 开始
 * @param content 注入模型或用于评分的切片正文
 * @param metadata 后端保留的原始文档元数据
 * @param ticker 归一化后的证券代码
 * @param filingType 归一化后的公告类型
 * @param filingDate 归一化后的公告日期
 * @param section 文档章节或语义分区
 * @param sourceId 来源 ID
 * @param docId 文档或切片 ID
 * @param score 向量/融合阶段分数
 * @param rerankScore 重排阶段分数
 */
public record RagEvalContext(
        @JsonProperty("rank")
        int rank,

        @JsonProperty("content")
        String content,

        @JsonProperty("metadata")
        Map<String, Object> metadata,

        @JsonProperty("ticker")
        String ticker,

        @JsonProperty("filing_type")
        String filingType,

        @JsonProperty("filing_date")
        String filingDate,

        @JsonProperty("section")
        String section,

        @JsonProperty("source_id")
        String sourceId,

        @JsonProperty("doc_id")
        String docId,

        @JsonProperty("score")
        Double score,

        @JsonProperty("rerank_score")
        Double rerankScore
) {
}
