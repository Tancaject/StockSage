package com.stocksage.repository;

import com.stocksage.model.entity.ResearchTask;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
class ResearchTaskRepositoryTest {

    @Autowired
    private ResearchTaskRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void failIfPendingOnlyClosesTasksThatHaveNotBeenClaimed() {
        ResearchTask pending = repository.saveAndFlush(task(
                "deep-submit:pending", ResearchTask.Status.PENDING, null));
        ResearchTask running = repository.saveAndFlush(task(
                "deep-submit:running", ResearchTask.Status.RUNNING, "owner-a"));
        entityManager.clear();

        LocalDateTime failedAt = LocalDateTime.now();
        int pendingUpdated = repository.failIfPending(pending.getId(), "invalid payload", failedAt);
        int runningUpdated = repository.failIfPending(running.getId(), "stale contender", failedAt);
        entityManager.flush();
        entityManager.clear();

        ResearchTask failed = repository.findById(pending.getId()).orElseThrow();
        ResearchTask stillRunning = repository.findById(running.getId()).orElseThrow();
        assertThat(pendingUpdated).isEqualTo(1);
        assertThat(failed.getStatus()).isEqualTo(ResearchTask.Status.FAILED);
        assertThat(failed.getStage()).isEqualTo(ResearchTask.Stage.FAILED);
        assertThat(failed.getErrorMessage()).isEqualTo("invalid payload");
        assertThat(failed.getCompletedAt()).isNotNull();
        assertThat(runningUpdated).isZero();
        assertThat(stillRunning.getStatus()).isEqualTo(ResearchTask.Status.RUNNING);
        assertThat(stillRunning.getStage()).isEqualTo(ResearchTask.Stage.DATA_PREFETCH);
        assertThat(stillRunning.getLeaseToken()).isEqualTo("owner-a");
        assertThat(stillRunning.getErrorMessage()).isNull();
    }

    @Test
    void takeoverCasRejectsFreshHeartbeatAndWrongOwnerBeforeAcceptingStaleOwner() {
        LocalDateTime now = LocalDateTime.now();
        ResearchTask fresh = task("deep-submit:fresh", ResearchTask.Status.RUNNING, "owner-a");
        fresh.setHeartbeatAt(now.minusSeconds(2));
        fresh = repository.saveAndFlush(fresh);
        ResearchTask stale = task("deep-submit:stale", ResearchTask.Status.RUNNING, "owner-a");
        stale.setHeartbeatAt(now.minusMinutes(1));
        stale.setErrorMessage("interrupted");
        stale = repository.saveAndFlush(stale);
        entityManager.clear();
        LocalDateTime cutoff = now.minusSeconds(10);

        int freshRejected = repository.takeOverStaleRunningAttempt(
                fresh.getId(), "owner-a", "owner-b", cutoff, 3, now);
        int wrongOwnerRejected = repository.takeOverStaleRunningAttempt(
                stale.getId(), "not-owner-a", "owner-b", cutoff, 3, now);
        int staleTakenOver = repository.takeOverStaleRunningAttempt(
                stale.getId(), "owner-a", "owner-b", cutoff, 3, now);
        entityManager.flush();
        entityManager.clear();

        ResearchTask stillFresh = repository.findById(fresh.getId()).orElseThrow();
        ResearchTask takenOver = repository.findById(stale.getId()).orElseThrow();
        assertThat(freshRejected).isZero();
        assertThat(wrongOwnerRejected).isZero();
        assertThat(staleTakenOver).isEqualTo(1);
        assertThat(stillFresh.getLeaseToken()).isEqualTo("owner-a");
        assertThat(stillFresh.getAttempts()).isEqualTo(1);
        assertThat(takenOver.getStatus()).isEqualTo(ResearchTask.Status.RUNNING);
        assertThat(takenOver.getStage()).isEqualTo(ResearchTask.Stage.DATA_PREFETCH);
        assertThat(takenOver.getLeaseToken()).isEqualTo("owner-b");
        assertThat(takenOver.getAttempts()).isEqualTo(2);
        assertThat(takenOver.getErrorMessage()).isNull();
        assertThat(takenOver.getCompletedAt()).isNull();
    }

