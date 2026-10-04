package com.stocksage.controller;

import com.stocksage.identity.RequestIdentity;
import com.stocksage.model.entity.AgentTrace;
import com.stocksage.trace.TraceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 对外提供单次 Agent 执行的可观测链路。
 *
 * <p>前端按时间线展示后端明确记录的路由、动作、工具观察和状态，不包含模型隐藏思维链。
 * 查询同时绑定当前用户，避免通过 traceId 读取他人的研究过程。</p>
 */
@RestController
@RequestMapping("/api/trace")
@RequiredArgsConstructor
public class TraceController {

    /** 按用户归属读取已持久化追踪。 */
    private final TraceService traceService;

    /** 从登录 Session 解析当前用户 ID。 */
    private final RequestIdentity requestIdentity;

    /**
     * 根据 traceId 获取完整的智能体调用链路。
     *
     * <p>返回的 steps 字段是 JSON 数组，每个元素对应一个 AgentStep，
     * 前端据此渲染“思考-动作-观察”的时间线。</p>
     *
     * @param traceId 对话流返回的链路标识
     * @return 属于当前用户的完整可观测链路
     */
    @GetMapping("/{traceId}")
    public AgentTrace getTrace(@PathVariable String traceId) {
        // TraceService 同时校验 traceId 与当前用户归属，未命中统一按 404 处理。
        return traceService.getTraceForUser(traceId, requestIdentity.currentUserId());
    }
}
