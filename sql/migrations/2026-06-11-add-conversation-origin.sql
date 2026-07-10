ALTER TABLE conversations
    ADD COLUMN origin VARCHAR(16) NOT NULL DEFAULT 'chat' AFTER user_id,
    ADD INDEX idx_conversation_user_origin_updated (user_id, origin, updated_at);