    @Test
    void onlyOneTakeoverContenderWinsAndOldOwnerCannotWriteAfterward() {
        LocalDateTime now = LocalDateTime.now();
        ResearchTask stale = task("deep-submit:contended", ResearchTask.Status.RUNNING, "owner-a");
        stale.setHeartbeatAt(now.minusMinutes(1));
        stale = repository.saveAndFlush(stale);
        entityManager.clear();
        LocalDateTime cutoff = now.minusSeconds(10);

        int contenderB = repository.takeOverStaleRunningAttempt(
                stale.getId(), "owner-a", "owner-b", cutoff, 3, now);
        int contenderC = repository.takeOverStaleRunningAttempt(
                stale.getId(), "owner-a", "owner-c", cutoff, 3, now.plusNanos(1));
        int oldHeartbeat = repository.heartbeatForOwner(stale.getId(), "owner-a", now.plusSeconds(1));
        int oldCompletion = repository.completeForOwner(
                stale.getId(), "owner-a", 99L, now.plusSeconds(1));
        entityManager.flush();
        entityManager.clear();

        ResearchTask winner = repository.findById(stale.getId()).orElseThrow();
        assertThat(contenderB).isEqualTo(1);
        assertThat(contenderC).isZero();
        assertThat(oldHeartbeat).isZero();
        assertThat(oldCompletion).isZero();
        assertThat(winner.getLeaseToken()).isEqualTo("owner-b");
        assertThat(winner.getAttempts()).isEqualTo(2);
        assertThat(winner.getStatus()).isEqualTo(ResearchTask.Status.RUNNING);
    }

    @Test
    void maxAttemptAndRecoveryCasCannotOverwriteFreshOrChangedOwner() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cutoff = now.minusSeconds(10);
        ResearchTask exhausted = task(
                "deep-submit:exhausted", ResearchTask.Status.RUNNING, "owner-a");
        exhausted.setAttempts(3);
        exhausted.setHeartbeatAt(now.minusMinutes(1));
        exhausted = repository.saveAndFlush(exhausted);
        ResearchTask retryFresh = task(
                "deep-submit:retry-fresh", ResearchTask.Status.RUNNING, "owner-a");
        retryFresh.setHeartbeatAt(now.minusSeconds(2));
        retryFresh = repository.saveAndFlush(retryFresh);
        entityManager.clear();

        int wrongOwnerRejected = repository.failStaleRunningAtAttemptLimit(
                exhausted.getId(), "owner-b", cutoff, 3, "attempt limit", now);
        int exhaustedFailed = repository.failStaleRunningAtAttemptLimit(
                exhausted.getId(), "owner-a", cutoff, 3, "attempt limit", now);
        int freshRecoveryRejected = repository.resetStaleRunningForRetry(
                retryFresh.getId(), "owner-a", cutoff, 3, "retry", now);
        entityManager.flush();
        entityManager.clear();

        ResearchTask failed = repository.findById(exhausted.getId()).orElseThrow();
        ResearchTask untouched = repository.findById(retryFresh.getId()).orElseThrow();
        assertThat(wrongOwnerRejected).isZero();
        assertThat(exhaustedFailed).isEqualTo(1);
        assertThat(freshRecoveryRejected).isZero();
        assertThat(failed.getStatus()).isEqualTo(ResearchTask.Status.FAILED);
        assertThat(failed.getLeaseToken()).isNull();
        assertThat(failed.getErrorMessage()).isEqualTo("attempt limit");
        assertThat(untouched.getStatus()).isEqualTo(ResearchTask.Status.RUNNING);
        assertThat(untouched.getLeaseToken()).isEqualTo("owner-a");
    }

    private ResearchTask task(String key, ResearchTask.Status status, String leaseToken) {
        ResearchTask task = new ResearchTask();
        task.setUserId("u_001");
        task.setConversationId(10L);
        task.setIdempotencyKey(key);
        task.setTicker("AAPL");
        task.setStatus(status);
        task.setStage(status == ResearchTask.Status.RUNNING
                ? ResearchTask.Stage.DATA_PREFETCH
                : ResearchTask.Stage.CREATED);
        task.setAttempts(status == ResearchTask.Status.RUNNING ? 1 : 0);
        task.setLeaseToken(leaseToken);
        task.setPayloadJson("{}");
        return task;
    }
}
