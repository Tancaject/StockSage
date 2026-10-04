-- Historical producer identities are unknown; never infer them from later cache consumers.
ALTER TABLE investment_report_versions ADD COLUMN producer_run_id BIGINT;
ALTER TABLE investment_report_versions ADD COLUMN producer_attempt INT;
ALTER TABLE investment_report_versions ADD COLUMN producer_evidence_snapshot_id VARCHAR(36);
