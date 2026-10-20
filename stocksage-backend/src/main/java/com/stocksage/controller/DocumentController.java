package com.stocksage.controller;

import com.stocksage.rag.DocumentLoader;
import com.stocksage.rag.RagService;
import com.stocksage.knowledge.EdgarIngestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * RAG 知识库的入库和检索诊断接口。
 *
 * <p>除只读搜索探针外，这些路由由管理令牌保护。控制器只负责参数绑定和结果包装，
 * 文档切片、向量写入与 EDGAR 下载分别交给专用服务。</p>
 */
@RestController
@RequestMapping("/api/docs")
@RequiredArgsConstructor
public class DocumentController {

    /** 扫描本地文档并执行全量切片入库。 */
    private final DocumentLoader documentLoader;

    /** 执行线上同款混合检索。 */
    private final RagService ragService;

    /** 下载并摄取 SEC EDGAR 财报。 */
    private final EdgarIngestionService edgarIngestionService;

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
        List<Document> results = ragService.retrieve(query);
        return results.stream().map(doc -> Map.<String, Object>of(
                "content", full ? doc.getText() : doc.getText().substring(0, Math.min(doc.getText().length(), 200)),
                "metadata", doc.getMetadata()
        )).toList();
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

}
