package com.stocksage.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.model.entity.ResearchTask;
import com.stocksage.repository.ResearchTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResearchTaskService {

    private static final int MAX_ERROR_LENGTH = 4000;
    private static final String RECOVERED_FOR_RETRY_MESSAGE = "stale research task recovered for retry";

    private final ResearchTaskRepository repository;
    private final ResearchTaskLeaseService leaseService;
    private final ObjectMapper objectMapper;

    public TaskCreation createIfAbsent(
            String idempotencyKey,
            String userId,
            Long conversationId,
            String ticker,
            ResearchTask.Stage initialStage,
            String payloadJson
    ) {
        String normalizedKey = normalizeText(idempotencyKey);
        if (normalizedKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        Optional<ResearchTask> existing = repository.findByIdempotencyKey(normalizedKey);
        if (existing.isPresent()) {
            return new TaskCreation(existing.get(), false);
        }

        ResearchTask task = new ResearchTask();
        task.setIdempotencyKey(normalizedKey);
        task.setUserId(normalizeText(userId));
        task.setConversationId(conversationId);
        task.setTicker(normalizeTicker(ticker));
        task.setStatus(ResearchTask.Status.PENDING);
        task.setStage(initialStage == null ? ResearchTask.Stage.CREATED : initialStage);
        task.setAttempts(0);
        task.setPayloadJson(normalizePayload(payloadJson));

        try {
            return new TaskCreation(repository.saveAndFlush(task), true);
        } catch (DataIntegrityViolationException e) {
            log.warn("Research task already exists after concurrent create, idempotencyKey={}", normalizedKey);
            return repository.findByIdempotencyKey(normalizedKey)
                    .map(found -> new TaskCreation(found, false))
                    .orElseThrow(() -> e);
        }
    }

    public Optional<ResearchTaskLeaseService.Lease> tryAcquire(ResearchTask task) {
        if (task == null) {
            return Optional.empty();
        }
        return leaseService.tryAcquire(task.getIdempotencyKey());
    }

    public void release(ResearchTaskLeaseService.Lease lease) {
        leaseService.release(lease);
    }

    public boolean renewLease(ResearchTaskLeaseService.Lease lease) {
        return leaseService.renew(lease);
    }

    @Transactional
    public ResearchTask startAttempt(
            ResearchTask task,
            String leaseToken,
            ResearchTask.Stage stage
    ) {
        if (task == null || task.getId() == null) {
            throw new IllegalArgumentException("persisted research task is required to start an attempt");
        }
        LocalDateTime now = LocalDateTime.now();
        ResearchTask.Stage targetStage = stage == null ? ResearchTask.Stage.DATA_PREFETCH : stage;
        String normalizedLeaseToken = normalizeText(leaseToken);
        int updated = repository.startAttemptIfPending(
                task.getId(), normalizedLeaseToken, targetStage.name(), now);
        if (updated == 0) {
            throw new IllegalStateException("research task is no longer pending; execution fencing rejected");
        }
        task.setStatus(ResearchTask.Status.RUNNING);
        task.setStage(targetStage);
        task.setAttempts(safeAttempts(task) + 1);
        task.setLeaseToken(normalizedLeaseToken);
        task.setStartedAt(now);
        task.setCompletedAt(null);
        task.setHeartbeatAt(now);
        task.setErrorMessage(null);
        return task;
    }

    @Transactional
    public boolean resetRunningForRetryForOwner(
            ResearchTask task,
            String leaseToken,
            String errorMessage
    ) {
        if (task == null || task.getId() == null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        String retryMessage = truncate(normalizeText(errorMessage), MAX_ERROR_LENGTH);
        if (retryMessage.isBlank()) {
            retryMessage = "research task attempt failed; pending retry";
        }
        int updated = repository.resetRunningForRetryForOwner(
                task.getId(),
                normalizeText(leaseToken),
                retryMessage,
                now
        );
        if (updated == 0) {
            return false;
        }
        task.setStatus(ResearchTask.Status.PENDING);
        task.setStage(ResearchTask.Stage.CREATED);
        task.setLeaseToken(null);
        task.setErrorMessage(retryMessage);
        task.setCompletedAt(null);
        task.setHeartbeatAt(now);
        return true;
    }

    @Transactional
    public ResearchTask markStage(ResearchTask task, ResearchTask.Stage stage) {
        task.setStage(stage == null ? task.getStage() : stage);
        task.setHeartbeatAt(LocalDateTime.now());
        return repository.saveAndFlush(task);
    }

    @Transactional
    public void markStageForOwner(ResearchTask task, String leaseToken, ResearchTask.Stage stage) {
        if (task == null || task.getId() == null) {
            throw new IllegalArgumentException("task id is required for owner-checked stage update");
        }
        int updated = repository.advanceStageForOwner(
                task.getId(),
                normalizeText(leaseToken),
                stage == null ? task.getStage() : stage,
                LocalDateTime.now()
        );
        if (updated == 0) {
            throw new IllegalStateException("research task lost ownership before stage update");
        }
        task.setStage(stage == null ? task.getStage() : stage);
        task.setHeartbeatAt(LocalDateTime.now());
    }

    @Transactional
    public boolean heartbeatForOwner(ResearchTask task, String leaseToken) {
        if (task == null || task.getId() == null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = repository.heartbeatForOwner(task.getId(), normalizeText(leaseToken), now);
        if (updated > 0) {
            task.setHeartbeatAt(now);
            return true;
        }
        return false;
    }

    @Transactional
    public ResearchTask markSucceeded(ResearchTask task, Long resultReportVersionId) {
        LocalDateTime now = LocalDateTime.now();
        task.setStatus(ResearchTask.Status.SUCCEEDED);
        task.setStage(ResearchTask.Stage.COMPLETE);
        task.setResultReportVersionId(resultReportVersionId);
        task.setCompletedAt(now);
        task.setHeartbeatAt(now);
        task.setLeaseToken(null);
        task.setErrorMessage(null);
        return repository.saveAndFlush(task);
    }

    @Transactional
    public void markSucceededForOwner(ResearchTask task, String leaseToken, Long resultReportVersionId) {
        if (task == null || task.getId() == null) {
            throw new IllegalArgumentException("task id is required for owner-checked completion");
        }
        int updated = repository.completeForOwner(
                task.getId(),
                normalizeText(leaseToken),
                resultReportVersionId,
                LocalDateTime.now()
        );
        if (updated == 0) {
            throw new IllegalStateException("research task lost ownership before completion");
        }
        task.setStatus(ResearchTask.Status.SUCCEEDED);
        task.setStage(ResearchTask.Stage.COMPLETE);
        task.setResultReportVersionId(resultReportVersionId);
        task.setCompletedAt(LocalDateTime.now());
        task.setLeaseToken(null);
        task.setErrorMessage(null);
    }

    @Transactional
    public ResearchTask markFailed(ResearchTask task, String errorMessage) {
        LocalDateTime now = LocalDateTime.now();
        task.setStatus(ResearchTask.Status.FAILED);
        task.setStage(ResearchTask.Stage.FAILED);
        task.setErrorMessage(truncate(normalizeText(errorMessage), MAX_ERROR_LENGTH));
        task.setCompletedAt(now);
        task.setHeartbeatAt(now);
        task.setLeaseToken(null);
        return repository.saveAndFlush(task);
    }

    @Transactional
    public boolean markFailedForOwner(ResearchTask task, String leaseToken, String errorMessage) {
        if (task == null || task.getId() == null) {
            return false;
        }
        int updated = repository.failForOwner(
                task.getId(),
                normalizeText(leaseToken),
                truncate(normalizeText(errorMessage), MAX_ERROR_LENGTH),
                LocalDateTime.now()
        );
        if (updated > 0) {
            task.setStatus(ResearchTask.Status.FAILED);
            task.setStage(ResearchTask.Stage.FAILED);
            task.setErrorMessage(truncate(normalizeText(errorMessage), MAX_ERROR_LENGTH));
            task.setCompletedAt(LocalDateTime.now());
            task.setLeaseToken(null);
            return true;
        }
        return false;
    }

    @Transactional
    public RecoveryResult recoverStaleRunningTasks(Duration staleAfter, int maxAttempts) {
        Duration effectiveStaleAfter = staleAfter == null ? Duration.ofMinutes(15) : staleAfter;
        int effectiveMaxAttempts = Math.max(1, maxAttempts);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cutoff = now.minus(effectiveStaleAfter);
        List<ResearchTask> staleTasks = repository.findByStatusAndHeartbeatAtBefore(
                ResearchTask.Status.RUNNING,
                cutoff
        );
        List<ResearchTask> pendingRecoveredTasks = repository.findByStatusAndErrorMessage(
                ResearchTask.Status.PENDING,
                RECOVERED_FOR_RETRY_MESSAGE
        );

        LinkedHashSet<Long> retriedTaskIds = new LinkedHashSet<>();
        if (pendingRecoveredTasks != null) {
            pendingRecoveredTasks.stream()
                    .map(ResearchTask::getId)
                    .filter(java.util.Objects::nonNull)
                    .forEach(retriedTaskIds::add);
        }
        int failed = 0;
        for (ResearchTask task : staleTasks) {
            task.setLeaseToken(null);
            task.setHeartbeatAt(now);
            if (safeAttempts(task) >= effectiveMaxAttempts) {
                task.setStatus(ResearchTask.Status.FAILED);
                task.setStage(ResearchTask.Stage.FAILED);
                task.setCompletedAt(now);
                task.setErrorMessage("stale research task exceeded attempt limit " + effectiveMaxAttempts);
                failed++;
            } else {
                task.setStatus(ResearchTask.Status.PENDING);
                task.setStage(ResearchTask.Stage.CREATED);
                task.setErrorMessage(RECOVERED_FOR_RETRY_MESSAGE);
                if (task.getId() != null) {
                    retriedTaskIds.add(task.getId());
                }
            }
        }
        if (!staleTasks.isEmpty()) {
            repository.saveAll(staleTasks);
        }
        List<Long> ids = List.copyOf(retriedTaskIds);
        return new RecoveryResult(ids.size(), failed, ids);
    }

    public String buildSubmissionKey(String userId, String ticker, String userQuery) {
        return buildSubmissionKey(userId, ticker, userQuery, null);
    }

    public String buildSubmissionKey(
            String userId,
            String ticker,
            String userQuery,
            Long conversationId
    ) {
        String normalizedQuery = normalizeText(userQuery)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
        return "deep-submit:" + sha256(String.join("|",
                normalizeText(userId),
                conversationId == null ? "no-conversation" : conversationId.toString(),
                normalizeTicker(ticker),
                normalizedQuery));
    }

    public int countActiveTasks(String userId) {
        return (int) repository.countByUserIdAndStatusIn(normalizeText(userId),
                List.of(ResearchTask.Status.PENDING, ResearchTask.Status.RUNNING));
    }

    public String buildSubmissionPayload(String ticker, String query, String traceId, Long conversationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ticker", normalizeTicker(ticker));
        payload.put("query", normalizeText(query));
        payload.put("traceId", normalizeText(traceId));
        payload.put("conversationId", conversationId);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize submission payload", e);
        }
    }

    @Transactional
    public TaskReset resetForResubmission(ResearchTask task, String payloadJson) {
        if (task == null || task.getId() == null) {
            throw new IllegalArgumentException("persisted research task is required for resubmission");
        }
        String normalizedPayload = normalizePayload(payloadJson);
        Long conversationId = submissionConversationId(normalizedPayload, task.getConversationId());
        LocalDateTime now = LocalDateTime.now();
        int updated = repository.resetTerminalForResubmission(
                task.getId(), conversationId, normalizedPayload, now);
        if (updated == 0) {
            ResearchTask current = repository.findById(task.getId()).orElse(task);
            return new TaskReset(current, false);
        }
        task.setStatus(ResearchTask.Status.PENDING);
        task.setStage(ResearchTask.Stage.CREATED);
        task.setAttempts(0);
        task.setConversationId(conversationId);
        task.setLeaseToken(null);
        task.setPayloadJson(normalizedPayload);
        task.setErrorMessage(null);
        task.setResultReportVersionId(null);
        task.setStartedAt(null);
        task.setCompletedAt(null);
        task.setHeartbeatAt(now);
        return new TaskReset(task, true);
    }

    private Long submissionConversationId(String payloadJson, Long fallback) {
        try {
            JsonNode root = objectMapper.readTree(payloadJson);
            return root.path("conversationId").canConvertToLong()
                    ? root.path("conversationId").longValue()
                    : fallback;
        } catch (JsonProcessingException error) {
            return fallback;
        }
    }

    public String buildInvestmentReportKey(
            String userId,
            String ticker,
            String dataSnapshotHash,
            String contextHash
    ) {
        String canonical = String.join("|",
                normalizeText(userId),
                normalizeTicker(ticker),
                normalizeText(dataSnapshotHash),
                normalizeText(contextHash)
        );
        return "investment-report:" + sha256(canonical);
    }

    public String buildInvestmentReportPayload(
            String ticker,
            String query,
            String dataSnapshotHash,
            String contextHash
    ) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("ticker", normalizeTicker(ticker));
        payload.put("query", normalizeText(query));
        payload.put("dataSnapshotHash", normalizeText(dataSnapshotHash));
        payload.put("contextHash", normalizeText(contextHash));
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize research task payload", e);
        }
    }

    private int safeAttempts(ResearchTask task) {
        return task.getAttempts() == null ? 0 : task.getAttempts();
    }

    private String normalizePayload(String payloadJson) {
        String normalized = normalizeText(payloadJson);
        return normalized.isBlank() ? "{}" : normalized;
    }

    private String normalizeTicker(String value) {
        String normalized = normalizeText(value)
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9.]", "");
        return normalized.isBlank() ? "UNKNOWN" : normalized;
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.trim();
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is unavailable", e);
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    public record TaskCreation(ResearchTask task, boolean created) {
    }

    public record TaskReset(ResearchTask task, boolean reset) {
    }

    public record RecoveryResult(int retried, int failed, List<Long> retriedTaskIds) {

        public RecoveryResult {
            retriedTaskIds = retriedTaskIds == null ? List.of() : List.copyOf(retriedTaskIds);
        }
    }
}
