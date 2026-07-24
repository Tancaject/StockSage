package com.stocksage.agent;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Records low-cardinality routing metrics.
 */
@Component
@RequiredArgsConstructor
public class RoutingDecisionObserver {

    private final MeterRegistry meterRegistry;

    public void record(RoutingDecisionMetadata decision) {
        if (decision == null) {
            return;
        }
        String source = decision.decisionSource().name().toLowerCase();
        String route = decision.route().name().toLowerCase();
        String outcome = decision.outcome();

        Counter.builder("stocksage.routing.decisions")
                .description("Number of StockSage routing decisions")
                .tags("source", source, "route", route, "outcome", outcome)
                .register(meterRegistry)
                .increment();
        Timer.builder("stocksage.routing.duration")
                .description("StockSage routing decision latency")
                .tags("source", source, "route", route, "outcome", outcome)
                .register(meterRegistry)
                .record(Duration.ofMillis(decision.durationMs()));
    }
}
