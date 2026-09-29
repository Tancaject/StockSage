package com.stocksage.service;

import com.stocksage.capability.CapabilityDescriptor;
import com.stocksage.capability.CapabilityRegistry;
import com.stocksage.mcp.McpCapabilityProvider;
import com.stocksage.mcp.McpProperties;
import com.stocksage.repository.AgentTraceRepository.LatencySample;
import com.stocksage.repository.AgentTraceRepository;
import com.stocksage.skill.SkillDefinition;
import com.stocksage.skill.SkillRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 生成供管理接口展示的 Agent 技能、能力和 MCP 运行快照。
 *
 * <p>该服务只读取注册表、Micrometer 当前进程指标和最近持久化 Trace，
 * 不修改 Agent 配置，也不会暴露密钥或原始 MCP 连接信息。</p>
 */
@Service
public class AgentAdminService {

    /** 提供当前已加载的技能定义。 */
    private final SkillRegistry skillRegistry;
    /** 提供可被计划器选择的能力描述。 */
    private final CapabilityRegistry capabilityRegistry;
    /** 提供 MCP 工具发现的即时状态。 */
    private final McpCapabilityProvider mcpProvider;
    /** 判断 MCP 开关和目标端点是否完整配置。 */
    private final McpProperties mcpProperties;
    /** 查询能力和工具的当前进程指标。 */
    private final MeterRegistry meterRegistry;
    /** 查询持久化 Agent Trace，以同一批样本计算端到端 P95。 */
    private final AgentTraceRepository agentTraceRepository;
    /** 配置中指定的默认新闻技能 ID。 */
    private final String defaultNewsSkill;

    /**
     * 注入管理快照所需的只读注册表和指标源。
     *
     * @param skillRegistry 技能注册表
     * @param capabilityRegistry 能力注册表
     * @param mcpProvider MCP 能力提供器
     * @param mcpProperties MCP 配置
     * @param meterRegistry 进程指标注册表
     * @param agentTraceRepository Agent Trace 仓储
     * @param defaultNewsSkill 默认新闻技能 ID
     */
    public AgentAdminService(
            SkillRegistry skillRegistry,
            CapabilityRegistry capabilityRegistry,
            McpCapabilityProvider mcpProvider,
            McpProperties mcpProperties,
            MeterRegistry meterRegistry,
            AgentTraceRepository agentTraceRepository,
            @Value("${stocksage.skills.defaults.news:latest-news-mcp}") String defaultNewsSkill
    ) {
        this.skillRegistry = skillRegistry;
        this.capabilityRegistry = capabilityRegistry;
        this.mcpProvider = mcpProvider;
        this.mcpProperties = mcpProperties;
        this.meterRegistry = meterRegistry;
        this.agentTraceRepository = agentTraceRepository;
        this.defaultNewsSkill = defaultNewsSkill;
    }

    /** @return 当前技能清单及默认新闻技能标记，不包含执行期私有状态。 */
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

