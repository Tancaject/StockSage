package com.stocksage.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class ModelPricingPropertiesTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Instant from = Instant.parse("2026-01-01T00:00:00Z");
    private final Instant until = Instant.parse("2027-01-01T00:00:00Z");

    @Test
    void bindsExactDecimalsAndFreezesBothLevelsOfTheSnapshot() throws Exception {
        JsonNode provider = provider();
        Map<String, Object> properties = new HashMap<>();
        String prefix = "stocksage.research.pricing.";
        properties.put(prefix + "provider-fingerprint", ModelPricingProperties.providerFingerprint(provider));
        properties.put(prefix + "version", "operator-card-1");
        properties.put(prefix + "currency", "USD");
        properties.put(prefix + "source", "contract:approved-card");
        properties.put(prefix + "valid-from", from.toString());
        properties.put(prefix + "valid-until", until.toString());
        properties.put(prefix + "rates[model-a].input-per-million", "0.12345678901234567890123456789");
        properties.put(prefix + "rates[model-a].output-per-million", "2.50");
        properties.put(prefix + "rates[model-a].max-input-tokens", "1000");
        var card = new Binder(new MapConfigurationPropertySource(properties))
                .bind("stocksage.research.pricing", Bindable.of(ModelPricingProperties.class)).get();
        var selected = card.select(provider, "model-a", from);
        assertThat(selected).containsEntry("status", "KNOWN")
                .containsEntry("inputPerMillion", "0.12345678901234567890123456789")
                .containsEntry("outputPerMillion", "2.50").containsEntry("maxInputTokens", 1000L)
                .containsEntry("modelBinding", "REQUESTED_MODEL")
                .containsEntry("validFrom", from.toString()).containsEntry("validUntil", until.toString());
        assertThat(mapper.readTree(mapper.writeValueAsString(card.snapshot())).path("rates").path("model-a")
                .path("inputPerMillion").textValue()).isEqualTo("0.12345678901234567890123456789");
        assertThatThrownBy(() -> card.rates().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> card.snapshot().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((Map<?, ?>) card.snapshot().get("rates")).clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> selected.clear()).isInstanceOf(UnsupportedOperationException.class);
        var mutable = new HashMap<>(card.rates());
        var copied = card(mutable);
        mutable.clear();
        assertThat(copied.rates()).hasSize(1);
    }

    @Test
    void unconfiguredAndInapplicableCardsNeverInventPrices() throws Exception {
        var unconfigured = new ModelPricingProperties(null, null, null, null, null, null, null);
        assertThat(unconfigured.snapshot()).containsEntry("status", "UNCONFIGURED");
        assertThat(unconfigured.select(null, null, null)).containsEntry("reason", "PRICING_UNCONFIGURED");
        var card = card(Map.of("model-a", new ModelPricingProperties.Rate(BigDecimal.ZERO, BigDecimal.ONE, 1000)));
        assertThat(card.select(null, "model-a", from)).containsEntry("reason", "PROVIDER_UNKNOWN");
        assertThat(card.select(mapper.readTree("{\"scope\":\"API_CONSTRUCTION\",\"base\":\"other\"}"), "model-a", from))
                .containsEntry("reason", "PROVIDER_MISMATCH");
        assertThat(card.select(provider(), "missing", from)).containsEntry("reason", "MODEL_NOT_PRICED");
        assertThat(card.select(provider(), null, from)).containsEntry("reason", "MODEL_NOT_PRICED");
        assertThat(card.select(provider(), "model-a", null)).containsEntry("reason", "TIME_UNKNOWN");
        assertThat(card.select(provider(), "model-a", from.minusNanos(1))).containsEntry("reason", "OUTSIDE_VALIDITY");
        assertThat(card.select(provider(), "model-a", from)).containsEntry("status", "KNOWN");
        assertThat(card.select(provider(), "model-a", until.minusNanos(1))).containsEntry("status", "KNOWN");
        assertThat(card.select(provider(), "model-a", until)).containsEntry("reason", "OUTSIDE_VALIDITY");
    }

    @Test
    void rejectsIncompleteOrInvalidConfiguredRates() throws Exception {
        var rate = new ModelPricingProperties.Rate(BigDecimal.ZERO, BigDecimal.ONE, 1);
        String fingerprint = ModelPricingProperties.providerFingerprint(provider());
        assertThatThrownBy(() -> new ModelPricingProperties("bad", "v", "USD", "s", from, until, Map.of("m", rate)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelPricingProperties(fingerprint, " ", "USD", "s", from, until, Map.of("m", rate)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelPricingProperties(fingerprint, "v", "USD", " ", from, until, Map.of("m", rate)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelPricingProperties(fingerprint, "v", "BAD", "s", from, until, Map.of("m", rate)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelPricingProperties(fingerprint, "v", "USD", "s", from, from, Map.of("m", rate)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> card(Map.of(" ", rate))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelPricingProperties.Rate(BigDecimal.valueOf(-1), BigDecimal.ONE, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelPricingProperties.Rate(BigDecimal.ONE, null, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelPricingProperties.Rate(BigDecimal.ONE, BigDecimal.ONE, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fingerprintsNestedObjectsCanonicallyAndRejectsUnknownIdentity() throws Exception {
        JsonNode reordered = mapper.readTree("{\"base\":{\"port\":443,\"host\":\"example.com\"},\"scope\":\"API_CONSTRUCTION\"}");
        assertThat(ModelPricingProperties.providerFingerprint(provider()))
                .isEqualTo(ModelPricingProperties.providerFingerprint(reordered)).matches("[0-9a-f]{64}");
        assertThat(ModelPricingProperties.providerFingerprint(null)).isNull();
        assertThat(ModelPricingProperties.providerFingerprint(mapper.readTree("[]"))).isNull();
        assertThat(ModelPricingProperties.providerFingerprint(mapper.readTree("{}"))).isNull();
        assertThat(ModelPricingProperties.providerFingerprint(mapper.readTree("{\"scope\":\"API_CONSTRUCTION\",\"status\":\"UNKNOWN\"}"))).isNull();
    }

    private JsonNode provider() throws Exception {
        return mapper.readTree("{\"scope\":\"API_CONSTRUCTION\",\"base\":{\"host\":\"example.com\",\"port\":443}}");
    }

    private ModelPricingProperties card(Map<String, ModelPricingProperties.Rate> rates) throws Exception {
        return new ModelPricingProperties(ModelPricingProperties.providerFingerprint(provider()), "v1", "USD",
                "contract:approved-card", from, until, rates);
    }
}
