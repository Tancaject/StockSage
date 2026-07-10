-- 公司关系图谱：从已入库的 SEC 财报（Item 1 业务 / Item 1A 风险因素）抽取的
-- 主体公司↔其它实体的 竞争/客户/供应/合作 关系。每条边携带逐字证据片段与出处，
-- 供前端关系图下钻核验。由 RelationExtractionService 按 ticker 幂等刷新。

USE stocksage;

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
) ENGINE=InnoDB;
