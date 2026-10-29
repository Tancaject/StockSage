package com.stocksage.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.ibkr.IbkrHistoricalResponse;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 美股日线派生指标；时间和样本范围绑定实际参与计算的源行情。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TechnicalIndicatorsResponse(
        int schemaVersion, Status status, String provider, String method,
        String symbol, String market, String conid, String exchange, String period, String bar, String source,
        Boolean requestedOutsideRth, String fetchedAt, String asOf, String firstBarAt, String timeKind,
        String currency, String adjustment, Boolean delayed, int count, List<String> requestedIndicators,
        Map<String, Double> indicators, Map<String, String> unavailable, Map<String, String> definitions,
        boolean error, String errorCode, String message, Boolean retryable
) {
    public enum Status { SUCCESS, PARTIAL, EMPTY, UNSUPPORTED, ERROR }
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    private static final Map<String, Integer> MINIMUM = Map.of(
            "MA5", 5, "MA10", 10, "MA20", 20, "MA60", 60, "RSI14", 15,
            "MACD_DIF", 26, "MACD_DEA", 26, "MACD", 26);

    public TechnicalIndicatorsResponse {
        if (schemaVersion != 1 || status == null || !"IBKR_WEB_API".equals(provider)
                || !"STOCKSAGE_DAILY_V1".equals(method) || !"INSTANT".equals(timeKind)
                || count < 0 || error != (status == Status.ERROR || status == Status.UNSUPPORTED)) {
            throw new IllegalArgumentException("Invalid technical indicator contract");
        }
        requestedIndicators = List.copyOf(requestedIndicators);
        indicators = Collections.unmodifiableMap(new LinkedHashMap<>(indicators));
        unavailable = Map.copyOf(unavailable);
        definitions = Map.copyOf(definitions);
        if (fetchedAt != null) Instant.parse(fetchedAt);
        boolean hasValues = indicators.values().stream().anyMatch(Objects::nonNull);
        if (error) {
            if (hasValues || count != 0 || asOf != null || firstBarAt != null) {
                throw new IllegalArgumentException("Failed indicators cannot contain calculated facts");
            }
        } else {
            if (!"US".equals(market) || !"1d".equals(bar) || symbol == null || symbol.isBlank()
                    || period == null || period.isBlank() || fetchedAt == null) {
                throw new IllegalArgumentException("Indicators require US daily source identity and time");
            }
            Set<String> expected = new LinkedHashSet<>();
            for (String requested : requestedIndicators) {
                expected.addAll(switch (requested) {
                    case "MA" -> Set.of("MA5", "MA10", "MA20", "MA60");
                    case "RSI" -> Set.of("RSI14");
                    case "MACD" -> Set.of("MACD_DIF", "MACD_DEA", "MACD");
                    default -> throw new IllegalArgumentException("Unsupported indicator");
                });
            }
            if (count > 0) {
                if (expected.isEmpty() || !indicators.keySet().equals(expected) || conid == null || conid.isBlank()
                        || asOf == null || firstBarAt == null || Instant.parse(firstBarAt).isAfter(Instant.parse(asOf))
                        || Instant.parse(asOf).isAfter(Instant.parse(fetchedAt))) {
                    throw new IllegalArgumentException("Indicators require consistent source range and values");
                }
                for (var entry : indicators.entrySet()) {
                    Double value = entry.getValue();
                    if (value != null && (!Double.isFinite(value) || count < MINIMUM.get(entry.getKey()))
                            || value == null && !unavailable.containsKey(entry.getKey())
                            || value != null && unavailable.containsKey(entry.getKey())) {
                        throw new IllegalArgumentException("Indicator sample count or gap differs from value");
                    }
                }
            } else if (hasValues || asOf != null || firstBarAt != null) {
                throw new IllegalArgumentException("Empty source cannot contain calculated facts");
            }
            if (status == Status.SUCCESS && (!hasValues || !unavailable.isEmpty())
                    || status == Status.PARTIAL && (!hasValues || unavailable.isEmpty())
                    || status == Status.EMPTY && hasValues) {
                throw new IllegalArgumentException("Indicator status differs from available values");
            }
        }
    }

    public static TechnicalIndicatorsResponse fromJson(JsonNode root) {
        try {
            if (root == null || !root.path("schemaVersion").isIntegralNumber() || !root.path("count").isIntegralNumber()
                    || !root.path("status").isTextual() || !root.path("error").isBoolean() || !root.path("indicators").isObject()
                    || !root.path("requestedIndicators").isArray()
                    || root.hasNonNull("delayed") && !root.get("delayed").isBoolean()
                    || root.hasNonNull("requestedOutsideRth") && !root.get("requestedOutsideRth").isBoolean()
                    || root.hasNonNull("retryable") && !root.get("retryable").isBoolean()) {
                throw new IllegalArgumentException("Missing technical indicator fields");
            }
            for (JsonNode value : root.path("requestedIndicators")) {
                if (!value.isTextual()) throw new IllegalArgumentException("Indicator names must be strings");
            }
            for (JsonNode value : root.path("indicators")) {
                if (!value.isNull() && (!value.isNumber() || !Double.isFinite(value.doubleValue()))) {
                    throw new IllegalArgumentException("Indicator values must be finite JSON numbers or null");
                }
            }
            return MAPPER.treeToValue(root, TechnicalIndicatorsResponse.class);
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid daily technical indicator v1 response", invalid);
        }
    }

    public boolean matchesSource(IbkrHistoricalResponse history) {
        if (error) return true;
        return Objects.equals(symbol, history.symbol()) && Objects.equals(market, history.market())
                && Objects.equals(conid, history.conid()) && Objects.equals(period, history.period())
                && Objects.equals(exchange, history.exchange()) && Objects.equals(source, history.source())
                && Objects.equals(bar, history.bar()) && Objects.equals(delayed, history.delayed())
                && Objects.equals(currency, history.currency()) && Objects.equals(adjustment, history.adjustment())
                && Objects.equals(requestedOutsideRth, history.requestedOutsideRth())
                && Instant.parse(fetchedAt).equals(Instant.parse(history.fetchedAt())) && count == history.count()
                && (count == 0 || Instant.parse(asOf).equals(Instant.parse(history.asOf()))
                    && Instant.parse(firstBarAt).equals(Instant.ofEpochMilli(history.data().stream()
                            .mapToLong(IbkrHistoricalResponse.Bar::t).min().orElseThrow())));
    }

    public String toJson() { return MAPPER.valueToTree(this).toString(); }

    public static TechnicalIndicatorsResponse failure(String symbol, String period, String bar, Status status,
                                                       String code, String message) {
        return failure(symbol, period, bar, status, code, message, false);
    }

    public static TechnicalIndicatorsResponse failure(String symbol, String period, String bar, Status status,
                                                       String code, String message, Boolean retryable) {
        return new TechnicalIndicatorsResponse(1, status, "IBKR_WEB_API", "STOCKSAGE_DAILY_V1",
                symbol, "US", null, null, period, bar, null, null, null, null, null, "INSTANT", null, null, null, 0,
                List.of(), Map.of(), Map.of(), Map.of(), true, code, message, retryable);
    }

    public static TechnicalIndicatorsResponse sourceFailure(IbkrHistoricalResponse history) {
        Status status = Status.valueOf(history.status().name());
        return new TechnicalIndicatorsResponse(1, status, "IBKR_WEB_API", "STOCKSAGE_DAILY_V1",
                history.symbol(), history.market(), history.conid(), history.exchange(), history.period(), history.bar(),
                history.source(), history.requestedOutsideRth(), history.fetchedAt(), null, null, "INSTANT",
                history.currency(), history.adjustment(), history.delayed(), 0, List.of(), Map.of(), Map.of(), Map.of(),
                history.error(), history.errorCode(), history.message(), history.retryable());
    }
}
