package com.stocksage.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.agent.AgentStep;
import com.stocksage.model.dto.ChatChunk;
import com.stocksage.trace.TraceEventStore;
import com.stocksage.trace.TraceService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/** Emits the same action/observation timeline for local and MCP capability calls. */
@Slf4j
@Component
@RequiredArgsConstructor
public class CapabilityInvocationObserver {

    private final TraceEventStore traceEventStore;
    private final TraceService traceService;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public void started(CapabilityDescriptor descriptor, CapabilityInvocationContext context) {
        String providerLabel = descriptor.providerType() == CapabilityDescriptor.ProviderType.MCP
                ? "外部 MCP" : "本地";
        emit(context, "action", providerLabel + "能力: " + descriptor.id());
    }

    public void completed(CapabilityDescriptor descriptor,
                          CapabilityInvocationContext context,
                          Map<String, Object> arguments,
                          CapabilityResult result) {
        String observation = "%s via %s, %d bytes (%dms)".formatted(
                result.status(), descriptor.providerId(), result.resultBytes(), result.durationMs());
        emit(context, "observation", observation);
        recordStep(descriptor, context, arguments, observation, result.durationMs(), false);
        recordMetrics(descriptor, context, result.status().name(), result.durationMs());
    }

    public void failed(CapabilityDescriptor descriptor,
                       CapabilityInvocationContext context,
                       Map<String, Object> arguments,
                       CapabilityException error,
                       long durationMs) {
        String observation = "%s via %s after %dms: %s".formatted(
                error.reason(), descriptor.providerId(), durationMs, truncate(error.getMessage(), 300));
        emit(context, "observation", observation);
        recordStep(descriptor, context, arguments, observation, durationMs, true);
        recordMetrics(descriptor, context, error.reason().name(), durationMs);
    }

    private void emit(CapabilityInvocationContext context, String type, String content) {
        if (context == null || context.traceId() == null || context.traceId().isBlank()) {
            return;
        }
        try {
            traceEventStore.append(context.traceId(), objectMapper.writeValueAsString(ChatChunk.builder()
                    .type(type)
                    .content(content)
                    .traceId(context.traceId())
                    .conversationId(context.conversationId())
                    .build()));
        } catch (Exception error) {
            log.warn("Failed to emit capability event, traceId={}", context.traceId(), error);
        }
    }

    private void recordStep(CapabilityDescriptor descriptor,
                            CapabilityInvocationContext context,
                            Map<String, Object> arguments,
                            String observation,
                            long durationMs,
                            boolean failed) {
        if (context == null || context.traceId() == null || context.traceId().isBlank()) {
            return;
        }
        try {
            String argumentSummary = objectMapper.writeValueAsString(Map.of(
                    "argumentKeys", arguments == null ? java.util.Set.of() : arguments.keySet(),
                    "skillId", context.skillId() == null ? "" : context.skillId()
            ));
            traceService.addStep(context.traceId(), AgentStep.builder()
                    .thought((failed ? "Capability failed: " : "Called capability: ") + descriptor.id())
                    .action(descriptor.id())
                    .actionInput(truncate(argumentSummary, 1200))
                    .observation(truncate(observation, 1200))
                    .durationMs(durationMs)
                    .tokenCount(0)
                    .build());
        } catch (Exception error) {
            log.debug("Failed to persist capability trace step, traceId={}, capability={}",
                    context.traceId(), descriptor.id(), error);
        }
    }

    private void recordMetrics(CapabilityDescriptor descriptor,
                               CapabilityInvocationContext context,
                               String status,
                               long durationMs) {
        String skillId = context == null || context.skillId() == null ? "unknown" : context.skillId();
        Counter.builder("stocksage.capability.calls")
                .tag("provider", descriptor.providerId())
                .tag("capability", descriptor.id())
                .tag("skill", skillId)
                .tag("status", status)
                .register(meterRegistry)
                .increment();
        Timer.builder("stocksage.capability.duration")
                .tag("provider", descriptor.providerId())
                .tag("capability", descriptor.id())
                .tag("skill", skillId)
                .tag("status", status)
                .register(meterRegistry)
                .record(Duration.ofMillis(Math.max(0, durationMs)));
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...";
    }
}
