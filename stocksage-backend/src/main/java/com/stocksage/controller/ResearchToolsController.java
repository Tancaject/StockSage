package com.stocksage.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.stocksage.identity.RequestIdentity;
import com.stocksage.skill.SkillDefinition;
import com.stocksage.skill.SkillExecutionService;
import com.stocksage.skill.SkillRegistry;
import com.stocksage.tool.ResearchToolCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/research-tools")
@RequiredArgsConstructor
public class ResearchToolsController {
    private final RequestIdentity identity;
    private final ResearchToolCatalog tools;
    private final SkillRegistry skills;
    private final SkillExecutionService execution;

    @GetMapping
    public Map<String, Object> catalog() {
        identity.currentUserId();
        return Map.of("tools", tools.list(), "skills", skills.list().stream()
                .filter(SkillDefinition::enabled)
                .filter(skill -> skill.steps().stream().allMatch(step -> step.type() == SkillDefinition.StepType.TOOL)).toList());
    }

    @PostMapping("/{name}")
    public JsonNode execute(@PathVariable String name, @RequestBody JsonNode arguments) {
        identity.currentUserId();
        return execution.executeTool(name, arguments, Instant.now().plusSeconds(60));
    }

    @PostMapping("/skills/{id}")
    public Map<String, Object> skill(@PathVariable String id, @RequestBody JsonNode input) {
        identity.currentUserId();
        return execution.executeNamed(id, input);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("status", "INVALID_INPUT", "message", error.getMessage()));
    }

    @ExceptionHandler(com.stocksage.capability.CapabilityException.class)
    public ResponseEntity<Map<String, String>> capability(com.stocksage.capability.CapabilityException error) {
        int status = switch (error.reason()) {
            case TIMEOUT -> 504; case DENIED -> 403; case CAPACITY_EXCEEDED -> 429;
            case UNKNOWN -> 400; default -> 503;
        };
        return ResponseEntity.status(status).body(Map.of("status", error.reason().name(), "message", error.getMessage()));
    }

    @ExceptionHandler(com.stocksage.exception.ResearchBudgetExceededException.class)
    public ResponseEntity<Map<String, String>> budget(com.stocksage.exception.ResearchBudgetExceededException error) {
        return ResponseEntity.status(429).body(Map.of("status", "BUDGET_EXCEEDED", "message", error.getMessage()));
    }
}
