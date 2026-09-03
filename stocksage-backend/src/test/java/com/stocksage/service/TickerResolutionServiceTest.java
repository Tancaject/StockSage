package com.stocksage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.repository.MessageRepository;
import com.stocksage.tool.MarketTools;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
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

    @ParameterizedTest
    @MethodSource("companyAliases")
    void resolvesEveryKnownCompanyAliasWithoutSearching(String query, String ticker) {
        assertThat(service.resolvePrimaryTicker(query)).isEqualTo(ticker);
        verifyNoInteractions(marketTools);
    }

    @Test
    void preservesKnownCompanyPriorityWhenSeveralAliasesAppear() {
        assertThat(service.resolvePrimaryTicker("比较 apple、NVIDIA 和 micron"))
                .isEqualTo("MU");
    }

    @Test
    void extractsUppercaseTickerFromText() {
        assertThat(service.resolvePrimaryTicker("TSLA 最近走势如何")).isEqualTo("TSLA");
    }

    @Test
    void resolvesExplicitTargetsWithoutSearchFallback() {
        assertThat(service.resolveExplicitTicker("Microsoft antitrust risk")).isEqualTo("MSFT");
        assertThat(service.resolveExplicitTicker("msft antitrust risk")).isEqualTo("MSFT");
        assertThat(service.resolveExplicitTicker("0700.HK regulation")).isEqualTo("0700.HK");
        assertThat(service.resolveExplicitTicker("regulatory risk")).isEmpty();
        verifyNoInteractions(marketTools);
    }

    @Test
    void detectsOnlyASecondExplicitTarget() {
        assertThat(service.hasConflictingExplicitTicker("比较 AAPL 和 MSFT", "AAPL")).isTrue();
        assertThat(service.hasConflictingExplicitTicker("苹果 vs 微软", "AAPL")).isTrue();
        assertThat(service.hasConflictingExplicitTicker("AAPL 与微软", "AAPL")).isTrue();
        assertThat(service.hasConflictingExplicitTicker("AAPL vs AMD", "AAPL")).isTrue();
        assertThat(service.hasConflictingExplicitTicker("AAPL vs ORCL", "AAPL")).isTrue();
        assertThat(service.hasConflictingExplicitTicker("AAPL vs BRK.B", "AAPL")).isTrue();
        assertThat(service.hasConflictingExplicitTicker("AAPL vs F", "AAPL")).isTrue();
        assertThat(service.hasConflictingExplicitTicker("AAPL VS AMD", "AAPL")).isTrue();
        assertThat(service.hasConflictingExplicitTicker("AAPL VS AAPL", "AAPL")).isFalse();
        assertThat(service.hasConflictingExplicitTicker("TSLA VS TESLA", "TSLA")).isFalse();
        assertThat(service.hasConflictingExplicitTicker("AAPL R&D", "AAPL")).isFalse();
        assertThat(service.hasConflictingExplicitTicker("AAPL / Apple 的 HBM 和 ROE", "AAPL")).isFalse();
        assertThat(service.hasConflictingExplicitTicker(
                "AAPL APPLE DCF WACC CAGR HBM DRAM NAND R&D", "AAPL")).isFalse();
        assertThat(service.hasConflictingExplicitTicker("TSLA TESLA", "TSLA")).isFalse();
        assertThat(service.hasConflictingExplicitTicker("SH.600519", "SH.600519")).isFalse();
        assertThat(service.hasConflictingExplicitTicker("SZ.000001", "SZ.000001")).isFalse();
        assertThat(service.hasConflictingExplicitTicker("HK:0700", "0700.HK")).isFalse();
        assertThat(service.hasConflictingExplicitTicker("HKG:0700", "0700.HK")).isFalse();
        assertThat(service.hasConflictingExplicitTicker("00700.HK", "0700.HK")).isFalse();
        assertThat(service.hasConflictingExplicitTicker("9988.HK", "0700.HK")).isTrue();
        assertThat(service.hasConflictingExplicitTicker("Should I buy AAPL?", "AAPL")).isFalse();
        assertThat(service.hasConflictingExplicitTicker("AAPL is good", "AAPL")).isFalse();
        assertThat(service.hasConflictingExplicitTicker("AAPL cloud risk", "AAPL")).isFalse();
        assertThat(service.hasConflictingExplicitTicker(
                "Should I buy AAPL for five years?", "AAPL")).isFalse();
    }

    @Test
    void normalizesOnlyStructuredTickerFormats() {
        assertThat(service.normalizeStructuredTicker("msft")).isEqualTo("MSFT");
        assertThat(service.normalizeStructuredTicker("sh.600519")).isEqualTo("SH.600519");
        assertThat(service.normalizeStructuredTicker("HK:0700")).isEqualTo("0700.HK");
        assertThat(service.normalizeStructuredTicker("HKG:0700")).isEqualTo("0700.HK");
        assertThat(service.normalizeStructuredTicker("00700.HK")).isEqualTo("0700.HK");
        assertThat(service.normalizeStructuredTicker("00700")).isEmpty();
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

    private static Stream<Arguments> companyAliases() {
        return Stream.of(
                Arguments.of("micron earnings", "MU"),
                Arguments.of("英偉達走势", "NVDA"),
                Arguments.of("microsoft cloud", "MSFT"),
                Arguments.of("蘋果新品", "AAPL"),
                Arguments.of("亞馬遜财报", "AMZN"),
                Arguments.of("alphabet valuation", "GOOGL"),
                Arguments.of("tesla deliveries", "TSLA"),
                Arguments.of("強生诉讼", "JNJ"),
                Arguments.of("exxon oil", "XOM"),
                Arguments.of("jpmorgan earnings", "JPM")
        );
    }
}
