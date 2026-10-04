# DEEP 有界重新规划改造方案

**状态：** Phase 0–3 已实现并完成离线回归；运行时默认关闭，Phase 4 live A/B 待执行
**日期：** 2026-08-27
**范围：** 仅调整 DEEP 的证据收集阶段；DIRECT、RAG、NEWS、MARKET、FUNDAMENTALS 路由及 Bull/Bear/Research Manager 权限不变

## 结论

DEEP 不应整体改写成自由循环的 ReAct。当前外层流程承担了任务租约、Checkpoint、Harness、报告原子发布和 SSE 恢复等确定性契约，继续保留更稳妥。需要改的是其中最僵硬的一段：固定证据模板完成后，系统没有根据用户问题和实际观察补齐主题证据。

目标形态是：

```text
固定 DEEP 工作流
  → 问题感知的基础取证
  → 现有 Harness 恢复与复验
  → 最多一次证据缺口判断
      ├─ STOP：直接继续
      └─ FOCUSED_NEWS_SEARCH：执行一个受控只读搜索
           → 追加并去重 EvidenceEnvelope
           → 重跑现有 Harness
  → prepareHashes / 报告复用判断
  → Bull / Bear / Manager / 确定性裁决 / 报告发布
```

这保留了 ReAct 最有价值的“观察后调整”，但不引入开放式 Thought/Action 循环、第二套任务队列或让模型直接选择工具。

当前实现已贯通后台 worker 与 Redis-down inline 路径，并保留默认关闭的 `STOCKSAGE_AGENT_DEEP_REPLAN_ENABLED` 开关。离线证据只证明契约、恢复和安全边界；在配对 live A/B 证明质量收益前，不把它描述为已提升回答质量，也不默认启用。

## 为什么先不做完整 ReAct

当前主要问题不是缺少通用 Agent runtime，而是基础新闻查询没有真正使用用户关注点。`DeepEvidenceCollector` 已接收 `userQuery`，但搜索仍主要由 `ticker + earnings + latest + year` 生成。第一性原理下，应先验证一个更便宜的改动：让基础搜索使用真实问题。若这已经解决大部分主题覆盖缺口，就没有必要再支付一次规划模型调用。

完整 ReAct 还会把以下现有确定性边界重新变成开放问题：

- 模型什么时候停止、最多调用多少工具；
- 崩溃接管后是否重复规划或重复计费；
- Redis 正常后台路径与 Redis 故障 inline 路径是否一致；
- 未经批准、跨 ticker 或未来可能带写权限的工具是否会被调用；
- 新证据是否真正进入 Ledger、报告哈希和最终引用，而不是只出现在对话文本中。

因此第一版只证明一个垂直切片：**观察基础证据后，最多追加一次与问题相关的新闻搜索，并把结果纳入现有证据与可靠性契约。**

## 当前约束

必须保持：

- 外层仍由 `RoutePlanCatalog` 生成固定 DEEP 计划，Redis Stream 仍只投递一个 `taskId`。
- 动态补证仍属于 `DATA_PREFETCH`，在第一次 Bull/Bear 调用前结束；不新增任务阶段、队列、表或 Flyway。
- Bull、Bear、Research Manager 继续无工具权限，只消费经过 Harness 验证的 Evidence Snapshot。
- `EvidenceLedger.usableEvidenceIds()` 继续是可引用证据的唯一集合；自由文本工具结果不能绕过 Envelope、来源、只读审批和 ticker 校验。
- 报告、助手消息和任务终态继续原子发布；Checkpoint 清理、SSE 终态和后续 Memory capture 仍在提交后进行。
- 后台 Pipeline 与 Redis-down inline fallback 必须调用同一个补证服务，不能维护两套重规划语义。

明确不做：

