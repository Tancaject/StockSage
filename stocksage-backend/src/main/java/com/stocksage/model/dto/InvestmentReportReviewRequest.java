package com.stocksage.model.dto;

import com.stocksage.model.entity.InvestmentReportVersion;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record InvestmentReportReviewRequest(
        @NotNull InvestmentReportVersion.ReviewStatus status,
        @Size(max = 2000) String comment,
        @NotNull Long expectedLockVersion
) {
}
