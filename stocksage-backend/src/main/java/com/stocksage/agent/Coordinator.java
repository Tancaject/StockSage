package com.stocksage.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.service.TickerResolutionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 将每次用户请求路由到合适的研究路径。
 *
 * <p>Coordinator 有意把“判断要做什么”和“回答用户”拆开：先用轻量模型调用尝试路由，
 * 再归一化为 {@link ExecutionPlan}。当路由失败或返回的 JSON 不合法时，确定性的关键词规则会兜底，
 * 保证对话流程仍然可用。</p>
 */
@Slf4j
@Service
public class Coordinator {

    private final ChatClient routingChatClient;
    private final ChatClient responseChatClient;
    private final ChatClient preparedAnswerChatClient;
    private final ObjectMapper objectMapper;
    private final TickerResolutionService tickerResolutionService;

    @Value("${stocksage.chat.model-routing.fast-model:qwen3.6-flash}")
    private String fastModel;

    @Value("${stocksage.chat.model-routing.standard-model:${STOCKSAGE_CHAT_MODEL:qwen3.6-plus}}")
    private String standardModel;

    @Value("${stocksage.chat.model-routing.strong-model:qwen3.6-max-preview}")
    private String strongModel;

    @Value("${stocksage.chat.model-routing.vision-model:qwen3-vl-plus}")
    private String visionModel;

    @Value("${stocksage.chat.model-routing.temperature:${STOCKSAGE_CHAT_TEMPERATURE:0.7}}")
    private double modelRoutingTemperature;

    @Value("${stocksage.chat.model-routing.max-output-tokens:${STOCKSAGE_CHAT_MAX_OUTPUT_TOKENS:4096}}")
    private int modelRoutingMaxOutputTokens;

    /**
     * 注入路由、普通回答和预取上下文回答三个不同职责的 ChatClient。
     */
    public Coordinator(@Qualifier("coordinatorChatClient") ChatClient routingChatClient,
                       @Qualifier("chatClient") ChatClient responseChatClient,
                       @Qualifier("preparedAnswerChatClient") ChatClient preparedAnswerChatClient,
                       ObjectMapper objectMapper,
                       TickerResolutionService tickerResolutionService) {
        this.routingChatClient = routingChatClient;
        this.responseChatClient = responseChatClient;
        this.preparedAnswerChatClient = preparedAnswerChatClient;
        this.objectMapper = objectMapper;
        this.tickerResolutionService = tickerResolutionService;
    }

    /**
     * 向路由模型请求执行计划；如果模型、解析或网络任一环节失败，则回退到本地路由规则。
     */
    public ExecutionPlan plan(String userQuery, int ragHitCount) {
        return plan(userQuery, ragHitCount, "");
    }

    /**
     * Lets one small routing model select only the execution layer. The model
     * never chooses tools, actions, agents, or concrete model names.
     */
    public ExecutionPlan plan(String userQuery, int ragHitCount, String ragSummary) {
        long startedNanos = System.nanoTime();
        try {
            String content = routingChatClient.prompt()
                    .user(buildRoutingPrompt(userQuery, ragHitCount, ragSummary))
                    .call()
                    .content();
            RouteDecision decision = parseRouteDecision(content);
            return buildPlan(decision, userQuery, ragHitCount, elapsedMillis(startedNanos));
        } catch (Exception e) {
            log.warn("Coordinator LLM routing failed, using deterministic fallback. errorType={}",
                    e.getClass().getSimpleName());
            return fallbackPlan(userQuery, ragHitCount, "ROUTING_LLM_FAILED", startedNanos);
        }
    }

    /**
     * 只使用本地规则生成计划，供回归测试和模型不可用时复用。
     */
    public ExecutionPlan planDeterministically(String userQuery, int ragHitCount) {
        return fallbackPlan(userQuery, ragHitCount, "EXPLICIT_DETERMINISTIC", System.nanoTime());
    }

    /**
     * 使用默认对话客户端流式生成回答。
     */
    public Flux<String> streamAnswer(List<Message> promptMessages) {
        return streamAnswer(promptMessages, false, ModelTier.STANDARD);
    }

    /**
     * 流式输出最终回答。深度研究使用仅含预置上下文的客户端，
     * 确保证据快照固定后最终模型不能再调用工具。
     */
    public Flux<String> streamAnswer(List<Message> promptMessages, boolean usePreparedContextOnly) {
        return streamAnswer(promptMessages, usePreparedContextOnly, ModelTier.STANDARD);
    }

