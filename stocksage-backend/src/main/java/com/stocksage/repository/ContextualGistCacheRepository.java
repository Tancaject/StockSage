package com.stocksage.repository;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Contextual Retrieval 的 gist 缓存仓储。
 *
 * <p>以切片文本的 sha256 为主键缓存 LLM 生成的情境说明，
 * 保证重新入库时未变化的切片不会重复付一次 LLM 调用。
 * 该表是纯缓存：删除后下次入库会自动重建。</p>
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class ContextualGistCacheRepository {

    /** 执行缓存表 DDL 和参数化读写 SQL 的 Spring JDBC 入口。 */
    private final JdbcTemplate jdbcTemplate;

    /**
     * 应用启动时确保缓存表存在，与 doc_index 一样免去本地环境手工迁移。
     *
     * <p>{@link PostConstruct} 自动调用；建表失败会阻止该仓储完成初始化。</p>
     */
    @PostConstruct
    public void ensureTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS contextual_gist_cache (
                    child_text_hash CHAR(64) PRIMARY KEY,
                    gist            TEXT NOT NULL,
                    model           VARCHAR(64) NOT NULL,
                    granularity     VARCHAR(16) NOT NULL,
                    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
    }

    /**
     * 按切片文本哈希查询已缓存的 gist。
     *
     * @param childTextHash 子切片原文的 SHA-256 哈希
     * @return 已生成的情境说明；缓存未命中时为空
     */
    public Optional<String> findGist(String childTextHash) {
        List<String> rows = jdbcTemplate.query(
                "SELECT gist FROM contextual_gist_cache WHERE child_text_hash = ?",
                (rs, rowNum) -> rs.getString("gist"),
                childTextHash
        );
        return rows.stream().findFirst();
    }

    /**
     * 写入或覆盖一条 gist 缓存。
     *
     * <p>同一哈希意味着源文本相同，重复写入直接覆盖即可。</p>
     *
     * @param childTextHash 子切片原文的 SHA-256 哈希，也是缓存主键
     * @param gist LLM 生成的简短情境说明
     * @param model 生成 gist 的模型名，便于质量追踪
     * @param granularity 情境生成粒度，例如文档级或章节级
     */
    public void save(String childTextHash, String gist, String model, String granularity) {
        jdbcTemplate.update(
                """
                        INSERT INTO contextual_gist_cache (child_text_hash, gist, model, granularity)
                        VALUES (?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                            gist = VALUES(gist),
                            model = VALUES(model),
                            granularity = VALUES(granularity)
                        """,
                childTextHash, gist, model, granularity
        );
    }
}
