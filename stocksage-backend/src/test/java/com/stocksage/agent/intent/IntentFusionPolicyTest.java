package com.stocksage.agent.intent;

import com.stocksage.agent.PlanRoute;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

class IntentFusionPolicyTest {

    private final IntentFusionPolicy policy = new IntentFusionPolicy();
    private final IntentRecognitionRequest request = new IntentRecognitionRequest(
            "AAPL current price", List.of(), 0, false, List.of("AAPL")
    );

    @Test
    void reNormalizesWhenOnlyOneSourceIsPresent() {
        IntentDecision decision = policy.fuse(request,
                signal(IntentSignalSource.LLM, PlanRoute.MARKET, FineIntent.MARKET_DATA, 0.8));

        assertThat(decision.targetRoute()).isEqualTo(PlanRoute.MARKET);
        assertThat(decision.confidence()).isCloseTo(0.8, offset(0.000001));
        assertThat(decision.needsClarification()).isFalse();
    }

    @Test
    void appliesInitialWeightsWithoutAllowingPatternToOverruleStrongLlm() {
        IntentDecision decision = policy.fuse(request, List.of(
                signal(IntentSignalSource.LLM, PlanRoute.MARKET, FineIntent.MARKET_DATA, 0.8),
                signal(IntentSignalSource.PATTERN, PlanRoute.NEWS, FineIntent.NEWS_EVENT, 1.0)
        ));

        assertThat(decision.targetRoute()).isEqualTo(PlanRoute.MARKET);
        assertThat(decision.sourceScores())
                .containsEntry(IntentSignalSource.LLM, 0.8)
                .containsEntry(IntentSignalSource.PATTERN, 1.0);
    }

    @Test
    void ngramIsOnlyTheLastNoDependencyFallback() {
        IntentDecision withoutSemanticOrPattern = policy.fuse(request, List.of(
                signal(IntentSignalSource.NGRAM, PlanRoute.NEWS, FineIntent.NEWS_EVENT, 0.9)
        ));
        IntentDecision withEmbedding = policy.fuse(request, List.of(
                signal(IntentSignalSource.LLM, PlanRoute.MARKET, FineIntent.MARKET_DATA, 0.8),
                signal(IntentSignalSource.EMBEDDING, PlanRoute.NEWS, FineIntent.NEWS_EVENT, 0.8),
                signal(IntentSignalSource.NGRAM, PlanRoute.NEWS, FineIntent.NEWS_EVENT, 1.0)
        ));

        assertThat(withoutSemanticOrPattern.targetRoute()).isEqualTo(PlanRoute.NEWS);
        assertThat(withoutSemanticOrPattern.reasonCodes()).contains("FUSION_NGRAM_FALLBACK");
        assertThat(withEmbedding.targetRoute()).isEqualTo(PlanRoute.MARKET);
        assertThat(withEmbedding.reasonCodes()).doesNotContain("FUSION_NGRAM_FALLBACK");
    }

    @Test
    void ngramCanBreakNearTieWhenEmbeddingExists() {
        IntentDecision decision = policy.fuse(request, List.of(
                signal(IntentSignalSource.LLM, PlanRoute.MARKET, FineIntent.MARKET_DATA, 0.33),
                signal(IntentSignalSource.EMBEDDING, PlanRoute.NEWS, FineIntent.NEWS_EVENT, 0.99),
                signal(IntentSignalSource.NGRAM, PlanRoute.NEWS, FineIntent.NEWS_EVENT, 0.9)
        ));

        assertThat(decision.targetRoute()).isEqualTo(PlanRoute.DIRECT);
        assertThat(decision.fineIntent()).isEqualTo(FineIntent.NEWS_EVENT);
        assertThat(decision.needsClarification()).isTrue();
        assertThat(decision.reasonCodes()).contains("FUSION_NGRAM_TIE_BREAK");
        assertThat(decision.reasonCodes()).contains("FUSION_CANDIDATE_ROUTE_NEWS");
    }

