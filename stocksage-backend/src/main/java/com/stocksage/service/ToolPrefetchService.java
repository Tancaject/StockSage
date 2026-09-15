package com.stocksage.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.agent.Coordinator;
import com.stocksage.agent.ExecutionPlan;
import com.stocksage.agent.PlanAction;
import com.stocksage.agent.PlanRoute;
import com.stocksage.agent.ReadRequest;
import com.stocksage.agent.AgentStep;
import com.stocksage.harness.OrdinaryCompletionPolicy;
import com.stocksage.harness.ResearchHarness;
import com.stocksage.harness.HarnessModels.EvidenceDimension;
import com.stocksage.harness.HarnessModels.RunContext;
import com.stocksage.trace.TraceService;
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
    private final EvidenceEnvelopeMapper evidenceEnvelopeMapper;
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
    private final ResearchHarness researchHarness;
    private final OrdinaryCompletionPolicy ordinaryCompletionPolicy;
    private final TraceService traceService;

    /** 单次搜索预取允许返回的最大结果数。 */
    @Value("${stocksage.chat.tool-prefetch.max-search-results:5}")
    private int toolPrefetchMaxSearchResults;

    /** 是否把聊天搜索结果异步写入知识库。 */
    @Value("${stocksage.chat.search-ingest.enabled:true}")
    private boolean searchIngestEnabled;

    /** 聊天搜索知识的有效期，单位为天。 */
    @Value("${stocksage.chat.search-ingest.ttl-days:7}")
    private long searchIngestTtlDays;

    /** 普通路由分析师的等待上限，单位为秒。 */
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

        if (executionPlan.route() == PlanRoute.DIRECT) return PreparedToolContext.empty();
        List<PlanAction> actions = executionPlan.actions();
        String primaryTicker = tickerResolutionService.resolvePrimaryTicker(userQuery, conversationId);
        if (isDeepResearchPlan(actions)) {
            // 深度计划交给持久化任务与 Redis 队列；提交失败时由其内部切换同步降级路线。
            return submitDeepResearch(
                    userQuery, traceId, conversationId, userId, selectedModel, primaryTicker);
        }

        ReadRequest read = executionPlan.readRequest();
        String clarification = read.clarification();
        boolean us = tickerResolutionService.isLikelySecTicker(primaryTicker);
        if (executionPlan.route() == PlanRoute.MARKET && read.intraday() && !us) {
            clarification = "当前 A 股/港股取数只支持日、周、月线，无法提供本次小时或分钟线。请改用日线或选择支持的美股标的。";
        }
        if (!clarification.isBlank()) return new PreparedToolContext("", clarification, null, traceId, "BLOCKED");
        OrdinaryEvidence evidence = new OrdinaryEvidence(primaryTicker, read, objectMapper, evidenceEnvelopeMapper);
        emitProgress(traceId, conversationId, "thought", "正在按已校验的周期与粒度获取证据。");
        for (PlanAction action : actions) {
            switch (action) {
                case GET_FINANCIAL_REPORTS, GET_STRUCTURED_FINANCIALS -> evidence.call("getFinancialReports", EvidenceDimension.FUNDAMENTALS,
                        () -> withResolvedTicker(primaryTicker, ticker -> fundamentalsTools.getFinancialReports(ticker, read.reportPeriod(), read.reportYears())));
                case SEARCH_COMPANY_REPORTS -> {
                    String result = evidence.call("searchCompanyReports", EvidenceDimension.FUNDAMENTALS,
                            () -> withResolvedTicker(primaryTicker, ticker -> fundamentalsTools.searchCompanyReports(ticker,
                                    read.reportPeriod().equals("quarterly") ? "10-Q 季报" : "10-K 年报", toolPrefetchMaxSearchResults)));
                    asyncIngestSearchResults("searchCompanyReports", result, userQuery);
                }
                case GET_STOCK_KLINE -> evidence.call(us ? "getIbkrHistoricalBars" : "getStockKLine", EvidenceDimension.MARKET,
                        () -> withResolvedTicker(primaryTicker, ticker -> us
                                ? marketTools.getIbkrHistoricalBars(ticker, read.period(), read.bar())
                                : marketTools.getStockKLine(ticker, read.klinePeriod(), read.days())));
                case GET_FINANCIAL_METRICS -> evidence.call(us ? "getStructuredFinancials" : "getFinancialMetrics", EvidenceDimension.MARKET,
                        () -> withResolvedTicker(primaryTicker, this::getMarketFinancialContext));
                case GET_TECHNICAL_INDICATORS -> {
                    // 美股分析复用本轮 K 线，不再额外取固定 6 个月日线。
                    if (!us) evidence.call("getTechnicalIndicators", EvidenceDimension.MARKET,
                            () -> withResolvedTicker(primaryTicker, ticker -> marketTools.getTechnicalIndicators(ticker, "MA,MACD,RSI")));
                    else if (userQuery.toLowerCase(java.util.Locale.ROOT).matches("(?s).*(指标|macd|rsi|ma[0-9]|均线|kdj).*")) {
                        evidence.add("getTechnicalIndicators", EvidenceDimension.MARKET,
                                "{\"error\":true,\"message\":\"美股指标尚无确定性计算工具，已有 K 线不能替代指标结果\"}");
                    }
                }
                case SEARCH_NEWS -> {
                    SkillExecutionService.ExecutionResult skill = skillExecutionService.executePrefetch(
                            executionPlan, userQuery, toolPrefetchMaxSearchResults, traceId, conversationId, userId);
                    if (skill.skillId().isBlank()) {
                        String result = evidence.call("searchNews", EvidenceDimension.NEWS,
                                () -> newsTools.searchNews(userQuery, toolPrefetchMaxSearchResults));
                        asyncIngestSearchResults("searchNews", result, userQuery);
                    } else if (skill.evidence().isEmpty()) {
                        evidence.add("searchNews", EvidenceDimension.NEWS, "{\"error\":true,\"message\":\"未取得可用新闻证据\"}");
                    } else {
                        for (var result : skill.evidence()) evidence.add(result);
                    }
                }
                case WEB_SEARCH -> {
                    String result = evidence.call("webSearch", EvidenceDimension.NEWS,
                            () -> newsTools.webSearch(userQuery, toolPrefetchMaxSearchResults));
                    asyncIngestSearchResults("webSearch", result, userQuery);
                }
                default -> { /* 标的已解析；角色分析在取证完成后执行。 */ }
            }
        }
        var decision = researchHarness.observeEvidence(traceId, ordinaryCompletionPolicy,
                new RunContext(executionPlan.route().name(), Map.of()), evidence.ledger());
        String outcome = decision.outcome() == HarnessOutcome.BLOCK ? "BLOCKED"
                : decision.outcome() == HarnessOutcome.PASS && evidence.allIncluded() ? "COMPLETED"
                : evidence.hasUsefulResult() ? "DEGRADED" : "FAILED";
        StringBuilder context = new StringBuilder("本轮标的：" + primaryTicker + "\n请求参数：" + read.attributes()
                + "\n证据验收：" + outcome + "。仅引用下列可用证据，使用 [E1] 等编号并保留来源；数据缺口必须明确说明。\n"
                + evidence.context());
        PlanAction analyst = actions.stream().filter(PlanAction::isAgentRole).findFirst().orElse(null);
        if (analyst != null && evidence.hasUsefulResult() && !outcome.equals("BLOCKED")) {
            String agentInput = context.toString();
            CompletableFuture<String> future = CompletableFuture.supplyAsync(
                    () -> safeAgentCall(analyst.label(), () -> switch (analyst) {
                        case FUNDAMENTALS_AGENT -> fundamentalsAgent.analyze(userQuery, agentInput);
                        case MARKET_AGENT -> marketAgent.analyze(userQuery, agentInput);
                        case NEWS_AGENT -> newsAgent.analyze(userQuery, agentInput);
                        default -> throw new IllegalStateException("Unsupported analyst: " + analyst);
                    }), agentTaskExecutor);
            try {
                String result = future.get(agentPrefetchTimeoutSeconds, TimeUnit.SECONDS);
                if (result == null || result.isBlank()) outcome = "DEGRADED";
                else {
                    appendContextSection(context, analyst.label(), result);
                    if (result.length() > 4500) outcome = "DEGRADED";
                }
            } catch (Exception error) {
                future.cancel(true);
                outcome = "DEGRADED";
                context.append("\n领域分析未完成，仅使用已取得的原始证据。\n");
            }
        }
        context.append("\n最终执行验收：").append(outcome).append("。不得将此状态升级。\n");
        traceService.addStep(traceId, AgentStep.builder().action("Ordinary Evidence")
                .observation("普通路线证据验收：" + outcome)
                .attributes(Map.of("kind", "ordinary-evidence", "route", executionPlan.route().name(),
                        "request", read.attributes(), "observations", evidence.observations(), "taskOutcome", outcome,
                        "contextChars", context.length(), "analystPlanned", analyst != null)).build());
        emitProgress(traceId, conversationId, "observation", "证据验收：" + outcome + "；回答将保留来源和数据缺口。");
        String direct = switch (outcome) {
            case "BLOCKED" -> "本次取证的标的或权限不符合请求，已停止生成分析。请确认股票代码后重试。";
            case "FAILED" -> "本轮未取得符合请求的可用证据，无法完成查询。请检查数据服务或稍后重试；不会使用其他周期或公司的数据替代。";
            default -> "";
        };
        return new PreparedToolContext(context.toString(), direct, null, traceId, outcome, evidence.citationIds());
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
            return runInlineDeepFallback(
                    primaryTicker, userQuery, traceId, conversationId, userId, selectedModel, task);
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
     * 获取指定 ticker 的财务/估值上下文。
     */
    private String getMarketFinancialContext(String ticker) {
        if (tickerResolutionService.isLikelySecTicker(ticker)) {
            return fundamentalsTools.getStructuredFinancials(ticker);
        }
        return marketTools.getFinancialMetrics(ticker);
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
        String identity = null;
        try {
            identity = marketTools.resolveStock(primaryTicker);
        } catch (Exception ignored) {
            // 身份读取失败只产生空观察，不替代后续证据的标的验收。
        }
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
            String taskOutcome,
            List<String> citationIds
    ) {
        public PreparedToolContext(String context, String directAnswer, Long submittedTaskId,
                                   String eventTraceId, String taskOutcome) {
            this(context, directAnswer, submittedTaskId, eventTraceId, taskOutcome, List.of());
        }

        /** 生成成功仍需引用本轮可用证据；未知或缺失编号不能记作完整完成。 */
        public String outcomeForAnswer(String answer) {
            if (!"COMPLETED".equals(taskOutcome) || citationIds.isEmpty()) return taskOutcome;
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\[E([0-9]+)\\]").matcher(answer == null ? "" : answer);
            boolean cited = false;
            while (matcher.find()) {
                if (!citationIds.contains("E" + matcher.group(1))) return "DEGRADED";
                cited = true;
            }
            return cited ? taskOutcome : "DEGRADED";
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
