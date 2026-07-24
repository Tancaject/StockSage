package com.stocksage.service;

import com.stocksage.model.dto.InvestmentReport;
import com.stocksage.model.entity.InvestmentReportVersion;

public record InvestmentReportPersistedEvent(
        InvestmentReportVersion source,
        InvestmentReport report
) {
}