- 不建设通用 ReAct/Graph 引擎，不给每个动作创建 Stream 消息。
- 不让模型输出 capability ID、ticker、结果条数、超时或任何写操作。
- 不给 Bull/Bear/Manager 绑定工具。
- 不保存模型 chain-of-thought、完整 prompt 或完整工具 payload。
- 不在第一版加入多轮、多动作、财报搜索、技术指标换窗或可配置预算平台。

## Walking skeleton

用一个固定问题先贯通全链路：

> GOOGL 的 DOJ 广告反垄断补救措施会怎样影响广告护城河和估值？

预期执行：

1. 现有基础取证仍生成 fundamentals、market、news 和 RAG 证据。
2. 基础新闻搜索使用 canonical ticker 和用户真实问题，不再使用 ticker 特例。
3. 现有 Evidence Gate 及确定性 recovery 先完成；未达到现有安全门槛时不进入动态补证。
4. Replanner 只收到用户问题、ticker 和有界 Evidence Snapshot，返回 `STOP` 或一条 `FOCUSED_NEWS_SEARCH` 建议。
5. 服务端拒绝带显式标的的模型主题，把合法主题规范化为 `GOOGL DOJ antitrust remedies ad tech` 一类的查询，先严格保存 `PLANNED + effectKey + exact action`。
6. 服务端先锁定当前 target，再把枚举动作映射到现有 `local.news.searchNews`；CapabilityGateway 校验 allowlist、READ_ONLY、deadline 和参数边界。
7. 结果被追加为带 provider、source、as-of 和 evidence ID 的 `EvidenceEnvelope`；基础新闻证据不会被替换。
8. 现有 Harness 重跑并严格保存同一 effect key 的 `REVALIDATED` 状态。
9. `prepareHashes` 在此之后执行，因此新 Ledger 证据自然进入 data snapshot/context hash；Bull/Bear 能引用新增 Evidence ID。

完成这个 skeleton 才扩展固定数据集；不要先抽象多轮循环。

## 类型化决策契约

Replanner 使用现有 FAST 模型、低温度、无 tools 的独立 ChatClient。它只做有界缺口分类与查询生成，是否需要更强模型由 A/B 结果决定。输出采用严格 JSON，第一版只有以下语义：

```json
{
  "decision": "ACT",
  "action": "FOCUSED_NEWS_SEARCH",
  "query": "DOJ antitrust remedies ad tech",
  "reasonCode": "USER_FOCUS_NOT_COVERED"
}
```

或：

```json
{
  "decision": "STOP",
  "action": null,
  "query": null,
  "reasonCode": "SUFFICIENT"
}
```

服务端规则：

- `decision` 只能是 `ACT | STOP`；`action` 只能是 `FOCUSED_NEWS_SEARCH`。
- `reasonCode` 使用有限枚举，例如 `USER_FOCUS_NOT_COVERED`、`CONFLICT_NEEDS_CURRENT_SOURCE`、`FRESHNESS_GAP`、`SUFFICIENT`、`NO_SAFE_ACTION`。
- 模型查询只能包含主题，不能显式指定任何 ticker 或公司别名；Java 校验后统一前置当前 canonical ticker。最终查询固定限制为 180 个字符；结果数复用当前上限 5，单工具超时复用当前 8 秒。
- 原始问题若显式包含第二个已知标的，fresh 与 checkpoint 恢复路径都在工具调用前交给现有 Harness 以 `TARGET_AMBIGUOUS` 阻断；当前单标的 Ledger 不静默执行比较型研究。
- 工具响应中的 `resolvedCode`、`symbol`、`ticker` 与请求标的使用同一 A/H/US canonical 规则；不一致结果由现有 `TARGET_MISMATCH` 阻断，不能被重标为请求标的。
- 模型不能指定 capability。Java 固定把该 action 映射到 `local.news.searchNews`；未知字段、非法 JSON、错误 ticker、越界参数或非只读能力一律零执行。
- CapabilityGateway 调用失败后不允许绕过策略直接调用 `NewsTools` 兜底；失败即记录停止原因并沿用已通过 Harness 的基础证据。
- 规划失败、STOP、非法计划、工具失败或无新增可用证据都不触发第二轮。只要现有 Harness 基线已通过，就记录原因并沿用当前结果；第一版把重规划定义为质量增强，不伪装成新的确定性语义覆盖门槛。
- STOP、非法计划和规划失败都严格保存为 `SKIPPED`，避免任务接管后再次消耗 planner 调用。

