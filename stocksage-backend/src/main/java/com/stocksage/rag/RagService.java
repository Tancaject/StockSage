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
 * 父级切片扩展。最终文档随后由 {@code ChatService} 作为参考性 {@code SystemMessage}
 * 上下注入。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagService {

    private static final Pattern TICKER_PATTERN = Pattern.compile("\\b([A-Z]{2,5})\\b");
    private static final Set<String> KNOWN_TICKERS = Set.of(
            "AAPL", "MSFT", "NVDA", "GOOGL", "GOOG", "AMZN", "META", "TSLA",
            "BRK", "JPM", "V", "UNH", "XOM", "JNJ", "WMT", "PG", "MA", "HD",
            "CVX", "MRK", "ABBV", "LLY", "PEP", "KO", "COST", "AVGO", "TMO",
            "MCD", "CSCO", "ACN", "ABT", "DHR", "CRM", "NKE", "TXN", "AMD",
            "INTC", "QCOM", "NFLX", "ADBE", "PYPL", "DIS", "BA", "GS", "MS", "MU"
    );

    private final VectorStore vectorStore;
    private final QueryRewriter queryRewriter;
    private final DashScopeReranker dashScopeReranker;
    private final KeywordSearchService keywordSearchService;
    private final VectorDocumentRepository vectorDocumentRepository;
    private final ObjectMapper objectMapper;

    @Value("${stocksage.rag.top-k}")
    private int topK;

    @Value("${stocksage.rag.rerank.candidate-top-k:20}")
    private int rerankCandidateTopK;

    @Value("${stocksage.rag.similarity-threshold}")
    private double similarityThreshold;

    @Value("${stocksage.rag.hybrid-search.enabled:true}")
    private boolean hybridSearchEnabled;

    @Value("${stocksage.rag.hybrid-search.keyword-top-k:10}")
    private int keywordTopK;

    @Value("${stocksage.rag.hybrid-search.rrf-k:60}")
    private int rrfK;

    @Value("${stocksage.rag.metadata-filter.enabled:true}")
    private boolean metadataFilterEnabled;

    /**
     * 使用自动元数据过滤执行生产检索。
     */
    public List<Document> retrieve(String query) {
        return retrieveForEval(query).finalContexts();
    }

    /**
     * 使用指定过滤表达式执行生产检索。
     */
    public List<Document> retrieve(String query, String filterExpression) {
        return retrieveForEval(query, filterExpression).finalContexts();
    }

    /**
     * 执行评测检索并自动构建过滤表达式。
     */
    public RagRetrievalEvaluation retrieveForEval(String query) {
        String filterExpression = metadataFilterEnabled ? buildAutoFilter(query) : null;
        return retrieveForEval(query, filterExpression);
    }

    /**
     * 检索上下文，并保留中间候选列表供评估使用。
     * 生产对话调用 {@link #retrieve(String)} 且只消费最终上下文；
     * 评估接口使用这个更丰富的对象为各检索阶段打分。
     */
    public RagRetrievalEvaluation retrieveForEval(String query, String filterExpression) {
        String retrievalQuery = queryRewriter.rewrite(query);
        int candidateTopK = candidateTopK();

        List<Document> vectorCandidates = similaritySearch(retrievalQuery, filterExpression, candidateTopK);
        List<Document> keywordCandidates = List.of();
        List<Document> fusedCandidates = vectorCandidates;

        if (hybridSearchEnabled) {
            keywordCandidates = keywordSearchService.search(retrievalQuery, keywordTopK, filterExpression);
            fusedCandidates = rrfFusion(vectorCandidates, keywordCandidates);
        }

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
     */
    private String metaString(Document doc, String key) {
        Object value = doc.getMetadata().get(key);
        return value == null ? null : String.valueOf(value).trim();
    }

    /**
     * 调用向量库执行相似度检索。
     */
    private List<Document> similaritySearch(String query, String filterExpression, int searchTopK) {
        SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(searchTopK)
                .similarityThreshold(similarityThreshold);

        if (filterExpression != null && !filterExpression.isBlank()) {
            builder.filterExpression(filterExpression);
        }
        return vectorStore.similaritySearch(builder.build());
    }

    /**
     * 根据是否启用重排决定候选召回数量。
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
        private final Document document;
        private final List<Integer> ranks = new ArrayList<>();

        /** 保存原始文档引用。 */
        RrfEntry(Document document) {
            this.document = document;
        }

        /** 记录该文档在某一路召回结果中的排名。 */
        void addRank(int rank) {
            ranks.add(rank);
        }

        /** 计算倒数排名融合分数。 */
        double score(int k) {
            return ranks.stream().mapToDouble(rank -> 1.0 / (k + rank)).sum();
        }

        /** 返回代表该融合结果的文档。 */
        Document document() {
            return document;
        }
    }
}
