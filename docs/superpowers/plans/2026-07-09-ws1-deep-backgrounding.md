# WS1: DEEP 深度研究后台化 Implementation Plan

> [!NOTE]
> 2026-08-12 状态：WS1 主体实现及当前 Docker/Testcontainers/PIT 自动门禁已完成；本文保留原任务拆解。真实 UI reconnect、Redis-stop fallback 和双实例四步验收仍未完成，当前待办与证据以根目录 `TODO.md`、`progress.md` 为准。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 DEEP 深度研究从 SSE 请求线程内的同步执行，改造成基于 Redis Stream 消费组的后台任务系统：断点续跑、跨实例流式桥接、断线回放、背压配额、优雅停机、双实例可验证。

**Architecture:** 聊天请求只做 ticker 解析 + 建任务 + 入队 + 订阅；worker 从 Redis Stream 消费 taskId（DB 为事实源，租约防双跑），执行全管线（证据 → 辩论 → 综合 → 落库），每个 stage 和每轮辩论后写 checkpoint；所有 trace 事件统一 XADD 到 per-trace Redis Stream（`TraceEventStore`），持有 SSE 连接的实例经 `TraceEventRelay` 转发，断线用 Last-Event-ID 回放。Redis 不可用时整体降级为现状的同步内联执行。

**Tech Stack:** Spring Boot 3 + Spring Data Redis（StringRedisTemplate Streams API）、Reactor、Flyway (MySQL)、Testcontainers（MySQL 8 + Redis 7）、Micrometer、Vue 3 前端（fetch+ReadableStream SSE）。

**Spec:** `docs/superpowers/specs/2026-07-09-depth-hardening-design.md`（WS1 章节，含已确认决策）。

## Global Constraints

- 所有命令在 `D:\programming\StockSage\stocksage-backend` 下用 `.\mvnw.cmd` 执行（Windows）；前端在 `stocksage-frontend` 下用 `npm`。
- 现有测试必须始终全绿：`.\mvnw.cmd test` 单测、`.\mvnw.cmd verify` 含 failsafe IT（`*IT.java`）。
- 配置键统一前缀 `stocksage.research-task.*`（队列/worker）与 `stocksage.trace-events.*`（事件桥），全部带默认值，`application.properties` 只加注释化示例。
- Redis 不可用 → 降级为同步内联执行 + 进程内直推（现状行为），demo 不开天窗。
- SSE chunk 的 JSON 结构只增不改：`ChatChunk` 现有字段（type/content/section/sectionLabel/traceId/conversationId/modelTier/modelName）语义不变，新增字段只能可选。
- 辩论 prompt、报告格式、`InvestmentReport` schema 不改。
- 每个 task 结束提交一次 git commit（仓库 `D:\programming\StockSage`）。
- 注释风格：中文 Javadoc，只写"为什么"，遵循现有代码密度。
- **本机 Testcontainers 已知坑（7/04 踩过，影响 Task 3/8/10/13 的 IT）**：docker-java 找不到默认管道 `//./pipe/docker_engine`（本机 Docker Desktop 只暴露 dockerDesktopLinuxEngine）。跑 `*IT` 前先在 Docker Desktop 设置开启 "Expose daemon on tcp://localhost:2375 without TLS" 并设 `$env:DOCKER_HOST='tcp://localhost:2375'`，或改在 IDEA 里运行 IT。`mvnw test` 单测不受影响。

---

### Task 1: Checkpoint 持久层（Flyway V3 + 实体 + 仓储 + 服务）

**Files:**
- Create: `stocksage-backend/src/main/resources/db/migration/V3__research_task_checkpoints.sql`
- Create: `stocksage-backend/src/main/java/com/stocksage/model/entity/ResearchTaskCheckpoint.java`
- Create: `stocksage-backend/src/main/java/com/stocksage/repository/ResearchTaskCheckpointRepository.java`
- Create: `stocksage-backend/src/main/java/com/stocksage/service/ResearchTaskCheckpointService.java`
- Test: `stocksage-backend/src/test/java/com/stocksage/service/ResearchTaskCheckpointServiceTest.java`

**Interfaces:**
- Consumes: `AnalysisState`（`com.stocksage.model.dto`，含 `debateTurns`）、`ResearchTask.Stage`。
- Produces（后续 Task 6/7 依赖）:
  - `Optional<CheckpointState> load(Long taskId)`
  - `void saveEvidence(Long taskId, AnalysisState state)` — stage=DATA_PREFETCH 完成
  - `void saveDebateRound(Long taskId, AnalysisState state, int roundsCompleted, int plannedRounds)`
  - `void saveSynthesis(Long taskId, AnalysisState state)` — stage=REPORT_SYNTHESIS 完成
  - `void deleteForTask(Long taskId)`
  - `record CheckpointState(ResearchTask.Stage stageCompleted, int debateRoundsCompleted, int plannedRounds, AnalysisState state)`

- [x] **Step 1: 写失败的序列化/往返测试**

```java
package com.stocksage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.model.dto.AnalysisState;
import com.stocksage.model.entity.ResearchTask;
import com.stocksage.model.entity.ResearchTaskCheckpoint;
import com.stocksage.repository.ResearchTaskCheckpointRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResearchTaskCheckpointServiceTest {

    private final ResearchTaskCheckpointRepository repository = mock(ResearchTaskCheckpointRepository.class);
    private final ResearchTaskCheckpointService service =
            new ResearchTaskCheckpointService(repository, new ObjectMapper());

    @Test
    void analysisStateRoundTripsThroughCheckpointJson() {
        AnalysisState state = AnalysisState.builder()
                .query("苹果值不值得投资").primaryTicker("AAPL")
                .fundamentalsReport("F").marketReport("M").newsReport("N")
                .build();
        state.getDebateTurns().add(new AnalysisState.DebateTurn(
                1, AnalysisState.DebateTurn.Side.BULL, "bull-r1"));

        ResearchTaskCheckpoint saved = service.toEntity(42L, state,
                ResearchTask.Stage.AGENT_DEBATE, 1, 3);
        when(repository.findByTaskId(42L)).thenReturn(Optional.of(saved));

        ResearchTaskCheckpointService.CheckpointState loaded = service.load(42L).orElseThrow();
        assertThat(loaded.stageCompleted()).isEqualTo(ResearchTask.Stage.AGENT_DEBATE);
        assertThat(loaded.debateRoundsCompleted()).isEqualTo(1);
        assertThat(loaded.plannedRounds()).isEqualTo(3);
        assertThat(loaded.state().getDebateTurns()).hasSize(1);
        assertThat(loaded.state().getDebateTurns().get(0).fullText()).isEqualTo("bull-r1");
        assertThat(loaded.state().getFundamentalsReport()).isEqualTo("F");
    }

    @Test
    void corruptedPayloadReturnsEmptyInsteadOfThrowing() {
        ResearchTaskCheckpoint broken = new ResearchTaskCheckpoint();
        broken.setTaskId(7L);
        broken.setStageCompleted(ResearchTask.Stage.DATA_PREFETCH);
        broken.setPayloadJson("{not-json");
        when(repository.findByTaskId(7L)).thenReturn(Optional.of(broken));

        assertThat(service.load(7L)).isEmpty();
    }
}
```

- [x] **Step 2: 跑测试确认失败**

Run: `.\mvnw.cmd test -Dtest=ResearchTaskCheckpointServiceTest`
Expected: 编译失败（`ResearchTaskCheckpoint`/`ResearchTaskCheckpointService` 不存在）。

- [x] **Step 3: Flyway V3 迁移**

```sql
-- V3__research_task_checkpoints.sql
-- DEEP 后台化：任务断点。payload_json 存完整 AnalysisState（分析师报告 + 辩论流水，几十 KB），
-- 单独建表避免 research_tasks 主表膨胀。
CREATE TABLE IF NOT EXISTS research_task_checkpoints (
    id                       BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id                  BIGINT NOT NULL,
    stage_completed          VARCHAR(32) NOT NULL,
    debate_rounds_completed  INT NOT NULL DEFAULT 0,
    planned_rounds           INT NOT NULL DEFAULT 0,
    payload_json             MEDIUMTEXT NOT NULL,
    created_at               DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at               DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_checkpoint_task (task_id)
) ENGINE=InnoDB;
```

