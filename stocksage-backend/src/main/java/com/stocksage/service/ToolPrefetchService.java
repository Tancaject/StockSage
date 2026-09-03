package com.stocksage.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.agent.Coordinator;
import com.stocksage.agent.ExecutionPlan;
import com.stocksage.agent.PlanAction;
import com.stocksage.harness.DeepResearchCompletionPolicy;
import com.stocksage.harness.HarnessModels.HarnessPhase;
import com.stocksage.harness.HarnessModels.HarnessOutcome;
import com.stocksage.harness.HarnessModels.HarnessSnapshot;
import com.stocksage.harness.HarnessModels.RecoveryAction;
import com.stocksage.harness.HarnessModels.RecoveryLifecycle;
import com.stocksage.agent.FundamentalsAgent;
import com.stocksage.agent.MarketAgent;
import com.stocksage.agent.NewsAgent;
import com.stocksage.model.dto.AnalysisState;
import com.stocksage.model.dto.InvestmentReport;
import com.stocksage.model.entity.ResearchTask;
import com.stocksage.skill.SkillExecutionService;
import com.stocksage.tool.ChatStreamEmitter;
import com.stocksage.tool.FundamentalsTools;
import com.stocksage.tool.MarketTools;
import com.stocksage.tool.NewsTools;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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

    /** 行情、K 线和股票搜索工具入口。 */
    private final MarketTools marketTools;
    /** 新闻搜索与公告查询工具入口。 */
    private final NewsTools newsTools;
    /** 财务指标和 SEC 结构化数据工具入口。 */
    private final FundamentalsTools fundamentalsTools;
    /** 基本面分析师，用预取证据生成专业观察。 */
    private final FundamentalsAgent fundamentalsAgent;
    /** 市场分析师，用预取行情生成技术面观察。 */
    private final MarketAgent marketAgent;
    /** 新闻分析师，用搜索结果生成事件面观察。 */
    private final NewsAgent newsAgent;
    /** 把自然语言与会话上下文解析为统一 ticker。 */
    private final TickerResolutionService tickerResolutionService;
    /** 构造任务受理、配额和报告复用提示。 */
    private final ReportMarkdownRenderer reportRenderer;
    /** 收集深度研究所需的多源证据。 */
    private final DeepEvidenceCollector deepEvidenceCollector;
    /** 在证据门禁通过后最多执行一次有界问题相关补证。 */
    private final DeepEvidenceReplanService deepEvidenceReplanService;
    /** 执行 Bull/Bear/Manager 深度研究流水线。 */
    private final DeepResearchPipeline deepResearchPipeline;
    /** 查询与发布可复用的投资报告版本。 */
    private final InvestmentReportVersionService investmentReportVersionService;
    /** 创建任务并维护状态、租约和 checkpoint。 */
    private final ResearchTaskService researchTaskService;
    /** 为同步降级路线读写阶段 checkpoint。 */
    private final ResearchTaskCheckpointService researchTaskCheckpointService;
    /** 将深度研究任务写入 Redis Stream。 */
    private final ResearchTaskQueue researchTaskQueue;
    /** 把搜索结果异步摄取到知识库。 */
    private final KnowledgeIngestionService knowledgeIngestionService;
    /** 执行规划中声明的可复用技能。 */
    private final SkillExecutionService skillExecutionService;
    /** 向聊天 SSE 通道发送预取进度。 */
    private final ChatStreamEmitter chatStreamEmitter;
    /** 序列化报告与解析工具结果。 */
    private final ObjectMapper objectMapper;
    /** 分析师预取与搜索入库共用的后台线程池。 */
    private final AsyncTaskExecutor agentTaskExecutor;
    /** 同步降级执行期间定时续租任务所有权。 */
    private final TaskScheduler researchHeartbeatScheduler;

    /** 单次搜索预取允许返回的最大结果数。 */
    @Value("${stocksage.chat.tool-prefetch.max-search-results:5}")
    private int toolPrefetchMaxSearchResults;

    /** 是否把聊天搜索结果异步写入知识库。 */
    @Value("${stocksage.chat.search-ingest.enabled:true}")
    private boolean searchIngestEnabled;

    /** 聊天搜索知识的有效期，单位为天。 */
    @Value("${stocksage.chat.search-ingest.ttl-days:7}")
    private long searchIngestTtlDays;

    /** 并行分析师预取的整体等待上限，单位为秒。 */
    @Value("${stocksage.agent.prefetch.timeout-seconds:120}")
    private long agentPrefetchTimeoutSeconds;

    /** 单个用户可同时持有的活跃研究任务上限。 */
    @Value("${stocksage.research-task.user-max-active:3}")
    private int userMaxActive = 3;

    /**
     * 在最终回答前执行 Coordinator 计划，并生成受证据约束的上下文。
     * 深度研究会提交后台任务；队列不可用时才在请求线程同步降级。
     *
     * @param executionPlan Coordinator 生成的动作计划
     * @param userQuery 用户原始问题
     * @param traceId 本次聊天链路标识
     * @param conversationId 会话 ID，可用于回看标的上下文
     * @param userId 当前用户 ID，用于配额与任务隔离
     * @param selectedModel 当前选定模型，供同步降级流水线使用
     * @return 待注入的上下文、可选直答以及可能创建的任务信息
     */
    public PreparedToolContext prefetch(
            ExecutionPlan executionPlan,
            String userQuery,
            String traceId,
            Long conversationId,
            String userId,
            Coordinator.SelectedModel selectedModel
    ) {
        if (executionPlan == null || executionPlan.actions() == null) {
            return PreparedToolContext.empty();
        }

        List<PlanAction> actions = executionPlan.actions();
        String primaryTicker = tickerResolutionService.resolvePrimaryTicker(userQuery, conversationId);
        if (isDeepResearchPlan(actions)) {
            // 深度计划交给持久化任务与 Redis 队列；提交失败时由其内部切换同步降级路线。
            return submitDeepResearch(
                    userQuery, traceId, conversationId, userId, selectedModel, primaryTicker);
        }

        StringBuilder context = new StringBuilder();
        String directAnswer = "";
        // 先放标的身份，便于下游提示词在股票代码/公司解析不确定时拒绝无关 RAG 或搜索片段。
        appendResolvedStockIdentity(context, primaryTicker);

        emitProgress(traceId, conversationId, "thought", "正在按服务器计划获取本轮证据。");

        // 阶段 1：后端按固定计划执行只读工具与 Capability，模型不参与工具选择。
        for (PlanAction action : actions) {
            switch (action) {
                case FUNDAMENTALS_AGENT, MARKET_AGENT, NEWS_AGENT -> { /* 在证据获取后处理 */ }
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
                    SkillExecutionService.ExecutionResult skillResult = skillExecutionService.executePrefetch(
                            executionPlan,
                            userQuery,
                            toolPrefetchMaxSearchResults,
                            traceId,
                            conversationId,
                            userId
                    );
                    if (!skillResult.context().isBlank()) {
                        context.append(skillResult.context()).append("\n\n");
                    }
                    if (!skillResult.handled(PlanAction.SEARCH_NEWS)) {
                        String result = appendToolObservation(context, "searchNews",
                                () -> newsTools.searchNews(userQuery, toolPrefetchMaxSearchResults));
                        asyncIngestSearchResults("searchNews", result, userQuery);
                    }
                }
                case WEB_SEARCH -> {
                    String result = appendToolObservation(context, "webSearch",
                            () -> newsTools.webSearch(userQuery, toolPrefetchMaxSearchResults));
                    asyncIngestSearchResults("webSearch", result, userQuery);
                }
                case GET_MARKET_OVERVIEW -> appendToolObservation(
                        context, "getMarketOverview", marketTools::getMarketOverview);
                default -> {
                    // Agent 与最终回答在证据获取后执行；DEEP 已在上方交给持久化流水线。
                }
            }
        }

        // 阶段 2：无工具分析师只消费已经取得的证据，不再拥有第二套取数决策权。
        boolean needsFundamentals = actions.contains(PlanAction.FUNDAMENTALS_AGENT);
        boolean needsMarket = actions.contains(PlanAction.MARKET_AGENT);
        boolean needsNews = actions.contains(PlanAction.NEWS_AGENT);
        if (needsFundamentals || needsMarket || needsNews) {
            emitProgress(traceId, conversationId, "thought", "证据获取完成，正在生成角色分析。");
            String agentInputContext = context.toString();
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
                log.warn("Analyst synthesis timed out or failed after {}s: {}",
                        agentPrefetchTimeoutSeconds, e.getMessage());
                emitProgress(traceId, conversationId, "observation",
                        "部分角色分析超时，系统将基于已取得的证据继续回答。");
            }

            String fundamentalsResult = completedValue(fundamentalsFuture);
            String marketResult = completedValue(marketFuture);
            String newsResult = completedValue(newsFuture);
            if (fundamentalsResult != null) {
                appendContextSection(context, "Fundamentals Agent", fundamentalsResult);
            }
            if (marketResult != null) {
                appendContextSection(context, "Market Agent", marketResult);
            }
            if (newsResult != null) {
                appendContextSection(context, "News Agent", newsResult);
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
            return new PreparedToolContext("", answer, null, traceId, "BLOCKED");
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
        try {
            if (!researchTaskService.renewLease(acquired)) {
                researchTaskService.release(acquired);
                log.info("Inline research lease expired before DB start; switching to task "
                        + "observation, taskId={}", task.getId());
                return observeInlineTask(context, task, traceId);
            }
        } catch (RuntimeException leaseError) {
            researchTaskService.release(acquired);
            log.info("Inline research lease validation failed before DB start; switching to task "
                    + "observation, taskId={}, error={}", task.getId(), leaseError.getMessage());
            return observeInlineTask(context, task, traceId);
        }
        ResearchTask runningTask;
        try {
            runningTask = researchTaskService.startAttempt(
                    task, acquired.token(), ResearchTask.Stage.DATA_PREFETCH);
        } catch (IllegalStateException ownershipRace) {
            researchTaskService.release(acquired);
            log.info("Inline research start was fenced; switching to task observation, taskId={}, error={}",
                    task.getId(), ownershipRace.getMessage());
            return observeInlineTask(context, task, traceId);
        } catch (RuntimeException error) {
            researchTaskService.release(acquired);
            throw error;
        }
        AtomicBoolean ownershipLost = new AtomicBoolean(false);
        ScheduledFuture<?> heartbeat = startInlineHeartbeat(runningTask, acquired, ownershipLost);
        try {
            requireInlineOwnership(runningTask, acquired, ownershipLost, "evidence prefetch");
            DeepEvidenceCollector.EvidenceCollection evidence = deepEvidenceCollector.collect(
                    primaryTicker, userQuery, traceId, conversationId);
            if (evidence.harnessDecision().outcome() == HarnessOutcome.RECOVER) {
                List<RecoveryAction> recoveryActions = evidence.harnessDecision().recoveryActions();
                String recoveryEffectKey = inlineRecoveryEffectKey(
                        runningTask.getId(),
                        recoveryActions
                );
                evidence.state().setHarnessSnapshot(HarnessSnapshot.recovery(
                        DeepResearchCompletionPolicy.POLICY_ID,
                        Integer.toString(DeepResearchCompletionPolicy.POLICY_VERSION),
                        HarnessPhase.EVIDENCE,
                        evidence.harnessDecision(),
                        Map.of(),
                        RecoveryLifecycle.PLANNED,
                        recoveryActions,
                        recoveryEffectKey
                ));
                requireInlineOwnership(
                        runningTask, acquired, ownershipLost, "planned recovery checkpoint");
                saveInlineHarnessSnapshot(runningTask, acquired, evidence.state());
                deepResearchPipeline.recordEvidenceRecoveryExecution(
                        traceId, recoveryEffectKey, recoveryActions);
                requireInlineOwnership(
                        runningTask, acquired, ownershipLost, "evidence recovery");
                evidence = deepEvidenceCollector.recover(
                        evidence,
                        recoveryActions,
                        traceId,
                        conversationId
                );
                Map<RecoveryAction, Integer> completedAttempts = recoveryActions.stream()
                        .collect(java.util.stream.Collectors.toUnmodifiableMap(
                                action -> action,
                                action -> 1,
                                Math::max
                        ));
                evidence.state().setHarnessSnapshot(HarnessSnapshot.recovery(
                        DeepResearchCompletionPolicy.POLICY_ID,
                        Integer.toString(DeepResearchCompletionPolicy.POLICY_VERSION),
                        HarnessPhase.EVIDENCE,
                        evidence.harnessDecision(),
                        completedAttempts,
                        RecoveryLifecycle.REVALIDATED,
                        recoveryActions,
                        recoveryEffectKey
                ));
                requireInlineOwnership(
                        runningTask, acquired, ownershipLost, "revalidated recovery checkpoint");
                saveInlineHarnessSnapshot(runningTask, acquired, evidence.state());
            }
            ResearchTask ownedTask = runningTask;
            evidence = deepEvidenceReplanService.replan(
                    runningTask.getId(),
                    userId,
                    conversationId,
                    traceId,
                    evidence,
                    () -> requireInlineOwnership(
                            ownedTask, acquired, ownershipLost, "evidence replan"),
                    state -> saveInlineEvidenceReplan(ownedTask, acquired, state)
            );
            context.append(evidence.contextMarkdown());
            AnalysisState agentState = evidence.state();
            if (evidence.harnessDecision().outcome() != HarnessOutcome.PASS) {
                String answer = reportRenderer.buildInsufficientEvidenceReport(
                        primaryTicker,
                        evidence.tickerResolved(),
                        evidence.fundamentalsOk(),
                        evidence.marketOk()
                );
                ResearchTask.ResultKind resultKind =
                        evidence.harnessDecision().outcome() == HarnessOutcome.BLOCK
                                ? ResearchTask.ResultKind.POLICY_BLOCKED
                                : ResearchTask.ResultKind.INSUFFICIENT_EVIDENCE;
                completeInlineTaskForOwner(
                        runningTask, acquired, ownershipLost, null, resultKind);
                return new PreparedToolContext(
                        context.toString().trim(), answer, null, traceId,
                        deepResearchPipeline.taskOutcome(runningTask));
            }

            investmentReportVersionService.prepareHashes(agentState);
            Optional<InvestmentReport> reusableReport = investmentReportVersionService.findReusableReport(
                    userId, conversationId, agentState);
            if (reusableReport.isPresent()) {
                InvestmentReport report = reusableReport.orElseThrow();
                agentState.setInvestmentReport(report);
                appendContextSection(context, "Research Manager", safeReportJson(report));
                completeInlineTaskForOwner(
                        runningTask,
                        acquired,
                        ownershipLost,
                        null,
                        ResearchTask.ResultKind.FULL_REPORT
                );
                return new PreparedToolContext(
                        context.toString().trim(), reportRenderer.buildFinalAnswerBrief(agentState), null, traceId,
                        deepResearchPipeline.taskOutcome(runningTask));
            }

            requireInlineOwnership(runningTask, acquired, ownershipLost, "debate start");
            emitProgress(traceId, conversationId, "thought",
                    "开始 Bull/Bear 辩论，并由 Research Manager 综合裁决。");
            if (heartbeat != null) {
                heartbeat.cancel(false);
                heartbeat = null;
            }
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
            } catch (DeepResearchPipeline.OwnershipLostException error) {
                throw error;
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
            return new PreparedToolContext(
                    context.toString().trim(), answer, null, traceId,
                    deepResearchPipeline.taskOutcome(runningTask));
        } catch (DeepResearchPipeline.OwnershipLostException error) {
            log.info("Inline research stopped after ownership loss; switching to task observation, "
                    + "taskId={}, error={}", runningTask.getId(), error.getMessage());
            return observeInlineTask(context, runningTask, traceId);
        } catch (RuntimeException error) {
            if (runningTask.getStatus() == ResearchTask.Status.FAILED) {
                emitProgress(traceId, conversationId, "error", "同步深度研究执行失败，本次任务已经结束。");
                return new PreparedToolContext(
                        context.toString().trim(), reportRenderer.buildResearchFailedAnswer(), null, traceId,
                        deepResearchPipeline.taskOutcome(runningTask));
            }
            try {
                requireInlineOwnership(
                        runningTask, acquired, ownershipLost, "failure publication");
            } catch (RuntimeException ownershipProofError) {
                log.info("Inline research failure could not be published by the current owner; "
                                + "switching to task observation, taskId={}, failure={}, fence={}",
                        runningTask.getId(), error.getMessage(), ownershipProofError.getMessage());
                return observeInlineTask(context, runningTask, traceId);
            }
            boolean failed =
                    researchTaskService.markFailedForOwner(
                            runningTask, acquired.token(), error.getMessage());
            if (failed) {
                emitProgress(traceId, conversationId, "error",
                        "同步深度研究执行失败，本次任务已经结束。");
                return new PreparedToolContext(
                        context.toString().trim(), reportRenderer.buildResearchFailedAnswer(), null, traceId,
                        deepResearchPipeline.taskOutcome(runningTask));
            }
            log.info("Inline research failure arrived after ownership changed; switching to task "
                    + "observation, taskId={}, error={}", runningTask.getId(), error.getMessage());
            return observeInlineTask(context, runningTask, traceId);
        } finally {
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
            researchTaskService.release(acquired);
        }
    }

    private void requireInlineOwnership(
            ResearchTask task,
            ResearchTaskLeaseService.Lease lease,
            AtomicBoolean ownershipLost,
            String operation
    ) {
        if (ownershipLost.get()) {
            throw inlineOwnershipLost(task, operation, null);
        }
        boolean leaseRenewed = researchTaskService.renewLease(lease);
        boolean databaseOwner = leaseRenewed
                && researchTaskService.heartbeatForOwner(task, lease.token());
        if (!databaseOwner) {
            ownershipLost.set(true);
            throw inlineOwnershipLost(task, operation, null);
        }
    }

    private void saveInlineHarnessSnapshot(
            ResearchTask task,
            ResearchTaskLeaseService.Lease lease,
            AnalysisState state
    ) {
        try {
            researchTaskCheckpointService.saveHarnessSnapshot(
                    task.getId(), lease.token(), state);
        } catch (ResearchTaskCheckpointService.OwnershipLostException error) {
            throw inlineOwnershipLost(task, "harness checkpoint", error);
        }
    }

    private void saveInlineEvidenceReplan(
            ResearchTask task,
            ResearchTaskLeaseService.Lease lease,
            AnalysisState state
    ) {
        try {
            researchTaskCheckpointService.saveEvidenceReplan(
                    task.getId(), lease.token(), state);
        } catch (ResearchTaskCheckpointService.OwnershipLostException error) {
            throw new DeepResearchPipeline.OwnershipLostException(
                    "inline research lost ownership before evidence replan checkpoint, taskId="
                            + task.getId(),
                    error
            );
        }
    }

    private void completeInlineTaskForOwner(
            ResearchTask task,
            ResearchTaskLeaseService.Lease lease,
            AtomicBoolean ownershipLost,
            Long reportVersionId,
            ResearchTask.ResultKind resultKind
    ) {
        requireInlineOwnership(task, lease, ownershipLost, "terminal completion");
        try {
            researchTaskService.markSucceededForOwner(
                    task, lease.token(), reportVersionId, resultKind);
        } catch (IllegalStateException error) {
            throw inlineOwnershipLost(task, "terminal completion", error);
        }
    }

    private DeepResearchPipeline.OwnershipLostException inlineOwnershipLost(
            ResearchTask task,
            String operation,
            Throwable cause
    ) {
        String message = "inline research task ownership was lost before "
                + operation + ", taskId=" + (task == null ? null : task.getId());
        return cause == null
                ? new DeepResearchPipeline.OwnershipLostException(message)
                : new DeepResearchPipeline.OwnershipLostException(message, cause);
    }

    private PreparedToolContext observeInlineTask(
            StringBuilder context,
            ResearchTask task,
            String traceId
    ) {
        return new PreparedToolContext(
                context.toString().trim(),
                reportRenderer.buildTaskAcceptedAnswer(task),
                task.getId(),
                taskTraceId(task, traceId)
        );
    }

    /** inline 降级仍可能包含耗时的数据预取，因此在进入辩论流水线前也必须持续续租。 */
    private ScheduledFuture<?> startInlineHeartbeat(
            ResearchTask task,
            ResearchTaskLeaseService.Lease lease,
            AtomicBoolean ownershipLost
    ) {
        Duration interval = researchTaskService.leaseHeartbeatInterval();
        if (interval == null || interval.isZero() || interval.isNegative()) {
            interval = Duration.ofSeconds(60);
        }
        return researchHeartbeatScheduler.scheduleAtFixedRate(() -> {
            if (ownershipLost.get()) {
                return;
            }
            try {
                boolean leaseRenewed = researchTaskService.renewLease(lease);
                boolean taskRenewed = leaseRenewed
                        && researchTaskService.heartbeatForOwner(task, lease.token());
                if (!taskRenewed) {
                    ownershipLost.set(true);
                    log.warn("Inline research task heartbeat lost ownership, taskId={}", task.getId());
                }
            } catch (Exception error) {
                ownershipLost.set(true);
                log.warn("Inline research task heartbeat failed, taskId={}, error={}",
                        task.getId(), error.getMessage());
            }
        }, Instant.now().plus(interval), interval);
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

    private String inlineRecoveryEffectKey(Long taskId, List<RecoveryAction> actions) {
        String actionKey = actions == null
                ? "none"
                : actions.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted()
                .map(action -> action.name().toLowerCase(Locale.ROOT) + "-1")
                .collect(java.util.stream.Collectors.joining("+"));
        if (actionKey.isBlank()) {
            actionKey = "none";
        }
        return "deep-evidence:"
                + taskId
                + ":"
                + DeepResearchCompletionPolicy.POLICY_ID
                + "-v"
                + DeepResearchCompletionPolicy.POLICY_VERSION
                + ":"
                + actionKey;
    }

    /**
     * 判断规划动作是否需要进入深度研究流水线。
     *
     * @param actions Coordinator 生成的动作列表
     * @return 包含任一 Bull、Bear 或 Research Manager 动作时为 true
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
     * @param submittedTaskId 已提交的研究任务 ID；普通预取时为空
     * @param eventTraceId SSE 事件链路标识；普通预取时为空
     * @param taskOutcome 同步完成的业务结果；后台任务与普通预取时为空
     */
    public record PreparedToolContext(
            String context,
            String directAnswer,
            Long submittedTaskId,
            String eventTraceId,
            String taskOutcome
    ) {
        /**
         * 构造普通预取结果，任务 ID 与事件 trace 均为空。
         *
         * @param context 待注入的工具上下文
         * @param directAnswer 可选直答
         */
        public PreparedToolContext(String context, String directAnswer) {
            this(context, directAnswer, null, null, null);
        }

        /**
         * 构造已提交任务的结果，事件 trace 默认留空。
         *
         * @param context 待注入的工具上下文
         * @param directAnswer 可选直答
         * @param submittedTaskId 已提交的研究任务 ID
         */
        public PreparedToolContext(String context, String directAnswer, Long submittedTaskId) {
            this(context, directAnswer, submittedTaskId, null, null);
        }

        public PreparedToolContext(
                String context,
                String directAnswer,
                Long submittedTaskId,
                String eventTraceId
        ) {
            this(context, directAnswer, submittedTaskId, eventTraceId, null);
        }

        /** 空预取结果。 */
        static PreparedToolContext empty() {
            return new PreparedToolContext("", "", null, null, null);
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
