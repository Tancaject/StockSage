package com.stocksage.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.agent.intent.AnalysisDepth;
import com.stocksage.agent.intent.FineIntent;
import com.stocksage.agent.intent.IntentDecision;
import com.stocksage.agent.intent.IntentGroup;
import com.stocksage.agent.intent.IntentRecognitionRequest;
import com.stocksage.agent.intent.IntentRecognitionResult;
import com.stocksage.agent.intent.IntentRecognitionService;
import com.stocksage.agent.intent.IntentSignalSource;
import com.stocksage.agent.intent.TimeSensitivity;
import com.stocksage.service.TickerResolutionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 将每次用户请求路由到合适的研究路径。
 *
 * <p>Coordinator 有意把“判断要做什么”和“回答用户”拆开：意图服务融合近期上下文、
 * LLM、Embedding 与本地模式信号后，直接产出一个经过验证的 {@link PlanRoute}；Coordinator
 * 只从服务器拥有的计划目录展开 {@link ExecutionPlan}。当识别链路整体不可用时，确定性规则会兜底，
 * 保证对话流程仍然可用。</p>
 */
@Slf4j
@Service
public class Coordinator {

    public static final String MULTI_TARGET_UNSUPPORTED = "MULTI_TARGET_UNSUPPORTED";
    public static final String TARGET_REWRITE_MISMATCH = "TARGET_REWRITE_MISMATCH";

    /** 统一执行语义、向量、模式和 n-gram 识别的意图服务。 */
    private final IntentRecognitionService intentRecognitionService;
    /** 各路由共用的无工具最终回答客户端。 */
    private final ChatClient responseChatClient;
    /** 确定性兜底中识别问题是否包含股票标的。 */
    private final TickerResolutionService tickerResolutionService;
    /** 唯一的 route -> actions/taskType/modelTier 后端目录。 */
    private final RoutePlanCatalog routePlanCatalog;

    /** FAST 层级映射的后端白名单模型名。 */
    @Value("${stocksage.chat.model-routing.fast-model:qwen3.6-flash}")
    private String fastModel;

    /** STANDARD 层级映射的后端白名单模型名。 */
    @Value("${stocksage.chat.model-routing.standard-model:${STOCKSAGE_CHAT_MODEL:qwen3.6-plus}}")
    private String standardModel;

    /** STRONG 层级映射的后端白名单模型名。 */
    @Value("${stocksage.chat.model-routing.strong-model:qwen3.6-max-preview}")
    private String strongModel;

    /** 图片输入固定使用的视觉模型名。 */
    @Value("${stocksage.chat.model-routing.vision-model:qwen3-vl-plus}")
    private String visionModel;

    /** 最终回答请求的统一温度配置。 */
    @Value("${stocksage.chat.model-routing.temperature:${STOCKSAGE_CHAT_TEMPERATURE:0.7}}")
    private double modelRoutingTemperature;

    /** 最终回答的最大输出 token 数。 */
    @Value("${stocksage.chat.model-routing.max-output-tokens:${STOCKSAGE_CHAT_MAX_OUTPUT_TOKENS:4096}}")
    private int modelRoutingMaxOutputTokens;

    /** 注入意图识别、最终回答和服务器计划目录。 */
    @Autowired
    public Coordinator(IntentRecognitionService intentRecognitionService,
                       @Qualifier("chatClient") ChatClient responseChatClient,
                       TickerResolutionService tickerResolutionService,
                       RoutePlanCatalog routePlanCatalog) {
        this.intentRecognitionService = intentRecognitionService;
        this.responseChatClient = responseChatClient;
        this.tickerResolutionService = tickerResolutionService;
        this.routePlanCatalog = routePlanCatalog;
    }

    /**
     * 保留旧测试/小工具的构造形式；它仍走同一 IntentRecognitionService，只禁用 embedding I/O。
     */
    public Coordinator(@Qualifier("coordinatorChatClient") ChatClient routingChatClient,
                       @Qualifier("chatClient") ChatClient responseChatClient,
                       ObjectMapper objectMapper,
                       TickerResolutionService tickerResolutionService) {
        this(new IntentRecognitionService(routingChatClient, objectMapper), responseChatClient,
                tickerResolutionService, new RoutePlanCatalog());
    }