- [x] **Step 4: 实体 + 仓储 + 服务实现**

实体（照 `ResearchTask` 的风格，`@Data @Entity`，`@PrePersist/@PreUpdate` 维护时间戳）：

```java
@Data
@Entity
@Table(name = "research_task_checkpoints",
        uniqueConstraints = @UniqueConstraint(name = "uk_checkpoint_task", columnNames = {"task_id"}))
public class ResearchTaskCheckpoint {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "task_id", nullable = false)
    private Long taskId;
    @Enumerated(EnumType.STRING)
    @Column(name = "stage_completed", length = 32, nullable = false)
    private ResearchTask.Stage stageCompleted;
    @Column(name = "debate_rounds_completed", nullable = false)
    private Integer debateRoundsCompleted = 0;
    @Column(name = "planned_rounds", nullable = false)
    private Integer plannedRounds = 0;
    @Column(name = "payload_json", columnDefinition = "MEDIUMTEXT", nullable = false)
    private String payloadJson;
    // created_at / updated_at 字段与 @PrePersist/@PreUpdate 写法同 ResearchTask
}
```

仓储：`interface ResearchTaskCheckpointRepository extends JpaRepository<ResearchTaskCheckpoint, Long>`，方法 `Optional<ResearchTaskCheckpoint> findByTaskId(Long taskId)`、`void deleteByTaskId(Long taskId)`。

服务：

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class ResearchTaskCheckpointService {

    private final ResearchTaskCheckpointRepository repository;
    private final ObjectMapper objectMapper;

    public record CheckpointState(ResearchTask.Stage stageCompleted, int debateRoundsCompleted,
                                  int plannedRounds, AnalysisState state) {
    }

    /** 损坏的 checkpoint 当不存在处理：宁可整任务重跑，不能让恢复路径抛异常卡死任务。 */
    public Optional<CheckpointState> load(Long taskId) {
        return repository.findByTaskId(taskId).flatMap(entity -> {
            try {
                AnalysisState state = objectMapper.readValue(entity.getPayloadJson(), AnalysisState.class);
                return Optional.of(new CheckpointState(entity.getStageCompleted(),
                        safeInt(entity.getDebateRoundsCompleted()), safeInt(entity.getPlannedRounds()), state));
            } catch (Exception e) {
                log.warn("Checkpoint payload corrupted, taskId={}, treating as absent: {}", taskId, e.getMessage());
                return Optional.empty();
            }
        });
    }

    @Transactional
    public void saveEvidence(Long taskId, AnalysisState state) {
        upsert(taskId, state, ResearchTask.Stage.DATA_PREFETCH, 0, 0);
    }

    @Transactional
    public void saveDebateRound(Long taskId, AnalysisState state, int roundsCompleted, int plannedRounds) {
        upsert(taskId, state, ResearchTask.Stage.AGENT_DEBATE, roundsCompleted, plannedRounds);
    }

    @Transactional
    public void saveSynthesis(Long taskId, AnalysisState state) {
        ResearchTaskCheckpoint existing = repository.findByTaskId(taskId).orElse(null);
        int rounds = existing == null ? 0 : safeInt(existing.getDebateRoundsCompleted());
        int planned = existing == null ? 0 : safeInt(existing.getPlannedRounds());
        upsert(taskId, state, ResearchTask.Stage.REPORT_SYNTHESIS, rounds, planned);
    }

    @Transactional
    public void deleteForTask(Long taskId) {
        repository.deleteByTaskId(taskId);
    }

    ResearchTaskCheckpoint toEntity(Long taskId, AnalysisState state,
                                    ResearchTask.Stage stage, int roundsCompleted, int plannedRounds) {
        // upsert 的纯构造部分，包级可见供测试直接构造；序列化 state → payloadJson
    }

    private void upsert(Long taskId, AnalysisState state,
                        ResearchTask.Stage stage, int roundsCompleted, int plannedRounds) {
        // findByTaskId 有则更新字段，无则 save(toEntity(...))；序列化失败记 warn 不抛
        //（checkpoint 是优化不是正确性前提，失败退化为整段重跑）
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }
}
```

注意：`AnalysisState` 反序列化需要 `InvestmentReport` 也可反序列化；`DebateTurn` 是 record，Jackson 对 record + `@Builder.Default` 列表的往返由 Step 1 测试验证，若 record 反序列化报错，给 `DebateTurn` 加 `@JsonCreator` 构造。

- [x] **Step 5: 跑测试通过 + 全量单测**

Run: `.\mvnw.cmd test -Dtest=ResearchTaskCheckpointServiceTest`，然后 `.\mvnw.cmd test`
Expected: 全绿（Flyway V3 在单测不跑 MySQL，不影响）。

- [x] **Step 6: Commit** — 已完成(7/10)：T1–T7 实现随基线提交 `623ea1d` 落库（当时 .git 为空壳，无法分任务提交；相关后续修订并入 `83b14fb`）。

```bash
git add stocksage-backend/src/main/resources/db/migration/V3__research_task_checkpoints.sql stocksage-backend/src/main/java/com/stocksage/model/entity/ResearchTaskCheckpoint.java stocksage-backend/src/main/java/com/stocksage/repository/ResearchTaskCheckpointRepository.java stocksage-backend/src/main/java/com/stocksage/service/ResearchTaskCheckpointService.java stocksage-backend/src/test/java/com/stocksage/service/ResearchTaskCheckpointServiceTest.java
git commit -m "feat(ws1): research task checkpoint persistence (Flyway V3 + service)"
```

---

### Task 2: 提交级幂等键 + 用户活跃任务配额 + Stage 扩展

**Files:**
- Modify: `stocksage-backend/src/main/java/com/stocksage/model/entity/ResearchTask.java`（Stage 枚举加 `REPORT_SYNTHESIS`，加在 `AGENT_DEBATE` 与 `REPORT_PERSIST` 之间）
- Modify: `stocksage-backend/src/main/java/com/stocksage/service/ResearchTaskService.java`
- Modify: `stocksage-backend/src/main/java/com/stocksage/repository/ResearchTaskRepository.java`
- Test: `stocksage-backend/src/test/java/com/stocksage/service/ResearchTaskServiceTest.java`（追加用例）

**Interfaces:**
- Produces（Task 7/9 依赖）:
  - `String buildSubmissionKey(String userId, String ticker, String userQuery)` — 返回 `"deep-submit:" + sha256(userId|ticker|normalizedQuery)`；query 归一化 = trim + 全小写 + 连续空白折叠为单空格。
  - `int countActiveTasks(String userId)` — status IN (PENDING, RUNNING) 的任务数。
  - `String buildSubmissionPayload(String ticker, String query, String traceId, Long conversationId)` — JSON 含 `ticker/query/traceId/conversationId`（worker 恢复上下文用；快照哈希此时不存在，不再进 payload）。
- 仓储新增: `long countByUserIdAndStatusIn(String userId, Collection<ResearchTask.Status> statuses)`。

- [x] **Step 1: 追加失败测试到 ResearchTaskServiceTest**

```java
@Test
void submissionKeyIsStableAcrossQueryWhitespaceAndCase() {
    String a = service.buildSubmissionKey("u1", "AAPL", "苹果 值不值得投资？");
    String b = service.buildSubmissionKey("u1", "aapl", "  苹果   值不值得投资？ ");
    assertThat(a).isEqualTo(b).startsWith("deep-submit:");
}

@Test
void submissionKeyDiffersByUserAndTicker() {
    String base = service.buildSubmissionKey("u1", "AAPL", "q");
    assertThat(service.buildSubmissionKey("u2", "AAPL", "q")).isNotEqualTo(base);
    assertThat(service.buildSubmissionKey("u1", "MSFT", "q")).isNotEqualTo(base);
}