## 最小持久化与恢复语义

在 `AnalysisState` 增加一个 nullable 的 `EvidenceReplanState`，随现有 Checkpoint JSON 保存，不新增数据库结构。第一版最多一个动作，因此不设计通用 round/action 列表：

```text
policyVersion
status          = SKIPPED | PLANNED | REVALIDATED
action          = FOCUSED_NEWS_SEARCH | null
normalizedQuery = 服务端校验后的查询 | null
reasonCode
effectKey       = 服务端生成 | null
addedEvidenceIds
stopReason
```

`effectKey` 由 `taskId + policyVersion + action + normalizedQuery` 的 canonical 表示生成，模型不能提供。CheckpointService 增加语义明确的 `saveEvidenceReplan(...)`，内部复用现有 owner-fenced、fail-closed、立即 flush 写入；不能使用 fail-open 的普通 `saveEvidence(...)` 保存待执行计划。

崩溃和接管语义：

| 崩溃点 | 接管行为 |
|---|---|
| `PLANNED` 前 | 没有动作副作用，可重新请求一次规划 |
| `PLANNED` 后、工具前 | 复用已保存的 exact action/query/effectKey，不再请求模型 |
| 工具完成、`REVALIDATED` 前 | 允许相同只读搜索 at-least-once 重放；按 evidence ID 去重 |
| `REVALIDATED` 后 | 不再规划或调用工具，直接继续现有流程 |
| ownership loss | 丢弃未提交结果，保持 PEL，不发布、不 ACK |

系统保证的是 owner-fenced 状态写入、at-least-once 只读取证和 exactly-once 业务终态发布，不声称工具调用 exactly-once。逻辑预算只在 `REVALIDATED` 后视为消费完成。

## 代码落点

第一版实际落在下列现有边界，并复用了现有 Capability、Checkpoint、Harness 与 Trace 契约：

| 文件 | 最小改动 |
|---|---|
| `DeepEvidenceCollector.java` | 让基础新闻查询使用 `userQuery`；提供 focused news 结果的 append + evidence ID 去重，不能使用会替换整维证据的 recovery 方法 |
| `AgentConfig.java` | 增加无 tools、低温度、严格 JSON 的 replanner ChatClient；复用现有模型配置 |
| `DeepEvidenceReplanService.java` | 新增唯一的共享编排点：判断/校验、严格 checkpoint、CapabilityGateway 调用、追加证据、Harness 复验和停止；不增加接口或工厂 |
| `AnalysisState.java` | 增加一个小型 `EvidenceReplanState`；保持旧 Checkpoint JSON 可反序列化为空状态 |
| `ResearchTaskCheckpointService.java` | 增加 owner-fenced、fail-closed 的专用严格保存方法，复用现有 strict upsert |
| `DeepResearchPipeline.java` | 在 evidence PASS 后、`prepareHashes` 前调用共享服务 |
| `ToolPrefetchService.java` | Redis-down inline 路径在相同边界调用同一共享服务 |
| `application.properties` | 只增加一个默认关闭的 `stocksage.agent.deep-replan.enabled` kill switch；动作数、轮数和 timeout 不做配置平台 |
| `CapabilityInvocationObserver.java` / `evals/run_harness_live_eval.py` | 复用 `AgentStep.attributes` 区分 decision、execution 和 capability；只把带 effect key 的 execution 计为补证动作 |

