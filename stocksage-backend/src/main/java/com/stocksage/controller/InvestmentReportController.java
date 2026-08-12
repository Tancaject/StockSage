package com.stocksage.controller;

import com.stocksage.config.RequestIdentity;
import com.stocksage.model.dto.InvestmentReportReviewRequest;
import com.stocksage.model.dto.InvestmentReportVersionSummary;
import com.stocksage.service.InvestmentReportVersionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 当前用户投资报告版本的查询与人工审核接口。
 *
 * <p>报告按用户隔离并保留历史版本；审核只追加或更新审核记录，不改写模型生成的原始报告。</p>
 */
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class InvestmentReportController {

    /** 封装报告版本读取、归属校验和审核写入。 */
    private final InvestmentReportVersionService investmentReportVersionService;

    /** 从登录 Session 提供可信用户 ID。 */
    private final RequestIdentity requestIdentity;

    /**
     * 分页列出当前用户最近的报告版本。
     *
     * @param ticker 可选股票代码过滤条件
     * @param limit 最大返回条数，服务层会限制上限
     * @return 不含完整正文的报告版本摘要
     */
    @GetMapping("/investment")
    public List<InvestmentReportVersionSummary> listInvestmentReports(
            @RequestParam(required = false) String ticker,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return investmentReportVersionService.listReportVersions(
                requestIdentity.currentUserId(),
                ticker,
                limit
        );
    }

    /**
     * 获取单个报告版本的正文、证据与审核信息。
     *
     * @param id 报告版本数据库 ID
     * @return 属于当前用户的报告详情
     */
    @GetMapping("/investment/{id}")
    public InvestmentReportVersionService.ReportDetail getInvestmentReport(
            @PathVariable Long id
    ) {
        return investmentReportVersionService.getReportDetail(
                requestIdentity.currentUserId(),
                id
        );
    }

    /**
     * 保存当前用户对报告版本的审核结论。
     *
     * @param id 报告版本数据库 ID
     * @param request 审核状态、备注及客户端看到的版本信息
     * @return 带最新审核记录的报告详情
     */
    @PatchMapping("/investment/{id}/review")
    public InvestmentReportVersionService.ReportDetail reviewInvestmentReport(
            @PathVariable Long id,
            @Valid @RequestBody InvestmentReportReviewRequest request
    ) {
        return investmentReportVersionService.reviewReport(
                requestIdentity.currentUserId(),
                id,
                request
        );
    }
}
