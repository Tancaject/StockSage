package com.stocksage.config;

import com.stocksage.model.entity.ResearchTask;
import com.stocksage.repository.ResearchTaskRepository;
import com.stocksage.service.ResearchTaskQueue;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResearchTaskMetricsConfigTest {

    private final ResearchTaskQueue queue = mock(ResearchTaskQueue.class);
    private final ResearchTaskRepository repository = mock(ResearchTaskRepository.class);
    private final ResearchTaskMetricsConfig config = new ResearchTaskMetricsConfig();

    @Test
    void registersQueueDlqAndRunningTaskGauges() {
        when(queue.queueDepth()).thenReturn(8L);
        when(queue.dlqDepth()).thenReturn(2L);
        when(repository.countByStatus(ResearchTask.Status.RUNNING)).thenReturn(3L);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        config.researchTaskMetrics(queue, repository).bindTo(registry);

        assertThat(registry.get("stocksage.research.queue.depth").gauge().value()).isEqualTo(8);
        assertThat(registry.get("stocksage.research.queue.dlq.depth").gauge().value()).isEqualTo(2);
        assertThat(registry.get("stocksage.research.tasks.running").gauge().value()).isEqualTo(3);
    }

    @Test
    void reportsMinusOneForQueueDepthWhenRedisIsUnavailable() {
        ResearchTaskQueue.QueueUnavailableException unavailable =
                new ResearchTaskQueue.QueueUnavailableException("redis unavailable", new IllegalStateException());
        when(queue.queueDepth()).thenThrow(unavailable);
        when(queue.dlqDepth()).thenThrow(unavailable);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        config.researchTaskMetrics(queue, repository).bindTo(registry);

        assertThat(registry.get("stocksage.research.queue.depth").gauge().value()).isEqualTo(-1);
        assertThat(registry.get("stocksage.research.queue.dlq.depth").gauge().value()).isEqualTo(-1);
    }
}
