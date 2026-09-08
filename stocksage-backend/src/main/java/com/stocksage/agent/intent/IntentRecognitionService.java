package com.stocksage.agent.intent;

import com.stocksage.util.JsonText;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.agent.PlanRoute;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 统一执行 LLM、Embedding、Pattern 与 n-gram 意图识别并融合成直接 targetRoute。
 */
@Slf4j
@Service
public class IntentRecognitionService {

    private final ChatClient routingChatClient;
    private final ObjectMapper objectMapper;
    private final EmbeddingIntentMatcher embeddingMatcher;
    private final PatternIntentMatcher patternMatcher;
    private final NgramIntentMatcher ngramMatcher;
    private final IntentFusionPolicy fusionPolicy;

    @Autowired
    public IntentRecognitionService(
            @Qualifier("coordinatorChatClient") ChatClient routingChatClient,
            ObjectMapper objectMapper,
            EmbeddingIntentMatcher embeddingMatcher
    ) {
        this(routingChatClient, objectMapper, embeddingMatcher,
                new PatternIntentMatcher(), new NgramIntentMatcher(), new IntentFusionPolicy());
    }

    /** 供 Coordinator 兼容构造器和纯单元测试使用，不触发 embedding。 */
    public IntentRecognitionService(ChatClient routingChatClient, ObjectMapper objectMapper) {
        this(routingChatClient, objectMapper, null,
                new PatternIntentMatcher(), new NgramIntentMatcher(), new IntentFusionPolicy());
    }

    IntentRecognitionService(
            ChatClient routingChatClient,
            ObjectMapper objectMapper,
            EmbeddingIntentMatcher embeddingMatcher,
            PatternIntentMatcher patternMatcher,
            NgramIntentMatcher ngramMatcher,
            IntentFusionPolicy fusionPolicy
    ) {
        this.routingChatClient = routingChatClient;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.embeddingMatcher = embeddingMatcher;
        this.patternMatcher = patternMatcher;
        this.ngramMatcher = ngramMatcher;
        this.fusionPolicy = fusionPolicy;
    }

