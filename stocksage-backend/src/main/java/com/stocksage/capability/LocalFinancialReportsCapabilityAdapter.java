package com.stocksage.capability;

import com.stocksage.tool.FundamentalsTools;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/** 结构化财报能力：复用基础取证同一个 {@link FundamentalsTools#getFinancialReports}。 */
@Component
public class LocalFinancialReportsCapabilityAdapter implements CapabilityAdapter {

    public static final String ID = "local.fundamentals.getFinancialReports";
    static final Set<String> PERIODS = Set.of("annual", "quarterly");
    private static final Set<String> KEYS = Set.of("ticker", "period", "periods");

    private final FundamentalsTools fundamentalsTools;

    public LocalFinancialReportsCapabilityAdapter(FundamentalsTools fundamentalsTools) {
        this.fundamentalsTools = fundamentalsTools;
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
        String period = ResearchCapabilityArguments.requireEnum(arguments, "period", PERIODS);
        int periods = ResearchCapabilityArguments.requireInt(arguments, "periods", 1, 5);
        return fundamentalsTools.getFinancialReports(ticker, period, periods);
    }
}
