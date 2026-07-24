package com.stocksage.service;

import com.stocksage.capability.CapabilityDescriptor;
import com.stocksage.capability.CapabilityRegistry;
import com.stocksage.mcp.McpCapabilityProvider;
import com.stocksage.mcp.McpProperties;
import com.stocksage.skill.SkillDefinition;
import com.stocksage.skill.SkillRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Builds sanitized, process-local Agent administration snapshots. */
@Service
public class AgentAdminService {

    private final SkillRegistry skillRegistry;
    private final CapabilityRegistry capabilityRegistry;
    private final McpCapabilityProvider mcpProvider;
    private final McpProperties mcpProperties;
    private final MeterRegistry meterRegistry;
    private final String defaultNewsSkill;

    public AgentAdminService(
            SkillRegistry skillRegistry,
            CapabilityRegistry capabilityRegistry,
            McpCapabilityProvider mcpProvider,
            McpProperties mcpProperties,
            MeterRegistry meterRegistry,
            @Value("${stocksage.skills.defaults.news:latest-news-mcp}") String defaultNewsSkill
    ) {
        this.skillRegistry = skillRegistry;
        this.capabilityRegistry = capabilityRegistry;
        this.mcpProvider = mcpProvider;
        this.mcpProperties = mcpProperties;
        this.meterRegistry = meterRegistry;
        this.defaultNewsSkill = defaultNewsSkill;
    }

    public SkillsSnapshot skills() {
        List<SkillView> skills = skillRegistry.list().stream()
                .map(skill -> new SkillView(
                        skill.id(),
                        skill.version(),
                        skill.displayName(),
                        skill.enabled(),
                        skill.routes(),
                        skill.executionMode(),
                        skill.minimumModelTier(),
                        skill.policy(),
                        skill.steps(),
                        skill.fallbackSkillIds(),
                        skill.id().equals(defaultNewsSkill)
                ))
                .toList();
        return new SkillsSnapshot("agent_skills_v1", defaultNewsSkill, skills);
    }

    public RuntimeSnapshot runtime() {
        McpCapabilityProvider.Status rawMcp = mcpProvider.statusSnapshot();
        McpState mcpState;
        String errorCode;
        if (!mcpProperties.isEnabled()) {
            mcpState = McpState.DISABLED;
            errorCode = "MCP_DISABLED";
        } else if (!mcpProperties.hasNewsSearchTarget()) {
            mcpState = McpState.UNCONFIGURED;
            errorCode = "MCP_TARGET_UNCONFIGURED";
        } else if (rawMcp.newsSearchAvailable()) {
            mcpState = McpState.READY;
            errorCode = "";
        } else {
            mcpState = McpState.DEGRADED;
            errorCode = "MCP_APPROVED_TOOL_UNAVAILABLE";
        }
        McpView mcp = new McpView(
                mcpState,
                rawMcp.approvedToolCount(),
                rawMcp.protocolVersions().values().stream().distinct().sorted().toList(),
                errorCode,
                rawMcp.checkedAt() == null ? "" : rawMcp.checkedAt().toString()
        );
        List<CapabilityView> capabilities = capabilityRegistry.descriptors().stream()
                .map(this::capabilityView)
                .toList();
        return new RuntimeSnapshot("agent_runtime_v1", capabilities, mcp);
    }

    private CapabilityView capabilityView(CapabilityDescriptor descriptor) {
        List<Counter> counters = meterRegistry.find("stocksage.capability.calls")
                .tag("capability", descriptor.id())
                .counters().stream().toList();
        double calls = counters.stream().mapToDouble(Counter::count).sum();
        double successful = counters.stream()
                .filter(counter -> {
                    String status = counter.getId().getTag("status");
                    return "SUCCESS".equals(status) || "TRUNCATED".equals(status);
                })
                .mapToDouble(Counter::count)
                .sum();
        List<Timer> timers = meterRegistry.find("stocksage.capability.duration")
                .tag("capability", descriptor.id())
                .timers().stream().toList();
        Double p95 = timers.stream()
                .flatMap(timer -> java.util.Arrays.stream(timer.takeSnapshot().percentileValues()))
                .filter(value -> Math.abs(value.percentile() - 0.95) < 0.001)
                .mapToDouble(value -> value.value(java.util.concurrent.TimeUnit.MILLISECONDS))
                .max()
                .stream().boxed().findFirst().orElse(null);
        return new CapabilityView(
                descriptor.id(),
                descriptor.providerType(),
                descriptor.riskLevel(),
                descriptor.enabled(),
                descriptor.timeoutMs(),
                descriptor.maxResultBytes(),
                calls == 0 ? "NO_DATA" : "OBSERVED",
                (long) calls,
                calls == 0 ? null : successful / calls,
                p95
        );
    }

    public record SkillsSnapshot(String schemaVersion, String defaultNewsSkill, List<SkillView> skills) {
    }

    public record SkillView(
            String id,
            int version,
            String displayName,
            boolean enabled,
            Set<com.stocksage.agent.PlanRoute> routes,
            SkillDefinition.ExecutionMode executionMode,
            com.stocksage.agent.ModelTier minimumModelTier,
            SkillDefinition.SkillPolicy policy,
            List<SkillDefinition.SkillStep> steps,
            List<String> fallbackSkillIds,
            boolean currentDefault
    ) {
    }

    public record RuntimeSnapshot(
            String schemaVersion,
            List<CapabilityView> capabilities,
            McpView mcp
    ) {
    }

    public record CapabilityView(
            String id,
            CapabilityDescriptor.ProviderType providerType,
            CapabilityDescriptor.RiskLevel riskLevel,
            boolean enabled,
            long timeoutMs,
            int maxResultBytes,
            String metricsStatus,
            long calls,
            Double successRate,
            Double p95DurationMs
    ) {
    }

    public record McpView(
            McpState state,
            int approvedToolCount,
            List<String> protocolVersions,
            String errorCode,
            String checkedAt
    ) {
    }

    public enum McpState {
        DISABLED,
        UNCONFIGURED,
        READY,
        DEGRADED
    }
}
