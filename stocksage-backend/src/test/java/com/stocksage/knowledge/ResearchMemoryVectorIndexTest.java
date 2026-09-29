package com.stocksage.knowledge;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResearchMemoryVectorIndexTest {

    @Test
    void tenantKeyIsStableAndDoesNotExposeRawUserId() {
        String first = ResearchMemoryVectorIndex.tenantKey("u-private");
        String second = ResearchMemoryVectorIndex.tenantKey("u-private");

        assertThat(first).isEqualTo(second).hasSize(64).doesNotContain("u-private");
    }
}
