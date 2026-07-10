package com.stocksage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.repository.MessageRepository;
import com.stocksage.tool.MarketTools;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TickerResolutionServiceTest {

    private final MarketTools marketTools = mock(MarketTools.class);
    private final TickerResolutionService service = new TickerResolutionService(
            mock(MessageRepository.class),
            marketTools,
            new ObjectMapper()
    );

    @Test
    void resolvesChineseCompanyNamesToTickers() {
        assertThat(service.resolvePrimaryTicker("美光最近财报怎么样")).isEqualTo("MU");
        assertThat(service.resolvePrimaryTicker("分析一下英伟达")).isEqualTo("NVDA");
        assertThat(service.resolvePrimaryTicker("苹果值不值得长期投资")).isEqualTo("AAPL");
    }

    @Test
    void extractsUppercaseTickerFromText() {
        assertThat(service.resolvePrimaryTicker("TSLA 最近走势如何")).isEqualTo("TSLA");
    }

    @Test
    void filtersCommonAbbreviationsAsNonTickers() {
        // 词表统一后，ETF/EPS/GDP 等缩写不应被当作 ticker；
        // 搜索兜底 mock 返回空，最终解析为空串。
        when(marketTools.searchStocks(anyString(), anyInt())).thenReturn("{\"candidates\":[]}");
        assertThat(service.resolvePrimaryTicker("ETF 和 EPS 是什么关系")).isEmpty();
        assertThat(service.containsLikelyTicker("PE ROE MACD GDP CPI")).isFalse();
        assertThat(service.containsLikelyTicker("NVDA earnings")).isTrue();
    }

    @Test
    void identifiesSecTickersVersusAShareAndHk() {
        assertThat(service.isLikelySecTicker("NVDA")).isTrue();
        assertThat(service.isLikelySecTicker("BRK.B")).isTrue();
        assertThat(service.isLikelySecTicker("SH.600519")).isFalse();
        assertThat(service.isLikelySecTicker("600519")).isFalse();
        assertThat(service.isLikelySecTicker("0700.HK")).isFalse();
        assertThat(service.isLikelySecTicker("HK:0700")).isFalse();
        assertThat(service.isLikelySecTicker(null)).isFalse();
    }

    @Test
    void resolvesSectorOnlyForKnownTickers() {
        assertThat(service.resolveSectorForTicker("NVDA")).isEqualTo("半导体");
        assertThat(service.resolveSectorForTicker("nvda")).isEqualTo("半导体");
        assertThat(service.resolveSectorForTicker("UNKNOWN1")).isEmpty();
        assertThat(service.resolveSectorForTicker(null)).isEmpty();
    }
}
