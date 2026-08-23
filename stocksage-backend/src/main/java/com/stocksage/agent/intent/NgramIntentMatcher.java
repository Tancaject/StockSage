package com.stocksage.agent.intent;

import com.stocksage.agent.PlanRoute;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 无网络依赖的字符 n-gram 原型匹配器。
 *
 * <p>它是 Embedding 缺失时的底线信号或融合平局裁决信号，不应替代 LLM 语义识别。</p>
 */
public final class NgramIntentMatcher {

    private static final int VECTOR_DIMENSION = 1024;
    private static final double MIN_SIMILARITY = 0.20;
    private static final double MIN_MARGIN = 0.015;

    private final List<IndexedPrototype> prototypes;

    public NgramIntentMatcher() {
        this(defaultPrototypes());
    }

    NgramIntentMatcher(List<Prototype> definitions) {
        this.prototypes = definitions.stream()
                .map(item -> new IndexedPrototype(item, vectorize(item.text())))
                .toList();
    }

    /** 对有界当前问题进行本地相似度匹配。 */
    public Optional<IntentSignal> match(IntentRecognitionRequest request) {
        if (request == null || request.currentQuestion().isBlank()) {
            return Optional.empty();
        }
        double[] queryVector = vectorize(contextualText(request));
        List<ScoredPrototype> ranked = prototypes.stream()
                .map(item -> new ScoredPrototype(item.definition(), cosine(queryVector, item.vector())))
                .sorted(Comparator.comparingDouble(ScoredPrototype::score).reversed())
                .toList();
        if (ranked.isEmpty() || ranked.get(0).score() < MIN_SIMILARITY) {
            return Optional.empty();
        }
        ScoredPrototype best = ranked.get(0);
        double secondScore = ranked.size() > 1 ? ranked.get(1).score() : 0.0;
        if (best.score() - secondScore < MIN_MARGIN
                && ranked.size() > 1
                && ranked.get(1).prototype().route() != best.prototype().route()) {
            return Optional.empty();
        }

        Prototype prototype = best.prototype();
        double confidence = IntentBounds.confidence(best.score());
        Map<String, String> entities = IntentBounds.entitiesFromTickers(request.tickerCandidates());
        return Optional.of(new IntentSignal(
                prototype.intent(), prototype.group(), prototype.route().name(), prototype.route(),
                IntentSignalSource.NGRAM, confidence, prototype.timeSensitivity(), prototype.depth(),
                Map.of(IntentSignalSource.NGRAM, confidence), entities, IntentBounds.resolvedQuery(request),
                "本地字符 n-gram 与已标注意图原型相似。", List.of(prototype.reasonCode())
        ));
    }

    private String contextualText(IntentRecognitionRequest request) {
        String question = request.currentQuestion();
        String lower = question.toLowerCase(Locale.ROOT);
        boolean shortFollowUp = question.length() <= 48
                && lower.matches(".*(?:它|这只|该股|那|what about|\\bit\\b|that stock).*?");
        if (!shortFollowUp || request.recentTurns().isEmpty()) {
            return question;
        }
        int from = Math.max(0, request.recentTurns().size() - 2);
        return String.join(" ", request.recentTurns().subList(from, request.recentTurns().size()))
                + " " + question;
    }

