package com.stocksage.controller;

import com.stocksage.identity.RequestIdentity;
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

/**
 * 投研工作台的一站式只读 API。
 *
 * <p>它把股票搜索、K 线驾驶舱、分时图、关系图谱和新闻聚合暴露给 Vue 工作台；
 * 复杂数据组装仍在各服务中完成，控制器不维护业务状态。</p>
 */
@RestController
@RequestMapping("/api/workbench")
@RequiredArgsConstructor
public class WorkbenchController {

    /** 组装当前用户的 K 线、报告、任务和证据驾驶舱。 */
    private final WorkbenchCockpitService workbenchCockpitService;

    /** 跨美股、A 股、港股查找候选标的。 */
    private final WorkbenchStockSearchService workbenchStockSearchService;

    /** 查询已持久化公司关系并组装 ego graph。 */
    private final CompanyRelationService companyRelationService;

    /** 管理关系抽取后台作业及其进度。 */
    private final RelationExtractionJobs relationExtractionJobs;

    /** 通过 data-service 获取并规范化近期新闻。 */
    private final StockNewsService stockNewsService;

    /** 从登录 Session 提供驾驶舱的数据隔离用户 ID。 */
    private final RequestIdentity requestIdentity;

    /**
     * 按用户输入搜索股票候选。
     *
     * @param query ticker、公司名或中文别名
     * @param limit 最大候选数
     * @return 去重并按解析优先级排序的候选
     */
    @GetMapping("/stocks/search")
    public List<WorkbenchStockSuggestion> searchStocks(
            @RequestParam("q") String query,
            @RequestParam(defaultValue = "8") int limit
    ) {
        return workbenchStockSearchService.search(query, limit);
    }

    /**
     * 获取单只股票的完整工作台驾驶舱。
     *
     * @param ticker 股票代码
     * @param period K 线周期，例如 daily
     * @param days 请求的历史天数
     * @return K 线、报告、证据和研究任务组合视图
     */
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

    /**
     * 获取单只股票的分时图载荷。
     *
     * @param ticker 股票代码
     * @return data-service 分时数据或显式降级状态
     */
    @GetMapping("/stocks/{ticker}/intraday")
    public Map<String, Object> getStockIntraday(@PathVariable String ticker) {
        return workbenchCockpitService.getIntradayChart(ticker);
    }

    /**
     * 公司关系图谱：某 ticker 的竞争对手 / 供应链等关系，组装成前端可直接渲染的 ego-graph，
     * 每条边携带 10-K 逐字证据与出处。无数据时返回 empty=true 的空图。
     *
     * @param ticker 股票代码
     * @return 图节点、边、证据及后台抽取进度
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
     *
     * @param ticker 股票代码
     * @return 标准化 ticker 与 started/running 状态
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
     *
     * @param ticker 股票代码
     * @param days 回看天数
     * @return 新闻条目、来源和时间范围
     */
    @GetMapping("/stocks/{ticker}/news")
    public Map<String, Object> getStockNews(
            @PathVariable String ticker,
            @RequestParam(defaultValue = "7") int days
    ) {
        return stockNewsService.getNews(ticker, days);
    }
}
