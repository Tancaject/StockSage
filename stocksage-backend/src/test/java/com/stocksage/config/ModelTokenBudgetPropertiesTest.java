package com.stocksage.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class ModelTokenBudgetPropertiesTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Instant from = Instant.parse("2026-01-01T00:00:00Z");
    private final Instant until = Instant.parse("2027-01-01T00:00:00Z");
    private final JsonNode provider = mapper.valueToTree(Map.of("scope", "API_CONSTRUCTION", "baseUrl", "https://provider.test"));

    private ModelTokenBudgetProperties configured(Map<String, ModelTokenBudgetProperties.Limit> models) {
        return new ModelTokenBudgetProperties(10_000L, ModelPricingProperties.providerFingerprint(provider),
                "verified-window-v1", "provider model card", from, until, models);
    }

    @Test
    void bindsIndependentEmbeddingContractAndReservesEveryInputWithoutChatOutputAllowance() {
        JsonNode embeddingProvider = mapper.valueToTree(Map.of("scope", "API_CONSTRUCTION", "baseUrl", "http://ollama.test"));
        Map<String, Object> values = new HashMap<>();
        String prefix = "stocksage.research.token-budget.";
        values.put(prefix + "max-tokens", "10000");
        values.put(prefix + "provider-fingerprint", ModelPricingProperties.providerFingerprint(provider));
        values.put(prefix + "version", "v1");
        values.put(prefix + "source", "operator-contract");
        values.put(prefix + "valid-from", from.toString());
        values.put(prefix + "valid-until", until.toString());
        values.put(prefix + "models[chat].max-input-tokens", "100");
        values.put(prefix + "models[chat].max-output-tokens", "200");
        values.put(prefix + "models[chat].max-reasoning-tokens", "300");
        values.put(prefix + "embedding.provider-fingerprint", ModelPricingProperties.providerFingerprint(embeddingProvider));
        values.put(prefix + "embedding.model", "embedding-model");
        values.put(prefix + "embedding.max-input-tokens-per-text", "512");
        values.put(prefix + "embedding.deployment-revision", "operator-deployment-1");
        values.put(prefix + "embedding.source", "operator-context-contract");
        values.put(prefix + "embedding.valid-from", from.toString());
        values.put(prefix + "embedding.valid-until", until.toString());
        var properties = new Binder(new MapConfigurationPropertySource(values))
                .bind("stocksage.research.token-budget", Bindable.of(ModelTokenBudgetProperties.class)).get();
        JsonNode frozen = mapper.valueToTree(properties.snapshot());
        assertThat(properties.embedding().maxInputTokensPerText()).isEqualTo(512L);
        assertThat(frozen.path("embedding").path("basis").asText()).isEqualTo("OPERATOR_INPUT_WINDOW");
        assertThat(frozen.path("embedding").path("deploymentRevision").asText()).isEqualTo("operator-deployment-1");
        assertThat(ModelTokenBudgetProperties.reserveEmbedding(frozen, embeddingProvider, "embedding-model", 3, 512, from))
                .isEqualTo(1536);
        assertThat(ModelTokenBudgetProperties.reserveEmbedding(frozen, embeddingProvider, "embedding-model", 1, 512, until.minusNanos(1)))
                .isEqualTo(512);
        assertThatThrownBy(() -> ModelTokenBudgetProperties.reserveEmbedding(frozen, provider, "embedding-model", 1, 512, from))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ((Map<?, ?>) properties.snapshot().get("embedding")).clear())
                .isInstanceOf(UnsupportedOperationException.class);
        var disabled = new ModelTokenBudgetProperties(null, null, null, null, null, null, null, properties.embedding());
        assertThat(disabled.snapshot()).containsEntry("status", "DISABLED").doesNotContainKey("embedding");
        assertThat(configured(Map.of("model", new ModelTokenBudgetProperties.Limit(1, 2, 0))).snapshot())
                .containsEntry("embedding", Map.of("status", "UNCONFIGURED"));
    }

    @Test
    void malformedEmbeddingWindowsAndExpiredFrozenContractsCannotReserve() {
        String fingerprint = ModelPricingProperties.providerFingerprint(provider);
        var embedding = new ModelTokenBudgetProperties.Embedding(fingerprint, "embed", 512, "deployment-v1", "operator", from, until);
        var config = new ModelTokenBudgetProperties(10_000L, fingerprint, "v1", "source", from, until,
                Map.of("chat", new ModelTokenBudgetProperties.Limit(1, 2, 0)), embedding);
        ObjectNode frozen = mapper.valueToTree(config.snapshot());
        assertThatThrownBy(() -> ModelTokenBudgetProperties.reserveEmbedding(frozen, provider, "embed", 0, 512, from))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModelTokenBudgetProperties.reserveEmbedding(frozen, provider, "embed", 1, 511, from))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModelTokenBudgetProperties.reserveEmbedding(frozen, provider, "different", 1, 512, from))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModelTokenBudgetProperties.reserveEmbedding(frozen, null, "embed", 1, 512, from))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModelTokenBudgetProperties.reserveEmbedding(frozen, provider, "embed", 1, 512, until))
                .isInstanceOf(IllegalArgumentException.class);
        for (String field : java.util.List.of("status", "basis", "maxTokens", "version", "source", "validFrom", "validUntil")) {
            ObjectNode invalid = frozen.deepCopy();
            invalid.remove(field);
            assertThatThrownBy(() -> ModelTokenBudgetProperties.reserveEmbedding(invalid, provider, "embed", 1, 512, from))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (String field : java.util.List.of("status", "basis", "providerFingerprint", "model", "maxInputTokensPerText",
                "deploymentRevision", "source", "validFrom", "validUntil")) {
            ObjectNode invalid = frozen.deepCopy();
            ((ObjectNode) invalid.path("embedding")).remove(field);
            assertThatThrownBy(() -> ModelTokenBudgetProperties.reserveEmbedding(invalid, provider, "embed", 1, 512, from))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        ObjectNode expired = frozen.deepCopy();
        ((ObjectNode) expired.path("embedding")).put("validUntil", from.plusSeconds(1).toString());
        assertThatThrownBy(() -> ModelTokenBudgetProperties.reserveEmbedding(expired, provider, "embed", 1, 512, from.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        for (long badWindow : new long[]{0, -1, (long) Integer.MAX_VALUE + 1, Long.MAX_VALUE}) {
            assertThatThrownBy(() -> new ModelTokenBudgetProperties.Embedding(fingerprint, "embed", badWindow, "revision", "operator", from, until))
                    .isInstanceOf(IllegalArgumentException.class);
            ObjectNode invalid = frozen.deepCopy();
            ((ObjectNode) invalid.path("embedding")).put("maxInputTokensPerText", badWindow);
            assertThatThrownBy(() -> ModelTokenBudgetProperties.reserveEmbedding(invalid, provider, "embed", Integer.MAX_VALUE, badWindow, from))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new ModelTokenBudgetProperties.Embedding("bad", "embed", 1, "revision", "source", from, until))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelTokenBudgetProperties.Embedding(fingerprint, "embed", 1, " ", "source", from, until))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void frozenReservationUsesProviderWindowAndRemainsImmutable() throws Exception {
        var models = new HashMap<>(Map.of("model", new ModelTokenBudgetProperties.Limit(1000, 200, 300)));
        var properties = configured(models);
        models.clear();
        var snapshot = properties.snapshot();
        JsonNode frozen = mapper.readTree(mapper.writeValueAsString(snapshot));
        assertThat(ModelTokenBudgetProperties.reserve(frozen, provider, "model", from)).isEqualTo(1500);
        assertThat(ModelTokenBudgetProperties.reserve(frozen, provider, "model", until.minusNanos(1))).isEqualTo(1500);
        assertThatThrownBy(snapshot::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((Map<?, ?>) snapshot.get("models")).clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> properties.models().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(ModelTokenBudgetProperties.disabled().snapshot()).containsEntry("status", "DISABLED");
    }

    @Test
    void unavailableOrMalformedFrozenContractsNeverReserveZero() {
        JsonNode frozen = mapper.valueToTree(configured(Map.of("model", new ModelTokenBudgetProperties.Limit(1, 2, 0))).snapshot());
        assertThatThrownBy(() -> ModelTokenBudgetProperties.reserve(frozen, provider, "model", until))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModelTokenBudgetProperties.reserve(frozen, provider, "model", from.minusNanos(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModelTokenBudgetProperties.reserve(frozen, null, "model", from))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModelTokenBudgetProperties.reserve(frozen, provider, "missing", from))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModelTokenBudgetProperties.reserve(
                mapper.valueToTree(ModelTokenBudgetProperties.disabled().snapshot()), provider, "model", from))
                .isInstanceOf(IllegalArgumentException.class);
        ObjectNode malformed = frozen.deepCopy();
        ((ObjectNode) malformed.path("models").path("model")).remove("maxReasoningTokens");
        assertThatThrownBy(() -> ModelTokenBudgetProperties.reserve(malformed, provider, "model", from))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void incompleteConfigurationInvalidLimitsAndOverflowAreRejected() {
        assertThatThrownBy(() -> new ModelTokenBudgetProperties(0L, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelTokenBudgetProperties(1L, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> configured(Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelTokenBudgetProperties.Limit(0, 1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelTokenBudgetProperties.Limit(1, 1, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelTokenBudgetProperties.Limit(Long.MAX_VALUE, 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
