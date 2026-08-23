package com.stocksage.model.dto;

import java.util.List;
import java.util.Map;

/**
 * 一批规划器评测样例的汇总与逐例结果。
 *
 * @param schemaVersion 响应结构版本，便于离线脚本兼容字段演进
 * @param mode 本次实际运行的规划器模式
 * @param status 全部样例通过时为 passed，否则为 failed
 * @param requestedCases 请求提交的样例总数，包括 DETERMINISTIC 无法评估而跳过的 live-only 样例
 * @param totalCases 实际评估的样例总数
 * @param skippedLiveOnlyCases DETERMINISTIC 模式跳过的上下文或严格来源样例数
 * @param passedCases 满足路由、动作及已提供 V2 可选断言的样例数
 * @param criticalFailures 标记为关键且未通过的样例数
 * @param routeAccuracy 路由命中数占全部样例的比例，范围 0～1
 * @param macroF1 各路由 F1 的算术平均值，范围 0～1
 * @param perRoute 以路由枚举名为键的分类指标
 * @param requiredActionRecall 必需动作命中率；没有必需动作时为 1
 * @param forbiddenActionRate 禁止动作被错误规划的比例；没有禁止动作时为 0
 * @param executableRate 成功生成执行计划的样例比例，范围 0～1
 * @param intentEvaluatedCases 提供 expectedFineIntent 的已评估样例数
 * @param intentAccuracy 细粒度意图准确率；没有可评估样例时为 0
 * @param contextCases 携带 recentTurns 的已评估样例数
 * @param contextCaseAccuracy 上下文样例最终路由准确率；没有上下文样例时为 0
 * @param contextResolutionCases 提供 expectedResolvedQueryContains 的已评估样例数
 * @param contextResolutionAccuracy 上下文消歧片段命中率；没有可评估样例时为 0
 * @param nonFallbackCases 未使用确定性回退的已评估样例数
 * @param nonFallbackRouteAccuracy 非回退样例最终路由准确率；没有此类样例时为 0
 * @param llmSignalCases 具有合法原始 LLM 路由候选的非回退样例数
 * @param llmSignalAccuracy 原始 LLM 路由候选准确率；没有此类样例时为 0
 * @param fallbackCases 使用确定性回退的样例数
 * @param fallbackRate 回退样例占全部已评估样例的比例
 * @param invalidRawRouteCases 原始路由为空或无法严格解析的样例数
 * @param invalidRawRouteRate 非法原始路由占全部已评估样例的比例
 * @param durationMs 整批评测墙钟耗时，单位毫秒
 * @param checkedAt 评测完成时间的 ISO 本地日期时间字符串
 * @param results 与实际评估样例一一对应的详细结果；跳过样例只计入 skippedLiveOnlyCases
 */
public record PlannerEvalResponse(
        String schemaVersion,
        PlannerEvalMode mode,
        String status,
        int requestedCases,
        int totalCases,
        int skippedLiveOnlyCases,
        int passedCases,
        int criticalFailures,
        double routeAccuracy,
        double macroF1,
        Map<String, RouteEvalMetrics> perRoute,
        double requiredActionRecall,
        double forbiddenActionRate,
        double executableRate,
        int intentEvaluatedCases,
        double intentAccuracy,
        int contextCases,
        double contextCaseAccuracy,
        int contextResolutionCases,
        double contextResolutionAccuracy,
        int nonFallbackCases,
        double nonFallbackRouteAccuracy,
        int llmSignalCases,
        double llmSignalAccuracy,
        int fallbackCases,
        double fallbackRate,
        int invalidRawRouteCases,
        double invalidRawRouteRate,
        long durationMs,
        String checkedAt,
        List<PlannerEvalResult> results
) {
    /** 保留 V1 汇总构造方式，迁移期的控制器和测试夹具无需一次性补齐新指标。 */
    public PlannerEvalResponse(
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
        this(schemaVersion, mode, status, totalCases, totalCases, 0, passedCases, criticalFailures,
                routeAccuracy, macroF1, perRoute, requiredActionRecall, forbiddenActionRate,
                executableRate, 0, 0.0, 0, 0.0, 0, 0.0, 0, 0.0, 0, 0.0,
                0, 0.0, 0, 0.0, durationMs, checkedAt, results);
    }
}
