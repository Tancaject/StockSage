package com.stocksage.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.client.KLineResponse;
import com.stocksage.ibkr.IbkrHistoricalResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.time.Instant;

/**
 * 把不同行情工具的 K 线 JSON 归一成前端蜡烛图载荷。
 *
 * <p>由工具事件/工作台链路调用，读取 Python data-service 与 IBKR 各自的 v1 响应契约；
 * 契约无效或上游返回非成功状态时返回 {@link Optional#empty()}，不会影响原工具结果。</p>
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
     * @return 图表载荷；非 K 线工具、错误响应或无有效 OHLC 时为空
     */
    public Optional<Map<String, Object>> toChartPayload(String sourceTool, Object result) {
        if (!isKlineTool(sourceTool) || result == null) {
            return Optional.empty();
        }
        try {
            JsonNode root = result instanceof String text
                    ? objectMapper.readTree(text)
                    : objectMapper.valueToTree(result);
            if ("getStockKLine".equals(sourceTool)) {
                return toVersionedChart(sourceTool, KLineResponse.fromJson(root));
            }
            return toIbkrChart(sourceTool, IbkrHistoricalResponse.fromJson(root));
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
                || "getIbkrHistoricalBars".equals(sourceTool);
    }

    private Optional<Map<String, Object>> toVersionedChart(String sourceTool, KLineResponse response) {
        if (response.status() != KLineResponse.Status.SUCCESS) return Optional.empty();
        List<Map<String, Object>> points = new ArrayList<>();
        int start = Math.max(0, response.data().size() - CHART_POINT_LIMIT);
        for (KLineResponse.Bar bar : response.data().subList(start, response.data().size())) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", bar.date());
            point.put("open", bar.open());
            point.put("high", bar.high());
            point.put("low", bar.low());
            point.put("close", bar.close());
            if (bar.volume() != null) point.put("volume", bar.volume());
            points.add(point);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chartType", "candlestick");
        payload.put("sourceTool", sourceTool);
        payload.put("title", response.resolvedCode() + " K线走势");
        payload.put("symbol", response.resolvedCode());
        payload.put("period", response.period());
        payload.put("provider", response.provider());
        payload.put("schemaVersion", response.schemaVersion());
        payload.put("currency", response.currency());
        payload.put("volumeUnit", response.volumeUnit().name());
        payload.put("adjustment", response.adjustment());
        payload.put("timeKind", response.timeKind());
        payload.put("asOf", response.asOf());
        payload.put("fetchedAt", response.fetchedAt());
        payload.put("points", points);
        return Optional.of(payload);
    }

    private Optional<Map<String, Object>> toIbkrChart(String sourceTool, IbkrHistoricalResponse response) {
        if (response.status() != IbkrHistoricalResponse.Status.SUCCESS) return Optional.empty();
        List<Map<String, Object>> points = new ArrayList<>();
        int start = Math.max(0, response.data().size() - CHART_POINT_LIMIT);
        for (IbkrHistoricalResponse.Bar bar : response.data().subList(start, response.data().size())) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", Instant.ofEpochMilli(bar.t()).toString());
            point.put("t", bar.t());
            point.put("open", bar.o());
            point.put("high", bar.h());
            point.put("low", bar.l());
            point.put("close", bar.c());
            if (bar.v() != null) point.put("volume", bar.v());
            points.add(point);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chartType", "candlestick");
        payload.put("sourceTool", sourceTool);
        payload.put("title", response.symbol() + " K线走势");
        payload.put("symbol", response.symbol());
        payload.put("period", response.bar());
        payload.put("window", response.period());
        payload.put("provider", response.provider());
        payload.put("schemaVersion", response.schemaVersion());
        payload.put("currency", response.currency());
        payload.put("volumeUnit", response.volumeUnit());
        payload.put("adjustment", response.adjustment());
        payload.put("timeKind", response.timeKind());
        payload.put("asOf", response.asOf());
        payload.put("fetchedAt", response.fetchedAt());
        payload.put("delayed", response.delayed());
        payload.put("historyMetadata", response.historyMetadata());
        payload.put("points", points);
        return Optional.of(payload);
    }
}
