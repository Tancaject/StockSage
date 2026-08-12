package com.stocksage.model.dto;

import com.stocksage.model.entity.InvestmentReportVersion;

import java.time.LocalDateTime;

/**
 * 报告详情接口返回的一条不可变审核历史。
 *
 * @param id 审核记录主键
 * @param reportVersionId 被审核的报告版本主键
 * @param fromStatus 变更前状态
 * @param toStatus 变更后状态
 * @param comment 审核说明；未填写时可为 null
 * @param reviewerUserId 执行审核的用户标识
 * @param createdAt 审核记录写入数据库的本地时间
 */
public record InvestmentReportReviewSummary(
        Long id,
        Long reportVersionId,
        InvestmentReportVersion.ReviewStatus fromStatus,
        InvestmentReportVersion.ReviewStatus toStatus,
        String comment,
        String reviewerUserId,
        LocalDateTime createdAt
) {
}