@Test
void submissionPayloadCarriesTraceContext() throws Exception {
    String payload = service.buildSubmissionPayload("AAPL", "q", "trace-1", 5L);
    JsonNode node = new ObjectMapper().readTree(payload);
    assertThat(node.path("traceId").asText()).isEqualTo("trace-1");
    assertThat(node.path("conversationId").asLong()).isEqualTo(5L);
}
```

- [x] **Step 2: 跑测试确认失败**

Run: `.\mvnw.cmd test -Dtest=ResearchTaskServiceTest`
Expected: 编译失败（方法不存在）。

- [x] **Step 3: 实现**

`ResearchTaskService` 新增（复用已有 `sha256`/`normalizeText`/`normalizeTicker`）：

```java
public String buildSubmissionKey(String userId, String ticker, String userQuery) {
    String normalizedQuery = normalizeText(userQuery)
            .toLowerCase(Locale.ROOT)
            .replaceAll("\\s+", " ");
    return "deep-submit:" + sha256(String.join("|",
            normalizeText(userId), normalizeTicker(ticker), normalizedQuery));
}

public int countActiveTasks(String userId) {
    return (int) repository.countByUserIdAndStatusIn(normalizeText(userId),
            List.of(ResearchTask.Status.PENDING, ResearchTask.Status.RUNNING));
}

public String buildSubmissionPayload(String ticker, String query, String traceId, Long conversationId) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("ticker", normalizeTicker(ticker));
    payload.put("query", normalizeText(query));
    payload.put("traceId", normalizeText(traceId));
    payload.put("conversationId", conversationId);
    try {
        return objectMapper.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
        throw new IllegalStateException("Unable to serialize submission payload", e);
    }
}
```

`ResearchTask.Stage` 插入 `REPORT_SYNTHESIS`（DB 存 VARCHAR，向后兼容，无迁移）。保留 `buildInvestmentReportKey`/`buildInvestmentReportPayload` 不删（旧数据与降级路径仍引用）。

- [x] **Step 4: 跑测试通过**

Run: `.\mvnw.cmd test -Dtest=ResearchTaskServiceTest`，再 `.\mvnw.cmd test`
Expected: 全绿。

- [x] **Step 5: Commit** — 已完成(7/10)：T1–T7 实现随基线提交 `623ea1d` 落库（当时 .git 为空壳，无法分任务提交；相关后续修订并入 `83b14fb`）。

```bash
git add -A stocksage-backend/src
git commit -m "feat(ws1): submission-level idempotency key, active-task quota, REPORT_SYNTHESIS stage"
```

---

### Task 3: TraceEventStore + TraceEventRelay（Redis 事件桥）

**Files:**
- Create: `stocksage-backend/src/main/java/com/stocksage/trace/TraceEventStore.java`
- Create: `stocksage-backend/src/main/java/com/stocksage/trace/TraceEventRelay.java`
- Test: `stocksage-backend/src/test/java/com/stocksage/trace/TraceEventStoreTest.java`（mock StringRedisTemplate 降级行为）
- Test: `stocksage-backend/src/test/java/com/stocksage/integration/TraceEventBridgeIT.java`（Testcontainers Redis，真实 XADD/XREAD/回放）

**Interfaces:**
- Consumes: `ToolCallEventBus.emit(traceId, chunk)`（降级直推目标）、`StringRedisTemplate.opsForStream()`。
- Produces（Task 4/7/10 依赖）:
  - `TraceEventStore.append(String traceId, String chunkJson)` — XADD 到 `stream:trace-events:{traceId}`（field `payload`），近似 `MAXLEN ~ 5000`，每次写后 `EXPIRE ttlSeconds`；Redis 异常 → 降级 `toolCallEventBus.emit(traceId, chunkJson)` 并 warn（每 trace 只 warn 一次，避免刷日志）。
  - `TraceEventStore.replayRange(String traceId, String afterEntryId)` — `XRANGE (afterEntryId, +]`，afterEntryId 为空则全量；返回 `List<StoredEvent>`。
  - `TraceEventStore.readAfter(String traceId, String afterEntryId, long blockMs)` — `XREAD BLOCK`，供 relay 轮询。
  - `TraceEventRelay.live(String traceId, String afterEntryId)` — 返回 `Flux<StoredEvent>`：先回放再持续转发（`Schedulers.boundedElastic()` 上 2s block 轮询）；收到 `type` 为 `task-final`/`error`/`stream-end` 的 chunk 或订阅取消时结束。
  - `record StoredEvent(String entryId, String chunkJson)`。
- 配置键：`stocksage.trace-events.stream-prefix`（默认 `stream:trace-events:`）、`.maxlen`（5000）、`.ttl-seconds`（3600）、`.poll-block-ms`（2000）。

- [x] **Step 1: 单测（降级路径）**

```java
class TraceEventStoreTest {

    @Test
    void appendFallsBackToLocalBusWhenRedisUnavailable() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        when(redis.opsForStream().add(any(MapRecord.class))).thenThrow(new RuntimeException("redis down"));
        ToolCallEventBus bus = mock(ToolCallEventBus.class);
        TraceEventStore store = new TraceEventStore(Optional.of(redis), bus,
                "stream:trace-events:", 5000, 3600, 2000);

        store.append("t1", "{\"type\":\"thought\"}");

