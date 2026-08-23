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

    /** 已归一化的研究标的代码。 */
    private String ticker;

    /** 证据快照哈希；相同值表示报告依据的数据集合未变化。 */
    private String dataSnapshotHash;

    /** 用户问题与数据快照的联合上下文哈希，用于严格限制缓存复用。 */
    private String contextHash;

    /** 同一用户、同一标的下从 1 递增的持久化版本号；未持久化时可为空。 */
    private Integer reportVersion;

    /** 生成报告时选择的模型能力层级。 */
    private String modelTier;

    /** 实际生成报告的模型名。 */
    private String modelName;

    /** 本次结果是否直接复用了已持久化且仍通过门禁的报告。 */
    private Boolean reusedFromCache;

    /** 报告是否通过证据与结构校验，决定能否缓存复用或写入研究记忆。 */
    private ReportQualityStatus qualityStatus;

    /** 验证本报告的完成策略稳定标识；旧报告可能为空。 */
    private String completionPolicyId;

    /** 完成策略版本号，用于策略升级后拒绝复用旧门禁结果。 */
    private Integer completionPolicyVersion;

    /** 研究经理给出的最终投资建议；证据不足时不应伪造明确结论。 */
    private String recommendation;

    /**
     * 投资建议适用的分析期限。
     *
     * <p>默认值保证旧版持久化 JSON 缺少该字段时仍能反序列化；新报告必须由
     * Research Manager 显式输出一个合法枚举值。</p>
     */
    @Builder.Default
    private AnalysisHorizon analysisHorizon = AnalysisHorizon.UNSPECIFIED;

    /** 支撑最终建议的核心理由。 */
    @Builder.Default
    private List<String> rationale = new ArrayList<>();

    /** 可能使结论失效或导致损失的主要风险。 */
    @Builder.Default
    private List<String> riskFactors = new ArrayList<>();

    /** 报告引用的来源标识或可展示引用文本。 */
    @Builder.Default
    private List<String> citations = new ArrayList<>();

    /** 按维度整理的可审计证据行。 */
    @Builder.Default
    private List<EvidenceItem> evidenceItems = new ArrayList<>();

    /** 多头论证中被最终报告采纳的要点。 */
    @Builder.Default
    private List<String> bullFactors = new ArrayList<>();

    /** 空头论证中被最终报告采纳的要点。 */
    @Builder.Default
    private List<String> bearFactors = new ArrayList<>();

    /** 可能适合采用该结论的投资者画像。 */
    @Builder.Default
    private List<String> suitableFor = new ArrayList<>();

    /** 不适合采用该结论的投资者画像。 */
    @Builder.Default
    private List<String> notSuitableFor = new ArrayList<>();

    /** 当前证据无法回答、需要后续补充研究的问题。 */
    @Builder.Default
    private List<String> unknowns = new ArrayList<>();

    /** 研究经理整合多方证据后的简要结论。 */
    private String analystSummary;

    /** 完整的看多情景论证。 */
    private String bullCase;

    /** 完整的看空情景论证。 */
    private String bearCase;

    /** 对行情、财报和新闻时效性的自然语言说明。 */
    private String dataFreshness;

    /** 报告内容生成时间；默认取当前本地时间。 */
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
        /** 证据所属维度，例如 fundamentals、market 或 news。 */
        private String dimension;

        /** 可核验的事实、数字或原文摘要。 */
        private String evidence;

        /** 该事实对投资判断的影响；不确定时可为空。 */
        private String implication;

        /** 面向用户展示的来源说明。 */
        private String source;

        /** 对应 {@code EvidenceLedger} 的证据 ID，供审计链路回查。 */
        @Builder.Default
        private List<String> sourceEvidenceIds = new ArrayList<>();
    }

    /** 报告经过当前完成策略后的可信度状态。 */
    public enum ReportQualityStatus {
        /** 已通过当前证据门禁和报告门禁，可在满足人工审核条件时复用。 */
        VERIFIED,

        /** 尚未完成门禁评级，不得当作已验证投资结论复用。 */
        NOT_RATED,

        /** 外部数据或模型不可用时生成的演示降级报告。 */
        OFFLINE_FALLBACK,

        /** 旧版本报告缺少当前策略元数据，因此按未验证处理。 */
        LEGACY_UNVERIFIED
    }
}
