package com.stocksage.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.rag.Bm25IndexUpdate.IndexedDocument;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.cjk.CJKAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherFactory;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于 Apache Lucene {@link BM25Similarity} 的标准 BM25 关键词检索。
 *
 * <p>MySQL {@code vector_documents} 是事实源；本服务在应用启动时重建内存 Lucene
 * 索引，并在知识入库事务提交后增量更新。Lucene 不可用时返回空列表，由
 * {@link RagService} 自然退化为向量召回，不再回退到 MySQL FULLTEXT 近似评分。</p>
 */
@Slf4j
@Service
public class KeywordSearchService {

    private static final String FIELD_DOCUMENT_ID = "document_id";
    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_METADATA = "metadata";
    private static final String FIELD_TICKER = "ticker";
    private static final String INDEX_VERSION = "lucene-9.12.3-cjk-v1";
    private static final int MAX_QUERY_TERMS = 64;
    private static final long REBUILD_RETRY_NANOS = TimeUnit.SECONDS.toNanos(30);

    private static final Pattern TICKER_FILTER_PATTERN =
            Pattern.compile("ticker\\s*==\\s*'([^']+)'");
    private static final Pattern SUPPORTED_FILTER_PATTERN = Pattern.compile(
            "\\s*ticker\\s*==\\s*'[^']+'(?:\\s*\\|\\|\\s*ticker\\s*==\\s*'[^']+')*\\s*");

