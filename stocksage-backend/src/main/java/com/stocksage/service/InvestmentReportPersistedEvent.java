package com.stocksage.service;

import com.stocksage.model.dto.InvestmentReport;
import com.stocksage.model.entity.InvestmentReportVersion;

/**
 * 投资报告版本成功持久化后的进程内领域事件。
 *
 * <p>{@link InvestmentReportVersionService} 发布该事件，研究记忆监听器可在事务完成后
 * 捕获结构化报告；事件不表示报告已被外部消息系统投递。</p>
 *
 * @param source 已落库的版本实体
 * @param report 与该版本对应的结构化报告 DTO
 */
public record InvestmentReportPersistedEvent(
        InvestmentReportVersion source,
        InvestmentReport report
) {
}