不应改 `RoutePlanCatalog`、Redis Stream schema、ResearchTask stage、Bull/Bear/Manager 工具绑定或数据库迁移。

## 分阶段实施

### Phase 0：修复可验证基线（已完成）

这是独立正确性任务，不与功能代码混在一起：

1. 把 live runner、manifest 和 `agent_eval_gates.json` 的 Harness policy 从过时的 v3 对齐到生产 v4。
2. 修复当前 `HarnessGoldenSetTest` 的 active ViolationCode coverage 失败，重新取得当前策略的 exact match 与 unsafe PASS 基线。
3. 不把 Planner Eval 当 Agent Eval；它不执行工具，也不能证明补证有效。
4. 现有 `tool_action_count` 会把 Bull/Bear/Manager 也计入，不能用于成本对照。新增/修正 replan execution 分类后再冻结 A 组数据。
5. 当前另外两个已知失败——Coordinator DIRECT 断言和 `context-direct-002` fixture——继续单独记录，不能通过 fallback 隐藏，也不能宣称全仓绿色。

产物：可重复的关闭功能基线、准确的 policy metadata 和真实的 replan 工具计数。若 provider 仍不可用，后续代码只能保持 flag off，不能发布“质量提升”结论。

### Phase 1：先做问题感知基础取证（已完成）

1. 将 `buildNewsQuery(ticker)` 改为使用 canonical ticker + resolved user query，保留长度、结果数和 timeout 上限。
2. 删除 MU 等 ticker 特例；事实只由用户问题和通用规范化逻辑决定。
3. 用同一组 focused cases 对比 Phase 0 的 evidence-backed facet coverage、引用支持和 P95。

停止门：若这一小改动已经覆盖目标关注点，而一次额外 planner 没有可证明的净增益，则项目在此结束，不实现 Phase 2。

### Phase 2：实现默认关闭的单步 walking skeleton（已完成）

1. 增加严格决策 DTO/枚举和服务端 validator。
2. 增加 `EvidenceReplanState` 与严格 checkpoint；先保存 `PLANNED`，再执行动作。
3. 只接通 `FOCUSED_NEWS_SEARCH -> local.news.searchNews`。
4. 追加/去重新 EvidenceEnvelope，重跑当前 Harness，再保存 `REVALIDATED`。
5. 后台与 inline 路径都接到同一个服务；未完成双路径前 flag 始终关闭。
6. 在 `prepareHashes` 前结束补证，保持现有 debate、报告与发布代码不变。
7. 只有 fresh task 或仍处于 evidence boundary 的 checkpoint 可以进入；已有辩论或报告产物的 checkpoint 不重新打开证据阶段。

### Phase 3：可靠性与可观测性验收（离线完成，live 待执行）

Trace 只记录公开决策摘要，不记录 chain-of-thought：

- `stepKind = replan_decision | replan_execution | capability`
- `replanPolicyVersion`
- `decision` / `action`
- `effectKey`（只在已严格保存的 execution 事件出现）
- `usableEvidenceBefore/After`
- `addedEvidenceIds`
- `stopReason`
- `durationMs`

现有 DEEP token 统计并不完整，因此第一版只声称模型调用次数、工具调用次数、输入/输出字符和耗时受到硬限制；除非接入真实 provider usage，否则不宣称 token 成本未回归。

### Phase 4：配对 A/B 后再决定默认开启（待执行）

同一构建分别运行：

- A：`deep-replan.enabled=false`
- B：`deep-replan.enabled=true`

两个 arm 使用独立 eval 用户，避免 owner-scoped 报告复用污染。先固定 1 个 GOOGL walking skeleton 和少量 `NO_GAP` 控制样例，再跑现有 30 个 DEEP case；每条增加 3 个 `required_facets`，不建设第二套评测框架。

质量指标：

