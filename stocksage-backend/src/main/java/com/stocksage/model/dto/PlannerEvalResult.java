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
 * @param expectedFineIntent 可选的期望细粒度意图
 * @param actualFineIntent 实际细粒度意图；元数据缺失时为空字符串
 * @param fineIntentMatched 未提供期望值或实际值匹配时为 true
 * @param expectedDecisionSource 可选的期望路由来源
 * @param decisionSourceMatched 未提供期望值或实际值匹配时为 true
 * @param requireNoFallback 本例是否要求不得回退
 * @param noFallbackMatched 未要求禁止回退或确实未回退时为 true
 * @param routeMatched 最终执行路由是否命中期望
 * @param contextCase 是否携带最近对话上下文
 * @param fallback 是否实际使用确定性回退
 * @param executionGuarded 最终执行路由是否被服务器安全闸门覆盖；此时不拿原始语义路由与执行路由比较
 * @param rawRouteValid 原始路由是否可严格解析为后端五选一路由
 * @param rawRouteMatched 合法原始路由是否命中期望路由
 * @param expectedResolvedQueryContains 可选的期望消歧片段
 * @param actualResolvedQuery 执行计划实际使用的有界消歧问题
 * @param contextResolutionMatched 未提供期望片段或实际消歧问题包含该片段时为 true
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
        String fallbackReason,
        String expectedFineIntent,
        String actualFineIntent,
        boolean fineIntentMatched,
        String expectedDecisionSource,
        boolean decisionSourceMatched,
        boolean requireNoFallback,
        boolean noFallbackMatched,
        boolean routeMatched,
        boolean contextCase,
        boolean fallback,
        boolean executionGuarded,
        boolean rawRouteValid,
        boolean rawRouteMatched,
        String expectedResolvedQueryContains,
        String actualResolvedQuery,
        boolean contextResolutionMatched
) {
}
