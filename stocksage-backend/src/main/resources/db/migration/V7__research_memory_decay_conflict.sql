ALTER TABLE research_memory_entries
    ADD COLUMN analysis_horizon VARCHAR(16) NOT NULL DEFAULT 'UNSPECIFIED' AFTER ticker,
    ADD COLUMN recommendation VARCHAR(32) NULL AFTER analysis_horizon,
    ADD COLUMN conflict_key VARCHAR(128) NULL AFTER recommendation,
    ADD COLUMN resolution_status VARCHAR(16) NOT NULL DEFAULT 'CURRENT' AFTER conflict_key,
    ADD COLUMN superseded_by_id BIGINT NULL AFTER resolution_status,
    ADD COLUMN superseded_at DATETIME NULL AFTER superseded_by_id,
    ADD COLUMN resolution_reason VARCHAR(64) NULL AFTER superseded_at,
    ADD INDEX idx_research_memory_conflict
        (user_id, conflict_key, resolution_status),
    ADD INDEX idx_research_memory_superseded_by (superseded_by_id);

CREATE TABLE research_memory_conflict_groups (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id VARCHAR(32) NOT NULL,
    conflict_key VARCHAR(128) NOT NULL,
    winner_entry_id BIGINT NULL,
    resolution_status VARCHAR(24) NOT NULL DEFAULT 'UNRESOLVED',
    blocked_before_at DATETIME NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_research_memory_conflict_group UNIQUE (user_id, conflict_key),
    INDEX idx_research_memory_conflict_group_winner (winner_entry_id),
    INDEX idx_research_memory_conflict_group_status (user_id, resolution_status)
);

-- Backfill only from the authoritative source report. Do not parse memory_text. Ambiguous legacy
-- queries that mention multiple horizon classes remain UNSPECIFIED instead of guessing one.
CREATE TEMPORARY TABLE research_memory_legacy_report_fields AS
SELECT classified.memory_id,
       classified.recommendation,
       CASE
           WHEN classified.has_long + classified.has_medium + classified.has_short <> 1
               THEN 'UNSPECIFIED'
           WHEN classified.has_long = 1 THEN 'LONG_TERM'
           WHEN classified.has_medium = 1 THEN 'MEDIUM_TERM'
           ELSE 'SHORT_TERM'
       END AS analysis_horizon
FROM (
    SELECT memory.id AS memory_id,
           UPPER(TRIM(report.recommendation)) AS recommendation,
           CASE WHEN LOWER(COALESCE(report.user_query, '')) LIKE '%long term%'
                  OR LOWER(COALESCE(report.user_query, '')) LIKE '%long-term%'
                  OR COALESCE(report.user_query, '') LIKE '%长期%'
                  OR COALESCE(report.user_query, '') LIKE '%长线%' THEN 1 ELSE 0 END AS has_long,
           CASE WHEN LOWER(COALESCE(report.user_query, '')) LIKE '%medium term%'
                  OR LOWER(COALESCE(report.user_query, '')) LIKE '%medium-term%'
                  OR COALESCE(report.user_query, '') LIKE '%中期%'
                  OR COALESCE(report.user_query, '') LIKE '%数月%' THEN 1 ELSE 0 END AS has_medium,
           CASE WHEN LOWER(COALESCE(report.user_query, '')) LIKE '%short term%'
                  OR LOWER(COALESCE(report.user_query, '')) LIKE '%short-term%'
                  OR COALESCE(report.user_query, '') LIKE '%短期%'
                  OR COALESCE(report.user_query, '') LIKE '%短线%' THEN 1 ELSE 0 END AS has_short
    FROM research_memory_entries memory
    JOIN investment_report_versions report
      ON memory.source_type = 'INVESTMENT_REPORT_VERSION'
     AND memory.source_id = CAST(report.id AS CHAR)
     AND memory.user_id = report.user_id
) classified;

ALTER TABLE research_memory_legacy_report_fields
    ADD PRIMARY KEY (memory_id);

UPDATE research_memory_entries memory
JOIN research_memory_legacy_report_fields fields
  ON fields.memory_id = memory.id
SET memory.recommendation = fields.recommendation,
    memory.analysis_horizon = fields.analysis_horizon;

DROP TEMPORARY TABLE research_memory_legacy_report_fields;

UPDATE research_memory_entries
SET conflict_key = CONCAT(
        'REPORT_RECOMMENDATION|',
        UPPER(TRIM(ticker)),
        '|',
        analysis_horizon
    )
WHERE source_type = 'INVESTMENT_REPORT_VERSION';

-- Rebuild the runtime resolver's eligible set from authoritative report review state.
CREATE TEMPORARY TABLE research_memory_migration_candidates AS
SELECT candidate.id,
       candidate.user_id,
       candidate.conflict_key,
       COALESCE(candidate.data_cutoff_at, candidate.created_at) AS reference_at,
       CASE WHEN report.review_status = 'APPROVED' THEN 1 ELSE 0 END AS review_rank,
       report.generated_at AS generated_at,
       CASE
           WHEN candidate.recommendation IN ('BUY', 'OVERWEIGHT') THEN 'BULLISH'
           WHEN candidate.recommendation = 'HOLD' THEN 'NEUTRAL'
           WHEN candidate.recommendation IN ('UNDERWEIGHT', 'SELL') THEN 'BEARISH'
       END AS recommendation_direction