    /** 识别并融合本轮意图；模型失败不阻断本地信号。 */
    public IntentRecognitionResult recognize(IntentRecognitionRequest request) {
        long startedNanos = System.nanoTime();
        IntentRecognitionRequest safeRequest = request == null
                ? new IntentRecognitionRequest("", List.of(), 0, false, List.of())
                : request;
        EmbeddingIntentMatcher.MatchHandle embeddingHandle = embeddingMatcher == null
                ? null
                : embeddingMatcher.beginMatch(safeRequest);

        List<IntentSignal> signals = new ArrayList<>();
        String rawRoute = "";
        boolean rawRouteValid = false;
        String rationale = "";
        String degradationReason = "";
        try {
            ParsedLlm parsed = parseLlm(callLlm(safeRequest), safeRequest);
            rawRoute = parsed.rawRoute();
            rawRouteValid = parsed.decisionValid();
            rationale = parsed.rationale();
            parsed.signal().ifPresent(signals::add);
            if (!parsed.routeTokenValid()) {
                degradationReason = "INTENT_LLM_INVALID_ROUTE";
            } else if (!parsed.decisionValid()) {
                degradationReason = "INTENT_LLM_INCONSISTENT_TUPLE";
            }
        } catch (Exception e) {
            degradationReason = "INTENT_LLM_FAILED";
            log.warn("Intent LLM recognition failed; local signals remain available. errorType={}",
                    e.getClass().getSimpleName());
        }

        if (embeddingMatcher != null) {
            embeddingMatcher.finishMatch(embeddingHandle).ifPresent(signals::add);
        }
        patternMatcher.match(safeRequest).ifPresent(signals::add);
        ngramMatcher.match(safeRequest).ifPresent(signals::add);

        IntentDecision decision = fusionPolicy.fuse(safeRequest, signals);
        if (rationale.isBlank()) {
            rationale = signals.stream()
                    .filter(signal -> signal.targetRoute() == decision.targetRoute())
                    .map(IntentSignal::rationale)
                    .filter(value -> !value.isBlank())
                    .findFirst()
                    .orElse("多信号融合后选择目标路由。");
        }
        return new IntentRecognitionResult(
                decision, rawRoute, rawRouteValid, rationale, signals, degradationReason,
                TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - startedNanos)));
    }

    String buildPrompt(IntentRecognitionRequest request) {
        String history = request.recentTurns().isEmpty()
                ? "（无）"
                : String.join("\n", request.recentTurns());
        return """
                最近对话（仅用于指代和省略消歧）：
                %s

                当前用户问题：%s
                是否包含图片：%s
                当前消息 ticker 候选：%s
                知识库命中数量：%d
                """.formatted(
                history,
                request.currentQuestion(),
                request.hasImages(),
                request.tickerCandidates().isEmpty() ? "（无）" : String.join(",", request.tickerCandidates()),
                request.ragHitCount()
        );
    }

    private String callLlm(IntentRecognitionRequest request) {
        if (routingChatClient == null) {
            throw new IllegalStateException("routing client unavailable");
        }
        return routingChatClient.prompt().user(buildPrompt(request)).call().content();
    }

    private ParsedLlm parseLlm(String content, IntentRecognitionRequest request) throws Exception {
        JsonNode root = objectMapper.readTree(JsonText.extractObject(content));
        String rawRoute = text(root, "targetRoute", text(root, "route", ""));
        Optional<PlanRoute> parsedRoute = PlanRoute.parse(rawRoute);
        String rationale = text(root, "rationale", "");
        if (parsedRoute.isEmpty()) {
            return new ParsedLlm(Optional.empty(), rawRoute, false, false, rationale);
        }

        PlanRoute route = parsedRoute.get();
        FineIntent fineIntent = enumValue(FineIntent.class, text(root, "fineIntent", ""))
                .orElseGet(() -> fineIntent(route));
        IntentGroup group = enumValue(IntentGroup.class, text(root, "intentGroup", ""))
                .orElseGet(() -> intentGroup(route));
        TimeSensitivity time = enumValue(TimeSensitivity.class, text(root, "timeSensitivity", ""))
                .orElse(TimeSensitivity.UNSPECIFIED);
        AnalysisDepth depth = enumValue(AnalysisDepth.class, text(root, "analysisDepth", ""))
                .orElse(route == PlanRoute.DEEP ? AnalysisDepth.DEEP : AnalysisDepth.STANDARD);
        if (!consistentTuple(fineIntent, group, route)) {
            return new ParsedLlm(Optional.empty(), rawRoute, true, false, rationale);
        }
        double confidence = root.path("confidence").asDouble(0.0);
        Map<String, String> entities = parseEntities(root.path("entities"));
        if (entities.isEmpty()) {
            entities = IntentBounds.entitiesFromTickers(request.tickerCandidates());
        }
        String resolvedQuery = text(root, "resolvedQuery", request.currentQuestion());
        List<String> reasonCodes = parseStrings(root.path("reasonCodes"));
        if (reasonCodes.isEmpty()) {
            reasonCodes = List.of("LLM_TARGET_ROUTE");
        }
        IntentSignal signal = new IntentSignal(
                fineIntent, group, rawRoute, route, IntentSignalSource.LLM, confidence,
                time, depth, Map.of(IntentSignalSource.LLM, confidence), entities,
                resolvedQuery, rationale, reasonCodes
        );
        return new ParsedLlm(Optional.of(signal), rawRoute, true, true, rationale);
    }

    private Map<String, String> parseEntities(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            String text;
            if (value.isArray()) {
                text = String.join(",", parseStrings(value));
            } else {
                text = value.asText("");
            }
            if (!text.isBlank()) {
                values.put(entry.getKey(), text);
            }
        });
        return IntentBounds.entities(values);
    }

    private List<String> parseStrings(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(value -> {
            String text = value.asText("");
            if (!text.isBlank()) {
                values.add(text);
            }
        });
        return values;
    }

    private <E extends Enum<E>> Optional<E> enumValue(Class<E> type, String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private FineIntent fineIntent(PlanRoute route) {
        return switch (route) {
            case DIRECT -> FineIntent.KNOWLEDGE_EXPLANATION;
            case MARKET -> FineIntent.MARKET_DATA;
            case FUNDAMENTALS -> FineIntent.FUNDAMENTALS;
            case NEWS -> FineIntent.NEWS_EVENT;
            case DEEP -> FineIntent.DEEP_RESEARCH;
        };
    }

    private IntentGroup intentGroup(PlanRoute route) {
        return switch (route) {
            case DIRECT -> IntentGroup.KNOWLEDGE;
            case MARKET -> IntentGroup.MARKET;
            case FUNDAMENTALS -> IntentGroup.FUNDAMENTALS;
            case NEWS -> IntentGroup.NEWS;
            case DEEP -> IntentGroup.RESEARCH;
        };
    }

    /** 只验证 LLM 自己输出的三个分类字段一致，不替它重新选择 targetRoute。 */
    private boolean consistentTuple(FineIntent fineIntent, IntentGroup group, PlanRoute route) {
        return switch (fineIntent) {
            case KNOWLEDGE_EXPLANATION -> group == IntentGroup.KNOWLEDGE && route == PlanRoute.DIRECT;
            case MARKET_DATA, TECHNICAL_ANALYSIS -> group == IntentGroup.MARKET && route == PlanRoute.MARKET;
            case FUNDAMENTALS -> group == IntentGroup.FUNDAMENTALS && route == PlanRoute.FUNDAMENTALS;
            case NEWS_EVENT -> group == IntentGroup.NEWS && route == PlanRoute.NEWS;
            case COMPARISON, PORTFOLIO_DIAGNOSIS, DEEP_RESEARCH ->
                    group == IntentGroup.RESEARCH && route == PlanRoute.DEEP;
            case UNKNOWN -> group == IntentGroup.UNKNOWN && route == PlanRoute.DIRECT;
        };
    }

    private String text(JsonNode root, String field, String fallback) {
        return root.path(field).asText(fallback == null ? "" : fallback);
    }

    private record ParsedLlm(
            Optional<IntentSignal> signal,
            String rawRoute,
            boolean routeTokenValid,
            boolean decisionValid,
            String rationale
    ) {
    }
}
