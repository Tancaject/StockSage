package com.stocksage.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 深度研究管线中的可变交接对象。
 *
 * <p>分析师智能体填充前三份报告，多头/空头研究员追加辩论轮次，
 * {@code ResearchManager} 写入最终 {@link InvestmentReport}。
 * 显式保存状态可以让链路输出和兜底格式化更容易理解。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisState {

    /** 用户原始研究问题，贯穿分析师、辩论和最终报告。 */
    private String query;

    private String primaryTicker;

    private String dataSnapshotHash;

    private String contextHash;

    /** 基本面分析师输出的财报、公告和经营质量分析。 */
    private String fundamentalsReport;

    /** 市场分析师输出的行情、技术面、估值和账户相关观察。 */
    private String marketReport;

    /** 新闻分析师输出的近期新闻、政策和市场情绪信息。 */
    private String newsReport;

    /** 多头研究员最后一轮的论点（向后兼容字段，等价于 debateTurns 中最近一条 BULL）。 */
    private String bullThesis;

    /** 空头研究员最后一轮的论点（向后兼容字段，等价于 debateTurns 中最近一条 BEAR）。 */
    private String bearThesis;

    /** 研究经理生成的最终结构化投资研究报告。 */
    private InvestmentReport investmentReport;

    /**
     * 多空辩论的完整发言流水，按发生顺序追加。
     *
     * <p>每一条都是单方单轮的完整原文，下一轮的 Bull/Bear 可以读到对方全部历史发言，
     * 而不是只看到上一轮一段文字——这是消除"自说自话"的关键。</p>
     */
    @Builder.Default
    private List<DebateTurn> debateTurns = new ArrayList<>();

    /**
     * 多空辩论的轮次记录（向后兼容字段）。
     *
     * <p>新代码应该读 {@link #debateTurns}；该字段仅供历史下游消费者使用，
     * 内容由 {@code ResearchDebateService} 在写入 turn 时同步追加，格式与旧版保持一致。</p>
     */
    @Builder.Default
    private List<String> debateRounds = new ArrayList<>();

    /** 当前研究链路中收集到的引用或证据来源。 */
    @Builder.Default
    private List<String> citations = new ArrayList<>();

    /**
     * 辩论中的一条单方发言。
     *
     * @param round    辩论轮次，从 1 开始
     * @param side     发言方：BULL 或 BEAR
     * @param fullText 该轮该方的完整论证原文
     */
    public record DebateTurn(int round, Side side, String fullText) {

        /** 辩论立场。 */
        public enum Side {
            BULL, BEAR
        }
    }
}
