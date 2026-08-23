package com.stocksage.agent.intent;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 包内共享的纯 Java 输入限界和不可变快照工具。 */
final class IntentBounds {

    static final int MAX_QUESTION_CHARS = 4096;
    static final int MAX_RECENT_TURNS = 6;
    static final int MAX_RECENT_TURN_CHARS = 600;
    static final int MAX_RAG_HITS = 100;
    static final int MAX_TICKERS = 8;
    static final int MAX_TICKER_CHARS = 24;
    static final int MAX_RAW_ROUTE_CHARS = 48;
    static final int MAX_RESOLVED_QUERY_CHARS = 600;
    static final int MAX_RATIONALE_CHARS = 240;
    static final int MAX_ENTITIES = 12;
    static final int MAX_ENTITY_KEY_CHARS = 32;
    static final int MAX_ENTITY_VALUE_CHARS = 160;
    static final int MAX_REASON_CODES = 16;
    static final int MAX_REASON_CODE_CHARS = 64;

    private IntentBounds() {
    }

    static String text(String value, int maxChars) {
        String normalized = value == null
                ? ""
                : Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("\\s+", " ")
                .trim();
        return normalized.substring(0, Math.min(Math.max(0, maxChars), normalized.length()));
    }

    static double confidence(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    static List<String> recentTurns(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> normalized = values.stream()
                .map(item -> text(item, MAX_RECENT_TURN_CHARS))
                .filter(item -> !item.isBlank())
                .toList();
        int from = Math.max(0, normalized.size() - MAX_RECENT_TURNS);
        return List.copyOf(normalized.subList(from, normalized.size()));
    }

    static List<String> tickers(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            String ticker = text(value, MAX_TICKER_CHARS).toUpperCase(Locale.ROOT);
            if (!ticker.isBlank()) {
                unique.add(ticker);
            }
            if (unique.size() >= MAX_TICKERS) {
                break;
            }
        }
        return List.copyOf(unique);
    }

    static Map<String, String> entities(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, String> bounded = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = text(entry.getKey(), MAX_ENTITY_KEY_CHARS);
            String value = text(entry.getValue(), MAX_ENTITY_VALUE_CHARS);
            if (!key.isBlank() && !value.isBlank()) {
                bounded.putIfAbsent(key, value);
            }
            if (bounded.size() >= MAX_ENTITIES) {
                break;
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(bounded));
    }

    static Map<String, String> entitiesFromTickers(List<String> tickers) {
        if (tickers == null || tickers.isEmpty()) {
            return Map.of();
        }
        if (tickers.size() == 1) {
            return Map.of("ticker", tickers.get(0));
        }
        return Map.of("tickers", String.join(",", tickers));
    }

    static Map<IntentSignalSource, Double> sourceScores(
            Map<IntentSignalSource, Double> values
    ) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        EnumMap<IntentSignalSource, Double> bounded = new EnumMap<>(IntentSignalSource.class);
        values.forEach((source, value) -> {
            if (source != null && value != null) {
                bounded.put(source, confidence(value));
            }
        });
        return Collections.unmodifiableMap(new EnumMap<>(bounded));
    }

    static List<String> reasonCodes(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            String code = text(value, MAX_REASON_CODE_CHARS)
                    .toUpperCase(Locale.ROOT)
                    .replace(' ', '_');
            if (!code.isBlank()) {
                unique.add(code);
            }
            if (unique.size() >= MAX_REASON_CODES) {
                break;
            }
        }
        return List.copyOf(unique);
    }

    static String resolvedQuery(IntentRecognitionRequest request) {
        String question = request == null ? "" : request.currentQuestion();
        if (request == null || request.tickerCandidates().isEmpty()) {
            return text(question, MAX_RESOLVED_QUERY_CHARS);
        }
        String lower = question.toLowerCase(Locale.ROOT);
        boolean followUp = lower.matches(".*(?:它|这只|该股|that company|that stock|what about it|\\bit\\b).*?");
        if (!followUp) {
            return text(question, MAX_RESOLVED_QUERY_CHARS);
        }
        return text(question + " [ticker=" + String.join(",", request.tickerCandidates()) + "]",
                MAX_RESOLVED_QUERY_CHARS);
    }

    /** LLM 若保留了指代但漏掉历史 ticker，在不改写原意的前提下补充结构化 ticker 标签。 */
    static String ensureContextTicker(String resolvedQuery, IntentRecognitionRequest request) {
        String safe = text(resolvedQuery, MAX_RESOLVED_QUERY_CHARS);
        if (request == null || request.tickerCandidates().isEmpty()) {
            return safe;
        }
        String localResolved = resolvedQuery(request);
        if (!localResolved.contains("[ticker=")) {
            return safe;
        }
        String upper = safe.toUpperCase(Locale.ROOT);
        boolean alreadyResolved = request.tickerCandidates().stream()
                .anyMatch(ticker -> upper.contains(ticker.toUpperCase(Locale.ROOT)));
        if (alreadyResolved) {
            return safe;
        }
        return text(safe + " [ticker=" + String.join(",", request.tickerCandidates()) + "]",
                MAX_RESOLVED_QUERY_CHARS);
    }

    static List<String> mergeReasonCodes(List<List<String>> groups, String... additional) {
        List<String> merged = new ArrayList<>();
        if (groups != null) {
            groups.stream().filter(java.util.Objects::nonNull).forEach(merged::addAll);
        }
        if (additional != null) {
            Collections.addAll(merged, additional);
        }
        return reasonCodes(merged);
    }
}