    /**
     * 汇总能力指标和 MCP 健康状态。
     *
     * @return 当前进程的只读运行快照；无指标样本时明确标为 NO_DATA
     */
    public RuntimeSnapshot runtime() {
        Instant generatedAt = Instant.now();
        Instant processStartedAt = Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime());
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
        return new RuntimeSnapshot(
                "agent_runtime_v2",
                generatedAt.toString(),
                new RuntimeWindow(
                        "PROCESS_LIFETIME",
                        processStartedAt.toString(),
                        generatedAt.toString()
                ),
                toolExecutionView(),
                agentE2eView(processStartedAt, generatedAt),
                capabilities,
                mcp
        );
    }

    /** 汇总 Trace 关联工具调用；没有样本时不把成功率伪装成 0。 */
    private ToolExecutionView toolExecutionView() {
        List<Counter> counters = meterRegistry.find("stocksage.agent.tool.executions")
                .counters().stream().toList();
        long attempts = (long) counters.stream().mapToDouble(Counter::count).sum();
        long successes = (long) counters.stream()
                .filter(counter -> "SUCCESS".equals(counter.getId().getTag("status")))
                .mapToDouble(Counter::count)
                .sum();
        return new ToolExecutionView(
                "LIVE",
                attempts == 0 ? "NO_DATA" : "OBSERVED",
                attempts,
                successes,
                attempts == 0 ? null : (double) successes / attempts,
                "TRACE_LINKED_EXECUTIONS"
        );
    }

    /** 从同一批持久化成功 Trace 计算样本数和端到端 P95。 */
    private AgentE2eView agentE2eView(Instant processStartedAt, Instant generatedAt) {
        LocalDateTime processStart = LocalDateTime.ofInstant(processStartedAt, ZoneId.systemDefault());
        List<LatencySample> traces = agentTraceRepository.findLatencySamples(PageRequest.of(
                0,
                500,
                Sort.by(Sort.Direction.DESC, "createdAt")
        )).stream()
                .filter(trace -> "success".equalsIgnoreCase(trace.getStatus()))
                .filter(trace -> trace.getCreatedAt() != null && !trace.getCreatedAt().isBefore(processStart))
                .filter(trace -> trace.getDurationMs() != null)
                .toList();
        List<Long> durations = traces.stream()
                .map(LatencySample::getDurationMs)
                .sorted()
                .toList();
        int sampleCount = durations.size();
        Double p95 = sampleCount == 0
                ? null
                : durations.get((int) Math.ceil(sampleCount * 0.95) - 1).doubleValue();
        Instant startedAt = traces.stream()
                .map(LatencySample::getCreatedAt)
                .min(Comparator.naturalOrder())
                .map(value -> value.atZone(ZoneId.systemDefault()).toInstant())
                .orElse(processStartedAt);
        return new AgentE2eView(
                "LIVE",
                sampleCount == 0 ? "NO_DATA" : "OBSERVED",
                sampleCount,
                p95,
                "SUCCESSFUL_TRACES_IN_RECENT_500",
                new RuntimeWindow("RECENT_SUCCESSFUL_TRACES", startedAt.toString(), generatedAt.toString())
        );
    }

    /** 将单个能力描述与其 Micrometer 观测值合并为管理视图。 */
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

    /** 管理接口的技能快照根对象。 */
    public record SkillsSnapshot(String schemaVersion, String defaultNewsSkill, List<SkillView> skills) {
    }

    /** 单个技能的公开定义以及它是否为当前默认技能。 */
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

    /** 管理接口的 Agent 运行快照根对象。 */
    public record RuntimeSnapshot(
            String schemaVersion,
            String generatedAt,
            RuntimeWindow window,
            ToolExecutionView toolExecution,
            AgentE2eView agentE2e,
            List<CapabilityView> capabilities,
            McpView mcp
    ) {
    }

    /** 当前 JVM 指标窗口，避免被误读成最近 24 小时。 */
    public record RuntimeWindow(String kind, String startedAt, String endedAt) {
    }

    /** Agent Trace 关联工具的执行成功率。 */
    public record ToolExecutionView(
            String evidenceKind,
            String status,
            long attempts,
            long successes,
            Double successRate,
            String scope
    ) {
    }

    /** 成功 Agent 链路的端到端耗时快照。 */
    public record AgentE2eView(
            String evidenceKind,
            String status,
            long sampleCount,
            Double p95DurationMs,
            String scope,
            RuntimeWindow window
    ) {
    }

    /** 能力配置与进程内调用指标的合并视图。 */
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

    /** MCP 工具发现状态的脱敏视图。 */
    public record McpView(
            McpState state,
            int approvedToolCount,
            List<String> protocolVersions,
            String errorCode,
            String checkedAt
    ) {
    }

    /** 前端可稳定消费的 MCP 状态枚举。 */
    public enum McpState {
        DISABLED,
        UNCONFIGURED,
        READY,
        DEGRADED
    }
}
