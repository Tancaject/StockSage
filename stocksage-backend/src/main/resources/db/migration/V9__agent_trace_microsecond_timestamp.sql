-- Preserve the Trace start instant at millisecond precision for end-to-end latency metrics.
ALTER TABLE agent_traces
    MODIFY COLUMN created_at DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6);
