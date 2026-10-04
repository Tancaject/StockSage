package com.stocksage.evolution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.stocksage.agent.AgentRuntimeConfiguration;
import com.stocksage.conversation.ChatPromptAssembler;
import org.springframework.ai.document.Document;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Serving and replay fingerprint the same constructed model options and final-answer contract. */
public final class FundamentalsRuntimeIdentity {
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private FundamentalsRuntimeIdentity() {}

    public static String json(Object value) {
        try { return MAPPER.writeValueAsString(value); }
        catch (java.io.IOException invalid) { throw new IllegalStateException("无法计算基本面运行身份。", invalid); }
    }

    public static String hash(Object value) { return AgentPolicyBundle.sha256(json(value)); }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> analystDefaults(AgentRuntimeConfiguration runtime) {
        Object value = runtime.snapshot().get("fundamentals");
        if (!(value instanceof Map<?, ?> defaults) || !(defaults.get("model") instanceof String model) || model.isBlank()) {
            throw new IllegalStateException("基本面客户端实际模型配置不可用，请检查客户端初始化。");
        }
        return (Map<String, Object>) defaults;
    }

    public static Map<String, Object> modelConfiguration(AgentRuntimeConfiguration runtime, List<Map<String, Object>> finalCalls,
                                                        long timeoutSeconds, int promptMaxChars) {
        var analysis = new LinkedHashMap<>(analystDefaults(runtime));
        analysis.remove("systemPromptHash"); // The method bundle has its own content/contract hash.
        return Map.of("provider", runtime.chatProviderSnapshot(), "analysis", Map.copyOf(analysis),
                "finalAnswer", finalCalls, "promptMaxChars", promptMaxChars, "analysisTimeoutSeconds", timeoutSeconds);
    }

    public static Map<String, String> conditions(Map<String, Object> modelConfig, String buildHash, String memoryHash) {
        return Map.of("modelConfigSha256", hash(modelConfig), "runtimeBuildSha256", buildHash,
                "fixedFinalPromptSha256", hash(ChatPromptAssembler.ordinaryFixedRules()), "memorySnapshotSha256", memoryHash);
    }

    public static String memoryHash(String researchMemory, String userMemory, List<Document> documents) {
        return hash(Map.of("researchMemory", researchMemory == null ? "" : researchMemory,
                "userMemory", userMemory == null ? "" : userMemory,
                "rag", documents.stream().map(doc -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", doc.getId()); row.put("text", doc.getText()); row.put("metadata", doc.getMetadata());
                    return row;
                }).toList()));
    }

    /** Constructed in ChatService, never bound from a client request body. */
    public record Request(String originalQuery, Map<String, Object> finalInvocation, String memorySnapshotSha256,
                          int promptMaxChars, boolean eligibleRouteAndModel) {
        public Request {
            originalQuery = originalQuery == null ? "" : originalQuery;
            finalInvocation = Map.copyOf(finalInvocation);
        }
    }
}
