package com.stocksage.config;

import com.stocksage.model.entity.ResearchTask;
import com.stocksage.repository.ResearchTaskRepository;
import com.stocksage.service.ResearchTaskQueue;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.LongSupplier;

@Configuration
public class ResearchTaskMetricsConfig {

    @Bean
    MeterBinder researchTaskMetrics(
            ResearchTaskQueue researchTaskQueue,
            ResearchTaskRepository researchTaskRepository
    ) {
        return registry -> {
            Gauge.builder(
                            "stocksage.research.queue.depth",
                            researchTaskQueue,
                            queue -> safeQueueDepth(queue::queueDepth)
                    )
                    .description("Current research task Redis Stream depth")
                    .register(registry);
            Gauge.builder(
                            "stocksage.research.queue.dlq.depth",
                            researchTaskQueue,
                            queue -> safeQueueDepth(queue::dlqDepth)
                    )
                    .description("Current research task dead-letter Stream depth")
                    .register(registry);
            Gauge.builder(
                            "stocksage.research.tasks.running",
                            researchTaskRepository,
                            repository -> repository.countByStatus(ResearchTask.Status.RUNNING)
                    )
                    .description("Current RUNNING research tasks in the database")
                    .register(registry);
        };
    }

    private static double safeQueueDepth(LongSupplier depthSupplier) {
        try {
            return depthSupplier.getAsLong();
        } catch (ResearchTaskQueue.QueueUnavailableException error) {
            return -1;
        }
    }
}
