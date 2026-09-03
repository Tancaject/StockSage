-- Keep business completion separate from transport and process execution status.
ALTER TABLE agent_traces
    ADD COLUMN task_outcome VARCHAR(32) NULL AFTER status;
