package com.stocksage.service;

import com.stocksage.agent.PlanAction;
import com.stocksage.model.dto.AnalysisState;
import com.stocksage.tool.ChatStreamEmitter;
import com.stocksage.tool.FundamentalsTools;
import com.stocksage.tool.MarketTools;
import com.stocksage.tool.NewsTools;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 收集 DEEP 深度研究所需的确定性证据快照。
 *
 * <p>单独成类是因为请求内联降级与后台 worker 都需要复用同一套证据收集行为。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeepEvidenceCollector {

    private static final List<PlanAction> DEEP_ACTIONS = List.of(
            PlanAction.FUNDAMENTALS_AGENT,
            PlanAction.MARKET_AGENT,
            PlanAction.NEWS_AGENT,
            PlanAction.BULL_RESEARCHER,
            PlanAction.BEAR_RESEARCHER,
            PlanAction.RESEARCH_MANAGER
    );

    private final MarketTools marketTools;
    private final NewsTools newsTools;
    private final FundamentalsTools fundamentalsTools;
    private final TickerResolutionService tickerResolutionService;
    private final ChatStreamEmitter chatStreamEmitter;
    private final AsyncTaskExecutor agentTaskExecutor;

    @Value("${stocksage.chat.tool-prefetch.max-search-results:5}")
    private int toolPrefetchMaxSearchResults;

    @Value("${stocksage.chat.tool-prefetch.per-tool-timeout-seconds:8}")
    private long toolPrefetchPerToolTimeoutSeconds;

    @Value("${stocksage.chat.auto-edgar-ingest.enabled:false}")
    private boolean autoEdgarIngestEnabled;

    /**
     * 为深度研究流程收集确定性基本面、市场和新闻快照。
     */
    public EvidenceCollection collect(
            String primaryTicker,
            String userQuery,
            String traceId,
            Long conversationId) {
        StringBuilder context = new StringBuilder();
        AnalysisState agentState = AnalysisState.builder()
                .query(userQuery)
                .primaryTicker(primaryTicker)
                .build();

        String ticker = primaryTicker == null ? "" : primaryTicker.trim();
        if (ticker.isBlank()) {
            context.append("## resolvedStockIdentity\n")
                    .append("No supported stock target was resolved. Do not infer company business, reports, or valuation from unrelated search/RAG context.\n\n");
            log.warn("DEEP deterministic prefetch skipped because no stock target was resolved, traceId={}", traceId);
            emitProgress(traceId, conversationId, "observation",
                    "未能可靠解析本轮分析标的，系统将避免套用无关公司的数据。");
            return new EvidenceCollection(context.toString(), agentState, false, false, false, false);
        }

        log.info("DEEP deterministic prefetch started, traceId={}, ticker={}, actions={}",
                traceId, ticker, DEEP_ACTIONS);
        emitProgress(traceId, conversationId, "thought",
                "DEEP prefetch started: collecting deterministic tool data for ticker=" + ticker + ".");

        EvidenceSnapshot fundamentals = buildFundamentalsSnapshot(ticker);
        agentState.setFundamentalsReport(fundamentals.text());
        appendContextSection(context, "Fundamentals Agent", fundamentals.text());
        boolean fundamentalsOk = fundamentals.hasUsableData();
        emitProgress(traceId, conversationId, "observation",
                "Fundamentals Agent data snapshot collected for " + ticker + ".");

        EvidenceSnapshot market = buildMarketSnapshot(ticker);
        agentState.setMarketReport(market.text());
        appendContextSection(context, "Market Agent", market.text());
        boolean marketOk = market.hasUsableData();
        emitProgress(traceId, conversationId, "observation",
                "Market Agent data snapshot collected for " + ticker + ".");

        EvidenceSnapshot news = buildNewsSnapshot(ticker);
        agentState.setNewsReport(news.text());
        appendContextSection(context, "News Agent", news.text());
        boolean newsOk = news.hasUsableData();
        emitProgress(traceId, conversationId, "observation",
                "News Agent data snapshot collected for " + ticker + ".");

        log.info("DEEP deterministic prefetch completed, traceId={}, ticker={}, fundamentalsOk={}, marketOk={}, newsOk={}",
                traceId, ticker, fundamentalsOk, marketOk, newsOk);
        return new EvidenceCollection(
                context.toString(),
                agentState,
                fundamentalsOk || marketOk,
                true,
                fundamentalsOk,
                marketOk
        );
    }

    /** 构造基本面预取快照。 */
    private EvidenceSnapshot buildFundamentalsSnapshot(String ticker) {
        StringBuilder report = new StringBuilder();
        report.append("Ticker: ").append(ticker).append('\n');
        boolean hasData = false;

        String financialReports = runPrefetchTool("Fundamentals Agent/getFinancialReports",
                () -> fundamentalsTools.getFinancialReports(ticker, "annual", 5));
        hasData |= !isToolFailureOrEmpty(financialReports);
        appendSnapshotItem(report, "getFinancialReports(annual,5)", financialReports, 4500);

        String companyReports = runPrefetchTool("Fundamentals Agent/searchCompanyReports",
                () -> fundamentalsTools.searchCompanyReports(ticker, "财报", toolPrefetchMaxSearchResults));
        hasData |= !isToolFailureOrEmpty(companyReports);
        appendSnapshotItem(report, "searchCompanyReports", companyReports, 3000);

        if (tickerResolutionService.isLikelySecTicker(ticker)) {
            String structured = runPrefetchTool("Fundamentals Agent/getStructuredFinancials",
                    () -> fundamentalsTools.getStructuredFinancials(ticker));
            hasData |= !isToolFailureOrEmpty(structured);
            appendSnapshotItem(report, "getStructuredFinancials(SEC XBRL)", structured, 4500);
            if (autoEdgarIngestEnabled) {
                appendSnapshotItem(report, "ingestCompanyFilings(10-K x1)",
                        runPrefetchTool("Fundamentals Agent/ingestCompanyFilings",
                                () -> fundamentalsTools.ingestCompanyFilings(ticker, "10-K", 1)), 2000);
            } else {
                appendSnapshotItem(report, "ingestCompanyFilings",
                        "Auto EDGAR ingestion is disabled for chat prefetch; use existing indexed filings or trigger manual ingestion if more filing context is needed.",
                        600);
            }
        }
        return new EvidenceSnapshot(report.toString().trim(), hasData);
    }

    /** 构造市场预取快照。 */
    private EvidenceSnapshot buildMarketSnapshot(String ticker) {
        StringBuilder report = new StringBuilder();
        report.append("Ticker: ").append(ticker).append('\n');
        boolean hasData = false;

        String kline = runPrefetchTool("Market Agent/" + marketKLineToolName(ticker),
                () -> getMarketKLine(ticker));
        hasData |= !isToolFailureOrEmpty(kline);
        appendSnapshotItem(report, marketKLineToolName(ticker), kline, 4500);

        String financial = runPrefetchTool("Market Agent/" + marketFinancialToolName(ticker),
                () -> getMarketFinancialContext(ticker));
        hasData |= !isToolFailureOrEmpty(financial);
        appendSnapshotItem(report, marketFinancialToolName(ticker), financial, 3000);

        String technical = runPrefetchTool("Market Agent/" + marketTechnicalToolName(ticker),
                () -> getMarketTechnicalContext(ticker));
        hasData |= !isToolFailureOrEmpty(technical);
        appendSnapshotItem(report, marketTechnicalToolName(ticker), technical, 3000);

        appendSectorContext(report, ticker);
        return new EvidenceSnapshot(report.toString().trim(), hasData);
    }

    private String marketKLineToolName(String ticker) {
        return tickerResolutionService.isLikelySecTicker(ticker) ? "getIbkrHistoricalBars(3m,1d)" : "getStockKLine(daily,60)";
    }

    private String marketFinancialToolName(String ticker) {
        return tickerResolutionService.isLikelySecTicker(ticker) ? "getStructuredFinancials(SEC XBRL)" : "getFinancialMetrics";
    }

    private String marketTechnicalToolName(String ticker) {
        return tickerResolutionService.isLikelySecTicker(ticker) ? "getIbkrHistoricalBars(6m,1d)" : "getTechnicalIndicators(MA,MACD,RSI)";
    }

    private String getMarketKLine(String ticker) {
        if (tickerResolutionService.isLikelySecTicker(ticker)) {
            return marketTools.getIbkrHistoricalBars(ticker, "3m", "1d");
        }
        return marketTools.getStockKLine(ticker, "daily", 60);
    }

    private String getMarketFinancialContext(String ticker) {
        if (tickerResolutionService.isLikelySecTicker(ticker)) {
            return fundamentalsTools.getStructuredFinancials(ticker);
        }
        return marketTools.getFinancialMetrics(ticker);
    }

    private String getMarketTechnicalContext(String ticker) {
        if (tickerResolutionService.isLikelySecTicker(ticker)) {
            return marketTools.getIbkrHistoricalBars(ticker, "6m", "1d");
        }
        return marketTools.getTechnicalIndicators(ticker, "MA,MACD,RSI");
    }

    private void appendSectorContext(StringBuilder report, String ticker) {
        String sector = tickerResolutionService.resolveSectorForTicker(ticker);
        if (sector.isBlank()) {
            appendSnapshotItem(report, "sectorContext",
                    "No reliable sector mapping was resolved for this ticker, so sector performance was not fetched.",
                    600);
            return;
        }
        if (tickerResolutionService.isLikelySecTicker(ticker)) {
            appendSnapshotItem(report, "sectorContext",
                    "Resolved sector/industry context: " + sector
                            + ". US sector performance is not served by the current A/HK sector data source, so it was not fetched.",
                    700);
            return;
        }
        appendSnapshotItem(report, "getSectorPerformance",
                runPrefetchTool("Market Agent/getSectorPerformance",
                        () -> marketTools.getSectorPerformance(sector)), 2500);
    }

    /** 构造新闻和网页搜索预取快照。 */
    private EvidenceSnapshot buildNewsSnapshot(String ticker) {
        StringBuilder report = new StringBuilder();
        String query = buildNewsQuery(ticker);
        report.append("Ticker: ").append(ticker).append('\n');
        report.append("News query: ").append(query).append('\n');
        boolean hasData = false;

        String stockNews = runPrefetchTool("News Agent/getStockNews",
                () -> newsTools.getStockNews(ticker, 7));
        hasData |= !isToolFailureOrEmpty(stockNews);
        appendSnapshotItem(report, "getStockNews(7d)", stockNews, 3500);

        // 深度投研预取：搜索证据质量直接影响最终报告，这里显式开启 Tavily advanced 深度检索。
        String searchNews = runPrefetchTool("News Agent/searchNews",
                () -> newsTools.searchNews(query, toolPrefetchMaxSearchResults, true));
        hasData |= !isToolFailureOrEmpty(searchNews);
        appendSnapshotItem(report, "searchNews", searchNews, 3500);

        String webSearch = runPrefetchTool("News Agent/webSearch",
                () -> newsTools.webSearch(query, toolPrefetchMaxSearchResults, true));
        hasData |= !isToolFailureOrEmpty(webSearch);
        appendSnapshotItem(report, "webSearch", webSearch, 3500);

        return new EvidenceSnapshot(report.toString().trim(), hasData);
    }

    private void appendSnapshotItem(StringBuilder report, String name, String value, int maxLength) {
        report.append("\n### ").append(name).append('\n')
                .append(PromptText.truncate(value, maxLength))
                .append('\n');
    }

    private String runPrefetchTool(String name, ToolCall toolCall) {
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            try {
                log.info("{} started.", name);
                String result = toolCall.call();
                log.info("{} completed, chars={}.", name, result == null ? 0 : result.length());
                return result == null || result.isBlank() ? "(empty result)" : result;
            } catch (Exception e) {
                log.warn("{} failed: {}", name, e.getMessage());
                return "Tool failed: " + e.getMessage();
            }
        }, agentTaskExecutor);

        try {
            return future.get(toolPrefetchPerToolTimeoutSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            future.cancel(true);
            log.warn("{} timed out or failed after {}s: {}",
                    name, toolPrefetchPerToolTimeoutSeconds, e.getMessage());
            return "Tool timed out after " + toolPrefetchPerToolTimeoutSeconds
                    + "s; continuing with available evidence.";
        }
    }

    private boolean isToolFailureOrEmpty(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        String trimmed = text.strip();
        return trimmed.equals("(empty result)")
                || trimmed.startsWith("Tool failed")
                || trimmed.startsWith("Tool timed out")
                || trimmed.startsWith("{\"error\":true");
    }

    private String buildNewsQuery(String ticker) {
        int year = LocalDate.now().getYear();
        if ("MU".equals(ticker)) {
            return "Micron MU earnings HBM AI memory export control latest " + year;
        }
        return ticker + " earnings stock latest " + year;
    }

    private void emitProgress(String traceId, Long conversationId, String type, String content) {
        chatStreamEmitter.emit(traceId, conversationId, type, content);
    }

    private void appendContextSection(StringBuilder context, String name, String content) {
        context.append("## ").append(name).append("\n")
                .append(PromptText.truncate(content, 4500))
                .append("\n\n");
    }

    /**
     * 深度研究证据上下文、分析状态和最低证据门槛结果。
     */
    public record EvidenceCollection(
            String contextMarkdown,
            AnalysisState state,
            boolean sufficientForRecommendation,
            boolean tickerResolved,
            boolean fundamentalsOk,
            boolean marketOk) {
    }

    private record EvidenceSnapshot(String text, boolean hasUsableData) {
    }

    @FunctionalInterface
    private interface ToolCall {
        String call();
    }
}
