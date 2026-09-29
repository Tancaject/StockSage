package com.stocksage.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Future;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AsyncConfigTest {

    @Test
    void shadowOverloadCannotBorrowOnlineOrCallerThreads() throws Exception {
        AsyncConfig config = new AsyncConfig();
        var shadow = config.evolutionReplayExecutor();
        var online = (ThreadPoolTaskExecutor) config.agentTaskExecutor(1);
        shadow.initialize();
        online.initialize();
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        try {
            shadow.execute(() -> {
                started.countDown();
                try { release.await(); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            });
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            AtomicBoolean called = new AtomicBoolean();
            assertThatThrownBy(() -> shadow.execute(() -> called.set(true))).isInstanceOf(RejectedExecutionException.class);
            assertThat(called).isFalse();
            assertThat(shadow.getQueueCapacity()).isZero();
            assertThat(online.submit(() -> Thread.currentThread().getName()).get(2, TimeUnit.SECONDS)).startsWith("agent-worker-");
        } finally {
            release.countDown();
            shadow.shutdown();
            online.shutdown();
        }
    }

    @Test
    void saturatedBackgroundPoolCannotBorrowOnlineOrCallerThreadsAndBothQueuesAreBounded() throws Exception {
        AsyncConfig config = new AsyncConfig();
        var online = (ThreadPoolTaskExecutor) config.agentTaskExecutor(1);
        var background = (ThreadPoolTaskExecutor) config.backgroundTaskExecutor(1);
        online.initialize();
        background.initialize();
        CountDownLatch release = new CountDownLatch(1);
        try {
            saturate(background, 2, release);
            assertThat(online.submit(() -> Thread.currentThread().getName()).get(2, TimeUnit.SECONDS))
                    .startsWith("agent-worker-");
            saturate(online, 6, release);
            assertThat(online.getQueueCapacity()).isEqualTo(1);
            assertThat(background.getQueueCapacity()).isEqualTo(1);
            assertThatThrownBy(() -> config.agentTaskExecutor(-1)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> config.backgroundTaskExecutor(-1)).isInstanceOf(IllegalArgumentException.class);
        } finally {
            release.countDown();
            online.shutdown();
            background.shutdown();
        }
    }

    private void saturate(ThreadPoolTaskExecutor executor, int workers, CountDownLatch release) throws Exception {
        CountDownLatch started = new CountDownLatch(workers);
        for (int i = 0; i < workers; i++) executor.execute(() -> {
            started.countDown();
            try { release.await(); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        });
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        executor.execute(() -> {});
        AtomicBoolean ranOnCaller = new AtomicBoolean();
        assertThatThrownBy(() -> executor.execute(() -> ranOnCaller.set(true)))
                .isInstanceOf(RejectedExecutionException.class);
        assertThat(ranOnCaller).isFalse();
    }

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
