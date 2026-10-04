CREATE TABLE research_model_invocations (
    id             VARCHAR(36) PRIMARY KEY,
    run_id         BIGINT NOT NULL,
    attempt        INT NOT NULL,
    trace_id       VARCHAR(64),
    role           VARCHAR(64) NOT NULL,
    request_json   LONGTEXT NOT NULL,
    response_json  LONGTEXT,
    status         VARCHAR(24) NOT NULL,
    created_at     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at   DATETIME(6)
);

CREATE INDEX idx_model_invocation_run_attempt ON research_model_invocations (run_id, attempt, created_at);
