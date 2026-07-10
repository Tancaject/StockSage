package com.stocksage.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.tool.ToolCallEventBus;
import com.stocksage.trace.TraceEventRelay;
import com.stocksage.trace.TraceEventStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class TraceEventBridgeIT {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    private LettuceConnectionFactory connectionFactory;
    private TraceEventStore store;
    private TraceEventRelay relay;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        StringRedisTemplate redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();

        ToolCallEventBus bus = new ToolCallEventBus();
        store = new TraceEventStore(Optional.of(redis), bus,
                "stream:trace-events:it:", 5000, 3600, 100);
        relay = new TraceEventRelay(store, bus, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        connectionFactory.destroy();
    }

    @Test
    void appendedEventsAreRelayedInOrderAndReplayedAfterReconnect() {
        store.append("trace-1", chunk("thought", "a"));
        store.append("trace-1", chunk("thought", "b"));

        List<TraceEventStore.StoredEvent> replay = store.replayRange("trace-1", null);
        assertThat(replay).hasSize(2);

        List<TraceEventStore.StoredEvent> resumed = store.replayRange("trace-1", replay.get(0).entryId());
        assertThat(resumed).hasSize(1);
        assertThat(resumed.get(0).chunkJson()).contains("\"content\":\"b\"");

        store.append("trace-1", chunk("task-final", "done"));
        List<String> streamed = relay.live("trace-1", null)
                .map(TraceEventStore.StoredEvent::chunkJson)
                .collectList()
                .block(Duration.ofSeconds(10));

        assertThat(streamed).hasSize(3);
        assertThat(streamed.get(2)).contains("task-final");
    }

    private String chunk(String type, String content) {
        return "{\"type\":\"" + type + "\",\"content\":\"" + content + "\"}";
    }
}
