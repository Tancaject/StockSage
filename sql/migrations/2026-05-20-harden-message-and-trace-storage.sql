-- Add the conversation history access index and convert JSON payload text fields to JSON.

ALTER TABLE messages
    ADD INDEX idx_conversation_created_at (conversation_id, created_at);

UPDATE agent_traces
SET steps = '[]'
WHERE steps IS NULL OR JSON_VALID(steps) = 0;

ALTER TABLE agent_traces
    MODIFY COLUMN steps JSON COMMENT 'JSON array of AgentStep details';

UPDATE tool_call_logs
SET input_params = NULL
WHERE input_params IS NOT NULL AND JSON_VALID(input_params) = 0;

ALTER TABLE tool_call_logs
    MODIFY COLUMN input_params JSON;
