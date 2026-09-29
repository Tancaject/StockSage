package com.stocksage.conversation;

import com.stocksage.service.ToolPrefetchService;
import com.stocksage.knowledge.SearchResultIngestionService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ChatServiceExecutorIsolationTest {
    @Test
    void actualConstructorsSelectNamedPoolsWhenBothExecutorBeansExist() {
        for (Class<?> serviceType : new Class<?>[]{ChatService.class, ToolPrefetchService.class,
                SearchResultIngestionService.class}) {
            try (var context = new AnnotationConfigApplicationContext()) {
                var online = mock(AsyncTaskExecutor.class);
                var background = mock(AsyncTaskExecutor.class);
                context.getBeanFactory().registerSingleton("agentTaskExecutor", online);
                context.getBeanFactory().registerSingleton("backgroundTaskExecutor", background);
                for (var parameter : serviceType.getConstructors()[0].getParameters()) {
                    if (parameter.getType() != AsyncTaskExecutor.class) {
                        context.getBeanFactory().registerSingleton(parameter.getName(), mock(parameter.getType()));
                    }
                }
                context.register(serviceType);
                context.refresh();
                Object service = context.getBean(serviceType);
                if (service instanceof ToolPrefetchService) {
                    assertThat(ReflectionTestUtils.getField(service, "agentTaskExecutor")).isSameAs(online);
                } else {
                    assertThat(ReflectionTestUtils.getField(service, "backgroundTaskExecutor")).isSameAs(background);
                }
            }
        }
    }
}