        verify(bus).emit("t1", "{\"type\":\"thought\"}");
    }

    @Test
    void appendWithoutRedisTemplateGoesStraightToLocalBus() {
        ToolCallEventBus bus = mock(ToolCallEventBus.class);
        TraceEventStore store = new TraceEventStore(Optional.empty(), bus,
                "stream:trace-events:", 5000, 3600, 2000);
        store.append("t1", "{}");
        verify(bus).emit("t1", "{}");
    }
}
```

- [x] **Step 2: 跑单测失败 → 实现 TraceEventStore**

Run: `.\mvnw.cmd test -Dtest=TraceEventStoreTest` → 编译失败。

实现要点（构造注入 `Optional<StringRedisTemplate>`，风格同 `ResearchTaskLeaseService`）：

```java
public void append(String traceId, String chunkJson) {
    if (traceId == null || traceId.isBlank()) {
        return;
    }
    if (redisTemplate != null) {
        try {
            String key = streamKey(traceId);
            redisTemplate.opsForStream().add(
                    StreamRecords.mapBacked(Map.of("payload", chunkJson)).withStreamKey(key));
            redisTemplate.opsForStream().trim(key, maxlen, true);
            redisTemplate.expire(key, Duration.ofSeconds(ttlSeconds));
            return;
        } catch (Exception e) {
            warnOncePerTrace(traceId, e);
        }
    }
    toolCallEventBus.emit(traceId, chunkJson);
}
```

`replayRange`：`redisTemplate.opsForStream().range(key, Range.rightOpen(afterEntryId, "+"))`（为空用 `Range.unbounded()`），映射为 `StoredEvent(record.getId().getValue(), record.getValue().get("payload"))`。
`readAfter`：`StreamReadOptions.empty().block(Duration.ofMillis(blockMs)).count(64)` + `StreamOffset.from(key, ReadOffset.from(entryId))`。

- [x] **Step 3: 实现 TraceEventRelay**

```java
public Flux<TraceEventStore.StoredEvent> live(String traceId, String afterEntryId) {
    return Flux.<TraceEventStore.StoredEvent>create(sink -> {
        String cursor = (afterEntryId == null || afterEntryId.isBlank()) ? "0-0" : afterEntryId;
        // 回放与增量同一循环处理：XREAD from cursor 天然覆盖"先补历史再转实时"，无缝无重复。
        while (!sink.isCancelled()) {
            List<TraceEventStore.StoredEvent> batch = store.readAfter(traceId, cursor, pollBlockMs);
            for (TraceEventStore.StoredEvent event : batch) {
                cursor = event.entryId();
                sink.next(event);
                if (isTerminal(event.chunkJson())) {
                    sink.complete();
                    return;
                }
            }
        }
    }).subscribeOn(Schedulers.boundedElastic());
}
```

`isTerminal`：Jackson 解析 chunk 的 `type`，属于 `Set.of("task-final", "error", "stream-end")` 即终止（解析失败不终止）。

- [x] **Step 4: 集成测试（Testcontainers Redis）**

`TraceEventBridgeIT`（failsafe 命名 `*IT`；不起 Spring 上下文——`GenericContainer redis:7-alpine` + 手工 `LettuceConnectionFactory` + `StringRedisTemplate`，new store/relay）：

```java
@Test
void appendedEventsAreRelayedInOrderAndReplayedAfterReconnect() {
    store.append("trace-1", chunk("thought", "a"));
    store.append("trace-1", chunk("thought", "b"));

    List<StoredEvent> replay = store.replayRange("trace-1", null);
    assertThat(replay).hasSize(2);

    // 断线重连：带上第 1 条的 entryId，只应回放第 2 条及之后
    List<StoredEvent> resumed = store.replayRange("trace-1", replay.get(0).entryId());
    assertThat(resumed).hasSize(1);

    store.append("trace-1", chunk("task-final", "done"));
    List<String> streamed = relay.live("trace-1", null)
            .map(StoredEvent::chunkJson)
            .collectList().block(Duration.ofSeconds(10));
    assertThat(streamed).hasSize(3);
    assertThat(streamed.get(2)).contains("task-final");
}
```

- [x] **Step 5: 跑测试通过** — `TraceEventStoreTest` 3/3；启用 `-Pit` 后 `TraceEventBridgeIT` 1/1 通过。

Run: `.\mvnw.cmd test -Dtest=TraceEventStoreTest` 和 `.\mvnw.cmd verify -Pit -Dit.test=TraceEventBridgeIT -DfailIfNoTests=false`
Expected: 全绿。

- [x] **Step 6: Commit** — 已完成(7/10)：T1–T7 实现随基线提交 `623ea1d` 落库（当时 .git 为空壳，无法分任务提交；相关后续修订并入 `83b14fb`）。

```bash
git add stocksage-backend/src/main/java/com/stocksage/trace/TraceEventStore.java stocksage-backend/src/main/java/com/stocksage/trace/TraceEventRelay.java stocksage-backend/src/test
git commit -m "feat(ws1): per-trace Redis Stream event store with replay and live relay"
```

---

### Task 4: 事件出入口切换到桥（行为保持改造）

**Files:**
- Modify: `stocksage-backend/src/main/java/com/stocksage/tool/ChatStreamEmitter.java`（`send` 末行 `toolCallEventBus.emit` → `traceEventStore.append`）
- Modify: `stocksage-backend/src/main/java/com/stocksage/tool/ToolCallAspect.java`（先 grep `toolCallEventBus.emit` 找到所有发射点，同样改走 `traceEventStore.append`；ToolCallContext 逻辑不动）
- Modify: `stocksage-backend/src/main/java/com/stocksage/service/ChatService.java:114`（`toolCallEventBus.register(traceId)` → `traceEventRelay.live(traceId, null).map(TraceEventStore.StoredEvent::chunkJson)`）；`doFinally`（line 279-281 附近）在 `toolCallEventBus.complete(traceId)` 之前先 `chatStreamEmitter.emit(traceId, conversationId, "stream-end", "")`（`complete` 保留，清理降级路径可能注册的 sink）
- Test: 现有 `ChatServiceTenancyTest`、`ChatConversationOriginTest`、`DeepResearchPipelineTest` 保持绿

**Interfaces:**
- Consumes: Task 3 的 `TraceEventStore.append` / `TraceEventRelay.live`。
- Produces: 所有 trace 事件（进度、工具、辩论 token）单路径经 Redis（不可用时降级直推 bus）；`type=stream-end` 成为普通聊天事件流的终止标记（前端 onChunk 不识别会静默忽略，同 heartbeat）。

注意：行为保持改造——普通聊天的 chunk 内容与顺序不变（单 trace 单 stream 保序），仅多一跳 Redis 毫秒级延迟。`ToolCallEventBus` 保留作降级通道。

- [x] **Step 1: 改 ChatStreamEmitter 与 ToolCallAspect**
- [x] **Step 2: 改 ChatService 订阅端与终止信号**
- [x] **Step 3: 跑全量单测 + 集成** — 已完成(7/10 19:46)：完整 `verify -Pit` 单测 144/144 + IT 14/14 全绿（AuthFlow/CheckpointTakeover/CsrfAndAdmin/ResearchTaskEvents/ResearchTaskQueue/TenancyIsolation/TraceEventBridge），singleton containers 修复生效。
- [x] **Step 4: 手工冒烟（真实栈）** — 已完成（7/11）：隔离 8083 实例 + 本机 MySQL/data-service + WSL Redis 下，正常 MARKET 8.33s 返回 `answer + stream-end`，并新增 `stream:trace-events:f19e390d-fd93-4175-a632-ed832eb7227a`；runtime mask Redis 后 MARKET 32.73s 仍由进程内直推完成；最终 Redis 已恢复为 `active / PONG`。证据：`tmp/ws1-redis-fallback-evidence.json`。
- [x] **Step 5: Commit** — 已完成(7/10)：T1–T7 实现随基线提交 `623ea1d` 落库（当时 .git 为空壳，无法分任务提交；相关后续修订并入 `83b14fb`）。

```bash
git add -A stocksage-backend/src
git commit -m "refactor(ws1): route all trace events through Redis event store with local fallback"
```

---

### Task 5: DeepEvidenceCollector 抽取（纯重构）

**Files:**
- Create: `stocksage-backend/src/main/java/com/stocksage/service/DeepEvidenceCollector.java`
- Modify: `stocksage-backend/src/main/java/com/stocksage/service/ToolPrefetchService.java`
- Test: 现有测试保持绿（方法体原样搬家，不改逻辑——同后端一期重构 T1 的手法）

**Interfaces:**
- Produces（Task 7 依赖）:
  - `EvidenceCollection collect(String primaryTicker, String userQuery, String traceId, Long conversationId)`
  - `record EvidenceCollection(String contextMarkdown, AnalysisState state, boolean sufficientForRecommendation, boolean tickerResolved, boolean fundamentalsOk, boolean marketOk)`
- 从 `ToolPrefetchService` **原样移入**：`collectDeterministicDeepContext`、`buildFundamentalsSnapshot`、`buildMarketSnapshot`、`buildNewsSnapshot`、`DeepEvidenceResult`（并入 `EvidenceCollection`）、`appendSnapshotItem` 及 DEEP 分支专用的 helper 与所需 `@Value`（`agentPrefetchTimeoutSeconds` 等）。
- `ToolPrefetchService` 注入 `DeepEvidenceCollector`，DEEP 分支改调 `collector.collect(...)`，行为逐字节不变（trace 文本、进度文案都不动）。

- [x] **Step 1: 建新类并搬方法**（Javadoc 写"为什么单独成类：请求内联降级与后台 worker 两个调用方"）
- [x] **Step 2: ToolPrefetchService 改调用**
- [x] **Step 3: 跑全量测试** — `.\mvnw.cmd test`，Expected: 全绿，无行为变化
- [x] **Step 4: Commit** — 已完成(7/10)：T1–T7 实现随基线提交 `623ea1d` 落库（当时 .git 为空壳，无法分任务提交；相关后续修订并入 `83b14fb`）。

```bash
git add -A stocksage-backend/src
git commit -m "refactor(ws1): extract DeepEvidenceCollector for worker reuse"
```

---

### Task 6: ResearchDebateService 断点续跑支持

**Files:**
- Modify: `stocksage-backend/src/main/java/com/stocksage/agent/ResearchDebateService.java`
- Test: `stocksage-backend/src/test/java/com/stocksage/agent/ResearchDebateServiceResumeTest.java`（新建，mock Bull/Bear/Manager/Planner/TraceService/Emitter）

**Interfaces:**
- Produces（Task 7 依赖）。现有 `runDebate(traceId, conversationId, state)` 保留并委托 `runDebate(traceId, conversationId, state, 1, 0, null)`：

```java
public interface RoundCheckpointer {
    /** 每轮辩论双方定稿写回 state 后回调；plannedRounds 首轮确定后保持不变。 */
    void onRoundCompleted(AnalysisState state, int roundsCompleted, int plannedRounds);
}

public AnalysisState runDebate(String traceId, Long conversationId, AnalysisState state,
                               int startRound, int fixedPlannedRounds, RoundCheckpointer checkpointer)
