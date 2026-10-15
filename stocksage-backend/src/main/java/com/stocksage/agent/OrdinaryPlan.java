package com.stocksage.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 普通路线（MARKET / FUNDAMENTALS / NEWS）的最终执行计划。
 *
 * <p>执行、Trace 和前端都读这一份计划；{@link ExecutionPlan#actions()} 由这里的步骤派生。</p>
 *
 * @param targets 多标的对比时服务端确认的 2～4 个标的；单标的时为空，由执行阶段解析主标的
 * @param steps 已校验的取数步骤，所有标的共用
 * @param requirements 问题明确要求的数据类别
 * @param source 计划来源
 * @param diagnostics 规划过程的有界诊断信息，只写入 Trace
 * @param fallbackSteps 规划器从固定计划中删掉的取数步骤；动态计划取数失败或需求未满足时补跑一轮
 */
public record OrdinaryPlan(List<String> targets, List<PlanStep> steps, Set<DataRequirement> requirements,
                           Source source, Map<String, Object> diagnostics, List<PlanStep> fallbackSteps) {

    /** FIXED 为固定计划，DYNAMIC 为规划器计划，FALLBACK 为规划失败后退回的固定计划。 */
    public enum Source { FIXED, DYNAMIC, FALLBACK }

    public OrdinaryPlan {
        targets = targets == null ? List.of() : List.copyOf(targets);
        steps = steps == null ? List.of() : List.copyOf(steps);
        requirements = requirements == null ? Set.of() : Set.copyOf(requirements);
        source = source == null ? Source.FIXED : source;
        diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
        fallbackSteps = fallbackSteps == null ? List.of() : List.copyOf(fallbackSteps);
    }

    public OrdinaryPlan(List<String> targets, List<PlanStep> steps, Set<DataRequirement> requirements,
                        Source source, Map<String, Object> diagnostics) {
        this(targets, steps, requirements, source, diagnostics, List.of());
    }

    /** 由固定动作表展开的计划：每个动作都使用同一份请求参数。 */
    public static OrdinaryPlan fixed(List<String> targets, List<PlanAction> actions, ReadRequest request,
                                     Set<DataRequirement> requirements) {
        List<PlanStep> steps = new ArrayList<>();
        if (actions != null) actions.forEach(action -> steps.add(new PlanStep(action, request)));
        return new OrdinaryPlan(targets, steps, requirements, Source.FIXED, Map.of());
    }

    /**
     * 固定计划按问题里明确提到的数据需求补步骤：路由计划没覆盖的需求，按 {@link DataRequirement#coveredBy}
     * 的对应关系插到最终回答之前。不调用模型；财报原文由知识库检索提供，不补步骤。
     */
    public static List<PlanAction> withRequirementSteps(List<PlanAction> actions, Set<DataRequirement> requirements) {
        if (actions == null || requirements == null || requirements.isEmpty()) return actions;
        List<PlanAction> result = new ArrayList<>(actions);
        int insertAt = result.contains(PlanAction.FINAL_ANSWER) ? result.indexOf(PlanAction.FINAL_ANSWER) : result.size();
        for (DataRequirement requirement : DataRequirement.values()) {
            if (!requirements.contains(requirement) || requirement.coveredBy(result)) continue;
            PlanAction step = switch (requirement) {
                case PRICE_HISTORY -> PlanAction.GET_STOCK_KLINE;
                case VALUATION -> PlanAction.GET_FINANCIAL_METRICS;
                case TECHNICAL -> PlanAction.GET_TECHNICAL_INDICATORS;
                case FINANCIAL_STATEMENTS -> PlanAction.GET_FINANCIAL_REPORTS;
                case NEWS -> PlanAction.SEARCH_NEWS;
                case FILING_TEXT -> null;
            };
            if (step != null) result.add(insertAt++, step);
        }
        return List.copyOf(result);
    }

    public boolean multiTarget() {
        return targets.size() >= 2;
    }

    public List<PlanAction> actions() {
        return steps.stream().map(PlanStep::action).toList();
    }

    public Map<String, Object> attributes() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("kind", "ordinary-plan");
        attributes.put("source", source.name());
        attributes.put("targets", targets);
        attributes.put("steps", steps.stream().map(PlanStep::attributes).toList());
        attributes.put("requirements", requirements.stream().map(Enum::name).sorted().toList());
        attributes.put("diagnostics", diagnostics);
        attributes.put("fallbackSteps", fallbackSteps.stream().map(PlanStep::attributes).toList());
        return attributes;
    }
}
