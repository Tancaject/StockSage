package com.stocksage.service;

import com.stocksage.client.DataServicePayloads;
import com.stocksage.agent.PlanAction;
import com.stocksage.capability.CapabilityResult;
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
import com.stocksage.harness.HarnessModels.TargetResolutionStatus;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 收集 DEEP 深度研究所需的确定性证据快照。
 *
 * <p>请求内联降级与后台 worker 复用同一套行为：按基本面、市场、新闻维度调用工具，
 * 把原始结果转换为带 capability、payloadHash、asOf、sourceRef 的 {@link EvidenceEnvelope}，
 * 再交给 {@link ResearchHarness} 和 {@link DeepResearchCompletionPolicy} 判断是否可继续辩论/评级。</p>
 *
 * <p>边界：工具失败会形成明确状态而非伪造证据；恢复只重试策略白名单维度，且保留 durable attempts。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeepEvidenceCollector {

    /** DEEP 新闻查询的最大字符数；ticker 固定放在最前面，截断不会丢失标的。 */
    private static final int NEWS_QUERY_MAX_LENGTH = 180;
    /** 有可引用资格审计信息的证据段边界；工具正文中的同名标记会被转义。 */
    private static final String SNAPSHOT_EVIDENCE_BEGIN = "[[STOCKSAGE_EVIDENCE_BEGIN]]";
    private static final String SNAPSHOT_EVIDENCE_END = "[[STOCKSAGE_EVIDENCE_END]]";
    /** 证据段中真正可供论点引用的有界正文边界。 */
    private static final String SNAPSHOT_CONTENT_BEGIN = "[[STOCKSAGE_CONTENT_BEGIN]]";
    private static final String SNAPSHOT_CONTENT_END = "[[STOCKSAGE_CONTENT_END]]";
    /** 仅用于解释调度/缺失原因、不能成为论据的段边界。 */
    private static final String SNAPSHOT_NOTE_BEGIN = "[[STOCKSAGE_NOTE_BEGIN]]";
    private static final String SNAPSHOT_NOTE_END = "[[STOCKSAGE_NOTE_END]]";
    /** DEEP 计划中预期的 Agent 动作清单，主要用于日志和可观测性。 */
    private static final List<PlanAction> DEEP_ACTIONS = List.of(
            PlanAction.FUNDAMENTALS_AGENT,
            PlanAction.MARKET_AGENT,
            PlanAction.NEWS_AGENT,
            PlanAction.BULL_RESEARCHER,
            PlanAction.BEAR_RESEARCHER,
            PlanAction.RESEARCH_MANAGER
    );

    /** 获取 K 线、财务指标、技术指标和板块上下文。 */
    private final MarketTools marketTools;
    /** 获取股票新闻与网页/新闻搜索结果。 */
    private final NewsTools newsTools;
    /** 获取结构化财报与 SEC 公司事实。 */
    private final FundamentalsTools fundamentalsTools;
    /** 判断 ticker 市场路径与 SEC 适用性。 */
    private final TickerResolutionService tickerResolutionService;
    /** 向 SSE/后台 trace 发出证据收集进度。 */
    private final ChatStreamEmitter chatStreamEmitter;
    /** 为可并行工具调用提供受控执行线程。 */
    private final AsyncTaskExecutor agentTaskExecutor;
    /** 记录并返回每次证据闸门决定。 */
    private final ResearchHarness researchHarness;
    /** 定义最低证据、恢复白名单和尝试上限。 */
    private final DeepResearchCompletionPolicy completionPolicy;
    private final EvidenceEnvelopeMapper evidenceEnvelopeMapper;

    /** 搜索类工具最多返回的结果数。 */
    @Value("${stocksage.chat.tool-prefetch.max-search-results:5}")
    private int toolPrefetchMaxSearchResults;

    /** 单个确定性工具调用的超时秒数。 */
    @Value("${stocksage.chat.tool-prefetch.per-tool-timeout-seconds:8}")
    private long toolPrefetchPerToolTimeoutSeconds;

    /** 是否允许证据路径自动触发 EDGAR 摄取。 */
    @Value("${stocksage.chat.auto-edgar-ingest.enabled:false}")
    private boolean autoEdgarIngestEnabled;

    /**
     * 为深度研究流程收集确定性基本面、市场和新闻快照。
     *
     * @param primaryTicker 已解析目标 ticker；空值会构造 unresolved ledger
     * @param userQuery 用户原始问题
     * @param traceId 研究 trace ID
     * @param conversationId 会话 ID，用于进度事件
     * @return 上下文、AnalysisState、EvidenceLedger 和首次 Harness 决定
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
        if (tickerResolutionService.hasConflictingExplicitTicker(userQuery, ticker)) {
            context.append("## resolvedStockIdentity\n")
                    .append("Multiple stock targets were explicitly requested, but this DEEP run supports one target. No evidence was fetched.\n\n");
            log.warn("DEEP deterministic prefetch blocked because multiple stock targets were resolved, traceId={}", traceId);
            emitProgress(traceId, conversationId, "observation",
                    "检测到多个分析标的；当前单标的 DEEP 流程已停止，避免混用不同公司的证据。");
            EvidenceLedger ledger = new EvidenceLedger(
                    new TargetIdentity(ticker, ticker, TargetResolutionStatus.AMBIGUOUS), List.of());
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

        // 依次调用三类确定性工具构造证据；每类内部可并行，但维度结果独立验收。
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

        EvidenceSnapshot news = buildNewsSnapshot(ticker, userQuery);
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
        // 调用 Harness 记录证据快照并按完成策略决定继续、恢复还是降级。
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
     * 只重新收集完成策略明确允许恢复的证据维度。
     *
     * @param existing 现有证据集合
     * @param recoveryActions 策略返回的恢复动作
     * @param traceId 研究 trace ID
     * @param conversationId 会话 ID
     * @return 替换目标维度后的证据集合
     */
    public EvidenceCollection recover(
            EvidenceCollection existing,
            List<RecoveryAction> recoveryActions,
            String traceId,
            Long conversationId
    ) {
        return recover(existing, recoveryActions, Map.of(), traceId, conversationId);
    }

    /**
     * 在保留 durable checkpoint 尝试次数的前提下执行证据恢复。
     *
     * @param existing 现有证据集合
     * @param recoveryActions 策略返回的恢复动作
     * @param previousAttempts 已持久化的各动作尝试次数
     * @param traceId 研究 trace ID
     * @param conversationId 会话 ID
     * @return 恢复后重新经过 Harness 判断的证据集合
     */
    public EvidenceCollection recover(
            EvidenceCollection existing,
            List<RecoveryAction> recoveryActions,
            Map<RecoveryAction, Integer> previousAttempts,
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
        if (previousAttempts != null) {
            previousAttempts.forEach((action, count) -> {
                if (action != null) {
                    attempts.put(action, Math.max(0, count == null ? 0 : count));
                }
            });
        }

        if (recoveryActions.contains(RecoveryAction.RETRY_FUNDAMENTALS)) {
            attempts.merge(RecoveryAction.RETRY_FUNDAMENTALS, 1, Integer::sum);
            EvidenceSnapshot fundamentals = buildFundamentalsSnapshot(ticker);
            state.setFundamentalsReport(fundamentals.text());
            replaceDimension(merged, EvidenceDimension.FUNDAMENTALS, fundamentals.evidence());
            fundamentalsOk = fundamentals.hasUsableData();
            emitProgress(traceId, conversationId, "observation",
                    "Fundamentals evidence recovery completed.");
        }
        if (recoveryActions.contains(RecoveryAction.RETRY_MARKET)) {
            attempts.merge(RecoveryAction.RETRY_MARKET, 1, Integer::sum);
            EvidenceSnapshot market = buildMarketSnapshot(ticker);
            state.setMarketReport(market.text());
            replaceDimension(merged, EvidenceDimension.MARKET, market.evidence());
            marketOk = market.hasUsableData();
            emitProgress(traceId, conversationId, "observation",
                    "Market evidence recovery completed.");
        }
        if (recoveryActions.contains(RecoveryAction.RETRY_NEWS)) {
            attempts.merge(RecoveryAction.RETRY_NEWS, 1, Integer::sum);
            EvidenceSnapshot news = buildNewsSnapshot(ticker, state.getQuery());
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

    /**
     * 从 durable checkpoint 重建策略输入并重新评估。
     *
     * <p>旧检查点若没有 evidence ledger，会被明确转为 unresolved 空账本；生产策略因此安全失败，
     * 不会基于无法核验的旧文本直接开始辩论。</p>
     *
     * @param checkpointState 持久化分析状态
     * @param recoveryAttempts 已持久化恢复计数
     * @param traceId 研究 trace ID
     * @return 重新计算可用维度和 Harness 决定的证据集合
     */
    public EvidenceCollection reevaluateCheckpoint(
            AnalysisState checkpointState,
            Map<RecoveryAction, Integer> recoveryAttempts,
            String traceId
    ) {
        AnalysisState state = checkpointState == null
                ? AnalysisState.builder().build()
                : checkpointState;
        EvidenceLedger ledger = state.getEvidenceLedger() == null
                ? EvidenceLedger.empty()
                : state.getEvidenceLedger();
        String checkpointTicker = state.getPrimaryTicker();
        if ((checkpointTicker == null || checkpointTicker.isBlank()) && ledger.target().isResolved()) {
            checkpointTicker = ledger.target().canonicalKey();
        }
        if (tickerResolutionService.hasConflictingExplicitTicker(state.getQuery(), checkpointTicker)) {
            ledger = new EvidenceLedger(
                    new TargetIdentity(checkpointTicker, checkpointTicker, TargetResolutionStatus.AMBIGUOUS),
                    ledger.evidence());
            state.setEvidenceLedger(ledger);
        }
        boolean fundamentalsOk = ledger.hasUsable(EvidenceDimension.FUNDAMENTALS);
        boolean marketOk = ledger.hasUsable(EvidenceDimension.MARKET);
        boolean newsOk = ledger.hasUsable(EvidenceDimension.NEWS);
        HarnessDecision decision = researchHarness.observeEvidence(
                traceId,
                completionPolicy,
                new RunContext("DEEP", recoveryAttempts),
                ledger
        );
        return new EvidenceCollection(
                rebuildContext(state),
                state,
                decision.allowsRecommendation(),
                ledger.target().isResolved(),
                fundamentalsOk,
                marketOk,
                newsOk,
                ledger,
                decision
        );
    }

    /**
     * 把一次已授权的聚焦新闻搜索追加到现有快照，并重新执行当前 Evidence Gate。
     *
     * <p>不可引用、跨标的或重复结果不会进入报告和 Ledger；基础 NEWS 证据永远不被替换。</p>
     */
    EvidenceCollection appendFocusedNews(
            EvidenceCollection existing,
            CapabilityResult result,
            String traceId
    ) {
        if (existing == null || result == null || existing.state() == null) {
            return existing;
        }
        AnalysisState state = existing.state();
        EvidenceLedger current = existing.evidenceLedger();
        String ticker = state.getPrimaryTicker() == null ? "" : state.getPrimaryTicker().strip();
        String content = result.content() == null ? "" : result.content();
        ToolObservation observation = new ToolObservation(
                content,
                capabilityEvidenceStatus(content),
                Instant.now()
        );
        EvidenceEnvelope envelope = evidenceEnvelopeMapper.map(
                EvidenceDimension.NEWS,
                ticker,
                result.capabilityId(),
                observation.text(), observation.status(), observation.observedAt(),
                true
        );
        if (!envelope.hasUsableData()
                || !envelope.hasProvenance()
                || !envelope.approvedReadOnly()
                || current == null
                || !current.target().isResolved()
                || !current.target().canonicalKey().equals(envelope.targetKey())
                || current.evidenceIds().contains(envelope.evidenceId())) {
            return existing;
        }

        List<EvidenceEnvelope> merged = new ArrayList<>(current.evidence());
        merged.add(envelope);
        EvidenceLedger ledger = new EvidenceLedger(current.target(), merged);
        state.setEvidenceLedger(ledger);

        StringBuilder news = new StringBuilder(state.getNewsReport() == null ? "" : state.getNewsReport());
        appendEvidenceSnapshotItem(
                news,
                "focusedNewsSearch",
                envelope,
                content,
                3500
        );
        state.setNewsReport(news.toString().strip());

        Map<RecoveryAction, Integer> recoveryAttempts = state.getHarnessSnapshot() == null
                ? Map.of()
                : state.getHarnessSnapshot().recoveryAttempts();
        return reevaluateCheckpoint(state, recoveryAttempts, traceId);
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
        appendNonCitableSnapshotNote(report, "snapshotMetadata", "Ticker: " + ticker, 256);
        boolean hasData = false;

        ToolObservation financialReports = runPrefetchTool("Fundamentals Agent/getFinancialReports",
                () -> fundamentalsTools.getFinancialReports(ticker, "annual", 5));
        hasData |= financialReports.hasUsableData();
        captureSnapshotEvidence(
                report, evidence, EvidenceDimension.FUNDAMENTALS, ticker,
                "getFinancialReports", "getFinancialReports(annual,5)", financialReports, 4500);

        ToolObservation companyReports = runPrefetchTool("Fundamentals Agent/searchCompanyReports",
                () -> fundamentalsTools.searchCompanyReports(ticker, "财报", toolPrefetchMaxSearchResults));
        hasData |= companyReports.hasUsableData();
        captureSnapshotEvidence(
                report, evidence, EvidenceDimension.FUNDAMENTALS, ticker,
                "searchCompanyReports", "searchCompanyReports", companyReports, 3000);

        if (tickerResolutionService.isLikelySecTicker(ticker)) {
            ToolObservation structured = runPrefetchTool("Fundamentals Agent/getStructuredFinancials",
                    () -> fundamentalsTools.getStructuredFinancials(ticker));
            hasData |= structured.hasUsableData();
            captureSnapshotEvidence(
                    report, evidence, EvidenceDimension.FUNDAMENTALS, ticker,
                    "getStructuredFinancials", "getStructuredFinancials(SEC XBRL)", structured, 4500);
            if (autoEdgarIngestEnabled) {
                ToolObservation ingestion = runPrefetchTool("Fundamentals Agent/ingestCompanyFilings",
                        () -> fundamentalsTools.ingestCompanyFilings(ticker, "10-K", 1));
                captureSnapshotEvidence(
                        report, evidence, EvidenceDimension.FUNDAMENTALS, ticker,
                        "ingestCompanyFilings", "ingestCompanyFilings(10-K x1)",
                        ingestion, 2000, false);
            } else {
                appendNonCitableSnapshotNote(report, "ingestCompanyFilings",
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
        appendNonCitableSnapshotNote(report, "snapshotMetadata", "Ticker: " + ticker, 256);
        boolean hasData = false;

        String klineTool = marketKLineToolName(ticker);
        ToolObservation kline = runPrefetchTool("Market Agent/" + klineTool,
                () -> getMarketKLine(ticker));
        hasData |= kline.hasUsableData();
        captureSnapshotEvidence(
                report, evidence, EvidenceDimension.MARKET, ticker,
                klineTool, klineTool, kline, 4500);

        String financialTool = marketFinancialToolName(ticker);
        ToolObservation financial = runPrefetchTool("Market Agent/" + financialTool,
                () -> getMarketFinancialContext(ticker));
        hasData |= financial.hasUsableData();
        captureSnapshotEvidence(
                report, evidence, EvidenceDimension.MARKET, ticker,
                financialTool, financialTool, financial, 3000);

        String technicalTool = marketTechnicalToolName(ticker);
        ToolObservation technical = runPrefetchTool("Market Agent/" + technicalTool,
                () -> getMarketTechnicalContext(ticker));
        hasData |= technical.hasUsableData();
        captureSnapshotEvidence(
                report, evidence, EvidenceDimension.MARKET, ticker,
                technicalTool, technicalTool, technical, 3000);

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
            appendNonCitableSnapshotNote(report, "sectorContext",
                    "No reliable sector mapping was resolved for this ticker, so sector performance was not fetched.",
                    600);
            return;
        }
        if (tickerResolutionService.isLikelySecTicker(ticker)) {
            appendNonCitableSnapshotNote(report, "sectorContext",
                    "Resolved sector/industry context: " + sector
                            + ". US sector performance is not served by the current A/HK sector data source, so it was not fetched.",
                    700);
            return;
        }
        ToolObservation sectorPerformance = runPrefetchTool("Market Agent/getSectorPerformance",
                () -> marketTools.getSectorPerformance(sector));
        captureSnapshotEvidence(
                report, evidence, EvidenceDimension.MARKET, ticker,
                "getSectorPerformance", "getSectorPerformance", sectorPerformance, 2500);
    }

    /** 构造新闻和网页搜索预取快照。 */
    private EvidenceSnapshot buildNewsSnapshot(String ticker, String userQuery) {
        StringBuilder report = new StringBuilder();
        List<EvidenceEnvelope> evidence = new ArrayList<>();
        String query = buildNewsQuery(ticker, userQuery);
        appendNonCitableSnapshotNote(
                report, "snapshotMetadata", "Ticker: " + ticker + "\nNews query: " + query, 600);
        boolean hasData = false;

        ToolObservation stockNews = runPrefetchTool("News Agent/getStockNews",
                () -> newsTools.getStockNews(ticker, 7));
        hasData |= stockNews.hasUsableData();
        captureSnapshotEvidence(
                report, evidence, EvidenceDimension.NEWS, ticker,
                "getStockNews", "getStockNews(7d)", stockNews, 3500);

        // 深度投研预取：搜索证据质量直接影响最终报告，这里显式开启 Tavily advanced 深度检索。
        ToolObservation searchNews = runPrefetchTool("News Agent/searchNews",
                () -> newsTools.searchNews(query, toolPrefetchMaxSearchResults, true));
        hasData |= searchNews.hasUsableData();
        captureSnapshotEvidence(
                report, evidence, EvidenceDimension.NEWS, ticker,
                "searchNews", "searchNews", searchNews, 3500);

        ToolObservation webSearch = runPrefetchTool("News Agent/webSearch",
                () -> newsTools.webSearch(query, toolPrefetchMaxSearchResults, true));
        hasData |= webSearch.hasUsableData();
        captureSnapshotEvidence(
                report, evidence, EvidenceDimension.NEWS, ticker,
                "webSearch", "webSearch", webSearch, 3500);

        return new EvidenceSnapshot(report.toString().trim(), hasData, evidence);
    }

    /**
     * 先构造证据元数据，再把同一 evidenceId 和有界正文写入快照。
     *
     * <p>这个顺序让辩论合同可以把引用 ID 确定性映射回模型实际看到的文本，
     * 同时仍不把完整工具 payload 放入 EvidenceLedger。</p>
     */
    private void captureSnapshotEvidence(
            StringBuilder report,
            List<EvidenceEnvelope> evidence,
            EvidenceDimension dimension,
            String ticker,
            String capabilityId,
            String displayName,
            ToolObservation observation,
            int maxLength
    ) {
        captureSnapshotEvidence(
                report, evidence, dimension, ticker, capabilityId, displayName,
                observation, maxLength, true);
    }

    /** 支持显式标记非只读能力的证据快照重载。 */
    private void captureSnapshotEvidence(
            StringBuilder report,
            List<EvidenceEnvelope> evidence,
            EvidenceDimension dimension,
            String ticker,
            String capabilityId,
            String displayName,
            ToolObservation observation,
            int maxLength,
            boolean approvedReadOnly
    ) {
        EvidenceEnvelope envelope = evidenceEnvelopeMapper.map(
                dimension, ticker, capabilityId, observation.text(), observation.status(),
                observation.observedAt(), approvedReadOnly);
        evidence.add(envelope);
        appendEvidenceSnapshotItem(report, displayName, envelope, observation.text(), maxLength);
    }

    /** 写入可被 DebateContractParser 精确定位的有界证据段。 */
    private void appendEvidenceSnapshotItem(
            StringBuilder report,
            String name,
            EvidenceEnvelope envelope,
            String value,
            int maxLength
    ) {
        report.append('\n').append(SNAPSHOT_EVIDENCE_BEGIN).append('\n')
                .append("name: ").append(snapshotMetadata(name, 160)).append('\n')
                .append("evidenceId: ").append(envelope.evidenceId()).append('\n')
                .append("status: ").append(envelope.status().name()).append('\n')
                .append("provider: ").append(snapshotMetadata(envelope.provider(), 240)).append('\n')
                .append("sourceRef: ").append(snapshotMetadata(envelope.sourceRef(), 500)).append('\n')
                .append("asOf: ").append(envelope.asOf() == null ? "unknown" : envelope.asOf()).append('\n')
                .append("citable: ").append(envelope.hasProvenance() && envelope.approvedReadOnly()).append('\n')
                .append(SNAPSHOT_CONTENT_BEGIN).append('\n')
                .append(snapshotContent(value, maxLength)).append('\n')
                .append(SNAPSHOT_CONTENT_END).append('\n')
                .append(SNAPSHOT_EVIDENCE_END).append('\n');
    }

    /** 写入无 EvidenceEnvelope 的说明段，并明确禁止模型引用。 */
    private void appendNonCitableSnapshotNote(
            StringBuilder report,
            String name,
            String value,
            int maxLength
    ) {
        report.append('\n').append(SNAPSHOT_NOTE_BEGIN).append('\n')
                .append("name: ").append(snapshotMetadata(name, 160)).append('\n')
                .append("evidenceId: none\n")
                .append("citable: false\n")
                .append("instruction: This section is operational context only and MUST NOT be cited as evidence.\n")
                .append(SNAPSHOT_CONTENT_BEGIN).append('\n')
                .append(snapshotContent(value, maxLength)).append('\n')
                .append(SNAPSHOT_CONTENT_END).append('\n')
                .append(SNAPSHOT_NOTE_END).append('\n');
    }

    /** 把元数据收敛到单行，避免来源字段破坏快照边界。 */
    private String snapshotMetadata(String value, int maxLength) {
        String normalized = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').strip();
        return escapeSnapshotMarkers(PromptText.truncate(normalized, maxLength));
    }

    /** 截断正文并转义协议标记，防止工具内容伪造证据段边界。 */
    private String snapshotContent(String value, int maxLength) {
        return escapeSnapshotMarkers(PromptText.truncate(value == null ? "" : value, maxLength));
    }

    /** 对工具返回中恰好出现的内部边界词做不可解析化转义。 */
    private String escapeSnapshotMarkers(String value) {
        return value
                .replace(SNAPSHOT_EVIDENCE_BEGIN, "[[STOCKSAGE_EVIDENCE_BEGIN_ESCAPED]]")
                .replace(SNAPSHOT_EVIDENCE_END, "[[STOCKSAGE_EVIDENCE_END_ESCAPED]]")
                .replace(SNAPSHOT_CONTENT_BEGIN, "[[STOCKSAGE_CONTENT_BEGIN_ESCAPED]]")
                .replace(SNAPSHOT_CONTENT_END, "[[STOCKSAGE_CONTENT_END_ESCAPED]]")
                .replace(SNAPSHOT_NOTE_BEGIN, "[[STOCKSAGE_NOTE_BEGIN_ESCAPED]]")
                .replace(SNAPSHOT_NOTE_END, "[[STOCKSAGE_NOTE_END_ESCAPED]]");
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

    /** 将 Capability 的统一文本结果归一化为现有 Evidence 状态。 */
    private EvidenceStatus capabilityEvidenceStatus(String content) {
        if (content == null || content.isBlank()) {
            return EvidenceStatus.EMPTY;
        }
        return DataServicePayloads.isFailure(content)
                ? EvidenceStatus.FAILED
                : EvidenceStatus.AVAILABLE;
    }

    private String buildNewsQuery(String ticker, String userQuery) {
        String target = ticker == null ? "" : ticker.strip();
        String focus = userQuery == null ? "" : userQuery.replaceAll("\\s+", " ").strip();
        String query = (target + " " + focus).strip();
        return query.length() <= NEWS_QUERY_MAX_LENGTH
                ? query
                : query.substring(0, NEWS_QUERY_MAX_LENGTH).strip();
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
     *
     * @param contextMarkdown 供 Agent/报告综合使用的确定性上下文
     * @param state 当前 AnalysisState
     * @param sufficientForRecommendation 策略是否允许评级
     * @param tickerResolved 是否解析了目标标的
     * @param fundamentalsOk 是否有可用基本面证据
     * @param marketOk 是否有可用市场证据
     * @param newsOk 是否有可用新闻证据
     * @param evidenceLedger 结构化证据账本
     * @param harnessDecision 当前 Harness 决定
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

    @FunctionalInterface
    private interface ToolCall {
        String call();
    }
}
