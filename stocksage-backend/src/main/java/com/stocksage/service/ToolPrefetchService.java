package com.stocksage.service;

import com.stocksage.exception.ResearchCapacityExceededException;
import com.stocksage.evolution.AgentPolicyBundle;
import com.stocksage.evolution.FundamentalsRuntimeIdentity;

import com.stocksage.knowledge.SearchResultIngestionService;
import com.stocksage.research.ResearchSubmissionService;


import com.stocksage.evidence.adapter.EvidenceEnvelopeMapper;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.agent.Coordinator;
import com.stocksage.agent.ModelCompletion;
import com.stocksage.agent.ExecutionPlan;
import com.stocksage.agent.PlanAction;
import com.stocksage.agent.PlanRoute;
import com.stocksage.agent.ReadRequest;
import com.stocksage.agent.DataRequirement;
import com.stocksage.agent.OrdinaryPlan;
import com.stocksage.agent.PlanStep;
import com.stocksage.tool.ToolCallContext;
import com.stocksage.agent.AgentStep;
import com.stocksage.harness.OrdinaryCompletionPolicy;
import com.stocksage.harness.ResearchHarness;
import com.stocksage.evidence.EvidenceModels.EvidenceDimension;
import com.stocksage.harness.HarnessModels.RunContext;
import com.stocksage.trace.TraceService;
import com.stocksage.harness.HarnessModels.HarnessOutcome;
import com.stocksage.agent.FundamentalsAgent;
import com.stocksage.agent.MarketAgent;
import com.stocksage.agent.NewsAgent;
import com.stocksage.skill.SkillExecutionService;
import com.stocksage.tool.ChatStreamEmitter;
import com.stocksage.tool.FundamentalsTools;
import com.stocksage.tool.MarketTools;
import com.stocksage.capability.CapabilityGateway;
import com.stocksage.capability.CapabilityInvocationContext;
import com.stocksage.capability.CapabilityException;
import com.stocksage.capability.CapabilityResult;
import com.stocksage.capability.LocalNewsSearchCapabilityAdapter;
import com.stocksage.capability.LocalWebSearchCapabilityAdapter;
import java.time.Instant;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Coordinator 计划的确定性预取编排。
 *
 * <p>在最终回答模型被调用之前，按公开计划执行工具与分析师智能体，把观察结果
 * 拼装为受证据约束的系统上下文；深度研究路线负责提交任务，队列不可用时
 * 由 ResearchSubmissionService 选择受理或同步执行，不在预取服务内管理任务与研究生命周期。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolPrefetchService {

    /** 行情、K 线和股票搜索工具入口。 */
    private final MarketTools marketTools;
    private final CapabilityGateway capabilityGateway;
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
    private final EvidenceEnvelopeMapper evidenceEnvelopeMapper;
    private final ResearchSubmissionService researchSubmissionService;
    /** 把搜索结果异步摄取到知识库。 */
    private final SearchResultIngestionService searchResultIngestionService;
    /** 执行规划中声明的可复用技能。 */
    private final SkillExecutionService skillExecutionService;
    /** 向聊天 SSE 通道发送预取进度。 */
    private final ChatStreamEmitter chatStreamEmitter;
    /** 序列化报告与解析工具结果。 */
    private final ObjectMapper objectMapper;
    /** 在线分析师使用独立于后台入库的有界线程池。 */
    private final AsyncTaskExecutor agentTaskExecutor;
    /** 多标的取证扇出使用的独立有界线程池，不与 Gateway 内部任务争用 Agent 池。 */
    private final AsyncTaskExecutor ordinaryFanoutExecutor;
    private final ResearchHarness researchHarness;
    private final OrdinaryCompletionPolicy ordinaryCompletionPolicy;
    private final TraceService traceService;

    /** 单次搜索预取允许返回的最大结果数。 */
    @Value("${stocksage.chat.tool-prefetch.max-search-results:5}")
    private int toolPrefetchMaxSearchResults;

    @Value("${stocksage.chat.tool-prefetch.per-tool-timeout-seconds:8}")
    private long toolPrefetchPerToolTimeoutSeconds = 8;

    /** 多标的扇出整轮取证的等待上限，单位为秒；到期未完成的步骤被取消并记为超时。 */
    @Value("${stocksage.agent.ordinary.multi-target.timeout-seconds:45}")
    private long fanoutTimeoutSeconds = 45;

    /** 普通路线证据可写入回答上下文的总字符预算；多标的按标的平分。 */
    private static final int ORDINARY_CONTEXT_BUDGET = 11000;

    /** 普通路由分析师的等待上限，单位为秒。 */
    @Value("${stocksage.agent.prefetch.timeout-seconds:120}")
    private long agentPrefetchTimeoutSeconds;

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
     * @param retrievedTickers 本轮知识库实际召回了正文的标的，用于判断"财报原文"需求是否满足
     * @param evaluationMode 评测专用的请求级模式（已由控制器按管理员令牌校验），普通请求为 null
     * @return 待注入的上下文、可选直答以及可能创建的任务信息
     */
    public PreparedToolContext prefetch(
            ExecutionPlan executionPlan, String userQuery, String traceId, Long conversationId,
            String userId, Coordinator.SelectedModel selectedModel, String submissionId,
            FundamentalsRuntimeIdentity.Request methodRequest, Set<String> retrievedTickers, String evaluationMode
    ) {
        if (executionPlan == null || executionPlan.actions() == null) {
            return PreparedToolContext.empty();
        }

        if (executionPlan.route() == PlanRoute.DIRECT) return PreparedToolContext.empty();
        List<PlanAction> actions = executionPlan.actions();
        if (isDeepResearchPlan(actions)) {
            String primaryTicker = tickerResolutionService.resolvePrimaryTicker(userQuery, conversationId);
            // 深度计划交给持久化任务与 Redis 队列；提交失败时由其内部切换同步降级路线。
            var result = researchSubmissionService.submit(
                    userQuery, traceId, conversationId, userId, selectedModel, primaryTicker,
                    executionPlan.timeSensitivity(), submissionId, evaluationMode);
            return new PreparedToolContext(result.context(), result.directAnswer(), result.submittedTaskId(),
                    result.eventTraceId(), result.taskOutcome());
        }

        ReadRequest read = executionPlan.readRequest();
        if (!read.clarification().isBlank()) return new PreparedToolContext("", read.clarification(), null, traceId, "BLOCKED");
        OrdinaryPlan ordinaryPlan = executionPlan.ordinaryPlan();
        if (ordinaryPlan.multiTarget()) {
            return prefetchMultiTarget(executionPlan, userQuery, traceId, conversationId, userId, retrievedTickers);
        }
        String primaryTicker = tickerResolutionService.resolvePrimaryTicker(userQuery, conversationId);
        boolean us = tickerResolutionService.isLikelySecTicker(primaryTicker);
        if (executionPlan.route() == PlanRoute.MARKET && !us
                && ordinaryPlan.steps().stream().anyMatch(step -> step.request().intraday())) {
            return new PreparedToolContext("", "当前 A 股/港股取数只支持日、周、月线，无法提供本次小时或分钟线。请改用日线或选择支持的美股标的。",
                    null, traceId, "BLOCKED");
        }
        OrdinaryEvidence evidence = new OrdinaryEvidence(
                primaryTicker, read, objectMapper, evidenceEnvelopeMapper, executionPlan.timeSensitivity());
        emitProgress(traceId, conversationId, "thought", "正在按已校验的周期与粒度获取证据。");
        for (PlanStep step : ordinaryPlan.steps()) {
            for (StepEvidence item : fetch(step, primaryTicker, us, userQuery, userQuery, traceId, conversationId, userId, executionPlan)) {
                item.addTo(evidence);
            }
        }
        List<String> fallbackExecuted = new ArrayList<>();
        List<PlanStep> fallbackSteps = fallbackStepsFor(ordinaryPlan, evidence,
                retrievedTickers.contains(primaryTicker.toUpperCase(Locale.ROOT)));
        if (!fallbackSteps.isEmpty()) {
            emitProgress(traceId, conversationId, "thought", "主数据源未取得所需证据，补跑同类备用取数步骤。");
            for (PlanStep step : fallbackSteps) {
                fallbackExecuted.add(step.action().label());
                for (StepEvidence item : fetch(step, primaryTicker, us, userQuery, userQuery, traceId, conversationId, userId, executionPlan)) {
                    item.addTo(evidence);
                }
            }
        }
        var decision = researchHarness.observeEvidence(traceId, ordinaryCompletionPolicy,
                new RunContext(executionPlan.route().name(), Map.of(), executionPlan.timeSensitivity()), evidence.ledger());
        String outcome = decision.outcome() == HarnessOutcome.BLOCK ? "BLOCKED"
                : decision.outcome() == HarnessOutcome.PASS && evidence.allIncluded() ? "COMPLETED"
                : evidence.hasUsefulResult() ? "DEGRADED" : "FAILED";
        List<DataRequirement> unmet = unmetRequirements(ordinaryPlan, evidence,
                retrievedTickers.contains(primaryTicker.toUpperCase(Locale.ROOT)));
        if ("COMPLETED".equals(outcome) && !unmet.isEmpty()) outcome = "DEGRADED";
        StringBuilder context = new StringBuilder(ordinaryEvidenceContext(primaryTicker, planRequestAttributes(ordinaryPlan, read),
                executionPlan.timeSensitivity().name(), outcome, evidence.context()) + requirementNote(unmet));
        String sourceEvidenceContext = evidence.context();
        PlanAction analyst = actions.stream().filter(PlanAction::isAgentRole).findFirst().orElse(null);
        var methodSelection = analyst == PlanAction.FUNDAMENTALS_AGENT
                ? fundamentalsAgent.selectMethod(userId, executionPlan.route() == PlanRoute.FUNDAMENTALS
                    ? evidence.evolutionTaskTags(userQuery) : Set.of(), evidence.evolutionEvidenceTags(),
                    methodRequest, agentPrefetchTimeoutSeconds) : null;
        var pinnedMethod = methodSelection == null ? null : methodSelection.bundle();
        var methodObservation = new java.util.concurrent.atomic.AtomicReference<FundamentalsAgent.Analysis>();
        String analystStatus = "NOT_EXECUTED";
        ModelCompletion analystCompletion = null;
        String analystCompletedAt = "";
        long analystStarted = System.nanoTime();
        AnalystSection analystSection = null;
        String analystContext = null;
        if (analyst != null && evidence.hasUsefulResult() && !outcome.equals("BLOCKED")) {
            String agentInput = context.toString();
            analystContext = agentInput;
            FutureTask<ModelCompletion.Output> future = null;
            try {
                // FutureTask propagates cancellation to the running worker; CompletableFuture does not.
                future = new FutureTask<>(
                    () -> safeAgentCall(analyst.label(), () -> switch (analyst) {
                        case FUNDAMENTALS_AGENT -> {
                            var observation = fundamentalsAgent.analyzeObserved(userQuery, agentInput, pinnedMethod);
                            methodObservation.set(observation);
                            yield new ModelCompletion.Output(observation.content(), observation.completion());
                        }
                        case MARKET_AGENT -> marketAgent.analyzeObserved(userQuery, agentInput);
                        case NEWS_AGENT -> newsAgent.analyzeObserved(userQuery, agentInput);
                        default -> throw new IllegalStateException("Unsupported analyst: " + analyst);
                    }));
                agentTaskExecutor.execute(future);
                emitProgress(traceId, conversationId, "thought", "证据已取得，正在等待领域分析完成。");
                ModelCompletion.Output result = future.get(agentPrefetchTimeoutSeconds, TimeUnit.SECONDS);
                analystCompletion = result.completion();
                AnalystDraft draft = appendAnalystDraft(context.toString(), analyst.label(), result.content(), outcome,
                        result.completion());
                context = new StringBuilder(draft.context());
                outcome = draft.taskOutcome();
                analystStatus = draft.status();
                analystSection = draft.section();
            } catch (RejectedExecutionException rejected) {
                outcome = "DEGRADED";
                analystStatus = "CAPACITY_REJECTED";
                context.append("\n在线分析容量不足，领域分析未执行；仅使用已取得的原始证据。\n");
                log.warn("Optional analyst not submitted, traceId={}, role={}, reason=CAPACITY_REJECTED", traceId, analyst);
            } catch (InterruptedException interrupted) {
                if (future != null) future.cancel(true);
                Thread.currentThread().interrupt();
                var cancelled = new CancellationException("领域分析等待已取消。");
                cancelled.initCause(interrupted);
                throw cancelled;
            } catch (Exception error) {
                if (future != null) future.cancel(true);
                outcome = "DEGRADED";
                if (ResearchCapacityExceededException.find(error) != null) {
                    analystStatus = "CAPACITY_REJECTED";
                    context.append("\n在线分析容量不足，领域分析未执行；仅使用已取得的原始证据。\n");
                    log.warn("Optional analyst provider call not admitted, traceId={}, role={}, reason=CAPACITY_REJECTED",
                            traceId, analyst);
                } else {
                    analystStatus = "FAILED";
                    context.append("\n领域分析未完成，仅使用已取得的原始证据。\n");
                }
            }
            analystCompletedAt = Instant.now().toString();
        }
        context = new StringBuilder(finishOrdinaryContext(context.toString(), outcome));
        Map<String, Object> evidenceAttributes = new LinkedHashMap<>(Map.of(
                "kind", "ordinary-evidence", "route", executionPlan.route().name(),
                "timeSensitivity", executionPlan.timeSensitivity().name(),
                "request", read.attributes(), "observations", evidence.observations(), "taskOutcome", outcome,
                "contextChars", context.length(), "analystPlanned", analyst != null, "analystStatus", analystStatus));
        evidenceAttributes.put("planSource", ordinaryPlan.source().name());
        evidenceAttributes.put("requirements", ordinaryPlan.requirements().stream().map(Enum::name).sorted().toList());
        evidenceAttributes.put("unmetRequirements", unmet.stream().map(Enum::name).toList());
        evidenceAttributes.put("fallbackExecuted", fallbackExecuted);
        if (analystCompletion != null) {
            evidenceAttributes.put("analystCompletionStatus", analystCompletion.status().name());
            evidenceAttributes.put("analystFinishReason", analystCompletion.finishReason());
        }
        if (analyst == PlanAction.FUNDAMENTALS_AGENT) {
            evidenceAttributes.put("methodBundle", pinnedMethod.identity());
            evidenceAttributes.put("methodSelection", methodSelection.attributes());
            evidenceAttributes.put("analystCompletedAt", analystCompletedAt);
            evidenceAttributes.put("analystDurationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - analystStarted));
            var observed = methodObservation.get();
            evidenceAttributes.put("analystUsage", observed == null ? Map.of("usageSource", "NO_DATA") : observed.responseMetadata());
            if (observed != null) {
                // 完整分析师输入只写入本地 MySQL Trace，失败池据此构建可回放的回归题。
                evidenceAttributes.put("analystQuery", userQuery);
                evidenceAttributes.put("analystContext", analystContext);
                evidenceAttributes.put("analystInvocation", Map.of("kind", "model-invocation", "scope", "fundamentals-analysis",
                        "actualSystemPromptSha256", AgentPolicyBundle.sha256(observed.systemPrompt()),
                        "actualUserPromptSha256", AgentPolicyBundle.sha256(observed.userPrompt()), "methodBundle", observed.methodBundle()));
            }
        }
        traceService.addStep(traceId, AgentStep.builder().action("Ordinary Evidence")
                .observation("普通路线证据验收：" + outcome).attributes(evidenceAttributes).build());
        emitProgress(traceId, conversationId, "observation", "证据验收：" + outcome + "；回答将保留来源和数据缺口。");
        String direct = switch (outcome) {
            case "BLOCKED" -> "本次取证的标的或权限不符合请求，已停止生成分析。请确认股票代码后重试。";
            case "FAILED" -> "本轮未取得符合请求的可用证据，无法完成查询。请检查数据服务或稍后重试；不会使用其他周期或公司的数据替代。";
            default -> "";
        };
        return new PreparedToolContext(context.toString(), direct, null, traceId, outcome,
                evidence.citationIds(), sourceEvidenceContext, analystSection);
    }

    /**
     * 一步取数的原始结果。执行线程只产出它，入账统一在请求线程按计划顺序进行，
     * 证据对象（列表、编号、字符预算）因此不会被多个线程同时修改。
     */
    private record StepEvidence(String name, EvidenceDimension dimension, String raw,
                                CapabilityResult capability, ReadRequest request) {
        void addTo(OrdinaryEvidence evidence) {
            if (capability != null) evidence.add(capability);
            else evidence.add(name, dimension, raw, request);
        }
    }

    /** 执行计划中的一步，不修改任何共享证据状态。 */
    private List<StepEvidence> fetch(PlanStep step, String ticker, boolean us, String searchQuery, String userQuery,
                                     String traceId, Long conversationId, String userId, ExecutionPlan executionPlan) {
        ReadRequest read = step.request();
        return switch (step.action()) {
            case GET_FINANCIAL_REPORTS, GET_STRUCTURED_FINANCIALS -> List.of(raw("getFinancialReports", EvidenceDimension.FUNDAMENTALS, read,
                    () -> withResolvedTicker(ticker, symbol -> fundamentalsTools.getFinancialReports(symbol, read.reportPeriod(), read.reportYears()))));
            case SEARCH_COMPANY_REPORTS -> {
                StepEvidence result = raw("searchCompanyReports", EvidenceDimension.FUNDAMENTALS, read,
                        () -> withResolvedTicker(ticker, symbol -> fundamentalsTools.searchCompanyReports(symbol,
                                read.reportPeriod().equals("quarterly") ? "10-Q 季报" : "10-K 年报", toolPrefetchMaxSearchResults)));
                searchResultIngestionService.submit("searchCompanyReports", result.raw(), userQuery);
                yield List.of(result);
            }
            case GET_STOCK_KLINE -> List.of(raw(us ? "getIbkrHistoricalBars" : "getStockKLine", EvidenceDimension.MARKET, read,
                    () -> withResolvedTicker(ticker, symbol -> us
                            ? marketTools.getIbkrHistoricalBars(symbol, read.period(), read.bar())
                            : marketTools.getStockKLine(symbol, read.klinePeriod(), read.days()))));
            case GET_FINANCIAL_METRICS -> List.of(raw(us ? "getStructuredFinancials" : "getFinancialMetrics", EvidenceDimension.MARKET, read,
                    () -> withResolvedTicker(ticker, this::getMarketFinancialContext)));
            case GET_TECHNICAL_INDICATORS -> {
                // 美股分析复用本轮 K 线，不再额外取固定 6 个月日线。
                if (!us) yield List.of(raw("getTechnicalIndicators", EvidenceDimension.MARKET, read,
                        () -> withResolvedTicker(ticker, symbol -> marketTools.getTechnicalIndicators(symbol, "MA,MACD,RSI"))));
                if (userQuery.toLowerCase(Locale.ROOT).matches("(?s).*(指标|macd|rsi|ma[0-9]|均线|kdj).*")) {
                    yield List.of(new StepEvidence("getTechnicalIndicators", EvidenceDimension.MARKET,
                            "{\"error\":true,\"message\":\"美股指标尚无确定性计算工具，已有 K 线不能替代指标结果\"}", null, read));
                }
                yield List.of();
            }
            case SEARCH_NEWS -> {
                SkillExecutionService.ExecutionResult skill = skillExecutionService.executePrefetch(
                        executionPlan, searchQuery, toolPrefetchMaxSearchResults, traceId, conversationId, userId);
                if (skill.skillId().isBlank()) {
                    yield List.of(search(LocalNewsSearchCapabilityAdapter.ID, "searchNews", searchQuery, read,
                            traceId, conversationId, userId));
                }
                if (skill.evidence().isEmpty()) {
                    yield List.of(new StepEvidence("searchNews", EvidenceDimension.NEWS,
                            "{\"error\":true,\"message\":\"未取得可用新闻证据\"}", null, read));
                }
                yield skill.evidence().stream().map(result -> new StepEvidence("", EvidenceDimension.NEWS, "", result, read)).toList();
            }
            case WEB_SEARCH -> List.of(search(LocalWebSearchCapabilityAdapter.ID, "webSearch", searchQuery, read,
                    traceId, conversationId, userId));
            default -> List.of(); // 标的已解析；角色分析在取证完成后执行。
        };
    }

    private StepEvidence raw(String name, EvidenceDimension dimension, ReadRequest request, java.util.function.Supplier<String> call) {
        String raw;
        try { raw = call.get(); }
        catch (Exception error) { raw = "{\"error\":true,\"message\":\"取证调用失败，请稍后重试\"}"; }
        return new StepEvidence(name, dimension, raw, null, request);
    }

    /** 普通搜索也经过注册表；策略拒绝不允许退回直接工具调用。 */
    private StepEvidence search(String capabilityId, String toolName, String query, ReadRequest request,
                                String traceId, Long conversationId, String userId) {
        try {
            CapabilityResult result = capabilityGateway.invoke(capabilityId,
                    Map.of("query", query, "maxResults", toolPrefetchMaxSearchResults),
                    new CapabilityInvocationContext(userId, conversationId, traceId, null,
                            Set.of(capabilityId), Instant.now().plusSeconds(toolPrefetchPerToolTimeoutSeconds)));
            if (result.status() == CapabilityResult.Status.SUCCESS) {
                searchResultIngestionService.submit(toolName, result.content(), query);
            }
            return new StepEvidence("", EvidenceDimension.NEWS, "", result, request);
        } catch (CapabilityException error) {
            if (error.reason() == CapabilityException.Reason.DENIED
                    || error.reason() == CapabilityException.Reason.UNKNOWN) throw error;
            return new StepEvidence(capabilityId, EvidenceDimension.NEWS,
                    "{\"error\":true,\"errorCode\":\"" + error.reason().name()
                            + "\",\"message\":\"能力取证失败，请稍后重试\"}", null, request);
        }
    }

    /**
     * 多标的对比：所有标的共用一份已校验计划，每个 (标的, 步骤) 并行执行；
     * 结果在请求线程按标的、步骤顺序入账，证据编号整轮唯一，每个标的各自验收。
     */
    private PreparedToolContext prefetchMultiTarget(ExecutionPlan executionPlan, String userQuery, String traceId,
                                                    Long conversationId, String userId, Set<String> retrievedTickers) {
        OrdinaryPlan ordinaryPlan = executionPlan.ordinaryPlan();
        List<String> targets = ordinaryPlan.targets();
        ReadRequest read = executionPlan.readRequest();
        if (executionPlan.route() == PlanRoute.MARKET
                && targets.stream().anyMatch(target -> !tickerResolutionService.isLikelySecTicker(target))
                && ordinaryPlan.steps().stream().anyMatch(step -> step.request().intraday())) {
            return new PreparedToolContext("", "当前 A 股/港股取数只支持日、周、月线，无法为对比中的这些标的提供小时或分钟线。请改用日线后再对比。",
                    null, traceId, "BLOCKED");
        }
        List<PlanStep> steps = ordinaryPlan.steps().stream()
                .filter(step -> !step.action().isAgentRole() && step.action() != PlanAction.FINAL_ANSWER
                        && step.action() != PlanAction.KNOWLEDGE_RETRIEVAL)
                .toList();
        String topic = tickerResolutionService.withoutTickerMentions(userQuery, targets);
        emitProgress(traceId, conversationId, "thought", "正在并行获取 " + String.join("、", targets) + " 的证据。");

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(fanoutTimeoutSeconds);
        List<List<List<StepEvidence>>> results = fanOut(targets, steps, topic, deadline, userQuery, traceId,
                conversationId, userId, executionPlan);

        AtomicInteger ids = new AtomicInteger();
        int budget = Math.max(2500, ORDINARY_CONTEXT_BUDGET / targets.size());
        List<OrdinaryEvidence> evidences = new ArrayList<>();
        for (int t = 0; t < targets.size(); t++) {
            OrdinaryEvidence evidence = new OrdinaryEvidence(targets.get(t), read, objectMapper, evidenceEnvelopeMapper,
                    executionPlan.timeSensitivity(), ids::incrementAndGet, budget);
            for (List<StepEvidence> items : results.get(t)) items.forEach(item -> item.addTo(evidence));
            evidences.add(evidence);
        }
        // 动态计划取数失败或需求未满足的标的，补跑同类数据的兜底步骤；所需步骤相同的标的一起并行，共用同一截止时间。
        Map<List<PlanStep>, List<Integer>> fallbackGroups = new LinkedHashMap<>();
        Map<Integer, List<String>> fallbackExecuted = new LinkedHashMap<>();
        for (int t = 0; t < targets.size(); t++) {
            List<PlanStep> needed = fallbackStepsFor(ordinaryPlan, evidences.get(t), retrievedTickers.contains(targets.get(t)));
            if (needed.isEmpty()) continue;
            fallbackGroups.computeIfAbsent(needed, key -> new ArrayList<>()).add(t);
            fallbackExecuted.put(t, needed.stream().map(step -> step.action().label()).toList());
        }
        if (!fallbackGroups.isEmpty()) {
            emitProgress(traceId, conversationId, "thought", "部分标的主数据源未取得所需证据，补跑同类备用取数步骤。");
        }
        for (var group : fallbackGroups.entrySet()) {
            List<Integer> indexes = group.getValue();
            List<List<List<StepEvidence>>> fallbackResults = fanOut(indexes.stream().map(targets::get).toList(),
                    group.getKey(), topic, deadline, userQuery, traceId, conversationId, userId, executionPlan);
            for (int i = 0; i < indexes.size(); i++) {
                OrdinaryEvidence evidence = evidences.get(indexes.get(i));
                for (List<StepEvidence> items : fallbackResults.get(i)) items.forEach(item -> item.addTo(evidence));
            }
        }

        StringBuilder context = new StringBuilder(comparisonHeader(targets));
        StringBuilder sourceEvidence = new StringBuilder();
        List<String> citationIds = new ArrayList<>();
        List<List<String>> citationGroups = new ArrayList<>();
        List<Map<String, Object>> targetRows = new ArrayList<>();
        List<String> blocked = new ArrayList<>();
        int comparable = 0;
        boolean allCompleted = true;
        for (int t = 0; t < targets.size(); t++) {
            String target = targets.get(t);
            OrdinaryEvidence evidence = evidences.get(t);
            var decision = researchHarness.observeEvidence(traceId, ordinaryCompletionPolicy,
                    new RunContext(executionPlan.route().name(), Map.of(), executionPlan.timeSensitivity()), evidence.ledger());
            String outcome = decision.outcome() == HarnessOutcome.BLOCK ? "BLOCKED"
                    : decision.outcome() == HarnessOutcome.PASS && evidence.allIncluded() ? "COMPLETED"
                    : evidence.hasUsefulResult() ? "DEGRADED" : "FAILED";
            List<DataRequirement> unmet = unmetRequirements(ordinaryPlan, evidence, retrievedTickers.contains(target));
            if ("COMPLETED".equals(outcome) && !unmet.isEmpty()) outcome = "DEGRADED";
            // "可比较"按本题需求判断：拿到任意数据不够，必须满足全部明确需求。
            boolean usable = !"BLOCKED".equals(outcome) && !"FAILED".equals(outcome) && unmet.isEmpty();
            if ("BLOCKED".equals(outcome)) blocked.add(target);
            if (usable) {
                comparable++;
                citationGroups.add(evidence.citationIds());
            }
            allCompleted &= "COMPLETED".equals(outcome);
            citationIds.addAll(evidence.citationIds());
            sourceEvidence.append(evidence.context()).append('\n');
            context.append("\n## 标的 ").append(target).append('\n')
                    .append(ordinaryEvidenceContext(target, planRequestAttributes(ordinaryPlan, read), executionPlan.timeSensitivity().name(),
                            outcome, evidence.context()))
                    .append(requirementNote(unmet));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("target", target); row.put("taskOutcome", outcome); row.put("comparable", usable);
            row.put("unmetRequirements", unmet.stream().map(Enum::name).toList());
            targetRows.add(row);
            Map<String, Object> attributes = new LinkedHashMap<>(Map.of(
                    "kind", "ordinary-evidence", "route", executionPlan.route().name(), "target", target,
                    "timeSensitivity", executionPlan.timeSensitivity().name(), "request", read.attributes(),
                    "observations", evidence.observations(), "taskOutcome", outcome,
                    "planSource", ordinaryPlan.source().name(),
                    "requirements", ordinaryPlan.requirements().stream().map(Enum::name).sorted().toList(),
                    "unmetRequirements", unmet.stream().map(Enum::name).toList()));
            attributes.put("fallbackExecuted", fallbackExecuted.getOrDefault(t, List.of()));
            traceService.addStep(traceId, AgentStep.builder().action("Ordinary Evidence")
                    .observation(target + " 证据验收：" + outcome).attributes(attributes).build());
        }
        // 对比汇总：任一被拦截即停止；可比较不足两个无法对比；全部完成才算完成；其余为部分完成。
        String outcome = !blocked.isEmpty() ? "BLOCKED" : comparable < 2 ? "FAILED" : allCompleted ? "COMPLETED" : "DEGRADED";
        traceService.addStep(traceId, AgentStep.builder().action("Ordinary Comparison")
                .observation("多标的对比验收：" + outcome)
                .attributes(Map.of("kind", "ordinary-comparison", "taskOutcome", outcome, "targets", targetRows,
                        "comparableTargets", comparable))
                .build());
        emitProgress(traceId, conversationId, "observation", "对比证据验收：" + outcome + "；回答将按标的保留来源和数据缺口。");
        String direct = switch (outcome) {
            case "BLOCKED" -> String.join("、", blocked) + " 的取证标的或权限不符合请求，已停止生成对比。请确认股票代码后重试。";
            case "FAILED" -> "本轮能满足对比所需数据的标的不足两个，无法完成对比。请检查数据服务后重试，或减少对比的数据项；不会用其他周期或公司的数据替代。";
            default -> "";
        };
        return new PreparedToolContext(finishOrdinaryContext(context.toString(), outcome), direct, null, traceId, outcome,
                citationIds, sourceEvidence.toString(), null, citationGroups);
    }

    /**
     * 对每个 (标的, 步骤) 并行取数：执行线程只返回原始结果，按标的、步骤顺序收集；
     * 截止时间覆盖整轮，取消会传到正在执行的任务。
     */
    private List<List<List<StepEvidence>>> fanOut(List<String> targets, List<PlanStep> steps, String topic, long deadline,
                                                  String userQuery, String traceId, Long conversationId, String userId,
                                                  ExecutionPlan executionPlan) {
        List<List<FutureTask<List<StepEvidence>>>> tasks = new ArrayList<>();
        for (String target : targets) {
            boolean us = tickerResolutionService.isLikelySecTicker(target);
            // 搜索绑定到各自标的，不把整句对比问题原样搜两遍。
            String searchQuery = (target + " " + topic).strip();
            List<FutureTask<List<StepEvidence>>> row = new ArrayList<>();
            for (PlanStep step : steps) {
                FutureTask<List<StepEvidence>> task = new FutureTask<>(() -> {
                    ToolCallContext.set(traceId, conversationId, searchQuery);
                    try {
                        return fetch(step, target, us, searchQuery, userQuery, traceId, conversationId, userId, executionPlan);
                    } finally {
                        ToolCallContext.clear();
                    }
                });
                try {
                    ordinaryFanoutExecutor.execute(task);
                } catch (RejectedExecutionException rejected) {
                    task = null;
                }
                row.add(task);
            }
            tasks.add(row);
        }
        List<List<List<StepEvidence>>> results = new ArrayList<>();
        try {
            for (List<FutureTask<List<StepEvidence>>> row : tasks) {
                List<List<StepEvidence>> rowResults = new ArrayList<>();
                for (int i = 0; i < row.size(); i++) {
                    rowResults.add(await(row.get(i), steps.get(i), deadline, tasks));
                }
                results.add(rowResults);
            }
        } catch (InterruptedException interrupted) {
            cancelAll(tasks);
            Thread.currentThread().interrupt();
            var cancelled = new CancellationException("多标的取证已取消。");
            cancelled.initCause(interrupted);
            throw cancelled;
        }
        return results;
    }

    /** 等待单个扇出任务；超时、容量拒绝和失败都转成这一步的错误证据，策略拒绝则终止整轮。 */
    private List<StepEvidence> await(FutureTask<List<StepEvidence>> task, PlanStep step, long deadline,
                                     List<List<FutureTask<List<StepEvidence>>>> all) throws InterruptedException {
        if (task == null) return List.of(stepError(step, "CAPACITY_REJECTED", "取证容量已满，本步未执行"));
        try {
            return task.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (java.util.concurrent.TimeoutException timeout) {
            task.cancel(true);
            return List.of(stepError(step, "TIMEOUT", "取证超时，本步结果未采用"));
        } catch (java.util.concurrent.ExecutionException failure) {
            if (failure.getCause() instanceof CapabilityException denied
                    && (denied.reason() == CapabilityException.Reason.DENIED
                    || denied.reason() == CapabilityException.Reason.UNKNOWN)) {
                cancelAll(all);
                throw denied;
            }
            return List.of(stepError(step, "FAILED", "取证调用失败，请稍后重试"));
        }
    }

    private static void cancelAll(List<List<FutureTask<List<StepEvidence>>>> tasks) {
        tasks.forEach(row -> row.forEach(task -> { if (task != null) task.cancel(true); }));
    }

    private static StepEvidence stepError(PlanStep step, String code, String message) {
        return new StepEvidence(step.action().label(), dimensionOf(step.action()),
                "{\"error\":true,\"errorCode\":\"" + code + "\",\"message\":\"" + message + "\"}", null, step.request());
    }

    private static String comparisonHeader(List<String> targets) {
        return "本轮为多标的对比：" + String.join("、", targets) + "。各标的证据分区列出，证据编号整轮唯一。\n"
                + "对比规则：描述某个标的只能引用它自己分区内的证据，不得把一个标的的数据套用到另一个标的；"
                + "某标的缺少的数据必须写明缺失，不得用其他口径或推测补齐；不同市场的标的分别标注币种和会计口径，不做汇率换算。\n";
    }

    /**
     * 写给回答模型的请求参数：各取数步骤参数相同时只给一份；规划器让各步参数不同时按步骤列出，
     * 避免模型拿一份总参数去解读另一步的数据。
     */
    private static Map<String, Object> planRequestAttributes(OrdinaryPlan plan, ReadRequest read) {
        List<PlanStep> dataSteps = plan.steps().stream()
                .filter(step -> !step.action().isAgentRole() && step.action() != PlanAction.FINAL_ANSWER
                        && step.action() != PlanAction.KNOWLEDGE_RETRIEVAL)
                .toList();
        if (dataSteps.stream().allMatch(step -> step.request().equals(read))) return read.attributes();
        Map<String, Object> bySteps = new LinkedHashMap<>();
        // 同一动作可能以不同参数出现多次，键里带上步骤序号。
        for (int i = 0; i < dataSteps.size(); i++) {
            bySteps.put((i + 1) + "." + dataSteps.get(i).action().label(), dataSteps.get(i).request().attributes());
        }
        return bySteps;
    }

    /**
     * 动态计划取数后要补跑的兜底步骤：只取与失败数据同类（财报、行情、新闻）的被删步骤，
     * 类别来自取数失败的证据和未满足的需求。只补跑一轮，不再调用模型。
     */
    private static List<PlanStep> fallbackStepsFor(OrdinaryPlan plan, OrdinaryEvidence evidence, boolean filingTextRetrieved) {
        if (plan.fallbackSteps().isEmpty()) return List.of();
        Set<String> failing = new java.util.HashSet<>();
        evidence.observations().stream()
                .filter(row -> "FAILED".equals(row.get("status")) || "EMPTY".equals(row.get("status")))
                .forEach(row -> failing.add(String.valueOf(row.get("dimension"))));
        unmetRequirements(plan, evidence, filingTextRetrieved)
                .forEach(requirement -> failing.add(dimensionOf(requirement).name()));
        return plan.fallbackSteps().stream()
                .filter(step -> failing.contains(dimensionOf(step.action()).name()))
                .toList();
    }

    private static EvidenceDimension dimensionOf(PlanAction action) {
        return switch (action) {
            case GET_FINANCIAL_REPORTS, GET_STRUCTURED_FINANCIALS, SEARCH_COMPANY_REPORTS -> EvidenceDimension.FUNDAMENTALS;
            case SEARCH_NEWS, WEB_SEARCH -> EvidenceDimension.NEWS;
            default -> EvidenceDimension.MARKET;
        };
    }

    private static EvidenceDimension dimensionOf(DataRequirement requirement) {
        return switch (requirement) {
            case FINANCIAL_STATEMENTS, FILING_TEXT -> EvidenceDimension.FUNDAMENTALS;
            case NEWS -> EvidenceDimension.NEWS;
            case PRICE_HISTORY, VALUATION, TECHNICAL -> EvidenceDimension.MARKET;
        };
    }

    /** 每项明确需求都要有满足条件的证据；新闻正常查无结果算满足。 */
    private static List<DataRequirement> unmetRequirements(OrdinaryPlan plan, OrdinaryEvidence evidence,
                                                           boolean filingTextRetrieved) {
        return plan.requirements().stream()
                .filter(requirement -> !requirement.isSatisfied(evidence.observations(), filingTextRetrieved))
                .sorted()
                .toList();
    }

    private static String requirementNote(List<DataRequirement> unmet) {
        if (unmet.isEmpty()) return "";
        return "未满足的数据需求：" + String.join("、", unmet.stream().map(DataRequirement::label).toList())
                + "。回答必须明确说明这些数据本轮没有取得，不得推测补齐。\n";
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
    private ModelCompletion.Output safeAgentCall(String agentName, AgentCall agentCall) {
        try {
            log.info("{} started.", agentName);
            ModelCompletion.Output result = agentCall.call();
            log.info("{} returned, completion={}.", agentName, result.completion().status());
            return result;
        } catch (Exception e) {
            ResearchCapacityExceededException.rethrowIfPresent(e);
            log.warn("{} failed: {}", agentName, e.getMessage());
            return new ModelCompletion.Output("", ModelCompletion.failure(e));
        }
    }

    /**
     * 向 SSE 事件总线推送进度分片。
     */
    private void emitProgress(String traceId, Long conversationId, String type, String content) {
        chatStreamEmitter.emit(traceId, conversationId, type, content);
    }

    /** 旧制品未保存结束原因时不能据非空正文推断生成完整。 */
    public static AnalystDraft appendAnalystDraft(String context, String name, String content, String taskOutcome) {
        return appendAnalystDraft(context, name, content, taskOutcome, ModelCompletion.assess("", content));
    }

    /** 不完整草稿保留在调用记录中；最终回答只使用原始证据，避免把半份分析作为完整依据。 */
    public static AnalystDraft appendAnalystDraft(String context, String name, String content, String taskOutcome,
                                                ModelCompletion completion) {
        if (!completion.complete()) {
            String outcome = "BLOCKED".equals(taskOutcome) || "FAILED".equals(taskOutcome) ? taskOutcome : "DEGRADED";
            return new AnalystDraft(context + "\n领域分析未完整生成，本轮仅使用原始证据。\n",
                    outcome, completion.status().name(), null);
        }
        String combined = context + "## " + name + "\n" + content + "\n\n";
        return new AnalystDraft(combined, taskOutcome, "COMPLETED",
                new AnalystSection(context.length(), combined.length()));
    }

    public static String finishOrdinaryContext(String context, String taskOutcome) {
        return context + "\n最终执行验收：" + taskOutcome + "。不得将此状态升级。\n";
    }

    public record AnalystDraft(String context, String taskOutcome, String status, AnalystSection section) {}

    public static String ordinaryEvidenceContext(String ticker, Map<String, Object> requestAttributes,
                                                String timeSensitivity, String taskOutcome, String evidenceContext) {
        return "本轮标的：" + ticker + "\n请求参数：" + requestAttributes
                + "\n请求时间要求：" + timeSensitivity
                + "\n证据验收：" + taskOutcome + "。仅引用下列可用证据，使用 [E1] 等编号并保留来源；数据缺口必须明确说明。\n"
                + evidenceContext;
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
            List<String> citationIds,
            String sourceEvidenceContext,
            AnalystSection analystSection,
            List<List<String>> requiredCitationGroups
    ) {
        public PreparedToolContext {
            requiredCitationGroups = requiredCitationGroups == null ? List.of() : List.copyOf(requiredCitationGroups);
        }

        public PreparedToolContext(String context, String directAnswer, Long submittedTaskId,
                                   String eventTraceId, String taskOutcome, List<String> citationIds,
                                   String sourceEvidenceContext, AnalystSection analystSection) {
            this(context, directAnswer, submittedTaskId, eventTraceId, taskOutcome, citationIds,
                    sourceEvidenceContext, analystSection, List.of());
        }
        public PreparedToolContext(String context, String directAnswer, Long submittedTaskId,
                                   String eventTraceId, String taskOutcome, List<String> citationIds) {
            this(context, directAnswer, submittedTaskId, eventTraceId, taskOutcome, citationIds, "", null);
        }

        public PreparedToolContext(String context, String directAnswer, Long submittedTaskId,
                                   String eventTraceId, String taskOutcome) {
            this(context, directAnswer, submittedTaskId, eventTraceId, taskOutcome, List.of());
        }

        /** 生成成功仍需引用本轮可用证据；未知或缺失编号不能记作完整完成。 */
        public String outcomeForAnswer(String answer, ModelCompletion completion) {
            if ("BLOCKED".equals(taskOutcome) || "FAILED".equals(taskOutcome)) return taskOutcome;
            if (completion.status() == ModelCompletion.Status.FAILED) return "FAILED";
            if (!completion.complete()) return "DEGRADED";
            if (!"COMPLETED".equals(taskOutcome) || citationIds.isEmpty()) return taskOutcome;
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\[E([0-9]+)\\]").matcher(answer == null ? "" : answer);
            boolean cited = false;
            Set<String> citedIds = new java.util.HashSet<>();
            while (matcher.find()) {
                if (!citationIds.contains("E" + matcher.group(1))) return "DEGRADED";
                cited = true;
                citedIds.add("E" + matcher.group(1));
            }
            // 对比任务：每个可比较的标的都要在回答里被引用，否则不能算完整对比。
            boolean everyTargetCited = requiredCitationGroups.stream()
                    .allMatch(group -> group.stream().anyMatch(citedIds::contains));
            return cited && everyTargetCited ? taskOutcome : "DEGRADED";
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

    /** 仅记录成功且完整写入的分析草稿范围；不从模型生成的标题反推边界。 */
    public record AnalystSection(int start, int end) {
    }

    @FunctionalInterface
    private interface TickerToolCall {
        String call(String ticker);
    }

    @FunctionalInterface
    private interface AgentCall {
        ModelCompletion.Output call() throws Exception;
    }


}
