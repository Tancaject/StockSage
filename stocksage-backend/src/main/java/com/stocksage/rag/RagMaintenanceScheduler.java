package com.stocksage.rag;

import com.stocksage.knowledge.KnowledgeIngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 清理过期知识，并协调派生向量与已提交的来源版本。
 *
 * <p>永久 SEC 或手动公告会持续保留索引；对话驱动的网页片段使用 TTL，
 * 并在这里清理，避免知识库积累过期市场新闻上下文。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagMaintenanceScheduler {

    /** 以已提交的来源索引协调 MySQL 镜像和向量。 */
    private final KnowledgeIngestionService knowledgeIngestionService;

    /**
     * 定时清理带 TTL 的临时来源，并重试各来源未完成的向量清理。
     *
     * <p>永久知识（例如公司公告、SEC 文件）不会被这里删除；过期清理主要面向对话过程中临时摄取的网页、
     * 新闻片段或短期材料，避免后续检索误召回已经失效的市场信息。</p>
     */
    @Scheduled(
            cron = "${stocksage.rag.ttl-cleanup-cron:0 15 * * * *}",
            zone = "${stocksage.rag.scheduler-zone:Asia/Shanghai}"
    )
    public void cleanupExpiredDocuments() {
        int cleaned = knowledgeIngestionService.deleteExpiredDocuments();
        knowledgeIngestionService.reconcileVectors();
        if (cleaned > 0) {
            log.info("RAG TTL cleanup removed {} expired source(s)", cleaned);
        }
    }
}
