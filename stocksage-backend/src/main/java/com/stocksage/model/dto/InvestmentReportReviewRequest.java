package com.stocksage.model.dto;

import com.stocksage.model.entity.InvestmentReportVersion;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 人工审核投资报告时提交的状态变更请求。
 *
 * @param status 目标审核状态，不能为空；合法状态迁移由服务层校验
 * @param comment 审核说明，最多 2000 字符；拒绝或要求补充研究时业务层要求填写
 * @param expectedLockVersion 前端读取报告时得到的乐观锁版本，不能为空；过期值会触发冲突响应
 */
public record InvestmentReportReviewRequest(
        @NotNull InvestmentReportVersion.ReviewStatus status,
        @Size(max = 2000) String comment,
        @NotNull Long expectedLockVersion
) {
}
