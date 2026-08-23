package com.stocksage.agent.intent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IntentRecognitionRequestTest {

    @Test
    void boundsTextHistoryRagHitsAndTickerCandidates() {
        List<String> turns = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            turns.add(" user: turn " + i + " ".repeat(900));
        }
        List<String> tickers = new ArrayList<>(List.of(
                "aapl", " AAPL ", "0700.hk", "nvda", "msft", "amzn", "meta", "googl", "cost", "extra"
        ));

        IntentRecognitionRequest request = new IntentRecognitionRequest(
                " q ".repeat(5000), turns, 999, true, tickers
        );
        turns.clear();
        tickers.clear();

        assertThat(request.currentQuestion()).hasSize(IntentBounds.MAX_QUESTION_CHARS);
        assertThat(request.recentTurns()).hasSize(IntentBounds.MAX_RECENT_TURNS);
        assertThat(request.recentTurns().get(0)).startsWith("user: turn 3");
        assertThat(request.recentTurns()).allSatisfy(turn ->
                assertThat(turn.length()).isLessThanOrEqualTo(IntentBounds.MAX_RECENT_TURN_CHARS));
        assertThat(request.ragHitCount()).isEqualTo(IntentBounds.MAX_RAG_HITS);
        assertThat(request.hasImages()).isTrue();
        assertThat(request.tickerCandidates())
                .containsExactly("AAPL", "0700.HK", "NVDA", "MSFT", "AMZN", "META", "GOOGL", "COST");
        assertThatThrownBy(() -> request.recentTurns().add("new"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> request.tickerCandidates().add("NEW"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void normalizesNullAndNegativeInputs() {
        IntentRecognitionRequest request = new IntentRecognitionRequest(null, null, -4, false, null);

        assertThat(request.currentQuestion()).isEmpty();
        assertThat(request.recentTurns()).isEmpty();
        assertThat(request.ragHitCount()).isZero();
        assertThat(request.tickerCandidates()).isEmpty();
    }
}
