package com.stocksage.agent.intent;

/** 产生意图证据的有限来源。 */
public enum IntentSignalSource {
    LLM,
    EMBEDDING,
    PATTERN,
    NGRAM,
    FALLBACK
}
