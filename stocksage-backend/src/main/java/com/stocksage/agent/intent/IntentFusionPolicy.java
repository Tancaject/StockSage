package com.stocksage.agent.intent;

import com.stocksage.agent.PlanRoute;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 将 LLM、Embedding、Pattern 和本地 n-gram 证据融合为一个可执行路由。
 *
 * <p>默认权重为 LLM 0.6、Embedding 0.2、Pattern 0.2；缺失来源会重新归一。
 * Embedding 不可用时由 NGRAM 补它的 0.2 证据槽；已有 Embedding 时，NGRAM 只在近似平局中裁决。</p>
 */
public final class IntentFusionPolicy {

    public static final double DEFAULT_LLM_WEIGHT = 0.6;
    public static final double DEFAULT_EMBEDDING_WEIGHT = 0.2;
    public static final double DEFAULT_PATTERN_WEIGHT = 0.2;

    private static final double TIE_MARGIN = 0.03;
    private static final double CLARIFICATION_MARGIN = 0.08;
    private static final double MIN_DECISION_CONFIDENCE = 0.55;

    private final EnumMap<IntentSignalSource, Double> baseWeights =
            new EnumMap<>(IntentSignalSource.class);

    public IntentFusionPolicy() {
        this(DEFAULT_LLM_WEIGHT, DEFAULT_EMBEDDING_WEIGHT, DEFAULT_PATTERN_WEIGHT);
    }

    public IntentFusionPolicy(double llmWeight, double embeddingWeight, double patternWeight) {
        baseWeights.put(IntentSignalSource.LLM, nonNegative(llmWeight));
        baseWeights.put(IntentSignalSource.EMBEDDING, nonNegative(embeddingWeight));
        baseWeights.put(IntentSignalSource.PATTERN, nonNegative(patternWeight));
        if (baseWeights.values().stream().mapToDouble(Double::doubleValue).sum() == 0.0) {
            throw new IllegalArgumentException("At least one intent source weight must be positive");
        }
    }

