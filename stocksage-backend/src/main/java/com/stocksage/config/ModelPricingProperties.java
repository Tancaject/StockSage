package com.stocksage.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Collections;
import java.util.Currency;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/** Operator-supplied historical flat rates; never a provider billing statement. */
@ConfigurationProperties(prefix = "stocksage.research.pricing")
public record ModelPricingProperties(String providerFingerprint, String version, String currency, String source,
                                     Instant validFrom, Instant validUntil, Map<String, Rate> rates) {
    private static final String BASIS = "CONFIGURED_FLAT_RATE_ESTIMATE";

    public ModelPricingProperties {
        rates = rates == null ? Map.of() : rates;
        if (!rates.isEmpty()) {
            require(providerFingerprint != null && providerFingerprint.matches("[0-9a-f]{64}"),
                    "providerFingerprint must be a lowercase SHA-256 hex digest");
            require(version != null && !version.isBlank(), "version is required");
            require(source != null && !source.isBlank(), "source is required");
            require(currency != null && currency.matches("[A-Z]{3}"), "currency must be an ISO currency code");
            Currency.getInstance(currency);
            require(validFrom != null && validUntil != null && validUntil.isAfter(validFrom),
                    "validUntil must be after validFrom");
            rates.forEach((model, rate) -> require(model != null && !model.isBlank() && rate != null,
                    "each model must have a nonblank name and a rate"));
        }
        rates = Collections.unmodifiableMap(new TreeMap<>(rates));
    }

    // ponytail: flat nominal rates only; tier/cache billing needs provider usage details and a versioned tariff.
    public record Rate(BigDecimal inputPerMillion, BigDecimal outputPerMillion, long maxInputTokens) {
        public Rate {
            require(inputPerMillion != null && inputPerMillion.signum() >= 0,
                    "inputPerMillion must be nonnegative");
            require(outputPerMillion != null && outputPerMillion.signum() >= 0,
                    "outputPerMillion must be nonnegative");
            require(maxInputTokens > 0, "maxInputTokens must be positive");
        }

        private Map<String, Object> snapshot() {
            return Map.of("inputPerMillion", inputPerMillion.toPlainString(), "outputPerMillion", outputPerMillion.toPlainString(),
                    "maxInputTokens", maxInputTokens);
        }
    }

    public Map<String, Object> snapshot() {
        if (rates.isEmpty()) return Map.of("status", "UNCONFIGURED", "basis", BASIS);
        Map<String, Object> snapshot = metadata();
        Map<String, Object> modelRates = new TreeMap<>();
        rates.forEach((model, rate) -> modelRates.put(model, rate.snapshot()));
        snapshot.put("rates", Collections.unmodifiableMap(modelRates));
        snapshot.put("status", "CONFIGURED");
        return Collections.unmodifiableMap(snapshot);
    }

    public Map<String, Object> select(JsonNode provider, String model, Instant at) {
        if (rates.isEmpty()) return unknown("PRICING_UNCONFIGURED");
        String actualProvider = providerFingerprint(provider);
        if (actualProvider == null) return unknown("PROVIDER_UNKNOWN");
        if (!providerFingerprint.equals(actualProvider)) return unknown("PROVIDER_MISMATCH");
        if (at == null) return unknown("TIME_UNKNOWN");
        // A rate transition instant belongs exclusively to the next rate card.
        if (at.isBefore(validFrom) || !at.isBefore(validUntil)) return unknown("OUTSIDE_VALIDITY");
        Rate rate = model == null ? null : rates.get(model);
        if (rate == null) return unknown("MODEL_NOT_PRICED");
        Map<String, Object> selection = metadata();
        selection.putAll(rate.snapshot());
        selection.put("status", "KNOWN");
        selection.put("model", model);
        selection.put("modelBinding", "REQUESTED_MODEL");
        return Collections.unmodifiableMap(selection);
    }

    private Map<String, Object> metadata() {
        Map<String, Object> metadata = new TreeMap<>();
        metadata.put("basis", BASIS);
        metadata.put("providerFingerprint", providerFingerprint);
        metadata.put("version", version);
        metadata.put("currency", currency);
        metadata.put("source", source);
        metadata.put("validFrom", validFrom.toString());
        metadata.put("validUntil", validUntil.toString());
        return metadata;
    }

    private static Map<String, Object> unknown(String reason) {
        return Map.of("status", "UNKNOWN", "reason", reason, "basis", BASIS);
    }

    public static String providerFingerprint(JsonNode provider) {
        if (provider == null || !provider.isObject()
                || !"API_CONSTRUCTION".equals(provider.path("scope").asText())
                || "UNKNOWN".equals(provider.path("status").asText())) return null;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical(provider).toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM does not support SHA-256", impossible);
        }
    }

    private static JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            Map<String, JsonNode> sorted = new TreeMap<>();
            value.fields().forEachRemaining(entry -> sorted.put(entry.getKey(), canonical(entry.getValue())));
            return JsonNodeFactory.instance.objectNode().setAll(sorted);
        }
        if (value.isArray()) {
            var array = JsonNodeFactory.instance.arrayNode();
            value.forEach(element -> array.add(canonical(element)));
            return array;
        }
        return value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException("stocksage.research.pricing: " + message);
    }
}
