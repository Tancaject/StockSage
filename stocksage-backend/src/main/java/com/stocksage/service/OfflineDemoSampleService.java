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

@Slf4j
@Service
@RequiredArgsConstructor
public class OfflineDemoSampleService {

    public static final String MODEL_TIER = "DEMO";
    public static final String MODEL_NAME = "offline-rule-fallback";

    private static final String DEFAULT_RESOURCE_PATH = "demo/offline-stock-samples.json";
    private static final String SAMPLE_STATUS = "SAMPLE";
    private static final String SAMPLE_MESSAGE =
            "已加载离线演示数据，未调用实时行情、SEC 与模型 API。";

    private final ObjectMapper objectMapper;

    @Value("${stocksage.demo.offline-sample.enabled:true}")
    private boolean enabled = true;

    @Value("${stocksage.demo.offline-sample.path:" + DEFAULT_RESOURCE_PATH + "}")
    private String sampleResourcePath = DEFAULT_RESOURCE_PATH;

    private volatile SampleData cachedData;

    public Optional<WorkbenchCockpitResponse> findCockpitSample(String ticker) {
        if (!enabled) {
            return Optional.empty();
        }
        return findSampleStock(ticker).map(this::toCockpitResponse);
    }

    public Optional<InvestmentReport> buildFallbackReport(AnalysisState state) {
        if (!enabled) {
            return Optional.empty();
        }
        String ticker = normalizeTicker(state == null ? null : state.getPrimaryTicker());
        return findSampleStock(ticker).map(stock -> toInvestmentReport(stock, state));
    }

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

    private Optional<SampleStock> findSampleStock(String ticker) {
        String normalizedTicker = normalizeTicker(ticker);
        if (normalizedTicker.isBlank()) {
            return Optional.empty();
        }
        return loadSamples().stocks().stream()
                .filter(stock -> normalizedTicker.equals(stock.ticker()))
                .findFirst();
    }

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

    private Map<String, Object> copyChart(Map<String, Object> chart) {
        return objectMapper.convertValue(chart, new TypeReference<>() {
        });
    }

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

    private LocalDateTime parseTime(String value) {
        try {
            return value == null || value.isBlank() ? LocalDateTime.now() : LocalDateTime.parse(value.trim());
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }

    private String firstOrDefault(List<String> values, String fallback) {
        return safeList(values).stream()
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(fallback);
    }

    private String normalizeTicker(String ticker) {
        return String.valueOf(ticker == null ? "" : ticker)
                .trim()
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9.]", "");
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private record SampleData(List<SampleStock> stocks) {
        private SampleData normalized() {
            return new SampleData(stocks.stream()
                    .map(SampleStock::normalized)
                    .toList());
        }
    }

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

    private record SampleEvidence(
            String dimension,
            String evidence,
            String implication,
            String source
    ) {
    }
}
