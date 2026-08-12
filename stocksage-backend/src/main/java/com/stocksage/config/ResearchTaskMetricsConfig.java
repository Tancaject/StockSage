package com.stocksage.config;

import com.stocksage.model.entity.ResearchTask;
import com.stocksage.repository.ResearchTaskRepository;
import com.stocksage.service.ResearchTaskQueue;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.LongSupplier;

/**
 * 注册后台研究队列与任务状态的 Micrometer 指标。
 *
 * <p>队列深度来自 Redis Stream，运行中任务数来自 MySQL。Redis 暂不可用时，
 * 队列类指标返回 {@code -1}，从而区分“队列为空”和“无法观测”。</p>
 */
@Configuration
public class ResearchTaskMetricsConfig {

    /**
     * 把研究队列、待确认消息、死信和运行中任务注册为 Gauge。
     *
     * @param researchTaskQueue Redis Stream 队列访问入口
     * @param researchTaskRepository 研究任务持久化仓库
     * @return 在 MeterRegistry 初始化时执行的指标绑定器
     */
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
                    .description("Current uncompleted research task Redis Stream depth (unread plus pending)")
                    .register(registry);
            Gauge.builder(
                            "stocksage.research.queue.pending.depth",
                            researchTaskQueue,
                            queue -> safeQueueDepth(queue::pendingDepth)
                    )
                    .description("Current research task records delivered but not yet acknowledged")
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

    /**
     * 安全读取 Redis 队列深度，避免一次观测失败影响 Actuator 请求。
     *
     * @param depthSupplier 具体队列深度查询
     * @return 正常深度；Redis 不可用时返回 {@code -1}
     */
    private static double safeQueueDepth(LongSupplier depthSupplier) {
        try {
            return depthSupplier.getAsLong();
        } catch (ResearchTaskQueue.QueueUnavailableException error) {
            return -1;
        }
    }
}
