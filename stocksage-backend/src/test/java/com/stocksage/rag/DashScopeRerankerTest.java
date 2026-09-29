package com.stocksage.rag;

import com.alibaba.cloud.ai.dashscope.rerank.DashScopeRerankModel;
import com.alibaba.cloud.ai.model.RerankRequest;
import com.alibaba.cloud.ai.model.RerankResponse;
import com.stocksage.exception.ResearchBudgetExceededException;
import com.stocksage.tool.ToolCallContext;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DashScopeRerankerTest {
    @SuppressWarnings("unchecked")
    private DashScopeReranker reranker(DashScopeRerankModel model) {
        ObjectProvider<DashScopeRerankModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(model);
        var reranker = new DashScopeReranker(provider, 1);
        ReflectionTestUtils.setField(reranker, "enabled", true);
        ReflectionTestUtils.setField(reranker, "modelName", "test-rerank");
        return reranker;
    }

    @Test
    void saturatedCallKeepsOriginalOrderWithoutInvokingProviderAndReleasesAfterReturn() throws Exception {
        var model = mock(DashScopeRerankModel.class);
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        when(model.call(any(RerankRequest.class))).thenAnswer(invocation -> {
            entered.countDown();
            if (!finish.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("Test provider was not released");
            return new RerankResponse(List.of());
        });
        var reranker = reranker(model);
        var candidates = List.of(new Document("first"), new Document("second"));
        var executor = Executors.newSingleThreadExecutor();
        try {
            var active = executor.submit(() -> reranker.rerank("query", candidates, 1));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(reranker.rerank("query", candidates, 1)).containsExactly(candidates.get(0));
            verify(model, times(1)).call(any(RerankRequest.class));
            finish.countDown();
            assertThat(active.get(2, TimeUnit.SECONDS)).containsExactly(candidates.get(0));
            assertThat(reranker.rerank("query", candidates, 1)).containsExactly(candidates.get(0));
            verify(model, times(2)).call(any(RerankRequest.class));
        } finally {
            finish.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void wrappedBudgetDoesNotDegradeAndFailureReleasesPermit() {
        var model = mock(DashScopeRerankModel.class);
        var reranker = reranker(model);
        var candidates = List.of(new Document("first"));
        var budget = new ResearchBudgetExceededException(71L, ResearchBudgetExceededException.Reason.DEADLINE);
        when(model.call(any(RerankRequest.class)))
                .thenThrow(new IllegalStateException("wrapped", budget))
                .thenReturn(new RerankResponse(List.of()));
        var deadline = new ToolCallContext.RunDeadline(71L, System.currentTimeMillis() + 60_000);
        assertThatThrownBy(() -> ToolCallContext.withRunDeadline(deadline,
                () -> reranker.rerank("query", candidates, 1))).isSameAs(budget);
        assertThat(reranker.rerank("query", candidates, 1)).containsExactlyElementsOf(candidates);
        verify(model, times(2)).call(any(RerankRequest.class));
    }
}
