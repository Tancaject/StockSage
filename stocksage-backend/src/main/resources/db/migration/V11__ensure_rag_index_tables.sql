-- Baseline-adopted databases may not have the tables previously created by repositories.
-- Flyway now owns both fresh-schema and upgrade DDL.
CREATE TABLE IF NOT EXISTS doc_index (
    file_path   VARCHAR(768) PRIMARY KEY COMMENT 'File path or scheduled source identifier',
    file_hash   CHAR(64) NOT NULL COMMENT 'SHA-256 of the source content',
    chunk_ids   JSON NOT NULL COMMENT 'Milvus document ids generated for this source',
    source_type VARCHAR(32) NOT NULL COMMENT 'manual | scheduled',
    ingested_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at  DATETIME NULL COMMENT 'NULL means never expires',
    INDEX idx_source_type (source_type),
    INDEX idx_expires_at (expires_at)
) ENGINE=InnoDB;

-- ========== Contextual Gist Cache ==========
-- Contextual Retrieval 的 LLM gist 缓存；key 为切片文本 sha256，保证重新入库幂等。
CREATE TABLE IF NOT EXISTS contextual_gist_cache (
    child_text_hash CHAR(64) PRIMARY KEY COMMENT 'sha256(目标切片文本)',
    gist            TEXT NOT NULL COMMENT 'LLM 生成的一句情境说明',
    model           VARCHAR(64) NOT NULL COMMENT '生成 gist 的模型名',
    granularity     VARCHAR(16) NOT NULL COMMENT 'child | parent',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
