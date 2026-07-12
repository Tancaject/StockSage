package com.stocksage.repository;

import com.stocksage.model.entity.ResearchTaskCheckpoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ResearchTaskCheckpointRepository extends JpaRepository<ResearchTaskCheckpoint, Long> {

    /**
     * Serializes checkpoint mutation with lease takeover and verifies the current database owner.
     * The lock is held until the surrounding transaction commits or rolls back.
     */
    @Query(value = """
            SELECT id
              FROM research_tasks
             WHERE id = :taskId
               AND status = 'RUNNING'
               AND lease_token = :leaseToken
             FOR UPDATE
            """, nativeQuery = true)
    Optional<Long> lockOwnedRunningTask(
            @Param("taskId") Long taskId,
            @Param("leaseToken") String leaseToken
    );

    /**
     * Locks a successfully completed task before its checkpoint is cleaned up.
     * Successful completion clears the lease token, so terminal cleanup fences on status instead.
     */
    @Query(value = """
            SELECT id
              FROM research_tasks
             WHERE id = :taskId
               AND status = 'SUCCEEDED'
             FOR UPDATE
            """, nativeQuery = true)
    Optional<Long> lockSucceededTask(@Param("taskId") Long taskId);

    Optional<ResearchTaskCheckpoint> findByTaskId(Long taskId);

    void deleteByTaskId(Long taskId);
}
