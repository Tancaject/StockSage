package com.stocksage.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 仅供评估和调试暴露的中间检索状态。
 *
 * <p>生产对话只消费最终上下文；该 DTO 保留向量、关键词、融合和重排候选，
 * 便于排序实验观察。</p>
 *
 * @param originalQuery 用户或评测集提供的原始问题
 * @param rewrittenQuery 查询改写器输出的检索词
 * @param filterExpression 元数据过滤表达式
 * @param hybridSearchEnabled 是否启用向量与关键词混合检索
 * @param rerankEnabled 是否启用重排模型
 * @param topK 最终返回给生成模型的上下文数量
 * @param candidateTopK 候选阶段保留的文档数量
 * @param vectorCandidateCount 向量检索候选数量
 * @param keywordCandidateCount 关键词检索候选数量
 * @param fusedCandidateCount 融合后的候选数量
 * @param rerankedCount 重排后的候选数量
 * @param finalContextCount 最终上下文数量
 * @param vectorCandidates 向量检索候选明细
 * @param keywordCandidates 关键词检索候选明细
 * @param fusedCandidates 融合候选明细
 * @param rerankedContexts 重排后的上下文明细
 */
public record RagEvalRetrievalDetails(
        @JsonProperty("original_query")
        String originalQuery,

        @JsonProperty("rewritten_query")
        String rewrittenQuery,

        @JsonProperty("filter_expression")
        String filterExpression,

        @JsonProperty("hybrid_search_enabled")
        boolean hybridSearchEnabled,

        @JsonProperty("rerank_enabled")
        boolean rerankEnabled,

        @JsonProperty("top_k")
        int topK,

        @JsonProperty("candidate_top_k")
        int candidateTopK,

        @JsonProperty("vector_candidate_count")
        int vectorCandidateCount,

        @JsonProperty("keyword_candidate_count")
        int keywordCandidateCount,

        @JsonProperty("fused_candidate_count")
        int fusedCandidateCount,

        @JsonProperty("reranked_count")
        int rerankedCount,

        @JsonProperty("final_context_count")
        int finalContextCount,

        @JsonProperty("vector_candidates")
        List<RagEvalContext> vectorCandidates,

        @JsonProperty("keyword_candidates")
        List<RagEvalContext> keywordCandidates,

        @JsonProperty("fused_candidates")
        List<RagEvalContext> fusedCandidates,

        @JsonProperty("reranked_contexts")
        List<RagEvalContext> rerankedContexts
) {
}
