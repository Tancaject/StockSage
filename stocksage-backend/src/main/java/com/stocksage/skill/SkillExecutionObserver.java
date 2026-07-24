package com.stocksage.skill;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Low-cardinality execution metrics for repository-owned Skills. */
@Component
public class SkillExecutionObserver {

    private final MeterRegistry meterRegistry;

    public SkillExecutionObserver(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void record(String skillId, Outcome outcome, long durationMs) {
        String safeSkill = skillId == null || skillId.isBlank() ? "none" : skillId;
        Counter.builder("stocksage.skill.calls")
                .tag("skill", safeSkill)
                .tag("outcome", outcome.name())
                .register(meterRegistry)
                .increment();
        Timer.builder("stocksage.skill.duration")
                .tag("skill", safeSkill)
                .tag("outcome", outcome.name())
                .publishPercentiles(0.95)
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(Duration.ofMillis(Math.max(0, durationMs)));
    }

    public enum Outcome {
        SUCCESS,
        FALLBACK_SUCCESS,
        LEGACY_PATH,
        FAILED
    }
}
