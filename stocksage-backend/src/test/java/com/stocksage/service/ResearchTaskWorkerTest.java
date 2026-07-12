package com.stocksage.service;

import com.stocksage.model.entity.ResearchTask;
import com.stocksage.repository.ResearchTaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResearchTaskWorkerTest {

    @Test
    void pendingRecordIsNotAcknowledgedWhenLeaseIsStillOwnedElsewhere() {
        ResearchTaskQueue queue = mock(ResearchTaskQueue.class);
        ResearchTaskRepository repository = mock(ResearchTaskRepository.class);
        ResearchTaskService taskService = mock(ResearchTaskService.class);
        ResearchTaskWorker worker = worker(queue, repository, taskService);
        ResearchTask task = task(7L, 0);
        MapRecord<String, String, String> record = record(7L);
        when(repository.findById(7L)).thenReturn(Optional.of(task));
        when(taskService.tryAcquire(task)).thenReturn(Optional.empty());
        worker.start();

        ReflectionTestUtils.invokeMethod(worker, "processRecord", record);

        verify(queue, never()).ack(record);
    }

    @Test
    void leaseIsReleasedWhenDeadLetterWriteFails() {
        ResearchTaskQueue queue = mock(ResearchTaskQueue.class);
        ResearchTaskRepository repository = mock(ResearchTaskRepository.class);
        ResearchTaskService taskService = mock(ResearchTaskService.class);
        ResearchTaskWorker worker = worker(queue, repository, taskService);
        ResearchTask task = task(8L, 3);
        MapRecord<String, String, String> record = record(8L);
        ResearchTaskLeaseService.Lease lease = new ResearchTaskLeaseService.Lease(
                "key-8", "token-8", ResearchTaskLeaseService.Backend.PROCESS);
        when(repository.findById(8L)).thenReturn(Optional.of(task));
        when(taskService.tryAcquire(task)).thenReturn(Optional.of(lease));
        when(taskService.failStaleRunningAtAttemptLimit(
                task, lease, 3, "research task exceeded attempt limit 3"))
                .thenReturn(true);
        org.mockito.Mockito.doThrow(new ResearchTaskQueue.QueueUnavailableException(
                        "down", new RuntimeException("down")))
                .when(queue).enqueueToDlq(8L, "max attempts exceeded");
        worker.start();

        ReflectionTestUtils.invokeMethod(worker, "processRecord", record);

        verify(taskService).release(lease);
    }

    @Test
    void staleRunningRecordMustWinDatabaseTakeoverBeforePipelineRuns() {
        ResearchTaskQueue queue = mock(ResearchTaskQueue.class);
        ResearchTaskRepository repository = mock(ResearchTaskRepository.class);
        ResearchTaskService taskService = mock(ResearchTaskService.class);
        DeepResearchPipeline pipeline = mock(DeepResearchPipeline.class);
        ResearchTaskWorker worker = worker(queue, repository, taskService, pipeline);
        ResearchTask task = task(10L, 1);
        task.setStatus(ResearchTask.Status.RUNNING);
        task.setLeaseToken("old-token");
        MapRecord<String, String, String> record = record(10L);
        ResearchTaskLeaseService.Lease lease = new ResearchTaskLeaseService.Lease(
                "key-10", "new-token", ResearchTaskLeaseService.Backend.REDIS);
        when(repository.findById(10L)).thenReturn(Optional.of(task));
        when(taskService.tryAcquire(task)).thenReturn(Optional.of(lease));
        when(taskService.takeOverRunningAttempt(task, lease, 3)).thenReturn(true);
        worker.start();

        ReflectionTestUtils.invokeMethod(worker, "processRecord", record);

        verify(taskService).takeOverRunningAttempt(task, lease, 3);
        verify(pipeline).runFullPipeline(task, lease);
        verify(queue).ack(record);
        verify(taskService).release(lease);
    }

    @Test
    void rejectedRunningTakeoverStaysPendingAndDoesNotRunPipeline() {
        ResearchTaskQueue queue = mock(ResearchTaskQueue.class);
        ResearchTaskRepository repository = mock(ResearchTaskRepository.class);
        ResearchTaskService taskService = mock(ResearchTaskService.class);
        DeepResearchPipeline pipeline = mock(DeepResearchPipeline.class);
        ResearchTaskWorker worker = worker(queue, repository, taskService, pipeline);
        ResearchTask task = task(11L, 1);
        task.setStatus(ResearchTask.Status.RUNNING);
        task.setLeaseToken("active-token");
        MapRecord<String, String, String> record = record(11L);
        ResearchTaskLeaseService.Lease lease = new ResearchTaskLeaseService.Lease(
                "key-11", "contender-token", ResearchTaskLeaseService.Backend.REDIS);
        when(repository.findById(11L)).thenReturn(Optional.of(task));
        when(taskService.tryAcquire(task)).thenReturn(Optional.of(lease));
        when(taskService.takeOverRunningAttempt(task, lease, 3)).thenReturn(false);
        worker.start();

        ReflectionTestUtils.invokeMethod(worker, "processRecord", record);

        verify(pipeline, never()).runFullPipeline(task, lease);
        verify(queue, never()).ack(record);
        verify(taskService).release(lease);
    }

    @Test
    void failedRunningAttemptIsResetForRetryWithoutAcknowledgingTheRecord() {
        ResearchTaskQueue queue = mock(ResearchTaskQueue.class);
        ResearchTaskRepository repository = mock(ResearchTaskRepository.class);
        ResearchTaskService taskService = mock(ResearchTaskService.class);
        DeepResearchPipeline pipeline = mock(DeepResearchPipeline.class);
        ResearchTaskWorker worker = worker(queue, repository, taskService, pipeline);
        ResearchTask task = task(9L, 1);
        MapRecord<String, String, String> record = record(9L);
        ResearchTaskLeaseService.Lease lease = new ResearchTaskLeaseService.Lease(
                "key-9", "token-9", ResearchTaskLeaseService.Backend.PROCESS);
        when(repository.findById(9L)).thenReturn(Optional.of(task));
        when(taskService.tryAcquire(task)).thenReturn(Optional.of(lease));
        doThrow(new IllegalStateException("simulated pipeline failure"))
                .when(pipeline).runFullPipeline(task, lease);
        when(taskService.resetRunningForRetryForOwner(
                task, "token-9", "simulated pipeline failure"))
                .thenReturn(true);
        worker.start();

        ReflectionTestUtils.invokeMethod(worker, "processRecord", record);

        verify(taskService).resetRunningForRetryForOwner(
                task, "token-9", "simulated pipeline failure");
        verify(queue, never()).ack(record);
        verify(taskService).release(lease);
    }

    @Test
    void ownershipLossLeavesRecordPendingWithoutResettingOldOwner() {
        ResearchTaskQueue queue = mock(ResearchTaskQueue.class);
        ResearchTaskRepository repository = mock(ResearchTaskRepository.class);
        ResearchTaskService taskService = mock(ResearchTaskService.class);
        DeepResearchPipeline pipeline = mock(DeepResearchPipeline.class);
        ResearchTaskWorker worker = worker(queue, repository, taskService, pipeline);
        ResearchTask task = task(12L, 1);
        MapRecord<String, String, String> record = record(12L);
        ResearchTaskLeaseService.Lease lease = new ResearchTaskLeaseService.Lease(
                "key-12", "token-12", ResearchTaskLeaseService.Backend.REDIS);
        when(repository.findById(12L)).thenReturn(Optional.of(task));
        when(taskService.tryAcquire(task)).thenReturn(Optional.of(lease));
        doThrow(new DeepResearchPipeline.OwnershipLostException("simulated ownership loss"))
                .when(pipeline).runFullPipeline(task, lease);
        worker.start();

        ReflectionTestUtils.invokeMethod(worker, "processRecord", record);

        verify(taskService, never()).resetRunningForRetryForOwner(
                task, "token-12", "simulated ownership loss");
        verify(queue, never()).ack(record);
        verify(taskService).release(lease);
    }

    private ResearchTaskWorker worker(
            ResearchTaskQueue queue,
            ResearchTaskRepository repository,
            ResearchTaskService taskService
    ) {
        return worker(queue, repository, taskService, mock(DeepResearchPipeline.class));
    }

    private ResearchTaskWorker worker(
            ResearchTaskQueue queue,
            ResearchTaskRepository repository,
            ResearchTaskService taskService,
            DeepResearchPipeline pipeline
    ) {
        return new ResearchTaskWorker(
                queue,
                repository,
                taskService,
                pipeline,
                mock(ThreadPoolTaskExecutor.class),
                true,
                1,
                3,
                0
        );
    }

    private ResearchTask task(Long id, int attempts) {
        ResearchTask task = new ResearchTask();
        task.setId(id);
        task.setIdempotencyKey("key-" + id);
        task.setStatus(ResearchTask.Status.PENDING);
        task.setStage(ResearchTask.Stage.CREATED);
        task.setAttempts(attempts);
        return task;
    }

    private MapRecord<String, String, String> record(Long taskId) {
        return StreamRecords.mapBacked(Map.of("taskId", taskId.toString()))
                .withStreamKey("stream:research-tasks");
    }
}
