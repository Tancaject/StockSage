package com.stocksage.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * {@code rag-eval/run_rag_eval.py} 消费的稳定响应载荷。
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
