package com.stocksage.agent.intent;

import com.stocksage.agent.PlanRoute;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 使用当前活动 EmbeddingModel 对五类意图原型做内存相似度匹配。
 *
 * <p>原型不会写入 RAG/Milvus；调用在隔离线程池运行，路由线程只等待短 deadline。
 * 任意超时、维度不一致、NaN/Inf 或零向量都会 abstain，由其他信号继续完成路由。</p>
 */
@Slf4j
@Component
public class EmbeddingIntentMatcher {

    private static final Map<PlanRoute, String> PROTOTYPES = Map.of(
            PlanRoute.DIRECT, "解释金融概念、定义、原理或方法，不需要实时市场数据。 what is a financial concept or how it works",
            PlanRoute.MARKET, "查询股票实时价格、K线、技术指标、成交量或单点估值指标。 stock price quote chart technical indicator",
            PlanRoute.FUNDAMENTALS, "查询公司财报、年报、季报、10-K、10-Q、风险因素或结构化财务披露。 company filing financial report fundamentals",
            PlanRoute.NEWS, "查询最新新闻、政策、宏观事件、公司事件或市场消息。 latest news event policy macro development",
            PlanRoute.DEEP, "综合财报行情新闻进行投资判断、多空论证、公司比较或组合诊断。 investment thesis deep research bull bear portfolio"
    );

    private final ObjectProvider<EmbeddingModel> modelProvider;
    private final AsyncTaskExecutor executor;
    private final boolean enabled;
    private final long timeoutMs;
    private final double minSimilarity;
    private final double minMargin;
    private volatile Map<PlanRoute, float[]> prototypeVectors;

    public EmbeddingIntentMatcher(
            ObjectProvider<EmbeddingModel> modelProvider,
            @Qualifier("intentEmbeddingExecutor") AsyncTaskExecutor executor,
            @Value("${stocksage.agent.intent.embedding.enabled:true}") boolean enabled,
            @Value("${stocksage.agent.intent.embedding.timeout-ms:800}") long timeoutMs,
            @Value("${stocksage.agent.intent.embedding.min-similarity:0.55}") double minSimilarity,
            @Value("${stocksage.agent.intent.embedding.min-margin:0.03}") double minMargin
    ) {
        this.modelProvider = modelProvider;
        this.executor = executor;
        this.enabled = enabled;
        this.timeoutMs = Math.max(1L, timeoutMs);
        this.minSimilarity = clamp(minSimilarity);
        this.minMargin = clamp(minMargin);
    }

    /** 在调用 LLM 前启动匹配，使本地向量计算与模型分类并行。 */
    public MatchHandle beginMatch(IntentRecognitionRequest request) {
        if (!enabled || request == null || request.currentQuestion().isBlank()
                || modelProvider == null || modelProvider.getIfAvailable() == null) {
            return MatchHandle.empty();
        }
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        try {
            CompletableFuture<Optional<IntentSignal>> future = CompletableFuture.supplyAsync(
                    () -> matchNow(request), executor);
            return new MatchHandle(future, deadlineNanos);
        } catch (RuntimeException e) {
            log.debug("Intent embedding submission skipped, errorType={}", e.getClass().getSimpleName());
            return MatchHandle.empty();
        }
    }

