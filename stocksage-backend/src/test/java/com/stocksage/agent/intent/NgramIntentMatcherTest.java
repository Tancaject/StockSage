package com.stocksage.agent.intent;

import com.stocksage.agent.PlanRoute;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NgramIntentMatcherTest {

    private final NgramIntentMatcher matcher = new NgramIntentMatcher();

    @Test
    void recognizesChineseAndEnglishFundamentalsParaphrases() {
        IntentSignal chinese = match("苹果季度报告中的收入和主要风险", List.of("AAPL"));
        IntentSignal english = match("summarise NVDA quarterly filling revenue risks", List.of("NVDA"));

        assertThat(chinese.targetRoute()).isEqualTo(PlanRoute.FUNDAMENTALS);
        assertThat(chinese.fineIntent()).isEqualTo(FineIntent.FUNDAMENTALS);
        assertThat(english.targetRoute()).isEqualTo(PlanRoute.FUNDAMENTALS);
        assertThat(english.source()).isEqualTo(IntentSignalSource.NGRAM);
    }

    @Test
    void recognizesBilingualTechnicalAndDeepQueries() {
        IntentSignal technical = match("看看日线的 RSI 和均线走势", List.of("AAPL"));
        IntentSignal deep = match("is this business worth a long term investment", List.of("COST"));

        assertThat(technical.targetRoute()).isEqualTo(PlanRoute.MARKET);
        assertThat(technical.fineIntent()).isEqualTo(FineIntent.TECHNICAL_ANALYSIS);
        assertThat(deep.targetRoute()).isEqualTo(PlanRoute.DEEP);
        assertThat(deep.fineIntent()).isEqualTo(FineIntent.DEEP_RESEARCH);
    }

    @Test
    void remainsEmptyForUnrelatedLowSimilarityText() {
        IntentRecognitionRequest request = new IntentRecognitionRequest(
                "purple bicycle weather recipe", List.of(), 0, false, List.of()
        );

        assertThat(matcher.match(request)).isEmpty();
    }

    @Test
    void usesBoundedRecentContextOnlyForShortFollowUp() {
        IntentRecognitionRequest request = new IntentRecognitionRequest(
                "那它呢？",
                List.of("user: 请总结 NVDA 的季度财报和风险因素", "assistant: 财报摘要"),
                0,
                false,
                List.of("NVDA")
        );

        IntentSignal signal = matcher.match(request).orElseThrow();
        assertThat(signal.targetRoute()).isEqualTo(PlanRoute.FUNDAMENTALS);
        assertThat(signal.resolvedQuery()).contains("ticker=NVDA");
    }

    private IntentSignal match(String query, List<String> tickers) {
        return matcher.match(new IntentRecognitionRequest(query, List.of(), 0, false, tickers))
                .orElseThrow();
    }
}
