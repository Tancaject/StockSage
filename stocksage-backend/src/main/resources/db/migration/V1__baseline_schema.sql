-- Flyway V1 基线：新建数据库所需的完整 StockSage schema。
-- 既有数据库由 baseline-on-migrate 在版本 1 处 baseline，本文件不会重复执行；
-- 全新空库则由本文件一次性建全。此后的 schema 变更请新增 V2__、V3__ 迁移文件。

-- ========== Users ==========
CREATE TABLE IF NOT EXISTS users (
    user_id     VARCHAR(32) PRIMARY KEY,
    nickname    VARCHAR(64),
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB;

-- Mock user for demo
INSERT IGNORE INTO users (user_id, nickname) VALUES ('u_001', 'Demo User');

-- ========== Conversations ==========
CREATE TABLE IF NOT EXISTS conversations (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     VARCHAR(32) NOT NULL,
    origin      VARCHAR(16) NOT NULL DEFAULT 'chat',
    title       VARCHAR(128),
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id),
    INDEX idx_conversation_user_origin_updated (user_id, origin, updated_at)
) ENGINE=InnoDB;

-- ========== Messages ==========
CREATE TABLE IF NOT EXISTS messages (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id   BIGINT NOT NULL,
    role              VARCHAR(16) NOT NULL COMMENT 'user | assistant | system',
    content           TEXT NOT NULL,
    token_count       INT,
    trace_id          VARCHAR(64),
    model_tier        VARCHAR(16),
    model_name        VARCHAR(64),
    created_at        DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_conversation_id (conversation_id),
    INDEX idx_conversation_created_at (conversation_id, created_at),
    INDEX idx_trace_id (trace_id),
    INDEX idx_model_name (model_name)
) ENGINE=InnoDB;

-- ========== Investment Report Versions ==========
CREATE TABLE IF NOT EXISTS investment_report_versions (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id             VARCHAR(32) NOT NULL,
    conversation_id     BIGINT,
    ticker              VARCHAR(32) NOT NULL,
    user_query          TEXT,
    recommendation      VARCHAR(32),
    report_version      INT NOT NULL,
    data_snapshot_hash  VARCHAR(64) NOT NULL,
    context_hash        VARCHAR(64) NOT NULL,
    model_tier          VARCHAR(16),
    model_name          VARCHAR(64),
    report_json         JSON NOT NULL,
    generated_at        DATETIME DEFAULT CURRENT_TIMESTAMP,
    created_at          DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_report_snapshot_context (user_id, ticker, data_snapshot_hash, context_hash),
    INDEX idx_report_user_created (user_id, created_at),
    INDEX idx_report_user_ticker_version (user_id, ticker, report_version),
    INDEX idx_report_hashes (data_snapshot_hash, context_hash)
) ENGINE=InnoDB;

-- ========== Research Tasks ==========
CREATE TABLE IF NOT EXISTS research_tasks (
    id                        BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id                   VARCHAR(32) NOT NULL,
    conversation_id           BIGINT,
    idempotency_key           VARCHAR(191) NOT NULL,
    ticker                    VARCHAR(32) NOT NULL,
    status                    VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    stage                     VARCHAR(32) NOT NULL DEFAULT 'CREATED',
    attempts                  INT NOT NULL DEFAULT 0,
    lease_token               VARCHAR(64),
    payload_json              JSON NOT NULL,
    error_message             TEXT,
    result_report_version_id  BIGINT,
    started_at                DATETIME,
    completed_at              DATETIME,
    heartbeat_at              DATETIME DEFAULT CURRENT_TIMESTAMP,
    created_at                DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at                DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_research_task_idempotency (idempotency_key),
    INDEX idx_research_task_user_created (user_id, created_at),
    INDEX idx_research_task_status_stage (status, stage),
    INDEX idx_research_task_heartbeat (status, heartbeat_at),
    INDEX idx_research_task_ticker_created (ticker, created_at)
) ENGINE=InnoDB;

