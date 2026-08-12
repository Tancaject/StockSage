package com.stocksage.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 后台执行器统一定义。
 *
 * <p>此前这些线程池是 ChatService 里的静态字段，脱离 Spring 生命周期、无法优雅停机，
 * 也难以在测试里替换。收敛到这里后由容器管理创建与关闭，行为参数（线程数、daemon）保持不变。</p>
 */
@Configuration
public class AsyncConfig {

    /**
     * 为 Spring MVC 的异步响应和 SSE 写出提供有界线程池。
     *
     * <p>它与 Agent、研究任务线程池隔离，避免慢客户端耗尽实际研究工作的执行容量。</p>
     *
     * @param corePoolSize 常驻工作线程数
     * @param maxPoolSize 高峰期允许创建的最大线程数
     * @param queueCapacity 等待执行的响应任务上限
     * @return 由 Spring 负责启动和关闭的 MVC 异步执行器
     */
    @Bean("applicationTaskExecutor")
    public ThreadPoolTaskExecutor applicationTaskExecutor(
            @Value("${stocksage.mvc.async.core-pool-size:4}") int corePoolSize,
            @Value("${stocksage.mvc.async.max-pool-size:16}") int maxPoolSize,
            @Value("${stocksage.mvc.async.queue-capacity:32}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setKeepAliveSeconds(60);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setThreadNamePrefix("mvc-stream-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }

    /**
     * 分析师/工具预取与后台记忆更新共用的工作线程池，对应原静态 AGENT_EXECUTOR（6 线程）。
     *
     * @return 固定并发度的 Agent 异步执行器
     */
    @Bean
    public AsyncTaskExecutor agentTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(6);
        executor.setMaxPoolSize(6);
        executor.setThreadNamePrefix("agent-worker-");
        executor.setDaemon(true);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }

    /**
     * 创建深度研究任务的租约心跳调度器。
     *
     * <p>线程数与 worker 并发一致，避免一次慢续租拖过其他任务的 TTL。</p>
     *
     * @param workerThreads 研究 worker 的配置并发数，最小按 1 处理
     * @return 专门执行租约续期的调度器
     */
    @Bean
    public TaskScheduler researchHeartbeatScheduler(
            @Value("${stocksage.research-task.worker-threads:2}") int workerThreads) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(Math.max(1, workerThreads));
        scheduler.setThreadNamePrefix("research-task-heartbeat-");
        scheduler.setDaemon(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }

    /**
     * Redis Stream 研究任务由固定 worker 线程长期阻塞消费，独立线程池避免占用分析师预取资源。
     *
     * @param workerThreads 同时消费 Redis Stream 的 worker 数，最小按 1 处理
     * @return 研究任务消费者专用执行器
     */
    @Bean("researchWorkerExecutor")
    public ThreadPoolTaskExecutor researchWorkerExecutor(
            @Value("${stocksage.research-task.worker-threads:2}") int workerThreads) {
        int size = Math.max(1, workerThreads);
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(size);
        executor.setMaxPoolSize(size);
        executor.setQueueCapacity(size);
        executor.setThreadNamePrefix("research-worker-");
        executor.setDaemon(true);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setPhase(Integer.MAX_VALUE - 100);
        return executor;
    }
}
