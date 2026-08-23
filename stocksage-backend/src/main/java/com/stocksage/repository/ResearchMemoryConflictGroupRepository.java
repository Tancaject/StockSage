package com.stocksage.repository;

import com.stocksage.model.entity.ResearchMemoryConflictGroup;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 研究记忆冲突组仓储。
 *
 * <p>唯一组行既保存 MySQL 业务真相，也为同用户、同冲突键提供稳定悲观锁点。
 * 调用方必须先锁组，再按 ID 顺序锁组内候选。</p>
 */
public interface ResearchMemoryConflictGroupRepository
        extends JpaRepository<ResearchMemoryConflictGroup, Long> {

    /** 按租户和冲突键读取组级真相，不获取写锁。 */
    Optional<ResearchMemoryConflictGroup> findByUserIdAndConflictKey(
            String userId,
            String conflictKey
    );

    /** 批量读取向量候选涉及的组级真相，避免检索路径逐组查询。 */
    List<ResearchMemoryConflictGroup> findByUserIdAndConflictKeyIn(
            String userId,
            Collection<String> conflictKeys
    );

    /**
     * 幂等创建冲突组。
     *
     * <p>MySQL 唯一键和 INSERT IGNORE 共同收敛并发首次创建；随后仍必须调用
     * {@link #findByUserIdAndConflictKeyForUpdate(String, String)} 获取悲观锁。</p>
     *
     * @return 1 表示本次创建，0 表示组已存在
     */
    @Transactional
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT IGNORE INTO research_memory_conflict_groups (
                user_id,
                conflict_key,
                winner_entry_id,
                resolution_status,
                blocked_before_at,
                created_at,
                updated_at
            ) VALUES (
                :userId,
                :conflictKey,
                NULL,
                'UNRESOLVED',
                NULL,
                :createdAt,
                :createdAt
            )
            """, nativeQuery = true)
    int createIfAbsent(
            @Param("userId") String userId,
            @Param("conflictKey") String conflictKey,
            @Param("createdAt") LocalDateTime createdAt
    );

    /** 在当前事务中读取并锁定唯一冲突组行。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select conflictGroup
            from ResearchMemoryConflictGroup conflictGroup
            where conflictGroup.userId = :userId
              and conflictGroup.conflictKey = :conflictKey
            """)
    Optional<ResearchMemoryConflictGroup> findByUserIdAndConflictKeyForUpdate(
            @Param("userId") String userId,
            @Param("conflictKey") String conflictKey
    );

    /**
     * 确保冲突组存在并取得悲观写锁。
     *
     * <p>必须作为“报告行 -> 冲突组 -> entry 候选”固定锁顺序的第二步调用。</p>
     */
    @Transactional
    default ResearchMemoryConflictGroup ensureAndLock(
            String userId,
            String conflictKey,
            LocalDateTime createdAt
    ) {
        LocalDateTime safeCreatedAt = createdAt == null ? LocalDateTime.now() : createdAt;
        createIfAbsent(userId, conflictKey, safeCreatedAt);
        return findByUserIdAndConflictKeyForUpdate(userId, conflictKey)
                .orElseThrow(() -> new IllegalStateException(
                        "RESEARCH_MEMORY_CONFLICT_GROUP_NOT_FOUND_AFTER_ENSURE"));
    }
}