    @Test
    void lowConfidenceDeepCandidateCannotStartExpensiveRoute() {
        IntentDecision decision = policy.fuse(request,
                signal(IntentSignalSource.LLM, PlanRoute.DEEP, FineIntent.DEEP_RESEARCH, 0.4));

        assertThat(decision.targetRoute()).isEqualTo(PlanRoute.DIRECT);
        assertThat(decision.fineIntent()).isEqualTo(FineIntent.DEEP_RESEARCH);
        assertThat(decision.needsClarification()).isTrue();
        assertThat(decision.reasonCodes())
                .contains("FUSION_LOW_CONFIDENCE", "FUSION_CANDIDATE_ROUTE_DEEP");
    }

    @Test
    void agreementCombinesEvidenceAndMergesEntitiesImmutably() {
        IntentSignal llm = signal(IntentSignalSource.LLM, PlanRoute.FUNDAMENTALS,
                FineIntent.FUNDAMENTALS, 0.8, Map.of("ticker", "AAPL"));
        IntentSignal embedding = signal(IntentSignalSource.EMBEDDING, PlanRoute.FUNDAMENTALS,
                FineIntent.FUNDAMENTALS, 0.7, Map.of("filing", "10-K"));
        IntentSignal pattern = signal(IntentSignalSource.PATTERN, PlanRoute.FUNDAMENTALS,
                FineIntent.FUNDAMENTALS, 0.9, Map.of());

        IntentDecision decision = policy.fuse(request, List.of(llm, embedding, pattern));

        assertThat(decision.targetRoute()).isEqualTo(PlanRoute.FUNDAMENTALS);
        assertThat(decision.confidence()).isCloseTo(0.8, offset(0.000001));
        assertThat(decision.entities()).containsEntry("ticker", "AAPL").containsEntry("filing", "10-K");
        assertThat(decision.reasonCodes()).contains("FUSION_ROUTE_AGREEMENT");
        assertThatThrownBy(() -> decision.entities().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void duplicateSignalsFromOneSourceCannotInflateItsVote() {
        IntentDecision decision = policy.fuse(request, List.of(
                signal(IntentSignalSource.PATTERN, PlanRoute.NEWS, FineIntent.NEWS_EVENT, 0.9),
                signal(IntentSignalSource.PATTERN, PlanRoute.DEEP, FineIntent.DEEP_RESEARCH, 0.8)
        ));

        assertThat(decision.targetRoute()).isEqualTo(PlanRoute.NEWS);
        assertThat(decision.confidence()).isCloseTo(0.9, offset(0.000001));
    }

    @Test
    void noSignalReturnsSafeClarificationDecision() {
        IntentDecision decision = policy.fuse(request, List.of());

        assertThat(decision.targetRoute()).isEqualTo(PlanRoute.DIRECT);
        assertThat(decision.fineIntent()).isEqualTo(FineIntent.UNKNOWN);
        assertThat(decision.needsClarification()).isTrue();
        assertThat(decision.reasonCodes()).containsExactly("FUSION_NO_SIGNAL");
    }

    @Test
    void rejectsInvalidWeightConfiguration() {
        assertThatThrownBy(() -> new IntentFusionPolicy(-1.0, 0.2, 0.2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IntentFusionPolicy(0.0, 0.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private IntentSignal signal(IntentSignalSource source,
                                PlanRoute route,
                                FineIntent intent,
                                double confidence) {
        return signal(source, route, intent, confidence, Map.of());
    }

    private IntentSignal signal(IntentSignalSource source,
                                PlanRoute route,
                                FineIntent intent,
                                double confidence,
                                Map<String, String> entities) {
        IntentGroup group = switch (route) {
            case DIRECT -> IntentGroup.KNOWLEDGE;
            case MARKET -> IntentGroup.MARKET;
            case FUNDAMENTALS -> IntentGroup.FUNDAMENTALS;
            case NEWS -> IntentGroup.NEWS;
            case DEEP -> IntentGroup.RESEARCH;
        };
        AnalysisDepth depth = route == PlanRoute.DEEP ? AnalysisDepth.DEEP : AnalysisDepth.STANDARD;
        return new IntentSignal(
                intent, group, route.name(), route, source, confidence,
                TimeSensitivity.UNSPECIFIED, depth, Map.of(source, confidence), entities,
                request.currentQuestion(), "test evidence", List.of(source.name() + "_TEST")
        );
    }
}
