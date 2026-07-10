package com.stocksage.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResearchTaskRecoverySchedulerTest {

    private final ResearchTaskService taskService = mock(ResearchTaskService.class);
    private final ResearchTaskQueue queue = mock(ResearchTaskQueue.class);
    private final ResearchTaskRecoveryScheduler scheduler = new ResearchTaskRecoveryScheduler(taskService, queue);

    @Test
    void reenqueueEveryRecoveredTaskAndContinueWhenOneQueueWriteFails() {
        when(taskService.recoverStaleRunningTasks(any(Duration.class), eq(3)))
                .thenReturn(new ResearchTaskService.RecoveryResult(2, 0, List.of(11L, 12L)));
        doThrow(new ResearchTaskQueue.QueueUnavailableException("redis unavailable", new IllegalStateException()))
                .when(queue).enqueue(11L);

        scheduler.recoverStaleResearchTasks();

        verify(queue).enqueue(11L);
        verify(queue).enqueue(12L);
    }
}
