package com.stocksage.controller;

import com.stocksage.config.RequestIdentity;
import com.stocksage.model.dto.WorkbenchCockpitResponse;
import com.stocksage.model.dto.WorkbenchStockSuggestion;
import com.stocksage.service.CompanyRelationService;
import com.stocksage.service.RelationExtractionJobs;
import com.stocksage.service.StockNewsService;
import com.stocksage.service.WorkbenchCockpitService;
import com.stocksage.service.WorkbenchStockSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/workbench")
@RequiredArgsConstructor
public class WorkbenchController {

    private final WorkbenchCockpitService workbenchCockpitService;
    private final WorkbenchStockSearchService workbenchStockSearchService;
    private final CompanyRelationService companyRelationService;
    private final RelationExtractionJobs relationExtractionJobs;
    private final StockNewsService stockNewsService;
    private final RequestIdentity requestIdentity;

    @GetMapping("/stocks/search")
    public List<WorkbenchStockSuggestion> searchStocks(
            @RequestParam("q") String query,
            @RequestParam(defaultValue = "8") int limit
    ) {
        return workbenchStockSearchService.search(query, limit);
    }

    @GetMapping("/stocks/{ticker}/cockpit")
    public WorkbenchCockpitResponse getStockCockpit(
            @PathVariable String ticker,
            @RequestParam(defaultValue = "daily") String period,
            @RequestParam(defaultValue = "120") int days
    ) {
        return workbenchCockpitService.getCockpit(
                requestIdentity.currentUserId(),
                ticker,
                period,
                days
        );
    }

    @GetMapping("/stocks/{ticker}/intraday")
    public Map<String, Object> getStockIntraday(@PathVariable String ticker) {
        return workbenchCockpitService.getIntradayChart(ticker);
    }

    /**
     * 公司关系图谱：某 ticker 的竞争对手 / 供应链等关系，组装成前端可直接渲染的 ego-graph，
     * 每条边携带 10-K 逐字证据与出处。无数据时返回 empty=true 的空图。
     */
    @GetMapping("/stocks/{ticker}/relations")
    public Map<String, Object> getStockRelations(@PathVariable String ticker) {
        Map<String, Object> graph = companyRelationService.getGraph(ticker);
        boolean running = relationExtractionJobs.isRunning(ticker);
        graph.put("extracting", running);
        if (running) {
            graph.put("progress", relationExtractionJobs.getProgress(ticker));
        }
        return graph;
    }

    /**
     * 触发某 ticker 的关系图谱后台抽取（异步，立即返回）。前端空态"生成"按钮调用，
     * 之后轮询 {@code GET .../relations} 直到 {@code extracting=false} 且有数据。
     * 同标的已在抽取时返回 {@code status=running}，不重复触发。
     */
    @PostMapping("/stocks/{ticker}/relations/refresh")
    public Map<String, Object> refreshStockRelations(@PathVariable String ticker) {
        boolean started = relationExtractionJobs.submit(ticker);
        return Map.of(
                "ticker", ticker == null ? "" : ticker.trim().toUpperCase(),
                "status", started ? "started" : "running");
    }

    /**
     * 某 ticker 的近期市场新闻，复用既有 data-service 新闻管线（Tavily 检索，已带缓存），
     * 归一为前端可直接渲染的条目列表。
     */
    @GetMapping("/stocks/{ticker}/news")
    public Map<String, Object> getStockNews(
            @PathVariable String ticker,
            @RequestParam(defaultValue = "7") int days
    ) {
        return stockNewsService.getNews(ticker, days);
    }
}
