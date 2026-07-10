USE stocksage;

ALTER TABLE messages
    ADD COLUMN trace_id VARCHAR(64) NULL AFTER token_count,
    ADD INDEX idx_trace_id (trace_id);
