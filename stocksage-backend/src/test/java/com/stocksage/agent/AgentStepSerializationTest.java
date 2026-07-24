package com.stocksage.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentStepSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void oldTraceJsonWithoutAttributesRemainsReadable() throws Exception {
        AgentStep step = objectMapper.readValue(
                "{\"index\":1,\"thought\":\"plan\",\"durationMs\":3,\"tokenCount\":0}",
                AgentStep.class
        );

        assertThat(step.getThought()).isEqualTo("plan");
        assertThat(step.getAttributes()).isNull();
    }

    @Test
    void routingAttributesRoundTrip() throws Exception {
        AgentStep original = AgentStep.builder()
                .action("Coordinate Request")
                .attributes(Map.of("kind", "routing-decision", "route", "NEWS"))
                .build();

        AgentStep restored = objectMapper.readValue(objectMapper.writeValueAsString(original), AgentStep.class);

        assertThat(restored.getAttributes()).containsEntry("kind", "routing-decision")
                .containsEntry("route", "NEWS");
    }
}
