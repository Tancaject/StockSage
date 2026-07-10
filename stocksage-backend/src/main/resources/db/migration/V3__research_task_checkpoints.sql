-- DEEP 后台化：任务断点。payload_json 存完整 AnalysisState（分析师报告 + 辩论流水，几十 KB），
-- 单独建表避免 research_tasks 主表膨胀。
CREATE TABLE IF NOT EXISTS research_task_checkpoints (
    id                       BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id                  BIGINT NOT NULL,
    stage_completed          VARCHAR(32) NOT NULL,
    debate_rounds_completed  INT NOT NULL DEFAULT 0,
    planned_rounds           INT NOT NULL DEFAULT 0,
    payload_json             MEDIUMTEXT NOT NULL,
    created_at               DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at               DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_checkpoint_task (task_id)
) ENGINE=InnoDB;
