package com.stocksage.repository;

import com.stocksage.model.entity.UserMemoryFact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/** 用户长期画像事实的真源仓储。 */
public interface UserMemoryFactRepository extends JpaRepository<UserMemoryFact, Long> {

    /** 读取未被显式撤销的事实；时间权重仍由服务层在查询时计算。 */
    List<UserMemoryFact> findByUserIdAndRevokedAtIsNullOrderByIdAsc(String userId);

    /** 原子确认事实；重复确认会刷新时间并恢复曾撤销的事实。 */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO user_memory_facts (
                user_id, fact_type, fact_key, fact_value,
                last_confirmed_at, source_message_id, revoked_at, created_at, updated_at
            ) VALUES (
                :userId, :factType, :factKey, :factValue,
                :confirmedAt, :sourceMessageId, NULL, :confirmedAt, :confirmedAt
            )
            ON DUPLICATE KEY UPDATE
                fact_value = IF(
                    (VALUES(source_message_id) IS NOT NULL AND source_message_id IS NOT NULL
                        AND VALUES(source_message_id) >= source_message_id)
                    OR ((VALUES(source_message_id) IS NULL OR source_message_id IS NULL)
                        AND VALUES(updated_at) >= updated_at),
                    VALUES(fact_value), fact_value
                ),
                last_confirmed_at = IF(
                    (VALUES(source_message_id) IS NOT NULL AND source_message_id IS NOT NULL
                        AND VALUES(source_message_id) >= source_message_id)
                    OR ((VALUES(source_message_id) IS NULL OR source_message_id IS NULL)
                        AND VALUES(updated_at) >= updated_at),
                    VALUES(last_confirmed_at), last_confirmed_at
                ),
                revoked_at = IF(
                    (VALUES(source_message_id) IS NOT NULL AND source_message_id IS NOT NULL
                        AND VALUES(source_message_id) >= source_message_id)
                    OR ((VALUES(source_message_id) IS NULL OR source_message_id IS NULL)
                        AND VALUES(updated_at) >= updated_at),
                    NULL, revoked_at
                ),
                source_message_id = IF(
                    (VALUES(source_message_id) IS NOT NULL AND source_message_id IS NOT NULL
                        AND VALUES(source_message_id) >= source_message_id)
                    OR ((VALUES(source_message_id) IS NULL OR source_message_id IS NULL)
                        AND VALUES(updated_at) >= updated_at),
                    VALUES(source_message_id), source_message_id
                ),
                updated_at = IF(
                    (VALUES(source_message_id) IS NOT NULL AND source_message_id IS NOT NULL
                        AND VALUES(source_message_id) >= source_message_id)
                    OR ((VALUES(source_message_id) IS NULL OR source_message_id IS NULL)
                        AND VALUES(updated_at) >= updated_at),
                    VALUES(updated_at), updated_at
                )
            """, nativeQuery = true)
    int confirm(
            @Param("userId") String userId,
            @Param("factType") String factType,
            @Param("factKey") String factKey,
            @Param("factValue") String factValue,
            @Param("confirmedAt") LocalDateTime confirmedAt,
            @Param("sourceMessageId") Long sourceMessageId
    );

    /** 写入撤销墓碑；即使此前未捕获，也能阻止该负向事实被当作有效持仓。 */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO user_memory_facts (
                user_id, fact_type, fact_key, fact_value,
                last_confirmed_at, source_message_id, revoked_at, created_at, updated_at
            ) VALUES (
                :userId, 'HOLDING', :factKey, :factValue,
                :revokedAt, :sourceMessageId, :revokedAt, :revokedAt, :revokedAt
            )
            ON DUPLICATE KEY UPDATE
                revoked_at = IF(
                    (VALUES(source_message_id) IS NOT NULL AND source_message_id IS NOT NULL
                        AND VALUES(source_message_id) >= source_message_id)
                    OR ((VALUES(source_message_id) IS NULL OR source_message_id IS NULL)
                        AND VALUES(updated_at) >= updated_at),
                    VALUES(revoked_at), revoked_at
                ),
                source_message_id = IF(
                    (VALUES(source_message_id) IS NOT NULL AND source_message_id IS NOT NULL
                        AND VALUES(source_message_id) >= source_message_id)
                    OR ((VALUES(source_message_id) IS NULL OR source_message_id IS NULL)
                        AND VALUES(updated_at) >= updated_at),
                    VALUES(source_message_id), source_message_id
                ),
                updated_at = IF(
                    (VALUES(source_message_id) IS NOT NULL AND source_message_id IS NOT NULL
                        AND VALUES(source_message_id) >= source_message_id)
                    OR ((VALUES(source_message_id) IS NULL OR source_message_id IS NULL)
                        AND VALUES(updated_at) >= updated_at),
                    VALUES(updated_at), updated_at
                )
            """, nativeQuery = true)
    int revokeHolding(
            @Param("userId") String userId,
            @Param("factKey") String factKey,
            @Param("factValue") String factValue,
            @Param("revokedAt") LocalDateTime revokedAt,
            @Param("sourceMessageId") Long sourceMessageId
    );
}
