ALTER TABLE research_tasks
    ADD COLUMN token_budget_json JSON NULL;
ALTER TABLE research_model_invocations
    ADD COLUMN reserved_tokens BIGINT NULL;
ALTER TABLE research_model_invocations
    ADD COLUMN accounted_tokens BIGINT NULL;
