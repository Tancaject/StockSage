package com.stocksage.controller;

import com.stocksage.evolution.EvolutionFailurePool;
import com.stocksage.model.entity.EvolutionFailureCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 自进化失败池的分诊接口，位于管理令牌保护范围内。
 *
 * <p>evals 的 {@code evolution_failure_pool.py} 通过这些接口读取待分诊条目、写入分诊结论，
 * 并导出 METHOD 类失败作为学习素材与回归题。</p>
 */
@RestController
@RequestMapping("/api/admin/evolution/failures")
@RequiredArgsConstructor
public class EvolutionAdminController {

    private final EvolutionFailurePool failurePool;

    @GetMapping
    public List<EvolutionFailureCase> list(@RequestParam(required = false) EvolutionFailureCase.Status status,
                                           @RequestParam(required = false) EvolutionFailureCase.FailureType failureType,
                                           @RequestParam(defaultValue = "50") int limit) {
        return failurePool.list(status, failureType, limit);
    }

    /** 失败条目及其完整 Trace、最终回答。 */
    @GetMapping("/{id}")
    public EvolutionFailurePool.Detail detail(@PathVariable Long id) {
        return failurePool.detail(id);
    }

    @PostMapping("/{id}/triage")
    public EvolutionFailureCase triage(@PathVariable Long id, @Valid @RequestBody TriageRequest request) {
        return failurePool.triage(id, request.failureType(), request.note());
    }

    public record TriageRequest(@NotNull EvolutionFailureCase.FailureType failureType, @Size(max = 2000) String note) {
    }
}
