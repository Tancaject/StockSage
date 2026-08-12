# WS1 DEEP 后台任务系统：决策与验证记录

更新时间：2026-08-12

状态：代码级方案和当前 Docker/Testcontainers/PIT 自动门禁已完成；双实例真实栈与性能数字仍待验证。本文只记录已经有证据的结论，未实测项明确标为“待验证”。

## 问题

原 DEEP 研究把证据收集、Bull/Bear 辩论、报告综合都放在聊天 SSE 请求生命周期内。一次研究持续数十秒时，请求线程、进程内事件总线和执行实例被绑定在一起：实例退出会丢失执行位置，浏览器断线后也无法从事件游标继续观看。

WS1 的目标不是引入通用消息平台，而是把这一条高耗时路径变成可恢复的后台任务，同时保留实时 token、报告先落库、Redis 故障时 demo 仍可同步完成的边界。

## 为什么选择 Redis Stream

| 方案 | 结论 | 主要理由 |
|---|---|---|
| Redis Stream + consumer group | 采用 | 项目已经依赖 Redis Session 和 Redis 租约；Stream 提供阻塞读取、pending、claim 和有序 entry id，同一套能力可覆盖任务队列与 SSE 回放。消息只传 taskId，DB 仍是事实源。 |
| Kafka | 否掉 | 当前只有一类低吞吐后台任务，没有分区扩展、长周期事件留存和多下游订阅需求；额外 broker、客户端与运维面会超过收益。 |
| RocketMQ | 否掉 | 重试/延时/事务消息能力不是 WS1 的核心需求；为本地求职演示增加独立 MQ 会扩大启动和故障面。 |
| MySQL 轮询 | 不作主队列，保留兜底 | 可以复用事实源，但高频轮询会给主库增加空查；缺少 Stream pending/claim 和阻塞消费语义。恢复调度器仍扫描 DB 并重投，用来覆盖 Redis 丢消息。 |

这个选择的代价是 Redis 成为多实例任务分发和实时事件的运行时依赖。因此队列不可用时提交路径回退同步内联；登录 Session 在多实例模式下仍把 Redis 视为硬依赖，不伪装成跨实例一致。

## 两级幂等

提交时还没有证据快照，无法计算旧的“用户 + ticker + dataSnapshotHash + contextHash”键。如果为了拿 hash 先收集证据，请求线程仍会被阻塞。因此幂等被拆成两层：

1. **提交级防重**：`userId + ticker + normalize(query)` 生成 submission key。相同用户的 PENDING/RUNNING 任务复用原 taskId 和事件 trace，不再次 `XADD`。
2. **快照级复用**：worker 收集证据并生成 `dataSnapshotHash/contextHash` 后查找可复用报告。命中即跳过辩论并把任务完成。

DB 的 idempotency 唯一键防止并发建出两行；执行前的租约防止重复投递同时运行；stage/checkpoint 让租约接管后的重复执行从已完成边界继续。三者分别处理“重复提交”“重复消费”“崩溃恢复”，不能互相替代。

## Checkpoint 粒度

采用“证据阶段 + 每轮辩论 + 综合完成”三个边界：

- 证据完成后保存完整 `AnalysisState`，恢复时不再调用 evidence collector；
- 每轮 Bull/Bear 都定稿后保存 `debateTurns`、`debateRoundsCompleted` 和 `plannedRounds`；
- Manager 综合完成后保存报告，再进入持久化阶段。

没有做 token 级 checkpoint。token 级写入会放大 MySQL 写压力，还会面对半句话和 Bull/Bear 并行 token 的一致性问题。按轮保存的最坏重算范围是一轮，且恢复时沿用第一次 planner 的总轮数，不会因重新规划改变历史决策。

checkpoint 放在独立的 `research_task_checkpoints` 表，避免几十 KB 的 AnalysisState 膨胀高频查询的任务主表。任务成功后删除 checkpoint；失败或进程退出时保留给接管者。

## 单路径事件桥

