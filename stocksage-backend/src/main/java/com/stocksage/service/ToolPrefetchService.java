package com.stocksage.service;

import com.stocksage.exception.ResearchCapacityExceededException;
import com.stocksage.evolution.AgentPolicyBundle;
import com.stocksage.evolution.FundamentalsRuntimeIdentity;

import com.stocksage.knowledge.SearchResultIngestionService;
import com.stocksage.research.ResearchSubmissionService;


import com.stocksage.evidence.adapter.EvidenceEnvelopeMapper;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.agent.Coordinator;
import com.stocksage.agent.ExecutionPlan;
import com.stocksage.agent.PlanAction;
import com.stocksage.agent.PlanRoute;
import com.stocksage.agent.ReadRequest;
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

import java.util.List;
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
    private final ResearchHarness researchHarness;
    private final OrdinaryCompletionPolicy ordinaryCompletionPolicy;
    private final TraceService traceService;

    /** 单次搜索预取允许返回的最大结果数。 */
    @Value("${stocksage.chat.tool-prefetch.max-search-results:5}")
    private int toolPrefetchMaxSearchResults;

    @Value("${stocksage.chat.tool-prefetch.per-tool-timeout-seconds:8}")
    private long toolPrefetchPerToolTimeoutSeconds = 8;

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
        return prefetch(executionPlan, userQuery, traceId, conversationId, userId, selectedModel, null);
    }

    public PreparedToolContext prefetch(
            ExecutionPlan executionPlan, String userQuery, String traceId, Long conversationId,
            String userId, Coordinator.SelectedModel selectedModel, String submissionId
    ) {
        return prefetch(executionPlan, userQuery, traceId, conversationId, userId, selectedModel, submissionId, null);
    }

    public PreparedToolContext prefetch(
            ExecutionPlan executionPlan, String userQuery, String traceId, Long conversationId,
            String userId, Coordinator.SelectedModel selectedModel, String submissionId, FundamentalsRuntimeIdentity.Request methodRequest
    ) {
        if (executionPlan == null || executionPlan.actions() == null) {
            return PreparedToolContext.empty();
        }

        if (executionPlan.route() == PlanRoute.DIRECT) return PreparedToolContext.empty();
        List<PlanAction> actions = executionPlan.actions();
        String primaryTicker = tickerResolutionService.resolvePrimaryTicker(userQuery, conversationId);
        if (isDeepResearchPlan(actions)) {
            // 深度计划交给持久化任务与 Redis 队列；提交失败时由其内部切换同步降级路线。
            var result = researchSubmissionService.submit(
                    userQuery, traceId, conversationId, userId, selectedModel, primaryTicker,
                    executionPlan.timeSensitivity(), submissionId);
            return new PreparedToolContext(result.context(), result.directAnswer(), result.submittedTaskId(),
                    result.eventTraceId(), result.taskOutcome());
        }

        ReadRequest read = executionPlan.readRequest();
        String clarification = read.clarification();
        boolean us = tickerResolutionService.isLikelySecTicker(primaryTicker);
        if (executionPlan.route() == PlanRoute.MARKET && read.intraday() && !us) {
            clarification = "当前 A 股/港股取数只支持日、周、月线，无法提供本次小时或分钟线。请改用日线或选择支持的美股标的。";
        }
        if (!clarification.isBlank()) return new PreparedToolContext("", clarification, null, traceId, "BLOCKED");
        OrdinaryEvidence evidence = new OrdinaryEvidence(
                primaryTicker, read, objectMapper, evidenceEnvelopeMapper, executionPlan.timeSensitivity());
        emitProgress(traceId, conversationId, "thought", "正在按已校验的周期与粒度获取证据。");
        for (PlanAction action : actions) {
            switch (action) {
                case GET_FINANCIAL_REPORTS, GET_STRUCTURED_FINANCIALS -> evidence.call("getFinancialReports", EvidenceDimension.FUNDAMENTALS,
                        () -> withResolvedTicker(primaryTicker, ticker -> fundamentalsTools.getFinancialReports(ticker, read.reportPeriod(), read.reportYears())));
                case SEARCH_COMPANY_REPORTS -> {
                    String result = evidence.call("searchCompanyReports", EvidenceDimension.FUNDAMENTALS,
                            () -> withResolvedTicker(primaryTicker, ticker -> fundamentalsTools.searchCompanyReports(ticker,
                                    read.reportPeriod().equals("quarterly") ? "10-Q 季报" : "10-K 年报", toolPrefetchMaxSearchResults)));
                    searchResultIngestionService.submit("searchCompanyReports", result, userQuery);
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
                        prefetchSearch(evidence, LocalNewsSearchCapabilityAdapter.ID, "searchNews",
                                userQuery, traceId, conversationId, userId);
                    } else if (skill.evidence().isEmpty()) {
                        evidence.add("searchNews", EvidenceDimension.NEWS, "{\"error\":true,\"message\":\"未取得可用新闻证据\"}");
                    } else {
                        for (var result : skill.evidence()) evidence.add(result);
                    }
                }
                case WEB_SEARCH -> {
                    prefetchSearch(evidence, LocalWebSearchCapabilityAdapter.ID, "webSearch",
                            userQuery, traceId, conversationId, userId);
                }
                default -> { /* 标的已解析；角色分析在取证完成后执行。 */ }
            }
        }
        var decision = researchHarness.observeEvidence(traceId, ordinaryCompletionPolicy,
                new RunContext(executionPlan.route().name(), Map.of(), executionPlan.timeSensitivity()), evidence.ledger());
        String outcome = decision.outcome() == HarnessOutcome.BLOCK ? "BLOCKED"
                : decision.outcome() == HarnessOutcome.PASS && evidence.allIncluded() ? "COMPLETED"
                : evidence.hasUsefulResult() ? "DEGRADED" : "FAILED";
        StringBuilder context = new StringBuilder(ordinaryEvidenceContext(primaryTicker, read.attributes(),
                executionPlan.timeSensitivity().name(), outcome, evidence.context()));
        String sourceEvidenceContext = evidence.context();
        PlanAction analyst = actions.stream().filter(PlanAction::isAgentRole).findFirst().orElse(null);
        var methodSelection = analyst == PlanAction.FUNDAMENTALS_AGENT
                ? fundamentalsAgent.selectMethod(userId, executionPlan.route() == PlanRoute.FUNDAMENTALS
                    ? evidence.evolutionTaskTags() : Set.of(), evidence.evolutionEvidenceTags(),
                    sourceEvidenceContext, methodRequest, agentPrefetchTimeoutSeconds) : null;
        var pinnedMethod = methodSelection == null ? null : methodSelection.bundle();
        var methodObservation = new java.util.concurrent.atomic.AtomicReference<FundamentalsAgent.Analysis>();
        String analystStatus = "NOT_EXECUTED";
        String analystCompletedAt = "";
        long analystStarted = System.nanoTime();
        AnalystSection analystSection = null;
        if (analyst != null && evidence.hasUsefulResult() && !outcome.equals("BLOCKED")) {
            String agentInput = context.toString();
            FutureTask<String> future = null;
            try {
                // FutureTask propagates cancellation to the running worker; CompletableFuture does not.
                future = new FutureTask<>(
                    () -> safeAgentCall(analyst.label(), () -> switch (analyst) {
                        case FUNDAMENTALS_AGENT -> {
                            var observation = fundamentalsAgent.analyzeObserved(userQuery, agentInput, pinnedMethod);
                            methodObservation.set(observation);
                            yield observation.content();
                        }
                        case MARKET_AGENT -> marketAgent.analyze(userQuery, agentInput);
                        case NEWS_AGENT -> newsAgent.analyze(userQuery, agentInput);
                        default -> throw new IllegalStateException("Unsupported analyst: " + analyst);
                    }));
                agentTaskExecutor.execute(future);
                emitProgress(traceId, conversationId, "thought", "证据已取得，正在等待领域分析完成。");
                String result = future.get(agentPrefetchTimeoutSeconds, TimeUnit.SECONDS);
                AnalystDraft draft = appendAnalystDraft(context.toString(), analyst.label(), result, outcome);
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
        if (analyst == PlanAction.FUNDAMENTALS_AGENT) {
            evidenceAttributes.put("methodBundle", pinnedMethod.identity());
            evidenceAttributes.put("methodSelection", methodSelection.attributes());
            evidenceAttributes.put("analystCompletedAt", analystCompletedAt);
            evidenceAttributes.put("analystDurationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - analystStarted));
            var observed = methodObservation.get();
            evidenceAttributes.put("analystUsage", observed == null ? Map.of("usageSource", "NO_DATA") : observed.responseMetadata());
            if (observed != null) {
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

    /** 普通搜索也经过注册表；策略拒绝不允许退回直接工具调用。 */
    private void prefetchSearch(OrdinaryEvidence evidence, String capabilityId, String toolName,
                                String query, String traceId, Long conversationId, String userId) {
        try {
            CapabilityResult result = capabilityGateway.invoke(capabilityId,
                    Map.of("query", query, "maxResults", toolPrefetchMaxSearchResults),
                    new CapabilityInvocationContext(userId, conversationId, traceId, null,
                            Set.of(capabilityId), Instant.now().plusSeconds(toolPrefetchPerToolTimeoutSeconds)));
            evidence.add(result);
            if (result.status() == CapabilityResult.Status.SUCCESS) {
                searchResultIngestionService.submit(toolName, result.content(), query);
            }
        } catch (CapabilityException error) {
            if (error.reason() == CapabilityException.Reason.DENIED
                    || error.reason() == CapabilityException.Reason.UNKNOWN) throw error;
            evidence.add(capabilityId, EvidenceDimension.NEWS,
                    "{\"error\":true,\"errorCode\":\"" + error.reason().name()
                            + "\",\"message\":\"能力取证失败，请稍后重试\"}");
        }
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
    private String safeAgentCall(String agentName, AgentCall agentCall) {
        try {
            log.info("{} started.", agentName);
            String result = agentCall.call();
            log.info("{} completed, chars={}.", agentName, result == null ? 0 : result.length());
            return (result == null || result.isBlank()) ? null : result;
        } catch (Exception e) {
            ResearchCapacityExceededException.rethrowIfPresent(e);
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

    /** 草稿完整性由最终 Prompt 总预算统一检查；普通执行与冻结回放共享状态和可消融范围。 */
    public static AnalystDraft appendAnalystDraft(String context, String name, String content, String taskOutcome) {
        if (content == null || content.isBlank()) {
            return new AnalystDraft(context, "DEGRADED", "EMPTY", null);
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
            AnalystSection analystSection
    ) {
        public PreparedToolContext(String context, String directAnswer, Long submittedTaskId,
                                   String eventTraceId, String taskOutcome, List<String> citationIds,
                                   String sourceEvidenceContext) {
            this(context, directAnswer, submittedTaskId, eventTraceId, taskOutcome, citationIds,
                    sourceEvidenceContext, null);
        }
        public PreparedToolContext(String context, String directAnswer, Long submittedTaskId,
                                   String eventTraceId, String taskOutcome, List<String> citationIds) {
            this(context, directAnswer, submittedTaskId, eventTraceId, taskOutcome, citationIds, "");
        }

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

    /** 仅记录成功且完整写入的分析草稿范围；不从模型生成的标题反推边界。 */
    public record AnalystSection(int start, int end) {
    }

    @FunctionalInterface
    private interface TickerToolCall {
        String call(String ticker);
    }

    @FunctionalInterface
    private interface AgentCall {
        String call() throws Exception;
    }


}
