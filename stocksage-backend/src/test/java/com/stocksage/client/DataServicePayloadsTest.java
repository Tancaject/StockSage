package com.stocksage.client;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DataServicePayloadsTest {

    @Test
    void topLevelErrorFieldSignalsFailure() {
        assertThat(DataServicePayloads.isFailure("{\"error\":true,\"message\":\"boom\"}")).isTrue();
        assertThat(DataServicePayloads.isFailure("{\"results\":[],\"error\":\"rate limited\"}")).isTrue();
    }

    @Test
    void successPayloadsAreNotFailures() {
        assertThat(DataServicePayloads.isFailure("{\"data\":[1,2,3],\"count\":3}")).isFalse();
        assertThat(DataServicePayloads.isFailure("{\"error\":false,\"data\":[]}")).isFalse();
        assertThat(DataServicePayloads.isFailure("{\"error\":null,\"data\":[]}")).isFalse();
        assertThat(DataServicePayloads.isFailure("[1,2,3]")).isFalse();
    }

    @Test
    void errorMentionedInsideContentIsNotMisjudged() {
        // 旧实现用子串/正则匹配，搜索结果正文里出现 "error": 字样会被误判为失败。
        String searchResult = "{\"results\":[{\"title\":\"HTTP status\",\"snippet\":\"body has \\\"error\\\": true inside\"}]}";
        assertThat(DataServicePayloads.isFailure(searchResult)).isFalse();
    }

    @Test
    void blankOrNonJsonBodiesAreFailures() {
        assertThat(DataServicePayloads.isFailure(null)).isTrue();
        assertThat(DataServicePayloads.isFailure("  ")).isTrue();
        assertThat(DataServicePayloads.isFailure("plain text response")).isTrue();
    }
}
