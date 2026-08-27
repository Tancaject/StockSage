package com.stocksage.agent;

import java.util.List;

/**
 * Coordinator 针对单轮用户请求生成的执行计划。
 *
 * <p>这是 Plan-and-Execute 架构的“计划”产物：Coordinator 一次性完成意图路由，
 * {@code ChatService} 随后把 action 名称作为确定性的预取步骤执行，最后才请求模型生成最终回答。
 * 它不是 ReAct 的逐步思考-行动循环——计划在执行前已经固定，便于审计与并行。</p>
 *
 * <p>计划对象刻意保持小而可序列化：thought/observation 会写入链路追踪。</p>
 *
 * @param route 后端可执行的稳定路由，不依赖模型生成的展示文本
 * @param taskType 任务类型，用于向追踪和界面说明本轮任务
 * @param thought 模型对本轮请求的简短思考，主要用于调试链路而不是直接展示为结论
 * @param actions 需要预先执行的确定性动作名称列表，例如 RAG 检索或行情查询
 * @param observation Coordinator 对当前上下文的观察摘要，会写入追踪面板
 * @param modelTier 最终回答应使用的模型能力层级，由后端映射到具体模型名
 * @param routingDecision 有界路由诊断元数据；历史调用可为空
 * @param resolvedQuery 仅供当前请求内部执行的消歧问题，不写入路由 Trace 属性
 */
public record ExecutionPlan(
        PlanRoute route,
        String taskType,
        String thought,
        List<PlanAction> actions,
        String observation,
        ModelTier modelTier,
        RoutingDecisionMetadata routingDecision,
        String resolvedQuery
) {
    public ExecutionPlan {
        resolvedQuery = resolvedQuery == null ? "" : resolvedQuery.trim();
        if (resolvedQuery.length() > 600) {
            resolvedQuery = resolvedQuery.substring(0, 600);
        }
    }

    /** 保留不传内部消歧问题的七字段调用形式。 */
    public ExecutionPlan(PlanRoute route,
                         String taskType,
                         String thought,
                         List<PlanAction> actions,
                         String observation,
                         ModelTier modelTier,
                         RoutingDecisionMetadata routingDecision) {
        this(route, taskType, thought, actions, observation, modelTier, routingDecision, "");
    }
    /** 保留显式路由与模型层级、但不传路由诊断元数据的调用形式。 */
    public ExecutionPlan(PlanRoute route,
                         String taskType,
                         String thought,
                         List<PlanAction> actions,
                         String observation,
                         ModelTier modelTier) {
        this(route, taskType, thought, actions, observation, modelTier, null, "");
    }

    /**
     * 动作的历史标签字符串列表，供追踪面板与回归比对使用。
     */
    public List<String> actionLabels() {
        return actions == null ? List.of() : actions.stream().map(PlanAction::label).toList();
    }
}
