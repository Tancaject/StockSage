package com.stocksage.controller;

import com.stocksage.model.dto.PlannerEvalRequest;
import com.stocksage.model.dto.PlannerEvalResponse;
import com.stocksage.service.PlannerEvalService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-protected Agent evaluation endpoints.
 */
@RestController
@RequestMapping("/api/eval/agent")
@RequiredArgsConstructor
public class AgentEvalController {

    private final PlannerEvalService plannerEvalService;

    @PostMapping("/planner")
    public PlannerEvalResponse evaluatePlanner(@Valid @RequestBody PlannerEvalRequest request) {
        return plannerEvalService.evaluate(request);
    }
}
