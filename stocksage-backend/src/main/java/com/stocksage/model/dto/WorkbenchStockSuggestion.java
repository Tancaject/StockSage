package com.stocksage.model.dto;

public record WorkbenchStockSuggestion(
        String ticker,
        String name,
        String market,
        String source
) {
}