    /** 获取并行结果；超过剩余等待预算立即 abstain。 */
    public Optional<IntentSignal> finishMatch(MatchHandle handle) {
        if (handle == null || handle.future() == null) {
            return Optional.empty();
        }
        try {
            if (handle.future().isDone()) {
                return handle.future().get();
            }
            long remaining = handle.deadlineNanos() - System.nanoTime();
            if (remaining <= 0L) {
                handle.future().cancel(true);
                return Optional.empty();
            }
            return handle.future().get(remaining, TimeUnit.NANOSECONDS);
        } catch (Exception e) {
            handle.future().cancel(true);
            log.debug("Intent embedding abstained, errorType={}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** 便于独立调用和单测的同步外观，仍受相同 deadline 约束。 */
    public Optional<IntentSignal> match(IntentRecognitionRequest request) {
        return finishMatch(beginMatch(request));
    }

    private Optional<IntentSignal> matchNow(IntentRecognitionRequest request) {
        EmbeddingModel model = modelProvider.getIfAvailable();
        if (model == null) {
            return Optional.empty();
        }
        Map<PlanRoute, float[]> prototypes = ensurePrototypeVectors(model);
        String input = buildEmbeddingInput(request);
        float[] queryVector = model.embed(input);
        if (!validVector(queryVector)) {
            return Optional.empty();
        }

        List<RouteScore> ranked = prototypes.entrySet().stream()
                .filter(entry -> entry.getValue().length == queryVector.length)
                .map(entry -> new RouteScore(entry.getKey(), cosine(queryVector, entry.getValue())))
                .filter(score -> Double.isFinite(score.score()))
                .sorted(java.util.Comparator.comparingDouble(RouteScore::score).reversed())
                .toList();
        if (ranked.isEmpty()) {
            return Optional.empty();
        }
        RouteScore best = ranked.get(0);
        double second = ranked.size() > 1 ? ranked.get(1).score() : 0.0;
        if (best.score() < minSimilarity || best.score() - second < minMargin) {
            return Optional.empty();
        }

        FineIntent fineIntent = fineIntent(best.route());
        IntentGroup group = intentGroup(best.route());
        double confidence = clamp(best.score());
        return Optional.of(new IntentSignal(
                fineIntent,
                group,
                best.route().name(),
                best.route(),
                IntentSignalSource.EMBEDDING,
                confidence,
                timeSensitivity(request.currentQuestion()),
                best.route() == PlanRoute.DEEP ? AnalysisDepth.DEEP : AnalysisDepth.STANDARD,
                Map.of(IntentSignalSource.EMBEDDING, confidence),
                IntentBounds.entitiesFromTickers(request.tickerCandidates()),
                request.currentQuestion(),
                "意图原型向量与该路由最相似。",
                List.of("EMBEDDING_PROTOTYPE_MATCH")
        ));
    }

    private Map<PlanRoute, float[]> ensurePrototypeVectors(EmbeddingModel model) {
        Map<PlanRoute, float[]> cached = prototypeVectors;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (prototypeVectors != null) {
                return prototypeVectors;
            }
            List<PlanRoute> routes = List.of(
                    PlanRoute.DIRECT, PlanRoute.MARKET, PlanRoute.FUNDAMENTALS, PlanRoute.NEWS, PlanRoute.DEEP);
            // 一次最多 5 条，低于 text-embedding-v4 的 10 条批次上限。
            List<float[]> vectors = model.embed(routes.stream().map(PROTOTYPES::get).toList());
            if (vectors == null || vectors.size() != routes.size()) {
                throw new IllegalStateException("intent prototype embedding count mismatch");
            }
            int dimension = -1;
            EnumMap<PlanRoute, float[]> validated = new EnumMap<>(PlanRoute.class);
            for (int i = 0; i < routes.size(); i++) {
                float[] vector = vectors.get(i);
                if (!validVector(vector) || (dimension >= 0 && vector.length != dimension)) {
                    throw new IllegalStateException("invalid intent prototype embedding");
                }
                dimension = vector.length;
                validated.put(routes.get(i), vector.clone());
            }
            prototypeVectors = Map.copyOf(validated);
            return prototypeVectors;
        }
    }

    private String buildEmbeddingInput(IntentRecognitionRequest request) {
        // 当前问题必须位于截断窗口最前面；历史只使用剩余额度，避免长历史覆盖本轮意图。
        List<String> pieces = new ArrayList<>();
        pieces.add("current: " + request.currentQuestion());
        for (int i = request.recentTurns().size() - 1; i >= 0; i--) {
            pieces.add("recent: " + request.recentTurns().get(i));
        }
        return IntentBounds.text(String.join("\n", pieces), IntentBounds.MAX_RESOLVED_QUERY_CHARS);
    }

    private boolean validVector(float[] vector) {
        if (vector == null || vector.length == 0) {
            return false;
        }
        double norm = 0.0;
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                return false;
            }
            norm += (double) value * value;
        }
        return norm > 1.0e-12;
    }

    private double cosine(float[] left, float[] right) {
        if (!validVector(left) || !validVector(right) || left.length != right.length) {
            return Double.NaN;
        }
        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int i = 0; i < left.length; i++) {
            dot += (double) left[i] * right[i];
            leftNorm += (double) left[i] * left[i];
            rightNorm += (double) right[i] * right[i];
        }
        return dot / Math.sqrt(leftNorm * rightNorm);
    }

    private FineIntent fineIntent(PlanRoute route) {
        return switch (route) {
            case DIRECT -> FineIntent.KNOWLEDGE_EXPLANATION;
            case MARKET -> FineIntent.MARKET_DATA;
            case FUNDAMENTALS -> FineIntent.FUNDAMENTALS;
            case NEWS -> FineIntent.NEWS_EVENT;
            case DEEP -> FineIntent.DEEP_RESEARCH;
        };
    }

    private IntentGroup intentGroup(PlanRoute route) {
        return switch (route) {
            case DIRECT -> IntentGroup.KNOWLEDGE;
            case MARKET -> IntentGroup.MARKET;
            case FUNDAMENTALS -> IntentGroup.FUNDAMENTALS;
            case NEWS -> IntentGroup.NEWS;
            case DEEP -> IntentGroup.RESEARCH;
        };
    }

    private TimeSensitivity timeSensitivity(String query) {
        String lower = query == null ? "" : query.toLowerCase(java.util.Locale.ROOT);
        if (lower.matches(".*(?:实时|现在|当前|right now|real-time|current price).*$")) {
            return TimeSensitivity.REAL_TIME;
        }
        if (lower.matches(".*(?:今天|最新|最近|近期|today|latest|recent).*$")) {
            return TimeSensitivity.RECENT;
        }
        return TimeSensitivity.UNSPECIFIED;
    }

    private static double clamp(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    public record MatchHandle(CompletableFuture<Optional<IntentSignal>> future, long deadlineNanos) {
        static MatchHandle empty() {
            return new MatchHandle(null, 0L);
        }
    }

    private record RouteScore(PlanRoute route, double score) {
    }
}
