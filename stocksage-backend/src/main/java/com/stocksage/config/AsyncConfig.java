package com.stocksage.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
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
     * 分析师/工具预取与后台记忆更新共用的工作线程池，对应原静态 AGENT_EXECUTOR（6 线程）。
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
     * 深度研究任务的租约心跳调度器，对应原静态单线程 ScheduledExecutorService。
     */
    @Bean
    public TaskScheduler researchHeartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("research-task-heartbeat-");
        scheduler.setDaemon(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }
}
