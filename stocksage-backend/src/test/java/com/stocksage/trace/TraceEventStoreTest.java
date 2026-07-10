package com.stocksage.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.tool.ToolCallEventBus;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TraceEventStoreTest {

    @Test
    void appendFallsBackToLocalBusWhenRedisUnavailable() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        when(redis.opsForStream().add(any(MapRecord.class))).thenThrow(new RuntimeException("redis down"));
        ToolCallEventBus bus = mock(ToolCallEventBus.class);
        TraceEventStore store = new TraceEventStore(Optional.of(redis), bus,
                "stream:trace-events:", 5000, 3600, 2000);

        store.append("t1", "{\"type\":\"thought\"}");

        verify(bus).emit("t1", "{\"type\":\"thought\"}");
    }

    @Test
    void appendWithoutRedisTemplateGoesStraightToLocalBus() {
        ToolCallEventBus bus = mock(ToolCallEventBus.class);
        TraceEventStore store = new TraceEventStore(Optional.empty(), bus,
                "stream:trace-events:", 5000, 3600, 2000);

        store.append("t1", "{}");

        verify(bus).emit("t1", "{}");
    }

    @Test
    void liveRelayConsumesLocalFallbackWhenRedisIsUnavailable() {
        ToolCallEventBus bus = new ToolCallEventBus();
        TraceEventStore store = new TraceEventStore(Optional.empty(), bus,
                "stream:trace-events:", 5000, 3600, 10);
        TraceEventRelay relay = new TraceEventRelay(store, bus, new ObjectMapper());

        var result = relay.live("t1", null).collectList().toFuture();
        store.append("t1", "{\"type\":\"thought\",\"content\":\"a\"}");
        store.append("t1", "{\"type\":\"task-final\",\"content\":\"done\"}");

        List<TraceEventStore.StoredEvent> events = result.orTimeout(2, java.util.concurrent.TimeUnit.SECONDS).join();
        assertThat(events).extracting(TraceEventStore.StoredEvent::chunkJson)
                .containsExactly(
                        "{\"type\":\"thought\",\"content\":\"a\"}",
                        "{\"type\":\"task-final\",\"content\":\"done\"}");
    }
}
