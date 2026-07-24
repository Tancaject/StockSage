package com.stocksage.agent;

import java.util.List;

public record IntentRecognitionRequest(
        String currentQuestion,
        List<String> recentContext,
        int ragHitCount,
        boolean hasImages,
        List<String> tickerCandidates
) {
    public IntentRecognitionRequest {
        currentQuestion = currentQuestion == null ? "" : currentQuestion;
        recentContext = recentContext == null
                ? List.of()
                : recentContext.stream().filter(item -> item != null && !item.isBlank()).limit(4).toList();
        ragHitCount = Math.max(0, ragHitCount);
        tickerCandidates = tickerCandidates == null
                ? List.of()
                : tickerCandidates.stream().filter(item -> item != null && !item.isBlank()).limit(5).toList();
    }
}
