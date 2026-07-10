package com.stocksage.controller;

import com.stocksage.config.RequestIdentity;
import com.stocksage.model.entity.AgentTrace;
import com.stocksage.trace.TraceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 链路追踪控制器 —— 可观测性功能的对外接口。
 *
 * 每次用户提问，智能体会产生一条完整的链路（包含所有推理步骤）。
 * 前端的"查看推理链路"功能通过此接口获取链路数据，
 * 然后用时间线界面渲染出智能体的决策过程。
 *
 * 这是本项目的差异化亮点——大多数智能体项目是黑盒，
 * 而我们能完整展示每一步思考 → 动作 → 观察的细节。
 */
@RestController
@RequestMapping("/api/trace")
@RequiredArgsConstructor
public class TraceController {

    private final TraceService traceService;
    private final RequestIdentity requestIdentity;

    /**
     * 根据 traceId 获取完整的智能体调用链路。
     *
     * <p>返回的 steps 字段是 JSON 数组，每个元素对应一个 AgentStep，
     * 前端据此渲染“思考-动作-观察”的时间线。</p>
     */
    @GetMapping("/{traceId}")
    public AgentTrace getTrace(@PathVariable String traceId) {
        return traceService.getTraceForUser(traceId, requestIdentity.currentUserId());
    }
}
