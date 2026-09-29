package com.stocksage.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.exception.ResearchBudgetExceededException;
import com.stocksage.tool.ToolCallContext;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.support.TaskExecutorAdapter;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class CapabilityGatewayTest {
    @Test
    void searchAdaptersPreserveDepthOriginalQuestionAndOwnerAcrossOneWorkerSubmission() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var submissions = new java.util.concurrent.atomic.AtomicInteger();
        var news = mock(com.stocksage.tool.NewsTools.class);
        var registry = mock(CapabilityRegistry.class);
        var observer = mock(CapabilityInvocationObserver.class);
        var gateway = new CapabilityGateway(registry, new CapabilityPolicy(), observer,
                new TaskExecutorAdapter(command -> { submissions.incrementAndGet(); executor.execute(command); }),
                new ObjectMapper());
        var run = new ToolCallContext.RunExecution(92L, 2, "owner", "trace", System.currentTimeMillis() + 60_000L);
        String original = "比较 2020 年与今年的公告";
        for (CapabilityAdapter adapter : java.util.List.of(new LocalNewsSearchCapabilityAdapter(news),
                new LocalWebSearchCapabilityAdapter(news))) {
            var descriptor = new CapabilityDescriptor(adapter.capabilityId(), CapabilityDescriptor.ProviderType.LOCAL,
                    "local", adapter.capabilityId(), CapabilityDescriptor.RiskLevel.READ_ONLY, 1000, 1024, true);
            when(registry.require(adapter.capabilityId())).thenReturn(
                    new CapabilityRegistry.RegisteredCapability(descriptor, adapter));
        }
        org.mockito.stubbing.Answer<String> answer = call -> {
            assertThat(ToolCallContext.getUserQuery()).isEqualTo(original);
            assertThat(ToolCallContext.currentRunExecution()).isEqualTo(run);
            assertThat(ToolCallContext.isObservationSuppressed()).isTrue();
            return "{\"results\":[]}";
        };
        when(news.searchNews("AAPL focused", 5, true)).thenAnswer(answer);
        when(news.webSearch("AAPL focused", 5, true)).thenAnswer(answer);
        try {
            assertThatThrownBy(() -> new CapabilityInvocationContext("u", 1L, "trace", null,
                    Set.of(LocalNewsSearchCapabilityAdapter.ID), Instant.now().plusSeconds(5),
                    new ToolCallContext.RunDeadline(93L, run.deadlineEpochMs()), original, run))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new LocalNewsSearchCapabilityAdapter(news)
                    .invoke(Map.of("query", "AAPL", "advanced", "true"), null))
                    .isInstanceOf(CapabilityException.class);
            for (String id : java.util.List.of(LocalNewsSearchCapabilityAdapter.ID, LocalWebSearchCapabilityAdapter.ID)) {
                var context = new CapabilityInvocationContext("u", 1L, "trace", null, Set.of(id),
                        Instant.now().plusSeconds(5), null, original, run);
                assertThat(gateway.invoke(id, Map.of("query", "AAPL focused", "maxResults", 5, "advanced", true), context)
                        .status()).isEqualTo(CapabilityResult.Status.SUCCESS);
            }
            assertThat(submissions.get()).isEqualTo(2);
            assertThat(executor.submit(ToolCallContext::currentRunExecution).get()).isNull();
            assertThat(executor.submit(ToolCallContext::currentRunDeadline).get()).isNull();
            assertThat(executor.submit(ToolCallContext::getUserQuery).get()).isNull();
            verify(news).searchNews("AAPL focused", 5, true);
            verify(news).webSearch("AAPL focused", 5, true);
            verifyNoMoreInteractions(news);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rejectedSubmissionClosesObservationWithoutInvokingAdapter() {
        var executor = Executors.newSingleThreadExecutor();
        executor.shutdown();
        var registry = mock(CapabilityRegistry.class);
        var adapter = mock(CapabilityAdapter.class);
        var observer = mock(CapabilityInvocationObserver.class);
        var descriptor = new CapabilityDescriptor("test.capability", CapabilityDescriptor.ProviderType.LOCAL,
                "test", "test", CapabilityDescriptor.RiskLevel.READ_ONLY, 1000, 1024, true);
        when(registry.require(descriptor.id())).thenReturn(new CapabilityRegistry.RegisteredCapability(descriptor, adapter));
        var gateway = new CapabilityGateway(registry, new CapabilityPolicy(), observer,
                new TaskExecutorAdapter(executor), new ObjectMapper());
        var context = new CapabilityInvocationContext("u", 1L, "trace", "skill", Set.of(descriptor.id()),
                Instant.now().plusSeconds(60));
        assertThatThrownBy(() -> gateway.invoke(descriptor.id(), Map.of(), context))
                .isInstanceOf(CapabilityException.class)
                .extracting(error -> ((CapabilityException) error).reason())
                .isEqualTo(CapabilityException.Reason.CAPACITY_EXCEEDED);
        verify(observer).started(descriptor, context);
        verify(observer).failed(eq(descriptor), eq(context), eq(Map.of()), any(CapabilityException.class), anyLong());
        verifyNoInteractions(adapter);
    }
    @Test
    void runDeadlineCrossesWorkerBoundaryAndBudgetFailureIsNotProviderFailure() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var registry = mock(CapabilityRegistry.class);
        var adapter = mock(CapabilityAdapter.class);
        var descriptor = new CapabilityDescriptor("test.capability", CapabilityDescriptor.ProviderType.LOCAL,
                "test", "test", CapabilityDescriptor.RiskLevel.READ_ONLY, 1000, 1024, true);
        when(registry.require(descriptor.id())).thenReturn(new CapabilityRegistry.RegisteredCapability(descriptor, adapter));
        when(adapter.isAvailable()).thenReturn(true);
        var gateway = new CapabilityGateway(registry, new CapabilityPolicy(), mock(CapabilityInvocationObserver.class),
                new TaskExecutorAdapter(executor), new ObjectMapper());
        var deadline = new ToolCallContext.RunDeadline(92L, System.currentTimeMillis() + 60_000L);
        var context = new CapabilityInvocationContext("u", 1L, "trace", "skill", Set.of(descriptor.id()),
                Instant.now().plusSeconds(60), deadline);
        var exhausted = new ResearchBudgetExceededException(92L, ResearchBudgetExceededException.Reason.DEADLINE);
        when(adapter.invoke(any(), any())).thenAnswer(call -> {
            assertThat(ToolCallContext.currentRunDeadline()).isEqualTo(deadline);
            throw exhausted;
        });
        try {
            assertThatThrownBy(() -> gateway.invoke(descriptor.id(), Map.of(), context)).isSameAs(exhausted);
            assertThat(executor.submit(ToolCallContext::currentRunDeadline).get()).isNull();
            reset(adapter);
            var expired = new CapabilityInvocationContext("u", 1L, "trace", "skill", Set.of(descriptor.id()),
                    Instant.now().plusSeconds(60), new ToolCallContext.RunDeadline(92L, 1L));
            assertThatThrownBy(() -> gateway.invoke(descriptor.id(), Map.of(), expired))
                    .isInstanceOf(ResearchBudgetExceededException.class);
            verifyNoInteractions(adapter);
        } finally {
            executor.shutdownNow();
        }
    }
}