    /** 融合每个来源置信度最高的证据；同一来源不能通过重复信号放大权重。 */
    public IntentDecision fuse(IntentRecognitionRequest request, List<IntentSignal> signals) {
        IntentRecognitionRequest safeRequest = request == null
                ? new IntentRecognitionRequest("", List.of(), 0, false, List.of())
                : request;
        List<IntentSignal> safeSignals = signals == null
                ? List.of()
                : signals.stream().filter(Objects::nonNull).toList();

        EnumMap<IntentSignalSource, IntentSignal> bestBySource = bestBySource(safeSignals);
        IntentSignal embedding = bestBySource.get(IntentSignalSource.EMBEDDING);
        IntentSignal ngram = bestBySource.get(IntentSignalSource.NGRAM);
        IntentSignal pattern = bestBySource.get(IntentSignalSource.PATTERN);

        EnumMap<IntentSignalSource, IntentSignal> votingSignals = new EnumMap<>(IntentSignalSource.class);
        addIfPresent(votingSignals, IntentSignalSource.LLM, bestBySource.get(IntentSignalSource.LLM));
        if (embedding != null && configuredWeight(IntentSignalSource.EMBEDDING) > 0.0) {
            votingSignals.put(IntentSignalSource.EMBEDDING, embedding);
        } else if (ngram != null && configuredWeight(IntentSignalSource.NGRAM) > 0.0) {
            votingSignals.put(IntentSignalSource.NGRAM, ngram);
        }
        addIfPresent(votingSignals, IntentSignalSource.PATTERN, pattern);

        if (votingSignals.isEmpty()) {
            return fallbackDecision(safeRequest, bestBySource.get(IntentSignalSource.FALLBACK));
        }

        EnumMap<IntentSignalSource, Double> normalizedWeights = normalizedWeights(votingSignals);
        EnumMap<PlanRoute, Double> routeScores = new EnumMap<>(PlanRoute.class);
        votingSignals.forEach((source, signal) -> routeScores.merge(
                signal.targetRoute(), normalizedWeights.get(source) * signal.confidence(), Double::sum));

        List<Map.Entry<PlanRoute, Double>> ranked = routeScores.entrySet().stream()
                .sorted(Map.Entry.<PlanRoute, Double>comparingByValue().reversed()
                        .thenComparing(entry -> entry.getKey().name()))
                .toList();
        PlanRoute winner = ranked.get(0).getKey();
        double topScore = ranked.get(0).getValue();
        double secondScore = ranked.size() > 1 ? ranked.get(1).getValue() : 0.0;
        boolean conflict = ranked.size() > 1 && topScore - secondScore < CLARIFICATION_MARGIN;
        boolean ngramTieBreak = false;

        if (embedding != null && ngram != null && ranked.size() > 1
                && topScore - secondScore <= TIE_MARGIN
                && (ngram.targetRoute() == ranked.get(0).getKey()
                || ngram.targetRoute() == ranked.get(1).getKey())) {
            winner = ngram.targetRoute();
            topScore = routeScores.getOrDefault(winner, topScore);
            ngramTieBreak = true;
        }

        PlanRoute selectedRoute = winner;
        IntentSignal representative = representative(
                selectedRoute, votingSignals, normalizedWeights, ngramTieBreak, ngram);
        Map<String, String> entities = mergeEntities(selectedRoute, safeSignals, normalizedWeights);
        if (entities.isEmpty()) {
            entities = IntentBounds.entitiesFromTickers(safeRequest.tickerCandidates());
        }
        String resolvedQuery = representative.resolvedQuery().isBlank()
                ? IntentBounds.resolvedQuery(safeRequest)
                : representative.resolvedQuery();
        resolvedQuery = IntentBounds.ensureContextTicker(resolvedQuery, safeRequest);
        TimeSensitivity time = selectTimeSensitivity(selectedRoute, safeSignals, representative);
        AnalysisDepth depth = selectDepth(selectedRoute, safeSignals, representative);

        EnumMap<IntentSignalSource, Double> sourceScores = new EnumMap<>(IntentSignalSource.class);
        bestBySource.forEach((source, signal) -> sourceScores.put(source, signal.confidence()));

        List<List<String>> reasonGroups = safeSignals.stream()
                .filter(signal -> signal.targetRoute() == selectedRoute)
                .map(IntentSignal::reasonCodes)
                .toList();
        List<String> reasons = IntentBounds.mergeReasonCodes(
                reasonGroups,
                votingSignals.size() > 1 ? "FUSION_MULTI_SOURCE" : "FUSION_SINGLE_SOURCE",
                conflict ? "FUSION_ROUTE_CONFLICT" : "FUSION_ROUTE_AGREEMENT",
                votingSignals.containsKey(IntentSignalSource.NGRAM) ? "FUSION_NGRAM_FALLBACK" : "",
                ngramTieBreak ? "FUSION_NGRAM_TIE_BREAK" : "",
                topScore < MIN_DECISION_CONFIDENCE ? "FUSION_LOW_CONFIDENCE" : ""
        );

        boolean needsClarification = representative.fineIntent() == FineIntent.UNKNOWN
                || topScore < MIN_DECISION_CONFIDENCE
                || (conflict && !ngramTieBreak);
        PlanRoute executableRoute = needsClarification ? PlanRoute.DIRECT : selectedRoute;
        if (needsClarification) {
            reasons = IntentBounds.mergeReasonCodes(
                    List.of(reasons),
                    "FUSION_CLARIFICATION_DIRECT",
                    "FUSION_CANDIDATE_ROUTE_" + selectedRoute.name()
            );
        }
        return new IntentDecision(
                representative.fineIntent(), representative.intentGroup(), executableRoute, topScore,
                time, depth, entities, resolvedQuery, sourceScores, needsClarification, reasons
        );
    }

    public IntentDecision fuse(IntentRecognitionRequest request, IntentSignal... signals) {
        return fuse(request, signals == null ? List.of() : java.util.Arrays.asList(signals));
    }

    private EnumMap<IntentSignalSource, IntentSignal> bestBySource(List<IntentSignal> signals) {
        EnumMap<IntentSignalSource, IntentSignal> result = new EnumMap<>(IntentSignalSource.class);
        for (IntentSignal signal : signals) {
            result.merge(signal.source(), signal,
                    (left, right) -> right.confidence() > left.confidence() ? right : left);
        }
        return result;
    }

    private EnumMap<IntentSignalSource, Double> normalizedWeights(
            EnumMap<IntentSignalSource, IntentSignal> votingSignals
    ) {
        double total = votingSignals.keySet().stream()
                .mapToDouble(this::configuredWeight)
                .sum();
        EnumMap<IntentSignalSource, Double> result = new EnumMap<>(IntentSignalSource.class);
        votingSignals.keySet().forEach(source -> result.put(source, configuredWeight(source) / total));
        return result;
    }

    private double configuredWeight(IntentSignalSource source) {
        if (source == IntentSignalSource.NGRAM) {
            return baseWeights.get(IntentSignalSource.EMBEDDING);
        }
        return baseWeights.getOrDefault(source, 0.0);
    }

