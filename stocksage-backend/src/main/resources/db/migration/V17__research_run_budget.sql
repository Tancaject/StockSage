ALTER TABLE research_tasks
    ADD COLUMN budget_deadline_epoch_ms BIGINT NULL;
ALTER TABLE research_tasks
    ADD COLUMN max_model_calls INT NULL;
