-- V1 基线声明了 idx_redis_key，但在 Flyway 接管前已建好的库里缺这个索引；
-- 按 vector id（redis_key）回读正文和父块时会全表扫描。只在缺失时补建。
SET @index_exists := (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'vector_documents' AND index_name = 'idx_redis_key'
);
SET @ddl := IF(@index_exists = 0, 'CREATE INDEX idx_redis_key ON vector_documents (redis_key)', 'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
