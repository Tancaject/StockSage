package com.stocksage.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class IntentRecognitionService {

    private final ChatClient intentChatClient;
    private final ObjectMapper objectMapper;
    private final AsyncTaskExecutor taskExecutor;
    private final long timeoutMs;

    @Autowired
    public IntentRecognitionService(@Qualifier("intentRecognitionChatClient") ChatClient intentChatClient,
                                    ObjectMapper objectMapper,
                                    @Qualifier("agentTaskExecutor") AsyncTaskExecutor taskExecutor,
                                    @Value("${stocksage.agent.intent.timeout-ms:5000}") long timeoutMs) {
        this.intentChatClient = intentChatClient;
        this.objectMapper = objectMapper;
        this.taskExecutor = taskExecutor;
        this.timeoutMs = Math.max(100L, timeoutMs);
    }

    IntentRecognitionService(ChatClient intentChatClient, ObjectMapper objectMapper) {
        this(intentChatClient, objectMapper, new org.springframework.core.task.SimpleAsyncTaskExecutor(), 5000L);
    }

    public IntentDecision recognize(IntentRecognitionRequest request) {
        try {
            String payload = objectMapper.writeValueAsString(request);
            String content = CompletableFuture.supplyAsync(() -> intentChatClient.prompt()
                            .user(payload)
                            .call()
                            .content(), taskExecutor)
                    .get(timeoutMs, TimeUnit.MILLISECONDS);
            return parseAndValidate(content);
        } catch (Exception error) {
            log.warn("Intent recognition failed. errorType={}", error.getClass().getSimpleName());
            return null;
        }
    }

    IntentDecision parseAndValidate(String content) throws Exception {
        JsonNode root = objectMapper.readTree(extractJson(content));
        IntentType primary = parseIntent(root.path("primaryIntent").asText(""));
        PlanRoute route = parseRoute(root.path("suggestedRoute").asText(""));
        if (primary == IntentType.UNKNOWN || route == null) {
            throw new IllegalArgumentException("Invalid intent decision");
        }

        List<IntentType> secondary = new ArrayList<>();
        if (root.path("secondaryIntents").isArray()) {
            for (JsonNode node : root.path("secondaryIntents")) {
                IntentType value = parseIntent(node.asText(""));
                if (value != IntentType.UNKNOWN && value != primary && !secondary.contains(value)) {
                    secondary.add(value);
                }
            }
        }
        Map<String, String> entities = new LinkedHashMap<>();
        JsonNode entityNode = root.path("entities");
        if (entityNode.isObject()) {
            entityNode.fields().forEachRemaining(entry -> {
                String value = entry.getValue().asText("").trim();
                if (entry.getKey().matches("[A-Za-z][A-Za-z0-9_]{0,31}")
                        && value.matches("[A-Za-z0-9.\\-]{1,16}")) {
                    entities.put(entry.getKey(), value);
                }
            });
        }
        boolean deep = root.path("needsDeepResearch").asBoolean(false);
        if ((deep || primary == IntentType.DEEP_RESEARCH) && route != PlanRoute.DEEP) {
            throw new IllegalArgumentException("Deep intent must use DEEP route");
        }
        return new IntentDecision(
                primary,
                secondary,
                entities,
                root.path("timeRange").asText(""),
                root.path("needsFreshData").asBoolean(false),
                root.path("needsRag").asBoolean(false),
                deep,
                route,
                root.path("rationale").asText(""),
                root.path("confidence").asDouble(0.0)
        );
    }

    private IntentType parseIntent(String value) {
        try {
            return IntentType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return IntentType.UNKNOWN;
        }
    }

    private PlanRoute parseRoute(String value) {
        try {
            return PlanRoute.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return null;
        }
    }

    private String extractJson(String content) {
        String text = content == null ? "" : content.trim()
                .replaceAll("(?is)^```json\\s*", "")
                .replaceAll("(?is)^```\\s*", "")
                .replaceAll("(?is)\\s*```$", "");
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return start >= 0 && end > start ? text.substring(start, end + 1) : text;
    }
}
