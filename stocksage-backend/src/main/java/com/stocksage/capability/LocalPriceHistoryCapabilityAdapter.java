package com.stocksage.capability;

import com.stocksage.service.TickerResolutionService;
import com.stocksage.tool.MarketTools;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * K 线能力：美股走 IBKR 历史 K 线，其余市场走普通 K 线，与基础取证的路由一致。
 */
@Component
public class LocalPriceHistoryCapabilityAdapter implements CapabilityAdapter {

    public static final String ID = "local.market.priceHistory";
    static final Set<String> WINDOWS = Set.of("1m", "3m", "6m", "1y");
    static final Set<String> BARS = Set.of("1d", "1w");
    private static final Set<String> KEYS = Set.of("ticker", "window", "bar");

    private final MarketTools marketTools;
    private final TickerResolutionService tickerResolutionService;

    public LocalPriceHistoryCapabilityAdapter(MarketTools marketTools, TickerResolutionService tickerResolutionService) {
        this.marketTools = marketTools;
        this.tickerResolutionService = tickerResolutionService;
    }

    @Override
    public String capabilityId() {
        return ID;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public String invoke(Map<String, Object> arguments, CapabilityInvocationContext context) {
        ResearchCapabilityArguments.requireOnlyKeys(arguments, KEYS);
        String ticker = ResearchCapabilityArguments.requireTicker(arguments);
        String window = ResearchCapabilityArguments.requireEnum(arguments, "window", WINDOWS);
        String bar = ResearchCapabilityArguments.requireEnum(arguments, "bar", BARS);
        if (tickerResolutionService.isLikelySecTicker(ticker)) {
            return marketTools.getIbkrHistoricalBars(ticker, window, bar);
        }
        int days = switch (window) {
            case "1m" -> 22;
            case "3m" -> 66;
            case "6m" -> 130;
            default -> 260;
        };
        return marketTools.getStockKLine(ticker, "1w".equals(bar) ? "weekly" : "daily", days);
    }
}