FROM research_memory_entries candidate
JOIN investment_report_versions report
  ON candidate.source_type = 'INVESTMENT_REPORT_VERSION'
 AND candidate.source_id = CAST(report.id AS CHAR)
 AND candidate.user_id = report.user_id
WHERE candidate.conflict_key IS NOT NULL
  AND candidate.revoked_at IS NULL
  AND candidate.vector_status <> 'REVOKED'
  AND candidate.recommendation IN ('BUY', 'OVERWEIGHT', 'HOLD', 'UNDERWEIGHT', 'SELL')
  AND report.review_status NOT IN ('REJECTED', 'NEEDS_RESEARCH');

-- DENSE_RANK identifies candidates with equal business priority. ROW_NUMBER is used only when
-- those candidates agree on direction, so generated_at/id never hides a real direction conflict.
CREATE TEMPORARY TABLE research_memory_migration_ranked AS
SELECT candidate.*,
       DENSE_RANK() OVER (
           PARTITION BY candidate.user_id, candidate.conflict_key
           ORDER BY candidate.reference_at DESC, candidate.review_rank DESC
       ) AS priority_rank,
       ROW_NUMBER() OVER (
           PARTITION BY candidate.user_id, candidate.conflict_key
           ORDER BY candidate.reference_at DESC,
                    candidate.review_rank DESC,
                    candidate.generated_at DESC,
                    candidate.id DESC
       ) AS winner_rank
FROM research_memory_migration_candidates candidate;

CREATE TEMPORARY TABLE research_memory_group_resolutions AS
SELECT candidate.user_id,
       candidate.conflict_key,
       CASE
           WHEN COUNT(DISTINCT candidate.recommendation_direction) > 1 THEN NULL
           ELSE MAX(CASE WHEN candidate.winner_rank = 1 THEN candidate.id END)
       END AS winner_entry_id,
       CASE
           WHEN COUNT(DISTINCT candidate.recommendation_direction) > 1 THEN 'UNRESOLVED'
           ELSE 'RESOLVED'
       END AS resolution_status
FROM research_memory_migration_ranked candidate
WHERE candidate.priority_rank = 1
GROUP BY candidate.user_id, candidate.conflict_key;

ALTER TABLE research_memory_group_resolutions
    ADD PRIMARY KEY (user_id, conflict_key);

UPDATE research_memory_entries memory
LEFT JOIN research_memory_migration_ranked candidate
  ON candidate.id = memory.id
LEFT JOIN research_memory_group_resolutions resolution
  ON resolution.user_id = memory.user_id
 AND resolution.conflict_key = memory.conflict_key
SET memory.resolution_status = CASE
        WHEN resolution.winner_entry_id = memory.id THEN 'CURRENT'
        WHEN resolution.resolution_status = 'UNRESOLVED'
         AND candidate.priority_rank = 1 THEN 'CONFLICTED'
        ELSE 'SUPERSEDED'
    END,
    memory.superseded_by_id = CASE
        WHEN resolution.resolution_status = 'RESOLVED'
         AND resolution.winner_entry_id <> memory.id THEN resolution.winner_entry_id
        ELSE NULL
    END,
    memory.superseded_at = CASE
        WHEN resolution.winner_entry_id = memory.id
          OR (resolution.resolution_status = 'UNRESOLVED' AND candidate.priority_rank = 1)
            THEN NULL
        ELSE CURRENT_TIMESTAMP
    END,
    memory.resolution_reason = CASE
        WHEN resolution.winner_entry_id = memory.id THEN 'MIGRATED_CURRENT'
        WHEN resolution.resolution_status = 'UNRESOLVED'
         AND candidate.priority_rank = 1 THEN 'MIGRATED_DIRECTION_CONFLICT'
        WHEN resolution.resolution_status IS NULL THEN 'MIGRATED_NO_ELIGIBLE_CANDIDATE'
        ELSE 'MIGRATED_SUPERSEDED'
    END
WHERE memory.conflict_key IS NOT NULL;

INSERT INTO research_memory_conflict_groups (
    user_id,
    conflict_key,
    winner_entry_id,
    resolution_status,
    blocked_before_at,
    created_at,
    updated_at
)
SELECT memory_groups.user_id,
       memory_groups.conflict_key,
       resolution.winner_entry_id,
       CASE
           WHEN resolution.resolution_status IS NULL THEN 'NO_ELIGIBLE_CANDIDATE'
           ELSE resolution.resolution_status
       END,
       NULL,
       CURRENT_TIMESTAMP,
       CURRENT_TIMESTAMP
FROM (
    SELECT DISTINCT user_id, conflict_key
    FROM research_memory_entries
    WHERE conflict_key IS NOT NULL
) memory_groups
LEFT JOIN research_memory_group_resolutions resolution
  ON resolution.user_id = memory_groups.user_id
 AND resolution.conflict_key = memory_groups.conflict_key;

DROP TEMPORARY TABLE research_memory_group_resolutions;
DROP TEMPORARY TABLE research_memory_migration_ranked;
DROP TEMPORARY TABLE research_memory_migration_candidates;
