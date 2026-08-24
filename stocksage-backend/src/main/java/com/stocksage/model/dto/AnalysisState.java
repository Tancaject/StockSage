package com.stocksage.model.dto;

import com.stocksage.harness.EvidenceLedger;
import com.stocksage.harness.HarnessModels.HarnessSnapshot;
import com.stocksage.model.dto.DebateModels.DebateTurn;
import com.stocksage.model.dto.DebateModels.DebateVerdict;
import com.stocksage.model.dto.DebateModels.ManagerAssessment;
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

    /** 本轮研究的主证券代码；哈希、报告版本和工具预取都以它为标的。 */
    private String primaryTicker;

    /** 结构化证据集合的稳定哈希，用于判断数据快照是否变化。 */
    private String dataSnapshotHash;

    /** 用户问题与数据快照的联合哈希，用于严格控制报告缓存复用。 */
    private String contextHash;

    /** 基本面分析师输出的财报、公告和经营质量分析。 */
    private String fundamentalsReport;

    /** 市场分析师输出的行情、技术面、估值和账户相关观察。 */
    private String marketReport;

    /** 新闻分析师输出的近期新闻、政策和市场情绪信息。 */
    private String newsReport;

    /** 研究经理生成的最终结构化投资研究报告。 */
    private InvestmentReport investmentReport;

    /**
     * 多空辩论的完整发言流水，按发生顺序追加。
     *
     * <p>每一条只保存经过后端契约校验的结构化论点。下一轮 Bull/Bear 读取这些带稳定 ID 的
     * 论点并显式引用反驳目标，避免自由文本辩论无法核验或恢复。</p>
     */
    @Builder.Default
    private List<DebateTurn> debateTurns = new ArrayList<>();

    /** Research Manager 对当前结构化论点的逐项语义评分；缺失时不得直接生成评级报告。 */
    private ManagerAssessment managerAssessment;

    /** Java 确定性策略依据评分、证据和时效性计算出的锁定裁决。 */
    private DebateVerdict debateVerdict;

    /** 当前研究链路中收集到的引用或证据来源。 */
    @Builder.Default
    private List<String> citations = new ArrayList<>();

    /**
     * 本轮研究的结构化证据元数据账本。
     *
     * <p>只保存来源、状态、时间和哈希，不重复保存工具正文。默认空账本确保旧 checkpoint JSON
     * 在缺少该字段时仍可反序列化。</p>
     */
    @Builder.Default
    private EvidenceLedger evidenceLedger = EvidenceLedger.empty();

    /** 最近一次持久化的完成策略决策及有界恢复计数，供任务接管后继续执行。 */
    private HarnessSnapshot harnessSnapshot;

}
