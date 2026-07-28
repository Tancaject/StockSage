package com.stocksage.model.dto;

import com.stocksage.model.entity.InvestmentReportVersion;

import java.time.LocalDateTime;

public record InvestmentReportReviewSummary(
        Long id,
        Long reportVersionId,
        InvestmentReportVersion.ReviewStatus fromStatus,
        InvestmentReportVersion.ReviewStatus toStatus,
        String comment,
        String reviewerUserId,
        LocalDateTime createdAt
) {
}
