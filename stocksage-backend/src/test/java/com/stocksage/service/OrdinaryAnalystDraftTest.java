package com.stocksage.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrdinaryAnalystDraftTest {
    @Test
    void replayUsesTheProductionDraftBoundaryAndOutcome() {
        String prefix = "本轮标的：AAPL\n[E1] 原始证据\n";
        var full = ToolPrefetchService.appendAnalystDraft(prefix, "Fundamentals Agent", "a".repeat(4500), "COMPLETED");
        assertThat(full.status()).isEqualTo("COMPLETED");
        assertThat(full.taskOutcome()).isEqualTo("COMPLETED");
        assertThat(full.context().substring(full.section().start(), full.section().end()))
                .isEqualTo("## Fundamentals Agent\n" + "a".repeat(4500) + "\n\n");

        var truncated = ToolPrefetchService.appendAnalystDraft(prefix, "Fundamentals Agent", "a".repeat(4501), "COMPLETED");
        assertThat(truncated.status()).isEqualTo("TRUNCATED");
        assertThat(truncated.taskOutcome()).isEqualTo("DEGRADED");
        assertThat(truncated.section()).isNull();
        assertThat(truncated.context()).startsWith(prefix).endsWith("\n...[truncated]\n\n");
        assertThat(truncated.context().length() - prefix.length() - "## Fundamentals Agent\n".length() - 2)
                .isEqualTo(4500);

        var empty = ToolPrefetchService.appendAnalystDraft(prefix, "Fundamentals Agent", "  ", "COMPLETED");
        assertThat(empty.status()).isEqualTo("EMPTY");
        assertThat(empty.taskOutcome()).isEqualTo("DEGRADED");
        assertThat(empty.context()).isEqualTo(prefix);
        assertThat(empty.section()).isNull();

        var alreadyDegraded = ToolPrefetchService.appendAnalystDraft(prefix, "Fundamentals Agent", "有据可查", "DEGRADED");
        assertThat(alreadyDegraded.taskOutcome()).isEqualTo("DEGRADED");
        assertThat(ToolPrefetchService.finishOrdinaryContext(alreadyDegraded.context(), alreadyDegraded.taskOutcome()))
                .endsWith("\n最终执行验收：DEGRADED。不得将此状态升级。\n");
    }
}
