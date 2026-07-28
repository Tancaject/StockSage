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

@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class InvestmentReportController {

    private final InvestmentReportVersionService investmentReportVersionService;
    private final RequestIdentity requestIdentity;

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

    @GetMapping("/investment/{id}")
    public InvestmentReportVersionService.ReportDetail getInvestmentReport(
            @PathVariable Long id
    ) {
        return investmentReportVersionService.getReportDetail(
                requestIdentity.currentUserId(),
                id
        );
    }

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
