package com.stocksage.tool;

import com.stocksage.exception.ResearchBudgetExceededException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;

class ToolCallContextBudgetTest {
    @Test
    void budgetIsExplicitAcrossThreadsAndRestoredAfterNestedFailures() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var deadline = new ToolCallContext.RunDeadline(42L, System.currentTimeMillis() + 60_000);
        ToolCallContext.register("only-trace", 1L);
        try {
            ToolCallContext.withRunDeadline(deadline, () -> {
                assertThat(ToolCallContext.remainingMillis(100)).isEqualTo(100);
                assertThat(ToolCallContext.remainingMillis(120_000)).isBetween(1L, 60_000L);
                try {
                    assertThat(executor.submit(ToolCallContext::currentRunDeadline).get()).isNull();
                    assertThat(executor.submit(() -> ToolCallContext.withRunDeadline(deadline,
                            ToolCallContext::currentRunDeadline)).get()).isEqualTo(deadline);
                    assertThat(executor.submit(ToolCallContext::currentRunDeadline).get()).isNull();
                } catch (Exception error) { throw new AssertionError(error); }
                var invoked = new AtomicBoolean();
                assertThatThrownBy(() -> ToolCallContext.withRunDeadline(
                        new ToolCallContext.RunDeadline(43L, 1L), () -> invoked.getAndSet(true)))
                        .isInstanceOf(ResearchBudgetExceededException.class);
                assertThat(invoked).isFalse();
                assertThat(ToolCallContext.currentRunDeadline()).isEqualTo(deadline);
                return null;
            });
            assertThat(ToolCallContext.currentRunDeadline()).isNull();
        } finally {
            ToolCallContext.unregister("only-trace");
            executor.shutdownNow();
        }
    }
}
