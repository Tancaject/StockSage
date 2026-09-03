package com.stocksage.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.model.entity.VectorDocument;
import com.stocksage.repository.VectorDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
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
import java.util.regex.Matcher;
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

    /** 从用户原文识别 2 到 5 位大写美股代码。 */
    private static final Pattern TICKER_PATTERN = Pattern.compile("\\b([A-Z]{2,5})\\b");
    /** Ticker 过滤值只接受规范化市场代码字符，禁止把用户文本拼入过滤表达式。 */
    private static final Pattern SAFE_TICKER_FILTER_VALUE = Pattern.compile("[A-Z0-9.:-]{1,16}");

    /** 允许自动生成元数据过滤器的保守 ticker 白名单。 */
    private static final Set<String> KNOWN_TICKERS = Set.of(
            "AAPL", "MSFT", "NVDA", "GOOGL", "GOOG", "AMZN", "META", "TSLA",
            "BRK", "JPM", "V", "UNH", "XOM", "JNJ", "WMT", "PG", "MA", "HD",
            "CVX", "MRK", "ABBV", "LLY", "PEP", "KO", "COST", "AVGO", "TMO",
            "MCD", "CSCO", "ACN", "ABT", "DHR", "CRM", "NKE", "TXN", "AMD",
            "INTC", "QCOM", "NFLX", "ADBE", "PYPL", "DIS", "BA", "GS", "MS", "MU"
    );

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

    /** 解析镜像表保存的文档元数据 JSON。 */
    private final ObjectMapper objectMapper;

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
        return retrieve(query, "ticker == '" + ticker + "'");
    }

    /**
     * 执行评测检索并自动构建过滤表达式。
     *
     * @param query 用户原始问题
     * @return 包含每个检索阶段结果的评测快照
     */
    public RagRetrievalEvaluation retrieveForEval(String query) {
        String filterExpression = metadataFilterEnabled ? buildAutoFilter(query) : null;
        return retrieveForEval(query, filterExpression);
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
            keywordCandidates = keywordSearchService.search(retrievalQuery, keywordTopK, filterExpression);
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
     * 当查询包含已知美股代码时，构建保守的股票代码元数据过滤器。
     * 未知大写词会被忽略，避免把 AI 或 SEC 等缩写误当作过滤条件。
     *
     * @param query 用户原始问题
     * @return ticker 等值/OR 表达式；没有可信 ticker 时返回 {@code null}
     */
    String buildAutoFilter(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }

        Matcher matcher = TICKER_PATTERN.matcher(query);
        List<String> tickers = new ArrayList<>();
        while (matcher.find()) {
            String candidate = matcher.group(1);
            if (KNOWN_TICKERS.contains(candidate)) {
                tickers.add(candidate);
            }
        }

        if (tickers.isEmpty()) {
            return null;
        }
        if (tickers.size() == 1) {
            return "ticker == '" + tickers.get(0) + "'";
        }
        return tickers.stream()
                .map(t -> "ticker == '" + t + "'")
                .reduce((a, b) -> a + " || " + b)
                .orElse(null);
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
            expanded.add(parent != null ? parent : doc);
        }
        return expanded;
    }

    /**
     * 根据父级向量 ID 查找父级上下文切片。
     *
     * <p>优先从 MySQL 镜像表读取完整文本；如果历史数据没有镜像，再尝试向量库过滤查询。</p>
     *
     * @param parentVectorId 父块向量 ID
     * @return 父块文档；两种存储都未命中或查询失败时返回 {@code null}
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

            // 历史数据可能只有 Milvus 记录；用 doc_id 过滤做兼容性回查。
            SearchRequest request = SearchRequest.builder()
                    .query("")
                    .topK(1)
                    .filterExpression("doc_id == '" + parentVectorId + "'")
                    .similarityThreshold(0.0)
                    .build();
            List<Document> found = vectorStore.similaritySearch(request);
            return found.isEmpty() ? null : found.get(0);
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
        return vectorStore.similaritySearch(builder.build());
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