    /**
     * 按 Coordinator 选出的能力层级流式输出最终回答。
     *
     * <p>层级只在后端映射为白名单模型名；即使路由模型建议了层级，也不能直接指定任意模型。</p>
     */
    public Flux<String> streamAnswer(List<Message> promptMessages, boolean usePreparedContextOnly, ModelTier modelTier) {
        return streamAnswer(promptMessages, usePreparedContextOnly, modelTier, false);
    }

    /**
     * 按能力层级和输入模态流式输出最终回答。
     */
    public Flux<String> streamAnswer(List<Message> promptMessages, boolean usePreparedContextOnly, ModelTier modelTier, boolean useVisionModel) {
        SelectedModel selectedModel = selectFinalAnswerModel(modelTier, usePreparedContextOnly, useVisionModel);
        ModelTier effectiveTier = selectedModel.tier();
        ChatClient client = usePreparedContextOnly ? preparedAnswerChatClient : responseChatClient;
        String modelName = selectedModel.modelName();
        if (usePreparedContextOnly) {
            log.info("Coordinator streaming final answer from prepared context only; tools disabled for this response.");
        }
        log.info("Coordinator selected final-answer model tier={}, model={}, preparedContextOnly={}",
                effectiveTier, modelName, usePreparedContextOnly);

        ChatClient.ChatClientRequestSpec requestSpec = client.prompt()
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
     * 解析最终回答会实际使用的模型层级和模型名，供 SSE 元数据与持久化复用。
     */
    public SelectedModel selectFinalAnswerModel(ModelTier modelTier, boolean usePreparedContextOnly) {
        return selectFinalAnswerModel(modelTier, usePreparedContextOnly, false);
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

    /** Parse the only three fields the routing model is allowed to produce. */
    RouteDecision parseRouteDecision(String content) throws Exception {
        String json = extractJson(content);
        JsonNode root = objectMapper.readTree(json);
        String rawRoute = root.path("route").asText("DIRECT");
        PlanRoute route = PlanRoute.normalize(rawRoute);
        String intentSummary = root.path("intent").asText("");
        String rationale = root.path("rationale").asText("");
        double confidence = root.path("confidence").asDouble(0.0);
        return new RouteDecision(intentSummary, rawRoute, route, rationale, confidence);
    }

    /** Convert a validated route into the server-owned fixed execution plan. */
    private ExecutionPlan buildPlan(
            RouteDecision decision,
            String userQuery,
            int ragHitCount,
            long durationMs
    ) {
        PlanRoute route = decision.route();
        String taskType = taskType(route);
        List<PlanAction> actions = defaultActions(route);
        ModelTier modelTier = selectDefaultModelTier(route, userQuery, ragHitCount);
        String thought = "Coordinator 路由：理解为「" + decision.intentSummary()
                + "」，选择「" + taskType + "」；" + decision.rationale();
        String observation = "分层路线：" + route + "；模型层级：" + modelTier + "；计划步骤："
                + String.join(" -> ", actions.stream().map(PlanAction::label).toList());
        RoutingDecisionMetadata routingDecision = new RoutingDecisionMetadata(
                RoutingDecisionSource.ROUTING_LLM,
                decision.rawRoute(),
                route,
                decision.intentSummary(),
                decision.rationale(),
                decision.confidence(),
                List.of(confidenceSignal(decision.confidence())),
                ragHitCount,
                "",
                durationMs
        );
        return new ExecutionPlan(route, taskType, thought, actions, observation, modelTier, routingDecision);
    }

    /**
     * 本地路由选择，同时用于回归测试，也用于路由模型不可用时的运行时兜底。
     */
    private ExecutionPlan fallbackPlan(String userQuery,
                                       int ragHitCount,
                                       String fallbackReason,
                                       long startedNanos) {
        String query = userQuery == null ? "" : userQuery.trim();
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
            route = PlanRoute.NEWS;
        }

        List<PlanAction> actions = defaultActions(route);
        String taskType = taskType(route);
        String thought = "Coordinator 路由：识别为「" + taskType + "」；使用规则兜底完成分流。";
        ModelTier modelTier = selectDefaultModelTier(route, query, ragHitCount);
        String observation = "分层路线：" + route + "；模型层级：" + modelTier + "；计划步骤："
                + String.join(" -> ", actions.stream().map(PlanAction::label).toList());
        List<String> matchedSignals = new ArrayList<>();
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
                elapsedMillis(startedNanos)
        );
        return new ExecutionPlan(route, taskType, thought, actions, observation, modelTier, routingDecision);
    }

