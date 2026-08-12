package com.stocksage.model.dto;

import com.stocksage.model.entity.InvestmentReportVersion;

import java.time.LocalDateTime;

/**
 * 报告历史和详情接口共用的版本元数据。
 *
 * @param id 报告版本数据库主键
 * @param conversationId 产生报告的会话主键；无关联会话时可为 null
 * @param ticker 已归一化的证券代码
 * @param reportVersion 同一用户、同一 ticker 下从 1 递增的业务版本号
 * @param recommendation 报告的最终建议摘要
 * @param dataSnapshotHash 证据快照哈希，用于判断底层数据是否变化
 * @param contextHash 问题与数据上下文哈希，用于限制报告缓存复用
 * @param modelTier 生成时选择的模型能力层级；历史数据可能为空
 * @param modelName 实际生成报告的模型名；历史数据可能为空
 * @param generatedAt 报告内容生成时间
 * @param createdAt 版本记录写入数据库的时间
 * @param userQuery 触发本报告的原始问题
 * @param preview 从结构化报告生成的短预览
 * @param reviewStatus 当前人工审核状态；旧记录由服务层视为 DRAFT
 * @param reviewerUserId 最近一次审核用户；尚未审核时为 null
 * @param reviewComment 最近一次审核说明；未填写时为 null
 * @param reviewedAt 最近一次审核时间；尚未审核时为 null
 * @param updatedAt 报告或审核元数据最近更新时间
 * @param lockVersion JPA 乐观锁版本，客户端更新审核状态时必须原样回传
 */
public record InvestmentReportVersionSummary(
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
        InvestmentReportVersion.ReviewStatus reviewStatus,
        String reviewerUserId,
        String reviewComment,
        LocalDateTime reviewedAt,
        LocalDateTime updatedAt,
        Long lockVersion
) {
    /**
     * 兼容仅包含报告生成元数据的旧调用路径。
     *
     * <p>该构造器把审核状态初始化为 DRAFT、乐观锁版本初始化为 0，
     * 不代表数据库中已经发生过人工审核。</p>
     *
     * @param id 报告版本主键
     * @param conversationId 关联会话主键
     * @param ticker 证券代码
     * @param reportVersion 业务版本号
     * @param recommendation 报告建议
     * @param dataSnapshotHash 数据快照哈希
     * @param contextHash 上下文哈希
     * @param modelTier 模型能力层级
     * @param modelName 实际模型名
     * @param generatedAt 内容生成时间
     * @param createdAt 数据库创建时间
     * @param userQuery 原始问题
     * @param preview 报告预览
     */
    public InvestmentReportVersionSummary(
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
            String preview
    ) {
        this(
                id,
                conversationId,
                ticker,
                reportVersion,
                recommendation,
                dataSnapshotHash,
                contextHash,
                modelTier,
                modelName,
                generatedAt,
                createdAt,
                userQuery,
                preview,
                InvestmentReportVersion.ReviewStatus.DRAFT,
                null,
                null,
                null,
                createdAt,
                0L
        );
    }
}