- `evidence-backed facet coverage`：0=缺失，1=提到但无可用证据，2=由 usable Evidence ID 支撑；进行配对盲评。
- `new-evidence utilization`：新增 usable Evidence ID 被 `evidenceItems` 或 `decisionAudit` 实际采用的比例。
- 只有新增证据被最终报告采用，才计为 Replan 增益。

默认开启门槛：

- 离线 Harness exact decision/violation/recovery match 均为 1.0，unsafe PASS=0。
- 30/30 DEEP 到达安全终态，Trace complete=1.0，非法/未批准/跨 ticker 动作执行数为 0。
- 每 case 最多 1 次 planner 调用和 1 次额外只读工具调用；`NO_GAP` 控制样例额外调用为 0。
- 每个 execution 都有严格 checkpoint 后生成的 effect key；接管不会重新请求 planner。
- B 相对 A 的配对 facet coverage 有正向统计证据，引用支持不下降，且提升 case 至少采用一条新证据。
- 复用当前延迟标准：B/A 的 P95 wall-time 比率不高于 1.2。

任一门槛失败，flag 保持关闭；结果用于判断是改 prompt/query，还是确有证据支持再增加第二类动作。

## 最小测试集

离线已覆盖前四类契约；第 5 项“新增证据被最终报告采用”仍需 live/provider 证据：

1. `DeepEvidenceCollectorTest`：真实问题进入基础查询；focused news 是 append 而非替换；新增 Envelope 有正确 target/provenance 并进入 usable IDs。
2. Replan validator 参数化测试：非法 JSON、未知 action、错误 ticker、越界查询和非只读 capability 均为零工具执行。
3. `DeepResearchPipelineTest`：`PLANNED` 后接管复用 exact plan/effectKey，不再次调用 planner；工具完成但未 `REVALIDATED` 时可重放且 Ledger 无重复。
4. `ToolPrefetchServiceSubmitTest`：后台与 inline 对同一决策得到相同停止结果；ownership loss 不 checkpoint、不发布、不 ACK。
5. 一个端到端固定夹具：新增 Evidence ID 实际出现在最终 `evidenceItems` 或 `decisionAudit`，证明“调用了工具”不是伪完成。

## 风险与后续触发条件

| 风险 | 第一版处理 | 何时扩展 |
|---|---|---|
| 模型总想继续搜索 | Java 固定最多一次，STOP 由服务端边界终止 | 单步 A/B 有明确质量增益但仍存在可分类的第二证据缺口时 |
| 只补新闻，无法回答 filing/财务细节 | 明确作为 walking skeleton 范围，不伪装成完整覆盖 | 数据显示 `FOCUSED_FILING_SEARCH` 能带来独立、可引用增益时 |
| 工具重放 | 只读 at-least-once + exact plan + evidence ID 去重 | 只有供应商明确支持幂等请求键时才增强 |
| Planner 输出注入或越权动作 | 严格 DTO、服务端枚举映射、CapabilityPolicy 二次校验 | 不放宽为 raw tool name |
| 新证据未被报告使用 | A/B 把 utilization 设为必要指标 | 若长期低利用，先改证据摘要/报告契约，不增加动作数 |
| live provider 不可用 | flag off，仅保留确定性离线证明 | 固定 30-case live gate 可运行后再讨论默认开启 |

## 决策记录

- 采用：固定外层 Workflow + 单步 Evidence Replan。
- 拒绝：把整个 DEEP 改成自由 ReAct；它会复制已有可靠性和任务运行时。
- 拒绝：第一版最多两轮/四动作；单步尚未证明价值前，这是没有证据的复杂度。
- 拒绝：只在后台 Pipeline 接入；inline fallback 行为漂移属于正确性缺陷。
- 拒绝：新增通用 action registry/interface/factory；现有 CapabilityGateway 已是执行边界。
- 保留升级点：只有 A/B 证明单步收益且剩余缺口能稳定分类时，再增加一个明确枚举动作或第二轮，而不是预先搭平台。