    private long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - startedNanos));
    }

    /**
     * 给每类路由设置默认能力层级；直接问答再按问题复杂度细分。
     */
    private ModelTier selectDefaultModelTier(PlanRoute route, String userQuery, int ragHitCount) {
        return switch (route) {
            case DEEP -> ModelTier.STRONG;
            case MARKET, FUNDAMENTALS, NEWS -> ModelTier.STANDARD;
            case DIRECT -> looksComplexDirectQuery(userQuery, ragHitCount) ? ModelTier.STANDARD : ModelTier.FAST;
        };
    }

    /**
     * 概念类问题可以走 FAST；长上下文、对比、估值和证据型问题至少走 STANDARD。
     */
    private boolean looksComplexDirectQuery(String userQuery, int ragHitCount) {
        String query = userQuery == null ? "" : userQuery.trim();
        String lower = query.toLowerCase(Locale.ROOT);
        return query.length() > 120
                || ragHitCount >= 3
                || containsAny(lower,
                "compare", "detailed", "analysis", "risk", "valuation",
                "对比", "比较", "详细", "深入", "分析", "风险", "估值", "财报", "年报", "季报");
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

    /**
     * 每条路由的默认步骤。名称刻意与 ChatService 的预取分支和前端追踪标签保持一致。
     */
    private List<PlanAction> defaultActions(PlanRoute route) {
        Set<PlanAction> actions = new LinkedHashSet<>();
        switch (route) {
            case MARKET -> {
                actions.add(PlanAction.MARKET_AGENT);
                actions.add(PlanAction.SEARCH_STOCKS);
                actions.add(PlanAction.GET_STOCK_KLINE);
                actions.add(PlanAction.GET_FINANCIAL_METRICS);
                actions.add(PlanAction.GET_TECHNICAL_INDICATORS);
            }
            case FUNDAMENTALS -> {
                actions.add(PlanAction.FUNDAMENTALS_AGENT);
                actions.add(PlanAction.SEARCH_STOCKS);
                actions.add(PlanAction.GET_FINANCIAL_REPORTS);
                actions.add(PlanAction.SEARCH_COMPANY_REPORTS);
                actions.add(PlanAction.KNOWLEDGE_RETRIEVAL);
            }
            case DEEP -> {
                actions.add(PlanAction.FUNDAMENTALS_AGENT);
                actions.add(PlanAction.MARKET_AGENT);
                actions.add(PlanAction.NEWS_AGENT);
                actions.add(PlanAction.BULL_RESEARCHER);
                actions.add(PlanAction.BEAR_RESEARCHER);
                actions.add(PlanAction.RESEARCH_MANAGER);
            }
            case NEWS -> {
                actions.add(PlanAction.NEWS_AGENT);
                actions.add(PlanAction.SEARCH_STOCKS);
                actions.add(PlanAction.SEARCH_NEWS);
                actions.add(PlanAction.WEB_SEARCH);
                actions.add(PlanAction.GET_MARKET_OVERVIEW);
            }
            default -> actions.add(PlanAction.KNOWLEDGE_RETRIEVAL);
        }
        actions.add(PlanAction.FINAL_ANSWER);
        return new ArrayList<>(actions);
    }

    private String taskType(PlanRoute route) {
        return switch (route) {
            case MARKET -> "单点市场查询";
            case FUNDAMENTALS -> "单点财报查询";
            case DEEP -> "深度投资分析";
            case NEWS -> "新闻与事件分析";
            default -> "知识类问答";
        };
    }

    private String confidenceSignal(double confidence) {
        if (confidence >= 0.8) {
            return "confidence-high";
        }
        if (confidence >= 0.5) {
            return "confidence-medium";
        }
        return "confidence-low";
    }

    private String boundedRagSummary(String value) {
        String summary = value == null ? "" : value.trim();
        return summary.isBlank() ? "无相关检索结果" : summary.substring(0, Math.min(1600, summary.length()));
    }

    String buildRoutingPrompt(String userQuery, int ragHitCount, String ragSummary) {
        return """
                用户问题：%s
                知识库命中数量：%d
                知识库检索摘要：
                %s
                """.formatted(userQuery == null ? "" : userQuery, Math.max(0, ragHitCount),
                boundedRagSummary(ragSummary));
    }

    /**
     * 从模型输出中提取 JSON 对象。
     */
    private String extractJson(String content) {
        if (content == null) {
            return "{}";
        }
        String trimmed = content.trim()
                .replaceAll("(?is)^```json\\s*", "")
                .replaceAll("(?is)^```\\s*", "")
                .replaceAll("(?is)\\s*```$", "")
                .trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
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
