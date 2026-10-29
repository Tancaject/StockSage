-- 线上 Trace 导出按 trace_id 批量读取 DEEP 调用账本；原表只有 (run_id, attempt, created_at) 索引，会全表扫描。
SET @index_exists := (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'research_model_invocations' AND index_name = 'idx_model_invocation_trace'
);
SET @ddl := IF(@index_exists = 0, 'CREATE INDEX idx_model_invocation_trace ON research_model_invocations (trace_id)', 'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