正常模式下，进度、工具观察、辩论 section token 和 `task-final` 都写入 `stream:trace-events:{traceId}`，SSE 所在实例从相同 Stream 回放并追读。即使 worker 和 SSE 在同一实例也经过 Redis，换取以下性质：

- 同实例和跨实例只有一套顺序、游标和终止语义；
- Redis entry id 可直接作为 SSE `id`，`Last-Event-ID` 后按开区间补发；
- 报告先写会话消息，再发 `task-final`，事件丢失不等于结果丢失。

代价是同实例多一次 Redis 往返。Redis 不可用时 `TraceEventStore` 降级进程内事件总线，这是明确例外；该路径能保住单实例 demo，但不承诺跨实例回放。

## 配置数字（不是性能实测）

- worker 线程数默认 `2`；单用户活跃任务上限默认 `3`（目标语义为 1 running + 2 queued）。
- 任务 Stream 为 `stream:research-tasks`，consumer group 为 `research-workers`，DLQ 为 `stream:research-tasks-dlq`。
- 生产默认租约 TTL 与 claim min idle 均为 `1,800,000 ms`；poll block 为 `2,000 ms`；优雅停机等待为 `30 s`。
- 每条 trace Stream 默认近似保留 `5,000` 个 entry，TTL 为 `3,600 s`。
- 双实例演示脚本只在子进程内把 lease TTL / claim idle / reclaim interval 缩短为 `10 s / 12 s / 2 s`，便于人工观察；这些不是生产推荐值，也没有写回应用配置。

## 验证记录

| 验证项 | 当前结果 | 证据/待办 |
|---|---|---|
| checkpoint 接管从 round 3 恢复 | **通过（1/1）** | `CheckpointTakeoverIT` 使用两个独立 worker 线程、真实序列化服务和内存仓储替身；日志确认 instance B 以 `startRound=3, totalRounds=3` 恢复并最终 SUCCEEDED。 |
| 前两轮是否重跑 | **通过** | IT 断言 round 1/2 的 Bull/Bear 各只调用 1 次、round 3 各 1 次，接管路径 evidence collector 调用 0 次，即确定跳过 2 个已完成轮次。该结论不等价于已测 token 省量。 |
| 后端单元回归 | **通过（144/144）** | 2026-07-10 加固后的 `mvnw test`：Failures=0、Errors=0；`init.ps1 -Mode fast` 后端阶段同样通过。 |
| WS1 定向容器 IT | **通过（9/9）** | `TraceEventBridgeIT` 1/1、`ResearchTaskQueueIT` 4/4、`ResearchTaskEventsIT` 4/4，使用真实 Testcontainers Redis/MySQL。 |
| 完整 `clean verify -Pit` | **通过** | 2026-07-31 当前证据：Surefire 312/312、Failsafe 17/17，无 failure、error 或 skip。该自动门禁不替代真实双实例四步验收。 |
| 双实例脚本静态检查 | **通过** | Windows PowerShell AST parser 与 `-ChecklistOnly` 通过；支持 `-MySqlPort`，Compose 端可用 `STOCKSAGE_MYSQL_PORT` 避让已占用的 3306。 |
| 双实例同任务不双跑 | **待真实栈验证** | 按 `scripts/dual-instance-demo.ps1` 第 1 步记录 taskId、attempts 和日志。 |
| kill 后接管耗时 | **待真实栈验证：不得填配置推算值** | 从强停时间到另一实例首个恢复日志/事件计时。Compose MySQL/Redis 已启动，但真实两进程四步尚未执行。 |
| 跨实例 token 延迟/丢失 | **待真实栈验证** | SSE entry id、Redis XRANGE 与两个实例日志交叉核对。 |
| 省掉的 token 数/成本 | **待 WS2 usage 埋点** | 现在只能证明跳过 2 轮，不能把字符数冒充 token。 |
| DEEP 提交延迟、吞吐、P95 | **待 WS2 Gatling** | WS1 不编造容量数字。 |

完成真实双实例验证后，只能用脚本输出、DB/Redis 观察和日志时间戳回填本表；失败结果也要原样记录。
