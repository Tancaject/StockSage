package com.stocksage.service;

import com.stocksage.research.InvestmentReportVersionService;

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

/**
 * 聚合工作台单标的驾驶舱数据。
 *
 * <p>一次调用组合实时/历史 K 线、用户最新报告、最近研究任务和证据预览：美股 K 线走
 * {@link MarketTools} 的只读 IBKR 路径，A/HK 走 Python data-service；报告和任务均按 userId 隔离。
 * 行情不可用时可使用 {@link OfflineDemoSampleService} 的明确 SAMPLE 数据，不会把样本标为实时成功。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkbenchCockpitService {

    /** 非美股 K 线未指定窗口时的默认天数。 */
    private static final int DEFAULT_DAYS = 120;
    /** 工作台 K 线最大天数，限制响应体和渲染成本。 */
    private static final int MAX_DAYS = 240;
    /** 驾驶舱展示的最近研究任务数。 */
    private static final int TASK_LIMIT = 5;
    /** IBKR 日内视图请求的历史窗口。 */
    private static final String INTRADAY_PERIOD = "24h";
    /** IBKR 日内视图请求的 K 线粒度。 */
    private static final String INTRADAY_BAR = "5min";

    /** 调用跨市场 K 线和 IBKR 只读行情工具。 */
    private final MarketTools marketTools;
    /** 读取用户隔离的最新报告详情。 */
    private final InvestmentReportVersionService reportVersionService;
    /** 查询该用户和 ticker 的最近任务时间线。 */
    private final ResearchTaskRepository researchTaskRepository;
    /** 把不同工具 JSON 统一为前端蜡烛图结构。 */
    private final KLinePayloadMapper kLinePayloadMapper;
    /** 外部服务失败时提供明确标记的离线样本。 */
    private final OfflineDemoSampleService offlineDemoSampleService;
    /** 从工具错误 JSON 中提取可读消息。 */
    private final ObjectMapper objectMapper;

    /**
     * 聚合工作台驾驶舱快照。
     *
     * @param userId 当前用户 ID，用于报告和任务隔离
     * @param ticker 目标股票代码
     * @param period 非美股 K 线周期
     * @param days 非美股回溯天数
     * @return 图表状态、最新报告、任务时间线、证据预览和服务器时间
     */
    public WorkbenchCockpitResponse getCockpit(String userId, String ticker, String period, int days) {
        String normalizedTicker = normalizeTicker(ticker);
        String normalizedPeriod = normalizePeriod(period);
        int normalizedDays = normalizeDays(days);

        Optional<WorkbenchCockpitResponse> offlineSample = loadOfflineSample(normalizedTicker);
        ChartResult chartResult = applyOfflineChartFallback(
                loadChart(normalizedTicker, normalizedPeriod, normalizedDays),
                offlineSample
        );
        // 调用报告版本服务按 userId+ticker 读取最新版本，避免跨用户报告泄漏。
        Optional<InvestmentReportVersionService.ReportDetail> reportDetail =
                reportVersionService.findLatestReportDetail(userId, normalizedTicker);
        // 调用任务仓储读取最近状态机快照，供前端展示 CREATED→COMPLETE/FAILED 进度。
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

    /** 尽力读取离线样本；样本文件异常不阻断正常驾驶舱。 */
    private Optional<WorkbenchCockpitResponse> loadOfflineSample(String ticker) {
        try {
            return offlineDemoSampleService.findCockpitSample(ticker);
        } catch (Exception e) {
            log.warn("Failed to load offline cockpit sample for {}: {}", ticker, e.getMessage());
            return Optional.empty();
        }
    }

    /** 仅在实时图表缺失时采用 SAMPLE 图表，并保留其显式状态消息。 */
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

    /**
     * 读取美股 24h/5min IBKR 日内图表。
     *
     * @param ticker 1 至 5 位美股 ticker
     * @return candlestick 载荷；不支持或 Gateway 失败时返回 error/message
     */
    public Map<String, Object> getIntradayChart(String ticker) {
        String norm = normalizeTicker(ticker);
        if (norm.isBlank() || !isLikelyUsTicker(norm)) {
            return Map.of("error", true, "message", "日内数据目前仅支持美股");
        }
        try {
            // 调用 MarketTools 的只读 IBKR history 工具，不触发任何交易操作。
            String raw = marketTools.getIbkrHistoricalBars(norm, INTRADAY_PERIOD, INTRADAY_BAR);
            Optional<Map<String, Object>> chart = kLinePayloadMapper.toChartPayload("getIbkrHistoricalBars", raw);
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

    /**
     * 按市场选择行情工具并统一映射图表；失败返回 DEGRADED 而不抛给页面。
     */
    private ChartResult loadChart(String ticker, String period, int days) {
        if (ticker.isBlank()) {
            return new ChartResult(null, "DEGRADED", "未选择标的。");
        }
        String sourceTool;
        Object rawResult;
        try {
            if (isLikelyUsTicker(ticker)) {
                sourceTool = "getIbkrHistoricalBars";
                // 美股调用只读 IBKR 历史端点，驾驶舱固定展示 1y/1d 日线。
                rawResult = marketTools.getIbkrHistoricalBars(ticker, "1y", "1d");
            } else {
                sourceTool = "getStockKLine";
                // A 股/港股调用 Python data-service 的跨市场 K 线工具。
                rawResult = marketTools.getStockKLine(ticker, period, days);
            }
        } catch (Exception e) {
            return new ChartResult(null, "DEGRADED", "K 线暂不可用：" + truncate(e.getMessage(), 180));
        }

        Optional<Map<String, Object>> chart = kLinePayloadMapper.toChartPayload(sourceTool, rawResult);
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

    /** 从顶层或 data 节点的工具错误载荷提取用户可读消息。 */
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

    /** 把完整报告详情裁剪成驾驶舱头部摘要。 */
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

    /** 优先展示结构化证据项；旧报告无证据表时用 citations 兼容降级。 */
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

    /** 将单条报告证据映射为工作台预览。 */
    private WorkbenchCockpitResponse.EvidencePreview toEvidencePreview(InvestmentReport.EvidenceItem item) {
        return new WorkbenchCockpitResponse.EvidencePreview(
                blankToDefault(item.getDimension(), "证据"),
                blankToDefault(item.getEvidence(), "暂无证据文本。"),
                blankToDefault(item.getImplication(), ""),
                blankToDefault(item.getSource(), "报告")
        );
    }

    /** 将完整任务实体映射为只读状态时间线条目。 */
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

    /** 用保守格式判断是否走当前 IBKR 美股路径。 */
    private boolean isLikelyUsTicker(String ticker) {
        return ticker.matches("[A-Z]{1,5}");
    }

    /** 去除首尾空白并大写 ticker。 */
    private String normalizeTicker(String ticker) {
        return String.valueOf(ticker == null ? "" : ticker)
                .trim()
                .toUpperCase(Locale.ROOT);
    }

    /** 只接受 daily/weekly/monthly，其他值回退 daily。 */
    private String normalizePeriod(String period) {
        String value = String.valueOf(period == null ? "" : period).trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "weekly", "monthly" -> value;
            default -> "daily";
        };
    }

    /** 将回溯天数收敛到默认值和 MAX_DAYS。 */
    private int normalizeDays(int days) {
        if (days <= 0) {
            return DEFAULT_DAYS;
        }
        return Math.min(days, MAX_DAYS);
    }

    /** null 安全归一化用户 ID。 */
    private String safeUserId(String userId) {
        return String.valueOf(userId == null ? "" : userId).trim();
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /** 按兼容字段顺序读取第一个非空 JSON 文本。 */
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

    /** 截断对前端展示的上游错误文本。 */
    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }

    /** 内部图表载荷及 READY/DEGRADED/SAMPLE 状态。 */
    private record ChartResult(
            Map<String, Object> chart,
            String status,
            String message
    ) {
    }
}