-- ========== User Profiles (Long-term Memory) ==========
CREATE TABLE IF NOT EXISTS user_profiles (
    user_id           VARCHAR(32) PRIMARY KEY,
    holdings          JSON COMMENT 'Stock codes the user holds, e.g. ["sh.600519","sz.300750"]',
    watch_list        JSON COMMENT 'Stock codes the user watches',
    risk_preference   VARCHAR(32) COMMENT 'conservative | moderate | aggressive',
    profile_summary   TEXT COMMENT 'Free-form summary from past conversations',
    updated_at        DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB;

INSERT IGNORE INTO user_profiles (user_id, holdings, watch_list, risk_preference)
VALUES ('u_001', '[]', '[]', 'moderate');

-- ========== Agent Traces (Observability) ==========
CREATE TABLE IF NOT EXISTS agent_traces (
    trace_id          VARCHAR(64) PRIMARY KEY,
    user_id           VARCHAR(32) NOT NULL,
    conversation_id   BIGINT,
    user_query        TEXT,
    total_steps       INT,
    total_tokens      INT,
    duration_ms       BIGINT,
    status            VARCHAR(16) COMMENT 'success | error | timeout',
    steps             JSON COMMENT 'JSON array of AgentStep details',
    created_at        DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB;

-- ========== Tool Call Logs ==========
CREATE TABLE IF NOT EXISTS tool_call_logs (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    trace_id        VARCHAR(64) NOT NULL,
    step_index      INT,
    tool_name       VARCHAR(64) NOT NULL,
    input_params    JSON,
    output_result   LONGTEXT,
    status          VARCHAR(16) COMMENT 'success | error | timeout',
    duration_ms     BIGINT,
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_trace_id (trace_id)
) ENGINE=InnoDB;

-- ========== Vector Documents Metadata ==========
-- Vector embeddings are stored in Milvus; this table keeps source metadata only.
CREATE TABLE IF NOT EXISTS vector_documents (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    doc_name        VARCHAR(256) NOT NULL COMMENT 'Source file name',
    chunk_index     INT NOT NULL,
    content_preview VARCHAR(512) COMMENT 'First 512 chars of the chunk',
    content_full    TEXT COMMENT 'Full chunk text for FULLTEXT keyword search',
    metadata        JSON COMMENT 'Source, author, date, category, etc.',
    redis_key       VARCHAR(128) COMMENT 'Milvus document id for deletion and traceability',
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_doc_name (doc_name),
    INDEX idx_redis_key (redis_key),
    FULLTEXT idx_ft_content (content_full)
) ENGINE=InnoDB;

-- Migration for existing databases: add content_full column and FULLTEXT index
-- ALTER TABLE vector_documents ADD COLUMN content_full TEXT COMMENT 'Full chunk text for FULLTEXT keyword search' AFTER content_preview;
-- ALTER TABLE vector_documents ADD FULLTEXT INDEX idx_ft_content (content_full);

-- ========== RAG Source Index ==========
-- Tracks idempotent ingestion, source-level chunk ids, and TTL cleanup state.
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

-- ========== Company Relations (关系图谱) ==========
-- 从已入库的 SEC 财报 Item 1 业务 / Item 1A 风险因素抽取的主体公司↔其它实体关系，
-- 每条边携带逐字证据片段与出处，供前端关系图下钻核验。按 ticker 幂等刷新。
CREATE TABLE IF NOT EXISTS company_relations (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    source_ticker       VARCHAR(32)  NOT NULL,
    source_name         VARCHAR(256),
    target_name         VARCHAR(256) NOT NULL,
    target_ticker       VARCHAR(32),
    relation_type       VARCHAR(32)  NOT NULL COMMENT 'COMPETITOR | CUSTOMER | SUPPLIER | PARTNER',
    confidence          DOUBLE       NOT NULL DEFAULT 0,
    evidence_snippet    TEXT,
    evidence_doc_id     VARCHAR(128),
    evidence_accession  VARCHAR(64),
    evidence_section    VARCHAR(128),
    filing_date         VARCHAR(16),
    extraction_model    VARCHAR(64),
    extracted_at        DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_company_relation_source (source_ticker),
    INDEX idx_company_relation_source_type (source_ticker, relation_type),
    UNIQUE KEY uniq_company_relation (source_ticker, relation_type, target_name, evidence_doc_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