```

- 语义：`startRound == 1 && fixedPlannedRounds <= 0` → 现状行为（planner 与第 1 轮并发）；`startRound > 1` → **跳过 planner**，用 `fixedPlannedRounds` 作总轮数，从 `startRound` 进循环（state 已含前几轮 debateTurns）；每轮 `applyRound` 后调 `checkpointer.onRoundCompleted`（null 安全）。

- [x] **Step 1: 写失败测试**

```java
@Test
void resumeFromRound3SkipsPlannerAndEarlierRounds() {
    // bull/bear mock：argue(state, round) 返回 Flux.just("bull-r" + round) / ("bear-r" + round)
    // planner mock：decide(...) 抛 AssertionError（不许被调）
    AnalysisState state = stateWithCompletedRounds(2); // debateTurns 已含 r1/r2 共 4 条
    List<Integer> checkpoints = new ArrayList<>();

    AnalysisState done = service.runDebate(null, null, state, 3, 3,
            (s, rounds, planned) -> checkpoints.add(rounds));

    verify(bullResearcher, never()).argue(any(), eq(1));
    verify(bullResearcher, never()).argue(any(), eq(2));
    verify(bullResearcher).argue(any(), eq(3));
    assertThat(checkpoints).contains(3);
    assertThat(done.getDebateTurns()).hasSize(6); // 2 轮旧 + 1 轮新，每轮 2 条
}

@Test
void freshRunStillPlansConcurrentlyWithRound1() {
    // startRound=1, fixedPlannedRounds=0 → planner.decide 恰好被调一次，行为同现状
}
```

- [x] **Step 2: 跑失败 → 实现**：把"第 1 轮 + planner 并发"块包进 `if (startRound == 1 && fixedPlannedRounds <= 0)`；resume 分支 `rounds = fixedPlannedRounds`，循环 `for (round = startRound; ...)`；`applyRound` 后插 `if (checkpointer != null) checkpointer.onRoundCompleted(workingState, round, rounds);`
- [x] **Step 3: 跑通过 + 全量** — `.\mvnw.cmd test -Dtest=ResearchDebateServiceResumeTest`，再 `.\mvnw.cmd test`
- [x] **Step 4: Commit** — 已完成(7/10)：T1–T7 实现随基线提交 `623ea1d` 落库（当时 .git 为空壳，无法分任务提交；相关后续修订并入 `83b14fb`）。

```bash
git add -A stocksage-backend/src
git commit -m "feat(ws1): debate resume from arbitrary round with per-round checkpoint callback"
```

---

### Task 7: Worker 全流程管线（stage 机 + 快照复用下移 + 报告落会话）

**Files:**
- Modify: `stocksage-backend/src/main/java/com/stocksage/service/DeepResearchPipeline.java`（新入口 `runFullPipeline`；现有 `runResearchDebateWithTask` 保留供同步降级复用）
- Modify: `stocksage-backend/src/main/java/com/stocksage/service/ChatService.java`（把私有 `saveMessage(conversationId, "assistant", text, traceId, selectedModel)` 的落库逻辑平移出可复用入口 `persistAssistantReport(Long conversationId, String userId, String text, String traceId)`——放 ChatService 公开方法即可，worker 调用）
- Test: `stocksage-backend/src/test/java/com/stocksage/service/DeepResearchPipelineTest.java`（扩展）

**Interfaces:**
- Consumes: Task 1 `ResearchTaskCheckpointService`、Task 2 payload（`ticker/query/traceId/conversationId`）、Task 5 `DeepEvidenceCollector.collect`、Task 6 `runDebate(..., startRound, fixedPlannedRounds, checkpointer)`、现有 `InvestmentReportVersionService.prepareHashes/findReusableReport/persistReportVersionWithMetadata`、`OfflineDemoSampleService.buildFallbackReport`、`ReportMarkdownRenderer.buildFinalAnswerBrief/buildInsufficientEvidenceReport`、`ChatStreamEmitter`。
- Produces（Task 8/9 依赖）:
  - `void runFullPipeline(ResearchTask task, ResearchTaskLeaseService.Lease lease)`

**Stage 机逻辑（实施按此写，异常处理沿用现有 tryPersistOfflineFallbackReport 语义）：**

```java
public void runFullPipeline(ResearchTask task, ResearchTaskLeaseService.Lease lease) {
    SubmissionPayload payload = parsePayload(task.getPayloadJson()); // ticker/query/traceId/conversationId
    String traceId = payload.traceId();
    Optional<CheckpointState> checkpoint = checkpointService.load(task.getId());
    try {
        researchTaskService.startAttempt(task, lease.token(),
                checkpoint.map(CheckpointState::stageCompleted).orElse(ResearchTask.Stage.DATA_PREFETCH));
        ScheduledFuture<?> heartbeat = startResearchTaskHeartbeat(task, lease);
        try {
            AnalysisState state;
            int roundsDone = 0;
            int plannedRounds = 0;

            // ---- Stage 1: EVIDENCE（有 checkpoint 则跳过）----
            if (checkpoint.isEmpty()) {
                emit(traceId, payload.conversationId(), "thought", "开始收集深度研究证据……");
                EvidenceCollection evidence = evidenceCollector.collect(
                        payload.ticker(), payload.query(), traceId, payload.conversationId());
                if (!evidence.sufficientForRecommendation()) {
                    // 证据不足：业务性完成而非系统失败——落说明消息，SUCCEEDED 无 reportVersion
                    String text = reportRenderer.buildInsufficientEvidenceReport(payload.ticker(),
                            evidence.tickerResolved(), evidence.fundamentalsOk(), evidence.marketOk());
                    finishWithText(task, lease, payload, traceId, text, null);
                    return;
                }
                state = evidence.state();
                investmentReportVersionService.prepareHashes(state);
                // ---- 快照级复用（从请求侧下移到这里）----
                Optional<InvestmentReport> reusable = investmentReportVersionService
                        .findReusableReport(task.getUserId(), payload.conversationId(), state);
                if (reusable.isPresent()) {
                    state.setInvestmentReport(reusable.get());
                    finishWithText(task, lease, payload, traceId,
                            reportRenderer.buildFinalAnswerBrief(state), null);
                    return;
                }
                checkpointService.saveEvidence(task.getId(), state);
                researchTaskService.markStageForOwner(task, lease.token(), ResearchTask.Stage.AGENT_DEBATE);
            } else {
                state = checkpoint.get().state();
                roundsDone = checkpoint.get().debateRoundsCompleted();
                plannedRounds = checkpoint.get().plannedRounds();
                emit(traceId, payload.conversationId(), "observation",
                        "从断点恢复研究任务：已完成 " + roundsDone + " 轮辩论。");
            }

            // ---- Stage 2: DEBATE + Manager（REPORT_SYNTHESIS checkpoint 已有报告则跳过）----
            if (state.getInvestmentReport() == null) {
                AnalysisState completed = researchDebateService.runDebate(
                        traceId, payload.conversationId(), state,
                        roundsDone + 1, plannedRounds,
                        (s, rounds, planned) -> checkpointService.saveDebateRound(
                                task.getId(), s, rounds, planned));
                checkpointService.saveSynthesis(task.getId(), completed);
                researchTaskService.markStageForOwner(task, lease.token(), ResearchTask.Stage.REPORT_SYNTHESIS);
                state = completed;
            }

            // ---- Stage 3: PERSIST + 会话消息 + final 事件 ----
            researchTaskService.markStageForOwner(task, lease.token(), ResearchTask.Stage.REPORT_PERSIST);
            var persisted = investmentReportVersionService.persistReportVersionWithMetadata(
                    task.getUserId(), payload.conversationId(), state,
                    ModelTier.STRONG.name(), null);
            String brief = reportRenderer.buildFinalAnswerBrief(state);
            chatService.persistAssistantReport(payload.conversationId(), task.getUserId(), brief, traceId);
            researchTaskService.markSucceededForOwner(task, lease.token(), persisted.reportVersionId());
            checkpointService.deleteForTask(task.getId());
            chatStreamEmitter.emit(traceId, payload.conversationId(), "task-final", brief);
        } finally {
            heartbeat.cancel(false);
        }
    } catch (Exception e) {
        // 沿用现有离线兜底：成功则 finishWithText(兜底文案)；兜底也失败 → markFailedForOwner + emit type=error
    } finally {
        researchTaskService.release(lease);
    }
}
```

`finishWithText` = persistAssistantReport + markSucceededForOwner + `chatStreamEmitter.emit(traceId, conversationId, "task-final", text)`。
注意 `startRound = roundsDone + 1`：恢复自 EVIDENCE checkpoint 时 roundsDone=0、plannedRounds=0 → `(1, 0)` = 全新辩论（planner 正常跑），语义自洽。
循环依赖预防：`DeepResearchPipeline` 依赖 `ChatService.persistAssistantReport`，而 `ChatService` 不依赖 pipeline（它依赖 `ToolPrefetchService`）——若实际出现 bean 环，把 `persistAssistantReport` 落到独立的 `ConversationMessageService` 中。

- [x] **Step 1: 扩展 DeepResearchPipelineTest 写失败用例**（三条：全新执行走完整管线并 emit task-final；证据不足走 SUCCEEDED-无版本路径且不辩论；有 2 轮 checkpoint 时 `runDebate` 收到 `startRound=3` 且 evidenceCollector 不被调用）
- [x] **Step 2: 跑失败 → 实现 runFullPipeline + persistAssistantReport 平移**
- [x] **Step 3: 跑通过 + 全量** — `.\mvnw.cmd test`（当前全量单测 144/144 通过）
- [x] **Step 4: Commit** — 已完成(7/10)：T1–T7 实现随基线提交 `623ea1d` 落库（当时 .git 为空壳，无法分任务提交；相关后续修订并入 `83b14fb`）。

```bash
git add -A stocksage-backend/src
git commit -m "feat(ws1): full worker pipeline with stage machine, checkpoint resume, snapshot reuse"
```

---

### Task 8: Redis Stream 队列 + Worker（消费组 / DLQ / 优雅停机）

**Files:**
- Create: `stocksage-backend/src/main/java/com/stocksage/service/ResearchTaskQueue.java`
- Create: `stocksage-backend/src/main/java/com/stocksage/service/ResearchTaskWorker.java`
- Modify: `stocksage-backend/src/main/java/com/stocksage/config/AsyncConfig.java`（新增 `researchWorkerExecutor`：ThreadPoolTaskExecutor，线程数 = worker-threads 默认 2，前缀 `research-worker`，daemon）
- Test: `stocksage-backend/src/test/java/com/stocksage/integration/ResearchTaskQueueIT.java`

**Interfaces:**
- Consumes: Task 7 `deepResearchPipeline.runFullPipeline(task, lease)`、现有 `researchTaskService.tryAcquire(task)`。
- Produces（Task 9/11 依赖）:
  - `ResearchTaskQueue.enqueue(Long taskId)` — `XADD stream:research-tasks * taskId {id}`；Redis 异常抛 `QueueUnavailableException`（RuntimeException 子类，调用方决定降级）。
  - `ResearchTaskQueue.enqueueToDlq(Long taskId, String reason)`。
  - `ResearchTaskQueue.ensureConsumerGroup()` — 启动时 `XGROUP CREATE ... MKSTREAM`，捕获 BUSYGROUP 忽略。
  - `ResearchTaskQueue.poll(String consumerName)` / `ack(record)`、`queueDepth()` / `dlqDepth()`（XLEN，供 Micrometer）。
  - `ResearchTaskWorker` — `SmartLifecycle`：start 提交 N 个消费循环到 `researchWorkerExecutor`；stop 置 running=false、等当前任务到 checkpoint 边界（最多 graceful-shutdown-wait-seconds=30）。
- 配置键：`stocksage.research-task.queue.enabled`（true）、`.queue.stream`（`stream:research-tasks`）、`.queue.group`（`research-workers`）、`.queue.dlq-stream`（`stream:research-tasks-dlq`）、`.queue.poll-block-ms`（2000）、`.queue.claim-min-idle-ms`（1800000，与租约 TTL 同量级）、`.worker-threads`（2）、`.graceful-shutdown-wait-seconds`（30）。

**消费循环（每 worker 线程）：**

```java
while (running.get()) {
    try {
        List<MapRecord<String, Object, Object>> records = queue.poll(consumerName); // XREADGROUP BLOCK COUNT 1
        for (MapRecord<String, Object, Object> record : records) {
            Long taskId = parseTaskId(record);
            ResearchTask task = taskId == null ? null : researchTaskRepository.findById(taskId).orElse(null);
            if (task == null || task.getStatus() == Status.SUCCEEDED || task.getStatus() == Status.FAILED) {
                queue.ack(record); // 幂等：重复投递/已终态直接 ACK 吞掉
                continue;
            }
            if (safeAttempts(task) >= maxAttempts) {
                researchTaskService.markFailed(task, "attempts exhausted before execution");
                queue.enqueueToDlq(taskId, "max attempts exceeded");
                queue.ack(record);
                continue;
            }
            Optional<Lease> lease = researchTaskService.tryAcquire(task);
            if (lease.isEmpty()) {
                queue.ack(record); // 另一实例已持锁在跑
                continue;
            }
            executing.set(true);
            try {
                deepResearchPipeline.runFullPipeline(task, lease.get());
                queue.ack(record);
            } catch (Exception e) {
                // 不 ACK：留在 pending，由 claim 清扫或 DB 恢复调度器重投；终态回写在 pipeline 内已处理
                log.warn("Research task execution failed, taskId={}, will be reclaimed: {}", taskId, e.getMessage());
            } finally {
                executing.set(false);
            }
        }
    } catch (Exception e) {
        log.warn("Worker poll loop error, backing off 2s: {}", e.getMessage());
        sleepQuietly(2000);
    }
}
```

**Pending 清扫**（`@Scheduled` 每分钟）：`XPENDING` 取 idle > claim-min-idle-ms 的 entry → `XCLAIM` 到本 consumer → 与消费循环相同处理（attempts 检查 → DLQ 或重执行）。Spring Data Redis 用 `opsForStream().pending(...)` + `claim(...)`（不依赖 XAUTOCLAIM，版本兼容性更稳）。

- [x] **Step 1: 写 ResearchTaskQueueIT 失败用例**（Testcontainers MySQL+Redis + Spring 上下文，照 `AuthIntegrationTestBase` 容器模式；`DeepResearchPipeline` 用 `@MockBean` 记录执行）

```java
@Test
void twoWorkersNeverExecuteSameTaskTwice() throws Exception {
    // worker-threads=2；mock pipeline：执行时 sleep(2s) 模拟耗时并按 taskId 原子累加计数
    ResearchTask task = createPendingTask("AAPL");
    queue.enqueue(task.getId());
    queue.enqueue(task.getId()); // 故意重复投递

    awaitUntil(() -> executionCount.get(task.getId()) != null, Duration.ofSeconds(10));
    Thread.sleep(3000); // 给第二条消息处理窗口
    assertThat(executionCount.get(task.getId())).isEqualTo(1); // 租约 + 终态 ACK 挡住重复
}

