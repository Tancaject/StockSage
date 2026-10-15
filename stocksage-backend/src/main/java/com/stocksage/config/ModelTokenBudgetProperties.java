package com.stocksage.config;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** Verified provider windows reserve capacity; they are not locally estimated token counts. */
@ConfigurationProperties(prefix = "stocksage.research.token-budget")
public record ModelTokenBudgetProperties(Long maxTokens, String providerFingerprint, String version, String source,
                                         Instant validFrom, Instant validUntil, Map<String, Limit> models, Embedding embedding) {
    private static final String BASIS = "PROVIDER_WINDOW_RESERVATION";

    @ConstructorBinding
    public ModelTokenBudgetProperties {
        models = models == null ? Map.of() : models;
        if (maxTokens != null) {
            require(maxTokens > 0, "maxTokens must be positive");
            require(providerFingerprint != null && providerFingerprint.matches("[0-9a-f]{64}"),
                    "providerFingerprint must be a lowercase SHA-256 hex digest");
            require(version != null && !version.isBlank(), "version is required");
            require(source != null && !source.isBlank(), "source is required");
            require(validFrom != null && validUntil != null && validUntil.isAfter(validFrom), "invalid validity interval");
            require(!models.isEmpty(), "models must not be empty");
        }
        models.forEach((name, limit) -> require(name != null && !name.isBlank() && limit != null,
                "each model requires a name and limit"));
        models = Collections.unmodifiableMap(new TreeMap<>(models));
    }

    public ModelTokenBudgetProperties(Long maxTokens, String providerFingerprint, String version, String source,
            Instant validFrom, Instant validUntil, Map<String, Limit> models) {
        this(maxTokens, providerFingerprint, version, source, validFrom, validUntil, models, null);
    }

    /** Deployment revision is an operator declaration, not remotely verified model identity. */
    public record Embedding(String providerFingerprint, String model, long maxInputTokensPerText,
                            String deploymentRevision, String source, Instant validFrom, Instant validUntil) {
        public Embedding {
            require(providerFingerprint != null && providerFingerprint.matches("[0-9a-f]{64}"),
                    "embedding providerFingerprint must be a lowercase SHA-256 hex digest");
            require(model != null && !model.isBlank(), "embedding model is required");
            require(maxInputTokensPerText > 0 && maxInputTokensPerText <= Integer.MAX_VALUE,
                    "embedding input window must be in 1..Integer.MAX_VALUE");
            require(deploymentRevision != null && !deploymentRevision.isBlank(), "embedding deploymentRevision is required");
            require(source != null && !source.isBlank(), "embedding source is required");
            require(validFrom != null && validUntil != null && validUntil.isAfter(validFrom), "invalid embedding validity interval");
        }

        private Map<String, Object> snapshot() {
            return Map.of("status", "CONFIGURED", "basis", "OPERATOR_INPUT_WINDOW",
                    "providerFingerprint", providerFingerprint, "model", model,
                    "maxInputTokensPerText", maxInputTokensPerText, "deploymentRevision", deploymentRevision,
                    "source", source, "validFrom", validFrom.toString(), "validUntil", validUntil.toString());
        }
    }

    public record Limit(long maxInputTokens, long maxOutputTokens, long maxReasoningTokens) {
        public Limit {
            require(maxInputTokens > 0 && maxOutputTokens > 0 && maxReasoningTokens >= 0,
                    "input/output limits must be positive and reasoning limit nonnegative");
            sum(maxInputTokens, maxOutputTokens, maxReasoningTokens);
        }

        private Map<String, Object> snapshot() {
            return Map.of("maxInputTokens", maxInputTokens, "maxOutputTokens", maxOutputTokens,
                    "maxReasoningTokens", maxReasoningTokens);
        }
    }

    public static ModelTokenBudgetProperties disabled() {
        return new ModelTokenBudgetProperties(null, null, null, null, null, null, null);
    }

    public Map<String, Object> snapshot() {
        if (maxTokens == null) return Map.of("status", "DISABLED", "basis", BASIS);
        Map<String, Object> limits = new TreeMap<>();
        models.forEach((name, limit) -> limits.put(name, limit.snapshot()));
        return Map.of("status", "CONFIGURED", "basis", BASIS, "maxTokens", maxTokens,
                "providerFingerprint", providerFingerprint, "version", version, "source", source,
                "validFrom", validFrom.toString(), "validUntil", validUntil.toString(),
                "models", Collections.unmodifiableMap(limits),
                "embedding", embedding == null ? Map.of("status", "UNCONFIGURED") : embedding.snapshot());
    }

    public static long reserve(JsonNode frozen, JsonNode provider, String model, Instant now) {
        validateFrozenBudget(frozen, now);
        String fingerprint = text(frozen, "providerFingerprint");
        require(fingerprint.matches("[0-9a-f]{64}")
                && fingerprint.equals(ModelPricingProperties.providerFingerprint(provider)), "provider mismatch or unknown");
        require(model != null && !model.isBlank() && frozen.path("models").isObject()
                && frozen.path("models").path(model).isObject(), "model limit unavailable");
        JsonNode limit = frozen.path("models").path(model);
        long input = number(limit, "maxInputTokens");
        long output = number(limit, "maxOutputTokens");
        long reasoning = number(limit, "maxReasoningTokens");
        new Limit(input, output, reasoning);
        return sum(input, output, reasoning);
    }

    /**
     * 只放行能被 {@link #reserve} 的"输入+输出+思考"上限完全覆盖的聊天请求选项。
     *
     * <ul>
     *   <li>n 只能为 1；</li>
     *   <li>extraBody 只允许 enable_thinking 和 thinking_budget：思考预算不超过该模型的思考上限，
     *       max_tokens（百炼口径包含思考）不超过输出上限加思考上限；</li>
     *   <li>带工具定义时必须关闭内部自动执行，保证一条调用记录只对应一次模型请求。</li>
     * </ul>
     *
     * @param request ModelInvocationAdvisor 记录的请求选项
     */
    public static void requireBoundedChatRequest(JsonNode request, JsonNode frozen, String model) {
        JsonNode n = request.path("n");
        require(n.isMissingNode() || (n.isIntegralNumber() && n.canConvertToLong() && n.longValue() == 1),
                "unbounded request: n must be 1");
        // 未记录的标志按"存在"处理，旧记录不会被放行。
        if (request.path("extraBodyPresent").asBoolean(true)) {
            JsonNode limit = frozen.path("models").path(model == null ? "" : model);
            require(limit.isObject(), "model limit unavailable");
            long reasoning = number(limit, "maxReasoningTokens");
            JsonNode keys = request.path("extraBodyKeys");
            require(keys.isArray() && !keys.isEmpty(), "unbounded request: extraBody keys unknown");
            boolean budgetKey = false;
            for (JsonNode key : keys) {
                require(java.util.Set.of("enable_thinking", "thinking_budget").contains(key.asText()),
                        "unbounded request: extraBody key not allowed");
                budgetKey |= "thinking_budget".equals(key.asText());
            }
            if (budgetKey) {
                JsonNode budget = request.path("thinkingBudget");
                require(budget.isIntegralNumber() && budget.longValue() >= 0 && budget.longValue() <= reasoning,
                        "unbounded request: thinking budget exceeds model reasoning limit");
            }
            JsonNode maxTokens = request.path("maxTokens");
            require(maxTokens.isIntegralNumber() && maxTokens.longValue() > 0
                            && maxTokens.longValue() <= sum(0, number(limit, "maxOutputTokens"), reasoning),
                    "unbounded request: max tokens exceed model output and reasoning limits");
        }
        if (request.path("toolsPresent").asBoolean(true)) {
            JsonNode internal = request.path("internalToolExecutionEnabled");
            require(internal.isBoolean() && !internal.booleanValue(),
                    "unbounded request: tools require external execution");
        }
    }

    public static long reserveEmbedding(JsonNode frozen, JsonNode provider, String model, int inputCount,
            long contextTokens, Instant now) {
        validateFrozenBudget(frozen, now);
        JsonNode contract = frozen.path("embedding");
        require(contract.isObject() && "CONFIGURED".equals(contract.path("status").asText())
                && "OPERATOR_INPUT_WINDOW".equals(contract.path("basis").asText()), "embedding contract unavailable");
        String fingerprint = text(contract, "providerFingerprint");
        require(fingerprint.matches("[0-9a-f]{64}")
                && fingerprint.equals(ModelPricingProperties.providerFingerprint(provider)), "embedding provider mismatch or unknown");
        require(model != null && !model.isBlank() && model.equals(text(contract, "model")), "embedding model mismatch");
        long inputWindow = number(contract, "maxInputTokensPerText");
        require(inputWindow > 0 && inputWindow <= Integer.MAX_VALUE && contextTokens == inputWindow,
                "embedding num_ctx differs from the declared input window");
        require(inputCount > 0, "embedding inputCount must be positive");
        text(contract, "deploymentRevision");
        text(contract, "source");
        validateValidity(contract, now);
        try {
            return Math.multiplyExact((long) inputCount, inputWindow);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("stocksage.research.token-budget: embedding window overflows", overflow);
        }
    }

    private static void validateFrozenBudget(JsonNode frozen, Instant now) {
        require(frozen != null && frozen.isObject() && "CONFIGURED".equals(frozen.path("status").asText())
                && BASIS.equals(frozen.path("basis").asText()), "no configured frozen budget");
        require(number(frozen, "maxTokens") > 0, "invalid frozen maxTokens");
        text(frozen, "version");
        text(frozen, "source");
        validateValidity(frozen, now);
    }

    private static void validateValidity(JsonNode frozen, Instant now) {
        Instant from;
        Instant until;
        try {
            from = Instant.parse(text(frozen, "validFrom"));
            until = Instant.parse(text(frozen, "validUntil"));
        } catch (DateTimeParseException invalid) {
            throw new IllegalArgumentException("stocksage.research.token-budget: invalid frozen validity", invalid);
        }
        require(until.isAfter(from) && now != null && !now.isBefore(from) && now.isBefore(until),
                "budget outside validity interval");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        require(value.isTextual() && !value.textValue().isBlank(), "missing or invalid " + field);
        return value.textValue();
    }

    private static long number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        require(value.isIntegralNumber() && value.canConvertToLong(), "missing or invalid " + field);
        return value.longValue();
    }

    private static long sum(long input, long output, long reasoning) {
        try {
            return Math.addExact(Math.addExact(input, output), reasoning);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("stocksage.research.token-budget: model window overflows", overflow);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException("stocksage.research.token-budget: " + message);
    }
}
