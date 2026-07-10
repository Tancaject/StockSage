package com.stocksage.controller;

import com.stocksage.rag.DocumentLoader;
import com.stocksage.rag.RagRegressionService;
import com.stocksage.rag.RagService;
import com.stocksage.rag.ScheduledRagCollector;
import com.stocksage.service.EdgarIngestionService;
import com.stocksage.service.KnowledgeIngestionService;
import com.stocksage.service.RelationExtractionService;
import com.stocksage.trace.PhoenixTraceService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 知识库管理接口。
 * 用于触发文档入库和调试检索效果。
 */
@RestController
@RequestMapping("/api/docs")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentLoader documentLoader;
    private final RagService ragService;
    private final RagRegressionService ragRegressionService;
    private final KnowledgeIngestionService knowledgeIngestionService;
    private final ScheduledRagCollector scheduledRagCollector;
    private final EdgarIngestionService edgarIngestionService;
    private final RelationExtractionService relationExtractionService;
    private final PhoenixTraceService phoenixTraceService;

    /**
     * 触发全量文档入库。
     * POST /api/docs/ingest
     */
    @PostMapping("/ingest")
    public Map<String, Object> ingestAll() {
        int count = documentLoader.loadAll();
        return Map.of("status", "ok", "chunksIngested", count);
    }

    /**
     * 手动清理已经过期的临时知识来源。
     */
    @PostMapping("/cleanup-expired")
    public Map<String, Object> cleanupExpired() {
        int sourcesCleaned = knowledgeIngestionService.deleteExpiredDocuments();
        return Map.of("status", "ok", "sourcesCleaned", sourcesCleaned);
    }

    /**
     * 手动触发配置的定时 RAG 采集源。
     */
    @PostMapping("/scheduled/collect")
    public Map<String, Object> collectScheduledSources() {
        return scheduledRagCollector.collectNow();
    }

    /**
     * 测试检索效果。
     * GET /api/docs/search?q=什么是市盈率
     */
    @GetMapping("/search")
    public List<Map<String, Object>> search(@RequestParam("q") String query,
                                            @RequestParam(defaultValue = "false") boolean full) {
        long startMs = System.currentTimeMillis();
        List<Document> results = ragService.retrieve(query);
        List<Map<String, Object>> response = results.stream().map(doc -> Map.<String, Object>of(
                "content", full ? doc.getText() : doc.getText().substring(0, Math.min(doc.getText().length(), 200)),
                "metadata", doc.getMetadata()
        )).toList();
        phoenixTraceService.recordRetrieval(
                query,
                results.size(),
                System.currentTimeMillis() - startMs,
                response.toString()
        );
        return response;
    }

    /**
     * 运行固定的 RAG 回归套件：
     * 写入一条可复用的学习型知识样本，验证相关问题能召回它，
     * 并验证无关问题不会错误带出它。
     */
    @PostMapping("/regression/run")
    public Map<String, Object> runRegression() {
        return ragRegressionService.runDefaultRegression();
    }

    /**
     * 手动触发 SEC EDGAR 财报入库。
     * POST /api/docs/edgar/ingest?ticker=AAPL&type=10-K&count=1
     */
    @PostMapping("/edgar/ingest")
    public Map<String, Object> ingestEdgarFilings(
            @RequestParam String ticker,
            @RequestParam(defaultValue = "10-K") String type,
            @RequestParam(defaultValue = "1") int count) {
        return edgarIngestionService.ingestFilings(ticker, type, count);
    }

    /**
     * 手动触发公司关系图谱抽取（基于已入库的 10-K 业务/风险章节）。
     * 按 ticker 幂等刷新：每条边都带逐字证据片段与出处。
     * POST /api/docs/relations/extract?ticker=NVDA
     */
    @PostMapping("/relations/extract")
    public Map<String, Object> extractRelations(@RequestParam String ticker) {
        return relationExtractionService.extractForTicker(ticker);
    }

}