    /**
     * 请求意图决定并生成执行计划；局部来源失败由融合层 abstain，只有决定对象缺失时才走规则兜底。
     *
     * @param userQuery 用户原始问题
     * @param ragHitCount 知识库命中数
     * @return 服务器拥有动作列表和模型层级的执行计划
     */
    public ExecutionPlan plan(String userQuery, int ragHitCount) {
        return plan(userQuery, ragHitCount, List.of());
    }

    /**
     * 使用最近三轮上下文识别意图，并由识别结果的 targetRoute 直接生成计划。
     *
     * <p>命中数量不进入意图提示词，避免检索结果反过来污染任务分类；
     * {@code ragHitCount} 只用于计划的模型层级和诊断元数据。</p>
     */
    public ExecutionPlan plan(String userQuery,
                              int ragHitCount,
                              List<String> recentTurns) {
        try {
            IntentRecognitionResult recognition = recognizeIntent(
                    userQuery, recentTurns, 0, false, List.of());
            return planRecognized(recognition, userQuery, ragHitCount);
        } catch (Exception e) {
            log.warn("Coordinator intent recognition failed, using deterministic fallback. errorType={}",
                    e.getClass().getSimpleName());
            return fallbackPlan(userQuery, ragHitCount, "INTENT_RECOGNITION_FAILED", System.nanoTime());
        }
    }

    /** 生产链路可先识别得到 resolvedQuery，再把它用于 RAG、ticker 和工具执行。 */
    public IntentRecognitionResult recognizeIntent(String userQuery,
                                                    List<String> recentTurns,
                                                    int ragHitCount,
                                                    boolean hasImages,
                                                    List<String> tickerCandidates) {
        try {
            IntentRecognitionResult recognition = intentRecognitionService.recognize(new IntentRecognitionRequest(
                    userQuery, recentTurns, ragHitCount, hasImages, tickerCandidates));
            return enforceSingleTargetContract(recognition, userQuery);
        } catch (Exception e) {
            log.warn("Intent recognition pipeline failed; deterministic plan remains available. errorType={}",
                    e.getClass().getSimpleName());
            IntentDecision safeDecision = new IntentDecision(
                    FineIntent.UNKNOWN,
                    IntentGroup.UNKNOWN,
                    PlanRoute.DIRECT,
                    0.0,
                    TimeSensitivity.UNSPECIFIED,
                    AnalysisDepth.UNSPECIFIED,
                    Map.of(),
                    userQuery,
                    Map.of(),
                    true,
                    List.of("INTENT_PIPELINE_FAILED")
            );
            return enforceSingleTargetContract(new IntentRecognitionResult(
                    safeDecision,
                    "",
                    false,
                    "意图识别不可用，转入确定性安全兜底。",
                    List.of(),
                    "INTENT_RECOGNITION_FAILED",
                    0L
            ), userQuery);
        }
    }

    /** 将已经完成的意图识别直接转换为服务器拥有的执行计划。 */
    public ExecutionPlan planRecognized(IntentRecognitionResult recognition,
                                        String originalQuery,
                                        int ragHitCount) {
        if (recognition == null || recognition.decision() == null) {
            return fallbackPlan(originalQuery, ragHitCount, "INTENT_DECISION_MISSING", System.nanoTime());
        }
        // 即使全部识别来源 abstain，IntentDecision 也会给出 DIRECT + needsClarification。
        // 这里必须执行该安全决定，不能再按关键词做第二次语义路由。
        return buildPlan(enforceSingleTargetContract(recognition, originalQuery), originalQuery, ragHitCount);
    }

    /**
     * 只使用本地规则生成计划，供回归测试和模型不可用时复用。
     *
     * @param userQuery 用户原始问题
     * @param ragHitCount 知识库命中数
     * @return 不访问模型或外部工具的固定计划
     */
    public ExecutionPlan planDeterministically(String userQuery, int ragHitCount) {
        return fallbackPlan(userQuery, ragHitCount, "EXPLICIT_DETERMINISTIC", System.nanoTime());
    }

