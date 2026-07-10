package com.stocksage.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

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

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    private final RowMapper<DocIndexEntry> rowMapper = (rs, rowNum) -> new DocIndexEntry(
            rs.getString("file_path"),
            rs.getString("file_hash"),
            fromJson(rs.getString("chunk_ids")),
            rs.getString("source_type"),
            rs.getTimestamp("ingested_at").toLocalDateTime(),
            rs.getTimestamp("expires_at") == null ? null : rs.getTimestamp("expires_at").toLocalDateTime()
    );

    /**
     * 应用启动时确保轻量索引表存在，避免本地演示环境需要手工执行额外迁移。
     */
    @PostConstruct
    public void ensureTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS doc_index (
                    file_path   VARCHAR(768) PRIMARY KEY,
                    file_hash   CHAR(64) NOT NULL,
                    chunk_ids   JSON NOT NULL,
                    source_type VARCHAR(32) NOT NULL,
                    ingested_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    expires_at  DATETIME NULL,
                    INDEX idx_source_type (source_type),
                    INDEX idx_expires_at (expires_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
    }

    /**
     * 根据来源路径或逻辑 sourceId 查询当前索引快照。
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
     */
    public void deleteByFilePath(String filePath) {
        jdbcTemplate.update("DELETE FROM doc_index WHERE file_path = ?", filePath);
    }

    /**
     * 反序列化切片 ID 列表；解析失败时返回空列表，避免维护任务中断。
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
     * @param chunkIds 实际写入向量库或全文表的切片 ID 列表
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
        public boolean isExpired(LocalDateTime now) {
            return expiresAt != null && !expiresAt.isAfter(now);
        }
    }
}