@Test
void failedExecutionIsReclaimedAndSentToDlqAfterMaxAttempts() {
    // mock pipeline 恒抛异常且每次 startAttempt 由真实 service 递增 attempts；
    // claim-min-idle-ms 调低到 500ms；max-attempts=2
    // 断言最终 task.status=FAILED 且 queue.dlqDepth()==1
}
```

- [x] **Step 2: 跑失败 → 实现 Queue + Worker + AsyncConfig**
- [x] **Step 3: 优雅停机用例**（同 IT：mock pipeline 阻塞在 CountDownLatch 时调 `worker.stop()`，释放 latch，断言 stop 在 wait-seconds 内返回且消息未 ACK——`XPENDING` 数为 1，重启可接管）
- [x] **Step 4: 跑通过** — 当前全量单测 144/144；启用 `-Pit` 后 `ResearchTaskQueueIT` 4/4 通过，覆盖防双跑、异常 reclaim/DLQ、优雅停机 pending 与指标。
- [x] **Step 5: Commit** — 已完成(7/10)：随 `83b14fb` 落库（后端 T8–T11 合并提交）。

```bash
git add -A stocksage-backend/src
git commit -m "feat(ws1): Redis Stream task queue, consumer-group worker, DLQ, graceful shutdown"
```

---

### Task 9: DEEP 提交路径切换（含配额与同步降级）

**Files:**
- Modify: `stocksage-backend/src/main/java/com/stocksage/service/ToolPrefetchService.java:191-260`（`RESEARCH_MANAGER` 分支重写为提交逻辑；原逻辑整块保留为私有方法 `runInlineDeepFallback(...)` 供降级）
- Modify: `stocksage-backend/src/main/java/com/stocksage/service/ChatService.java`（`PreparedToolContext` 增加 `submittedTaskId`；DEEP 提交轮次的 `doFinally` 不发 `stream-end`、不触发 `heartbeatStop`，relay 在 `task-final` 处自然终止整条 SSE；给 relay 加 `.timeout(Duration.ofMinutes(30))` + onErrorResume 发 error chunk 防僵尸连接）
- Modify: `stocksage-backend/src/main/java/com/stocksage/service/ReportMarkdownRenderer.java`（新增纯文本方法 `buildTaskAcceptedAnswer(ResearchTask task)` 与 `buildQuotaExceededAnswer(int activeCount, int maxActive)`）
- Modify: `stocksage-backend/src/main/java/com/stocksage/service/ResearchTaskService.java`（新增 `resetForResubmission(task, payloadJson)`：status=PENDING、stage=CREATED、leaseToken=null、errorMessage=null、payload 更新）
- Test: `stocksage-backend/src/test/java/com/stocksage/service/ToolPrefetchServiceSubmitTest.java`（新建）+ `ReportMarkdownRendererTest`（追加）

**Interfaces:**
- Consumes: Task 2 `buildSubmissionKey/countActiveTasks/buildSubmissionPayload`、Task 8 `ResearchTaskQueue.enqueue`（`QueueUnavailableException` → 降级）、Task 7 兼容入口 `runResearchDebateWithTask`。
- Produces: `PreparedToolContext` record 变为 `(String context, String directAnswer, Long submittedTaskId)`，`empty()` 返回 `("", "", null)`，旧两参构造保留委托（其余调用点不改）。
- 配置键：`stocksage.research-task.user-max-active`（默认 3 = 1 running + 2 queued）。

**RESEARCH_MANAGER 分支新逻辑：**

```java
case RESEARCH_MANAGER -> {
    int active = researchTaskService.countActiveTasks(userId);
    if (active >= userMaxActive) {
        directAnswer = reportRenderer.buildQuotaExceededAnswer(active, userMaxActive);
        appendContextSection(context, "Research Manager", directAnswer);
        break;
    }
    String submissionKey = researchTaskService.buildSubmissionKey(userId, primaryTicker, userQuery);
    String payload = researchTaskService.buildSubmissionPayload(primaryTicker, userQuery, traceId, conversationId);
    ResearchTaskService.TaskCreation creation = researchTaskService.createIfAbsent(
            submissionKey, userId, conversationId, primaryTicker, ResearchTask.Stage.CREATED, payload);
    ResearchTask task = creation.task();
    boolean isActive = task.getStatus() == Status.PENDING || task.getStatus() == Status.RUNNING;
    if (!creation.created() && isActive) {
        // 同 query 活跃任务已存在：不重复入队，实时旁观其事件流
        submittedTaskId = task.getId();
        directAnswer = reportRenderer.buildTaskAcceptedAnswer(task);
        emitProgress(traceId, conversationId, "observation", "匹配到进行中的深度研究任务，已切换为实时旁观。");
        break;
    }
    if (!creation.created()) {
        researchTaskService.resetForResubmission(task, payload); // 终态旧任务同 key 重问 → 重置重投
    }
    try {
        researchTaskQueue.enqueue(task.getId());
        submittedTaskId = task.getId();
        directAnswer = reportRenderer.buildTaskAcceptedAnswer(task);
        emitProgress(traceId, conversationId, "thought", "深度研究任务已受理，后台开始执行。");
    } catch (QueueUnavailableException e) {
        log.warn("Task queue unavailable, falling back to inline DEEP execution: {}", e.getMessage());
        directAnswer = runInlineDeepFallback(/* 原 191-260 行逻辑：evidence + reuse + tryAcquire + runResearchDebateWithTask */);
    }
}
```

注意：提交路径**零证据收集、零 LLM 调用**——原 evidence gate/prepareHashes/findReusableReport 全部只存在于 worker（Task 7）与 inline fallback 里。旁观模式（重复提交）下 relay 从 `Last-Event-ID=null` 回放，用户能看到任务已产生的全部过程。

- [x] **Step 1: 写失败测试**（`ToolPrefetchServiceSubmitTest` mock 全依赖，四条：正常提交→createIfAbsent(submission key)+enqueue+directAnswer 含受理文案+submittedTaskId 非空；配额满→不建任务不入队；重复提交活跃任务→不再 enqueue 但 submittedTaskId 指向旧任务；enqueue 抛 QueueUnavailableException→verify runResearchDebateWithTask 被调。`ReportMarkdownRendererTest` 两条：受理文案含 ticker 与任务号；配额文案含数字）
- [x] **Step 2: 跑失败 → 实现**
- [x] **Step 3: 跑通过 + 全量** — `.\mvnw.cmd test` + `.\mvnw.cmd verify`（当前 144/144 单测通过，非容器 verify 构建成功）
- [x] **Step 4: 手工冒烟** — 后台队列路径已由任务 #10 完成：4.39s 受理、辩论持续流入、并发 MARKET 6.59s、`task-final` 生成 1176 字报告；Redis-down 同步路径于 7/11 完成：10s 短租约下预取心跳持续续租，73.09s 完成两轮辩论与 Research Manager 汇总，以同步 `answer + stream-end` 返回 1379 字报告，响应体 18620 字符，Redis 随后恢复。证据：`tmp/ws1-ui-e2e-evidence.json`、`tmp/ws1-redis-fallback-evidence.json`、`tmp/ws1-backend-8083-heartbeat.log`。
- [x] **Step 5: Commit** — 已完成(7/10)：随 `83b14fb` 落库（后端 T8–T11 合并提交）。

```bash
git add -A stocksage-backend/src
git commit -m "feat(ws1): DEEP submits to background queue with quota, acceptance answer, inline fallback"
```

---

### Task 10: 任务状态 / 重连回放 REST 端点

**Files:**
- Create: `stocksage-backend/src/main/java/com/stocksage/controller/ResearchTaskController.java`
- Test: `stocksage-backend/src/test/java/com/stocksage/integration/ResearchTaskEventsIT.java`

**Interfaces:**
- Consumes: Task 3 `TraceEventRelay.live(traceId, afterEntryId)`、`ResearchTaskRepository`。
- Produces（Task 12 依赖）:
  - `GET /api/research-tasks/active?conversationId={id}` — 当前登录用户该会话的 PENDING/RUNNING 任务 `{taskId, status, stage, ticker, createdAt}`；无则 204。owner 校验沿用现有 controller 身份获取方式。
  - `GET /api/research-tasks/{taskId}/events`（`produces = MediaType.TEXT_EVENT_STREAM_VALUE`，返回 `Flux<ServerSentEvent<String>>`）— owner 校验（非 owner 一律 404，不泄露存在性）；taskId → payload.traceId → `relay.live(traceId, lastEventId)`；每事件 `ServerSentEvent.builder(chunkJson).id(entryId).build()`；`Last-Event-ID` 头缺省 null（全量回放）；任务已终态且 stream 过期 → 立即发一条 `task-final`（content 取任务 resultReportVersionId 对应报告 brief 或 errorMessage）并完成。

- [x] **Step 1: 写 IT 失败用例**（照 `AuthIntegrationTestBase`：非 owner 访问他人 taskId → 404；owner 访问 → 事先 `store.append` 三条事件 + task-final，断言 SSE 收到 4 条且每条带 id；带 `Last-Event-ID` = 第 2 条 id → 只收 2 条）
- [x] **Step 2: 跑失败 → 实现 controller**
- [x] **Step 3: 跑通过** — 当前全量单测 144/144；启用 `-Pit` 后 `ResearchTaskEventsIT` 4/4 通过，覆盖 owner 404、全量/游标回放、active endpoint 与终态 fallback。
- [x] **Step 4: Commit** — 已完成(7/10)：随 `83b14fb` 落库（后端 T8–T11 合并提交）。

```bash
git add -A stocksage-backend/src
git commit -m "feat(ws1): research task status and SSE replay endpoints with Last-Event-ID"
```

---

### Task 11: 恢复调度器升级 + Micrometer 指标

**Files:**
- Modify: `stocksage-backend/src/main/java/com/stocksage/service/ResearchTaskService.java`（`RecoveryResult` 扩为 `(int retried, int failed, List<Long> retriedTaskIds)`）
- Modify: `stocksage-backend/src/main/java/com/stocksage/service/ResearchTaskRecoveryScheduler.java`（对每个 retriedTaskId 调 `researchTaskQueue.enqueue(id)`，捕 QueueUnavailableException 只 warn——下轮再试；这是"Redis 丢消息"的 DB 兜底重投）
- Create: `stocksage-backend/src/main/java/com/stocksage/config/ResearchTaskMetricsConfig.java`（3 个 Gauge：`stocksage.research.queue.depth`、`stocksage.research.queue.dlq.depth`、`stocksage.research.tasks.running`；Redis 不可用 depth 返回 -1；仓储补 `long countByStatus(Status status)`）
- Test: 扩展 `ResearchTaskServiceTest` + `ResearchTaskQueueIT`

- [x] **Step 1: 失败测试**（RecoveryResult 携带 id 列表；调度器对 retried 任务逐个 enqueue——mock queue verify）
- [x] **Step 2: 实现 → 测试通过** — 目标测试 13/13、当前后端全量单测 144/144 通过
- [x] **Step 3: Commit** — 已完成(7/10)：随 `83b14fb` 落库（后端 T8–T11 合并提交）。

```bash
git add -A stocksage-backend/src
git commit -m "feat(ws1): recovery re-enqueue and queue depth metrics"
```

---

### Task 12: 前端适配（任务卡片 / task-final / 断线重连 / Workbench）

**Files:**
- Create: `stocksage-frontend/src/lib/sse-cursor.mjs` + Test: `stocksage-frontend/src/lib/sse-cursor.test.mjs`（纯函数：`latestEntryId(prev, next)` 按 Redis entry id `ms-seq` 比较取新值；`isTaskTerminal(chunk)` 判 type ∈ {task-final, error}——node --test 可测）
- Create: `stocksage-frontend/src/api/researchTasks.js`（`getActiveTask(conversationId)`；`openTaskEvents(taskId, lastEventId, onChunk, onDone, onError)` — fetch+ReadableStream 读 `/api/research-tasks/{taskId}/events`，请求头带 `Last-Event-ID`，SSE 解析复用 chat.js 的行缓冲逻辑——若为内联实现则提取共享函数到 `src/lib/`）
- Modify: `stocksage-frontend/src/api/chat.js`（onChunk 分发识别 `task-final`）
- Modify: `stocksage-frontend/src/views/ChatView.vue` + `src/components/ChatMessage.vue`
- Modify: `stocksage-frontend/src/views/WorkbenchView.vue`（任务 timeline 增加 REPORT_SYNTHESIS stage 展示与死信提示，沿用现有 timeline 字段展示方式）

**行为：**
1. 聊天流收到 `task-final` chunk：content 作为新 assistant 消息追加渲染（复用现有 markdown 渲染管线），随后流正常结束。
2. `thought`/`observation`/section chunk 在答案完成后继续到达：继续追加到当前轮推理面板（现有 section 聚合逻辑按 section 分组——验证其不依赖"答案未完成"状态，若依赖则解除）。
3. ChatView 加载会话时调 `getActiveTask(conversationId)`：有活跃任务 → `openTaskEvents(taskId, null, ...)` 重建推理卡片并续流（`sse-cursor` 按 entryId 去重续传）。
4. 断线（idle timeout / 网络错误）且本轮含活跃任务 → 用最后 entryId 重连 events 端点，而不是提示失败。

- [x] **Step 1: 写 sse-cursor 纯函数测试** — 首次 `npm test` 75 通过、1 个预期失败
- [x] **Step 2: 实现 lib + api + 视图接线**
- [x] **Step 3: `npm test` 通过 + `npm run build` 通过** — 81/81 通过；构建通过（仅既有大 chunk 警告）
- [x] **Step 4: 手工验证（必须亲眼看 UI）** — 已完成（7/11）：隔离 8082 实例 + 本地 OpenAI-compatible SSE 验收桩下，任务 #10 在 4.39s 内受理；辩论 token 实时展开；刷新后 RUNNING / DATA_PREFETCH 卡片与推理流恢复；offline 3s 后自动继续；并发 MARKET 6.59s 返回；task-final 后生成 1176 字报告；Workbench timeline 显示 COMPLETE；浏览器 console error 0。证据：`tmp/ws1-ui-e2e-evidence.json`。验收中发现并修复 `ChatView` 首次挂载未选择最新会话、导致刷新后无法调用 active-task 恢复的缺陷。
- [x] **Step 5: Commit** — 已完成(7/10)：随 `e91d011` 落库（前端 T12）。

```bash
git add -A stocksage-frontend/src
git commit -m "feat(ws1): frontend task card, reconnect with Last-Event-ID, task-final rendering"
```

---

### Task 13: 双实例验证脚本 + 断点接管 IT + 决策文档

**Files:**
- Create: `scripts/dual-instance-demo.ps1`
- Create: `stocksage-backend/src/test/java/com/stocksage/integration/CheckpointTakeoverIT.java`
- Create: `docs/superpowers/specs/2026-07-09-ws1-decisions.md`（短文档：问题 → 否掉的方案 → 取舍 → 实测数字）
- Modify: `README.md`（架构章节补后台任务系统一段 + 双实例验证入口）

- [x] **Step 1: CheckpointTakeoverIT**（单 JVM 双 worker 线程模拟双实例；定向 IT 1/1 通过）：

```java
@Test
void workerCrashMidDebateIsResumedFromCheckpointWithoutRedoingRounds() {
    // 真实 checkpointService + mock 辩论依赖：
    // Bull/Bear mock 在第 2 轮完成后（checkpoint 已写）抛 SimulatedCrashException；
    // 手动释放租约模拟 lease 过期，重新 enqueue（模拟 XCLAIM 重投）；
    // 第二次执行断言：evidenceCollector 未被调、runDebate 收到 startRound=3、
    // 最终 SUCCEEDED 且 argue(any(), eq(1)) 总调用次数 == 1（轮次没有重跑）。
}
```

- [x] **Step 2: dual-instance-demo.ps1**：脚本、四步命令与观察点已落盘；AST 解析和 `-ChecklistOnly` 通过；支持 `-MySqlPort`，Compose MySQL 宿主端口也可用 `STOCKSAGE_MYSQL_PORT` 配置，默认行为不变。
- [x] **Step 3: 全量回归** — 后端非容器回归 144/144；前端 `npm test` 81/81；`npm run build` 通过；`.\init.ps1 -Mode fast` 三阶段通过。
- [ ] **Step 4: 按脚本实测双实例四步验证** — 真实双实例四步仍未执行；接管耗时和跨实例观测不得用配置推算。
- [ ] **Step 5: 决策文档定稿** — 架构取舍已落盘；真实双实例的接管耗时与跨实例日志/DB/Redis 证据待补齐后才能完成。
- [x] **Step 6: Commit** — 已完成(7/10)：随 `df64dfa` 落库（脚本+决策文档+README）。

```bash
git add scripts/dual-instance-demo.ps1 stocksage-backend/src/test docs README.md
git commit -m "feat(ws1): dual-instance verification, checkpoint takeover IT, decision doc"
```

---

## WS1 验收清单（对照 spec）

- [x] 现有全部测试 + 新增测试绿 — 2026-07-10 初始门禁通过；当前 2026-07-31 `clean verify -Pit` 证据为 Surefire 312/312、Failsafe 17/17。
- [x] DEEP 提交后请求线程不阻塞（Task 9 冒烟验证）— 任务 #10 受理后后台运行，期间并发 MARKET 查询 6.59s 完成
- [x] 断线/刷新恢复观看（Task 12 手工验证）— 任务 #10 刷新重建成功，offline 3s 恢复后继续到 task-final
- [ ] kill worker 断点接管（Task 13 IT + 双实例实测）
- [ ] 双实例四步验证通过（Task 13 脚本）
- [x] Redis 停机降级为同步内联执行验证（Task 9 冒烟）— Redis-down MARKET 与完整 DEEP 均 200；DEEP 73.09s 完成并返回 `answer + stream-end`，Redis 已恢复 `PONG`
- [x] 决策文档落盘（Task 13；真实双实例数字明确保留待验证）

## 后续

WS1 验收通过后，写 WS2（数字化）计划——`llm_usage_record` 的埋点位置将直接标注在本 WS 落地后的 `DeepResearchPipeline`/`Coordinator` 代码上，WS3 证据冻结复用本 WS 的 checkpoint 序列化格式。
