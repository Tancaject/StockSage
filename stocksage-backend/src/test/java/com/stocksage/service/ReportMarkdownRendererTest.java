package com.stocksage.service;

import com.stocksage.model.dto.AnalysisState;
import com.stocksage.model.dto.InvestmentReport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReportMarkdownRendererTest {

    private final ReportMarkdownRenderer renderer = new ReportMarkdownRenderer();

    @Test
    void normalizesFiveLevelRecommendationLabels() {
        assertThat(renderer.normalizeRecommendationLabel("BUY")).isEqualTo("积极买入（BUY）");
        assertThat(renderer.normalizeRecommendationLabel("overweight")).isEqualTo("偏积极（OVERWEIGHT）");
        assertThat(renderer.normalizeRecommendationLabel("UNDERWEIGHT")).isEqualTo("偏谨慎（UNDERWEIGHT）");
        assertThat(renderer.normalizeRecommendationLabel("SELL")).isEqualTo("明确卖出（SELL）");
        assertThat(renderer.normalizeRecommendationLabel(null)).isEqualTo("中性观察（HOLD）");
        assertThat(renderer.normalizeRecommendationLabel("nonsense")).isEqualTo("中性观察（HOLD）");
    }

    @Test
    void markdownCellEscapesPipesAndNewlinesAndFallsBack() {
        assertThat(renderer.markdownCell("a|b\nc", "fallback")).isEqualTo("a\\|b c");
        assertThat(renderer.markdownCell("  ", "fallback")).isEqualTo("fallback");
        assertThat(renderer.markdownCell(null, "fallback")).isEqualTo("fallback");
    }

    @Test
    void sanitizeReplacesInternalToolAndAgentNames() {
        String sanitized = renderer.sanitizeUserFacingText("Bull Researcher 调用 getStockKLine 与 webSearch");
        assertThat(sanitized).doesNotContain("Bull Researcher", "getStockKLine", "webSearch");
        assertThat(sanitized).contains("看多侧", "行情数据", "网页搜索");
    }

    @Test
    void reportWithoutInvestmentReportRendersEmpty() {
        AnalysisState state = AnalysisState.builder().build();
        assertThat(renderer.buildEvidenceFirstInvestmentReport(state)).isEmpty();
        assertThat(renderer.buildFinalAnswerBrief(state)).isEmpty();
        assertThat(renderer.buildEvidenceFirstInvestmentReport(null)).isEmpty();
    }

    @Test
    void evidenceFirstReportKeepsRequiredSectionsAndDisclaimer() {
        InvestmentReport report = InvestmentReport.builder()
                .ticker("NVDA")
                .recommendation("OVERWEIGHT")
                .analystSummary("盈利动能仍强")
                .rationale(List.of("数据中心需求"))
                .citations(List.of("SEC 10-K"))
                .build();
        AnalysisState state = AnalysisState.builder().build();
        state.setInvestmentReport(report);

        String answer = renderer.buildEvidenceFirstInvestmentReport(state);

        assertThat(answer).contains("## 投资结论", "## 核心依据", "## 证据表",
                "## 多空权衡", "## 适合 / 不适合", "## 风险与未知项", "## 数据来源与时间说明");
        assertThat(answer).contains("偏积极（OVERWEIGHT）");
        assertThat(answer).contains("不构成投资建议");
    }

    @Test
    void insufficientEvidenceReportListsGaps() {
        String unresolved = renderer.buildInsufficientEvidenceReport("", false, false, false);
        assertThat(unresolved).contains("无法评级（数据不足）", "未能可靠解析到具体股票标的", "本次询问的标的");

        String noData = renderer.buildInsufficientEvidenceReport("NVDA", true, false, false);
        assertThat(noData).contains("NVDA", "财务 / 财报数据源", "行情 / 技术指标数据源");
        assertThat(noData).doesNotContain("未能可靠解析到具体股票标的");
    }
}
