package com.stocksage.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 本地知识源到向量切片的索引表仓储。
 *
 * <p>RAG 入库需要知道某个文件当前对应哪些 chunkId，才能在文件变更、过期或重建时
 * 定位旧索引并安全清理。该表是 MySQL 元数据层，不直接存向量。</p>
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class DocIndexRepository {

    /** Jackson 反序列化 {@code chunk_ids} JSON 数组所需的泛型类型。 */
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    /** 执行索引表参数化 SQL 的 Spring JDBC 入口。 */
    private final JdbcTemplate jdbcTemplate;

    /** 负责切片 ID 列表与 JSON 字符串互转。 */
    private final ObjectMapper objectMapper;

    /** 将一行 {@code doc_index} 数据统一映射为不可变索引快照。 */
    private final RowMapper<DocIndexEntry> rowMapper = (rs, rowNum) -> new DocIndexEntry(
            rs.getString("file_path"),
            rs.getString("file_hash"),
            fromJson(rs.getString("chunk_ids")),
            rs.getString("source_type"),
            rs.getTimestamp("ingested_at").toLocalDateTime(),
            rs.getTimestamp("expires_at") == null ? null : rs.getTimestamp("expires_at").toLocalDateTime()
    );

    /** 独立提交来源占位；即使首次写向量时进程退出，维护任务仍能找到该来源。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ensureSource(String sourceId, String sourceType) {
        jdbcTemplate.update("""
                INSERT INTO doc_index (file_path, file_hash, chunk_ids, source_type)
                VALUES (?, '', '[]', ?)
                ON DUPLICATE KEY UPDATE file_path = VALUES(file_path)
                """, sourceId, sourceType);
    }

    /** 摄取和清理共用来源行锁，避免清理另一个正在写入的版本。 */
    public Optional<DocIndexEntry> lockByFilePath(String sourceId) {
        return jdbcTemplate.query("SELECT * FROM doc_index WHERE file_path = ? FOR UPDATE",
                rowMapper, sourceId).stream().findFirst();
    }

    public List<String> findSourceIds() {
        return jdbcTemplate.query("SELECT file_path FROM doc_index", (rs, row) -> rs.getString(1));
    }

    /**
     * 根据来源路径或逻辑 sourceId 查询当前索引快照。
     *
     * @param filePath 文件路径或逻辑来源 ID，也是表主键
     * @return 当前索引快照；来源尚未入库时为空
     */
    public Optional<DocIndexEntry> findByFilePath(String filePath) {
        List<DocIndexEntry> rows = jdbcTemplate.query(
                "SELECT file_path, file_hash, chunk_ids, source_type, ingested_at, expires_at FROM doc_index WHERE file_path = ?",
                rowMapper,
                filePath
        );
        return rows.stream().findFirst();
    }

    /**
     * 查找已过期的临时知识来源，供维护任务清理向量和元数据。
     *
     * @param now 过期判断基准时间，不能为空
     * @return expiresAt 不晚于基准时间的索引快照
     */
    public List<DocIndexEntry> findExpired(LocalDateTime now) {
        return jdbcTemplate.query(
                """
                        SELECT file_path, file_hash, chunk_ids, source_type, ingested_at, expires_at
                        FROM doc_index
                        WHERE expires_at IS NOT NULL AND expires_at <= ?
                        """,
                rowMapper,
                Timestamp.valueOf(now)
        );
    }

    /**
     * 写入或替换一个来源的索引快照。
     *
     * <p>filePath 冲突时原子覆盖哈希、切片 ID、来源类型和时间字段。</p>
     *
     * @param entry 要持久化的完整索引快照
     * @throws IllegalStateException 切片 ID 无法序列化为 JSON 时抛出
     */
    public void save(DocIndexEntry entry) {
        jdbcTemplate.update(
                """
                        INSERT INTO doc_index (file_path, file_hash, chunk_ids, source_type, ingested_at, expires_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                            file_hash = VALUES(file_hash),
                            chunk_ids = VALUES(chunk_ids),
                            source_type = VALUES(source_type),
                            ingested_at = VALUES(ingested_at),
                            expires_at = VALUES(expires_at)
                        """,
                entry.filePath(),
                entry.fileHash(),
                toJson(entry.chunkIds()),
                entry.sourceType(),
                Timestamp.valueOf(entry.ingestedAt()),
                entry.expiresAt() == null ? null : Timestamp.valueOf(entry.expiresAt())
        );
    }

    /**
     * 删除来源索引记录；调用方负责先清理对应切片。
     *
     * @param filePath 要删除的文件路径或逻辑来源 ID
     */
    public void deleteByFilePath(String filePath) {
        jdbcTemplate.update("DELETE FROM doc_index WHERE file_path = ?", filePath);
    }

    /**
     * 反序列化切片 ID 列表；解析失败时返回空列表，避免维护任务中断。
     *
     * @param json 数据库中的 JSON 字符串数组
     * @return 切片 ID 列表；格式损坏时记录警告并返回空列表
     */
    private List<String> fromJson(String json) {
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (Exception e) {
            log.warn("Failed to parse doc_index.chunk_ids: {}", json, e);
            return List.of();
        }
    }

    /**
     * 将切片 ID 列表保存为 JSON，便于 MySQL 一行记录描述一个来源。
     *
     * @param chunkIds 切片 ID 列表；null 按空列表处理
     * @return JSON 字符串数组
     * @throws IllegalStateException Jackson 序列化失败时抛出
     */
    private String toJson(List<String> chunkIds) {
        try {
            return objectMapper.writeValueAsString(chunkIds == null ? List.of() : chunkIds);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize chunk ids", e);
        }
    }

    /**
     * 单个文件或临时来源的索引快照。
     *
     * @param filePath 文件路径或逻辑来源 ID，也是索引表主键
     * @param fileHash 来源内容的 SHA-256 哈希，用于判断是否变化
     * @param chunkIds 实际写入向量库或全文表的切片 ID 列表
     * @param sourceType 来源分类，例如本地文档或临时上传
     * @param ingestedAt 本次索引写入时间
     * @param expiresAt 为空表示永久知识源；非空表示可由维护任务按时清理
     */
    public record DocIndexEntry(
            String filePath,
            String fileHash,
            List<String> chunkIds,
            String sourceType,
            LocalDateTime ingestedAt,
            LocalDateTime expiresAt
    ) {
        /**
         * 判断该来源在指定时间点是否已经到期。
         *
         * @param now 判断基准时间，不能为空
         * @return expiresAt 非空且不晚于 now 时为 true
         */
        public boolean isExpired(LocalDateTime now) {
            return expiresAt != null && !expiresAt.isAfter(now);
        }
    }
}
