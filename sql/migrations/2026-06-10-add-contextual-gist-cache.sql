-- Contextual Retrieval: 子块语义 gist 缓存。
-- 按切片文本 sha256 幂等，重新入库时未变化的切片不重复调用 LLM。
CREATE TABLE IF NOT EXISTS contextual_gist_cache (
    child_text_hash CHAR(64) PRIMARY KEY COMMENT 'sha256(目标切片文本)',
    gist            TEXT NOT NULL COMMENT 'LLM 生成的一句情境说明',
    model           VARCHAR(64) NOT NULL COMMENT '生成 gist 的模型名',
    granularity     VARCHAR(16) NOT NULL COMMENT 'child | parent',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
