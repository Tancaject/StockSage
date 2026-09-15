package com.stocksage.agent;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class ReadRequestTest {
    @Test void preservesExplicitPeriodGranularityAndQuarterCount() {
        var bars = ReadRequest.parse(PlanRoute.MARKET, "给我 AAPL 最近一周的小时线", Map.of());
        assertThat(bars.period()).isEqualTo("1w");
        assertThat(bars.bar()).isEqualTo("1h");
        assertThat(bars.barsOnly()).isTrue();
        var reports = ReadRequest.parse(PlanRoute.FUNDAMENTALS, "最近两个季度财报", Map.of());
        assertThat(reports.reportPeriod()).isEqualTo("quarterly");
        assertThat(reports.reportCount()).isEqualTo(2);
        assertThat(reports.reportYears()).isEqualTo(1);
        assertThat(ReadRequest.parse(PlanRoute.MARKET, "过去24小时的5分钟K线", Map.of()).bar()).isEqualTo("5min");
    }

    @Test void rejectsUnsupportedRangesAndUntrustedParametersInsteadOfSubstitutingDailyData() {
        assertThat(ReadRequest.parse(PlanRoute.MARKET, "AAPL", Map.of("bar", "3h")).clarification()).isNotBlank();
        assertThat(ReadRequest.parse(PlanRoute.MARKET, "AAPL 2024年1月日线", Map.of()).clarification()).isNotBlank();
        assertThat(ReadRequest.parse(PlanRoute.MARKET, "近999年K线", Map.of()).clarification()).isNotBlank();
        assertThat(ReadRequest.parse(PlanRoute.MARKET, "分析AAPL最近一周的K线", Map.of()).barsOnly()).isFalse();
    }
}
