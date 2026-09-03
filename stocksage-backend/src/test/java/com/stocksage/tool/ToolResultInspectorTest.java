package com.stocksage.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolResultInspectorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void detectsStructuredErrorsWithoutTreatingNormalResultsAsFailures() {
        assertThat(ToolResultInspector.isErrorPayload("{\"error\":true}", objectMapper)).isTrue();
        assertThat(ToolResultInspector.isErrorPayload("{\"error\":\"rate limited\"}", objectMapper)).isTrue();
        assertThat(ToolResultInspector.isErrorPayload(Map.of("error", true), objectMapper)).isTrue();
        assertThat(ToolResultInspector.isErrorPayload("{\"items\":[]}", objectMapper)).isFalse();
        assertThat(ToolResultInspector.isErrorPayload("plain text result", objectMapper)).isFalse();
    }
}