    private IntentSignal representative(PlanRoute winner,
                                        EnumMap<IntentSignalSource, IntentSignal> votingSignals,
                                        EnumMap<IntentSignalSource, Double> normalizedWeights,
                                        boolean ngramTieBreak,
                                        IntentSignal ngram) {
        if (ngramTieBreak && ngram != null && ngram.targetRoute() == winner) {
            IntentSignal supported = votingSignals.entrySet().stream()
                    .filter(entry -> entry.getValue().targetRoute() == winner)
                    .max(Comparator.comparingDouble(entry ->
                            normalizedWeights.getOrDefault(entry.getKey(), 0.0) * entry.getValue().confidence()))
                    .map(Map.Entry::getValue)
                    .orElse(null);
            return supported == null ? ngram : supported;
        }
        return votingSignals.entrySet().stream()
                .filter(entry -> entry.getValue().targetRoute() == winner)
                .max(Comparator.comparingDouble(entry ->
                        normalizedWeights.getOrDefault(entry.getKey(), 0.0) * entry.getValue().confidence()))
                .map(Map.Entry::getValue)
                .orElseThrow();
    }

    private Map<String, String> mergeEntities(PlanRoute winner,
                                               List<IntentSignal> signals,
                                               Map<IntentSignalSource, Double> normalizedWeights) {
        Map<String, String> merged = new LinkedHashMap<>();
        signals.stream()
                .filter(signal -> signal.targetRoute() == winner)
                .sorted(Comparator.comparingDouble((IntentSignal signal) ->
                        normalizedWeights.getOrDefault(signal.source(), 0.0) * signal.confidence()).reversed())
                .forEach(signal -> signal.entities().forEach(merged::putIfAbsent));
        return IntentBounds.entities(merged);
    }

    private TimeSensitivity selectTimeSensitivity(PlanRoute winner,
                                                   List<IntentSignal> signals,
                                                   IntentSignal representative) {
        if (representative.timeSensitivity() != TimeSensitivity.UNSPECIFIED) {
            return representative.timeSensitivity();
        }
        return signals.stream()
                .filter(signal -> signal.targetRoute() == winner)
                .map(IntentSignal::timeSensitivity)
                .filter(value -> value != TimeSensitivity.UNSPECIFIED)
                .findFirst()
                .orElse(TimeSensitivity.UNSPECIFIED);
    }

    private AnalysisDepth selectDepth(PlanRoute winner,
                                      List<IntentSignal> signals,
                                      IntentSignal representative) {
        return signals.stream()
                .filter(signal -> signal.targetRoute() == winner)
                .map(IntentSignal::analysisDepth)
                .max(Comparator.comparingInt(this::depthRank))
                .orElse(representative.analysisDepth());
    }

    private int depthRank(AnalysisDepth depth) {
        return switch (depth) {
            case DEEP -> 3;
            case STANDARD -> 2;
            case BRIEF -> 1;
            case UNSPECIFIED -> 0;
        };
    }

    private IntentDecision fallbackDecision(IntentRecognitionRequest request, IntentSignal fallback) {
        if (fallback != null) {
            return new IntentDecision(
                    fallback.fineIntent(), fallback.intentGroup(), PlanRoute.DIRECT, fallback.confidence(),
                    fallback.timeSensitivity(), fallback.analysisDepth(), fallback.entities(),
                    fallback.resolvedQuery().isBlank() ? IntentBounds.resolvedQuery(request) : fallback.resolvedQuery(),
                    fallback.sourceScores(), true,
                    IntentBounds.mergeReasonCodes(List.of(fallback.reasonCodes()), "FUSION_FALLBACK_ONLY")
            );
        }
        return new IntentDecision(
                FineIntent.UNKNOWN, IntentGroup.UNKNOWN, PlanRoute.DIRECT, 0.0,
                TimeSensitivity.UNSPECIFIED, AnalysisDepth.UNSPECIFIED,
                IntentBounds.entitiesFromTickers(request.tickerCandidates()), IntentBounds.resolvedQuery(request),
                Map.of(), true, List.of("FUSION_NO_SIGNAL")
        );
    }

    private void addIfPresent(Map<IntentSignalSource, IntentSignal> target,
                              IntentSignalSource source,
                              IntentSignal signal) {
        if (signal != null && configuredWeight(source) > 0.0) {
            target.put(source, signal);
        }
    }

    private double nonNegative(double value) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException("Intent source weights must be finite and non-negative");
        }
        return value;
    }
}
