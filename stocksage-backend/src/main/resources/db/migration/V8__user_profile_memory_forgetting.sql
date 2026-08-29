-- Fact-level confirmation timestamps let independently changing profile facts decay independently.
-- Watch-list state is intentionally excluded: it remains until the user changes it.
CREATE TABLE user_memory_facts (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id             VARCHAR(32) NOT NULL,
    fact_type           VARCHAR(32) NOT NULL,
    fact_key            VARCHAR(128) NOT NULL,
    fact_value          TEXT NOT NULL,
    last_confirmed_at   DATETIME(6),
    source_message_id   BIGINT,
    revoked_at          DATETIME(6),
    created_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_user_memory_fact (user_id, fact_type, fact_key),
    INDEX idx_user_memory_fact_active (user_id, revoked_at)
) ENGINE=InnoDB;

-- Legacy rows have no fact-level confirmation timestamp. Preserve their values for audit/backfill,
-- but leave them unconfirmed so they cannot masquerade as current memory.
INSERT IGNORE INTO user_memory_facts (
    user_id, fact_type, fact_key, fact_value, last_confirmed_at, created_at, updated_at
)
SELECT
    profile.user_id,
    'HOLDING',
    UPPER(REGEXP_REPLACE(TRIM(item.ticker), '[^A-Za-z0-9]', '')),
    UPPER(TRIM(item.ticker)),
    NULL,
    CURRENT_TIMESTAMP(6),
    TIMESTAMP('1970-01-01 00:00:01')
FROM user_profiles profile
JOIN JSON_TABLE(
    COALESCE(profile.holdings, JSON_ARRAY()),
    '$[*]' COLUMNS (ticker VARCHAR(128) PATH '$')
) AS item ON TRUE
WHERE TRIM(item.ticker) <> ''
  AND REGEXP_REPLACE(TRIM(item.ticker), '[^A-Za-z0-9]', '') <> '';

INSERT IGNORE INTO user_memory_facts (
    user_id, fact_type, fact_key, fact_value, last_confirmed_at, created_at, updated_at
)
SELECT
    user_id,
    'RISK_PREFERENCE',
    'risk_preference',
    TRIM(risk_preference),
    NULL,
    CURRENT_TIMESTAMP(6),
    TIMESTAMP('1970-01-01 00:00:01')
FROM user_profiles
WHERE risk_preference IS NOT NULL
  AND TRIM(risk_preference) <> ''
  AND TRIM(risk_preference) <> 'moderate';

INSERT IGNORE INTO user_memory_facts (
    user_id, fact_type, fact_key, fact_value, last_confirmed_at, created_at, updated_at
)
SELECT
    user_id,
    'PROFILE_SUMMARY',
    'profile_summary',
    TRIM(profile_summary),
    NULL,
    CURRENT_TIMESTAMP(6),
    TIMESTAMP('1970-01-01 00:00:01')
FROM user_profiles
WHERE profile_summary IS NOT NULL
  AND TRIM(profile_summary) <> '';
