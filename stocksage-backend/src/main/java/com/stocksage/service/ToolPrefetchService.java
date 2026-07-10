package com.stocksage.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.agent.Coordinator;
import com.stocksage.agent.ExecutionPlan;
import com.stocksage.agent.PlanAction;
import com.stocksage.agent.FundamentalsAgent;
import com.stocksage.agent.MarketAgent;
import com.stocksage.agent.NewsAgent;
import com.stocksage.model.dto.AnalysisState;
import com.stocksage.model.dto.InvestmentReport;
import com.stocksage.model.entity.ResearchTask;
import com.stocksage.tool.ChatStreamEmitter;
import com.stocksage.tool.FundamentalsTools;
import com.stocksage.tool.MarketTools;
import com.stocksage.tool.NewsTools;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Coordinator 计划的确定性预取编排。
 *
 * <p>在最终回答模型被调用之前，按公开计划执行工具与分析师智能体，把观察结果
 * 拼装为受证据约束的系统上下文；深度研究路线在这里完成证据门槛判定、
 * 报告版本复用和 Bull/Bear 辩论任务的触发。从 ChatService 拆出。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolPrefetchService {

    private final MarketTools marketTools;
    private final NewsTools newsTools;
    private final FundamentalsTools fundamentalsTools;
    private final FundamentalsAgent fundamentalsAgent;
    private final MarketAgent marketAgent;
    private final NewsAgent newsAgent;
    private final TickerResolutionService tickerResolutionService;
    private final ReportMarkdownRenderer reportRenderer;
    private final DeepEvidenceCollector deepEvidenceCollector;
    private final DeepResearchPipeline deepResearchPipeline;
    private final InvestmentReportVersionService investmentReportVersionService;
    private final ResearchTaskService researchTaskService;
    private final ResearchTaskQueue researchTaskQueue;
    private final KnowledgeIngestionService knowledgeIngestionService;
    private final ChatStreamEmitter chatStreamEmitter;
    private final ObjectMapper objectMapper;
    // 分析师/工具预取和后台记忆更新共用的工作线程池（AsyncConfig#agentTaskExecutor，6 线程 daemon）。
    private final AsyncTaskExecutor agentTaskExecutor;

    @Value("${stocksage.chat.tool-prefetch.enabled:true}")
    private boolean toolPrefetchEnabled;

    @Value("${stocksage.chat.tool-prefetch.max-search-results:5}")
    private int toolPrefetchMaxSearchResults;

    @Value("${stocksage.chat.search-ingest.enabled:true}")
    private boolean searchIngestEnabled;

    @Value("${stocksage.chat.search-ingest.ttl-days:7}")
    private long searchIngestTtlDays;

    @Value("${stocksage.agent.prefetch.timeout-seconds:120}")
    private long agentPrefetchTimeoutSeconds;

    @Value("${stocksage.research-task.user-max-active:3}")
    private int userMaxActive = 3;

    /**
     * 在最终回答前执行 Coordinator 计划的证据收集步骤。
     * 生成的类 Markdown 片段会作为系统上下文注入，
     * 使最终模型回答稳定且受证据约束。
     */
    /**
     * 根据 Coordinator 规划预取确定性工具和智能体上下文。
     */
    public PreparedToolContext prefetch(
            ExecutionPlan executionPlan,
            String userQuery,
            String traceId,
            Long conversationId,
            String userId,
            Coordinator.SelectedModel selectedModel
    ) {
        if (!toolPrefetchEnabled || executionPlan == null || executionPlan.actions() == null) {
            return PreparedToolContext.empty();
        }

        List<PlanAction> actions = executionPlan.actions();
        String primaryTicker = tickerResolutionService.resolvePrimaryTicker(userQuery, conversationId);
        if (isDeepResearchPlan(actions)) {
            return submitDeepResearch(
                    userQuery, traceId, conversationId, userId, selectedModel, primaryTicker);
        }

        StringBuilder context = new StringBuilder();
        String directAnswer = "";
        // 先放标的身份，便于下游提示词在股票代码/公司解析不确定时拒绝无关 RAG 或搜索片段。
        appendResolvedStockIdentity(context, primaryTicker);

        emitProgress(traceId, conversationId, "thought",
                "正在执行深度分析预取：Fundamentals / Market / News / Bull-Bear Debate。");

        AnalysisState agentState = AnalysisState.builder()
                    .query(userQuery)
                    .primaryTicker(primaryTicker)
                    .build();
            // 阶段 1：并行运行分析师智能体，因为它们的输入不依赖彼此生成的文本。
            boolean needsFundamentals = actions.contains(PlanAction.FUNDAMENTALS_AGENT);
            boolean needsMarket = actions.contains(PlanAction.MARKET_AGENT);
            boolean needsNews = actions.contains(PlanAction.NEWS_AGENT);
            String agentInputContext = context.toString();

            if (needsFundamentals || needsMarket || needsNews) {
            CompletableFuture<String> fundamentalsFuture = needsFundamentals
                    ? CompletableFuture.supplyAsync(() -> safeAgentCall("Fundamentals Agent",
                            () -> fundamentalsAgent.analyze(userQuery, agentInputContext)), agentTaskExecutor)
                    : CompletableFuture.completedFuture(null);

            CompletableFuture<String> marketFuture = needsMarket
                    ? CompletableFuture.supplyAsync(() -> safeAgentCall("Market Agent",
                            () -> marketAgent.analyze(userQuery, agentInputContext)), agentTaskExecutor)
                    : CompletableFuture.completedFuture(null);

            CompletableFuture<String> newsFuture = needsNews
                    ? CompletableFuture.supplyAsync(() -> safeAgentCall("News Agent",
                            () -> newsAgent.analyze(userQuery, agentInputContext)), agentTaskExecutor)
                    : CompletableFuture.completedFuture(null);

            try {
                CompletableFuture.allOf(fundamentalsFuture, marketFuture, newsFuture)
                        .get(agentPrefetchTimeoutSeconds, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("Analyst prefetch timed out or failed after {}s: {}",
                        agentPrefetchTimeoutSeconds, e.getMessage());
                emitProgress(traceId, conversationId, "observation",
                        "部分 Analyst 预取超时，系统将基于已完成的数据继续生成回答。");
            }

            String fundamentalsResult = completedValue(fundamentalsFuture);
            String marketResult = completedValue(marketFuture);
            String newsResult = completedValue(newsFuture);

            if (fundamentalsResult != null) {
                agentState.setFundamentalsReport(fundamentalsResult);
                appendContextSection(context, "Fundamentals Agent", fundamentalsResult);
            }
            if (marketResult != null) {
                agentState.setMarketReport(marketResult);
                appendContextSection(context, "Market Agent", marketResult);
            }
            if (newsResult != null) {
                agentState.setNewsReport(newsResult);
                appendContextSection(context, "News Agent", newsResult);
            }
            }

        // 阶段 2：顺序执行依赖分析师结果的步骤，
        // 或者需要按可预测顺序使用已解析股票代码/工具观测的步骤。
        for (PlanAction action : actions) {
            switch (action) {
                case FUNDAMENTALS_AGENT, MARKET_AGENT, NEWS_AGENT -> { /* 已经在前面处理 */ }
                case RESEARCH_MANAGER -> { /* DEEP plans return through submitDeepResearch above. */ }
                case SEARCH_STOCKS -> appendToolObservation(context, "searchStocks",
                        () -> marketTools.searchStocks(userQuery, toolPrefetchMaxSearchResults));
                case GET_FINANCIAL_REPORTS, GET_STRUCTURED_FINANCIALS -> appendToolObservation(
                        context, "getFinancialReports",
                        () -> withResolvedTicker(primaryTicker,
                                ticker -> fundamentalsTools.getFinancialReports(ticker, "annual", 5)));
                case SEARCH_COMPANY_REPORTS -> {
                    String result = appendToolObservation(context, "searchCompanyReports",
                            () -> withResolvedTicker(primaryTicker,
                                    ticker -> fundamentalsTools.searchCompanyReports(ticker, "财报", toolPrefetchMaxSearchResults)));
                    asyncIngestSearchResults("searchCompanyReports", result, userQuery);
                }
                case GET_STOCK_KLINE -> appendToolObservation(
                        context, marketKLineToolName(primaryTicker),
                        () -> withResolvedTicker(primaryTicker,
                                this::getMarketKLine));
                case GET_FINANCIAL_METRICS -> appendToolObservation(
                        context, marketFinancialToolName(primaryTicker),
                        () -> withResolvedTicker(primaryTicker, this::getMarketFinancialContext));
                case GET_TECHNICAL_INDICATORS -> appendToolObservation(
                        context, marketTechnicalToolName(primaryTicker),
                        () -> withResolvedTicker(primaryTicker,
                                this::getMarketTechnicalContext));
                case SEARCH_NEWS -> {
                    String result = appendToolObservation(context, "searchNews",
                            () -> newsTools.searchNews(userQuery, toolPrefetchMaxSearchResults));
                    asyncIngestSearchResults("searchNews", result, userQuery);
                }
                case WEB_SEARCH -> {
                    String result = appendToolObservation(context, "webSearch",
                            () -> newsTools.webSearch(userQuery, toolPrefetchMaxSearchResults));
                    asyncIngestSearchResults("webSearch", result, userQuery);
                }
                case GET_MARKET_OVERVIEW -> appendToolObservation(
                        context, "getMarketOverview", marketTools::getMarketOverview);
                default -> {
                    // 部分计划动作需要结构化股票代码抽取，仍交给模型工具调用处理。
                }
            }
        }
        return new PreparedToolContext(context.toString().trim(), directAnswer);
    }

    /**
     * Submit a DEEP request without collecting evidence or invoking analyst models on the request thread.
     */
    private PreparedToolContext submitDeepResearch(
            String userQuery,
            String traceId,
            Long conversationId,
            String userId,
            Coordinator.SelectedModel selectedModel,
            String primaryTicker
    ) {
        int active = researchTaskService.countActiveTasks(userId);
        if (active >= Math.max(1, userMaxActive)) {
            String answer = reportRenderer.buildQuotaExceededAnswer(active, Math.max(1, userMaxActive));
            return new PreparedToolContext("", answer, null, traceId);
        }

        String submissionKey = researchTaskService.buildSubmissionKey(
                userId, primaryTicker, userQuery, conversationId);
        String payload = researchTaskService.buildSubmissionPayload(
                primaryTicker, userQuery, traceId, conversationId);
        ResearchTaskService.TaskCreation creation = researchTaskService.createIfAbsent(
                submissionKey,
                userId,
                conversationId,
                primaryTicker,
                ResearchTask.Stage.CREATED,
                payload
        );
        ResearchTask task = creation.task();
        boolean activeTask = task.getStatus() == ResearchTask.Status.PENDING
                || task.getStatus() == ResearchTask.Status.RUNNING;
        if (!creation.created() && activeTask) {
            String eventTraceId = taskTraceId(task, traceId);
            emitProgress(traceId, conversationId, "observation",
                    "匹配到进行中的深度研究任务，已切换为实时旁观。");
            return new PreparedToolContext(
                    "", reportRenderer.buildTaskAcceptedAnswer(task), task.getId(), eventTraceId);
        }

        if (!creation.created()) {
            ResearchTaskService.TaskReset reset = researchTaskService.resetForResubmission(task, payload);
            task = reset.task();
            if (!reset.reset()) {
                boolean resetWinnerActive = task.getStatus() == ResearchTask.Status.PENDING
                        || task.getStatus() == ResearchTask.Status.RUNNING;
                if (!resetWinnerActive) {
                    throw new IllegalStateException("research task resubmission race did not produce an active task");
                }
                return new PreparedToolContext(
                        "", reportRenderer.buildTaskAcceptedAnswer(task),
                        task.getId(), taskTraceId(task, traceId));
            }
        }

        try {
            researchTaskQueue.enqueue(task.getId());
            emitProgress(traceId, conversationId, "thought", "深度研究任务已受理，后台开始执行。");
            return new PreparedToolContext(
                    "", reportRenderer.buildTaskAcceptedAnswer(task), task.getId(), traceId);
        } catch (ResearchTaskQueue.QueueUnavailableException error) {
            log.warn("Task queue unavailable, falling back to inline DEEP execution: {}", error.getMessage());
            try {
                return runInlineDeepFallback(
                        primaryTicker, userQuery, traceId, conversationId, userId, selectedModel, task);
            } catch (RuntimeException inlineError) {
                throw inlineError;
            }
        }
    }

    /**
     * Preserve the former synchronous DEEP path for the explicit Redis queue outage fallback.
     */
    private PreparedToolContext runInlineDeepFallback(
            String primaryTicker,
            String userQuery,
            String traceId,
            Long conversationId,
            String userId,
            Coordinator.SelectedModel selectedModel,
            ResearchTask task
    ) {
        StringBuilder context = new StringBuilder();
        appendResolvedStockIdentity(context, primaryTicker);
        emitProgress(traceId, conversationId, "thought",
                "任务队列暂不可用，正在请求内同步完成深度研究。");
        Optional<ResearchTaskLeaseService.Lease> lease = researchTaskService.tryAcquire(task);
        if (lease.isEmpty()) {
            return new PreparedToolContext(
                    context.toString().trim(), reportRenderer.buildTaskAcceptedAnswer(task),
                    task.getId(), taskTraceId(task, traceId));
        }
        ResearchTaskLeaseService.Lease acquired = lease.orElseThrow();
        ResearchTask runningTask;
        try {
            runningTask = researchTaskService.startAttempt(
                    task, acquired.token(), ResearchTask.Stage.DATA_PREFETCH);
        } catch (RuntimeException error) {
            researchTaskService.release(acquired);
            throw error;
        }
        try {
            DeepEvidenceCollector.EvidenceCollection evidence = deepEvidenceCollector.collect(
                    primaryTicker, userQuery, traceId, conversationId);
            context.append(evidence.contextMarkdown());
            AnalysisState agentState = evidence.state();
            if (!evidence.sufficientForRecommendation()) {
                String answer = reportRenderer.buildInsufficientEvidenceReport(
                        primaryTicker,
                        evidence.tickerResolved(),
                        evidence.fundamentalsOk(),
                        evidence.marketOk()
                );
                researchTaskService.markSucceededForOwner(runningTask, acquired.token(), null);
                return new PreparedToolContext(context.toString().trim(), answer, null, traceId);
            }

            investmentReportVersionService.prepareHashes(agentState);
            Optional<InvestmentReport> reusableReport = investmentReportVersionService.findReusableReport(
                    userId, conversationId, agentState);
            if (reusableReport.isPresent()) {
                InvestmentReport report = reusableReport.orElseThrow();
                agentState.setInvestmentReport(report);
                appendContextSection(context, "Research Manager", safeReportJson(report));
                researchTaskService.markSucceededForOwner(runningTask, acquired.token(), null);
                return new PreparedToolContext(
                        context.toString().trim(), reportRenderer.buildFinalAnswerBrief(agentState), null, traceId);
            }

            emitProgress(traceId, conversationId, "thought",
                    "开始 Bull/Bear 辩论，并由 Research Manager 综合裁决。");
            String reportJson;
            try {
                reportJson = deepResearchPipeline.runResearchDebateWithTask(
                        traceId,
                        conversationId,
                        userId,
                        agentState,
                        selectedModel,
                        runningTask,
                        acquired
                );
            } catch (Exception error) {
                throw new IllegalStateException("inline DEEP research failed", error);
            }
            appendContextSection(context, "Research Manager", reportJson);
            if (agentState.getInvestmentReport() == null && reportJson != null && !reportJson.isBlank()) {
                try {
                    agentState.setInvestmentReport(objectMapper.readValue(reportJson, InvestmentReport.class));
                } catch (JsonProcessingException error) {
                    log.warn("Inline DEEP report could not be deserialized for rendering: {}", error.getMessage());
                }
            }
            String answer = agentState.getInvestmentReport() == null
                    ? reportJson
                    : reportRenderer.buildFinalAnswerBrief(agentState);
            return new PreparedToolContext(context.toString().trim(), answer, null, traceId);
        } catch (RuntimeException error) {
            researchTaskService.markFailedForOwner(runningTask, acquired.token(), error.getMessage());
            emitProgress(traceId, conversationId, "error", "同步深度研究执行失败：" + error.getMessage());
            throw error;
        } finally {
            researchTaskService.release(acquired);
        }
    }

    private String taskTraceId(ResearchTask task, String fallbackTraceId) {
        if (task == null || task.getPayloadJson() == null || task.getPayloadJson().isBlank()) {
            return fallbackTraceId;
        }
        try {
            String storedTraceId = objectMapper.readTree(task.getPayloadJson()).path("traceId").asText("").trim();
            return storedTraceId.isBlank() ? fallbackTraceId : storedTraceId;
        } catch (JsonProcessingException error) {
            log.warn("Unable to read traceId from research task payload, taskId={}: {}",
                    task.getId(), error.getMessage());
            return fallbackTraceId;
        }
    }

    /**
     * 判断规划动作是否需要进入深度研究流水线。
     */
    public boolean isDeepResearchPlan(List<PlanAction> actions) {
        return actions.contains(PlanAction.BULL_RESEARCHER)
                || actions.contains(PlanAction.BEAR_RESEARCHER)
                || actions.contains(PlanAction.RESEARCH_MANAGER);
    }

    /**
     * 根据市场选择 K 线工具名称。
     */
    private String marketKLineToolName(String ticker) {
        return tickerResolutionService.isLikelySecTicker(ticker) ? "getIbkrHistoricalBars(3m,1d)" : "getStockKLine(daily,60)";
    }

    /**
     * 根据市场选择财务指标工具名称。
     */
    private String marketFinancialToolName(String ticker) {
        return tickerResolutionService.isLikelySecTicker(ticker) ? "getStructuredFinancials(SEC XBRL)" : "getFinancialMetrics";
    }

    /**
     * 根据市场选择技术指标工具名称。
     */
    private String marketTechnicalToolName(String ticker) {
        return tickerResolutionService.isLikelySecTicker(ticker) ? "getIbkrHistoricalBars(6m,1d)" : "getTechnicalIndicators(MA,MACD,RSI)";
    }

    /**
     * 获取指定 ticker 的市场 K 线上下文。
     */
    private String getMarketKLine(String ticker) {
        if (tickerResolutionService.isLikelySecTicker(ticker)) {
            return marketTools.getIbkrHistoricalBars(ticker, "3m", "1d");
        }
        return marketTools.getStockKLine(ticker, "daily", 60);
    }

    /**
     * 获取指定 ticker 的财务/估值上下文。
     */
    private String getMarketFinancialContext(String ticker) {
        if (tickerResolutionService.isLikelySecTicker(ticker)) {
            return fundamentalsTools.getStructuredFinancials(ticker);
        }
        return marketTools.getFinancialMetrics(ticker);
    }

    /**
     * 获取指定 ticker 的技术指标上下文。
     */
    private String getMarketTechnicalContext(String ticker) {
        if (tickerResolutionService.isLikelySecTicker(ticker)) {
            return marketTools.getIbkrHistoricalBars(ticker, "6m", "1d");
        }
        return marketTools.getTechnicalIndicators(ticker, "MA,MACD,RSI");
    }

    /**
     * 将已解析股票身份写入提示词上下文。
     */
    private void appendResolvedStockIdentity(StringBuilder context, String primaryTicker) {
        context.append("## resolvedStockIdentity\n");
        if (primaryTicker == null || primaryTicker.isBlank()) {
            context.append("No supported stock target was resolved. Do not use unrelated company facts.\n\n");
            return;
        }
        String identity = appendToolObservation(new StringBuilder(), "resolveStock",
                () -> marketTools.resolveStock(primaryTicker));
        context.append(identity == null || identity.isBlank() ? "(empty result)" : identity)
                .append("\n\n");
    }

    /**
     * 在 ticker 有效时执行依赖 ticker 的工具调用。
     */
    private String withResolvedTicker(String ticker, TickerToolCall toolCall) {
        if (ticker == null || ticker.isBlank()) {
            return "{\"error\":true,\"message\":\"No supported stock target was resolved; refusing to use unrelated company data.\"}";
        }
        return toolCall.call(ticker);
    }

    /**
     * 安全执行智能体调用，失败时返回可读错误文本。
     */
    private String safeAgentCall(String agentName, AgentCall agentCall) {
        try {
            log.info("{} started.", agentName);
            String result = agentCall.call();
            log.info("{} completed, chars={}.", agentName, result == null ? 0 : result.length());
            return (result == null || result.isBlank()) ? null : result;
        } catch (Exception e) {
            log.warn("{} failed: {}", agentName, e.getMessage());
            return null;
        }
    }

    /**
     * 读取已完成 Future 的结果，失败时返回空字符串。
     */
    private String completedValue(CompletableFuture<String> future) {
        if (future == null || !future.isDone() || future.isCancelled() || future.isCompletedExceptionally()) {
            return null;
        }
        return future.getNow(null);
    }

    /**
     * 向 SSE 事件总线推送进度分片。
     */
    private void emitProgress(String traceId, Long conversationId, String type, String content) {
        chatStreamEmitter.emit(traceId, conversationId, type, content);
    }

    /**
     * 向最终提示词上下文追加命名段落。
     */
    private void appendContextSection(StringBuilder context, String name, String content) {
        context.append("## ").append(name).append("\n")
                .append(PromptText.truncate(content, 4500))
                .append("\n\n");
    }

    /**
     * 执行智能体并把观察结果追加到上下文。
     */
    private String appendAgentObservation(StringBuilder context, String agentName, AgentCall agentCall) {
        try {
            String result = agentCall.call();
            if (result == null || result.isBlank()) {
                return null;
            }
            context.append("## ").append(agentName).append("\n")
                    .append(PromptText.truncate(result, 4500))
                    .append("\n\n");
            return result;
        } catch (Exception e) {
            context.append("## ").append(agentName).append("\n")
                    .append("Agent failed: ").append(e.getMessage())
                    .append("\n\n");
            return null;
        }
    }

    /**
     * 执行工具并把观察结果追加到上下文。
     */
    private String appendToolObservation(StringBuilder context, String toolName, ToolCall toolCall) {
        try {
            String result = toolCall.call();
            if (result == null || result.isBlank()) {
                return null;
            }
            context.append("## ").append(toolName).append("\n")
                    .append(PromptText.truncate(result, 3500))
                    .append("\n\n");
            return result;
        } catch (Exception e) {
            context.append("## ").append(toolName).append("\n")
                    .append("Tool failed: ").append(e.getMessage())
                    .append("\n\n");
            return null;
        }
    }

    /**
     * 异步把搜索结果摄取为带 TTL 的临时 RAG 知识。
     *
     * <p>这是后端拥有的异步摄取流程，刻意不是模型可调用的“写知识库”工具。</p>
     */
    private void asyncIngestSearchResults(String toolName, String rawResult, String userQuery) {
        if (!searchIngestEnabled || rawResult == null || rawResult.isBlank()) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                JsonNode root = objectMapper.readTree(rawResult);
                JsonNode items = root.path("results");

                if (!items.isArray()) {
                    return;
                }
                for (JsonNode item : items) {
                    String title = item.path("title").asText("").trim();
                    String link = item.path("link").asText("").trim();
                    String snippet = item.path("snippet").asText("").trim();

                    String content = """
                            Title: %s
                            Query: %s
                            Source: %s

                            Summary:
                            %s
                            """.formatted(title, userQuery, link, snippet).trim();
                    if (content.length() < 100) {
                        continue;
                    }

                    Map<String, Object> metadata = new LinkedHashMap<>();
                    metadata.put("source", link.isBlank() ? userQuery : link);
                    metadata.put("doc_type", "chat_search_result");
                    metadata.put("title", title.isBlank() ? userQuery : title);
                    metadata.put("knowledge_category", "market_update");
                    metadata.put("query", userQuery);
                    metadata.put("tool", toolName);
                    metadata.put("origin", "chat_driven");

                    String sourceKey = "chat:" + KnowledgeIngestionService.sha256(userQuery + "|" + link + "|" + title);
                    knowledgeIngestionService.ingestText(
                            sourceKey, content, metadata, "chat_driven",
                            Duration.ofDays(searchIngestTtlDays), true);
                }
            } catch (Exception e) {
                log.warn("Async ingestion of {} search results failed: {}", toolName, e.getMessage());
            }
        }, agentTaskExecutor);
    }

    /**
     * 预取阶段返回的上下文和可选直答。
     *
     * @param context 追加到最终提示词的工具/智能体观察
     * @param directAnswer 可以直接发送给用户的答案；为空时继续走模型生成
     */
    public record PreparedToolContext(
            String context,
            String directAnswer,
            Long submittedTaskId,
            String eventTraceId
    ) {
        public PreparedToolContext(String context, String directAnswer) {
            this(context, directAnswer, null, null);
        }

        public PreparedToolContext(String context, String directAnswer, Long submittedTaskId) {
            this(context, directAnswer, submittedTaskId, null);
        }

        /** 空预取结果。 */
        static PreparedToolContext empty() {
            return new PreparedToolContext("", "", null, null);
        }

        /** 是否包含可直接返回的答案。 */
        public boolean hasDirectAnswer() {
            return directAnswer != null && !directAnswer.isBlank();
        }
    }

    @FunctionalInterface
    private interface ToolCall {
        String call();
    }

    @FunctionalInterface
    private interface TickerToolCall {
        String call(String ticker);
    }

    @FunctionalInterface
    private interface AgentCall {
        String call() throws Exception;
    }

    private String safeReportJson(InvestmentReport report) {
        try {
            return objectMapper.writeValueAsString(report);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize investment report for prepared context: {}", e.getMessage());
            return "{}";
        }
    }
}
