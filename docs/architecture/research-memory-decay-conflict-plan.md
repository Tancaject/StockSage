# Research Memory 时间衰减与冲突消解实施方案

> 状态：Implemented and enabled，唯一新链路已启用，捕获/索引/检索/注入全开；live Milvus 门禁待执行
> 最近修订：2026-08-23
> 范围：仅覆盖有来源、可撤销的跨会话研究记忆；不改 `user_profiles` 画像记忆
> 发布原则：MySQL 是真源，Milvus 只是可重建索引；Research Memory 只保留冲突门禁、时间衰减和自适应 Top-K 这一条检索链路，环境变量仅作为四个阶段的独立 kill switch

## 1. 核心决策

当前“向量候选数、最终记忆数、Prompt 容量”都被硬限制 `3 / 2400` 混在一起，无法在
数据库过滤、冲突消解和时间衰减后保持足够召回。新链路拆成三个独立阶段：

```text
用户问题 + 已识别的分析深度/时效性
  -> Milvus 初始候选 30
  -> MySQL 租户、撤销、INDEXED 真源过滤
  -> 冲突组当前赢家门禁
  -> 时间衰减重排
  -> 候选短名单 12
  -> 自适应最终记忆 4 / 6 / 8
  -> 结构化压缩后注入，最多 4800 字符
```

`30 -> 12 -> 4/6/8` 是首轮评测起点，不宣称是无需校准的行业最优值。候选数与最终
注入数必须分开：候选负责召回，最终数量和字符预算负责控制上下文噪声。

## 2. 目标参数

| 参数 | 初始值 | 规则 |
|---|---:|---|
| `candidateK` | 30 | Milvus 初始召回，不再受最终 Top-K 限制 |
| `shortlistK` | 12 | 完成真源过滤、冲突门禁和衰减排序后的短名单 |
| `briefTopK` | 4 | `AnalysisDepth.BRIEF` |
| `standardTopK` | 6 | `STANDARD/UNSPECIFIED` 默认值 |
| `deepTopK` | 8 | `DEEP` 或明确 `HISTORICAL` |
| `maxPromptChars` | 4800 | 记忆上下文总字符预算，包含边界提示 |
| `decayHalfLifeDays` | 90 | 首轮统一半衰期；上线数据证明有必要后再分领域 |
| `minEffectiveScore` | live 校准 | 未校准前不伪造阈值；低于最终阈值时允许少于目标条数 |
| 每个冲突组 | 最多 1 条 | `UNRESOLVED` 时两条相反结论都不注入 |

最终条数是上限，不是配额：不足时不补齐无关记忆。Prompt 构造按最终条数动态分配单条
字符预算，优先保留 ticker、期限、建议、数据截止时间、核心理由和来源，不允许出现
“选中了 8 条但实际只拼进去前 3 条”的隐式截断。

