package com.stocksage.model.dto;

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
        String preview
) {
}
