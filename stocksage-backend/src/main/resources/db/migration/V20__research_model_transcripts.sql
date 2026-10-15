-- 评测模式下每次 DEEP 模型调用的完整输入输出；生产任务只保留 research_model_invocations 里的哈希。
CREATE TABLE research_model_transcripts (
    invocation_id  VARCHAR(36) NOT NULL,
    run_id         BIGINT NOT NULL,
    request_text   LONGTEXT NOT NULL,
    response_text  LONGTEXT,
    reasoning_text LONGTEXT,
    created_at     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (invocation_id),
    INDEX idx_model_transcript_run (run_id, created_at)
);
