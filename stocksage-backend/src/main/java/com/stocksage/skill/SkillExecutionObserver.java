package com.stocksage.skill;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 仓库内 Skill 的低基数指标记录器。
 *
 * <p>{@link SkillExecutionService} 在每次结束路径调用本组件，按 Skill ID 和有限结果枚举记录次数、
 * P95 与直方图。这里不记录查询词、用户 ID 或能力正文，避免指标标签基数失控和敏感信息泄露。</p>
 */
@Component
public class SkillExecutionObserver {

    /** Micrometer 指标注册入口。 */
    private final MeterRegistry meterRegistry;

    public SkillExecutionObserver(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * 记录一次 Skill 执行结果和耗时。
     *
     * @param skillId Skill ID；空值统一归为 {@code none}
     * @param outcome 有限结果分类
     * @param durationMs 整个 Skill 解析与能力执行耗时
     */
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

    /** Skill 执行的稳定结果分类，供监控面板聚合。 */
    public enum Outcome {
        SUCCESS,
        FALLBACK_SUCCESS,
        LEGACY_PATH,
        FAILED
    }
}
