package com.stocksage.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.model.entity.VectorDocument;
import com.stocksage.rag.RagService;
import com.stocksage.repository.DocIndexRepository;
import com.stocksage.repository.VectorDocumentRepository;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** 真实 SQL 提交/回滚与独立向量写入，故障不依赖网络或外部供应商。 */
class KnowledgeIngestionTransactionTest {
    private static final String SOURCE = "audit:source";
    private final Map<String, Document> vectors = new LinkedHashMap<>();
    private final AtomicInteger adds = new AtomicInteger();
    private final AtomicInteger deletes = new AtomicInteger();
    private int failAdd = -1;
    private int failDelete = -1;
    private boolean failSql;
    private final java.util.concurrent.atomic.AtomicBoolean expired = new java.util.concurrent.atomic.AtomicBoolean();
    private boolean expireAfterVector;
    private boolean expireAfterSql;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;
    private DocIndexRepository index;
    private VectorDocumentRepository rows;
    private VectorStore store;
    private KnowledgeIngestionService service;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        // JSON 的内容映射由真实 ObjectMapper 执行；H2 用文本列保存，避免不同数据库 JSON 编码差异。
        jdbc.execute("CREATE TABLE doc_index(file_path VARCHAR(768) PRIMARY KEY, file_hash VARCHAR(64) NOT NULL, "
                + "chunk_ids VARCHAR(20000) NOT NULL, source_type VARCHAR(32) NOT NULL, "
                + "ingested_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL, expires_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE vector_documents(vector_id VARCHAR(128) PRIMARY KEY, content TEXT, metadata TEXT)");
        transactions = new DataSourceTransactionManager(dataSource);
        index = transactional(new DocIndexRepository(jdbc, new ObjectMapper()));
        rows = mock(VectorDocumentRepository.class);
        doAnswer(call -> {
            List<VectorDocument> values = call.getArgument(0);
            for (VectorDocument row : values) {
                jdbc.update("INSERT INTO vector_documents VALUES (?, ?, ?)", row.getVectorId(),
                        row.getContentFull(), row.getMetadata());
            }
            if (failSql) throw new IllegalStateException("injected SQL save failure");
            if (expireAfterSql) expired.set(true);
            return values;
        }).when(rows).saveAll(any());
        doAnswer(call -> {
            Collection<String> ids = call.getArgument(0);
            ids.forEach(id -> jdbc.update("DELETE FROM vector_documents WHERE vector_id = ?", id));
            return null;
        }).when(rows).deleteByVectorIdIn(anyCollection());
        when(rows.findByVectorId(anyString())).thenAnswer(call -> findRow(call.getArgument(0)));
        when(rows.findByVectorIdIn(anyCollection())).thenAnswer(call -> {
            Collection<String> ids = call.getArgument(0);
            return ids.stream().map(this::findRow).flatMap(Optional::stream).toList();
        });
        store = mock(VectorStore.class);
        doAnswer(call -> {
            List<Document> documents = call.getArgument(0);
            documents.forEach(document -> vectors.put(document.getId(), document));
            if (adds.incrementAndGet() == failAdd) throw new IllegalStateException("injected partial vector write");
            if (expireAfterVector) expired.set(true);
            return null;
        }).when(store).add(anyList());
        doAnswer(call -> {
            if (deletes.incrementAndGet() == failDelete) throw new IllegalStateException("injected vector cleanup failure");
            Filter.Expression expression = call.getArgument(0);
            String source = (String) ((Filter.Value) ((Filter.Expression) expression.left()).right()).value();
            String keepHash = (String) ((Filter.Value) ((Filter.Expression) expression.right()).right()).value();
            vectors.values().removeIf(document -> source.equals(document.getMetadata().get("source_id"))
                    && !keepHash.equals(document.getMetadata().get("file_hash")));
            return null;
        }).when(store).delete(any(Filter.Expression.class));
        service = transactional(new KnowledgeIngestionService(store, index, rows, new ObjectMapper(), null, transactions));
    }

    @Test
    void failedReplacementKeepsOldSqlAndVectorsAndRecoveryRemovesPartialVersion() {
        ingest("old", 1);
        String oldHash = index.findByFilePath(SOURCE).orElseThrow().fileHash();
        List<String> oldIds = List.copyOf(vectors.keySet());
        adds.set(0);
        failAdd = 2;
        assertThatThrownBy(() -> ingest("new", 11)).hasMessageContaining("partial vector");
        assertThat(index.findByFilePath(SOURCE).orElseThrow().fileHash()).isEqualTo(oldHash);
        assertThat(vectors.keySet()).containsAll(oldIds);
        assertThat(sqlCount()).isEqualTo(1);

        service.reconcileVectors();
        assertThat(vectors.keySet()).containsExactlyElementsOf(oldIds);
        failAdd = -1;
        ingest("new", 11);
        assertThat(sqlCount()).isEqualTo(11);
        assertThat(vectors).hasSize(11);
        assertThat(vectors.keySet()).doesNotContainAnyElementsOf(oldIds);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void expiredReplacementStopsBeforeNextBatchOrCommitAndKeepsPublishedVersion(boolean duringSql) {
        ingest("old", 1);
        String oldHash = index.findByFilePath(SOURCE).orElseThrow().fileHash();
        List<String> oldIds = List.copyOf(vectors.keySet());
        adds.set(0);
        expireAfterSql = duringSql;
        expireAfterVector = !duringSql;
        var deadline = mock(com.stocksage.tool.ToolCallContext.RunDeadline.class);
        var failure = new com.stocksage.exception.ResearchBudgetExceededException(71L,
                com.stocksage.exception.ResearchBudgetExceededException.Reason.DEADLINE);
        when(deadline.remainingMillis()).thenAnswer(call -> {
            if (expired.get()) throw failure;
            return 1000L;
        });
        assertThatThrownBy(() -> com.stocksage.tool.ToolCallContext.withRunDeadline(deadline, () -> {
            ingest("replacement", 11);
            return null;
        })).isSameAs(failure);
        assertThat(adds.get()).isEqualTo(duringSql ? 2 : 1);
        assertThat(index.findByFilePath(SOURCE).orElseThrow().fileHash()).isEqualTo(oldHash);
        assertThat(sqlCount()).isEqualTo(1);
        assertThat(vectors.keySet()).containsAll(oldIds);
        service.reconcileVectors();
        assertThat(vectors.keySet()).containsExactlyElementsOf(oldIds);
        assertThat(com.stocksage.tool.ToolCallContext.currentRunDeadline()).isNull();
    }

    @Test
    void sqlFailureRollsBackReplacementAndInitialFailureKeepsDurableCleanupSource() {
        failSql = true;
        assertThatThrownBy(() -> ingest("first", 1)).hasMessageContaining("SQL save failure");
        assertThat(index.findByFilePath(SOURCE).orElseThrow().fileHash()).isEmpty();
        assertThat(sqlCount()).isZero();
        assertThat(vectors).hasSize(1);
        // 新服务实例只依赖持久化来源，模拟进程恢复后不再有原调用栈。
        KnowledgeIngestionService restarted = transactional(
                new KnowledgeIngestionService(store, index, rows, new ObjectMapper(), null, transactions));
        restarted.reconcileVectors();
        assertThat(vectors).isEmpty();
        failSql = false;
        ingest("old", 1);
        String oldHash = index.findByFilePath(SOURCE).orElseThrow().fileHash();
        failSql = true;
        assertThatThrownBy(() -> ingest("new", 1)).hasMessageContaining("SQL save failure");
        assertThat(index.findByFilePath(SOURCE).orElseThrow().fileHash()).isEqualTo(oldHash);
        assertThat(jdbc.queryForObject("SELECT content FROM vector_documents", String.class)).isEqualTo("old chunk 0");
        restarted.reconcileVectors();
        assertThat(vectors.values()).extracting(Document::getText).containsExactly("old chunk 0");
    }

    @Test
    void staleParentContentIsRebuiltFromSqlAndParentExpansionCannotMixVersions() {
        jdbc.update("INSERT INTO vector_documents VALUES (?, ?, ?)", "parent", "current parent",
                "{\"doc_id\":\"parent\",\"source_id\":\"audit:source\",\"file_hash\":\"new\"}");
        RagService retrieval = mock(RagService.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(retrieval, "vectorDocumentRepository", rows);
        ReflectionTestUtils.setField(retrieval, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(retrieval, "vectorStore", store);
        Document staleParent = Document.builder().text("stale parent")
                .metadata(Map.of("doc_id", "parent", "file_hash", "old")).score(0.8).build();
        List<Document> current = ReflectionTestUtils.invokeMethod(retrieval, "committedCandidates", List.of(staleParent));
        assertThat(current).extracting(Document::getText).containsExactly("current parent");
        assertThat(current.get(0).getMetadata()).containsEntry("file_hash", "new");
        assertThat(current.get(0).getScore()).isEqualTo(0.8);

        Document oldChild = new Document("old child", Map.of("parent_vector_id", "parent",
                "source_id", SOURCE, "file_hash", "old"));
        List<Document> expanded = ReflectionTestUtils.invokeMethod(retrieval, "expandToParentChunks", List.of(oldChild));
        assertThat(expanded).containsExactly(oldChild);
        assertThat((Document) ReflectionTestUtils.invokeMethod(retrieval, "findParentDocument", "missing")).isNull();
        verifyNoInteractions(store);
    }

    @Test
    void flywayRepairCreatesMissingTablesAndPreservesExistingRowsOnRepeat() {
        JdbcDataSource clean = new JdbcDataSource();
        clean.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        ResourceDatabasePopulator migration = new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V11__ensure_rag_index_tables.sql"));
        migration.execute(clean);
        JdbcTemplate schema = new JdbcTemplate(clean);
        schema.update("INSERT INTO contextual_gist_cache VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)",
                "a".repeat(64), "existing gist", "model", "child");
        migration.execute(clean);
        assertThat(schema.queryForObject("SELECT COUNT(*) FROM contextual_gist_cache", Integer.class)).isEqualTo(1);
        assertThat(schema.queryForObject("SELECT COUNT(*) FROM doc_index", Integer.class)).isZero();
    }

    @Test
    void cleanupFailureDoesNotPublishObsoleteCandidatesAndCanBeRetried() {
        ingest("old", 1);
        deletes.set(0);
        failDelete = 2;
        ingest("new", 1);
        assertThat(vectors).hasSize(2);
        RagService retrieval = mock(RagService.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(retrieval, "vectorDocumentRepository", rows);
        ReflectionTestUtils.setField(retrieval, "objectMapper", new ObjectMapper());
        List<Document> committed = ReflectionTestUtils.invokeMethod(retrieval, "committedCandidates",
                new ArrayList<>(vectors.values()));
        assertThat(committed).extracting(Document::getText).containsExactly("new chunk 0");
        failDelete = -1;
        service.reconcileVectors();
        assertThat(vectors).hasSize(1);
    }

    private void ingest(String version, int count) {
        service.ingestDocuments(SOURCE, version,
                IntStream.range(0, count).mapToObj(i -> new Document(version + " chunk " + i)).toList(),
                "manual", null);
    }

    private int sqlCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM vector_documents", Integer.class);
    }

    private Optional<VectorDocument> findRow(String id) {
        return jdbc.query("SELECT * FROM vector_documents WHERE vector_id = ?", (rs, n) -> {
            VectorDocument row = new VectorDocument();
            row.setVectorId(rs.getString("vector_id"));
            row.setContentFull(rs.getString("content"));
            row.setMetadata(rs.getString("metadata"));
            return row;
        }, id).stream().findFirst();
    }

    @SuppressWarnings("unchecked")
    private <T> T transactional(T target) {
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }
}