    private static double[] vectorize(String input) {
        String normalized = Normalizer.normalize(input == null ? "" : input, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim();
        double[] vector = new double[VECTOR_DIMENSION];
        if (normalized.isBlank()) {
            return vector;
        }
        for (String word : normalized.split("\\s+")) {
            // 英文完整词比易受拼写邻近影响的字符片段更可靠；中文没有空格分词，跳过整句词特征。
            if (!containsCjk(word)) {
                addFeature(vector, "w:" + word, 3.0);
            }
        }
        String compact = normalized.replace(" ", "");
        double characterWeight = containsCjk(compact) ? 1.0 : 0.5;
        for (int n = 2; n <= 3; n++) {
            if (compact.length() < n) {
                addFeature(vector, "c:" + compact, characterWeight);
                continue;
            }
            for (int i = 0; i <= compact.length() - n; i++) {
                addFeature(vector, "c" + n + ":" + compact.substring(i, i + n), characterWeight);
            }
        }
        return vector;
    }

    private static boolean containsCjk(String value) {
        for (int i = 0; i < value.length(); i++) {
            Character.UnicodeScript script = Character.UnicodeScript.of(value.charAt(i));
            if (script == Character.UnicodeScript.HAN) {
                return true;
            }
        }
        return false;
    }

    private static void addFeature(double[] vector, String feature, double weight) {
        int hash = feature.hashCode();
        int index = Math.floorMod(hash, vector.length);
        vector[index] += weight;
    }

    private static double cosine(double[] left, double[] right) {
        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, dot / Math.sqrt(leftNorm * rightNorm)));
    }

    private static List<Prototype> defaultPrototypes() {
        List<Prototype> values = new ArrayList<>();
        values.add(prototype(FineIntent.KNOWLEDGE_EXPLANATION, IntentGroup.KNOWLEDGE, PlanRoute.DIRECT,
                TimeSensitivity.NONE, AnalysisDepth.BRIEF, "什么是市盈率，它代表什么", "NGRAM_KNOWLEDGE_ZH"));
        values.add(prototype(FineIntent.KNOWLEDGE_EXPLANATION, IntentGroup.KNOWLEDGE, PlanRoute.DIRECT,
                TimeSensitivity.NONE, AnalysisDepth.BRIEF, "explain how dollar cost averaging works", "NGRAM_KNOWLEDGE_EN"));
        values.add(prototype(FineIntent.MARKET_DATA, IntentGroup.MARKET, PlanRoute.MARKET,
                TimeSensitivity.REAL_TIME, AnalysisDepth.STANDARD, "查看苹果当前股价成交量和行情", "NGRAM_MARKET_ZH"));
        values.add(prototype(FineIntent.MARKET_DATA, IntentGroup.MARKET, PlanRoute.MARKET,
                TimeSensitivity.REAL_TIME, AnalysisDepth.STANDARD, "latest stock price quote and trading volume", "NGRAM_MARKET_EN"));
        values.add(prototype(FineIntent.TECHNICAL_ANALYSIS, IntentGroup.MARKET, PlanRoute.MARKET,
                TimeSensitivity.RECENT, AnalysisDepth.STANDARD, "查看日线走势 macd rsi 技术指标", "NGRAM_TECHNICAL_ZH"));
        values.add(prototype(FineIntent.TECHNICAL_ANALYSIS, IntentGroup.MARKET, PlanRoute.MARKET,
                TimeSensitivity.RECENT, AnalysisDepth.STANDARD, "technical analysis price chart moving average rsi", "NGRAM_TECHNICAL_EN"));
        values.add(prototype(FineIntent.FUNDAMENTALS, IntentGroup.FUNDAMENTALS, PlanRoute.FUNDAMENTALS,
                TimeSensitivity.RECENT, AnalysisDepth.STANDARD, "总结最新季度财报收入现金流风险因素", "NGRAM_FUNDAMENTALS_ZH"));
        values.add(prototype(FineIntent.FUNDAMENTALS, IntentGroup.FUNDAMENTALS, PlanRoute.FUNDAMENTALS,
                TimeSensitivity.RECENT, AnalysisDepth.STANDARD, "季度报告中的收入和主要风险", "NGRAM_FUNDAMENTALS_DISCLOSURE_ZH"));
        values.add(prototype(FineIntent.FUNDAMENTALS, IntentGroup.FUNDAMENTALS, PlanRoute.FUNDAMENTALS,
                TimeSensitivity.RECENT, AnalysisDepth.STANDARD, "summarize quarterly filing revenue and risk factors", "NGRAM_FUNDAMENTALS_EN"));
        values.add(prototype(FineIntent.NEWS_EVENT, IntentGroup.NEWS, PlanRoute.NEWS,
                TimeSensitivity.RECENT, AnalysisDepth.STANDARD, "今天有哪些最新公司新闻和事件", "NGRAM_NEWS_ZH"));
        values.add(prototype(FineIntent.NEWS_EVENT, IntentGroup.NEWS, PlanRoute.NEWS,
                TimeSensitivity.RECENT, AnalysisDepth.STANDARD, "latest company news recent market headlines", "NGRAM_NEWS_EN"));
        values.add(prototype(FineIntent.COMPARISON, IntentGroup.RESEARCH, PlanRoute.DEEP,
                TimeSensitivity.UNSPECIFIED, AnalysisDepth.DEEP, "从多个维度比较两家公司", "NGRAM_COMPARISON_ZH"));
        values.add(prototype(FineIntent.COMPARISON, IntentGroup.RESEARCH, PlanRoute.DEEP,
                TimeSensitivity.UNSPECIFIED, AnalysisDepth.DEEP, "compare two companies across valuation growth and risks", "NGRAM_COMPARISON_EN"));
        values.add(prototype(FineIntent.PORTFOLIO_DIAGNOSIS, IntentGroup.RESEARCH, PlanRoute.DEEP,
                TimeSensitivity.RECENT, AnalysisDepth.DEEP, "诊断我的投资组合持仓和风险", "NGRAM_PORTFOLIO_ZH"));
        values.add(prototype(FineIntent.PORTFOLIO_DIAGNOSIS, IntentGroup.RESEARCH, PlanRoute.DEEP,
                TimeSensitivity.RECENT, AnalysisDepth.DEEP, "review my portfolio holdings and concentration risk", "NGRAM_PORTFOLIO_EN"));
        values.add(prototype(FineIntent.DEEP_RESEARCH, IntentGroup.RESEARCH, PlanRoute.DEEP,
                TimeSensitivity.RECENT, AnalysisDepth.DEEP, "分析公司是否值得长期投资和多空逻辑", "NGRAM_DEEP_ZH"));
        values.add(prototype(FineIntent.DEEP_RESEARCH, IntentGroup.RESEARCH, PlanRoute.DEEP,
                TimeSensitivity.RECENT, AnalysisDepth.DEEP, "evaluate whether the company is worth a long term investment", "NGRAM_DEEP_EN"));
        return List.copyOf(values);
    }

    private static Prototype prototype(FineIntent intent,
                                       IntentGroup group,
                                       PlanRoute route,
                                       TimeSensitivity time,
                                       AnalysisDepth depth,
                                       String text,
                                       String reasonCode) {
        return new Prototype(intent, group, route, time, depth, text, reasonCode);
    }

    record Prototype(FineIntent intent,
                     IntentGroup group,
                     PlanRoute route,
                     TimeSensitivity timeSensitivity,
                     AnalysisDepth depth,
                     String text,
                     String reasonCode) {
    }

    private record IndexedPrototype(Prototype definition, double[] vector) {
    }

    private record ScoredPrototype(Prototype prototype, double score) {
    }
}
