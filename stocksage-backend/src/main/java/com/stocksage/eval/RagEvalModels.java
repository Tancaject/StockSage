package com.stocksage.eval;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Map;

/** 回答级 RAG 评估接口的请求与响应载荷，由 evals/run_rag_eval.py 消费。 */
public final class RagEvalModels {

    private RagEvalModels() {
    }

    /**
     * 回答级 RAG 评估接口的请求体。
     *
     * @param question 评测问题，不能为空
     * @param referenceAnswer 数据集中的参考答案
     * @param answerable 该问题是否应当能被当前知识库回答
     * @param includeIntermediate 是否返回检索中间阶段，空值默认返回
     * @param maxContextChars 注入回答模型的最大上下文字数，空值或非正数使用默认值
     */
    public record RagEvalRequest(
            @JsonProperty("question")
            @NotBlank
            String question,

            @JsonProperty("reference_answer")
            String referenceAnswer,

            @JsonProperty("answerable")
            Boolean answerable,

            @JsonProperty("include_intermediate")
            Boolean includeIntermediate,

            @JsonProperty("max_context_chars")
            Integer maxContextChars
    ) {
        /**
         * 返回 includeIntermediate 的业务默认值。
         *
         * <p>评测脚本通常需要中间阶段来定位问题，因此未传值时默认开启。</p>
         *
         * @return 显式请求值；请求未提供时返回 true
         */
        public boolean includeIntermediateOrDefault() {
            return includeIntermediate == null || includeIntermediate;
        }

        /**
         * 返回最大上下文字数的业务默认值。
         *
         * <p>默认 6000 字符在保留足够证据和控制评测耗时之间做了折中。</p>
         *
         * @return 正整数上下文上限；请求值为空或非正数时返回 6000
         */
        public int maxContextCharsOrDefault() {
            if (maxContextChars == null || maxContextChars <= 0) {
                return 6000;
            }
            return maxContextChars;
        }
    }

    /**
     * {@code evals/run_rag_eval.py} 消费的稳定响应载荷。
     *
     * <p>响应包含改写后的查询、最终上下文、生成回答、引用映射和可选的中间检索阶段，
     * 便于评估定位质量退化发生在哪个环节。</p>
     *
     * @param traceId 本次评测请求的链路 ID，方便与日志或追踪面板关联
     * @param question 原始评测问题
     * @param referenceAnswer 数据集中的参考答案，用于离线评分
     * @param answerable 该问题在当前知识库中是否应当可回答
     * @param rewrittenQuery RAG 查询改写后的文本
     * @param filterExpression 从问题中解析出的元数据过滤表达式
     * @param retrievedContexts 最终注入回答模型的上下文片段
     * @param answer 后端生成的回答
     * @param citations 回答中抽取或映射到的引用信息
     * @param retrieval 可选的检索阶段细节，仅评测/调试使用
     */
    public record RagEvalResponse(
            @JsonProperty("trace_id")
            String traceId,

            @JsonProperty("question")
            String question,

            @JsonProperty("reference_answer")
            String referenceAnswer,

            @JsonProperty("answerable")
            Boolean answerable,

            @JsonProperty("rewritten_query")
            String rewrittenQuery,

            @JsonProperty("filter_expression")
            String filterExpression,

            @JsonProperty("retrieved_contexts")
            List<RagEvalContext> retrievedContexts,

            @JsonProperty("answer")
            String answer,

            @JsonProperty("citations")
            List<RagEvalCitation> citations,

            @JsonProperty("retrieval")
            RagEvalRetrievalDetails retrieval
    ) {
    }

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

    /**
     * 从评估回答中抽取的引用信息。
     *
     * <p>rank 会指回 {@link RagEvalContext} 返回的编号上下文，
     * 便于评估脚本打分引用精度和元数据准确性。</p>
     *
     * @param rank 引用指向的上下文排名
     * @param sourceId 来源 ID，用于跨上下文和原始文档定位
     * @param ticker 证券代码，便于评测脚本判断引用是否命中目标公司
     * @param filingType 公告或财报类型，例如 10-K、10-Q
     * @param filingDate 公告日期
     * @param section 引用所在章节或分区
     */
    public record RagEvalCitation(
            @JsonProperty("rank")
            int rank,

            @JsonProperty("source_id")
            String sourceId,

            @JsonProperty("ticker")
            String ticker,

            @JsonProperty("filing_type")
            String filingType,

            @JsonProperty("filing_date")
            String filingDate,

            @JsonProperty("section")
            String section
    ) {
    }

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
}
