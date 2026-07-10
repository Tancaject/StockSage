package com.stocksage.rag;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 完整检索管线快照。
 *
 * <p>{@code RagService.retrieve()} 只向对话暴露最终上下文；
 * {@code retrieveForEval()} 返回本记录，使评估脚本可以分别打分查询改写、
 * 元数据过滤、混合融合、重排和父级扩展。</p>
 *
 * @param originalQuery 用户原始问题
 * @param rewrittenQuery 改写后用于检索的查询文本
 * @param filterExpression 从问题中提取出的过滤表达式
 * @param hybridSearchEnabled 当前检索是否启用关键词混合召回
 * @param rerankEnabled 当前检索是否启用重排
 * @param topK 最终上下文数量上限
 * @param candidateTopK 候选阶段数量上限
 * @param vectorCandidates 向量召回候选
 * @param keywordCandidates 关键词召回候选
 * @param fusedCandidates 混合融合后的候选
 * @param rerankedContexts 重排后的上下文
 * @param finalContexts 最终注入模型的上下文
 */
public record RagRetrievalEvaluation(
        String originalQuery,
        String rewrittenQuery,
        String filterExpression,
        boolean hybridSearchEnabled,
        boolean rerankEnabled,
        int topK,
        int candidateTopK,
        List<Document> vectorCandidates,
        List<Document> keywordCandidates,
        List<Document> fusedCandidates,
        List<Document> rerankedContexts,
        List<Document> finalContexts
) {
}
