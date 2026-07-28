package com.stocksage.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkbenchCockpitResponse(
        String ticker,
        Map<String, Object> chart,
        String chartStatus,
        String chartMessage,
        ReportSummary latestReport,
        List<TaskSummary> taskTimeline,
        List<EvidencePreview> evidencePreview,
        LocalDateTime generatedAt
) {
    public record ReportSummary(
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
            List<String> citations
    ) {
    }

    public record TaskSummary(
            Long id,
            Long conversationId,
            String ticker,
            String status,
            String stage,
            Integer attempts,
            Long resultReportVersionId,
            String resultKind,
            LocalDateTime startedAt,
            LocalDateTime heartbeatAt,
            LocalDateTime completedAt,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            String errorMessage
    ) {
    }

    public record EvidencePreview(
            String dimension,
            String evidence,
            String implication,
            String source
    ) {
    }
}
