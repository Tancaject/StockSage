package com.stocksage.agent.intent;

import com.stocksage.agent.PlanRoute;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PatternIntentMatcherTest {

    private final PatternIntentMatcher matcher = new PatternIntentMatcher();

    @Test
    void recognizesChineseKnowledgeAndInvestmentJudgment() {
        IntentSignal knowledge = match("什么是市盈率？", List.of());
        IntentSignal deep = match("NVDA 现在是否值得长期投资？", List.of("NVDA"));

        assertThat(knowledge.targetRoute()).isEqualTo(PlanRoute.DIRECT);
        assertThat(knowledge.fineIntent()).isEqualTo(FineIntent.KNOWLEDGE_EXPLANATION);
        assertThat(deep.targetRoute()).isEqualTo(PlanRoute.DEEP);
        assertThat(deep.fineIntent()).isEqualTo(FineIntent.DEEP_RESEARCH);
        assertThat(deep.entities()).containsEntry("ticker", "NVDA");
    }

    @Test
    void keepsLatestFilingInFundamentalsInsteadOfNews() {
        IntentSignal signal = match("Summarize AAPL latest 10-K risk factors", List.of("AAPL"));

        assertThat(signal.targetRoute()).isEqualTo(PlanRoute.FUNDAMENTALS);
        assertThat(signal.fineIntent()).isEqualTo(FineIntent.FUNDAMENTALS);
        assertThat(signal.timeSensitivity()).isEqualTo(TimeSensitivity.RECENT);
    }

    @Test
    void recognizesEnglishNewsAndTechnicalAnalysis() {
        IntentSignal news = match("Latest NVDA news", List.of("NVDA"));
        IntentSignal technical = match("AAPL RSI and MACD technical analysis", List.of("AAPL"));

        assertThat(news.targetRoute()).isEqualTo(PlanRoute.NEWS);
        assertThat(news.fineIntent()).isEqualTo(FineIntent.NEWS_EVENT);
        assertThat(technical.targetRoute()).isEqualTo(PlanRoute.MARKET);
        assertThat(technical.fineIntent()).isEqualTo(FineIntent.TECHNICAL_ANALYSIS);
    }

    @Test
    void conceptQuestionAboutMetricDoesNotBecomeMarketWithoutTicker() {
        IntentSignal signal = match("Explain what PE means", List.of());

        assertThat(signal.targetRoute()).isEqualTo(PlanRoute.DIRECT);
        assertThat(signal.fineIntent()).isEqualTo(FineIntent.KNOWLEDGE_EXPLANATION);
    }

    @Test
    void unsupportedTradeExecutionProducesSafeDirectEvidence() {
        IntentSignal signal = match("Place an order to buy 100 shares of AAPL", List.of("AAPL"));

        assertThat(signal.targetRoute()).isEqualTo(PlanRoute.DIRECT);
        assertThat(signal.fineIntent()).isEqualTo(FineIntent.UNKNOWN);
        assertThat(signal.reasonCodes()).contains("PATTERN_UNSUPPORTED_TRADE_ACTION");
    }

    @Test
    void doesNotTreatBroadFreeTextAsHighPrecisionPattern() {
        assertThat(matcher.match(request("Please analyze this carefully", List.of()))).isEmpty();
        assertThat(matcher.match(request("帮我看看这个", List.of()))).isEmpty();
    }

    private IntentSignal match(String query, List<String> tickers) {
        return matcher.match(request(query, tickers)).orElseThrow();
    }

    private IntentRecognitionRequest request(String query, List<String> tickers) {
        return new IntentRecognitionRequest(query, List.of(), 0, false, tickers);
    }
}
