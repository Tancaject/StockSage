package com.stocksage.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 研究工作台单个标的的聚合驾驶舱响应。
 *
 * <p>服务层把行情图表、最新持久化报告、后台研究任务和证据预览合并成一次请求；
 * 数据源不可用时仍可返回降级状态或离线样例。</p>
 *
 * @param ticker 已归一化的证券代码
 * @param chart 前端 K 线组件可直接消费的结构化图表数据；不可用时为 null
 * @param chartStatus 图表状态，例如 READY、DEGRADED 或 SAMPLE
 * @param chartMessage 面向用户的图表加载或降级说明
 * @param latestReport 当前用户该标的的最新报告摘要；尚无报告时为 null
 * @param taskTimeline 最近的研究任务，按创建时间倒序，最多由服务层配置上限控制
 * @param evidencePreview 最新报告中的证据预览；无报告时通常为空列表
 * @param generatedAt 本次聚合响应生成时间
 */
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
    /**
     * 工作台顶部展示的最新报告摘要。
     *
     * @param id 报告版本主键；离线样例可为 null
     * @param conversationId 产生报告的会话主键；无持久化会话时可为 null
     * @param ticker 报告标的代码
     * @param reportVersion 同一用户、同一标的下递增的版本号；样例可为 null
     * @param recommendation 报告结论；内容受证据门禁约束
     * @param dataSnapshotHash 支撑报告的数据快照哈希，用于防止陈旧复用
     * @param contextHash 用户问题和数据上下文哈希，用于精确命中缓存
     * @param modelTier 模型能力层级，例如 FAST、STANDARD 或 STRONG
     * @param modelName 实际生成报告的模型名
     * @param generatedAt 报告内容生成时间
     * @param createdAt 版本记录写入数据库的时间
     * @param userQuery 触发报告的原始问题
     * @param preview 报告正文的短预览
     * @param citations 报告引用列表；无引用时为空列表
     */
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

    /**
     * 工作台时间线展示的一次后台深度研究任务。
     *
     * @param id 任务主键
     * @param conversationId 任务所属会话；尚未绑定时可为 null
     * @param ticker 研究标的代码
     * @param status 任务生命周期状态，取自 {@code ResearchTask.Status}
     * @param stage 当前或最终执行阶段，取自 {@code ResearchTask.Stage}
     * @param attempts 已启动的执行尝试次数
     * @param resultReportVersionId 成功产出的报告版本主键；未产出完整报告时为 null
     * @param resultKind 终态结果类型；任务未完成时可为 null
     * @param startedAt 最近一次尝试开始时间；尚未开始时为 null
     * @param heartbeatAt 最近一次持有者心跳时间，用于判断任务是否失联
     * @param completedAt 进入成功或失败终态的时间；运行中为 null
     * @param createdAt 任务创建时间
     * @param updatedAt 任务记录最近更新时间
     * @param errorMessage 最近的错误或重试原因；正常执行时为 null
     */
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

    /**
     * 工作台报告卡片展示的一条证据摘要。
     *
     * @param dimension 证据维度，例如基本面、市场或新闻
     * @param evidence 支撑事实或原文摘要
     * @param implication 该证据对投资结论的影响；未知时可为空
     * @param source 证据来源名称或引用标识
     */
    public record EvidencePreview(
            String dimension,
            String evidence,
            String implication,
            String source
    ) {
    }
}
