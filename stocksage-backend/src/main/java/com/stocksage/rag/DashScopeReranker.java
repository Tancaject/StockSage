package com.stocksage.rag;

import com.alibaba.cloud.ai.dashscope.rerank.DashScopeRerankModel;
import com.alibaba.cloud.ai.dashscope.rerank.DashScopeRerankOptions;
import com.alibaba.cloud.ai.document.DocumentWithScore;
import com.alibaba.cloud.ai.model.RerankRequest;
import com.alibaba.cloud.ai.model.RerankResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * RAG 检索中可选的 DashScope 重排阶段。
 *
 * <p>失败时会回退到融合检索器顺序，因为对话和评估运行中的检索应当优雅降级。</p>
 */
@Slf4j
@Service
public class DashScopeReranker {

    private final ObjectProvider<DashScopeRerankModel> rerankModelProvider;

    @Value("${stocksage.rag.rerank.enabled:true}")
    private boolean enabled;

    @Value("${stocksage.rag.rerank.model:gte-rerank-v2}")
    private String modelName;

    /**
     * 延迟注入 DashScope 重排模型。
     *
     * <p>使用 ObjectProvider 是为了让缺少 DashScope 配置时服务仍能启动，并在重排阶段自动降级。</p>
     */
    public DashScopeReranker(ObjectProvider<DashScopeRerankModel> rerankModelProvider) {
        this.rerankModelProvider = rerankModelProvider;
    }

    /**
     * 返回当前配置是否启用重排。
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 对融合候选重新排序，并把重排分数保存在文档元数据中，
     * 供引用展示和评估诊断使用。
     */
    public List<Document> rerank(String query, List<Document> candidates, int topN) {
        if (!enabled || query == null || query.isBlank() || candidates == null || candidates.isEmpty()) {
            return limit(candidates, topN);
        }

        DashScopeRerankModel rerankModel = rerankModelProvider.getIfAvailable();
        if (rerankModel == null) {
            log.warn("DashScope rerank model bean is unavailable, using vector search order.");
            return limit(candidates, topN);
        }

        try {
            DashScopeRerankOptions options = DashScopeRerankOptions.builder()
                    .model(modelName)
                    .topN(topN)
                    .returnDocuments(true)
                    .build();
            RerankResponse response = rerankModel.call(new RerankRequest(query, candidates, options));
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
            log.warn("DashScope rerank failed, using vector search order: {}", e.getMessage());
            return limit(candidates, topN);
        }
    }

    /**
     * 将 DashScope 返回的带分数文档转回 Spring AI Document。
     *
     * <p>重排分数同时写入 metadata 和 score，方便引用展示、评测脚本和后续排序逻辑读取。</p>
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
