# 深度强化四工作流：DEEP 后台化 / 数字化 / 评测消融 / 降级矩阵（2026-07-09）

## 背景与目标

外部评审（大厂在职工程师）结论：项目广度够，但**深度证据缺失**——没有量化数字、没有极限推演、没有验证性实验；且 DEEP 深度研究至今同步跑在 SSE 请求线程里（`ToolPrefetchService` 的 `RESEARCH_MANAGER` 分支内联调 `DeepResearchPipeline.runResearchDebateWithTask`），任务状态机/租约/心跳只当防重用，没有真正的后台 worker。

**目标：四个 workstream（WS1–WS4），把"跑通了"升级为"做深了"。** 执行顺序 WS1 → WS2 → WS3 → WS4，质量门控：每个 WS 有验收清单，验收通过才进下一个。不设 deadline。

已确认的关键决策（2026-07-09 与用户逐项确认）：

1. **流式体验全保留**：辩论 token 经 Redis 桥接跨实例推送 + 断线回放，体验与现状一致；
2. **整条 DEEP 管线进 worker**：取数 → 分析师 → 检索 → 辩论 → 报告全部后台执行，聊天请求只做解析 + 建任务 + 入队 + 订阅；
3. **消融用冻结证据回放**：先持久化证据快照，消融只重跑辩论 + Manager；
4. **压测用 Gatling**（原生 SSE 支持 + JVM 生态）；
5. **消融 runner = Java 评测端点 + Python 编排打分**（沿用 rag-eval 现有模式）；
6. **指标展示 = DB + Workbench 面板**，不上 Prometheus/Grafana；Micrometer 埋点保留。

## 非目标（明确排除）

- 不引入 Kafka/RocketMQ/K8s/微服务拆分（Redis Stream 够用，且"为什么不用重型 MQ"本身是面试素材）；
- 不做数据诚实化（$0.00/假 PE，独立线另开 spec）；
- 不改 Bull/Bear 辩论 prompt 与 showcase 行为（消融只加参数化入口，不改默认行为）；
- 不重构 Coordinator 路由逻辑（只加评测）；
- 前端只做必要适配（重连、任务卡片、两个面板），不动视觉方向。

---

## WS1：DEEP 后台化（异步任务系统）

### 现状关键事实

- DEEP 在 SSE 请求线程同步执行 30–60s+；`ChatStreamEmitter` 进程内直推，断线即失去过程与结果的实时性；
- `ResearchTask`（status/stage/attempts/payload/error）+ Redis Lua 租约（acquire/release/renew，进程内兜底）+ 心跳 + `ResearchTaskRecoveryScheduler` 已就位；
- 幂等键 `buildInvestmentReportKey(userId, ticker, dataSnapshotHash, contextHash)` 在**证据预取之后**才可计算——整条管线进 worker 后提交时拿不到 snapshotHash，幂等语义必须重新分层（见 1.1）。

### 1.1 任务提交与两级幂等

聊天请求内 DEEP 路由只做：ticker 解析 → 提交级幂等检查 → `createIfAbsent` → `XADD` 入队 → SSE 推"任务受理"事件（taskId、排队位）→ 请求线程立即空闲。

**两级幂等**（替代现有单级）：

- **提交级**：key = `userId + ticker + normalize(userQuery) 哈希`；该 key 存在 PENDING/RUNNING 任务时不建新任务，直接订阅现有任务的事件流（现状"已在运行"文案升级为实时旁观）。
- **快照级**：下移到 worker 的 EVIDENCE 阶段完成后——算出 dataSnapshotHash/contextHash，先查 `findReusableReport`，命中则跳过辩论直接 SUCCEEDED 并引用已有 reportVersion（报告防腐语义不变，只是换了发生位置）。

`ResearchTask.Stage` 扩展为：`EVIDENCE_PREFETCH → AGENT_DEBATE → REPORT_SYNTHESIS → REPORT_PERSIST`（现有 `AGENT_DEBATE/REPORT_PERSIST` 保留语义，Flyway 迁移不破坏历史行）。

