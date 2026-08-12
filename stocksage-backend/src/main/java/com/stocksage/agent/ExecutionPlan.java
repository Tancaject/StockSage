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
 */
public record ExecutionPlan(
        PlanRoute route,
        String taskType,
        String thought,
        List<PlanAction> actions,
        String observation,
        ModelTier modelTier,
        RoutingDecisionMetadata routingDecision
) {
    /**
     * 保留历史五参数构造形式；旧调用只在显式传入枚举名称时才能恢复对应路由。
     *
     * <p>运行时 Coordinator 必须使用包含 {@link PlanRoute} 的主构造器，
     * 不能再从自由文本 taskType 推断执行路由。</p>
     */
    public ExecutionPlan(String taskType,
                         String thought,
                         List<PlanAction> actions,
                         String observation,
                         ModelTier modelTier) {
        this(PlanRoute.normalize(taskType), taskType, thought, actions, observation, modelTier, null);
    }

    /**
     * 保留旧构造形式，避免测试或小工具只关心路由动作时必须显式传 tier。
     */
    public ExecutionPlan(String taskType, String thought, List<PlanAction> actions, String observation) {
        this(PlanRoute.normalize(taskType), taskType, thought, actions, observation, ModelTier.STANDARD, null);
    }

    /**
     * 允许显式路由调用省略模型层级。
     */
    /** 保留显式路由与模型层级、但不传路由诊断元数据的兼容构造形式。 */
    public ExecutionPlan(PlanRoute route,
                         String taskType,
                         String thought,
                         List<PlanAction> actions,
                         String observation) {
        this(route, taskType, thought, actions, observation, ModelTier.STANDARD, null);
    }

    public ExecutionPlan(PlanRoute route,
                         String taskType,
                         String thought,
                         List<PlanAction> actions,
                         String observation,
                         ModelTier modelTier) {
        this(route, taskType, thought, actions, observation, modelTier, null);
    }

    /**
     * 动作的历史标签字符串列表，供追踪面板与回归比对使用。
     */
    public List<String> actionLabels() {
        return actions == null ? List.of() : actions.stream().map(PlanAction::label).toList();
    }
}
