package com.stocksage.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 后台执行器统一定义。
 *
 * <p>由容器管理生命周期，隔离响应写出、在线执行、后台更新和持久化研究 worker。</p>
 */
@Configuration
public class AsyncConfig {

    /** Offline evaluation must never borrow an online worker or execute on the caller when full. */
    @Bean
    @Profile("evolution-eval")
    public ThreadPoolTaskExecutor evolutionReplayExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("evolution-replay-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }

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
     * 在线分析师、工具预取与补证共享有限容量；满队拒绝由提交方按业务语义处理。
     *
     * @return 固定并发度的 Agent 异步执行器
     */
    @Bean
    public AsyncTaskExecutor agentTaskExecutor(
            @Value("${stocksage.agent.queue-capacity:32}") int queueCapacity) {
        if (queueCapacity < 0) throw new IllegalArgumentException("Agent queue capacity must be non-negative");
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(6);
        executor.setMaxPoolSize(6);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("agent-worker-");
        executor.setDaemon(true);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }

    /** Background model/embedding work cannot occupy the online pool's slots. */
    @Bean
    public AsyncTaskExecutor backgroundTaskExecutor(
            @Value("${stocksage.background.queue-capacity:16}") int queueCapacity) {
        if (queueCapacity < 0) throw new IllegalArgumentException("Background queue capacity must be non-negative");
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("background-worker-");
        executor.setDaemon(true);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        return executor;
    }

    /**
     * 意图原型向量匹配的隔离线程池。
     *
     * <p>本地 embedding 客户端是同步调用且供应商超时可能很长；路由热路径只等待一个很短的
     * deadline。隔离且有界的线程池可避免慢 embedding 占满普通 Agent 或 SSE 线程。</p>
     */
    @Bean("intentEmbeddingExecutor")
    public ThreadPoolTaskExecutor intentEmbeddingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(4);
        executor.setKeepAliveSeconds(30);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setThreadNamePrefix("intent-embedding-");
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
