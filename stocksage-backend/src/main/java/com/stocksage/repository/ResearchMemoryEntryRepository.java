package com.stocksage.repository;

import com.stocksage.model.entity.ResearchMemoryEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ResearchMemoryEntryRepository extends JpaRepository<ResearchMemoryEntry, Long> {

    Optional<ResearchMemoryEntry> findByUserIdAndSourceTypeAndSourceId(
            String userId, String sourceType, String sourceId);

    Optional<ResearchMemoryEntry> findByIdAndUserId(Long id, String userId);

    List<ResearchMemoryEntry> findByIdInAndUserIdAndRevokedAtIsNullAndVectorStatus(
            Collection<Long> ids,
            String userId,
            ResearchMemoryEntry.VectorStatus vectorStatus
    );

    List<ResearchMemoryEntry> findByUserIdAndRevokedAtIsNullOrderByCreatedAtDesc(
            String userId, Pageable pageable);

    List<ResearchMemoryEntry> findByVectorStatusInOrderByUpdatedAtAsc(
            Collection<ResearchMemoryEntry.VectorStatus> statuses, Pageable pageable);

    long countByIdAndRevokedAtIsNullAndVectorStatusIn(
            Long id, Collection<ResearchMemoryEntry.VectorStatus> statuses);

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