    private static final String REBUILD_SQL = """
            SELECT id, redis_key, content_full, content_preview, metadata
            FROM vector_documents
            ORDER BY id
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final float k1;
    private final float b;
    private final Analyzer analyzer;
    private final BM25Similarity similarity;
    private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock();

    private volatile IndexState indexState;
    private volatile boolean available;
    private volatile long nextRebuildAttemptNanos;

    public KeywordSearchService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            @Value("${stocksage.rag.hybrid-search.enabled:true}") boolean enabled,
            @Value("${stocksage.rag.hybrid-search.bm25.k1:1.2}") float k1,
            @Value("${stocksage.rag.hybrid-search.bm25.b:0.75}") float b) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.k1 = k1;
        this.b = b;
        this.analyzer = new CJKAnalyzer();
        this.similarity = new BM25Similarity(k1, b);
    }

    /** 应用依赖完成初始化后，从 MySQL 事实源完整重建 BM25 索引。 */
    @EventListener(ApplicationReadyEvent.class)
    public void initializeIndex() {
        rebuildFromDatabase(true);
    }

    /**
     * 只在知识入库事务成功提交后更新派生索引，避免 Lucene 暴露已回滚的数据。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void applyCommittedUpdate(Bm25IndexUpdate update) {
        if (!enabled || update == null
                || (update.deletedDocumentIds().isEmpty() && update.upsertedDocuments().isEmpty())) {
            return;
        }
        if (!ensureAvailable()) {
            return;
        }

        Lock writeLock = lifecycleLock.writeLock();
        writeLock.lock();
        try {
            IndexState current = indexState;
            if (!available || current == null) {
                return;
            }

            for (String documentId : update.deletedDocumentIds()) {
                if (documentId != null && !documentId.isBlank()) {
                    current.writer().deleteDocuments(new Term(FIELD_DOCUMENT_ID, documentId.trim()));
                }
            }
            for (IndexedDocument document : update.upsertedDocuments()) {
                org.apache.lucene.document.Document luceneDocument = toLuceneDocument(document);
                if (luceneDocument != null) {
                    current.writer().updateDocument(
                            new Term(FIELD_DOCUMENT_ID, document.documentId()),
                            luceneDocument
                    );
                }
            }
            current.writer().commit();
            current.searcherManager().maybeRefreshBlocking();
        } catch (Exception e) {
            markUnavailable();
            log.warn("Lucene BM25 incremental update failed; vector retrieval remains available: {}", e.getMessage());
        } finally {
            writeLock.unlock();
        }
    }

    public List<Document> search(String query, int topK) {
        return search(query, topK, null);
    }

    /**
     * 执行 Lucene BM25 检索，并复用向量路径的 ticker 过滤边界。
     *
     * @return BM25 候选；功能关闭、索引不可用或过滤表达式不受支持时为空
     */
    public List<Document> search(String query, int topK, String filterExpression) {
        if (!enabled || query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }
        if (filterExpression != null && !filterExpression.isBlank()
                && !SUPPORTED_FILTER_PATTERN.matcher(filterExpression).matches()) {
            log.debug("BM25 search skipped because filter expression is unsupported: {}", filterExpression);
            return List.of();
        }
        if (!ensureAvailable()) {
            return List.of();
        }

        try {
            Query luceneQuery = buildQuery(query, extractTickerFilters(filterExpression));
            if (luceneQuery == null) {
                return List.of();
            }
            return executeSearch(luceneQuery, topK);
        } catch (Exception e) {
            markUnavailable();
            log.warn("Lucene BM25 search failed; vector retrieval remains available: {}", e.getMessage());
            return List.of();
        }
    }

    private List<Document> executeSearch(Query query, int topK) throws IOException {
        Lock readLock = lifecycleLock.readLock();
        readLock.lock();
        IndexSearcher searcher = null;
        SearcherManager searcherManager = null;
        try {
            IndexState current = indexState;
            if (!available || current == null) {
                return List.of();
            }
            searcherManager = current.searcherManager();
            searcher = searcherManager.acquire();
            TopDocs topDocs = searcher.search(query, topK);
            List<Document> results = new ArrayList<>(topDocs.scoreDocs.length);

            for (ScoreDoc hit : topDocs.scoreDocs) {
                org.apache.lucene.document.Document stored = searcher.storedFields().document(hit.doc);
                String documentId = stored.get(FIELD_DOCUMENT_ID);
                String content = stored.get(FIELD_CONTENT);
                String metadataJson = stored.get(FIELD_METADATA);

                Map<String, Object> diagnostics = new LinkedHashMap<>();
                diagnostics.put("doc_id", documentId == null ? "" : documentId);
                diagnostics.put("source", "keyword_search");
                diagnostics.put("keyword_relevance", hit.score);
                diagnostics.put("retrieval_engine", "lucene_bm25");
                diagnostics.put("bm25_score", hit.score);
                diagnostics.put("bm25_k1", k1);
                diagnostics.put("bm25_b", b);
                diagnostics.put("bm25_index_version", INDEX_VERSION);

                results.add(new Document(
                        content == null ? "" : content,
                        mergeMetadata(metadataJson, diagnostics)
                ));
            }
            return results;
        } finally {
            if (searcher != null && searcherManager != null) {
                searcherManager.release(searcher);
            }
            readLock.unlock();
        }
    }

    private Query buildQuery(String query, List<String> tickerFilters) throws IOException {
        Set<String> terms = analyze(query);
        if (terms.isEmpty()) {
            return null;
        }

        BooleanQuery.Builder lexical = new BooleanQuery.Builder();
        for (String term : terms) {
            lexical.add(new TermQuery(new Term(FIELD_CONTENT, term)), BooleanClause.Occur.SHOULD);
        }
        lexical.setMinimumNumberShouldMatch(1);

        BooleanQuery.Builder combined = new BooleanQuery.Builder();
        combined.add(lexical.build(), BooleanClause.Occur.MUST);
        if (!tickerFilters.isEmpty()) {
            BooleanQuery.Builder tickerQuery = new BooleanQuery.Builder();
            for (String ticker : tickerFilters) {
                tickerQuery.add(
                        new TermQuery(new Term(FIELD_TICKER, ticker)),
                        BooleanClause.Occur.SHOULD
                );
            }
            tickerQuery.setMinimumNumberShouldMatch(1);
            combined.add(tickerQuery.build(), BooleanClause.Occur.FILTER);
        }
        return combined.build();
    }

    private Set<String> analyze(String query) throws IOException {
        Set<String> terms = new LinkedHashSet<>();
        try (TokenStream tokenStream = analyzer.tokenStream(FIELD_CONTENT, query)) {
            CharTermAttribute termAttribute = tokenStream.addAttribute(CharTermAttribute.class);
            tokenStream.reset();
            while (tokenStream.incrementToken() && terms.size() < MAX_QUERY_TERMS) {
                String term = termAttribute.toString();
                if (!term.isBlank()) {
                    terms.add(term);
                }
            }
            tokenStream.end();
        }
        return terms;
    }

    private List<String> extractTickerFilters(String filterExpression) {
        if (filterExpression == null || filterExpression.isBlank()) {
            return List.of();
        }
        Matcher matcher = TICKER_FILTER_PATTERN.matcher(filterExpression);
        Set<String> tickers = new LinkedHashSet<>();
        while (matcher.find()) {
            String ticker = matcher.group(1).trim().toUpperCase(Locale.ROOT);
            if (!ticker.isBlank()) {
                tickers.add(ticker);
            }
        }
        return List.copyOf(tickers);
    }

    private boolean ensureAvailable() {
        if (available && indexState != null) {
            return true;
        }
        if (System.nanoTime() < nextRebuildAttemptNanos) {
            return false;
        }
        rebuildFromDatabase(false);
        return available && indexState != null;
    }

    private void rebuildFromDatabase(boolean force) {
        if (!enabled) {
            return;
        }

        Lock writeLock = lifecycleLock.writeLock();
        writeLock.lock();
        try {
            if (!force && available && indexState != null) {
                return;
            }
            List<IndexedDocument> documents = jdbcTemplate.query(REBUILD_SQL, (rs, rowNum) -> {
                String documentId = rs.getString("redis_key");
                if (documentId == null || documentId.isBlank()) {
                    documentId = "mysql:" + rs.getLong("id");
                }
                String content = rs.getString("content_full");
                if (content == null) {
                    content = rs.getString("content_preview");
                }
                return new IndexedDocument(documentId, content, rs.getString("metadata"));
            });

            IndexState replacement = buildIndex(documents);
            IndexState previous = indexState;
            indexState = replacement;
            available = true;
            nextRebuildAttemptNanos = 0L;
            closeIndexState(previous);
            log.info(
                    "Lucene BM25 index rebuilt: documents={}, analyzer=CJKAnalyzer, k1={}, b={}, version={}",
                    documents.size(), k1, b, INDEX_VERSION
            );
        } catch (Exception e) {
            markUnavailable();
            log.warn("Lucene BM25 rebuild failed; vector retrieval remains available: {}", e.getMessage());
        } finally {
            writeLock.unlock();
        }
    }

    private IndexState buildIndex(List<IndexedDocument> documents) throws IOException {
        Directory directory = new ByteBuffersDirectory();
        IndexWriter writer = null;
        SearcherManager searcherManager = null;
        try {
            IndexWriterConfig config = new IndexWriterConfig(analyzer)
                    .setOpenMode(IndexWriterConfig.OpenMode.CREATE)
                    .setSimilarity(similarity);
            writer = new IndexWriter(directory, config);
            for (IndexedDocument document : documents) {
                org.apache.lucene.document.Document luceneDocument = toLuceneDocument(document);
                if (luceneDocument != null) {
                    writer.addDocument(luceneDocument);
                }
            }
            writer.commit();
            searcherManager = new SearcherManager(writer, createSearcherFactory());
            return new IndexState(directory, writer, searcherManager);
        } catch (Exception e) {
            closeIndexState(new IndexState(directory, writer, searcherManager));
            if (e instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("Failed to build Lucene BM25 index", e);
        }
    }

    private SearcherFactory createSearcherFactory() {
        return new SearcherFactory() {
            @Override
            public IndexSearcher newSearcher(IndexReader reader, IndexReader previousReader) throws IOException {
                IndexSearcher searcher = super.newSearcher(reader, previousReader);
                searcher.setSimilarity(similarity);
                return searcher;
            }
        };
    }

    private org.apache.lucene.document.Document toLuceneDocument(IndexedDocument document) {
        if (document == null || document.documentId().isBlank()) {
            return null;
        }

        org.apache.lucene.document.Document luceneDocument = new org.apache.lucene.document.Document();
        luceneDocument.add(new StringField(FIELD_DOCUMENT_ID, document.documentId(), Field.Store.YES));
        luceneDocument.add(new TextField(FIELD_CONTENT, document.content(), Field.Store.YES));
        luceneDocument.add(new StoredField(FIELD_METADATA, document.metadataJson()));
        addTickerFields(luceneDocument, document.metadataJson());
        return luceneDocument;
    }

    private void addTickerFields(org.apache.lucene.document.Document document, String metadataJson) {
        Map<String, Object> metadata = parseMetadata(metadataJson);
        Object rawTicker = metadata.get(FIELD_TICKER);
        if (rawTicker instanceof Iterable<?> tickers) {
            for (Object ticker : tickers) {
                addTickerField(document, ticker);
            }
            return;
        }
        addTickerField(document, rawTicker);
    }

    private void addTickerField(org.apache.lucene.document.Document document, Object rawTicker) {
        if (rawTicker == null) {
            return;
        }
        String ticker = String.valueOf(rawTicker).trim().toUpperCase(Locale.ROOT);
        if (!ticker.isBlank()) {
            document.add(new StringField(FIELD_TICKER, ticker, Field.Store.NO));
        }
    }

    private Map<String, Object> mergeMetadata(
            String metadataJson,
            Map<String, Object> retrievalDiagnostics) {
        Map<String, Object> merged = new LinkedHashMap<>(parseMetadata(metadataJson));
        merged.putAll(retrievalDiagnostics);
        return merged;
    }

    private Map<String, Object> parseMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> metadata = objectMapper.readValue(
                    metadataJson,
                    new TypeReference<Map<String, Object>>() {}
            );
            return metadata == null ? Map.of() : metadata;
        } catch (Exception e) {
            log.debug("Ignoring invalid RAG metadata while building BM25 index: {}", e.getMessage());
            return Map.of();
        }
    }

    private void markUnavailable() {
        available = false;
        nextRebuildAttemptNanos = System.nanoTime() + REBUILD_RETRY_NANOS;
    }

    @PreDestroy
    public void close() {
        Lock writeLock = lifecycleLock.writeLock();
        writeLock.lock();
        try {
            IndexState current = indexState;
            indexState = null;
            available = false;
            closeIndexState(current);
            analyzer.close();
        } finally {
            writeLock.unlock();
        }
    }

    private void closeIndexState(IndexState state) {
        if (state == null) {
            return;
        }
        closeQuietly(state.searcherManager(), "searcher manager");
        closeQuietly(state.writer(), "index writer");
        closeQuietly(state.directory(), "directory");
    }

    private void closeQuietly(AutoCloseable closeable, String resourceName) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception e) {
            log.debug("Failed to close Lucene {}: {}", resourceName, e.getMessage());
        }
    }

    private record IndexState(
            Directory directory,
            IndexWriter writer,
            SearcherManager searcherManager) {
    }
}
