package com.stocksage.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.model.entity.VectorDocument;
import com.stocksage.repository.VectorDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * StockSage 知识库的读取路径。
 *
 * <p>检索管线刻意显式展开，而不是隐藏在框架 advisor 后面：
 * 查询改写 -> 可选元数据过滤 -> 向量搜索 -> 可选关键词搜索 -> RRF 融合 -> 重排 ->
 * 父级切片扩展。最终文档随后由 {@code ChatService} 放入带信任边界和总预算的用户上下文。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagService {

    /** Ticker 过滤值只接受规范化市场代码字符，禁止把用户文本拼入过滤表达式。 */
    private static final Pattern SAFE_TICKER_FILTER_VALUE = Pattern.compile("[A-Z0-9.:-]{1,16}");
    private static final Pattern ANNUAL_REPORT = Pattern.compile("(?i)(?<![A-Za-z0-9])10-?K(?![A-Za-z0-9])|annual report|年报|年度报告");
    private static final Pattern QUARTERLY_REPORT = Pattern.compile("(?i)(?<![A-Za-z0-9])10-?Q(?![A-Za-z0-9])|quarterly report|季报|季度报告");
    /** 多目标检索每个目标都要单独召回和重排，上限控制额外开销。 */
    private static final int MAX_TARGETS = 4;

    /** Milvus 语义向量检索入口。 */
    private final VectorStore vectorStore;

    /** 将口语问题改写为召回关键词，失败时保留原文。 */
    private final QueryRewriter queryRewriter;

    /** 对融合候选执行可选 DashScope 语义重排。 */
    private final DashScopeReranker dashScopeReranker;

    /** Apache Lucene 标准 BM25 精确术语召回入口。 */
    private final KeywordSearchService keywordSearchService;

    /** 从 MySQL 镜像读取父级切片完整正文。 */
    private final VectorDocumentRepository vectorDocumentRepository;

    /** 知识库已入库的公司，用于从问题中识别过滤用的 ticker。 */
    private final IndexedCompanies indexedCompanies;

    /** 解析镜像表保存的文档元数据 JSON。 */
    private final ObjectMapper objectMapper;
    private final EmbeddingModel embeddingModel;

    /** 最终交给回答模型的上下文数量上限。 */
    @Value("${stocksage.rag.top-k}")
    private int topK;

    /** 开启重排时，向量/RRF 阶段保留的候选数量。 */
    @Value("${stocksage.rag.rerank.candidate-top-k:20}")
    private int rerankCandidateTopK;

    /** Milvus 向量相似度最低阈值。 */
    @Value("${stocksage.rag.similarity-threshold}")
    private double similarityThreshold;

    /** 是否把 Lucene BM25 召回与向量召回融合。 */
    @Value("${stocksage.rag.hybrid-search.enabled:true}")
    private boolean hybridSearchEnabled;

    /** 关键词路径最多召回的候选数。 */
    @Value("${stocksage.rag.hybrid-search.keyword-top-k:10}")
    private int keywordTopK;

    /** RRF 平滑常数，降低单一路径头部排名的绝对优势。 */
    @Value("${stocksage.rag.hybrid-search.rrf-k:60}")
    private int rrfK;

    /** 是否根据原始问题中的已知 ticker 自动过滤文档。 */
    @Value("${stocksage.rag.metadata-filter.enabled:true}")
    private boolean metadataFilterEnabled;

    /** Captures bound retrieval settings, not which optional stages succeeded for a query. */
    public Map<String, Object> runtimeConfiguration() {
        Map<String, Object> reranker = new LinkedHashMap<>(dashScopeReranker.runtimeConfiguration());
        reranker.put("topN", topK);
        Map<String, Object> settings = new LinkedHashMap<>(Map.of(
                "topK", topK,
                "candidateTopK", candidateTopK(),
                "configuredRerankCandidateTopK", rerankCandidateTopK,
                "similarityThreshold", similarityThreshold,
                "hybridSearchEnabled", hybridSearchEnabled,
                "keywordTopK", keywordTopK,
                "rrfK", rrfK,
                "metadataFilterEnabled", metadataFilterEnabled,
                "reranker", Map.copyOf(reranker)));
        settings.put("queryRewrite", queryRewriter.runtimeConfiguration());
        settings.put("keywordSearch", keywordSearchService.runtimeConfiguration());
        settings.put("embedding", embeddingModel instanceof LocalEmbeddingModel local
                ? local.runtimeConfiguration()
                : Map.of("implementation", embeddingModel.getClass().getName(),
                        "configurationStatus", "UNKNOWN"));
        return Map.copyOf(settings);
    }

    /**
     * 使用自动元数据过滤执行生产检索。
     *
     * @param query 用户原始问题
     * @return 最终可注入回答模型的上下文
     */
    public List<Document> retrieve(String query) {
        return retrieveForEval(query).finalContexts();
    }

    /**
     * 使用指定过滤表达式执行生产检索。
     *
     * @param query 用户原始问题
     * @param filterExpression Milvus 元数据过滤表达式；空值表示不过滤
     * @return 最终可注入回答模型的上下文
     */
    public List<Document> retrieve(String query, String filterExpression) {
        return retrieveForEval(query, filterExpression).finalContexts();
    }

    /** 使用服务器已经规范化的单一 ticker 检索，不再受本类静态美股白名单限制。 */
    public List<Document> retrieveForTicker(String query, String canonicalTicker) {
        String ticker = canonicalTicker == null ? "" : canonicalTicker.strip().toUpperCase(java.util.Locale.ROOT);
        if (!metadataFilterEnabled || !SAFE_TICKER_FILTER_VALUE.matcher(ticker).matches()) {
            return retrieve(query);
        }
        List<String> forms = formsIn(query);
        if (forms.isEmpty()) {
            return retrieve(query, "ticker == '" + ticker + "'");
        }
        return retrieveForTargets(query, forms.stream().map(form -> new Target(ticker, form)).toList()).finalContexts();
    }

    /** DEEP 取证的财报原文检索：标的和表单都由服务端确定，不从问题文本推断。 */
    public List<Document> retrieveForTickerForm(String query, String canonicalTicker, String filingType) {
        String ticker = canonicalTicker == null ? "" : canonicalTicker.strip().toUpperCase(java.util.Locale.ROOT);
        if (!SAFE_TICKER_FILTER_VALUE.matcher(ticker).matches() || !Set.of("10-K", "10-Q").contains(filingType)) {
            throw new IllegalArgumentException("Unsupported filing retrieval target");
        }
        return retrieveForTargets(query, List.of(new Target(ticker, filingType))).finalContexts();
    }

    /**
     * 多标的对比：使用服务器确认的完整标的列表，每家公司都单独召回，不依赖从问题文本里重新识别公司。
     * 公司 × 表单超过上限时放弃按表单拆分，而不是截掉某家公司。
     */
    public List<Document> retrieveForTickers(String query, List<String> canonicalTickers) {
        List<String> tickers = canonicalTickers == null ? List.of() : canonicalTickers.stream()
                .map(ticker -> ticker == null ? "" : ticker.strip().toUpperCase(java.util.Locale.ROOT))
                .filter(ticker -> SAFE_TICKER_FILTER_VALUE.matcher(ticker).matches())
                .distinct().toList();
        if (!metadataFilterEnabled || tickers.isEmpty()) {
            return retrieve(query);
        }
        if (tickers.size() == 1) {
            return retrieveForTicker(query, tickers.get(0));
        }
        return retrieveForTargets(query, expand(tickers, formsIn(query))).finalContexts();
    }

    /**
     * 执行评测检索并自动构建过滤表达式。
     *
     * @param query 用户原始问题
     * @return 包含每个检索阶段结果的评测快照
     */
    public RagRetrievalEvaluation retrieveForEval(String query) {
        List<Target> targets = metadataFilterEnabled ? targets(query) : List.of();
        // 带表单的目标走多目标路径：BM25 不支持表单过滤，需在召回后按元数据筛选。
        if (targets.isEmpty() || (targets.size() == 1 && targets.get(0).filingType() == null)) {
            return retrieveForEval(query, targets.isEmpty() ? null : targets.get(0).filter());
        }
        return retrieveForTargets(query, targets);
    }

    /**
     * 问题涉及多个检索目标（多家公司，或同时比较 10-K 与 10-Q）时，每个目标单独过滤、召回和重排，再轮流合并。
     * 一次混合检索取前 topK 时，相关度高的一方常占满全部名额，另一方完全缺席。
     */
    private RagRetrievalEvaluation retrieveForTargets(String query, List<Target> targets) {
        String retrievalQuery = queryRewriter.rewrite(query);
        int candidateTopK = candidateTopK();
        int perTarget = Math.max(1, (topK + targets.size() - 1) / targets.size());
        List<Document> vectorCandidates = new ArrayList<>();
        List<Document> keywordCandidates = new ArrayList<>();
        List<Document> fusedCandidates = new ArrayList<>();
        List<List<Document>> rankedByTarget = new ArrayList<>();
        for (Target target : targets) {
            List<Document> vector = similaritySearch(retrievalQuery, target.filter(), candidateTopK);
            List<Document> fused = vector;
            if (hybridSearchEnabled) {
                // BM25 索引只有 ticker 字段，表单类型在召回后按元数据筛选；
                // 先多取候选，避免另一种表单的切片占满名额后筛选剩下太少。
                int keywordFetch = target.filingType() == null ? keywordTopK : keywordTopK * 3;
                List<Document> keyword = committedCandidates(
                        keywordSearchService.search(retrievalQuery, keywordFetch, target.keywordFilter())).stream()
                        .filter(target::accepts)
                        .limit(keywordTopK)
                        .toList();
                keywordCandidates.addAll(keyword);
                fused = rrfFusion(vector, keyword);
            }
            vectorCandidates.addAll(vector);
            fusedCandidates.addAll(fused);
            rankedByTarget.add(dashScopeReranker.rerank(retrievalQuery, fused, perTarget));
        }
        List<Document> rerankedContexts = interleave(rankedByTarget, topK);
        List<Document> finalContexts = expandToParentChunks(rerankedContexts);
        String filterExpression = String.join(" || ", targets.stream().map(target -> "(" + target.filter() + ")").toList());
        log.debug("RAG multi-target retrieve query='{}', rewritten='{}', targets={}, final={}",
                query, retrievalQuery, filterExpression, finalContexts.size());
        return new RagRetrievalEvaluation(query, retrievalQuery, filterExpression, hybridSearchEnabled,
                dashScopeReranker.isEnabled(), topK, candidateTopK, vectorCandidates, keywordCandidates,
                fusedCandidates, rerankedContexts, finalContexts);
    }

    /** 各目标按名次轮流取一条，去重后截到 limit，保证每个目标至少占一个位置。 */
    private List<Document> interleave(List<List<Document>> rankedByTarget, int limit) {
        List<Document> merged = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int depth = rankedByTarget.stream().mapToInt(List::size).max().orElse(0);
        for (int rank = 0; rank < depth && merged.size() < limit; rank++) {
            for (List<Document> ranked : rankedByTarget) {
                if (rank < ranked.size() && merged.size() < limit && seen.add(documentKey(ranked.get(rank)))) {
                    merged.add(ranked.get(rank));
                }
            }
        }
        return merged;
    }

    /**
     * 检索上下文，并保留中间候选列表供评估使用。
     * 生产对话调用 {@link #retrieve(String)} 且只消费最终上下文；
     * 评估接口使用这个更丰富的对象为各检索阶段打分。
     *
     * @param query 用户原始问题
     * @param filterExpression 向量和关键词路径共用的可选 ticker 过滤条件
     * @return 查询改写、候选、融合、重排和父块扩展快照
     */
    public RagRetrievalEvaluation retrieveForEval(String query, String filterExpression) {
        // 查询改写是 fail-open 增强；失败时 QueryRewriter 原样返回用户问题。
        String retrievalQuery = queryRewriter.rewrite(query);
        int candidateTopK = candidateTopK();

        List<Document> vectorCandidates = similaritySearch(retrievalQuery, filterExpression, candidateTopK);
        List<Document> keywordCandidates = List.of();
        List<Document> fusedCandidates = vectorCandidates;

        if (hybridSearchEnabled) {
            keywordCandidates = committedCandidates(keywordSearchService.search(retrievalQuery, keywordTopK, filterExpression));
            // RRF 只使用各自排名，不直接混合向量分数与 BM25 score 的不同量纲。
            fusedCandidates = rrfFusion(vectorCandidates, keywordCandidates);
        }

        // 先用精确子块重排，再替换为父块，兼顾召回精度和回答上下文完整性。
        List<Document> rerankedContexts = dashScopeReranker.rerank(retrievalQuery, fusedCandidates, topK);
        List<Document> finalContexts = expandToParentChunks(rerankedContexts);

        log.debug(
                "RAG retrieve query='{}', rewritten='{}', filter={}, vector={}, keyword={}, fused={}, reranked={}, final={}",
                query, retrievalQuery, filterExpression, vectorCandidates.size(), keywordCandidates.size(),
                fusedCandidates.size(), rerankedContexts.size(), finalContexts.size());

        return new RagRetrievalEvaluation(
                query,
                retrievalQuery,
                filterExpression,
                hybridSearchEnabled,
                dashScopeReranker.isEnabled(),
                topK,
                candidateTopK,
                vectorCandidates,
                keywordCandidates,
                fusedCandidates,
                rerankedContexts,
                finalContexts
        );
    }

    /**
     * 从问题中识别检索目标：提到的已入库公司（代码或公司名，被否定的不算），
     * 以及问题提到的表单（10-K、10-Q 或两者）。
     * 未入库公司的代码和普通大写缩写（AI、SEC 等）都不会成为过滤条件。
     */
    List<Target> targets(String query) {
        List<String> tickers = indexedCompanies.tickersIn(query).stream()
                .filter(ticker -> SAFE_TICKER_FILTER_VALUE.matcher(ticker).matches())
                .toList();
        List<Target> targets = expand(tickers, formsIn(query));
        return targets.size() > MAX_TARGETS ? targets.subList(0, MAX_TARGETS) : targets;
    }

    /** 公司 × 表单；超过上限时只按公司拆分，保证每家公司都在。 */
    private static List<Target> expand(List<String> tickers, List<String> forms) {
        if (tickers.size() * Math.max(1, forms.size()) > MAX_TARGETS && tickers.size() > 1) {
            forms = List.of();
        }
        List<Target> targets = new ArrayList<>();
        for (String ticker : tickers.isEmpty() ? java.util.Collections.<String>singletonList(null) : tickers) {
            for (String form : forms.isEmpty() ? java.util.Collections.<String>singletonList(null) : forms) {
                if (ticker != null || form != null) targets.add(new Target(ticker, form));
            }
        }
        return targets;
    }

    /** 问题明确提到的表单；只提一种就只检索这一种，避免另一种表单的大量切片挤占名次。 */
    private static List<String> formsIn(String query) {
        if (query == null) return List.of();
        List<String> forms = new ArrayList<>();
        if (ANNUAL_REPORT.matcher(query).find()) forms.add("10-K");
        if (QUARTERLY_REPORT.matcher(query).find()) forms.add("10-Q");
        return forms;
    }

    /** 一个检索目标：公司和/或表单类型，至少有一项。 */
    record Target(String ticker, String filingType) {
        String filter() {
            String tickerPart = ticker == null ? null : "ticker == '" + ticker + "'";
            String formPart = filingType == null ? null : "filing_type == '" + filingType + "'";
            return tickerPart == null ? formPart : formPart == null ? tickerPart : tickerPart + " && " + formPart;
        }

        String keywordFilter() {
            return ticker == null ? null : "ticker == '" + ticker + "'";
        }

        boolean accepts(Document document) {
            return filingType == null || filingType.equals(document.getMetadata().get("filing_type"));
        }
    }

    /**
     * 使用 RRF（倒数排名融合）融合向量排序和关键词排序。
     * 这样可以让精确股票代码或术语匹配与语义匹配保持竞争力，
     * 同时不把任一检索器单独视为排序真值。
     *
     * @param vectorResults 按向量相似度排序的候选
     * @param keywordResults 按全文相关度排序的候选
     * @return 去重并按累计 RRF 分数排序的候选
     */
    List<Document> rrfFusion(List<Document> vectorResults, List<Document> keywordResults) {
        Map<String, RrfEntry> scoreMap = new LinkedHashMap<>();

        for (int i = 0; i < vectorResults.size(); i++) {
            Document doc = vectorResults.get(i);
            String key = documentKey(doc);
            scoreMap.computeIfAbsent(key, k -> new RrfEntry(doc))
                    .addRank(i + 1);
        }

        for (int i = 0; i < keywordResults.size(); i++) {
            Document doc = keywordResults.get(i);
            String key = documentKey(doc);
            scoreMap.computeIfAbsent(key, k -> new RrfEntry(doc))
                    .addRank(i + 1);
        }

        return scoreMap.values().stream()
                .sorted((a, b) -> Double.compare(b.score(rrfK), a.score(rrfK)))
                .map(RrfEntry::document)
                .limit(candidateTopK())
                .toList();
    }

    /**
     * 如果存在父级切片，则用父级切片替换子级命中。
     * 子级切片提供精确召回，父级切片为最终回答补足本地公告上下文，
     * 避免脆弱的句子级引用。
     *
     * @param results 重排后的子块候选
     * @return 去重后的父块上下文；父块缺失时保留原子块
     */
    private List<Document> expandToParentChunks(List<Document> results) {
        if (results == null || results.isEmpty()) {
            return results;
        }

        List<Document> expanded = new ArrayList<>();
        Set<String> seenParents = new HashSet<>();

        for (Document doc : results) {
            String parentId = metaString(doc, "parent_vector_id");
            if (parentId == null || parentId.isBlank()) {
                expanded.add(doc);
                continue;
            }

            if (seenParents.contains(parentId)) {
                continue;
            }
            seenParents.add(parentId);

            Document parent = findParentDocument(parentId);
            boolean sameVersion = parent != null
                    && java.util.Objects.equals(parent.getMetadata().get("source_id"), doc.getMetadata().get("source_id"))
                    && java.util.Objects.equals(parent.getMetadata().get("file_hash"), doc.getMetadata().get("file_hash"));
            expanded.add(sameVersion ? parent : doc);
        }
        return expanded;
    }

    /**
     * 根据父级向量 ID 查找父级上下文切片。
     *
     * <p>只从已提交的 MySQL 镜像读取；历史向量缺少镜像时需重新摄取，不能绕过发布边界。</p>
     *
     * @param parentVectorId 父块向量 ID
     * @return 父块文档；关系库未命中或查询失败时返回 {@code null}
     */
    private Document findParentDocument(String parentVectorId) {
        try {
            Optional<VectorDocument> storedParent = vectorDocumentRepository.findByVectorId(parentVectorId);
            if (storedParent.isPresent()) {
                VectorDocument parent = storedParent.get();
                return new Document(
                        parent.getContentFull() != null ? parent.getContentFull() : parent.getContentPreview(),
                        parseMetadata(parent.getMetadata(), parentVectorId)
                );
            }

            return null;
        } catch (Exception e) {
            log.debug("Parent chunk lookup failed for {}: {}", parentVectorId, e.getMessage());
            return null;
        }
    }

    /**
     * 解析 MySQL 中保存的文档元数据 JSON。
     *
     * @param metadataJson 镜像表元数据 JSON
     * @param vectorId 用于补齐 doc_id 的父块向量 ID
     * @return 可变元数据 Map；JSON 无效时至少包含 doc_id
     */
    private Map<String, Object> parseMetadata(String metadataJson, String vectorId) {
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("doc_id", vectorId);
        if (metadataJson == null || metadataJson.isBlank()) {
            return fallback;
        }
        try {
            Map<String, Object> metadata = objectMapper.readValue(metadataJson, new TypeReference<>() {});
            metadata.putIfAbsent("doc_id", vectorId);
            return metadata;
        } catch (Exception e) {
            log.debug("Failed to parse parent metadata for {}: {}", vectorId, e.getMessage());
            return fallback;
        }
    }

    /**
     * 生成用于 RRF 去重和融合的文档 key。
     *
     * @param doc 候选文档
     * @return 优先使用 doc_id，否则使用正文哈希
     */
    private String documentKey(Document doc) {
        String docId = metaString(doc, "doc_id");
        if (docId != null && !docId.isBlank()) {
            return docId;
        }
        return String.valueOf(doc.getText().hashCode());
    }

    /**
     * 从文档元数据中读取字符串字段。
     *
     * @param doc 文档
     * @param key 元数据键
     * @return 去除空白的字符串值；字段缺失时返回 {@code null}
     */
    private String metaString(Document doc, String key) {
        Object value = doc.getMetadata().get(key);
        return value == null ? null : String.valueOf(value).trim();
    }

    /**
     * 调用向量库执行相似度检索。
     *
     * @param query 改写后的检索文本
     * @param filterExpression 可选 Milvus 元数据过滤表达式
     * @param searchTopK 候选数量上限
     * @return 满足阈值和过滤条件的向量候选
     */
    private List<Document> similaritySearch(String query, String filterExpression, int searchTopK) {
        SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(searchTopK)
                .similarityThreshold(similarityThreshold);

        if (filterExpression != null && !filterExpression.isBlank()) {
            builder.filterExpression(filterExpression);
        }
        // VectorStore.similaritySearch 最终由 Milvus 实现执行 embedding 与近邻检索。
        return committedCandidates(vectorStore.similaritySearch(builder.build()));
    }

    /** 向量和 BM25 都是派生索引；未提交或已移除的 ID 不得进入回答证据。 */
    private List<Document> committedCandidates(List<Document> candidates) {
        if (candidates.isEmpty()) return candidates;
        Map<String, VectorDocument> committed = vectorDocumentRepository.findByVectorIdIn(
                        candidates.stream().map(this::documentKey).toList()).stream()
                .collect(java.util.stream.Collectors.toMap(VectorDocument::getVectorId, row -> row));
        List<Document> result = new ArrayList<>();
        for (Document candidate : candidates) {
            VectorDocument row = committed.get(documentKey(candidate));
            if (row == null) continue;
            // 排名来自派生索引，正文及版本必须来自同一次读取的 SQL 快照。
            Map<String, Object> metadata = parseMetadata(row.getMetadata(), row.getVectorId());
            candidate.getMetadata().forEach((key, value) -> {
                if (key.startsWith("bm25_") || key.equals("keyword_relevance") || key.equals("retrieval_engine")) {
                    metadata.put(key, value);
                }
            });
            result.add(Document.builder().id(row.getVectorId())
                    .text(row.getContentFull() == null ? row.getContentPreview() : row.getContentFull())
                    .metadata(metadata).score(candidate.getScore()).build());
        }
        return result;
    }

    /**
     * 根据是否启用重排决定候选召回数量。
     *
     * @return 不重排时等于 topK；重排时至少为 topK 的候选上限
     */
    private int candidateTopK() {
        if (!dashScopeReranker.isEnabled()) {
            return topK;
        }
        return Math.max(topK, rerankCandidateTopK);
    }

    /**
     * RRF 融合过程中的单文档累计分数。
     */
    private static class RrfEntry {
        /** 两条召回路径中代表同一 doc_id 的文档。 */
        private final Document document;

        /** 该文档在每条命中路径中的 1-based 排名。 */
        private final List<Integer> ranks = new ArrayList<>();

        /**
         * 保存原始文档引用。
         *
         * @param document 首次遇到该去重键时的文档
         */
        RrfEntry(Document document) {
            this.document = document;
        }

        /**
         * 记录该文档在某一路召回结果中的排名。
         *
         * @param rank 从 1 开始的候选排名
         */
        void addRank(int rank) {
            ranks.add(rank);
        }

        /**
         * 计算倒数排名融合分数。
         *
         * @param k RRF 平滑常数
         * @return 所有召回路径的倒数排名和
         */
        double score(int k) {
            return ranks.stream().mapToDouble(rank -> 1.0 / (k + rank)).sum();
        }

        /** @return 代表该融合结果的原文档 */
        Document document() {
            return document;
        }
    }
}
