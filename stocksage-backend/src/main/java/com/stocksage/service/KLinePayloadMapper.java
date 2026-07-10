package com.stocksage.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class KLinePayloadMapper {

    private static final int CHART_POINT_LIMIT = 260;

    private final ObjectMapper objectMapper;

    public Optional<Map<String, Object>> toChartPayload(String sourceTool, Object result, Object[] args) {
        if (!isKlineTool(sourceTool) || result == null) {
            return Optional.empty();
        }
        try {
            JsonNode root = result instanceof String text
                    ? objectMapper.readTree(text)
                    : objectMapper.valueToTree(result);
            if (root.path("error").asBoolean(false) || root.path("data").path("error").asBoolean(false)) {
                return Optional.empty();
            }

            List<Map<String, Object>> points = extractChartPoints(root);
            if (points.isEmpty()) {
                return Optional.empty();
            }

            String symbol = firstNonBlank(
                    firstText(root, "symbol", "code", "input"),
                    firstText(root.path("route"), "standard_code", "api_code", "input"),
                    argAt(args, 0)
            );
            String period = firstNonBlank(firstText(root, "period", "bar"), argAt(args, 1));
            String provider = firstNonBlank(firstText(root, "provider"), firstText(root.path("data"), "provider"));

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("chartType", "candlestick");
            payload.put("sourceTool", sourceTool);
            payload.put("title", symbol.isBlank() ? "K线走势" : symbol + " K线走势");
            payload.put("symbol", symbol);
            payload.put("period", period);
            payload.put("provider", provider);
            payload.put("points", points);
            return Optional.of(payload);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public static boolean isKlineTool(String sourceTool) {
        return "getStockKLine".equals(sourceTool)
                || "getGlobalKLine".equals(sourceTool)
                || "getIbkrHistoricalBars".equals(sourceTool);
    }

    private List<Map<String, Object>> extractChartPoints(JsonNode root) {
        JsonNode rows = findOhlcvArray(root, 0);
        if (rows == null || !rows.isArray()) {
            return List.of();
        }

        int start = Math.max(0, rows.size() - CHART_POINT_LIMIT);
        List<Map<String, Object>> points = new ArrayList<>();
        for (int i = start; i < rows.size(); i++) {
            JsonNode row = rows.get(i);
            Double open = firstDouble(row, "open", "Open", "o", "O");
            Double high = firstDouble(row, "high", "High", "h", "H");
            Double low = firstDouble(row, "low", "Low", "l", "L");
            Double close = firstDouble(row, "close", "Close", "c", "C");
            if (open == null || high == null || low == null || close == null) {
                continue;
            }

            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", firstText(row, "date", "Date", "time", "Time", "timestamp", "Timestamp", "t", "T"));
            point.put("open", open);
            point.put("high", high);
            point.put("low", low);
            point.put("close", close);
            Double volume = firstDouble(row, "volume", "Volume", "v", "V");
            if (volume != null) {
                point.put("volume", volume);
            }
            points.add(point);
        }
        return points;
    }

    private JsonNode findOhlcvArray(JsonNode node, int depth) {
        if (node == null || node.isMissingNode() || depth > 4) {
            return null;
        }
        if (node.isArray()) {
            return containsOhlcRow(node) ? node : null;
        }
        if (!node.isObject()) {
            return null;
        }

        for (String field : new String[]{"data", "bars", "candles", "items", "rows"}) {
            JsonNode found = findOhlcvArray(node.path(field), depth + 1);
            if (found != null) {
                return found;
            }
        }

        var fields = node.fields();
        while (fields.hasNext()) {
            JsonNode found = findOhlcvArray(fields.next().getValue(), depth + 1);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private boolean containsOhlcRow(JsonNode array) {
        if (!array.isArray() || array.isEmpty()) {
            return false;
        }
        int sampleSize = Math.min(array.size(), 5);
        for (int i = 0; i < sampleSize; i++) {
            JsonNode row = array.get(i);
            if (firstDouble(row, "open", "Open", "o", "O") != null
                    && firstDouble(row, "high", "High", "h", "H") != null
                    && firstDouble(row, "low", "Low", "l", "L") != null
                    && firstDouble(row, "close", "Close", "c", "C") != null) {
                return true;
            }
        }
        return false;
    }

    private String firstText(JsonNode node, String... fields) {
        if (node == null || node.isMissingNode()) {
            return "";
        }
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (!value.isMissingNode() && !value.isNull()) {
                String text = value.asText("").trim();
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return "";
    }

    private Double firstDouble(JsonNode node, String... fields) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isMissingNode() || value.isNull()) {
                continue;
            }
            if (value.isNumber()) {
                return value.asDouble();
            }
            try {
                String text = value.asText("").replace(",", "").trim();
                if (!text.isBlank() && !"-".equals(text)) {
                    return Double.parseDouble(text);
                }
            } catch (NumberFormatException ignored) {
                // Try the next field alias.
            }
        }
        return null;
    }

    private String argAt(Object[] args, int index) {
        if (args == null || index < 0 || index >= args.length || args[index] == null) {
            return "";
        }
        return String.valueOf(args[index]).trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
