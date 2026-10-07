package com.stocksage.repository;

import com.stocksage.model.entity.ResearchMemoryEntry;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
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
 * 用户研究记忆真相表仓储。
 *
 * <p>查询同时携带 userId、撤销状态和向量状态，把租户隔离与“只使用有效索引”约束下推到数据库；
 * 条件更新用于阻止迟到的索引结果覆盖已撤销记忆。</p>
 */
public interface ResearchMemoryEntryRepository extends JpaRepository<ResearchMemoryEntry, Long> {

    /**
     * 按用户和来源唯一键查找已捕获记忆，实现来源级幂等。
     *
     * @param userId 记忆所属用户
     * @param sourceType 来源类型
     * @param sourceId 来源稳定标识
     * @return 已存在记忆；尚未捕获时为空
     */
    Optional<ResearchMemoryEntry> findByUserIdAndSourceTypeAndSourceId(
            String userId, String sourceType, String sourceId);

    /**
     * 批量回查向量搜索命中的有效 MySQL 真相行。
     *
     * <p>只返回当前用户、未撤销且状态完全匹配的记录，丢弃过期或跨用户向量结果。</p>
     *
     * @param ids 向量结果携带的记忆主键集合
     * @param userId 当前用户标识
     * @param vectorStatus 要求的索引状态，检索通常传 INDEXED
     * @return 仍可接受的真相行；顺序不保证与输入 ID 一致
     */
    List<ResearchMemoryEntry> findByIdInAndUserIdAndRevokedAtIsNullAndVectorStatus(
            Collection<Long> ids,
            String userId,
            ResearchMemoryEntry.VectorStatus vectorStatus
    );

    /**
     * 查找待处理或失败的索引任务，最久未更新的优先。
     *
     * @param statuses 可补偿的向量状态集合
     * @param pageable 批次大小和页码
     * @return 当前补偿批次；没有候选时为空列表
     */
    List<ResearchMemoryEntry> findByVectorStatusInOrderByUpdatedAtAsc(
            Collection<ResearchMemoryEntry.VectorStatus> statuses, Pageable pageable);

    /**
     * 按固定主键顺序读取并锁定同一用户、同一冲突组的全部候选。
     *
     * <p>必须在已经锁定 {@code research_memory_conflict_groups} 组行的事务内调用；
     * 固定 ID 顺序用于避免并发捕获、审核和撤销之间形成 entry 锁环。</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select entry
            from ResearchMemoryEntry entry
            where entry.userId = :userId
              and entry.conflictKey = :conflictKey
            order by entry.id asc
            """)
    List<ResearchMemoryEntry> findConflictCandidatesForUpdate(
            @Param("userId") String userId,
            @Param("conflictKey") String conflictKey
    );

    /**
     * 检查记忆是否仍未撤销且处于允许的索引状态。
     *
     * @param id 记忆主键
     * @param statuses 允许继续索引的状态集合
     * @return 条件命中的行数，合法主键下为 0 或 1
     */
    long countByIdAndRevokedAtIsNullAndVectorStatusIn(
            Long id, Collection<ResearchMemoryEntry.VectorStatus> statuses);

    /**
     * 以比较并交换方式推进向量索引状态。
     *
     * <p>仅当真相行未撤销且当前状态属于 {@code expectedStatuses} 时更新；
     * 因此迟到的 INDEXED/FAILED 回写不能覆盖并发撤销。方法会立即 flush SQL。</p>
     *
     * @param id 记忆主键
     * @param expectedStatuses 允许发生迁移的当前状态
     * @param targetStatus 目标向量状态
     * @param errorCode 失败错误码；成功时传 null
     * @param updatedAt 本次状态变更时间
     * @return 更新行数；1 表示迁移成功，0 表示条件已失效
     */
    @Transactional
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE ResearchMemoryEntry entry
               SET entry.vectorStatus = :targetStatus,
                   entry.vectorErrorCode = :errorCode,
                   entry.updatedAt = :updatedAt
             WHERE entry.id = :id
               AND entry.revokedAt IS NULL
               AND entry.vectorStatus IN (:expectedStatuses)
            """)
    int updateVectorStateIfIndexable(
            @Param("id") Long id,
            @Param("expectedStatuses") Collection<ResearchMemoryEntry.VectorStatus> expectedStatuses,
            @Param("targetStatus") ResearchMemoryEntry.VectorStatus targetStatus,
            @Param("errorCode") String errorCode,
            @Param("updatedAt") LocalDateTime updatedAt
    );
}
