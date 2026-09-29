package com.stocksage.ibkr;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.stocksage.client.DataServicePayloads;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** IBKR history 独立契约；t 保持供应商毫秒时间戳，价格和成交量不再次应用 factor。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IbkrHistoricalResponse(
        int schemaVersion, Status status, String provider, String input, String market, String symbol,
        String exchange, String conid, String period, String bar, boolean requestedOutsideRth, String source,
        String fetchedAt, String asOf, String timeKind, String currency, String adjustment, String volumeUnit,
        Boolean delayed, boolean error, String errorCode, String message, Boolean retryable, int count,
        List<Bar> data, HistoryMetadata historyMetadata
) {
    public enum Status { SUCCESS, EMPTY, UNSUPPORTED, ERROR }
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);

    public IbkrHistoricalResponse {
        if (schemaVersion != 1 || status == null || !"IBKR_WEB_API".equals(provider)
                || !"EPOCH_MILLIS".equals(timeKind) || !"UNKNOWN".equals(volumeUnit)) {
            throw new IllegalArgumentException("Invalid IBKR history contract");
        }
        Instant.parse(fetchedAt);
        data = List.copyOf(data);
        if (count != data.size() || error != (status == Status.ERROR || status == Status.UNSUPPORTED)) {
            throw new IllegalArgumentException("Inconsistent IBKR history status/count");
        }
        if (status == Status.SUCCESS) {
            if (data.isEmpty() || !data.stream().allMatch(Bar::hasOhlc) || symbol == null || symbol.isBlank()
                    || conid == null || conid.isBlank() || period == null || period.isBlank() || bar == null || bar.isBlank()) {
                throw new IllegalArgumentException("Successful IBKR history requires a contract and dated OHLC rows");
            }
            if (!latestTime(data).equals(asOf)) throw new IllegalArgumentException("IBKR asOf differs from latest bar timestamp");
        } else if (asOf != null || ((status == Status.EMPTY || status == Status.UNSUPPORTED) && !data.isEmpty())) {
            throw new IllegalArgumentException("Non-success IBKR history cannot claim business freshness");
        }
    }

    public static IbkrHistoricalResponse fromJson(JsonNode node) {
        try {
            if (node == null || !node.path("schemaVersion").isIntegralNumber()
                    || !node.path("count").isIntegralNumber() || !node.path("status").isTextual()
                    || !node.path("error").isBoolean()
                    || (node.hasNonNull("currency") && !node.get("currency").isTextual())) {
                throw new IllegalArgumentException("Missing IBKR history version/count/error");
            }
            return MAPPER.treeToValue(node, IbkrHistoricalResponse.class);
        } catch (Exception error) {
            throw new IllegalArgumentException("Invalid IBKR history v1 response", error);
        }
    }

    static IbkrHistoricalResponse fromGateway(JsonNode raw, IbkrInstrument instrument, JsonNode contract,
                                              String period, String bar) {
        if (DataServicePayloads.hasTopLevelError(raw)) {
            String message = text(raw, "message");
            if (message == null && raw.path("error").isTextual()) message = raw.get("error").textValue();
            return failure(instrument.input(), period, bar, Status.ERROR, "IBKR_UPSTREAM_ERROR",
                    message == null ? "IBKR history was unavailable; check the Gateway session and retry." : message, null);
        }
        String conid = text(contract, "conid");
        String contractSymbol = text(contract, "symbol");
        String historySymbol = text(raw, "symbol");
        String symbol = historySymbol == null ? contractSymbol : historySymbol;
        String historyConid = text(raw, "conid");
        if (!matches(instrument, contractSymbol) || !matches(instrument, historySymbol) || symbol == null
                || (historyConid != null && !historyConid.equals(conid))) {
            return failure(instrument.input(), period, bar, Status.ERROR, "TARGET_MISMATCH",
                    "IBKR returned a different or unverifiable symbol; verify the requested stock and contract before retrying.", false);
        }
        try {
            if (raw == null || !raw.path("data").isArray()) throw new IllegalArgumentException("Missing history data array");
            if (raw.hasNonNull("currency") && !raw.get("currency").isTextual()) {
                throw new IllegalArgumentException("IBKR currency must be an explicit string");
            }
            List<Bar> rows = new ArrayList<>();
            for (JsonNode row : raw.get("data")) rows.add(MAPPER.treeToValue(row, Bar.class));
            Status status = rows.isEmpty() ? Status.EMPTY : rows.stream().allMatch(Bar::hasOhlc) ? Status.SUCCESS : Status.ERROR;
            boolean invalid = status == Status.ERROR;
            return new IbkrHistoricalResponse(1, status, "IBKR_WEB_API", instrument.input(), instrument.market(), symbol,
                    instrument.exchange(), conid, period, bar, true, "Trades", Instant.now().toString(),
                    status == Status.SUCCESS ? latestTime(rows) : null, "EPOCH_MILLIS", text(raw, "currency"), null,
                    "UNKNOWN", null, invalid, invalid ? "INVALID_PROVIDER_DATA" : null,
                    invalid ? "IBKR history contains incomplete timestamp/OHLC rows; retry or use another range." : text(raw, "message"),
                    invalid ? Boolean.FALSE : null, rows.size(), rows, MAPPER.treeToValue(raw, HistoryMetadata.class));
        } catch (Exception error) {
            return failure(instrument.input(), period, bar, Status.ERROR, "INVALID_PROVIDER_DATA",
                    "IBKR history returned invalid timestamp/OHLC data; retry or use another range.", false);
        }
    }

    static IbkrHistoricalResponse failure(String input, String period, String bar, Status status,
                                          String errorCode, String message, Boolean retryable) {
        return new IbkrHistoricalResponse(1, status, "IBKR_WEB_API", input, null, null, null, null, period, bar,
                true, "Trades", Instant.now().toString(), null, "EPOCH_MILLIS", null, null, "UNKNOWN", null,
                true, errorCode, message, retryable, 0, List.of(), null);
    }

    private static String latestTime(List<Bar> rows) {
        return Instant.ofEpochMilli(rows.stream().mapToLong(Bar::t).max().orElseThrow()).toString();
    }

    private static boolean matches(IbkrInstrument instrument, String actual) {
        if (actual == null) return true;
        String expected = instrument.symbol();
        if ("HK".equals(instrument.market())) {
            return actual.matches("\\d{1,5}") && expected.equals(actual.replaceFirst("^0+(?!$)", ""));
        }
        return expected.equalsIgnoreCase(actual);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() || !value.isValueNode() || value.asText().isBlank() ? null : value.asText();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Bar(Long t, Double o, Double h, Double l, Double c, Double v) {
        public Bar {
            for (Double value : new Double[]{o, h, l, c, v}) {
                if (value != null && !Double.isFinite(value)) throw new IllegalArgumentException("Non-finite IBKR bar value");
            }
        }
        public boolean hasOhlc() { return t != null && o != null && h != null && l != null && c != null; }
    }

    /** mktDataDelay 是请求处理延迟毫秒数；不能据此推导行情是否延迟。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HistoryMetadata(
            Double priceFactor, Double volumeFactor, String mdAvailability, Long mktDataDelay, Boolean outsideRth,
            Long barLength, String timePeriod, Boolean negativeCapable, Double priceDisplayRule, String priceDisplayValue,
            String startTime, String chartPanStartTime, String high, String low, String serverId, String text,
            Integer messageVersion, Integer direction, Integer points, Long travelTime
    ) {
        public HistoryMetadata {
            for (Double value : new Double[]{priceFactor, volumeFactor, priceDisplayRule}) {
                if (value != null && !Double.isFinite(value)) throw new IllegalArgumentException("Non-finite IBKR metadata");
            }
        }
    }
}
