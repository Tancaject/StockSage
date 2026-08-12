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

/**
 * 把不同行情工具的 K 线 JSON 归一成前端蜡烛图载荷。
 *
 * <p>由工具事件/工作台链路调用，兼容 Python data-service 与 IBKR 的嵌套字段和大小写差异；
 * 无法识别或上游返回错误时返回 {@link Optional#empty()}，不会影响原工具结果。</p>
 */
@Component
@RequiredArgsConstructor
public class KLinePayloadMapper {

    /** 防止一次工具事件向前端推送过多图表点。 */
    private static final int CHART_POINT_LIMIT = 260;

    /** 在字符串响应和对象响应之间统一构造 JSON 树。 */
    private final ObjectMapper objectMapper;

    /**
     * 将受支持工具的响应转换为前端 candlestick 载荷。
     *
     * @param sourceTool 产生结果的工具名
     * @param result 工具原始结果，可为 JSON 字符串或对象
     * @param args 工具调用参数，用于补足 symbol、period
     * @return 图表载荷；非 K 线工具、错误响应或无有效 OHLC 时为空
     */
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

    /**
     * 判断工具结果是否应尝试解析为 K 线。
     *
     * @param sourceTool 工具名
     * @return true 表示该工具受当前映射器支持
     */
    public static boolean isKlineTool(String sourceTool) {
        return "getStockKLine".equals(sourceTool)
                || "getGlobalKLine".equals(sourceTool)
                || "getIbkrHistoricalBars".equals(sourceTool);
    }

    /** 从任意兼容嵌套结构中提取并限制有效 OHLCV 点。 */
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

    /** 在常见字段及有限深度的对象树中定位 OHLCV 数组。 */
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

    /** 采样数组前几项，判断是否至少包含一条完整 OHLC 记录。 */
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

    /** 按兼容字段顺序读取第一个非空文本。 */
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

    /** 按兼容字段顺序读取数值，并容忍带千分位的字符串。 */
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

    /** 容错读取工具参数，缺失时返回空串。 */
    private String argAt(Object[] args, int index) {
        if (args == null || index < 0 || index >= args.length || args[index] == null) {
            return "";
        }
        return String.valueOf(args[index]).trim();
    }

    /** 从若干候选文本中选择第一个非空值。 */
    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
