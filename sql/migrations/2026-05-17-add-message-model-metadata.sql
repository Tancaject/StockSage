-- Store the final-answer model used for each assistant message.

ALTER TABLE messages
    ADD COLUMN model_tier VARCHAR(16) NULL AFTER trace_id,
    ADD COLUMN model_name VARCHAR(64) NULL AFTER model_tier,
    ADD INDEX idx_model_name (model_name);
