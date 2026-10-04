package com.stocksage.rag;

import com.alibaba.cloud.ai.dashscope.rerank.DashScopeRerankModel;
import com.alibaba.cloud.ai.dashscope.rerank.DashScopeRerankOptions;
import com.alibaba.cloud.ai.document.DocumentWithScore;
import com.alibaba.cloud.ai.model.RerankRequest;
import com.alibaba.cloud.ai.model.RerankResponse;
import com.stocksage.exception.ResearchBudgetExceededException;
import com.stocksage.tool.ToolCallContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * RAG 检索中可选的 DashScope 重排阶段。
 *
 * <p>失败时会回退到融合检索器顺序，因为对话和评估运行中的检索应当优雅降级。</p>
 */
@Slf4j
@Service
public class DashScopeReranker {

    /** 延迟提供重排模型，使未配置 DashScope 时应用仍可启动。 */
    private final ObjectProvider<DashScopeRerankModel> rerankModelProvider;
    private final int maxConcurrentCalls;
    private final Semaphore permits;

    /** 是否启用语义重排阶段。 */
    @Value("${stocksage.rag.rerank.enabled:true}")
    private boolean enabled;

    /** DashScope 重排模型名。 */
    @Value("${stocksage.rag.rerank.model:gte-rerank-v2}")
    private String modelName;

    /**
     * 延迟注入 DashScope 重排模型。
     *
     * <p>使用 ObjectProvider 是为了让缺少 DashScope 配置时服务仍能启动，并在重排阶段自动降级。</p>
     *
     * @param rerankModelProvider 可选重排模型提供器
     * @param maxConcurrentCalls 本进程同时执行的同步重排调用上限
     */
    public DashScopeReranker(ObjectProvider<DashScopeRerankModel> rerankModelProvider,
                            @Value("${stocksage.rag.rerank.max-concurrent-calls:2}") int maxConcurrentCalls) {
        if (maxConcurrentCalls <= 0) throw new IllegalArgumentException("Rerank concurrency must be positive");
        this.rerankModelProvider = rerankModelProvider;
        this.maxConcurrentCalls = maxConcurrentCalls;
        this.permits = new Semaphore(maxConcurrentCalls);
    }

    /**
     * 返回当前配置是否启用重排。
     *
     * @return 配置开关值；不代表模型 Bean 一定可用
     */
    public boolean isEnabled() {
        return enabled;
    }

    /** Provider availability and per-query fallback are execution facts, not configuration. */
    public Map<String, Object> runtimeConfiguration() {
        return Map.of("enabled", enabled, "model", modelName, "returnDocuments", true,
                "maxConcurrentCalls", maxConcurrentCalls);
    }

    /**
     * 对融合候选重新排序，并把重排分数保存在文档元数据中，
     * 供引用展示和评估诊断使用。
     *
     * @param query 用户查询或改写后的查询
     * @param candidates RRF 融合后的候选列表
     * @param topN 最多返回的文档数
     * @return 重排结果；关闭、失败或无模型时按原顺序截断
     */
    public List<Document> rerank(String query, List<Document> candidates, int topN) {
        var run = ToolCallContext.currentRunDeadline();
        if (run != null) run.remainingMillis();
        if (!enabled || query == null || query.isBlank() || candidates == null || candidates.isEmpty()) {
            return limit(candidates, topN);
        }

        DashScopeRerankModel rerankModel = rerankModelProvider.getIfAvailable();
        if (rerankModel == null) {
            log.warn("DashScope rerank model bean is unavailable, using vector search order.");
            return limit(candidates, topN);
        }

        try {
            // DashScopeRerankModel.call 执行远程重排；returnDocuments 让响应保留原文和元数据。
            DashScopeRerankOptions options = DashScopeRerankOptions.builder()
                    .model(modelName)
                    .topN(topN)
                    .returnDocuments(true)
                    .build();
            if (run != null) run.remainingMillis();
            if (!permits.tryAcquire()) {
                log.warn("DashScope rerank CAPACITY_REJECTED: concurrent call limit {} reached; using fused retrieval order.",
                        maxConcurrentCalls);
                return limit(candidates, topN);
            }
            RerankResponse response;
            try {
                // SDK 未暴露逐请求超时；期限检查不能中断正在执行的同步 HTTP。
                if (run != null) run.remainingMillis();
                response = rerankModel.call(new RerankRequest(query, candidates, options));
                if (run != null) run.remainingMillis();
            } finally {
                permits.release();
            }
            List<Document> reranked = response.getResults().stream()
                    .map(this::toDocument)
                    .filter(document -> document != null)
                    .toList();

            if (reranked.isEmpty()) {
                log.warn("DashScope rerank returned no results, using vector search order.");
                return limit(candidates, topN);
            }
            return reranked;
        } catch (Exception e) {
            ResearchBudgetExceededException.rethrowIfPresent(e);
            if (run != null) run.remainingMillis();
            log.warn("DashScope rerank failed, using vector search order: {}", e.getMessage());
            return limit(candidates, topN);
        }
    }

    /**
     * 将 DashScope 返回的带分数文档转回 Spring AI Document。
     *
     * <p>重排分数同时写入 metadata 和 score，方便引用展示、评测脚本和后续排序逻辑读取。</p>
     *
     * @param scoredDocument DashScope 返回的文档与分数
     * @return 带 {@code rerank_score} 的 Spring AI 文档；无输出时返回 {@code null}
     */
    private Document toDocument(DocumentWithScore scoredDocument) {
        if (scoredDocument == null || scoredDocument.getOutput() == null) {
            return null;
        }
        Document document = scoredDocument.getOutput();
        return document.mutate()
                .metadata("rerank_score", scoredDocument.getScore())
                .score(scoredDocument.getScore())
                .build();
    }

    /**
     * 在重排不可用时按原顺序截断候选列表。
     *
     * @param documents 待截断文档；可为空
     * @param topN 最大返回数，负数按 0 处理
     * @return 不超过上限的新列表
     */
    private List<Document> limit(List<Document> documents, int topN) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        return documents.stream()
                .limit(Math.max(0, topN))
                .toList();
    }
}
