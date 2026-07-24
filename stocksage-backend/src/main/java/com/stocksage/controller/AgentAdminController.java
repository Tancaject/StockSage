package com.stocksage.controller;

import com.stocksage.service.AgentAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/agent")
@RequiredArgsConstructor
public class AgentAdminController {

    private final AgentAdminService agentAdminService;

    @GetMapping("/skills")
    public AgentAdminService.SkillsSnapshot skills() {
        return agentAdminService.skills();
    }

    @GetMapping("/runtime")
    public AgentAdminService.RuntimeSnapshot runtime() {
        return agentAdminService.runtime();
    }
}