### 1.2 队列：Redis Stream + 消费组

- `stream:research-tasks`，消费组 `research-workers`；每实例 worker 线程数可配（`stocksage.research-task.worker-threads`，默认 2）。
- **消息体只带 taskId**；任务内容以 DB 为事实源——消息允许重复投递，重复消费由租约挡住。
- 消费循环：`XREADGROUP BLOCK` → 读 DB 任务 → `tryAcquire` 租约（复用现有 Lua）→ 执行 → `XACK`。
- 认领超时：定时 `XAUTOCLAIM`（min-idle 与租约 TTL 同量级）接管挂死消息；现有 DB `ResearchTaskRecoveryScheduler` 保留并升级为兜底——扫描超时 PENDING/RUNNING 任务重新 `XADD`（覆盖 Redis 丢消息场景，双保险）。
- 重试与死信：attempts 记在 DB（现有字段）；超 `max-attempts` → 标 FAILED + `XADD` 到 `stream:research-tasks-dlq`，Workbench 任务 timeline 可见死信原因。
- **Redis 不可用降级**：回退现状——进程内同步执行 + 进程内租约（保持"demo 不开天窗"哲学，与现有 lease fallback 一致）。

### 1.3 Checkpoint 断点续跑

- 新表 `research_task_checkpoint`（`task_id` 唯一、`stage`、`debate_rounds_completed`、`planned_rounds`、`payload_json` MEDIUMTEXT 存 AnalysisState 序列化、`updated_at`）。单独表而非主表加列：AnalysisState 含分析师报告 + 全部辩论文本，几十 KB，避免主表膨胀。
- **写入时机**：EVIDENCE 完成后；辩论**每完成一轮**后（debateTurns 追加）；REPORT_SYNTHESIS 完成后。
- **恢复**：worker 认领任务先读 checkpoint，按 stage 跳过已完成阶段；辩论恢复 = 从 payload 重建 workingState，从第 `debate_rounds_completed + 1` 轮继续——**轮次规划结果（planned_rounds）必须进 checkpoint**，避免恢复后 planner 重新决策出不同轮数。
- `ResearchDebateService` 增加"从第 N 轮开始 + 既定总轮数"入口；每个 stage 执行前查 checkpoint 已有产物则跳过（幂等 stage）。
- 崩溃恢复的直接收益可量化：辩论第 2 轮挂掉恢复，不重烧前两轮 token（WS2 的 usage 记录可证明）。

### 1.4 SSE 事件桥接（跨实例 + 断线回放）

- 每 trace 一条 Redis Stream `stream:trace-events:{traceId}`：`XADD` maxlen≈5000（approx trim）+ 每次写入刷新 TTL（1h）。
- 事件 schema 与现有 SSE chunk 对齐（`thought` / `observation` / token 分组 section+label / 阶段事件 / 报告 final 事件），前端推理面板的分组逻辑不用改。
- worker 一律写 Redis Stream（不再进程内直推）；持有 SSE 连接的实例 `XREAD BLOCK` 转发。同实例场景多一跳 Redis，延迟毫秒级，换来路径唯一（不维护两套推送逻辑）。同步降级路径（Redis 不可用）例外：保持现状直推。
- **断线回放**：SSE 事件 id = stream entry id。首次订阅发生在聊天 SSE 内（受理事件后同一连接继续收）；断线/刷新后走专用端点 `GET /api/research-tasks/{taskId}/events`（带 `Last-Event-ID`），服务端从该 id `XRANGE` 补发再转实时。
- **结果持久化先于推送**：worker 在 REPORT_PERSIST 阶段把最终报告写入会话消息，再发 final 事件——断线用户回来看会话历史也有完整报告，不依赖推送到达。
- 前端改动：任务受理卡片（状态/阶段/排队位）；EventSource 重连逻辑；Workbench timeline 补 checkpoint 阶段与死信展示。

