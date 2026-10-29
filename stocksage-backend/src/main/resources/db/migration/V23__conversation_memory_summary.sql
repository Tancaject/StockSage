ALTER TABLE conversations
    ADD COLUMN memory_summary TEXT NULL,
    ADD COLUMN memory_summary_through_message_id BIGINT NULL;
