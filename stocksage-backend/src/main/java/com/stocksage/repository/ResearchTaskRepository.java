package com.stocksage.repository;

import com.stocksage.model.entity.ResearchTask;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ResearchTaskRepository extends JpaRepository<ResearchTask, Long> {

    Optional<ResearchTask> findByIdempotencyKey(String idempotencyKey);

    long countByUserIdAndStatusIn(
            String userId,
            Collection<ResearchTask.Status> statuses
    );

    List<ResearchTask> findByStatusAndHeartbeatAtBefore(
            ResearchTask.Status status,
            LocalDateTime heartbeatBefore
    );

    List<ResearchTask> findByUserIdAndTickerOrderByCreatedAtDesc(
            String userId,
            String ticker,
            Pageable pageable
    );

    default int advanceStageForOwner(
            Long id,
            String leaseToken,
            ResearchTask.Stage stage,
            LocalDateTime heartbeatAt
    ) {
        return advanceStageForOwnerValue(id, leaseToken, stage.name(), heartbeatAt);
    }

    @Modifying
    @Query(value = """
            UPDATE research_tasks
               SET stage = :stage,
                   heartbeat_at = :heartbeatAt,
                   updated_at = :heartbeatAt
             WHERE id = :id
               AND lease_token = :leaseToken
               AND status = 'RUNNING'
            """, nativeQuery = true)
    int advanceStageForOwnerValue(
            @Param("id") Long id,
            @Param("leaseToken") String leaseToken,
            @Param("stage") String stage,
            @Param("heartbeatAt") LocalDateTime heartbeatAt
    );

    @Modifying
    @Query(value = """
            UPDATE research_tasks
               SET heartbeat_at = :heartbeatAt,
                   updated_at = :heartbeatAt
             WHERE id = :id
               AND lease_token = :leaseToken
               AND status = 'RUNNING'
            """, nativeQuery = true)
    int heartbeatForOwner(
            @Param("id") Long id,
            @Param("leaseToken") String leaseToken,
            @Param("heartbeatAt") LocalDateTime heartbeatAt
    );

    @Modifying
    @Query(value = """
            UPDATE research_tasks
               SET status = 'SUCCEEDED',
                   stage = 'COMPLETE',
                   result_report_version_id = :resultReportVersionId,
                   completed_at = :completedAt,
                   heartbeat_at = :completedAt,
                   updated_at = :completedAt,
                   lease_token = NULL,
                   error_message = NULL
             WHERE id = :id
               AND lease_token = :leaseToken
               AND status = 'RUNNING'
            """, nativeQuery = true)
    int completeForOwner(
            @Param("id") Long id,
            @Param("leaseToken") String leaseToken,
            @Param("resultReportVersionId") Long resultReportVersionId,
            @Param("completedAt") LocalDateTime completedAt
    );

    @Modifying
    @Query(value = """
            UPDATE research_tasks
               SET status = 'FAILED',
                   stage = 'FAILED',
                   error_message = :errorMessage,
                   completed_at = :failedAt,
                   heartbeat_at = :failedAt,
                   updated_at = :failedAt,
                   lease_token = NULL
             WHERE id = :id
               AND lease_token = :leaseToken
               AND status = 'RUNNING'
            """, nativeQuery = true)
    int failForOwner(
            @Param("id") Long id,
            @Param("leaseToken") String leaseToken,
            @Param("errorMessage") String errorMessage,
            @Param("failedAt") LocalDateTime failedAt
    );
}