    /**
     * 按能力层级和输入模态流式输出最终回答。
     */
    public Flux<String> streamAnswer(List<Message> promptMessages, boolean usePreparedContextOnly, ModelTier modelTier, boolean useVisionModel) {
        SelectedModel selectedModel = selectFinalAnswerModel(modelTier, usePreparedContextOnly, useVisionModel);
        ModelTier effectiveTier = selectedModel.tier();
        String modelName = selectedModel.modelName();
        if (usePreparedContextOnly) {
            log.info("Coordinator streaming final answer from prepared context only.");
        }
        log.info("Coordinator selected final-answer model tier={}, model={}, preparedContextOnly={}",
                effectiveTier, modelName, usePreparedContextOnly);

        ChatClient.ChatClientRequestSpec requestSpec = responseChatClient.prompt()
                .messages(promptMessages);
        if (modelName != null && !modelName.isBlank()) {
            String resolvedModel = modelName.trim();
            requestSpec = requestSpec.options(OpenAiChatOptions.builder()
                    .model(resolvedModel)
                    .temperature(modelRoutingTemperature)
                    .maxTokens(modelRoutingMaxOutputTokens)
                    .build());
        }
        return requestSpec
                .stream()
                .content();
    }

    /**
     * 解析最终回答会实际使用的模型层级和模型名；图片输入强制走视觉模型白名单。
     */
    public SelectedModel selectFinalAnswerModel(ModelTier modelTier, boolean usePreparedContextOnly, boolean useVisionModel) {
        ModelTier safeTier = modelTier == null ? ModelTier.STANDARD : modelTier;
        ModelTier effectiveTier = usePreparedContextOnly ? ModelTier.max(safeTier, ModelTier.STRONG) : safeTier;
        if (useVisionModel) {
            effectiveTier = ModelTier.max(effectiveTier, ModelTier.STANDARD);
        }
        String configuredModel = useVisionModel ? visionModel : modelNameFor(effectiveTier);
        String modelName = configuredModel == null || configuredModel.isBlank() ? null : configuredModel.trim();
        return new SelectedModel(effectiveTier, modelName);
    }

    public record SelectedModel(ModelTier tier, String modelName) {
    }

