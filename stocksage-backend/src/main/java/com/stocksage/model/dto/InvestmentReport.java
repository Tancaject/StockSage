package com.stocksage.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * ResearchManager 生成的结构化结果，随后由 ChatService 格式化成面向用户的 Markdown 回答。
 *
 * <p>字段围绕证据优先的投资推理设计：结论、理由、风险、支撑证据、适配人群、
 * 未知项和数据新鲜度。字段缺失是允许的，这样最终格式化器可以保留不确定性，
 * 而不是编造事实。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvestmentReport {

    private String ticker;
    private String dataSnapshotHash;
    private String contextHash;
    private Integer reportVersion;
    private String modelTier;
    private String modelName;
    private Boolean reusedFromCache;

    private ReportQualityStatus qualityStatus;
    private String completionPolicyId;
    private Integer completionPolicyVersion;

    private String recommendation;

    @Builder.Default
    private List<String> rationale = new ArrayList<>();

    @Builder.Default
    private List<String> riskFactors = new ArrayList<>();

    @Builder.Default
    private List<String> citations = new ArrayList<>();

    @Builder.Default
    private List<EvidenceItem> evidenceItems = new ArrayList<>();

    @Builder.Default
    private List<String> bullFactors = new ArrayList<>();

    @Builder.Default
    private List<String> bearFactors = new ArrayList<>();

    @Builder.Default
    private List<String> suitableFor = new ArrayList<>();

    @Builder.Default
    private List<String> notSuitableFor = new ArrayList<>();

    @Builder.Default
    private List<String> unknowns = new ArrayList<>();

    private String analystSummary;
    private String bullCase;
    private String bearCase;
    private String dataFreshness;

    @Builder.Default
    private LocalDateTime generatedAt = LocalDateTime.now();

    /**
     * 最终报告表中的一条可审计证据行。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EvidenceItem {
        private String dimension;
        private String evidence;
        private String implication;
        private String source;

        @Builder.Default
        private List<String> sourceEvidenceIds = new ArrayList<>();
    }

    public enum ReportQualityStatus {
        VERIFIED,
        NOT_RATED,
        OFFLINE_FALLBACK,
        LEGACY_UNVERIFIED
    }
}
