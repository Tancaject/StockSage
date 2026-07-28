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

    long countByStatus(ResearchTask.Status status);

    Optional<ResearchTask> findFirstByUserIdAndConversationIdAndStatusInOrderByCreatedAtDesc(
            String userId,
            Long conversationId,
            Collection<ResearchTask.Status> statuses
    );

    Optional<ResearchTask> findByIdAndUserId(Long id, String userId);

    List<ResearchTask> findByStatusAndHeartbeatAtBefore(
            ResearchTask.Status status,
            LocalDateTime heartbeatBefore
    );

    List<ResearchTask> findByStatusAndErrorMessage(
            ResearchTask.Status status,
            String errorMessage
    );

    List<ResearchTask> findByUserIdAndTickerOrderByCreatedAtDesc(
            String userId,
            String ticker,
            Pageable pageable
    );

    @Modifying
    @Query(value = """
            UPDATE research_tasks
               SET status = 'RUNNING',
                   stage = :stage,
                   attempts = attempts + 1,
                   lease_token = :leaseToken,
                   started_at = :startedAt,
                   completed_at = NULL,
                   heartbeat_at = :startedAt,
                   error_message = NULL,
                   updated_at = :startedAt
             WHERE id = :id
               AND status = 'PENDING'
            """, nativeQuery = true)
    int startAttemptIfPending(
            @Param("id") Long id,
            @Param("leaseToken") String leaseToken,
            @Param("stage") String stage,
            @Param("startedAt") LocalDateTime startedAt
    );

    @Modifying
    @Query(value = """
            UPDATE research_tasks
               SET lease_token = :newLeaseToken,
                   attempts = attempts + 1,
                   started_at = :takenOverAt,
                   completed_at = NULL,
                   heartbeat_at = :takenOverAt,
                   error_message = NULL,
                   updated_at = :takenOverAt
             WHERE id = :id
               AND status = 'RUNNING'
               AND lease_token = :observedLeaseToken
               AND heartbeat_at < :heartbeatBefore
               AND attempts < :maxAttempts
            """, nativeQuery = true)
    int takeOverStaleRunningAttempt(
            @Param("id") Long id,
            @Param("observedLeaseToken") String observedLeaseToken,
            @Param("newLeaseToken") String newLeaseToken,
            @Param("heartbeatBefore") LocalDateTime heartbeatBefore,
            @Param("maxAttempts") int maxAttempts,
            @Param("takenOverAt") LocalDateTime takenOverAt
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
               AND status = 'RUNNING'
               AND lease_token = :observedLeaseToken
               AND heartbeat_at < :heartbeatBefore
               AND attempts >= :maxAttempts
            """, nativeQuery = true)
    int failStaleRunningAtAttemptLimit(
            @Param("id") Long id,
            @Param("observedLeaseToken") String observedLeaseToken,
            @Param("heartbeatBefore") LocalDateTime heartbeatBefore,
            @Param("maxAttempts") int maxAttempts,
            @Param("errorMessage") String errorMessage,
            @Param("failedAt") LocalDateTime failedAt
    );

    @Modifying
    @Query(value = """
            UPDATE research_tasks
               SET status = 'PENDING',
                   stage = 'CREATED',
                   lease_token = NULL,
                   error_message = :errorMessage,
                   completed_at = NULL,
                   heartbeat_at = :resetAt,
                   updated_at = :resetAt
             WHERE id = :id
               AND status = 'RUNNING'
               AND lease_token = :observedLeaseToken
               AND heartbeat_at < :heartbeatBefore
               AND attempts < :maxAttempts
            """, nativeQuery = true)
    int resetStaleRunningForRetry(
            @Param("id") Long id,
            @Param("observedLeaseToken") String observedLeaseToken,
            @Param("heartbeatBefore") LocalDateTime heartbeatBefore,
            @Param("maxAttempts") int maxAttempts,
            @Param("errorMessage") String errorMessage,
            @Param("resetAt") LocalDateTime resetAt
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
               AND status = 'PENDING'
            """, nativeQuery = true)
    int failIfPending(
            @Param("id") Long id,
            @Param("errorMessage") String errorMessage,
            @Param("failedAt") LocalDateTime failedAt
    );

    @Modifying
    @Query(value = """
            UPDATE research_tasks
               SET status = 'PENDING',
                   stage = 'CREATED',
                   lease_token = NULL,
                   error_message = :errorMessage,
                   completed_at = NULL,
                   heartbeat_at = :resetAt,
                   updated_at = :resetAt
             WHERE id = :id
               AND lease_token = :leaseToken
               AND status = 'RUNNING'
            """, nativeQuery = true)
    int resetRunningForRetryForOwner(
            @Param("id") Long id,
            @Param("leaseToken") String leaseToken,
            @Param("errorMessage") String errorMessage,
            @Param("resetAt") LocalDateTime resetAt
    );

    @Modifying
    @Query(value = """
            UPDATE research_tasks
               SET status = 'PENDING',
                   stage = 'CREATED',
                   attempts = 0,
                   conversation_id = :conversationId,
                   lease_token = NULL,
                   payload_json = :payloadJson,
                   error_message = NULL,
                   result_report_version_id = NULL,
                   result_kind = NULL,
                   started_at = NULL,
                   completed_at = NULL,
                   heartbeat_at = :resetAt,
                   updated_at = :resetAt
             WHERE id = :id
               AND status IN ('SUCCEEDED', 'FAILED')
            """, nativeQuery = true)
    int resetTerminalForResubmission(
            @Param("id") Long id,
            @Param("conversationId") Long conversationId,
            @Param("payloadJson") String payloadJson,
            @Param("resetAt") LocalDateTime resetAt
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
                   result_kind = :resultKind,
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
            @Param("resultKind") String resultKind,
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