    /** 把融合后的直接 targetRoute 转换为服务器拥有动作表的固定执行计划。 */
    private ExecutionPlan buildPlan(
            IntentRecognitionResult recognition,
            String originalQuery,
            int ragHitCount
    ) {
        IntentDecision decision = recognition.decision();
        PlanRoute route = decision.targetRoute();
        String resolvedQuery = decision.resolvedQuery().isBlank() ? originalQuery : decision.resolvedQuery();
        RoutePlanCatalog.RoutePlanSpec planSpec = routePlanCatalog.resolve(
                route, resolvedQuery, ragHitCount, decision.needsClarification());
        String taskType = planSpec.taskType();
        List<PlanAction> actions = planSpec.actions();
        ModelTier modelTier = planSpec.modelTier();
        String intentSummary = fineIntentLabel(decision.fineIntent());
        String thought = "Coordinator 路由：理解为「" + intentSummary
                + "」，直接选择「" + taskType + "」；" + recognition.rationale();
        String observation = buildPlanObservation(route, modelTier, actions);
        Map<String, Double> sourceScores = new LinkedHashMap<>();
        decision.sourceScores().forEach((source, score) -> sourceScores.put(source.name(), score));
        List<String> matchedSignals = sourceScores.entrySet().stream()
                .map(entry -> entry.getKey().toLowerCase(Locale.ROOT) + "="
                        + String.format(Locale.ROOT, "%.2f", entry.getValue()))
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        matchedSignals.add(confidenceSignal(decision.confidence()));
        RoutingDecisionSource source = !recognition.hasUsableSignal()
                ? RoutingDecisionSource.DETERMINISTIC_FALLBACK
                : recognition.llmOnly()
                ? RoutingDecisionSource.ROUTING_LLM
                : RoutingDecisionSource.INTENT_FUSION;
        RoutingDecisionMetadata routingDecision = new RoutingDecisionMetadata(
                source,
                recognition.rawRoute(),
                route,
                intentSummary,
                recognition.rationale(),
                decision.confidence(),
                matchedSignals,
                ragHitCount,
                recognition.degradationReason(),
                recognition.durationMs(),
                decision.fineIntent().name(),
                decision.intentGroup().name(),
                decision.timeSensitivity().name(),
                decision.analysisDepth().name(),
                decision.entities(),
                sourceScores,
                decision.needsClarification(),
                decision.reasonCodes()
        );
        ReadRequest readRequest = ReadRequest.parse(route, resolvedQuery, decision.entities());
        // 当前消息的显式约束不能被模型改写覆盖；省略的约束仍从已消歧问题取得。
        ReadRequest explicit = ReadRequest.parse(route, originalQuery, readRequest.attributes().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> String.valueOf(entry.getValue()))));
        if (explicit.clarification().isBlank() && !readRequest.clarification().isBlank()) explicit = readRequest;
        if (explicit.barsOnly()) {
            actions = List.of(PlanAction.GET_STOCK_KLINE, PlanAction.FINAL_ANSWER);
        }
        observation = buildPlanObservation(route, modelTier, actions);
        return new ExecutionPlan(
                route, taskType, thought, actions, observation, modelTier, routingDecision, resolvedQuery, explicit);
    }

    /**
     * 本地路由选择，同时用于回归测试，也用于路由模型不可用时的运行时兜底。
     */
    private ExecutionPlan fallbackPlan(String userQuery,
                                       int ragHitCount,
                                       String fallbackReason,
                                       long startedNanos) {
        String query = userQuery == null ? "" : userQuery.trim();
        if (hasMultipleExplicitTargets(query)) {
            IntentDecision decision = new IntentDecision(
                    FineIntent.COMPARISON,
                    IntentGroup.RESEARCH,
                    PlanRoute.DIRECT,
                    1.0,
                    TimeSensitivity.UNSPECIFIED,
                    AnalysisDepth.UNSPECIFIED,
                    Map.of(),
                    query,
                    Map.of(IntentSignalSource.FALLBACK, 1.0),
                    true,
                    List.of(MULTI_TARGET_UNSUPPORTED)
            );
            return buildPlan(new IntentRecognitionResult(
                    decision,
                    "",
                    false,
                    "当前执行链一次只支持一个明确标的。",
                    List.of(),
                    fallbackReason,
                    elapsedMillis(startedNanos)
            ), query, ragHitCount);
        }
        String lower = query.toLowerCase(Locale.ROOT);
        boolean conceptQuestion = containsAny(lower, "what is", "什么是", "为什么", "解释", "概念");
        PlanRoute route;

        if (containsAny(lower, "worth", "invest", "valuation", "值不值得", "投资价值", "长期", "买入", "卖出")) {
            route = PlanRoute.DEEP;
        } else if (containsAny(lower, "10-k", "10-q", "sec", "filing", "risk factor", "风险因素", "财报", "年报", "季报")) {
            route = PlanRoute.FUNDAMENTALS;
        } else if (containsAny(lower, "news", "latest", "recent", "today", "fed", "fomc", "最新", "新闻", "消息", "今天", "近期", "美联储")) {
            route = PlanRoute.NEWS;
        } else if (tickerResolutionService.containsLikelyTicker(query)
                || containsAny(lower, "k线", "行情", "技术指标", "price", "股价")
                || (!conceptQuestion && containsAny(lower, "pe", "pb", "roe"))) {
            route = PlanRoute.MARKET;
        } else if (ragHitCount > 0 || conceptQuestion) {
            route = PlanRoute.DIRECT;
        } else {
            // 没有任何可靠领域信号时留在 DIRECT，绝不凭缺省值触发新闻工具链。
            route = PlanRoute.DIRECT;
        }

        RoutePlanCatalog.RoutePlanSpec planSpec = routePlanCatalog.resolve(route, query, ragHitCount);
        List<PlanAction> actions = planSpec.actions();
        String taskType = planSpec.taskType();
        String thought = "Coordinator 路由：识别为「" + taskType + "」；使用规则兜底完成分流。";
        ModelTier modelTier = planSpec.modelTier();
        String observation = buildPlanObservation(route, modelTier, actions);
        List<String> matchedSignals = new java.util.ArrayList<>();
        matchedSignals.add(route.name().toLowerCase(Locale.ROOT) + "-rule");
        if (ragHitCount > 0) {
            matchedSignals.add("rag-hit");
        }
        RoutingDecisionMetadata routingDecision = new RoutingDecisionMetadata(
                RoutingDecisionSource.DETERMINISTIC_FALLBACK,
                route.name(),
                route,
                taskType,
                "Matched deterministic fallback signals: " + String.join(", ", matchedSignals),
                0.0,
                matchedSignals,
                ragHitCount,
                fallbackReason,
                elapsedMillis(startedNanos),
                fallbackFineIntent(route, query).name(),
                fallbackIntentGroup(route).name(),
                fallbackTimeSensitivity(query).name(),
                (route == PlanRoute.DEEP ? AnalysisDepth.DEEP : AnalysisDepth.STANDARD).name(),
                Map.of(),
                Map.of(IntentSignalSource.FALLBACK.name(), 0.0),
                false,
                matchedSignals
        );
        ReadRequest read = ReadRequest.parse(route, query, Map.of());
        if (read.barsOnly()) actions = List.of(PlanAction.GET_STOCK_KLINE, PlanAction.FINAL_ANSWER);
        return new ExecutionPlan(route, taskType, thought, actions, buildPlanObservation(route, modelTier, actions),
                modelTier, routingDecision, query, read);
    }

    /** 多标的尚无真实执行器时，在任何检索或工具执行前收敛为单标的澄清。 */
    private IntentRecognitionResult enforceSingleTargetContract(IntentRecognitionResult recognition,
                                                                 String userQuery) {
        if (recognition == null || recognition.decision() == null) {
            return recognition;
        }
        IntentDecision current = recognition.decision();
        String resolvedQuery = current.resolvedQuery().isBlank() ? userQuery : current.resolvedQuery();
        String explicitTicker = tickerResolutionService.resolveExplicitTicker(userQuery);
        String rewrittenTicker = tickerResolutionService.resolveExplicitTicker(resolvedQuery);
        boolean targetChanged = !explicitTicker.isBlank() && !rewrittenTicker.isBlank()
                && !explicitTicker.equals(rewrittenTicker);
        boolean comparisonIntent = current.fineIntent() == FineIntent.COMPARISON;
        if (!comparisonIntent
                && !hasMultipleExplicitTargets(userQuery)
                && !hasMultipleExplicitTargets(resolvedQuery)) {
            if (!targetChanged) {
                if (explicitTicker.isBlank() || !rewrittenTicker.isBlank()) return recognition;
                // 改写可以省略原文，但不能因此重新从历史中选择另一只股票。
                IntentDecision bound = new IntentDecision(current.fineIntent(), current.intentGroup(),
                        current.targetRoute(), current.confidence(), current.timeSensitivity(), current.analysisDepth(),
                        current.entities(), "[ticker=" + explicitTicker + "] " + resolvedQuery, current.sourceScores(),
                        current.needsClarification(), current.reasonCodes());
                return new IntentRecognitionResult(bound, recognition.rawRoute(), recognition.rawRouteValid(),
                        recognition.rationale(), recognition.signals(), recognition.degradationReason(), recognition.durationMs());
            }
        }
        java.util.ArrayList<String> reasonCodes = new java.util.ArrayList<>(current.reasonCodes());
        String reason = targetChanged ? TARGET_REWRITE_MISMATCH : MULTI_TARGET_UNSUPPORTED;
        if (!reasonCodes.contains(reason)) reasonCodes.add(reason);
        IntentDecision safeDecision = new IntentDecision(
                FineIntent.COMPARISON,
                IntentGroup.RESEARCH,
                PlanRoute.DIRECT,
                current.confidence(),
                current.timeSensitivity(),
                current.analysisDepth(),
                current.entities(),
                targetChanged ? userQuery : resolvedQuery,
                current.sourceScores(),
                true,
                reasonCodes
        );
        return new IntentRecognitionResult(
                safeDecision,
                recognition.rawRoute(),
                recognition.rawRouteValid(),
                targetChanged ? "改写标的与用户明确指定的股票不一致，已停止取证。" : "当前执行链一次只支持一个明确标的。",
                recognition.signals(),
                recognition.degradationReason(),
                recognition.durationMs()
        );
    }

    private boolean hasMultipleExplicitTargets(String query) {
        String safeQuery = query == null ? "" : query;
        String primaryTicker = tickerResolutionService.resolveExplicitTicker(safeQuery);
        return !primaryTicker.isBlank()
                && tickerResolutionService.hasConflictingExplicitTicker(safeQuery, primaryTicker);
    }

    private String fineIntentLabel(FineIntent intent) {
        FineIntent safeIntent = intent == null ? FineIntent.UNKNOWN : intent;
        return switch (safeIntent) {
            case KNOWLEDGE_EXPLANATION -> "金融知识解释";
            case MARKET_DATA -> "市场数据查询";
            case TECHNICAL_ANALYSIS -> "技术面分析";
            case FUNDAMENTALS -> "公司基本面与财报";
            case NEWS_EVENT -> "新闻与事件";
            case COMPARISON -> "标的比较";
            case PORTFOLIO_DIAGNOSIS -> "组合诊断";
            case DEEP_RESEARCH -> "深度投资研究";
            case UNKNOWN -> "待澄清意图";
        };
    }

    private FineIntent fallbackFineIntent(PlanRoute route, String query) {
        String lower = query == null ? "" : query.toLowerCase(Locale.ROOT);
        return switch (route) {
            case DIRECT -> FineIntent.KNOWLEDGE_EXPLANATION;
            case MARKET -> containsAny(lower, "k线", "技术指标", "macd", "rsi")
                    ? FineIntent.TECHNICAL_ANALYSIS
                    : FineIntent.MARKET_DATA;
            case FUNDAMENTALS -> FineIntent.FUNDAMENTALS;
            case NEWS -> FineIntent.NEWS_EVENT;
            case DEEP -> FineIntent.DEEP_RESEARCH;
        };
    }

    private IntentGroup fallbackIntentGroup(PlanRoute route) {
        return switch (route) {
            case DIRECT -> IntentGroup.KNOWLEDGE;
            case MARKET -> IntentGroup.MARKET;
            case FUNDAMENTALS -> IntentGroup.FUNDAMENTALS;
            case NEWS -> IntentGroup.NEWS;
            case DEEP -> IntentGroup.RESEARCH;
        };
    }

    private TimeSensitivity fallbackTimeSensitivity(String query) {
        String lower = query == null ? "" : query.toLowerCase(Locale.ROOT);
        if (containsAny(lower, "实时", "现在", "当前", "real-time", "right now")) {
            return TimeSensitivity.REAL_TIME;
        }
        if (containsAny(lower, "今天", "最新", "最近", "近期", "today", "latest", "recent")) {
            return TimeSensitivity.RECENT;
        }
        if (containsAny(lower, "历史", "过去", "去年", "historical", "last year")) {
            return TimeSensitivity.HISTORICAL;
        }
        return TimeSensitivity.UNSPECIFIED;
    }

    /** 把单调时钟差转换为非负毫秒数。 */
    private long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - startedNanos));
    }

    /**
     * 将能力层级映射为后端配置中的白名单模型名。
     */
    private String modelNameFor(ModelTier modelTier) {
        return switch (modelTier == null ? ModelTier.STANDARD : modelTier) {
            case FAST -> fastModel;
            case STANDARD -> standardModel;
            case STRONG -> strongModel;
        };
    }

    /** 生成不含原始 prompt 的可读计划摘要。 */
    private String buildPlanObservation(
            PlanRoute route,
            ModelTier modelTier,
            List<PlanAction> actions
    ) {
        String actionLabels = String.join(" -> ", actions.stream().map(PlanAction::label).toList());
        return "分层路线：" + route + "；模型层级：" + modelTier + "；计划步骤：" + actionLabels;
    }

    /** 把连续置信度收敛为有限诊断信号，便于 Trace/Eval 使用。 */
    private String confidenceSignal(double confidence) {
        if (confidence >= 0.8) {
            return "confidence-high";
        }
        if (confidence >= 0.5) {
            return "confidence-medium";
        }
        return "confidence-low";
    }

    /**
     * 判断文本中是否包含任意关键词。
     */
    private boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