### 1.5 背压与配额

- 单用户 **1 running + 2 queued**（可配），超限返回可见文案（"已有研究任务排队中"），不是报错。计数以 DB 为准（`count by userId + status`），不另设 Redis 计数器。
- 全局并发由 worker 线程数天然限制；暴露队列深度（XLEN）、运行中任务数、死信数为 Micrometer gauge。

### 1.6 优雅停机

- `SmartLifecycle` stop：停止认领新消息 → 当前任务跑到下一个 checkpoint 边界（辩论轮边界）后中断 → **不 XACK**（消息回 pending，由 XAUTOCLAIM 或重启后接管）→ 释放租约。
- 集成测试验证：停机后任务被另一 worker 从断点接管，轮次不重跑。

### 1.7 双实例验证

- `scripts/dual-instance-demo.ps1`：同 Redis/MySQL 起两个后端进程（不同端口）。
- 验证清单：① 同任务不双跑；② kill 正在辩论的实例 → 另一实例经 XAUTOCLAIM 从 checkpoint 恢复；③ SSE 连实例 A、worker 在实例 B，token 流畅；④ 恢复后前两轮 token 不重烧（usage 记录佐证）。
- Testcontainers（MySQL+Redis）集成测试覆盖 ①②（双 worker 线程模拟双实例）。

### WS1 验收

- 现有 84 测试全绿 + 新增单测/集成测试绿；
- DEEP 提交后聊天请求线程不再阻塞（提交后可继续正常聊天）；
- 断线/刷新可恢复观看进行中的辩论；kill worker 断点接管实测通过；
- 双实例脚本四项验证通过；
- `docs/` 短设计文档：为什么 Redis Stream 而不是 Kafka/RocketMQ/DB 轮询、两级幂等的推导、checkpoint 粒度取舍。

---

## WS2：数字化（成本/延迟工程）

### 2.1 Token/成本核算

- 新表 `llm_usage_record`：`trace_id / task_id / conversation_id / stage / agent_name / model / prompt_tokens / completion_tokens / est_cost / created_at`。
- 捕获点：所有 ChatClient 调用统一经 advisor 或薄封装拦截 `ChatResponse` 的 usage metadata；流式响应 usage 在末尾 chunk。
- 价格表走配置：`stocksage.cost.model-prices.{model}.input/.output`（元/千 token）。
- **风险（plan 阶段先 spike）**：DashScope OpenAI 兼容模式下流式 usage 的可得性（可能需要开启 include-usage 选项）；拿不到就按 token 估算器兜底并在面板标注"估算"。

### 2.2 阶段延迟统计

- 新表 `stage_timing`（`trace_id / task_id / stage / duration_ms / success / created_at`），由 pipeline 各 stage 写入（不依赖 trace 展示层的 durationMs）。
- Micrometer Timer `stocksage.stage.duration{stage}` 同步埋点。
- P50/P95 在应用层聚合（数据量小，不做 SQL 百分位）。

### 2.3 Workbench 面板

- 成本面板：单次 DEEP 总成本、分 stage 成本、按天汇总；
- 延迟面板：各 stage P50/P95、最近任务的 stage 耗时瀑布。
- 复用现有 Workbench 只读聚合接口模式（`GET /api/workbench/...`），沿用暖色 token 体系。

### 2.4 Gatling 压测

- 新独立 maven 模块 `stocksage-loadtest/`（profile 隔离，不进主构建路径）。
- **Mock LLM**：WireMock 假 OpenAI 兼容端点，流式返回固定 token 序列、速率可配——压测不烧真实 DashScope token（"如何压测 LLM 应用而不烧钱"本身是面试素材）；data-service 用离线样本模式。
- 场景：S1 并发普通聊天 SSE（登录→提问→读完整流）；S2 并发 DEEP 提交+订阅；S3 混合（80/20）。
- 产出：单实例容量数字（最大并发 SSE 连接、DEEP 吞吐/小时）、瓶颈定位（线程池/DB 连接池/Redis/内存）。

