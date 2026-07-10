package com.stocksage.tool;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCallEventBusTest {

    @Test
    void multipleObserversShareTheSameReplayChannelWithoutReplacingEachOther() {
        ToolCallEventBus bus = new ToolCallEventBus();
        Flux<String> first = bus.register("trace-1");
        Flux<String> second = bus.register("trace-1");

        bus.emit("trace-1", "one");
        bus.complete("trace-1");

        List<String> firstEvents = first.collectList().block(Duration.ofSeconds(1));
        List<String> secondEvents = second.collectList().block(Duration.ofSeconds(1));
        assertThat(firstEvents).containsExactly("one");
        assertThat(secondEvents).containsExactly("one");
    }
}
