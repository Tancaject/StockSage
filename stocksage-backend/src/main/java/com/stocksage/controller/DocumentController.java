package com.stocksage.controller;

import com.stocksage.rag.DocumentLoader;
import com.stocksage.rag.RagRegressionService;
import com.stocksage.rag.RagService;
import com.stocksage.rag.ScheduledRagCollector;
import com.stocksage.knowledge.EdgarIngestionService;
import com.stocksage.knowledge.KnowledgeIngestionService;
import com.stocksage.service.RelationExtractionService;
import com.stocksage.trace.PhoenixTraceService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * RAG 知识库的入库、维护、检索诊断和离线回归接口。
 *
 * <p>除只读搜索探针外，这些路由由管理令牌保护。控制器只负责参数绑定和结果包装，
 * 文档切片、向量写入、EDGAR 下载与关系抽取分别交给专用服务。</p>
 */
@RestController
@RequestMapping("/api/docs")
@RequiredArgsConstructor
public class DocumentController {

    /** 扫描本地文档并执行全量切片入库。 */
    private final DocumentLoader documentLoader;

    /** 执行线上同款混合检索。 */
    private final RagService ragService;

    /** 运行固定的检索隔离回归用例。 */
    private final RagRegressionService ragRegressionService;

    /** 维护临时知识来源及其过期清理。 */
    private final KnowledgeIngestionService knowledgeIngestionService;

    /** 执行配置化定时来源的即时采集。 */
    private final ScheduledRagCollector scheduledRagCollector;

    /** 下载并摄取 SEC EDGAR 财报。 */
    private final EdgarIngestionService edgarIngestionService;

    /** 从已入库财报证据抽取公司关系。 */
    private final RelationExtractionService relationExtractionService;

    /** 将检索探针写入可选 Phoenix 追踪。 */
    private final PhoenixTraceService phoenixTraceService;

    /**
     * 触发全量文档入库。
     * POST /api/docs/ingest
     *
     * @return 本次写入的切片数量
     */
    @PostMapping("/ingest")
    public Map<String, Object> ingestAll() {
        int count = documentLoader.loadAll();
        return Map.of("status", "ok", "chunksIngested", count);
    }

    /**
     * 手动清理已经过期的临时知识来源。
     *
     * @return 被清理的来源数量
     */
    @PostMapping("/cleanup-expired")
    public Map<String, Object> cleanupExpired() {
        int sourcesCleaned = knowledgeIngestionService.deleteExpiredDocuments();
        return Map.of("status", "ok", "sourcesCleaned", sourcesCleaned);
    }

    /**
     * 手动触发配置的定时 RAG 采集源。
     *
     * @return 各采集源的执行摘要
     */
    @PostMapping("/scheduled/collect")
    public Map<String, Object> collectScheduledSources() {
        return scheduledRagCollector.collectNow();
    }

    /**
     * 测试检索效果。
     * GET /api/docs/search?q=什么是市盈率
     *
     * @param query 待检索的自然语言问题
     * @param full 是否返回完整切片；默认只返回前 200 字符
     * @return 检索内容及其元数据
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
        // 复用 PhoenixTraceService 记录检索数量、耗时和脱敏后的诊断载荷。
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
     *
     * @return 正向召回与负向隔离检查结果
     */
    @PostMapping("/regression/run")
    public Map<String, Object> runRegression() {
        return ragRegressionService.runDefaultRegression();
    }

    /**
     * 手动触发 SEC EDGAR 财报入库。
     * POST /api/docs/edgar/ingest?ticker=AAPL&type=10-K&count=1
     *
     * @param ticker 美股代码
     * @param type SEC 表单类型，例如 10-K 或 10-Q
     * @param count 最多摄取的最新财报份数
     * @return 下载、跳过和写入统计
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
     *
     * @param ticker 已入库 SEC 财报对应的美股代码
     * @return 抽取、校验和保存的关系统计
     */
    @PostMapping("/relations/extract")
    public Map<String, Object> extractRelations(@RequestParam String ticker) {
        return relationExtractionService.extractForTicker(ticker);
    }

}
