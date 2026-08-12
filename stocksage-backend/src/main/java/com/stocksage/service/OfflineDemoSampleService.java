package com.stocksage.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.model.dto.AnalysisState;
import com.stocksage.model.dto.InvestmentReport;
import com.stocksage.model.dto.WorkbenchCockpitResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 在外部行情、SEC 或模型不可用时提供明确标记的离线演示样本。
 *
 * <p>{@link WorkbenchCockpitService} 用它回退工作台数据，{@link DeepResearchPipeline} 用它生成
 * OFFLINE_FALLBACK 报告。样本来自 classpath JSON，绝不伪装成实时数据，也不会写入正常报告缓存。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OfflineDemoSampleService {

    /** 离线报告暴露给前端的模型档位标识。 */
    public static final String MODEL_TIER = "DEMO";
    /** 离线报告暴露给前端的模型名称。 */
    public static final String MODEL_NAME = "offline-rule-fallback";

    /** 默认 classpath 样本文件。 */
    private static final String DEFAULT_RESOURCE_PATH = "demo/offline-stock-samples.json";
    /** 工作台状态字段，明确区分样本和实时成功。 */
    private static final String SAMPLE_STATUS = "SAMPLE";
    /** 提醒用户本次未访问任何实时外部服务。 */
    private static final String SAMPLE_MESSAGE =
            "已加载离线演示数据，未调用实时行情、SEC 与模型 API。";

    /** 读取样本 JSON 并深复制图表对象。 */
    private final ObjectMapper objectMapper;

    /** 是否允许离线样本降级。 */
    @Value("${stocksage.demo.offline-sample.enabled:true}")
    private boolean enabled = true;

    /** 可覆盖的 classpath 样本资源路径。 */
    @Value("${stocksage.demo.offline-sample.path:" + DEFAULT_RESOURCE_PATH + "}")
    private String sampleResourcePath = DEFAULT_RESOURCE_PATH;

    /** 延迟加载并安全发布的不可变样本快照。 */
    private volatile SampleData cachedData;

    /**
     * 查找工作台离线样本。
     *
     * @param ticker 股票代码
     * @return 已标记 SAMPLE 的工作台响应；关闭或无样本时为空
     */
    public Optional<WorkbenchCockpitResponse> findCockpitSample(String ticker) {
        if (!enabled) {
            return Optional.empty();
        }
        return findSampleStock(ticker).map(this::toCockpitResponse);
    }

    /**
     * 根据研究状态构造不含实时断言的离线回退报告。
     *
     * @param state 当前研究状态，用于读取 ticker 和用户查询
     * @return OFFLINE_FALLBACK 报告；关闭或无样本时为空
     */
    public Optional<InvestmentReport> buildFallbackReport(AnalysisState state) {
        if (!enabled) {
            return Optional.empty();
        }
        String ticker = normalizeTicker(state == null ? null : state.getPrimaryTicker());
        return findSampleStock(ticker).map(stock -> toInvestmentReport(stock, state));
    }

    /** 把单个样本转换为与正常工作台一致的响应结构。 */
    private WorkbenchCockpitResponse toCockpitResponse(SampleStock stock) {
        SampleReport report = stock.report();
        LocalDateTime generatedAt = parseTime(report.generatedAt());
        List<WorkbenchCockpitResponse.EvidencePreview> evidence = toEvidencePreview(report.evidenceItems());

        return new WorkbenchCockpitResponse(
                stock.ticker(),
                copyChart(stock.chart()),
                SAMPLE_STATUS,
                SAMPLE_MESSAGE,
                new WorkbenchCockpitResponse.ReportSummary(
                        null,
                        null,
                        stock.ticker(),
                        1,
                        report.recommendation(),
                        report.dataSnapshotHash(),
                        report.contextHash(),
                        MODEL_TIER,
                        MODEL_NAME,
                        generatedAt,
                        generatedAt,
                        report.userQuery(),
                        report.preview(),
                        safeList(report.citations())
                ),
                List.of(new WorkbenchCockpitResponse.TaskSummary(
                        null,
                        null,
                        stock.ticker(),
                        "SUCCEEDED",
                        "COMPLETE",
                        1,
                        null,
                        "OFFLINE_FALLBACK",
                        generatedAt,
                        generatedAt,
                        generatedAt,
                        generatedAt,
                        generatedAt,
                        null
                )),
                evidence,
                generatedAt
        );
    }

    /** 把样本报告映射为显式 OFFLINE_FALLBACK 的投资报告 DTO。 */
    private InvestmentReport toInvestmentReport(SampleStock stock, AnalysisState state) {
        SampleReport report = stock.report();
        String query = state == null || state.getQuery() == null || state.getQuery().isBlank()
                ? report.userQuery()
                : state.getQuery().trim();
        return InvestmentReport.builder()
                .ticker(stock.ticker())
                .qualityStatus(InvestmentReport.ReportQualityStatus.OFFLINE_FALLBACK)
                .recommendation(report.recommendation())
                .modelTier(MODEL_TIER)
                .modelName(MODEL_NAME)
                .rationale(safeList(report.rationale()))
                .riskFactors(safeList(report.riskFactors()))
                .citations(safeList(report.citations()))
                .evidenceItems(toReportEvidence(report.evidenceItems()))
                .bullFactors(safeList(report.bullFactors()))
                .bearFactors(safeList(report.bearFactors()))
                .suitableFor(safeList(report.suitableFor()))
                .notSuitableFor(safeList(report.notSuitableFor()))
                .unknowns(safeList(report.unknowns()))
                .analystSummary("离线演示回退报告（" + stock.ticker() + "）：" + report.preview())
                .bullCase(firstOrDefault(report.bullFactors(), report.preview()))
                .bearCase(firstOrDefault(report.bearFactors(), "离线样本未核验实时数据风险。"))
                .dataFreshness("离线样本数据集；本降级报告未调用实时行情、SEC 或 DashScope。查询：" + query)
                .generatedAt(parseTime(report.generatedAt()))
                .build();
    }

    /** 按归一化 ticker 查找样本。 */
    private Optional<SampleStock> findSampleStock(String ticker) {
        String normalizedTicker = normalizeTicker(ticker);
        if (normalizedTicker.isBlank()) {
            return Optional.empty();
        }
        return loadSamples().stocks().stream()
                .filter(stock -> normalizedTicker.equals(stock.ticker()))
                .findFirst();
    }

    /** 使用双重检查只加载一次 classpath 样本。 */
    private SampleData loadSamples() {
        SampleData data = cachedData;
        if (data != null) {
            return data;
        }
        synchronized (this) {
            if (cachedData != null) {
                return cachedData;
            }
            cachedData = readSamples();
            return cachedData;
        }
    }

    /** 读取并归一化样本文件；缺失或损坏时降级为空数据集。 */
    private SampleData readSamples() {
        ClassPathResource resource = new ClassPathResource(sampleResourcePath);
        try (InputStream stream = resource.getInputStream()) {
            SampleData data = objectMapper.readValue(stream, SampleData.class);
            return data == null || data.stocks() == null ? new SampleData(List.of()) : data.normalized();
        } catch (IOException e) {
            log.warn("Offline demo sample dataset unavailable at {}: {}", sampleResourcePath, e.getMessage());
            return new SampleData(List.of());
        }
    }

    /** 深复制图表 map，避免调用方修改进程内缓存。 */
    private Map<String, Object> copyChart(Map<String, Object> chart) {
        return objectMapper.convertValue(chart, new TypeReference<>() {
        });
    }

    /** 将样本证据转换为工作台预览条目。 */
    private List<WorkbenchCockpitResponse.EvidencePreview> toEvidencePreview(List<SampleEvidence> items) {
        return safeList(items).stream()
                .map(item -> new WorkbenchCockpitResponse.EvidencePreview(
                        blankToDefault(item.dimension(), "证据"),
                        blankToDefault(item.evidence(), "暂无证据文本。"),
                        blankToDefault(item.implication(), ""),
                        blankToDefault(item.source(), "离线样本")
                ))
                .toList();
    }

    /** 将样本证据转换为结构化报告证据项。 */
    private List<InvestmentReport.EvidenceItem> toReportEvidence(List<SampleEvidence> items) {
        return safeList(items).stream()
                .map(item -> InvestmentReport.EvidenceItem.builder()
                        .dimension(blankToDefault(item.dimension(), "证据"))
                        .evidence(blankToDefault(item.evidence(), "暂无证据文本。"))
                        .implication(blankToDefault(item.implication(), "仅用于演示的回退证据。"))
                        .source(blankToDefault(item.source(), "离线样本"))
                        .build())
                .toList();
    }

    /** 解析样本时间；缺失或非法时使用当前时间以保持响应结构完整。 */
    private LocalDateTime parseTime(String value) {
        try {
            return value == null || value.isBlank() ? LocalDateTime.now() : LocalDateTime.parse(value.trim());
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }

    /** 选择列表首个非空值。 */
    private String firstOrDefault(List<String> values, String fallback) {
        return safeList(values).stream()
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(fallback);
    }

    /** 归一化样本查找键。 */
    private String normalizeTicker(String ticker) {
        return String.valueOf(ticker == null ? "" : ticker)
                .trim()
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9.]", "");
    }

    /** 空白文本使用给定默认值。 */
    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    /** 把可空列表复制成只读列表。 */
    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    /** 样本文件根结构。 */
    private record SampleData(List<SampleStock> stocks) {
        private SampleData normalized() {
            return new SampleData(stocks.stream()
                    .map(SampleStock::normalized)
                    .toList());
        }
    }

    /** 单只股票的离线图表与报告。 */
    private record SampleStock(
            String ticker,
            Map<String, Object> chart,
            SampleReport report
    ) {
        private SampleStock normalized() {
            return new SampleStock(
                    ticker == null ? "" : ticker.trim().toUpperCase(Locale.ROOT),
                    chart == null ? Map.of() : chart,
                    report == null ? SampleReport.empty() : report
            );
        }
    }

    /** JSON 文件中可直接映射的离线报告字段。 */
    private record SampleReport(
            String recommendation,
            String dataSnapshotHash,
            String contextHash,
            String generatedAt,
            String userQuery,
            String preview,
            List<String> rationale,
            List<String> riskFactors,
            List<String> citations,
            List<SampleEvidence> evidenceItems,
            List<String> bullFactors,
            List<String> bearFactors,
            List<String> suitableFor,
            List<String> notSuitableFor,
            List<String> unknowns
    ) {
        private static SampleReport empty() {
            return new SampleReport(
                    "HOLD",
                    "0".repeat(64),
                    "0".repeat(64),
                    null,
                    "离线样本报告",
                    "离线样本报告。",
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of()
            );
        }
    }

    /** 离线报告的一条证据。 */
    private record SampleEvidence(
            String dimension,
            String evidence,
            String implication,
            String source
    ) {
    }
}
