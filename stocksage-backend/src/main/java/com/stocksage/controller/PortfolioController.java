package com.stocksage.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.stocksage.workbench.PortfolioAnalysisService;
import org.springframework.web.bind.annotation.*;

/** 无持久化、无券商账户访问；沿用 /api 的登录与 CSRF 保护。 */
@RestController
@RequestMapping("/api/workbench/portfolio")
public class PortfolioController {
    private final PortfolioAnalysisService service;
    public PortfolioController(PortfolioAnalysisService service) { this.service = service; }
    @PostMapping("/analysis")
    public JsonNode analyze(@RequestBody JsonNode input) { return service.analyzePortfolio(input); }
    @PostMapping("/etf-lookthrough")
    public JsonNode lookThrough(@RequestBody JsonNode input) { return service.lookThroughEtfs(input); }
    @PostMapping("/screen")
    public JsonNode screen(@RequestBody JsonNode input) { return service.screenStocks(input); }
}
