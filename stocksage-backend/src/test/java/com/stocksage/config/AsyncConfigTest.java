package com.stocksage.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncConfigTest {

    @Test
    void applicationTaskExecutorIsBoundedAndDedicatedToMvcStreaming() throws Exception {
        ThreadPoolTaskExecutor executor = new AsyncConfig().applicationTaskExecutor(4, 16, 32);
        executor.initialize();

        try {
            assertThat(executor.getCorePoolSize()).isEqualTo(4);
            assertThat(executor.getMaxPoolSize()).isEqualTo(16);
            assertThat(executor.getQueueCapacity()).isEqualTo(32);
            assertThat(executor.getKeepAliveSeconds()).isEqualTo(60);
            assertThat(executor.getThreadPoolExecutor().allowsCoreThreadTimeOut()).isTrue();
            assertThat(executor.getThreadNamePrefix()).isEqualTo("mvc-stream-");

            Future<String> threadName = executor.submit(() -> Thread.currentThread().getName());
            assertThat(threadName.get()).startsWith("mvc-stream-");
        } finally {
            executor.shutdown();
        }
    }
}
