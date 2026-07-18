package com.stocksage.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WebConfigTest {

    @Test
    void configuresDedicatedExecutorAndExplicitAsyncTimeout() {
        AdminApiInterceptor interceptor = mock(AdminApiInterceptor.class);
        AsyncTaskExecutor executor = mock(AsyncTaskExecutor.class);
        AsyncSupportConfigurer configurer = mock(AsyncSupportConfigurer.class);
        WebConfig webConfig = new WebConfig(interceptor, executor, 1_800_000L);

        webConfig.configureAsyncSupport(configurer);

        verify(configurer).setTaskExecutor(executor);
        verify(configurer).setDefaultTimeout(1_800_000L);
    }
}
