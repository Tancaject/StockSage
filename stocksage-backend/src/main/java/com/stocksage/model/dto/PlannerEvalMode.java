package com.stocksage.model.dto;

/**
 * 规划器回归评测的执行模式。
 *
 * <p>该枚举由评测 API 传入，用来选择纯规则基线或真实协调器路径。</p>
 */
public enum PlannerEvalMode {
    /** 仅运行确定性规则规划，不发起大模型调用，适合稳定回归。 */
    DETERMINISTIC,

    /** 运行生产 {@code Coordinator} 规划路径，结果可能受模型输出影响。 */
    LIVE_COORDINATOR
}
