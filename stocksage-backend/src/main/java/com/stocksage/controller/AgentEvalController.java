package com.stocksage.controller;

import com.stocksage.model.dto.PlannerEvalRequest;
import com.stocksage.model.dto.PlannerEvalResponse;
import com.stocksage.service.PlannerEvalService;
import com.stocksage.conversation.OrdinaryAnswerReplayService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 受管理令牌保护的 Agent 规划评测与普通文本回答回放接口。
 *
 * <p>离线脚本提交固定用例，本控制器交给 {@link PlannerEvalService} 执行并返回逐例结果；
 * 它不属于普通用户聊天链路。</p>
 */
@RestController
@RequestMapping("/api/eval/agent")
@RequiredArgsConstructor
public class AgentEvalController {

    /** 执行规则或真实 Coordinator 规划评测。 */
    private final PlannerEvalService plannerEvalService;
    private final OrdinaryAnswerReplayService ordinaryAnswerReplayService;

    /**
     * 批量评估 Coordinator 的路由和执行计划。
     *
     * @param request 评测模式及测试用例集合
     * @return 每条用例的预测结果与汇总指标
     */
    @PostMapping("/planner")
    public PlannerEvalResponse evaluatePlanner(@Valid @RequestBody PlannerEvalRequest request) {
        return plannerEvalService.evaluate(request);
    }

    /** 固定使用当前部署的模型配置；输入消息不进入用户会话或研究报告。 */
    @PostMapping("/answer")
    public OrdinaryAnswerReplayService.Result replayAnswer(
            @RequestBody OrdinaryAnswerReplayService.Request request) {
        return ordinaryAnswerReplayService.replay(request);
    }
}
