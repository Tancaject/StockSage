package com.stocksage.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.agent.PlanAction;
import com.stocksage.harness.DeepResearchCompletionPolicy;
import com.stocksage.harness.EvidenceLedger;
import com.stocksage.harness.HarnessModels.EvidenceDimension;
import com.stocksage.harness.HarnessModels.EvidenceEnvelope;
import com.stocksage.harness.HarnessModels.EvidenceStatus;
import com.stocksage.harness.HarnessModels.HarnessDecision;
import com.stocksage.harness.HarnessModels.HarnessOutcome;
import com.stocksage.harness.HarnessModels.RecoveryAction;
import com.stocksage.harness.HarnessModels.RunContext;
import com.stocksage.harness.HarnessModels.TargetIdentity;
import com.stocksage.harness.ResearchHarness;
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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 收集 DEEP 深度研究所需的确定性证据快照。
 *
 * <p>单独成类是因为请求内联降级与后台 worker 都需要复用同一套证据收集行为。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeepEvidenceCollector {

    private static final ObjectMapper PROVENANCE_MAPPER = new ObjectMapper();
    private static final Set<String> BUSINESS_TIME_FIELDS = Set.of(
            "asof",
            "date",
            "tradedate",
            "filed",
            "filingdate",
            "publishedat",
            "publisheddate",
            "reportdate",
            "statdate",
            "pubdate",
            "end",
            "t"
    );

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
    private final ResearchHarness researchHarness;
    private final DeepResearchCompletionPolicy completionPolicy;

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
            EvidenceLedger ledger = new EvidenceLedger(TargetIdentity.unresolved(), List.of());
            agentState.setEvidenceLedger(ledger);
            HarnessDecision harnessDecision = researchHarness.observeEvidence(
                    traceId, completionPolicy, RunContext.deepResearch(), ledger);
            return new EvidenceCollection(
                    context.toString(), agentState, false, false, false, false, false, ledger,
                    harnessDecision);
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

        List<EvidenceEnvelope> collectedEvidence = new ArrayList<>();
        collectedEvidence.addAll(fundamentals.evidence());
        collectedEvidence.addAll(market.evidence());
        collectedEvidence.addAll(news.evidence());
        EvidenceLedger ledger = new EvidenceLedger(
                TargetIdentity.resolved(ticker), collectedEvidence);
        agentState.setEvidenceLedger(ledger);
        HarnessDecision harnessDecision = researchHarness.observeEvidence(
                traceId,
                completionPolicy,
                RunContext.deepResearch(),
                ledger
        );

        log.info("DEEP deterministic prefetch completed, traceId={}, ticker={}, fundamentalsOk={}, marketOk={}, newsOk={}",
                traceId, ticker, fundamentalsOk, marketOk, newsOk);
        return new EvidenceCollection(
                context.toString(),
                agentState,
                harnessDecision.allowsRecommendation(),
                true,
                fundamentalsOk,
                marketOk,
                newsOk,
                ledger,
                harnessDecision
        );
    }

    /**
     * Re-collects only dimensions explicitly allowlisted by the completion policy.
     */
    public EvidenceCollection recover(
            EvidenceCollection existing,
            List<RecoveryAction> recoveryActions,
            String traceId,
            Long conversationId
    ) {
        if (existing == null || recoveryActions == null || recoveryActions.isEmpty()) {
            return existing;
        }
        AnalysisState state = existing.state();
        String ticker = state == null ? "" : state.getPrimaryTicker();
        List<EvidenceEnvelope> merged = new ArrayList<>(existing.evidenceLedger().evidence());
        boolean fundamentalsOk = existing.fundamentalsOk();
        boolean marketOk = existing.marketOk();
        boolean newsOk = existing.newsOk();
        EnumMap<RecoveryAction, Integer> attempts = new EnumMap<>(RecoveryAction.class);

        if (recoveryActions.contains(RecoveryAction.RETRY_FUNDAMENTALS)) {
            attempts.put(RecoveryAction.RETRY_FUNDAMENTALS, 1);
            EvidenceSnapshot fundamentals = buildFundamentalsSnapshot(ticker);
            state.setFundamentalsReport(fundamentals.text());
            replaceDimension(merged, EvidenceDimension.FUNDAMENTALS, fundamentals.evidence());
            fundamentalsOk = fundamentals.hasUsableData();
            emitProgress(traceId, conversationId, "observation",
                    "Fundamentals evidence recovery completed.");
        }
        if (recoveryActions.contains(RecoveryAction.RETRY_MARKET)) {
            attempts.put(RecoveryAction.RETRY_MARKET, 1);
            EvidenceSnapshot market = buildMarketSnapshot(ticker);
            state.setMarketReport(market.text());
            replaceDimension(merged, EvidenceDimension.MARKET, market.evidence());
            marketOk = market.hasUsableData();
            emitProgress(traceId, conversationId, "observation",
                    "Market evidence recovery completed.");
        }
        if (recoveryActions.contains(RecoveryAction.RETRY_NEWS)) {
            attempts.put(RecoveryAction.RETRY_NEWS, 1);
            EvidenceSnapshot news = buildNewsSnapshot(ticker);
            state.setNewsReport(news.text());
            replaceDimension(merged, EvidenceDimension.NEWS, news.evidence());
            newsOk = news.hasUsableData();
            emitProgress(traceId, conversationId, "observation",
                    "News evidence recovery completed.");
        }

        EvidenceLedger ledger = new EvidenceLedger(existing.evidenceLedger().target(), merged);
        state.setEvidenceLedger(ledger);
        HarnessDecision decision = researchHarness.observeEvidence(
                traceId,
                completionPolicy,
                new RunContext("DEEP", Map.copyOf(attempts)),
                ledger
        );
        return new EvidenceCollection(
                rebuildContext(state),
                state,
                decision.allowsRecommendation(),
                existing.tickerResolved(),
                fundamentalsOk,
                marketOk,
                newsOk,
                ledger,
                decision
        );
    }

    private void replaceDimension(
            List<EvidenceEnvelope> merged,
            EvidenceDimension dimension,
            List<EvidenceEnvelope> replacement
    ) {
        merged.removeIf(item -> item.dimension() == dimension);
        merged.addAll(replacement);
    }

    private String rebuildContext(AnalysisState state) {
        StringBuilder context = new StringBuilder();
        appendContextSection(context, "Fundamentals Agent", state.getFundamentalsReport());
        appendContextSection(context, "Market Agent", state.getMarketReport());
        appendContextSection(context, "News Agent", state.getNewsReport());
        return context.toString();
    }

    /** 构造基本面预取快照。 */
    private EvidenceSnapshot buildFundamentalsSnapshot(String ticker) {
        StringBuilder report = new StringBuilder();
        List<EvidenceEnvelope> evidence = new ArrayList<>();
        report.append("Ticker: ").append(ticker).append('\n');
        boolean hasData = false;

        ToolObservation financialReports = runPrefetchTool("Fundamentals Agent/getFinancialReports",
                () -> fundamentalsTools.getFinancialReports(ticker, "annual", 5));
        hasData |= financialReports.hasUsableData();
        appendSnapshotItem(report, "getFinancialReports(annual,5)", financialReports.text(), 4500);
        evidence.add(toEnvelope(
                EvidenceDimension.FUNDAMENTALS, ticker, "getFinancialReports", financialReports));

        ToolObservation companyReports = runPrefetchTool("Fundamentals Agent/searchCompanyReports",
                () -> fundamentalsTools.searchCompanyReports(ticker, "财报", toolPrefetchMaxSearchResults));
        hasData |= companyReports.hasUsableData();
        appendSnapshotItem(report, "searchCompanyReports", companyReports.text(), 3000);
        evidence.add(toEnvelope(
                EvidenceDimension.FUNDAMENTALS, ticker, "searchCompanyReports", companyReports));

        if (tickerResolutionService.isLikelySecTicker(ticker)) {
            ToolObservation structured = runPrefetchTool("Fundamentals Agent/getStructuredFinancials",
                    () -> fundamentalsTools.getStructuredFinancials(ticker));
            hasData |= structured.hasUsableData();
            appendSnapshotItem(report, "getStructuredFinancials(SEC XBRL)", structured.text(), 4500);
            evidence.add(toEnvelope(
                    EvidenceDimension.FUNDAMENTALS, ticker, "getStructuredFinancials", structured));
            if (autoEdgarIngestEnabled) {
                ToolObservation ingestion = runPrefetchTool("Fundamentals Agent/ingestCompanyFilings",
                        () -> fundamentalsTools.ingestCompanyFilings(ticker, "10-K", 1));
                appendSnapshotItem(report, "ingestCompanyFilings(10-K x1)",
                        ingestion.text(), 2000);
                evidence.add(toEnvelope(
                        EvidenceDimension.FUNDAMENTALS,
                        ticker,
                        "ingestCompanyFilings",
                        ingestion,
                        false
                ));
            } else {
                appendSnapshotItem(report, "ingestCompanyFilings",
                        "Auto EDGAR ingestion is disabled for chat prefetch; use existing indexed filings or trigger manual ingestion if more filing context is needed.",
                        600);
            }
        }
        return new EvidenceSnapshot(report.toString().trim(), hasData, evidence);
    }

    /** 构造市场预取快照。 */
    private EvidenceSnapshot buildMarketSnapshot(String ticker) {
        StringBuilder report = new StringBuilder();
        List<EvidenceEnvelope> evidence = new ArrayList<>();
        report.append("Ticker: ").append(ticker).append('\n');
        boolean hasData = false;

        String klineTool = marketKLineToolName(ticker);
        ToolObservation kline = runPrefetchTool("Market Agent/" + klineTool,
                () -> getMarketKLine(ticker));
        hasData |= kline.hasUsableData();
        appendSnapshotItem(report, klineTool, kline.text(), 4500);
        evidence.add(toEnvelope(EvidenceDimension.MARKET, ticker, klineTool, kline));

        String financialTool = marketFinancialToolName(ticker);
        ToolObservation financial = runPrefetchTool("Market Agent/" + financialTool,
                () -> getMarketFinancialContext(ticker));
        hasData |= financial.hasUsableData();
        appendSnapshotItem(report, financialTool, financial.text(), 3000);
        evidence.add(toEnvelope(EvidenceDimension.MARKET, ticker, financialTool, financial));

        String technicalTool = marketTechnicalToolName(ticker);
        ToolObservation technical = runPrefetchTool("Market Agent/" + technicalTool,
                () -> getMarketTechnicalContext(ticker));
        hasData |= technical.hasUsableData();
        appendSnapshotItem(report, technicalTool, technical.text(), 3000);
        evidence.add(toEnvelope(EvidenceDimension.MARKET, ticker, technicalTool, technical));

        appendSectorContext(report, evidence, ticker);
        return new EvidenceSnapshot(report.toString().trim(), hasData, evidence);
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

    private void appendSectorContext(
            StringBuilder report,
            List<EvidenceEnvelope> evidence,
            String ticker
    ) {
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
        ToolObservation sectorPerformance = runPrefetchTool("Market Agent/getSectorPerformance",
                () -> marketTools.getSectorPerformance(sector));
        appendSnapshotItem(report, "getSectorPerformance",
                sectorPerformance.text(), 2500);
        evidence.add(toEnvelope(
                EvidenceDimension.MARKET, ticker, "getSectorPerformance", sectorPerformance));
    }

    /** 构造新闻和网页搜索预取快照。 */
    private EvidenceSnapshot buildNewsSnapshot(String ticker) {
        StringBuilder report = new StringBuilder();
        List<EvidenceEnvelope> evidence = new ArrayList<>();
        String query = buildNewsQuery(ticker);
        report.append("Ticker: ").append(ticker).append('\n');
        report.append("News query: ").append(query).append('\n');
        boolean hasData = false;

        ToolObservation stockNews = runPrefetchTool("News Agent/getStockNews",
                () -> newsTools.getStockNews(ticker, 7));
        hasData |= stockNews.hasUsableData();
        appendSnapshotItem(report, "getStockNews(7d)", stockNews.text(), 3500);
        evidence.add(toEnvelope(EvidenceDimension.NEWS, ticker, "getStockNews", stockNews));

        // 深度投研预取：搜索证据质量直接影响最终报告，这里显式开启 Tavily advanced 深度检索。
        ToolObservation searchNews = runPrefetchTool("News Agent/searchNews",
                () -> newsTools.searchNews(query, toolPrefetchMaxSearchResults, true));
        hasData |= searchNews.hasUsableData();
        appendSnapshotItem(report, "searchNews", searchNews.text(), 3500);
        evidence.add(toEnvelope(EvidenceDimension.NEWS, ticker, "searchNews", searchNews));

        ToolObservation webSearch = runPrefetchTool("News Agent/webSearch",
                () -> newsTools.webSearch(query, toolPrefetchMaxSearchResults, true));
        hasData |= webSearch.hasUsableData();
        appendSnapshotItem(report, "webSearch", webSearch.text(), 3500);
        evidence.add(toEnvelope(EvidenceDimension.NEWS, ticker, "webSearch", webSearch));

        return new EvidenceSnapshot(report.toString().trim(), hasData, evidence);
    }

    private void appendSnapshotItem(StringBuilder report, String name, String value, int maxLength) {
        report.append("\n### ").append(name).append('\n')
                .append(PromptText.truncate(value, maxLength))
                .append('\n');
    }

    private ToolObservation runPrefetchTool(String name, ToolCall toolCall) {
        CompletableFuture<ToolObservation> future = CompletableFuture.supplyAsync(() -> {
            Instant observedAt = Instant.now();
            try {
                log.info("{} started.", name);
                String result = toolCall.call();
                log.info("{} completed, chars={}.", name, result == null ? 0 : result.length());
                if (result == null || result.isBlank()) {
                    return new ToolObservation(
                            "(empty result)", EvidenceStatus.EMPTY, observedAt);
                }
                EvidenceStatus status = result.strip().startsWith("{\"error\":true")
                        ? EvidenceStatus.FAILED
                        : EvidenceStatus.AVAILABLE;
                return new ToolObservation(result, status, observedAt);
            } catch (Exception e) {
                log.warn("{} failed: {}", name, e.getMessage());
                return new ToolObservation(
                        "Tool failed: " + e.getMessage(), EvidenceStatus.FAILED, observedAt);
            }
        }, agentTaskExecutor);

        try {
            return future.get(toolPrefetchPerToolTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("{} timed out after {}s", name, toolPrefetchPerToolTimeoutSeconds);
            return new ToolObservation(
                    "Tool timed out after " + toolPrefetchPerToolTimeoutSeconds
                            + "s; continuing with available evidence.",
                    EvidenceStatus.TIMED_OUT,
                    Instant.now()
            );
        } catch (Exception e) {
            future.cancel(true);
            log.warn("{} timed out or failed after {}s: {}",
                    name, toolPrefetchPerToolTimeoutSeconds, e.getMessage());
            return new ToolObservation(
                    "Tool timed out after " + toolPrefetchPerToolTimeoutSeconds
                            + "s; continuing with available evidence.",
                    EvidenceStatus.FAILED,
                    Instant.now()
            );
        }
    }

    private EvidenceEnvelope toEnvelope(
            EvidenceDimension dimension,
            String ticker,
            String capabilityId,
            ToolObservation observation
    ) {
        return toEnvelope(dimension, ticker, capabilityId, observation, true);
    }

    private EvidenceEnvelope toEnvelope(
            EvidenceDimension dimension,
            String ticker,
            String capabilityId,
            ToolObservation observation,
            boolean approvedReadOnly
    ) {
        String payloadHash = sha256(observation.text());
        String evidenceId = sha256(
                dimension.name() + "|" + capabilityId + "|" + ticker + "|" + payloadHash);
        EvidenceStatus structuredStatus = dimension == EvidenceDimension.NEWS
                && observation.status() == EvidenceStatus.AVAILABLE
                && isSuccessfulNoResults(observation.text())
                ? EvidenceStatus.NO_RESULTS
                : observation.status();
        EvidenceProvenance provenance = observation.status() == EvidenceStatus.AVAILABLE
                ? extractProvenance(capabilityId, ticker, observation.text())
                : EvidenceProvenance.empty();
        return new EvidenceEnvelope(
                evidenceId,
                dimension,
                capabilityId,
                ticker,
                structuredStatus,
                provenance.sourceRef(),
                provenance.provider(),
                observation.observedAt(),
                provenance.asOf(),
                payloadHash,
                approvedReadOnly
        );
    }

    private EvidenceProvenance extractProvenance(
            String capabilityId,
            String ticker,
            String payload
    ) {
        if (payload == null || payload.isBlank()) {
            return EvidenceProvenance.empty();
        }
        try {
            JsonNode root = PROVENANCE_MAPPER.readTree(payload);
            if (root == null || root.isNull() || root.isMissingNode()
                    || root.path("error").asBoolean(false)) {
                return EvidenceProvenance.empty();
            }

            String provider = firstDirectText(root, "provider");
            if (provider.isBlank()) {
                provider = firstDirectText(root, "source");
            }

            String cik = firstDirectText(root, "cik");
            if (isSecEvidence(capabilityId, cik)) {
                provider = "SEC EDGAR XBRL";
            } else if (isIbkrEvidence(capabilityId)) {
                provider = provider.isBlank() ? "IBKR_WEB_API" : provider;
            }

            JsonNode selectedSearchResult = null;
            String sourceRef = allowlistedSourceRef(
                    firstDirectText(
                            root,
                            "sourceRef",
                            "source_ref",
                            "documentUrl",
                            "document_url",
                            "url"
                    )
            );
            if (sourceRef.isBlank() && isSearchEvidence(capabilityId)) {
                selectedSearchResult = firstSearchResult(root);
                sourceRef = selectedSearchResult == null
                        ? ""
                        : firstDirectText(
                                selectedSearchResult,
                                "url",
                                "link",
                                "sourceRef",
                                "source_ref",
                                "documentUrl",
                                "document_url"
                        );
            }
            if (sourceRef.isBlank() && isSecEvidence(capabilityId, cik)) {
                sourceRef = secCompanyFactsUrl(cik);
            }
            if (sourceRef.isBlank() && isIbkrEvidence(capabilityId)) {
                sourceRef = ibkrHistoryRef(root, ticker);
            }
            if (sourceRef.isBlank()) {
                sourceRef = allowlistedSourceRef(firstDirectText(root, "endpoint"));
            }
            if (sourceRef.isBlank() && !provider.isBlank()) {
                String providerTarget = firstDirectText(
                        root,
                        "resolvedCode",
                        "symbol",
                        "code",
                        "ticker"
                );
                sourceRef = stableProviderRef(
                        provider,
                        capabilityId,
                        providerTarget.isBlank() ? ticker : providerTarget
                );
            }

            Instant asOf = parseBusinessInstant(
                    firstDirectText(root, "asOf", "as_of")
            );
            if (asOf == null) {
                asOf = extractBusinessAsOf(
                        selectedSearchResult == null ? root : selectedSearchResult
                );
            }
            return new EvidenceProvenance(
                    sourceRef,
                    provider,
                    asOf
            );
        } catch (Exception ignored) {
            return EvidenceProvenance.empty();
        }
    }

    private boolean isSecEvidence(String capabilityId, String cik) {
        String normalized = capabilityId == null ? "" : capabilityId;
        return normalized.startsWith("getStructuredFinancials")
                || normalized.startsWith("ingestCompanyFilings")
                || (normalized.startsWith("getFinancialReports") && !cik.isBlank());
    }

    private boolean isIbkrEvidence(String capabilityId) {
        return capabilityId != null
                && capabilityId.toLowerCase().contains("ibkr");
    }

    private boolean isSearchEvidence(String capabilityId) {
        if (capabilityId == null) {
            return false;
        }
        String normalized = capabilityId.toLowerCase();
        return normalized.contains("search") || "getstocknews".equals(normalized);
    }

    private String firstDirectText(JsonNode root, String... fieldNames) {
        if (root == null || !root.isObject()) {
            return "";
        }
        for (String fieldName : fieldNames) {
            JsonNode value = root.get(fieldName);
            if (value != null && value.isValueNode()) {
                String text = value.asText("").trim();
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return "";
    }

    private JsonNode firstSearchResult(JsonNode root) {
        JsonNode results = root.path("results");
        if (!results.isArray()) {
            return null;
        }
        for (JsonNode result : results) {
            String url = firstDirectText(
                    result,
                    "url",
                    "link",
                    "sourceRef",
                    "source_ref",
                    "documentUrl",
                    "document_url"
            );
            if (url.startsWith("https://") || url.startsWith("http://")) {
                return result;
            }
        }
        return null;
    }

    private String secCompanyFactsUrl(String cik) {
        String digits = cik == null ? "" : cik.replaceAll("\\D", "");
        if (digits.isBlank()) {
            return "";
        }
        if (digits.length() > 10) {
            return "";
        }
        return "https://data.sec.gov/api/xbrl/companyfacts/CIK"
                + "0".repeat(10 - digits.length())
                + digits
                + ".json";
    }

    private String ibkrHistoryRef(JsonNode root, String ticker) {
        String conid = firstDirectText(root, "conid");
        String period = firstDirectText(root, "period");
        String bar = firstDirectText(root, "bar");
        StringBuilder ref = new StringBuilder(
                "ibkr://client-portal/iserver/marketdata/history");
        appendQuery(ref, "conid", conid);
        if (conid.isBlank()) {
            appendQuery(ref, "symbol", ticker);
        }
        appendQuery(ref, "period", period);
        appendQuery(ref, "bar", bar);
        return ref.toString();
    }

    private String stableProviderRef(
            String provider,
            String capabilityId,
            String ticker
    ) {
        String normalizedProvider = provider.toLowerCase()
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (normalizedProvider.isBlank()) {
            return "";
        }
        String normalizedCapability = capabilityId == null
                ? "data"
                : capabilityId.replaceAll("[^A-Za-z0-9._-]+", "-")
                        .replaceAll("(^-+|-+$)", "");
        StringBuilder ref = new StringBuilder()
                .append("provider://")
                .append(normalizedProvider)
                .append('/')
                .append(normalizedCapability);
        appendQuery(ref, "target", ticker);
        return ref.toString();
    }

    private String allowlistedSourceRef(String sourceRef) {
        if (sourceRef == null) {
            return "";
        }
        String normalized = sourceRef.trim();
        if (normalized.startsWith("https://")
                || normalized.startsWith("http://")
                || normalized.startsWith("ibkr://")
                || normalized.startsWith("provider://")) {
            return normalized;
        }
        return "";
    }

    private void appendQuery(StringBuilder ref, String name, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        ref.append(ref.indexOf("?") >= 0 ? '&' : '?')
                .append(URLEncoder.encode(name, StandardCharsets.UTF_8))
                .append('=')
                .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    private Instant extractBusinessAsOf(JsonNode root) {
        Instant explicit = parseBusinessInstant(firstDirectText(root, "asOf", "as_of"));
        if (explicit != null) {
            return explicit;
        }
        List<Instant> candidates = new ArrayList<>();
        collectBusinessTimes(root, candidates);
        return candidates.stream().max(Instant::compareTo).orElse(null);
    }

    private void collectBusinessTimes(JsonNode node, List<Instant> candidates) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                String normalizedName = field.getKey()
                        .toLowerCase()
                        .replace("_", "")
                        .replace("-", "");
                if (BUSINESS_TIME_FIELDS.contains(normalizedName)) {
                    Instant parsed = parseBusinessInstant(field.getValue());
                    if (parsed != null) {
                        candidates.add(parsed);
                    }
                }
                collectBusinessTimes(field.getValue(), candidates);
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                collectBusinessTimes(item, candidates);
            }
        }
    }

    private Instant parseBusinessInstant(JsonNode value) {
        if (value == null || value.isNull() || !value.isValueNode()) {
            return null;
        }
        if (value.isIntegralNumber()) {
            long epoch = value.asLong();
            if (epoch >= 1_000_000_000_000L) {
                return Instant.ofEpochMilli(epoch);
            }
            if (epoch >= 1_000_000_000L) {
                return Instant.ofEpochSecond(epoch);
            }
            return null;
        }
        return parseBusinessInstant(value.asText(""));
    }

    private Instant parseBusinessInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        try {
            return Instant.parse(normalized);
        } catch (DateTimeParseException ignored) {
            // Try the bounded business date formats below.
        }
        try {
            return OffsetDateTime.parse(normalized).toInstant();
        } catch (DateTimeParseException ignored) {
            // Continue.
        }
        try {
            return ZonedDateTime.parse(
                    normalized,
                    DateTimeFormatter.RFC_1123_DATE_TIME
            ).toInstant();
        } catch (DateTimeParseException ignored) {
            // Continue.
        }
        try {
            return LocalDateTime.parse(normalized).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            // Continue.
        }
        try {
            return LocalDate.parse(normalized).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private boolean isSuccessfulNoResults(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String compact = value.replaceAll("\\s+", "");
        return compact.equals("[]")
                || compact.contains("\"results\":[]")
                || compact.contains("\"news\":[]")
                || compact.contains("\"items\":[]")
                || compact.contains("\"data\":[]");
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((value == null ? "" : value)
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
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
            boolean marketOk,
            boolean newsOk,
            EvidenceLedger evidenceLedger,
            HarnessDecision harnessDecision) {

        /**
         * Compatibility constructor for existing tests and callers that do not yet provide a ledger.
         */
        public EvidenceCollection(
                String contextMarkdown,
                AnalysisState state,
                boolean sufficientForRecommendation,
                boolean tickerResolved,
                boolean fundamentalsOk,
                boolean marketOk
        ) {
            this(
                    contextMarkdown,
                    state,
                    sufficientForRecommendation,
                    tickerResolved,
                    fundamentalsOk,
                    marketOk,
                    false,
                    state == null || state.getEvidenceLedger() == null
                            ? EvidenceLedger.empty()
                            : state.getEvidenceLedger(),
                    new HarnessDecision(
                            sufficientForRecommendation
                                    ? HarnessOutcome.PASS
                                    : HarnessOutcome.DEGRADE,
                            List.of(),
                            List.of()
                    )
            );
        }
    }

    private record EvidenceSnapshot(
            String text,
            boolean hasUsableData,
            List<EvidenceEnvelope> evidence
    ) {
        private EvidenceSnapshot {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }
    }

    private record ToolObservation(
            String text,
            EvidenceStatus status,
            Instant observedAt
    ) {
        private boolean hasUsableData() {
            return status == EvidenceStatus.AVAILABLE;
        }
    }

    private record EvidenceProvenance(
            String sourceRef,
            String provider,
            Instant asOf
    ) {
        private static EvidenceProvenance empty() {
            return new EvidenceProvenance("", "", null);
        }
    }

    @FunctionalInterface
    private interface ToolCall {
        String call();
    }
}
