package com.stocksage.agent;

/**
 * 标识执行计划由哪种路由机制产生。
 *
 * <p>用于 Trace 与指标区分正常路由模型结果和模型失败/显式测试时的确定性兜底。</p>
 */
public enum RoutingDecisionSource {
    ROUTING_LLM,
    DETERMINISTIC_FALLBACK
}
