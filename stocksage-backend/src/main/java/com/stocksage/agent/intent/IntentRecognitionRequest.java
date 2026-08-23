package com.stocksage.agent.intent;

import java.util.List;

/**
 * 一次意图识别的有界输入。
 *
 * <p>{@code recentTurns} 使用带角色前缀的字符串快照，最多保留末 6 条消息，等价于最多
 * 3 个用户/助手轮次。构造器不会保存调用方提供的可变集合。{@code ragHitCount} 仅为旧调用契约
 * 保留，不会送入语义识别提示词。</p>
 */
public record IntentRecognitionRequest(
        String currentQuestion,
        List<String> recentTurns,
        int ragHitCount,
        boolean hasImages,
        List<String> tickerCandidates
) {
    public IntentRecognitionRequest {
        currentQuestion = IntentBounds.text(currentQuestion, IntentBounds.MAX_QUESTION_CHARS);
        recentTurns = IntentBounds.recentTurns(recentTurns);
        ragHitCount = Math.max(0, Math.min(IntentBounds.MAX_RAG_HITS, ragHitCount));
        tickerCandidates = IntentBounds.tickers(tickerCandidates);
    }
}
