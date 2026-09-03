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
import java.util.Objects;
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
    public static final String SCHEMA_VERSION = "planner_eval_v2";
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
        List<PlannerEvalCase> evaluatedCases = request.cases().stream()
                .filter(evalCase -> request.mode() != PlannerEvalMode.DETERMINISTIC || !evalCase.liveOnly())
                .toList();
        int skippedLiveOnlyCases = request.cases().size() - evaluatedCases.size();
        List<PlannerEvalResult> results = evaluatedCases.stream()
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
        long requiredTotal = evaluatedCases.stream().mapToLong(item -> item.requiredActions().size()).sum();
        long requiredMissing = results.stream().mapToLong(item -> item.missingRequiredActions().size()).sum();
        long forbiddenTotal = evaluatedCases.stream().mapToLong(item -> item.forbiddenActions().size()).sum();
        long forbiddenMatched = results.stream().mapToLong(item -> item.matchedForbiddenActions().size()).sum();
        int intentEvaluatedCases = (int) results.stream()
                .filter(result -> result.expectedFineIntent() != null)
                .count();
        int intentMatches = (int) results.stream()
                .filter(result -> result.expectedFineIntent() != null && result.fineIntentMatched())
                .count();
        int contextCases = (int) results.stream().filter(PlannerEvalResult::contextCase).count();
        int contextRouteMatches = (int) results.stream()
                .filter(result -> result.contextCase() && result.routeMatched())
                .count();
        int contextResolutionCases = (int) results.stream()
                .filter(result -> result.expectedResolvedQueryContains() != null)
                .count();
        int contextResolutionMatches = (int) results.stream()
                .filter(result -> result.expectedResolvedQueryContains() != null
                        && result.contextResolutionMatched())
                .count();
        int nonFallbackCases = (int) results.stream().filter(this::hasNonFallbackSource).count();
        int nonFallbackRouteMatches = (int) results.stream()
                .filter(result -> hasNonFallbackSource(result) && result.routeMatched())
                .count();
        int llmSignalCases = (int) results.stream()
                .filter(result -> hasNonFallbackSource(result)
                        && !result.executionGuarded()
                        && result.rawRouteValid())
                .count();
        int llmSignalMatches = (int) results.stream()
                .filter(result -> hasNonFallbackSource(result)
                        && !result.executionGuarded()
                        && result.rawRouteMatched())
                .count();
        int fallbackCases = (int) results.stream().filter(PlannerEvalResult::fallback).count();
        int invalidRawRouteCases = (int) results.stream()
                .filter(result -> !result.rawRouteValid())
                .count();
        Map<String, RouteEvalMetrics> perRoute = perRouteMetrics(results);
        double macroF1 = perRoute.values().stream()
                .mapToDouble(RouteEvalMetrics::f1)
                .average()
                .orElse(0.0);

        String status = total == 0 ? "not_run" : passed == total ? "passed" : "failed";
        return new PlannerEvalResponse(
                SCHEMA_VERSION,
                request.mode(),
                status,
                request.cases().size(),
                total,
                skippedLiveOnlyCases,
                passed,
                criticalFailures,
                ratio(routeMatches, total),
                macroF1,
                perRoute,
                requiredTotal == 0 ? 1.0 : ratio(requiredTotal - requiredMissing, requiredTotal),
                forbiddenTotal == 0 ? 0.0 : ratio(forbiddenMatched, forbiddenTotal),
                ratio(executable, total),
                intentEvaluatedCases,
                ratio(intentMatches, intentEvaluatedCases),
                contextCases,
                ratio(contextRouteMatches, contextCases),
                contextResolutionCases,
                ratio(contextResolutionMatches, contextResolutionCases),
                nonFallbackCases,
                ratio(nonFallbackRouteMatches, nonFallbackCases),
                llmSignalCases,
                ratio(llmSignalMatches, llmSignalCases),
                fallbackCases,
                ratio(fallbackCases, total),
                invalidRawRouteCases,
                ratio(invalidRawRouteCases, total),
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
                    : coordinator.plan(evalCase.query(), evalCase.ragHitCount(), "", evalCase.recentTurns());
            List<PlanAction> planned = plan.actions() == null ? List.of() : List.copyOf(plan.actions());
            List<PlanAction> missing = evalCase.requiredActions().stream()
                    .filter(action -> !planned.contains(action))
                    .toList();
            List<PlanAction> forbidden = evalCase.forbiddenActions().stream()
                    .filter(planned::contains)
                    .toList();
            RoutingDecisionMetadata routing = plan.routingDecision();
            String decisionSource = routing == null ? "" : routing.decisionSource().name();
            String actualFineIntent = routing == null ? "" : routing.fineIntent();
            boolean routeMatched = plan.route() == evalCase.expectedRoute();
            boolean fineIntentMatched = matchesOptional(evalCase.expectedFineIntent(), actualFineIntent);
            boolean decisionSourceMatched = matchesOptional(evalCase.expectedDecisionSource(), decisionSource);
            boolean fallback = routing != null && routing.fallback();
            boolean executionGuarded = routing != null
                    && routing.reasonCodes().contains(Coordinator.MULTI_TARGET_UNSUPPORTED);
            boolean noFallbackMatched = !evalCase.requireNoFallback()
                    || routing != null && !fallback;
            String actualResolvedQuery = Objects.requireNonNullElse(plan.resolvedQuery(), "").trim();
            boolean contextResolutionMatched = containsOptional(
                    evalCase.expectedResolvedQueryContains(), actualResolvedQuery);
            PlanRoute parsedRawRoute = routing == null
                    ? null
                    : PlanRoute.parse(routing.rawRoute()).orElse(null);
            boolean rawRouteValid = parsedRawRoute != null;
            boolean rawRouteMatched = parsedRawRoute == evalCase.expectedRoute();
            boolean passed = routeMatched
                    && missing.isEmpty()
                    && forbidden.isEmpty()
                    && fineIntentMatched
                    && decisionSourceMatched
                    && noFallbackMatched
                    && contextResolutionMatched;
            return new PlannerEvalResult(
                    evalCase.id(), evalCase.expectedRoute(), plan.route(), planned,
                    missing, forbidden, evalCase.critical(), passed, true,
                    System.currentTimeMillis() - startedAt, "",
                    decisionSource,
                    routing == null ? "" : routing.rawRoute(),
                    routing == null ? "" : routing.intentSummary(),
                    routing == null ? "" : routing.rationale(),
                    routing == null ? 0.0 : routing.confidence(),
                    routing == null ? "" : routing.fallbackReason(),
                    evalCase.expectedFineIntent(),
                    actualFineIntent,
                    fineIntentMatched,
                    evalCase.expectedDecisionSource(),
                    decisionSourceMatched,
                    evalCase.requireNoFallback(),
                    noFallbackMatched,
                    routeMatched,
                    !evalCase.recentTurns().isEmpty(),
                    fallback,
                    executionGuarded,
                    rawRouteValid,
                    rawRouteMatched,
                    evalCase.expectedResolvedQueryContains(),
                    actualResolvedQuery,
                    contextResolutionMatched
            );
        } catch (Exception ignored) {
            return new PlannerEvalResult(
                    evalCase.id(), evalCase.expectedRoute(), null, List.of(),
                    evalCase.requiredActions(), List.of(), evalCase.critical(),
                    false, false, System.currentTimeMillis() - startedAt,
                    "PLANNER_EXECUTION_FAILED", "", "", "", "", 0.0, "",
                    evalCase.expectedFineIntent(), "", evalCase.expectedFineIntent() == null,
                    evalCase.expectedDecisionSource(), evalCase.expectedDecisionSource() == null,
                    evalCase.requireNoFallback(), !evalCase.requireNoFallback(), false,
                    !evalCase.recentTurns().isEmpty(), false, false, false, false,
                    evalCase.expectedResolvedQueryContains(), "",
                    evalCase.expectedResolvedQueryContains() == null
            );
        }
    }

    /** 非回退准确率只纳入带明确路由来源的 LLM/融合结果，不能把缺失元数据当作成功。 */
    private boolean hasNonFallbackSource(PlannerEvalResult result) {
        return result.decisionSource() != null
                && !result.decisionSource().isBlank()
                && !"DETERMINISTIC_FALLBACK".equalsIgnoreCase(result.decisionSource());
    }

    /** 可选枚举名使用忽略大小写的精确比较；未提供期望值时保持 V1 通过语义。 */
    private boolean matchesOptional(String expected, String actual) {
        return expected == null || expected.equalsIgnoreCase(Objects.requireNonNullElse(actual, "").trim());
    }

    /** 可选消歧断言忽略大小写；未声明时保持旧样例的通过语义。 */
    private boolean containsOptional(String expected, String actual) {
        return expected == null
                || Objects.requireNonNullElse(actual, "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains(expected.toLowerCase(java.util.Locale.ROOT));
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
