package com.stocksage.agent;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 记录低基数路由指标。
 *
 * <p>Coordinator/调用方把有界 {@link RoutingDecisionMetadata} 交给本组件；指标只按来源、有限路由和
 * success/fallback 聚合，不把用户问题或自由文本理由作为标签。</p>
 */
@Component
@RequiredArgsConstructor
public class RoutingDecisionObserver {

    /** Micrometer 指标注册入口。 */
    private final MeterRegistry meterRegistry;

    /**
     * 记录一次路由决策的计数和耗时。
     *
     * @param decision 有界路由元数据；为空时跳过
     */
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
