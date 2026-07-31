package com.stocksage.service;

import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ChatServiceHeartbeatBackpressureTest {

    @Test
    void backgroundTaskOwnsItsTraceWhileObserverRequestOwnsOnlyItsOwnTrace() {
        assertThat(ChatService.shouldCloseObserverTraceFromSubscriber(
                "task-trace",
                "task-trace"
        )).isFalse();
        assertThat(ChatService.shouldCloseObserverTraceFromSubscriber(
                "observer-trace",
                "task-trace"
        )).isTrue();
    }

    @Test
    void slowSseSubscriberDropsOnlyHeartbeatsAndRetainsDomainEvents() {
        Flux<String> heartbeat = Flux.create(sink -> {
            for (int tick = 0; tick < 10_000; tick++) {
                sink.next("heartbeat-" + tick);
            }
            sink.complete();
        }, FluxSink.OverflowStrategy.ERROR);
        DemandControlledSubscriber subscriber = new DemandControlledSubscriber();

        ChatService.mergeSseStreamsWithLossyHeartbeat(
                        Flux.just("tool-event"),
                        heartbeat,
                        Flux.just("answer-token"))
                .subscribe(subscriber);

        assertThat(subscriber.failure()).isNull();
        assertThat(subscriber.values()).isEmpty();

        subscriber.request(Long.MAX_VALUE);

        assertThat(subscriber.failure()).isNull();
        assertThat(subscriber.values()).contains("tool-event", "answer-token");
        assertThat(subscriber.values().stream()
                .filter(value -> value.startsWith("heartbeat-"))
                .count()).isLessThan(10_000);

        subscriber.cancel();
    }

    private static final class DemandControlledSubscriber extends BaseSubscriber<String> {

        private final List<String> values = new ArrayList<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        @Override
        protected void hookOnSubscribe(Subscription subscription) {
            // Intentionally start with zero demand to model a stalled SSE client.
        }

        @Override
        protected void hookOnNext(String value) {
            values.add(value);
        }

        @Override
        protected void hookOnError(Throwable throwable) {
            failure.set(throwable);
        }

        List<String> values() {
            return values;
        }

        Throwable failure() {
            return failure.get();
        }
    }
}
