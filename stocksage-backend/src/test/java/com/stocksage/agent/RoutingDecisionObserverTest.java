package com.stocksage.agent;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RoutingDecisionObserverTest {

    @Test
    void recordsOnlyBoundedRoutingTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RoutingDecisionObserver observer = new RoutingDecisionObserver(registry);
        RoutingDecisionMetadata decision = new RoutingDecisionMetadata(
                RoutingDecisionSource.DETERMINISTIC_FALLBACK,
                "NEWS",
                PlanRoute.NEWS,
                "最新新闻查询",
                "Matched deterministic fallback signals: news-rule",
                0.0,
                List.of("news-rule"),
                2,
                "ROUTING_LLM_FAILED",
                17
        );

        observer.record(decision);

        Counter counter = registry.get("stocksage.routing.decisions")
                .tags("source", "deterministic_fallback", "route", "news", "outcome", "fallback")
                .counter();
        Timer timer = registry.get("stocksage.routing.duration")
                .tags("source", "deterministic_fallback", "route", "news", "outcome", "fallback")
                .timer();
        assertThat(counter.count()).isEqualTo(1);
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo(17);
        assertThat(counter.getId().getTags()).noneMatch(tag -> tag.getKey().contains("query"));
    }
}
