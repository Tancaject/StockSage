package com.stocksage.client;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Python A/H 股 K 线 v1 边界；日期表示业务周期，fetchedAt 仅表示服务取得响应的时间。 */
public record KLineResponse(
        int schemaVersion, Status status, String input, String market, String resolvedCode,
        String source, String routeReason, String provider, String period, int count,
        String code, String symbol, boolean error, String message, String fetchedAt, String asOf,
        String timeKind, String adjustment, String currency, VolumeUnit volumeUnit,
        Boolean retryable, String errorCode, List<Bar> data,
        String primaryProvider, String fallbackProvider, String primaryProviderError,
        JsonNode baostockStatus, String feature
) {
    public enum Status { SUCCESS, EMPTY, UNSUPPORTED, ERROR }
    public enum VolumeUnit { SHARE, LOT, UNKNOWN }
    private static final Set<String> PERIODS = Set.of("daily", "weekly", "monthly");

    public KLineResponse {
        if (schemaVersion != 1 || status == null || volumeUnit == null || !"DATE".equals(timeKind)
                || (period == null ? status != Status.ERROR : !PERIODS.contains(period))) {
            throw new IllegalArgumentException("Unsupported K-line contract");
        }
        Instant.parse(fetchedAt);
        if (asOf != null) LocalDate.parse(asOf);
        if (adjustment != null && !"FORWARD_ADJUSTED".equals(adjustment)) {
            throw new IllegalArgumentException("Unsupported K-line adjustment");
        }
        if (currency != null && !Set.of("CNY", "HKD").contains(currency)) {
            throw new IllegalArgumentException("Unsupported K-line currency");
        }
        data = List.copyOf(data);
        if (count != data.size() || error != (status == Status.ERROR || status == Status.UNSUPPORTED)) {
            throw new IllegalArgumentException("Inconsistent K-line status/count");
        }
        if (status == Status.SUCCESS) {
            if (data.isEmpty() || !data.stream().allMatch(Bar::hasOhlc)
                    || provider == null || provider.isBlank() || resolvedCode == null || resolvedCode.isBlank()) {
                throw new IllegalArgumentException("SUCCESS K-line response requires dated OHLC, provider and target");
            }
            String latest = data.stream().map(Bar::date).max(String::compareTo).orElseThrow();
            if (!latest.equals(asOf)) throw new IllegalArgumentException("K-line asOf differs from latest business date");
        } else if (asOf != null || ((status == Status.EMPTY || status == Status.UNSUPPORTED) && !data.isEmpty())) {
            throw new IllegalArgumentException("Non-success K-line response cannot claim business freshness");
        }
    }

    /** 版本化响应只接受规范字段，不再猜测别名或递归寻找业务数据。 */
    public static KLineResponse fromJson(JsonNode root) {
        if (root == null || !root.isObject() || !root.path("schemaVersion").isIntegralNumber()
                || !root.path("schemaVersion").canConvertToInt() || !root.path("count").canConvertToInt()
                || !root.path("count").isIntegralNumber() || !root.path("data").isArray()
                || !root.path("error").isBoolean()) {
            throw new IllegalArgumentException("Invalid K-line v1 response shape");
        }
        List<Bar> rows = new ArrayList<>();
        for (JsonNode row : root.get("data")) rows.add(Bar.fromJson(row));
        return new KLineResponse(root.get("schemaVersion").intValue(), Status.valueOf(text(root, "status")),
                text(root, "input"), text(root, "market"), text(root, "resolvedCode"), text(root, "source"),
                text(root, "routeReason"), text(root, "provider"), text(root, "period"), root.get("count").intValue(),
                text(root, "code"), text(root, "symbol"), root.get("error").booleanValue(), text(root, "message"),
                text(root, "fetchedAt"), text(root, "asOf"), text(root, "timeKind"), text(root, "adjustment"),
                text(root, "currency"), VolumeUnit.valueOf(text(root, "volumeUnit")), bool(root, "retryable"),
                text(root, "errorCode"), rows, text(root, "primaryProvider"), text(root, "fallbackProvider"),
                text(root, "primaryProviderError"), root.get("baostockStatus"), text(root, "feature"));
    }

    /** HTTP/协议错误没有可验证的市场、供应商或业务日期，保留未知值。 */
    public static KLineResponse failure(String input, String period, String errorCode, String message, Boolean retryable) {
        String knownPeriod = period != null && PERIODS.contains(period) ? period : null;
        return new KLineResponse(1, Status.ERROR, input, null, null, null, null, null, knownPeriod, 0,
                null, null, true, message, Instant.now().toString(), null, "DATE", null, null,
                VolumeUnit.UNKNOWN, retryable, errorCode, List.of(), null, null, null, null, null);
    }

    public record Bar(String date, Double open, Double high, Double low, Double close, Double volume,
                      Double amount, Double change, Double pctChg, Double turnoverRate, Double amplitude,
                      Double turn, String code) {

        public Bar {
            if (date != null) LocalDate.parse(date);
            for (Double value : new Double[]{open, high, low, close, volume, amount, change, pctChg, turnoverRate, amplitude, turn}) {
                if (value != null && !Double.isFinite(value)) throw new IllegalArgumentException("Non-finite K-line value");
            }
        }

        public boolean hasOhlc() { return date != null && open != null && high != null && low != null && close != null; }

        private static Bar fromJson(JsonNode row) {
            if (!row.isObject()) throw new IllegalArgumentException("K-line row must be an object");
            return new Bar(text(row, "date"), number(row, "open"), number(row, "high"), number(row, "low"),
                    number(row, "close"), number(row, "volume"), number(row, "amount"), number(row, "change"),
                    number(row, "pctChg"), number(row, "turnoverRate"), number(row, "amplitude"), number(row, "turn"),
                    text(row, "code"));
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw new IllegalArgumentException("K-line " + field + " must be text");
        return value.textValue();
    }

    private static Double number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isNumber()) throw new IllegalArgumentException("K-line " + field + " must be numeric");
        return value.doubleValue();
    }

    private static Boolean bool(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isBoolean()) throw new IllegalArgumentException("K-line " + field + " must be boolean");
        return value.booleanValue();
    }

}
