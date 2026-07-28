ALTER TABLE research_tasks
    ADD COLUMN result_kind VARCHAR(32) NULL AFTER result_report_version_id;
