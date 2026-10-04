CREATE TABLE research_evidence_snapshots (
    id                  VARCHAR(36) PRIMARY KEY,
    run_id              BIGINT NOT NULL,
    attempt             INT NOT NULL,
    data_snapshot_hash  VARCHAR(64) NOT NULL,
    context_hash        VARCHAR(64) NOT NULL,
    payload_hash        VARCHAR(64) NOT NULL,
    payload_json        LONGTEXT NOT NULL,
    created_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_evidence_snapshot_payload UNIQUE (run_id, attempt, payload_hash)
);

ALTER TABLE research_tasks ADD COLUMN final_evidence_snapshot_id VARCHAR(36);
ALTER TABLE research_model_invocations ADD COLUMN evidence_snapshot_id VARCHAR(36);
