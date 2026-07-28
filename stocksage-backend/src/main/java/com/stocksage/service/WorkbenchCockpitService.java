package com.stocksage.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.model.dto.InvestmentReport;
import com.stocksage.model.dto.InvestmentReportVersionSummary;
import com.stocksage.model.dto.WorkbenchCockpitResponse;
import com.stocksage.model.entity.ResearchTask;
import com.stocksage.repository.ResearchTaskRepository;
import com.stocksage.tool.MarketTools;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkbenchCockpitService {

    private static final int DEFAULT_DAYS = 120;
    private static final int MAX_DAYS = 240;
    private static final int TASK_LIMIT = 5;
    private static final String INTRADAY_PERIOD = "24h";
    private static final String INTRADAY_BAR = "5min";

    private final MarketTools marketTools;
    private final InvestmentReportVersionService reportVersionService;
    private final ResearchTaskRepository researchTaskRepository;
    private final KLinePayloadMapper kLinePayloadMapper;
    private final OfflineDemoSampleService offlineDemoSampleService;
    private final ObjectMapper objectMapper;

    public WorkbenchCockpitResponse getCockpit(String userId, String ticker, String period, int days) {
        String normalizedTicker = normalizeTicker(ticker);
        String normalizedPeriod = normalizePeriod(period);
        int normalizedDays = normalizeDays(days);

        Optional<WorkbenchCockpitResponse> offlineSample = loadOfflineSample(normalizedTicker);
        ChartResult chartResult = applyOfflineChartFallback(
                loadChart(normalizedTicker, normalizedPeriod, normalizedDays),
                offlineSample
        );
        Optional<InvestmentReportVersionService.ReportDetail> reportDetail =
                reportVersionService.findLatestReportDetail(userId, normalizedTicker);
        List<WorkbenchCockpitResponse.TaskSummary> taskTimeline = researchTaskRepository
                .findByUserIdAndTickerOrderByCreatedAtDesc(
                        safeUserId(userId),
                        normalizedTicker,
                        PageRequest.of(0, TASK_LIMIT)
                )
                .stream()
                .map(this::toTaskSummary)
                .toList();
        if (taskTimeline.isEmpty()) {
            taskTimeline = offlineSample
                    .map(WorkbenchCockpitResponse::taskTimeline)
                    .orElse(List.of());
        }

        return new WorkbenchCockpitResponse(
                normalizedTicker,
                chartResult.chart(),
                chartResult.status(),
                chartResult.message(),
                reportDetail.map(this::toReportSummary)
                        .orElseGet(() -> offlineSample
                                .map(WorkbenchCockpitResponse::latestReport)
                                .orElse(null)),
                taskTimeline,
                reportDetail.map(this::toEvidencePreview)
                        .orElseGet(() -> offlineSample
                                .map(WorkbenchCockpitResponse::evidencePreview)
                                .orElse(List.of())),
                LocalDateTime.now()
        );
    }

    private Optional<WorkbenchCockpitResponse> loadOfflineSample(String ticker) {
        try {
            return offlineDemoSampleService.findCockpitSample(ticker);
        } catch (Exception e) {
            log.warn("Failed to load offline cockpit sample for {}: {}", ticker, e.getMessage());
            return Optional.empty();
        }
    }

    private ChartResult applyOfflineChartFallback(
            ChartResult liveChart,
            Optional<WorkbenchCockpitResponse> offlineSample
    ) {
        if (liveChart.chart() != null || offlineSample.isEmpty() || offlineSample.get().chart() == null) {
            return liveChart;
        }
        WorkbenchCockpitResponse sample = offlineSample.get();
        return new ChartResult(sample.chart(), sample.chartStatus(), sample.chartMessage());
    }

    public Map<String, Object> getIntradayChart(String ticker) {
        String norm = normalizeTicker(ticker);
        if (norm.isBlank() || !isLikelyUsTicker(norm)) {
            return Map.of("error", true, "message", "日内数据目前仅支持美股");
        }
        try {
            String raw = marketTools.getIbkrHistoricalBars(norm, INTRADAY_PERIOD, INTRADAY_BAR);
            Optional<Map<String, Object>> chart = kLinePayloadMapper.toChartPayload("getIbkrHistoricalBars", raw, new Object[]{norm, INTRADAY_PERIOD, INTRADAY_BAR});
            if (chart.isPresent()) {
                Map<String, Object> payload = new LinkedHashMap<>(chart.get());
                payload.put("period", "intraday");
                return payload;
            }
            Optional<String> toolErrorMessage = extractToolErrorMessage(raw);
            if (toolErrorMessage.isPresent()) {
                return Map.of("error", true, "message", "日内 K 线暂不可用：" + truncate(toolErrorMessage.get(), 180));
            }
            return Map.of("error", true, "message", "日内 K 线暂不可用");
        } catch (Exception e) {
            return Map.of("error", true, "message", "日内 K 线暂不可用：" + truncate(e.getMessage(), 180));
        }
    }

    private ChartResult loadChart(String ticker, String period, int days) {
        if (ticker.isBlank()) {
            return new ChartResult(null, "DEGRADED", "未选择标的。");
        }
        String sourceTool;
        Object[] args;
        Object rawResult;
        try {
            if (isLikelyUsTicker(ticker)) {
                sourceTool = "getIbkrHistoricalBars";
                args = new Object[]{ticker, "1y", "1d"};
                rawResult = marketTools.getIbkrHistoricalBars(ticker, "1y", "1d");
            } else {
                sourceTool = "getStockKLine";
                args = new Object[]{ticker, period, days};
                rawResult = marketTools.getStockKLine(ticker, period, days);
            }
        } catch (Exception e) {
            return new ChartResult(null, "DEGRADED", "K 线暂不可用：" + truncate(e.getMessage(), 180));
        }

        Optional<Map<String, Object>> chart = kLinePayloadMapper.toChartPayload(sourceTool, rawResult, args);
        if (chart.isPresent()) {
            return new ChartResult(chart.get(), "READY", "K 线数据已加载。");
        }
        Optional<String> toolErrorMessage = extractToolErrorMessage(rawResult);
        if (toolErrorMessage.isPresent()) {
            return new ChartResult(null, "DEGRADED", "K 线暂不可用：" + truncate(toolErrorMessage.get(), 180));
        }
        return chart
                .map(value -> new ChartResult(value, "READY", "K 线数据已加载。"))
                .orElseGet(() -> new ChartResult(null, "DEGRADED", "该标的暂无可用 K 线数据。"));
    }

    private Optional<String> extractToolErrorMessage(Object rawResult) {
        if (rawResult == null) {
            return Optional.empty();
        }
        try {
            JsonNode root = rawResult instanceof String text
                    ? objectMapper.readTree(text)
                    : objectMapper.valueToTree(rawResult);
            if (!root.path("error").asBoolean(false) && !root.path("data").path("error").asBoolean(false)) {
                return Optional.empty();
            }
            String message = firstText(root, "message", "errorMessage");
            if (message.isBlank()) {
                message = firstText(root.path("data"), "message", "errorMessage");
            }
            return message.isBlank() ? Optional.empty() : Optional.of(message);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private WorkbenchCockpitResponse.ReportSummary toReportSummary(InvestmentReportVersionService.ReportDetail detail) {
        InvestmentReportVersionSummary summary = detail.summary();
        return new WorkbenchCockpitResponse.ReportSummary(
                summary.id(),
                summary.conversationId(),
                summary.ticker(),
                summary.reportVersion(),
                summary.recommendation(),
                summary.dataSnapshotHash(),
                summary.contextHash(),
                summary.modelTier(),
                summary.modelName(),
                summary.generatedAt(),
                summary.createdAt(),
                summary.userQuery(),
                summary.preview(),
                detail.citations()
        );
    }

    private List<WorkbenchCockpitResponse.EvidencePreview> toEvidencePreview(
            InvestmentReportVersionService.ReportDetail detail
    ) {
        List<WorkbenchCockpitResponse.EvidencePreview> evidence = detail.evidenceItems().stream()
                .map(this::toEvidencePreview)
                .toList();
        if (!evidence.isEmpty()) {
            return evidence;
        }
        return detail.citations().stream()
                .map(citation -> new WorkbenchCockpitResponse.EvidencePreview(
                        "引用",
                        citation,
                        "沿用最新已保存报告版本中的引用。",
                        "报告"
                ))
                .toList();
    }

    private WorkbenchCockpitResponse.EvidencePreview toEvidencePreview(InvestmentReport.EvidenceItem item) {
        return new WorkbenchCockpitResponse.EvidencePreview(
                blankToDefault(item.getDimension(), "证据"),
                blankToDefault(item.getEvidence(), "暂无证据文本。"),
                blankToDefault(item.getImplication(), ""),
                blankToDefault(item.getSource(), "报告")
        );
    }

    private WorkbenchCockpitResponse.TaskSummary toTaskSummary(ResearchTask task) {
        return new WorkbenchCockpitResponse.TaskSummary(
                task.getId(),
                task.getConversationId(),
                task.getTicker(),
                task.getStatus() == null ? "" : task.getStatus().name(),
                task.getStage() == null ? "" : task.getStage().name(),
                task.getAttempts(),
                task.getResultReportVersionId(),
                task.getResultKind() == null ? null : task.getResultKind().name(),
                task.getStartedAt(),
                task.getHeartbeatAt(),
                task.getCompletedAt(),
                task.getCreatedAt(),
                task.getUpdatedAt(),
                task.getErrorMessage()
        );
    }

    private boolean isLikelyUsTicker(String ticker) {
        return ticker.matches("[A-Z]{1,5}");
    }

    private String normalizeTicker(String ticker) {
        return String.valueOf(ticker == null ? "" : ticker)
                .trim()
                .toUpperCase(Locale.ROOT);
    }

    private String normalizePeriod(String period) {
        String value = String.valueOf(period == null ? "" : period).trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "weekly", "monthly" -> value;
            default -> "daily";
        };
    }

    private int normalizeDays(int days) {
        if (days <= 0) {
            return DEFAULT_DAYS;
        }
        return Math.min(days, MAX_DAYS);
    }

    private String safeUserId(String userId) {
        return String.valueOf(userId == null ? "" : userId).trim();
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String firstText(JsonNode node, String... fields) {
        if (node == null || node.isMissingNode()) {
            return "";
        }
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (!value.isMissingNode() && !value.isNull()) {
                String text = value.asText("").trim();
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return "";
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }

    private record ChartResult(
            Map<String, Object> chart,
            String status,
            String message
    ) {
    }
}
