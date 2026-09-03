package com.stocksage.trace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.agent.AgentStep;
import com.stocksage.model.entity.AgentTrace;
import com.stocksage.repository.AgentTraceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对话和工具执行的持久化链路写入器。
 *
 * <p>每次对话请求对应一条链路记录。步骤会以 JSON 形式追加，供前端渲染推理和工具时间线；
 * 如果配置了 Phoenix 追踪，同一批事件也会同步镜像到 Phoenix。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TraceService {

    /** AgentTrace.steps JSON 的反序列化类型，避免每次构造泛型信息。 */
    private static final TypeReference<List<AgentStep>> STEP_LIST_TYPE = new TypeReference<>() {
    };

    /** 按链路维度加锁，避免并发追加步骤时丢失数据。 */
    private final ConcurrentHashMap<String, Object> traceLocks = new ConcurrentHashMap<>();

    /** MySQL 链路实体仓库，是追踪详情和最终状态的持久化真相。 */
    private final AgentTraceRepository agentTraceRepository;
    /** 在 AgentStep 列表与 JSON 字段之间转换。 */
    private final ObjectMapper objectMapper;
    /** 可选地把同一生命周期镜像到 Phoenix/OpenTelemetry。 */
    private final PhoenixTraceService phoenixTraceService;
    /**
     * 开始一条新的智能体执行链路。
     *
     * <p>该方法会先在数据库创建 running 状态的追踪记录，再同步通知 PhoenixTraceService 开始外部 span。</p>
     */
    @Transactional
    public String startTrace(String userId, Long conversationId, String userQuery) {
        String traceId = UUID.randomUUID().toString();

        AgentTrace trace = new AgentTrace();
        trace.setTraceId(traceId);
        trace.setUserId(userId);
        trace.setConversationId(conversationId);
        trace.setUserQuery(userQuery);
        trace.setTotalSteps(0);
        trace.setTotalTokens(0);
        trace.setDurationMs(0L);
        trace.setStatus("running");
        trace.setSteps("[]");
        trace.setCreatedAt(LocalDateTime.now());

        // 数据库先落 running 记录，再启动可选 Phoenix span；外部观测不是持久化真相。
        agentTraceRepository.save(trace);
        phoenixTraceService.startTrace(traceId, userId, conversationId, userQuery);
        log.debug("Trace started, traceId={}, userId={}, conversationId={}", traceId, userId, conversationId);
        return traceId;
    }

    /**
     * 追加一个有序步骤。
     *
     * <p>多个工作线程可能上报同一条链路的步骤，因此按 traceId 串行化写入，避免覆盖更新。</p>
     */
    @Transactional
    public void addStep(String traceId, AgentStep step) {
        Object lock = traceLocks.computeIfAbsent(traceId, k -> new Object());
        synchronized (lock) {
            AgentTrace trace = findTrace(traceId);
            List<AgentStep> steps = readSteps(trace);
            step.setIndex(steps.size());
            steps.add(step);

            trace.setSteps(writeSteps(traceId, steps));
            trace.setTotalSteps(steps.size());
            // 在同一 traceId 锁内完成读-改-写，避免并行工具/辩论步骤相互覆盖。
            agentTraceRepository.save(trace);
            phoenixTraceService.addStep(traceId, step);
        }
    }

    /**
     * 结束追踪并写入最终状态、token 数和耗时。
     */
    @Transactional
    public void endTrace(String traceId, String status, int totalTokens, long durationMs) {
        endTrace(traceId, status, totalTokens, durationMs, null);
    }

    /** 在同一次终态写入中保存技术状态与可选的业务完成结果。 */
    @Transactional
    public void endTrace(String traceId,
                         String status,
                         int totalTokens,
                         long durationMs,
                         String taskOutcome) {
        AgentTrace trace = findTrace(traceId);
        long traceElapsedMs = Duration.between(trace.getCreatedAt(), LocalDateTime.now()).toMillis();
        // DEEP 的调用方耗时从 worker 启动计算；持久化 Trace 起点更早，包含排队时间。
        long endToEndDurationMs = Math.max(Math.max(0, durationMs), traceElapsedMs);
        trace.setStatus(status);
        trace.setTotalTokens(totalTokens);
        trace.setDurationMs(endToEndDurationMs);
        trace.setTotalSteps(readSteps(trace).size());
        if (taskOutcome != null && !taskOutcome.isBlank()) {
            trace.setTaskOutcome(taskOutcome.trim());
        }
        agentTraceRepository.save(trace);
        phoenixTraceService.endTrace(traceId, status, totalTokens, endToEndDurationMs);
        traceLocks.remove(traceId);
        log.debug("Trace ended, traceId={}, status={}, durationMs={}", traceId, status, endToEndDurationMs);
    }

    /**
     * 按 traceId 读取追踪详情。
     */
    @Transactional(readOnly = true)
    public AgentTrace getTrace(String traceId) {
        return findTrace(traceId);
    }

    /**
     * 读取链路并校验租户归属。
     *
     * @param traceId 链路 ID
     * @param userId 当前登录用户 ID
     * @return 属于当前用户的链路
     * @throws ResponseStatusException 链路属于其他用户时返回 403
     */
    @Transactional(readOnly = true)
    public AgentTrace getTraceForUser(String traceId, String userId) {
        AgentTrace trace = findTrace(traceId);
        if (!userId.equals(trace.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Trace does not belong to current user");
        }
        return trace;
    }

    /**
     * 读取用户最近的追踪记录列表。
     *
     * @param userId 当前租户用户 ID
     * @param limit 请求条数，最小收敛为 1
     * @return 按创建时间倒序的链路列表
     */
    @Transactional(readOnly = true)
    public List<AgentTrace> listTraces(String userId, int limit) {
        int pageSize = Math.max(1, limit);
        return agentTraceRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, pageSize));
    }

    /**
     * 查询追踪实体，不存在时抛出业务异常。
     */
    private AgentTrace findTrace(String traceId) {
        return agentTraceRepository.findById(traceId)
                .orElseThrow(() -> new IllegalArgumentException("Trace not found: " + traceId));
    }

    /**
     * 反序列化追踪步骤 JSON。
     *
     * <p>旧数据或损坏数据无法解析时返回空列表，保证追踪接口仍可响应。</p>
     */
    private List<AgentStep> readSteps(AgentTrace trace) {
        String rawSteps = trace.getSteps();
        if (rawSteps == null || rawSteps.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(rawSteps, STEP_LIST_TYPE);
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse trace steps, traceId={}", trace.getTraceId(), e);
            return new ArrayList<>();
        }
    }

    /**
     * 序列化追踪步骤列表。
     */
    private String writeSteps(String traceId, List<AgentStep> steps) {
        try {
            return objectMapper.writeValueAsString(steps);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize trace steps: " + traceId, e);
        }
    }
}