参数区间参考的是生产检索系统常见的“先过量召回、再缩小上下文”做法，而不是照搬单一
厂商默认值：[Anthropic Contextual Retrieval](https://www.anthropic.com/engineering/contextual-retrieval)、
[Azure AI Search hybrid query](https://learn.microsoft.com/en-us/azure/search/hybrid-search-how-to-query)、
[Amazon Bedrock Knowledge Bases](https://docs.aws.amazon.com/bedrock/latest/userguide/kb-test-config.html)。

## 3. 时间衰减

时间衰减只在检索时动态计算，不增加定时任务，也不把 `ACTIVE -> DECAYING -> STALE`
写回数据库：

```text
referenceAt = dataCutoffAt != null ? dataCutoffAt : createdAt
ageDays = max(0, now - referenceAt)
freshness = 2 ^ (-ageDays / decayHalfLifeDays)
effectiveScore = max(0, semanticScore) * freshness
```

- 不能使用 `updatedAt`，因为向量重试会刷新它，与内容新鲜度无关。
- 当前 `InvestmentReportVersion` 尚未持久化统一的底层证据 `asOf`。本切片捕获时把报告
  `generatedAt` 写入现有 `dataCutoffAt`，因此首版衰减衡量的是“结论生成后经过多久”，
  不是行情、财报等底层证据的严格业务时效；Prompt 和 Trace 均明确标为报告生成参考时点。
  后续若要宣称证据级 freshness，必须从 `EvidenceLedger` 持久化独立 evidence cutoff，不能
  用重新生成报告的时间冒充。
- 注入前必须先通过撤销和冲突门禁；高相似度或新鲜度不能让被淘汰记录复活。
- 明确的 `HISTORICAL` 查询不使用“距离现在”的衰减，但仍只读取当前可用记忆；精确
  `as-of` 历史版本重建不在本切片内，不能把它宣传成已经支持。
- 通过注入 `Clock` 计算时间，保证线上和验证逻辑一致。

## 4. 冲突模型

第一版只消解结构化的“报告投资建议”，不让 LLM 在事务内判断两段自由文本是否冲突。

### 4.1 冲突键

```text
(userId, conflictKey)
conflictKey = REPORT_RECOMMENDATION | normalizedTicker | analysisHorizon
```

`analysisHorizon` 使用严格枚举：

```text
SHORT_TERM / MEDIUM_TERM / LONG_TERM / UNSPECIFIED
```

新报告把该字段加入 `InvestmentReport`、Research Manager JSON 和 Completion Harness 契约。
旧报告不解析 `memory_text`，只从来源报告的 `recommendation` 和 `user_query` 做确定性回填；
无法可靠判断期限时写 `UNSPECIFIED`。

建议方向归一化：

```text
BUY / OVERWEIGHT       -> BULLISH
HOLD                   -> NEUTRAL
UNDERWEIGHT / SELL     -> BEARISH
```

### 4.2 真源字段

新增 Flyway `V7__research_memory_decay_conflict.sql`：

- `research_memory_entries` 增加 `analysis_horizon`、`recommendation`、`conflict_key`、
  `resolution_status`、`superseded_by_id`、`superseded_at`、`resolution_reason`。
- 新增 `research_memory_conflict_groups`，唯一键为 `(user_id, conflict_key)`，保存
  `winner_entry_id`、`resolution_status`、`blocked_before_at` 和更新时间。
- `resolution_status` 与 `vector_status` 分离；前者表示业务可用性，后者只表示 Milvus
  副本生命周期。
- 衰减分数不持久化，避免时间漂移和全表写放大。

冲突组表同时是稳定的并发锁点，解决同一用户、同一冲突组“第一条记录”并发创建时仅锁
entry 行无法串行化的问题。

### 4.3 赢家规则

按以下固定顺序选出当前赢家：

1. 已撤销、`REJECTED`、`NEEDS_RESEARCH` 直接失去资格。
2. `dataCutoffAt` 更新者优先；为空时回退 `createdAt`。
3. 数据时点相同，`APPROVED` 优先于 `DRAFT/IN_REVIEW`。
4. 同时点、同审核等级、同方向时，按 `generatedAt`、`id` 保证确定性。
5. 同时点、同审核等级但方向相反时，组设为 `UNRESOLVED`，双方都不注入；只向 Prompt
   提供一条“存在未消解历史冲突”的安全提示。

同一组的新版本无论是确认、调整强度还是反转方向，旧版本都保留审计记录并标记
`SUPERSEDED`，不会物理删除 MySQL 行。

### 4.4 撤销与重新选举

- 来源报告被负向审核：撤销该来源记忆，并重新选举之前仍有效的候选。
- 报告变为 `APPROVED` 或重新进入审核：只恢复“因负向审核撤销”的条目、重新索引并重算
  赢家；用户主动撤销的条目不得借审核状态变化复活。
- 用户显式撤销当前记忆：写入组的 `blockedBeforeAt`，防止更老结论无提示地自动复活；
  后续更新且有效的报告仍可解除阻断。
- 本切片不物理删除 `SUPERSEDED` 向量；MySQL 门禁保证它不能注入。向量物理清理和
  Outbox/DLQ 继续留在现有 H1 范围。可逆的负向审核只做 MySQL 逻辑撤销，避免迟到删除
  覆盖恢复后的同 ID 新向量；只有用户永久撤销才提交后尽力物理删除。

## 5. 事务与检索流程

### 5.1 捕获事务

固定锁顺序，避免死锁：

1. 锁来源报告行并重查租户、机器门禁和人工审核状态。
2. 创建或读取冲突组，并锁定组行。
3. 按来源唯一键执行现有幂等捕获。
4. 按固定 ID 顺序读取组内候选，执行纯 Java `ResearchMemoryConflictResolver`。
5. 在同一 MySQL 事务更新 entry 关系和组赢家。
6. 提交后再执行现有 Milvus 索引；索引失败不回滚 MySQL 真源。

审核和撤销路径必须使用相同的“报告行 -> 冲突组 -> entry”锁顺序。

### 5.2 检索流程

`ChatService` 不再只传字符串，而是把现有意图结果组装为 `ResearchMemoryQuery`，至少包含
`resolvedQuery`、ticker、`analysisDepth`、`timeSensitivity` 和 traceId；不增加新的 LLM 调用。

检索严格按以下顺序执行：

1. Milvus 按 tenant/ticker 召回 30 条。
2. MySQL 批量回查当前用户、未撤销、`INDEXED` 的真源行。
3. 只保留冲突组当前赢家；未消解组只产出安全提示。
4. 计算 `semanticScore / freshness / effectiveScore` 并排序。
5. 截成 12 条短名单，再按查询深度截成 4、6 或 8 条。
6. 在 4800 字符内结构化压缩并生成 Prompt；若阈值、冲突或预算不足，返回更少条目。

Trace 只记录安全元数据：候选数、真源过滤数、冲突淘汰数、各阶段 K、年龄、语义分、
衰减因子、有效分、最终 ID 和 Prompt 字符数，不复制完整记忆正文。

## 6. 配置与紧急停用

保留现有 `capture/index/retrieve/inject` 四个开关，并增加：

```properties
stocksage.research-memory.capture=true
stocksage.research-memory.index=true
stocksage.research-memory.retrieve=true
stocksage.research-memory.inject=true
stocksage.research-memory.candidate-k=30
stocksage.research-memory.shortlist-k=12
stocksage.research-memory.brief-top-k=4
stocksage.research-memory.top-k=6
stocksage.research-memory.deep-top-k=8
stocksage.research-memory.max-prompt-chars=4800
stocksage.research-memory.decay-half-life-days=90
stocksage.research-memory.min-effective-score=0.0
stocksage.research-memory.compensation-delay-ms=60000
stocksage.research-memory.compensation-initial-delay-ms=60000
```

不存在第二套旧排序或影子排序：启用检索时始终执行冲突门禁、时间衰减和自适应 Top-K。
四个阶段开关继续保留，出现故障时可按影响范围独立停止捕获、索引、检索或 Prompt 注入；
它们只控制阶段是否运行，不会切换到另一套排序实现。

当前按产品决定以 `min-effective-score=0.0` 启用新链路，避免在缺少校准数据时误删相关结果；
该阈值仍需根据 live golden set 校准。

## 7. 实施顺序

1. **契约与迁移**：增加期限枚举、V7、冲突组实体和仓储；旧数据做幂等回填。
2. **冲突真源**：实现确定性 Resolver，接入捕获、人工审核、来源撤销和用户显式撤销。
3. **衰减检索**：拆开 `candidateK/shortlistK/finalTopK`，实现 Ranker 和自适应预算。
4. **Prompt 与 Trace**：结构化压缩、未消解提示、安全阶段指标。
5. **运行时启用**：唯一新链路默认生效，并开启 capture/index/retrieve/inject。
6. **观测与停用**：继续记录 Trace 和 live 指标；异常时用环境变量关闭受影响的单个阶段。

主要改动位置：

- `ResearchMemoryEntry`、新 `ResearchMemoryConflictGroup` 及对应 Repository
- `ResearchMemoryService`、`ResearchMemoryVectorIndex`、`ResearchMemoryProperties`
- 新 `ResearchMemoryConflictResolver`、`ResearchMemoryRanker`、`ResearchMemoryQuery`
- `InvestmentReport`、`ResearchManager`、`DeepResearchCompletionPolicy`
- `InvestmentReportVersionService`、`ChatService`、`application.properties`
- Flyway V7 与现有 Research Memory 定向测试

## 8. 最小验收范围

实现完成后统一验证一次，不在开发过程中反复跑全套：

1. 一次后端编译。
2. 只跑现有 `ResearchMemoryServiceTest`、`ResearchMemoryEntryRepositoryTest`、
   `ResearchMemoryServiceTransactionTest` 的定向用例。
3. 一次真实 MySQL V7 迁移/冲突事务检查。
4. 一组小型 Research Memory golden set，覆盖结论冲突、相同/不同期限、撤销重选、用户显式撤销、
   tenant 隔离和低相关少返回。
5. 注入前只跑一次现有 RAG 非回退门禁。

目标门禁：

- candidate `Recall@30 >= 0.95`
- 默认 final `Recall@6 >= 0.85`，并记录 `nDCG@6`
- 跨租户、撤销记录、被替代旧结论和未消解冲突结论泄漏均 `= 0`
- Prompt 字符预算超限 `= 0`
- 暖机后新增 P95 `<= 300ms`

不运行前端、data-service、PIT、完整 Harness、live provider 或全量 RAG/LLM judge，除非实际
改动进入对应链路或定向门禁暴露问题。

本次实现验证（2026-08-23）：

- 9 个后端定向测试类共 `81/81` 通过。
- Testcontainers MySQL 8.0 的 Flyway V1→V7 迁移与回填断言 `1/1` 通过。
- `rag-eval` policy/summary 定向测试 `52/52` 通过，两份 gate JSON 语法校验通过。
- 删除多模式分支后，单链路的 Service、Ranker、Resolver 定向测试 `29/29` 通过。
- live Milvus 的 Recall、nDCG、P95、物理清理与全量 RAG 非回退门禁尚未执行；当前启用
  不代表这些生产指标已经通过，它们仍是后续验收项。

## 9. 明确不做

- 不修改长期用户画像的合并/覆盖语义。
- 不增加衰减定时任务或持久化“陈旧状态”。
- 不使用 LLM 做事务内冲突裁决。
- 不把 Milvus 当成状态真源。
- 不在本切片实现精确历史 `as-of` 重建、向量 Outbox/DLQ 或自动学习最优半衰期。
