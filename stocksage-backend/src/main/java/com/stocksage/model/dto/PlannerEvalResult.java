package com.stocksage.model.dto;

import com.stocksage.agent.PlanAction;
import com.stocksage.agent.PlanRoute;

import java.util.List;

/**
 * 单条规划器样例的实际执行和断言结果。
 *
 * @param id 对应 {@link PlannerEvalCase#id()} 的样例标识
 * @param expectedRoute 样例期望路由
 * @param actualRoute 实际路由；规划器执行异常时为 null
 * @param plannedActions 实际执行计划中的动作，保持规划顺序
 * @param missingRequiredActions 未出现在计划中的必需动作
 * @param matchedForbiddenActions 错误出现在计划中的禁止动作
 * @param critical 样例是否属于关键门禁
 * @param passed 路由、必需动作和禁止动作是否全部符合预期
 * @param executable 是否成功生成了执行计划
 * @param durationMs 单条样例墙钟耗时，单位毫秒
 * @param errorCode 失败分类；执行成功时为空字符串
 * @param decisionSource 路由决定来源，例如规则、模型或回退路径
 * @param rawRoute 模型返回或路由器解析前保留的原始路由文本
 * @param intentSummary 协调器提取的用户意图摘要
 * @param rationale 路由决定理由
 * @param confidence 路由置信度，通常范围 0～1；无元数据时为 0
 * @param fallbackReason 使用回退路由时的原因；未回退时为空字符串
 */
public record PlannerEvalResult(
        String id,
        PlanRoute expectedRoute,
        PlanRoute actualRoute,
        List<PlanAction> plannedActions,
        List<PlanAction> missingRequiredActions,
        List<PlanAction> matchedForbiddenActions,
        boolean critical,
        boolean passed,
        boolean executable,
        long durationMs,
        String errorCode,
        String decisionSource,
        String rawRoute,
        String intentSummary,
        String rationale,
        double confidence,
        String fallbackReason
) {
}
