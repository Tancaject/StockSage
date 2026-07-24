package com.stocksage.agent;

/**
 * Identifies which routing mechanism produced an execution plan.
 */
public enum RoutingDecisionSource {
    LEGACY_LLM,
    INTENT_LLM,
    DETERMINISTIC_FALLBACK
}
