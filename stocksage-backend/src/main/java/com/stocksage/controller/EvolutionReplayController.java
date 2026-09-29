package com.stocksage.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.stocksage.evolution.EvolutionReplayService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The existing /api/eval/** admin interceptor protects this profile-only endpoint. */
@RestController
@Profile("evolution-eval")
@RequestMapping("/api/eval/evolution")
@RequiredArgsConstructor
public class EvolutionReplayController {
    private final EvolutionReplayService service;

    @PostMapping("/replay")
    public EvolutionReplayService.Result replay(@RequestBody JsonNode request) {
        if (request == null || !request.isObject() || request.size() != 3
                || !request.path("caseId").isTextual() || !request.path("bundleId").isTextual()
                || !request.path("runId").isTextual()) {
            throw new IllegalArgumentException("进化回放只接受 caseId、bundleId、runId 三个文本字段；不能提交路径、提示词或模型参数。");
        }
        return service.replay(new EvolutionReplayService.Request(request.get("caseId").textValue(),
                request.get("bundleId").textValue(), request.get("runId").textValue()));
    }
}
