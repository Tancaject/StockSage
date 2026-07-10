package com.stocksage.controller;

import com.stocksage.config.RequestIdentity;
import com.stocksage.model.dto.InvestmentReportVersionSummary;
import com.stocksage.service.InvestmentReportVersionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
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
}
