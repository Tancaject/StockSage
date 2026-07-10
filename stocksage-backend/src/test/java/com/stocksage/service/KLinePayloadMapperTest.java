package com.stocksage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KLinePayloadMapperTest {

    private final KLinePayloadMapper mapper = new KLinePayloadMapper(new ObjectMapper());

    @Test
    void mapsStockKLineRowsIntoCandlestickPayload() {
        String raw = """
                {
                  "code": "sh.600519",
                  "period": "daily",
                  "provider": "baostock",
                  "data": [
                    {"date": "2026-06-03", "open": "1580.00", "high": "1602.00", "low": "1571.00", "close": "1598.00", "volume": "123456"},
                    {"date": "2026-06-04", "open": "1598.00", "high": "1610.00", "low": "1588.00", "close": "1604.00", "volume": "234567"}
                  ]
                }
                """;

        Map<String, Object> payload = mapper
                .toChartPayload("getStockKLine", raw, new Object[]{"sh.600519", "daily", 120})
                .orElseThrow();

        assertThat(payload)
                .containsEntry("chartType", "candlestick")
                .containsEntry("sourceTool", "getStockKLine")
                .containsEntry("symbol", "sh.600519")
                .containsEntry("period", "daily")
                .containsEntry("provider", "baostock");
        assertThat((List<?>) payload.get("points")).hasSize(2);
        Map<?, ?> lastPoint = (Map<?, ?>) ((List<?>) payload.get("points")).get(1);
        assertThat(lastPoint.get("date")).isEqualTo("2026-06-04");
        assertThat(lastPoint.get("close")).isEqualTo(1604.0);
        assertThat(lastPoint.get("volume")).isEqualTo(234567.0);
    }

    @Test
    void mapsIbkrHistoricalBarsIntoSameCandlestickContract() {
        String raw = """
                {
                  "symbol": "NVDA",
                  "bar": "1d",
                  "data": {
                    "provider": "IBKR",
                    "bars": [
                      {"time": "2026-06-03", "o": 116.5, "h": 118.2, "l": 115.9, "c": 117.7, "v": 4567890}
                    ]
                  }
                }
                """;

        Map<String, Object> payload = mapper
                .toChartPayload("getIbkrHistoricalBars", raw, new Object[]{"NVDA", "6m", "1d"})
                .orElseThrow();

        assertThat(payload)
                .containsEntry("chartType", "candlestick")
                .containsEntry("sourceTool", "getIbkrHistoricalBars")
                .containsEntry("symbol", "NVDA")
                .containsEntry("period", "1d")
                .containsEntry("provider", "IBKR");
        assertThat((List<?>) payload.get("points")).hasSize(1);
    }

    @Test
    void returnsEmptyWhenToolResultHasNoOhlcRows() {
        assertThat(mapper.toChartPayload(
                "getStockKLine",
                "{\"code\":\"NVDA\",\"data\":[]}",
                new Object[]{"NVDA", "daily", 120}
        )).isEmpty();
    }
}
