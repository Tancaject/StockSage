package com.stocksage.model.dto;

import com.stocksage.model.entity.InvestmentReportVersion;

import java.time.LocalDateTime;

public record InvestmentReportVersionSummary(
        Long id,
        Long conversationId,
        String ticker,
        Integer reportVersion,
        String recommendation,
        String dataSnapshotHash,
        String contextHash,
        String modelTier,
        String modelName,
        LocalDateTime generatedAt,
        LocalDateTime createdAt,
        String userQuery,
        String preview,
        InvestmentReportVersion.ReviewStatus reviewStatus,
        String reviewerUserId,
        String reviewComment,
        LocalDateTime reviewedAt,
        LocalDateTime updatedAt,
        Long lockVersion
) {
    public InvestmentReportVersionSummary(
            Long id,
            Long conversationId,
            String ticker,
            Integer reportVersion,
            String recommendation,
            String dataSnapshotHash,
            String contextHash,
            String modelTier,
            String modelName,
            LocalDateTime generatedAt,
            LocalDateTime createdAt,
            String userQuery,
            String preview
    ) {
        this(
                id,
                conversationId,
                ticker,
                reportVersion,
                recommendation,
                dataSnapshotHash,
                contextHash,
                modelTier,
                modelName,
                generatedAt,
                createdAt,
                userQuery,
                preview,
                InvestmentReportVersion.ReviewStatus.DRAFT,
                null,
                null,
                null,
                createdAt,
                0L
        );
    }
}
