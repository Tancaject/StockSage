package com.stocksage.model.dto;

import java.util.List;
import java.util.Map;

/**
 * 一批规划器评测样例的汇总与逐例结果。
 *
 * @param schemaVersion 响应结构版本，便于离线脚本兼容字段演进
 * @param mode 本次实际运行的规划器模式
 * @param status 全部样例通过时为 passed，否则为 failed
 * @param totalCases 样例总数
 * @param passedCases 同时满足路由、必需动作和禁止动作断言的样例数
 * @param criticalFailures 标记为关键且未通过的样例数
 * @param routeAccuracy 路由命中数占全部样例的比例，范围 0～1
 * @param macroF1 各路由 F1 的算术平均值，范围 0～1
 * @param perRoute 以路由枚举名为键的分类指标
 * @param requiredActionRecall 必需动作命中率；没有必需动作时为 1
 * @param forbiddenActionRate 禁止动作被错误规划的比例；没有禁止动作时为 0
 * @param executableRate 成功生成执行计划的样例比例，范围 0～1
 * @param durationMs 整批评测墙钟耗时，单位毫秒
 * @param checkedAt 评测完成时间的 ISO 本地日期时间字符串
 * @param results 与请求样例一一对应的详细结果
 */
public record PlannerEvalResponse(
        String schemaVersion,
        PlannerEvalMode mode,
        String status,
        int totalCases,
        int passedCases,
        int criticalFailures,
        double routeAccuracy,
        double macroF1,
        Map<String, RouteEvalMetrics> perRoute,
        double requiredActionRecall,
        double forbiddenActionRate,
        double executableRate,
        long durationMs,
        String checkedAt,
        List<PlannerEvalResult> results
) {
}
