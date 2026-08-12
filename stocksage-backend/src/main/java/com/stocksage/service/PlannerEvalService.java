package com.stocksage.service;

import com.stocksage.agent.Coordinator;
import com.stocksage.agent.ExecutionPlan;
import com.stocksage.agent.PlanAction;
import com.stocksage.agent.PlanRoute;
import com.stocksage.agent.RoutingDecisionMetadata;
import com.stocksage.model.dto.PlannerEvalCase;
import com.stocksage.model.dto.PlannerEvalMode;
import com.stocksage.model.dto.PlannerEvalRequest;
import com.stocksage.model.dto.PlannerEvalResponse;
import com.stocksage.model.dto.PlannerEvalResult;
import com.stocksage.model.dto.RouteEvalMetrics;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 对 Coordinator 路由计划执行类型化回归评估。
 *
 * <p>评估接口和离线 Golden Set runner 调用本服务；它比较期望 route、必需/禁止 action，
 * 并计算逐路由 precision、recall、F1。该服务不执行任何行情工具或 Agent。</p>
 */
@Service
public class PlannerEvalService {

    /** 评估响应结构版本，供离线脚本做兼容检查。 */
    public static final String SCHEMA_VERSION = "planner_eval_v1";
    /** 被测的计划器入口。 */
    private final Coordinator coordinator;

    /** @param coordinator 被测 Coordinator。 */
    public PlannerEvalService(Coordinator coordinator) {
        this.coordinator = coordinator;
    }

    /**
     * 运行请求中的全部路由用例并汇总指标。
     *
     * @param request 评估模式和用例集合
     * @return 包含逐用例结果、宏 F1 和动作约束指标的稳定响应
     */
    public PlannerEvalResponse evaluate(PlannerEvalRequest request) {
        long startedAt = System.currentTimeMillis();
        List<PlannerEvalResult> results = request.cases().stream()
                .map(evalCase -> evaluateCase(request.mode(), evalCase))
                .toList();

        int total = results.size();
        int passed = (int) results.stream().filter(PlannerEvalResult::passed).count();
        int criticalFailures = (int) results.stream()
                .filter(result -> result.critical() && !result.passed())
                .count();
        int executable = (int) results.stream().filter(PlannerEvalResult::executable).count();
        int routeMatches = (int) results.stream()
                .filter(result -> result.executable() && result.expectedRoute() == result.actualRoute())
                .count();
        long requiredTotal = request.cases().stream().mapToLong(item -> item.requiredActions().size()).sum();
        long requiredMissing = results.stream().mapToLong(item -> item.missingRequiredActions().size()).sum();
        long forbiddenTotal = request.cases().stream().mapToLong(item -> item.forbiddenActions().size()).sum();
        long forbiddenMatched = results.stream().mapToLong(item -> item.matchedForbiddenActions().size()).sum();
        Map<String, RouteEvalMetrics> perRoute = perRouteMetrics(results);
        double macroF1 = perRoute.values().stream()
                .mapToDouble(RouteEvalMetrics::f1)
                .average()
                .orElse(0.0);

        String status;
        status = passed == total ? "passed" : "failed";
        return new PlannerEvalResponse(
                SCHEMA_VERSION,
                request.mode(),
                status,
                total,
                passed,
                criticalFailures,
                ratio(routeMatches, total),
                macroF1,
                perRoute,
                requiredTotal == 0 ? 1.0 : ratio(requiredTotal - requiredMissing, requiredTotal),
                forbiddenTotal == 0 ? 0.0 : ratio(forbiddenMatched, forbiddenTotal),
                ratio(executable, total),
                System.currentTimeMillis() - startedAt,
                LocalDateTime.now().toString(),
                results
        );
    }

    /** 评估单个查询；计划器异常会被记录为不可执行失败而不中断整批。 */
    private PlannerEvalResult evaluateCase(PlannerEvalMode mode, PlannerEvalCase evalCase) {
        long startedAt = System.currentTimeMillis();
        try {
            // 根据评估模式调用确定性规则或完整 Coordinator 计划入口，不执行计划中的动作。
            ExecutionPlan plan = mode == PlannerEvalMode.DETERMINISTIC
                    ? coordinator.planDeterministically(evalCase.query(), evalCase.ragHitCount())
                    : coordinator.plan(evalCase.query(), evalCase.ragHitCount());
            List<PlanAction> planned = plan.actions() == null ? List.of() : List.copyOf(plan.actions());
            List<PlanAction> missing = evalCase.requiredActions().stream()
                    .filter(action -> !planned.contains(action))
                    .toList();
            List<PlanAction> forbidden = evalCase.forbiddenActions().stream()
                    .filter(planned::contains)
                    .toList();
            boolean passed = plan.route() == evalCase.expectedRoute()
                    && missing.isEmpty()
                    && forbidden.isEmpty();
            RoutingDecisionMetadata routing = plan.routingDecision();
            return new PlannerEvalResult(
                    evalCase.id(), evalCase.expectedRoute(), plan.route(), planned,
                    missing, forbidden, evalCase.critical(), passed, true,
                    System.currentTimeMillis() - startedAt, "",
                    routing == null ? "" : routing.decisionSource().name(),
                    routing == null ? "" : routing.rawRoute(),
                    routing == null ? "" : routing.intentSummary(),
                    routing == null ? "" : routing.rationale(),
                    routing == null ? 0.0 : routing.confidence(),
                    routing == null ? "" : routing.fallbackReason()
            );
        } catch (Exception ignored) {
            return new PlannerEvalResult(
                    evalCase.id(), evalCase.expectedRoute(), null, List.of(),
                    evalCase.requiredActions(), List.of(), evalCase.critical(),
                    false, false, System.currentTimeMillis() - startedAt,
                    "PLANNER_EXECUTION_FAILED", "", "", "", "", 0.0, ""
            );
        }
    }

    /** 按 one-vs-rest 口径计算每个 route 的 precision、recall 和 F1。 */
    private Map<String, RouteEvalMetrics> perRouteMetrics(List<PlannerEvalResult> results) {
        Set<PlanRoute> labels = new LinkedHashSet<>();
        results.stream().map(PlannerEvalResult::expectedRoute).filter(java.util.Objects::nonNull).forEach(labels::add);
        results.stream().map(PlannerEvalResult::actualRoute).filter(java.util.Objects::nonNull).forEach(labels::add);
        Map<String, RouteEvalMetrics> metrics = new LinkedHashMap<>();
        for (PlanRoute label : labels) {
            long truePositive = results.stream()
                    .filter(result -> result.expectedRoute() == label && result.actualRoute() == label)
                    .count();
            long falsePositive = results.stream()
                    .filter(result -> result.expectedRoute() != label && result.actualRoute() == label)
                    .count();
            long falseNegative = results.stream()
                    .filter(result -> result.expectedRoute() == label && result.actualRoute() != label)
                    .count();
            int expectedCount = (int) (truePositive + falseNegative);
            int predictedCount = (int) (truePositive + falsePositive);
            double precision = ratio(truePositive, truePositive + falsePositive);
            double recall = ratio(truePositive, truePositive + falseNegative);
            double f1 = precision + recall == 0.0
                    ? 0.0
                    : 2.0 * precision * recall / (precision + recall);
            metrics.put(label.name(), new RouteEvalMetrics(
                    precision, recall, f1, expectedCount, predictedCount
            ));
        }
        return Map.copyOf(metrics);
    }

    /** 安全计算比例；分母非正时返回 0。 */
    private double ratio(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0.0;
        }
        return (double) numerator / denominator;
    }
}
