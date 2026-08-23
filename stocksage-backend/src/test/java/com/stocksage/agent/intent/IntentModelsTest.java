package com.stocksage.agent.intent;

import com.stocksage.agent.PlanRoute;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IntentModelsTest {

    @Test
    void signalPreservesInvalidRawRouteButOnlyExposesValidatedTarget() {
        Map<IntentSignalSource, Double> scores = new EnumMap<>(IntentSignalSource.class);
        scores.put(IntentSignalSource.LLM, 0.6);
        Map<String, String> entities = new LinkedHashMap<>();
        entities.put("ticker", "NVDA");
        List<String> reasons = new ArrayList<>(List.of("llm invalid route"));

        IntentSignal signal = new IntentSignal(
                FineIntent.MARKET_DATA,
                IntentGroup.MARKET,
                "UNREGISTERED_ROUTE".repeat(10),
                PlanRoute.DIRECT,
                IntentSignalSource.LLM,
                1.4,
                TimeSensitivity.REAL_TIME,
                AnalysisDepth.STANDARD,
                scores,
                entities,
                "NVDA current price ".repeat(400),
                "reason ".repeat(100),
                reasons
        );
        scores.clear();
        entities.clear();
        reasons.clear();

        assertThat(signal.rawRoute()).hasSize(IntentBounds.MAX_RAW_ROUTE_CHARS);
        assertThat(signal.rawRouteValid()).isFalse();
        assertThat(signal.targetRoute()).isEqualTo(PlanRoute.DIRECT);
        assertThat(signal.confidence()).isEqualTo(1.0);
        assertThat(signal.sourceScores()).containsEntry(IntentSignalSource.LLM, 1.0);
        assertThat(signal.entities()).containsEntry("ticker", "NVDA");
        assertThat(signal.resolvedQuery().length()).isLessThanOrEqualTo(IntentBounds.MAX_RESOLVED_QUERY_CHARS);
        assertThat(signal.rationale().length()).isLessThanOrEqualTo(IntentBounds.MAX_RATIONALE_CHARS);
        assertThat(signal.reasonCodes()).containsExactly("LLM_INVALID_ROUTE");
        assertThatThrownBy(() -> signal.entities().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void recognizesValidRawRouteAndRouteMismatchSeparately() {
        IntentSignal matching = signal(" market ", PlanRoute.MARKET);
        IntentSignal mismatching = signal("NEWS", PlanRoute.DIRECT);

        assertThat(matching.rawRouteValid()).isTrue();
        assertThat(matching.rawRouteMatchesTarget()).isTrue();
        assertThat(mismatching.rawRouteValid()).isTrue();
        assertThat(mismatching.rawRouteMatchesTarget()).isFalse();
    }

    @Test
    void decisionClampsNullsAndOwnsImmutableCollections() {
        Map<String, String> entities = new LinkedHashMap<>(Map.of("ticker", "AAPL"));
        Map<IntentSignalSource, Double> scores = new EnumMap<>(IntentSignalSource.class);
        scores.put(IntentSignalSource.PATTERN, Double.NaN);
        List<String> reasons = new ArrayList<>(List.of("low confidence"));

        IntentDecision decision = new IntentDecision(
                null, null, null, Double.POSITIVE_INFINITY, null, null,
                entities, null, scores, true, reasons
        );
        entities.clear();
        scores.clear();
        reasons.clear();

        assertThat(decision.fineIntent()).isEqualTo(FineIntent.UNKNOWN);
        assertThat(decision.intentGroup()).isEqualTo(IntentGroup.UNKNOWN);
        assertThat(decision.targetRoute()).isEqualTo(PlanRoute.DIRECT);
        assertThat(decision.confidence()).isZero();
        assertThat(decision.sourceScores()).containsEntry(IntentSignalSource.PATTERN, 0.0);
        assertThat(decision.entities()).containsEntry("ticker", "AAPL");
        assertThat(decision.reasonCodes()).containsExactly("LOW_CONFIDENCE");
        assertThatThrownBy(() -> decision.reasonCodes().add("new"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private IntentSignal signal(String rawRoute, PlanRoute target) {
        return new IntentSignal(
                FineIntent.MARKET_DATA, IntentGroup.MARKET, rawRoute, target,
                IntentSignalSource.LLM, 0.8, TimeSensitivity.REAL_TIME, AnalysisDepth.STANDARD,
                Map.of(), Map.of(), "query", "reason", List.of("test")
        );
    }
}