### 2.5 优化对照表

- 后台化前后：聊天路径 P95、同资源下可承载并发；
- 已有优化补量化：planner 与第 1 轮并发、轮内 Bull/Bear 并行 vs 串行的耗时对比；
- 全部进 `docs/PERFORMANCE.md` + README 摘要一段亮点数字。

### WS2 验收

- Workbench 可见每次 DEEP 的成本与分阶段耗时；
- Gatling 三场景 mock 模式一键跑通，容量数字进文档；
- README 有量化亮点（并发、P95、单次 DEEP 成本、断点恢复省的 token）。

---

## WS3：Agent 质量评测与消融

### 3.1 证据快照冻结

- 10 只 ticker（配置文件可改，默认多样化：AAPL/MSFT/NVDA/AMZN/GOOGL/META/TSLA/JPM/XOM/UNH），各跑一次 EVIDENCE 阶段，AnalysisState JSON 导出到 `rag-eval/fixtures/evidence/{ticker}.json` 并进 git（可复现）。
- 导出走 admin REST 端点（现有 admin token 鉴权模式），序列化直接复用 WS1 checkpoint 的格式。

### 3.2 辩论消融 harness

- Java 端：`POST /api/eval/debate`（admin）：入参 evidence JSON + `{rounds: 0|1|3}` → 回放辩论 + Manager → 返回 InvestmentReport + usage。`rounds=0` = Manager 直接综合三份分析师报告——需确认 ResearchManager prompt 在 bullThesis/bearThesis 为空时的行为并显式支持。
- Python 端：`rag-eval/debate_ablation.py` 编排 10 tickers × 3 configs × 2 次重复（控方差）→ 结果落 `rag-eval/results/`。

### 3.3 LLM-as-judge

- Judge 模型：qwen3.6-max（强于被评模型档位）；judge prompt 存 `rag-eval/prompts/` 并版本化。
- 绝对打分：4 维 rubric（论据覆盖 / 风险识别 / 证据支持 / 结论可操作性）各 1–5 分；
- Pairwise：0轮 vs 1轮、1轮 vs 3轮胜率，**候选位置随机化**防 position bias；
- 统计：bootstrap 置信区间；样本小（10×3×2），如实报告不确定性——结论无论是"辩论有用"还是"1 轮够了"，都如实写进文档。

### 3.4 引用忠实度评测

- 对消融报告 + 现有 50 条金标 RAG 回答：解析每个 `[n]` → 提取引用所在句（claim）+ 被引 chunk → judge LLM 三分类 `supported / partial / unsupported`。
- 指标 `faithfulness = supported / total`，纳入 rag-eval 指标集与报告。

### 3.5 路由评测

- `rag-eval/fixtures/routing_cases.jsonl`：约 80 条人工标注 query（5 条路由 × ~16，中英混合，含边界 case：概念题带 ticker、新闻+估值混合意图）。
- 分别评 LLM 路由与关键词兜底（`planDeterministically`）的准确率 + 混淆矩阵；实现沿用 `CoordinatorRegressionService` 模式，输出 JSON 给 Python 汇总。

### 3.6 eval-gate 回归门禁

- `scripts/eval-gate.ps1`（+bash 版）：跑 RAG 检索回归（已有）+ 忠实度抽样 + 路由评测 → 对照 `rag-eval/thresholds.json` → 不达标非零退出。
- **阈值以首轮实测定基线**（如检索指标不低于基线−容差、忠实度/路由不低于基线），spec 不预设拍脑袋数字；
- 本地可跑为验收标准，文档说明如何挂 GitHub Actions（不强制）。

### WS3 验收

