package com.stocksage.repository;

import com.stocksage.model.entity.ResearchMemoryEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ResearchMemoryEntryRepository extends JpaRepository<ResearchMemoryEntry, Long> {

    Optional<ResearchMemoryEntry> findByUserIdAndSourceTypeAndSourceId(
            String userId, String sourceType, String sourceId);

    Optional<ResearchMemoryEntry> findByIdAndUserId(Long id, String userId);

    List<ResearchMemoryEntry> findByIdInAndUserIdAndRevokedAtIsNull(
            Collection<Long> ids, String userId);

    List<ResearchMemoryEntry> findByUserIdAndRevokedAtIsNullOrderByCreatedAtDesc(
            String userId, Pageable pageable);

    List<ResearchMemoryEntry> findByVectorStatusInOrderByUpdatedAtAsc(
            Collection<ResearchMemoryEntry.VectorStatus> statuses, Pageable pageable);
}
