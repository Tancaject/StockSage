package com.stocksage.agent;

/**
 * Identifies which routing mechanism produced an execution plan.
 */
public enum RoutingDecisionSource {
    ROUTING_LLM,
    DETERMINISTIC_FALLBACK
}