- 消融数字表 + 结论进 `docs/AGENT_EVALUATION.md`；
- faithfulness 进 rag-eval 报告；路由准确率 + 混淆矩阵有数字；
- eval-gate 一键跑通，thresholds.json 有实测基线。

---

## WS4：降级矩阵与故障演练

### 4.1 矩阵文档

`docs/DEGRADATION_MATRIX.md`：行 = 依赖（MySQL / Redis / Milvus / DashScope / data-service / IBKR / SearchAPI），列 = 路径（登录会话 / 普通聊天 / RAG 检索 / DEEP 任务 / Workbench cockpit）。每格三项：**预期行为（设计）/ 实测行为（演练回填）/ 恢复行为（依赖恢复后是否自愈）**。

### 4.2 预计缺口修补（演练后确认清单）

- **Milvus down** → RagService 捕获向量检索异常，降级纯 `KeywordSearchService`（组件现成，补降级开关 `stocksage.rag.degrade-to-keyword-on-vector-failure` + trace observation 标注"降级检索"）；
- **data-service down** → `DataServiceClient` 统一收紧超时 + 补 resilience4j 断路器（现在只有 search/IBKR 有）；聊天回答带"实时数据暂不可用"上下文而非裸报错；
- **DashScope down** → 普通聊天快速失败 + 友好文案；DEEP 走现有重试 + 离线兜底报告（演练验证而非新建）；
- **Redis down** → 租约进程内兜底（已有）、队列降级同步执行（1.2）；spring-session 导致登录不可用：**接受为硬依赖并写明理由**（session 降级进程内存会破坏多实例一致性，不做）；
- **MySQL down** → 硬依赖，快速失败 + 健康检查，文档写明。

### 4.3 限流配额

- Redis 令牌桶（Lua，与租约同代码风格）per-user 聊天限流（默认 20 req/min，可配可关）；DEEP 配额已由 WS1 背压覆盖，此处不重复。
- 超限返回 429 + 前端可见文案；demo 模式可一键关闭。

### 4.4 演练脚本化

- `scripts/chaos/`：每依赖一个脚本（`stop-milvus.ps1` 等）：停容器 → 跑冒烟场景断言降级行为 → 恢复容器 → 断言自愈。
- 至少覆盖 4 个演练：Milvus / Redis / data-service / DashScope（后者用假 base-url 模拟不可达）。

### WS4 验收

- 矩阵文档全格填入实测结果；4 个演练脚本可重复执行；限流生效且可关。

---

## 执行顺序与依赖

- WS1 → WS2 → WS3 → WS4；两个交叉点：
  - WS2.1 usage 记录建议在 WS1 改造 ChatClient 调用链时顺手埋（同一批代码位置，避免二次翻同一片代码）；
  - WS3.1 证据冻结直接复用 WS1 checkpoint 序列化，故 WS3 严格后于 WS1。
- 每个 WS 完成时在 `docs/` 留一篇短设计文档：问题 → 否掉的方案 → 取舍 → 数字。这些文档就是面试的"想得深"物证。

## 测试策略

- **单测**：队列消费循环、checkpoint 序列化/恢复、两级幂等、令牌桶、成本计算、claim 提取；
- **集成**（Testcontainers MySQL+Redis）：双 worker 不双跑、断点接管、优雅停机、SSE 回放；
- **回归**：现有 84 测试保持绿；Coordinator/RAG 回归不动；
- **评测**：eval-gate 成为长期回归入口。

## 风险与开放问题

1. Spring AI + DashScope 兼容模式的流式 usage 可得性 → plan 首个 spike，拿不到用估算器兜底；
2. `rounds=0` 时 ResearchManager prompt 的空辩论输入行为 → 实现时显式处理并测试；
3. 压测 mock LLM 的 token 速率逼真度 → 速率可配，按真实 DashScope 观测值校准；
4. 消融样本量小（10×3×2）→ bootstrap CI + 如实报告，不过度声称显著性。
