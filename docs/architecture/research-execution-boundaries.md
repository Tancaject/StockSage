# 研究入口与模块边界

研究仍运行在同一个 Java 应用内。阶段编排由 [`DeepResearchPipeline`](../../stocksage-backend/src/main/java/com/stocksage/research/DeepResearchPipeline.java) 拥有；工具预取、聊天传输和 worker 各自适配入口，不复制研究状态机。

## 同步与后台入口

### 搜索能力执行边界

普通搜索、Skill 和 DEEP 新闻/网页搜索复用 `CapabilityGateway`。应用编排提供用户、会话、Trace、原始问题和服务器 allowlist；DEEP 另传实际运行身份。新闻时间窗按原始问题判断，不使用改写后的搜索词替代。普通与 Skill 沿用 basic，DEEP 初始取证及恢复显式请求 advanced；定向补证沿用其原有检索深度。

网关是这两项 DEEP 搜索的唯一执行器提交者，采集器不再在同一池中提交一层任务后等待网关。既有能力与运行 deadline 继续生效；授权拒绝、未知能力和容量拒绝不回退到直接工具调用，超时、提供方失败和截断形成不同的不可引用结果。DEEP 证据账本保留原 `searchNews/webSearch` 标识，避免这次执行入口迁移改变引用 ID；注册能力 ID 用于策略和调用观察。股票新闻接口及财务、行情取证仍保留各自入口，不据此声称所有工具都已完成迁移。

普通取证中的搜索结果由 [`SearchResultIngestionService`](../../stocksage-backend/src/main/java/com/stocksage/knowledge/SearchResultIngestionService.java) 接收。知识模块拥有结果解析、来源键、元数据、保留时间和后台提交；`ToolPrefetchService` 只传递工具名、原始结果与查询。该入口沿用聊天搜索入库开关与独立后台执行器，入库失败不会替代本轮已经取得的证据。

### 提交、运行与重试身份

一次主动研究对应一条 `ResearchTask`，其 `id` 同时是运行身份。聊天与工作台通过 `submissionId` 区分主动提交：同一次网络重发沿用该值，重新生成或再次研究使用新值。前端 `streamChat` 为没有此值的请求对象生成 UUID；重用该对象会保留身份，新对象得到新身份。后端将其与登录用户绑定生成提交键，不能跨用户复用。

已有提交在 `ChatService` 创建会话、写消息或识别意图之前回读。观察入口 [`ResearchTaskObservationService`](../../stocksage-backend/src/main/java/com/stocksage/research/ResearchTaskObservationService.java) 共用于聊天重发与任务 SSE，返回原会话、Trace 和任务身份；历史过程事件缺失时仍从数据库读取终态。重发不重置已结束任务，也不受新任务配额限制。若两个请求同时首次到达，数据库提交键唯一约束裁决一个研究运行；这不是对入库前意图识别和聊天预处理的全链路 exactly-once 承诺。

- `requestFingerprint` 将相同用户、标的与归一化问题跨会话分组，允许多条运行。它不代替提交幂等键，也不证明不同运行的上下文、数据或模型相同。
- 新提交创建新任务，`previousTaskId` 记录创建时可见的最近同组任务；并发主动运行可有同一前驱，不把它解释为严格串行链。旧任务的载荷、结果引用、状态和未清理快照不被新运行覆盖。
- worker 重试与接管保留任务 ID、提交键和请求载荷，仅沿用原有 owner fence 增加 `attempts`。任务成功后的正常 checkpoint 清理规则不变。
- 缺少 `submissionId` 的旧客户端继续使用原来的用户、会话、标的、问题提交键；同键的终态请求现在回读原运行。需要在同一会话主动重新研究的客户端须发送新 `submissionId`。

[`V12`](../../stocksage-backend/src/main/java/db/migration/V12__research_run_identity.java) 保留历史 ID 与幂等键，按历史请求载荷回填跨会话分组，并增加分组查询索引；缺少问题的历史载荷保留原键作为分组，不推测原始问题。历史前驱不补造，新运行按实际查询建立关联。迁移与新代码应在停止旧 worker 后部署，旧代码仍有重置终态的写路径，不能混用。

运行身份不等于完整执行归因：实际模型参数、Prompt/策略/能力版本、数据快照、制品身份及花费还需要不可变执行清单与 usage 记录；实施状态见文末进度链接。

报告复用返回原持久化版本 ID 与报告正文。同步和后台入口均把该 ID 关联到当前运行，沿用原有 owner-fenced 完成路径；后台仍与助手消息同事务发布。复用不新建报告版本、不把当前运行选用的模型名覆盖到原报告，也不额外触发报告持久化事件。运行所引用的报告版本号与数据库实体 ID 是不同字段，回读结果使用后者。

### 执行入口

[`ResearchSubmissionService`](../../stocksage-backend/src/main/java/com/stocksage/research/ResearchSubmissionService.java) 负责提交去重、配额检查、入队与已有运行观察。只有队列明确不可用时才调用 `runInlineFallback`；它准备已解析标的的展示上下文，但不续租、保存研究 checkpoint 或更新研究阶段。该入口返回 `SubmissionResult`，不依赖聊天预取类型；`ToolPrefetchService` 仅把结果映射为聊天使用的 `PreparedToolContext`。

| 契约 | 后台 `runFullPipeline` | 同步 `runInlineFallback` |
|---|---|---|
| 启动输入 | worker 提供任务和租约，提交载荷提供请求条件 | 提交入口提供任务和请求条件，管线获取租约；获取失败则返回观察身份 |
| 所有权 | 启动或接管后检查 Redis 租约与数据库 owner | 启动前检查租约，阶段与副作用前检查 owner；失去所有权转为观察已有任务 |
| 恢复 | 加载 checkpoint，重新验收证据，并延续恢复次数与授权轮次 | 当前执行从证据阶段开始，持久化恢复计划与完成状态；中断后可由 worker 接管这些 checkpoint |
| 辩论 | 从恢复后的下一轮及授权边界继续 | 从第 1 轮、尚未授权后续轮次开始 |
| 共同阶段 | 两个入口都调用 `runDebateAndCheckpoint`：逐轮保存、Harness 快照、综合快照及阶段写入，沿用 owner 检查 | 同左 |
| 完整报告发布 | 用户发布事务内提交报告版本、最终助手消息和任务终态 | 用户发布事务内提交报告版本和任务终态；助手消息由后续聊天交付负责 |
| 交付 | 提交后清理完成 checkpoint，发送 `task-final`，依据持久化任务终态结束 Trace | 返回答复或观察身份；不发送 `task-final`，不结束聊天 Trace，不在研究管线写助手消息 |

同步执行的消息交付不与报告发布处于同一事务，不能把后台的原子发布保证扩大到同步入口。报告复用、证据不足和显式离线样本沿用各自已有结果类型，不能按技术成功自动判为完整研究。

证据采集、恢复及有界补证继续使用 `DeepEvidenceCollector` / `DeepEvidenceReplanService`；辩论和报告验收继续使用 `ResearchDebateService`。交付不同的部分保留在各自入口，不能用一组布尔开关把两条路径变成通用执行引擎。

## 模块所有权与依赖方向

会话入口、SQL、Prompt、SSE、标题及图片处理归入 [`conversation`](../../stocksage-backend/src/main/java/com/stocksage/conversation/package-info.java) 包；`ChatStreamSession` 保持包内可见，不向其他模块公开。共同使用的确定性文本截断位于 `util.PromptText`，不属于会话或研究实现。证据事实与判定位于 [`evidence`](../../stocksage-backend/src/main/java/com/stocksage/evidence/package-info.java)，供应商响应映射位于 `evidence.adapter`。任务执行、取证/辩论编排和报告发布归入 [`research`](../../stocksage-backend/src/main/java/com/stocksage/research/package-info.java)。来源摄取与研究记忆归入 [`knowledge`](../../stocksage-backend/src/main/java/com/stocksage/knowledge/package-info.java)，账号注册与请求身份提取归入 [`identity`](../../stocksage-backend/src/main/java/com/stocksage/identity/package-info.java)。这些包表达职责所有权，并不表示进程隔离或完整依赖图无环。

| 所有者 | 公开职责 | 不应进入该职责的依赖 |
|---|---|---|
| conversation | 聊天入口、会话 SQL、纯 Prompt 组装、请求级 SSE；研究发布可调用会话消息写入服务 | Prompt 组装不得查询仓储、调用模型或写 Trace；SSE 不得执行研究或写消息 |
| research | 任务状态、租约、checkpoint、证据/辩论编排、报告发布 | 不依赖 `ChatService`、`ChatStreamSession` 或 HTTP 会话；身份通过参数传递 |
| evidence | `EvidenceModels` 拥有标的与证据类型，账本负责可引用性，`EvidenceTiming` / `EvidenceFreshness` 保存时间事实并判定时效；`evidence.adapter` 解析供应商响应 | 纯事实与判定类型不依赖 Harness、适配器、仓储、网络客户端、模型 SDK 或 Trace |
| knowledge | 文档摄取、检索、版本发布及派生索引维护 | 不依赖聊天 Session 或研究 worker；SQL 仍是正文与版本真源 |
| identity | 认证、请求身份提取及用户会话 | 业务服务不自行读取 HTTP Session/SecurityContext；画像服务不因名称含 User 就归入认证 |

`knowledge` 拥有手动、SEC 及统一来源摄取，以及记忆的资格、冲突、排序和向量索引。既有 `rag` 包保留检索、重排、嵌入、文档加载、定时摄取和维护适配职责：它调用知识摄取 API，知识摄取通过公开 `Bm25IndexUpdate` 事件通知提交后的派生索引更新。`ResearchMemoryProperties` 的配置键和实际值约束、来源/版本标识、tenant hash 及嵌入批次上限不随包归属变化。

`identity.AuthService` 负责账号注册，`identity.RequestIdentity` 在请求边界提取用户 ID。`config.SecurityConfig` 继续配置认证链；[`security.AuthenticatedUser`](../../stocksage-backend/src/main/java/com/stocksage/security/AuthenticatedUser.java) 是保留的存储契约例外。该主体经 SecurityContext 写入 Redis Session，现有默认 Java 序列化依赖类名与结构；不能只为目录命名迁走主体、修改其序列化身份或让已有会话失效。

[`ModuleDependencyBoundaryTest`](../../stocksage-backend/src/test/java/com/stocksage/architecture/ModuleDependencyBoundaryTest.java) 检查上述指定类以及研究、知识、身份和 RAG 范围的直接源码引用，并检查生产源对应的组件发现与 Session 可见性。证据根包的全部 Java 源文件只允许纯契约及序列化注解依赖；Harness 消费证据类型，证据不反向依赖 Harness。知识与 RAG 不拥有聊天 Session 或研究任务执行生命周期，身份入口不依赖这些业务编排。检查约束职责倒流，不证明反射、动态加载或传递依赖层面的运行时隔离。

`ResearchTaskWorker`、队列、租约和恢复调度器共同拥有任务执行生命周期；`DeepResearchPipeline` 组织阶段，`DeepEvidenceCollector` / `DeepEvidenceReplanService` 组织取证，`ResearchDebateService` 组织辩论续停与 checkpoint 回调。Bull/Bear/Manager 及模型协议解析位于 `agent`，由研究编排调用；补证的 [`DeepEvidenceReplanner`](../../stocksage-backend/src/main/java/com/stocksage/agent/DeepEvidenceReplanner.java) 只负责已有提示和模型 SDK 调用，返回原始提案。研究服务保留提案校验、固定能力 allowlist、executor/超时/取消、owner 检查与严格 checkpoint，模型适配器不取得执行权限。研究包禁止直接引用模型调用 SDK。`ToolPrefetchService` 委托 `ResearchSubmissionService`，由后者提交、选择同步执行或读取已有任务的观察身份；普通请求状态和传输 Session 不进入研究模块。报告版本、呈现和 `InvestmentReportPersistedEvent` 随报告发布者归属 research，事件仍是进程内事务事件。

`EvidenceEnvelopeMapper` 共用于普通与 DEEP 路径，负责响应解析、来源元数据、标的规范化及稳定证据 ID。它复用既有 `TickerResolutionService.normalizeStructuredTicker` 和 `KLinePayloadMapper`，不调用前者的会话历史查询或工具搜索；这些协作者的传递依赖仍在上述检查范围之外。请求级 `OrdinaryEvidence` 含取证调用、展示预算与请求覆盖判断，保留在执行适配层，不并入纯证据事实包。

证据契约迁移保留 JSON 字段、枚举值、日期/瞬时精度及 hash 输入。Checkpoint 的状态部分通过 `AnalysisState` 字段结构读写，不写 Java 包名；存储格式版本与恢复规则见下文。历史 JSON 的恢复与报告 hash 兼容检查位于 `ResearchTaskCheckpointServiceTest`。

## 数据所有权与删除边界

SQL 中的 ID 关联分为聚合内关系和历史来源关系。删除聚合只处理它拥有的数据；来源 ID 不隐含级联删除，也不替代读取时的用户归属校验。当前迁移没有为下列关系建立数据库外键，写入约束主要由服务事务、owner fence 与唯一键承担。

| 数据关系 | 所有者与已实现边界 | 生命周期含义 |
|---|---|---|
| 会话 → 消息 | [`ConversationMessageService`](../../stocksage-backend/src/main/java/com/stocksage/conversation/ConversationMessageService.java) 校验会话归属，同事务先删消息再删会话；重生成只裁剪最后一个用户轮次起的消息 | 消息属于会话。删除会话不删除研究任务、报告、Trace 或研究记忆；这些记录中的原会话 ID 可作为历史来源保留 |
| 会话 → Redis 短期记忆 | [`ChatService.deleteConversation`](../../stocksage-backend/src/main/java/com/stocksage/conversation/ChatService.java) 在 SQL 删除返回后清理 Redis | 这是跨存储顺序执行，不是原子删除，也不是账号数据物理擦除承诺 |
| 运行 → checkpoint | [`ResearchTaskCheckpointService`](../../stocksage-backend/src/main/java/com/stocksage/research/ResearchTaskCheckpointService.java) 通过当前 owner 或锁定成功任务授权清理 | checkpoint 是可回收的恢复状态；删除它不删除运行历史、模型调用和独立证据快照 |
| 报告 → 生产运行、证据快照；调用 → 运行、attempt、快照 | research 拥有发布和不可变归因，具体规则见[独立证据快照](#独立证据快照)及[报告生产者](#报告生产者) | 属于历史来源关系。报告复用保留原生产者；不能从消费者会话删除或 checkpoint 清理推导历史记录应级联删除 |
| 报告 → 研究记忆 | [`ResearchMemoryService`](../../stocksage-backend/src/main/java/com/stocksage/knowledge/ResearchMemoryService.java) 校验用户、处理负向审核与显式撤回，提交后删除派生向量 | 撤回与物理删除不同；SQL 资格决定可用性，向量删除完成情况不能由撤回成功推断。资格规则见[记忆契约](research-memory-decay-conflict-plan.md) |
| RAG 来源 → SQL 分块 → Milvus/Lucene | [`KnowledgeIngestionService`](../../stocksage-backend/src/main/java/com/stocksage/knowledge/KnowledgeIngestionService.java) 持有来源锁，发布 SQL 当前版本，提交后更新/清理索引；过期来源保留空来源行作为清理标记 | SQL 是正文与版本真源，派生索引可重建。物理向量清理可重试，不因清理失败撤销已提交来源；详见[来源发布与清理](../reviews/2026-09-14-code-audit.md#rag-来源发布与清理) |
| Trace → 步骤 JSON | [`TraceService`](../../stocksage-backend/src/main/java/com/stocksage/trace/TraceService.java) 拥有 Trace 及步骤，当前每次追加重写 steps JSON | 用于有界演示轨迹；摘要查询不读取 steps。它不是按步追加的审计存储，不承诺长轨迹恒定写成本 |

会话删除不等于取消其关联研究；任务的接管、发布与终态仍由 research 处理。对外提供“删除全部数据”或账号注销前，必须另行定义活动任务的停止、历史归因的保留或匿名化、外部索引的清理完成状态，不能将现有会话删除接口改名充当该功能。当前没有为运行、报告、调用、快照及 Trace 建立统一自动保留期限；本契约不虚构保存天数或物理擦除保证。

外键演进先选共同生命周期的聚合内关系，例如 `messages.conversation_id`。实施前须检查存量孤立行并定义迁移处置，再用并发消息写入/会话删除验证约束；本次不新增外键。对于历史来源 ID，禁止未经生命周期决策直接添加 `ON DELETE CASCADE`。JPA 发布事务与显式 SQL 的 owner CAS 共同承担原子性，不能用单一外键代替这些业务条件。

### 扩容改造的触发条件

沿用现有本地部署分工，下面的变化需要独立实施与验收；实现状态仅记录在[进度文件](../../progress.md)，不在这里复制运行状态。

- 增加第二个检索实例前，为 SQL 来源变更提供持久化序列和副本应用水位；本进程提交事件不能作为跨实例同步协议。验收增删改、落后副本补偿和重启重建。
- 批量/并发摄取需要缩短连接与来源锁占用时，引入有生命周期的待发布版本，把 embedding 移到事务外，再短事务 CAS 发布。准备中的向量必须受保护；现有“非当前已提交版本即清理”规则不能原样复用。验收旧版本持续可用、准备失败、竞争发布及清理恢复。
- Trace 承接长轨迹运营或审计时，改用带稳定事件 ID/序号的追加步骤表，主表只留摘要；验收追加幂等、顺序和与历史 JSON 的兼容读取。

## Checkpoint 格式与恢复契约

[`ResearchTaskCheckpointService`](../../stocksage-backend/src/main/java/com/stocksage/research/ResearchTaskCheckpointService.java) 统一写入 `{"schemaVersion":1,"state":{...}}`。外层版本描述持久化格式，独立于 state 内的 Harness、补证和评级策略版本；升级策略不会自动改变存储版本。

- 历史没有外层版本的裸 `AnalysisState` 是 v0：直接读取，下一次正常 owner-fenced 保存时写为 v1，阶段、轮次和执行决定沿用原记录；不批量改写历史载荷。
- v1 必须有对象类型的 `state`；未知版本、非法版本值、损坏 JSON 或残缺封装均拒绝恢复。只有数据库没有记录才按无断点开始。
- 后台管线在取得执行权后读取快照；格式被拒绝时，通过原有 owner fence 将任务置为 FAILED，保留原快照，并发送包含任务位置与处理指引的错误。此路径不调用工具、模型或离线报告替代逻辑；之后仍按原规则释放租约和结束任务 Trace。
- 新格式的 writer 不能与只认识裸状态的旧 worker 混跑。首次发布须停止并排空旧 worker，再启用 v1 writer；存在 v1 快照时不能直接回滚到不支持该格式的旧制品。旧 worker 将解析错误视作无断点的行为，无法由新 writer 替它修正。

新增不兼容的状态结构时应提升外层版本，并显式实现旧版本迁移或拒绝恢复规则。格式兼容不表示旧策略结论可以直接发布，证据与报告仍经过当前 Harness 验收。定向检查见 `ResearchTaskCheckpointServiceTest`、`ResearchTaskCheckpointFencingTest` 和 `DeepResearchPipelineTest`；这不等同于真实数据库上的跨版本发布验收。

需要保留的具体边界：

- 会话编排调用研究入口，研究发布调用 `ConversationMessageService`；限制到明确入口，不能声称两个完整包已经无环。
- `TickerResolutionService` 仍读取消息仓储以解析会话中的标的；会话仓储尚不是单一读取入口，写入边界则由会话服务负责。
- `ResearchTaskPublicationTransaction` 对用户行加锁是报告原子发布契约，不能为了目录隔离拆开这笔事务。
- 报告审核仍同步调用记忆撤销/协调，记忆仍在报告事件提交后捕获；研究与知识暂不是完整单向依赖。实体、仓储和公共 DTO 保留既有技术层包，不以新增转发服务或重复类型制造隔离。
- `ChatService` 当前复用 `KnowledgeIngestionService.sha256` 计算归因指纹；移动纯哈希函数时必须保留算法与输入编码。

## 职责边界的验收入口

| 变更责任 | 实际入口与验证位置 |
|---|---|
| Prompt 与归因使用同一输入 | `ChatService` 调用 `ChatPromptAssembler.assemble`，模型输入与 answer-context 记录消费同一 Assembly；ordinary live evaluator 调用真实聊天 API，不复制组装逻辑。离线边界见 `ChatPromptAssemblerTest` / `ChatServiceRoutingHistoryTest`。 |
| 会话写入不拥有模型与 Redis 工作 | `ConversationMessageService.appendUserTurn` 在短事务中完成授权、分支替换、路由历史读取及消息写入；`ConversationMessageServiceTest` 验证失败回滚和加入研究发布事务。 |
| SSE 调整不进入证据或研究执行 | `ChatStreamSession` 负责请求级传输生命周期；`ChatStreamSessionTest` / `ChatServiceStreamLifecycleTest` 覆盖取消、后台观察、完整事件与唯一终态。 |
| 两个研究入口复用阶段而保留业务差异 | `DeepResearchPipeline.runDebateAndCheckpoint` 复用轮次与综合 checkpoint；`DeepResearchPipelineTest` 验证同步入口的发布与聊天交付边界，任务事务测试验证后台原子发布。 |
| provider 和模型适配不进入会话 SQL | `EvidenceEnvelopeMapper` 负责供应商结果映射，领域 Agent 与 `DeepEvidenceReplanner` 负责模型调用；`ModuleDependencyBoundaryTest` 限制职责倒流，补证测试通过真实适配器执行受控模型请求。 |

以上入口用于验证职责拆分与明确列出的格式、运行身份契约；H2、受控模型和事件测试不能代替真实供应商、MySQL/Redis、浏览器或多实例故障验收。

## 运行执行配置

[`AgentRuntimeConfiguration`](../../stocksage-backend/src/main/java/com/stocksage/agent/AgentRuntimeConfiguration.java) 从九个研究角色以及查询改写、入库 gist 两个客户端构建时使用的同一 options 和 system 字符串记录模型、temperature、maxTokens 与系统 Prompt SHA-256。快照明确标为 `CLIENT_DEFAULTS`，提供商实际模型修订未知；它不代表动态 Prompt、调用级覆盖参数、历史文档生成身份或响应 usage。

V13 为任务增加独立的 `model_configuration_json` 列。两个研究入口取得 owner 后、读取 checkpoint 或采集证据前，通过 [`ResearchRunManifestService`](../../stocksage-backend/src/main/java/com/stocksage/research/ResearchRunManifestService.java) 冻结或校验版本化快照。事务复用任务行锁，原生 SQL 仅允许当前 owner 首次写入；JPA 不写此列，避免旧实体覆盖。成功清理 checkpoint 不会删除这份配置。

清单 schemaVersion=6、scope=`EXECUTION_CONFIGURATION`。除角色默认配置外，包含完成/评级策略 ID、版本及类资源 SHA-256，实际绑定的能力目录（按 ID 排序），RagService 检索参数、重排默认参数和调用上限，以及补证开关、归一后的搜索数量与超时。清单同时冻结下述费率卡配置，未配置也明确留存。策略哈希只代表对应打包类资源，不代表其全部依赖、完整部署制品或 JVM 插桩后的字节。RAG 参数只描述配置，不证明某次重排成功。旧 v1/v2/v3/v4/v5 缺少新增归因字段，因此保留原文并拒绝恢复，不自动填入当前值。

`chatProvider` 来自实际 `OpenAiApi` 构造边界：AiConfig 沿用框架的连接参数解析、headers、HTTP builders 和错误处理器，在实例构造成功后记录同一份 base URL 与 completions path 的脱敏身份；默认 ChatModel 的其余自动配置保持由框架负责。scope=`API_CONSTRUCTION`，协议/主机/端口明文、路径仅存 SHA-256，不记录 userinfo、query、fragment、headers 或凭据。身份缺失拒绝开始/恢复，已记录身份变化拒绝复用原清单；不把排除字段、DNS、代理、重定向或网关背后的模型权重宣称为已追踪。

RAG 快照还包括 QueryRewriter 的开关/长度上限和 KeywordSearchService 的 BM25 参数/索引契约版本。注入的 LocalEmbeddingModel 直接提供其模型别名、维度和超时；其它 embedding 实现仅记录实现类型和 `UNKNOWN`，不通过试请求探测、不将环境配置冒充 Bean 实际值。EDGAR 分块快照按实际 splitter 边界归一大小和重叠，记录版本、gist 开关/长度与粒度，scope=`CURRENT_INGESTION_DEFAULTS`；它不能证明已检索历史文档使用了这套配置，历史来源仍需文档自身元数据支持。

- 重试按 JSON 结构校验原配置，不覆盖清单。配置变化、未知格式和事务提交失败都停止执行；保留快照，沿用 owner-fenced 失败终态，禁止发布离线替代报告。
- 已有尝试但没有清单的历史任务不能补造历史配置。接管后尝试次数大于 1 时拒绝冻结，需检查原记录或主动发起新研究。完成任务的历史归因仍未知。
- 发布时应先排空旧 worker；新字段迁移不允许旧 worker 绕过配置冻结继续执行。H2 检查不证明真实 MySQL 迁移或混合版本部署已通过。

当前 DEEP 路由不检索 RAG 文档，也不读取研究记忆；清单的 consumption 分别记录 `NOT_USED`。上述 RAG/入库配置属于运行配置目录，不能当作本次研究消费过历史文档的证据。DEEP 实际数据来源来自财务、行情和新闻证据账本。

`RuntimeArtifactIdentity` 在启动时从 StockSageApplication 的代码来源取样，不读取 Git HEAD。文件部署使用整个来源文件 SHA-256（`CODE_SOURCE_FILE`）；开发目录使用按相对路径排序的 class 文件树 SHA-256（`CODE_SOURCE_CLASS_TREE`），并记录 Java runtime 版本。后者不包含外部 classpath 依赖和配置文件，前者只覆盖该文件实际包含的字节；两者都不证明 JVM 插桩后的内存字节。来源无法读取时记录 UNKNOWN，研究入口拒绝开始或恢复。运行期间替换制品/热改 class 不在此启动快照保证内，部署应使用不可变制品并重启进程。

DEEP 的实际证据账本和有界上下文通过下述独立快照留存。非本地 embedding 的默认值仍未知，但当前 DEEP 不使用它；普通问答/入库调用级归因不由这个 DEEP 契约宣称覆盖。运行预算的范围见下文。

实施状态和验证证据统一记录在 [progress.md](../../progress.md)。

## 运行截止时间与调用额度

新任务创建时从 `stocksage.research.budget.timeout-ms` 和 `stocksage.research.budget.max-model-calls` 冻结截止时间与模型调用上限，分别保存到 V17 的 `budget_deadline_epoch_ms`、`max_model_calls`。排队时间也计入期限；同一提交的重复请求、失败重试和接管不刷新预算，主动发起新研究才获得新预算。历史空值不回填，继续执行时明确失败。

调用开始在任务行锁下统计该 run 的全部调用记录并预留下一次额度；成功、失败、取消和未知结果都占用额度，跨 attempt 不返还。调用上下文期限必须与数据库一致。用量接口的 `budget` 返回冻结上限、已预留调用数和查询时剩余毫秒；历史未知字段保持 null。

模型流使用单次绝对截止定时器，持续输出不能续期；同步调用在调用前后校验，补证等待时间取阶段上限与运行剩余时间的较小值。预算异常穿过补证、辩论和发布边界，沿原 owner fence 写 FAILED，不转成离线报告或策略性跳过。失败状态写入本身不受已经耗尽的期限阻止。Spring AI 的 `spring.ai.retry.max-attempts=1` 关闭该重试层，避免一次额度对应多个隐式 SDK 尝试。

期限来自后端主机的 epoch 毫秒，多后端部署要求时钟同步。本地取消不能证明供应商停止计费。聊天、Ollama 与 rerank 并发、同步模型 HTTP 超时及 SEC 摄取的阶段预算检查见下文。Token/金额上限及剩余供应商并发边界仍未完成。

### 工具与数据服务期限

首次取证、恢复取证和补证沿用原任务的 `RunDeadline`。执行者显式把值带入线程池闭包，在入队、开跑、返回处检查；工具等待取阶段上限与运行剩余时间的较小值。`ToolCallContext` 的预算只读当前线程，不使用观察信息的单活跃会话注册表，也不在线程池中自动继承，作用域结束恢复原值。

Java 数据客户端每次实际请求及重试重新计算期限，HTTP timeout 取原请求上限与运行剩余时间的较小值。研究请求携带 `X-StockSage-Remaining-Ms`，值为原运行剩余毫秒、最多 1800000；它独立于单工具的更短 timeout。普通请求不附加该 header。超过协议上限的长研究，其单次数据服务请求最多获得 30 分钟，不能据此无限延长一次 provider 操作。

Python ASGI 入口校验唯一的正整数 header（1–1800000），转换为本机单调时钟期限，并通过 ContextVar 传到同步路由；请求结束清理。该定义从服务端收到请求起计时，不假设两台机器时钟相同，也不扣除传输耗时；Java 原期限始终限制调用方等待。非法 header 返回 400 `INVALID_RESEARCH_BUDGET`，到期返回 504 `RESEARCH_BUDGET_EXCEEDED`。Java 将该错误还原为研究预算异常，不计作搜索供应商故障或继续重试。

搜索、SEC、AKShare 和 BaoStock 的请求路径在新调用、备用来源和查询迭代边界检查期限；支持显式 timeout 的 HTTP 和等待使用剩余值。AKShare 目录线程显式复制上下文，BaoStock 请求侧锁等待受限，后台登录管理不绑定某一次研究。局部 provider timeout 在运行仍有余额时保留既有错误与回退语义。Java 直连 IBKR 的只读 HTTP 同样取剩余时间，预算异常不转换为行情错误，也不计入网关熔断失败。

这些检查不能强行终止正在运行且不支持取消的第三方 SDK；`CompletableFuture.cancel(true)` 也不证明底层线程已经停止。本地 HTTP 取消与 Python 协作式截止不等于远端供应商中断。可选自动入库的辅助模型与持久化链路仍需补预算约束，不能把默认取证路径的证据扩展为所有入库工作已受限。

### 执行队列与容量拒绝

`agentTaskExecutor` 承载在线分析师、取证和补证，`backgroundTaskExecutor` 独立承载画像、会话标题及搜索结果入库。两者使用固定线程数与有界队列；队列容量由 `stocksage.agent.queue-capacity`、`stocksage.background.queue-capacity` 配置，负值拒绝启动，零表示不排队。满队使用拒绝策略，不在提交线程执行慢任务。线程和队列默认值以 [AsyncConfig](../../stocksage-backend/src/main/java/com/stocksage/config/AsyncConfig.java) 为准。

必需 DEEP 取证被拒绝时，`ResearchCapacityExceededException` 沿 owner fence 写入失败终态，不触发离线报告。可选补证规划记录 `CAPACITY_UNAVAILABLE`，能力网关记录 `CAPACITY_EXCEEDED` 并补足失败观测；同池能力容量不足不会被解释为 MCP 供应商故障而立即重试本地备用能力。普通可选分析师被拒绝则保留已取证据，结果为 `DEGRADED`，Trace 中 `analystStatus=CAPACITY_REJECTED`。

后台提交被拒绝记录 `reason=CAPACITY_REJECTED`；该次更新未执行，不改变已完成回答及 Trace 的成功状态，也不声称画像、标题或知识已更新。后台执行失败有独立日志。这里没有增加持久化后台任务队列或自动重试，因此不能承诺这些可选更新最终必达。

队列上限约束本进程等待任务数，不等于全部供应商的全局并发上限。已取消的 CompletableFuture 包装任务可能仍占队列槽位，直到 worker 取出；不能宣称取消立即释放容量。跨实例公平性及负载下延迟验收仍需按范围分别处理。

### 聊天模型共享准入

Spring `ChatClientCustomizer` 为注入的构建器及其 clone 追加同一个 `ChatConcurrencyAdvisor`，覆盖当前普通回答、路由、DEEP 角色和后台聊天客户端。`stocksage.chat.max-concurrent-calls` 控制本进程共享上限，必须为正数；默认值是运行策略，不是性能或质量 SLO。现有 OpenAiApi 来源记录、模型参数和框架自动配置保持不变。

准入使用即时 `tryAcquire`，不增加等待队列，并先于 DEEP 调用事实入库：未获准的请求没有发给模型，也不预留一次已执行调用额度。同步调用在退出时释放；流式按每次订阅持有名额，直到完成、错误或本地取消，收到响应头或单个 token 不释放。名额表示本地活跃调用，取消后服务商可能仍处理请求，不能把本地名额释放当作远端停止计费。

容量异常沿研究失败契约传播；可选补证保留明确的容量跳过原因。关系抽取不能将拒绝执行当作空关系发布，旧关系保留。可选查询改写、gist、画像或标题仍遵循各自已有的确定性降级规则，不将这些结果声称为模型成功输出。

每个流订阅通过 Reactor Context 将独立的停止信号传给 chat 专用 WebClient filter。调用结束时先停止尚未返回响应头的请求或正在读取的 HTTP body，再释放并发名额；这绕开当前 SDK 内部窗口融合在首块到来前可能保留上游的取消行为，不依赖收到首个 token。停止信号不会跨请求复用，也不改变其他 WebClient 的行为。

普通回答准备阶段或流内的容量错误返回明确的容量不足提示，保持失败终态；普通 HTTP 接口使用现有错误结构返回 503。可选分析师容量不足保留原始证据及 `DEGRADED / CAPACITY_REJECTED`，不发布虚假的模型分析成功。

该上限不覆盖直接绕过注入构建器的新 ChatModel 调用、embedding、rerank 或 Python 数据供应商，也不跨多个后端实例协调；新增调用路径需明确纳入相应入口。它控制并发数量，不替代每次研究的 Token/费用上限。

### Ollama 向量调用准入与期限

当前单例 `LocalEmbeddingModel` 在实际 `/api/embed` HTTP 边界共享独立并发名额，`stocksage.embedding.ollama.max-concurrent-calls` 必须为正数。名额持有到响应体读取完成、错误或超时；容量不足立即抛出容量异常，不进入无效向量的逐条重试。

有研究期限时，每次请求使用本地 timeout 与研究剩余时间的较小值；批量无效后的逐条请求仍消费原研究期限。过期会中止等待并阻止后续文本请求，不伪造向量或刷新期限。没有研究上下文的普通检索、手动入库仍使用本地 timeout。有效并发值进入现有 embedding 运行配置快照。

该边界只覆盖 Ollama 实现，不覆盖可选 DashScope embedding、rerank 或远端推理生命周期。批次上限保持现有约束。

研究内的每次实际 `/api/embed` 请求还进入同一运行账本，包括批量失败后的每次逐条调用。实际构建的 endpoint/protocol 身份进入 `rag.embedding.providerIdentity`；它与 chat 供应商分别校验。输入归因为清洗后实际文本数组的哈希，类型 `EMBEDDING_INPUT`，不要求已经完成的研究证据快照。缓存或来源未变化而跳过向量计算时，不产生模型调用记录。

开启 token 预算后，`stocksage.research.token-budget.embedding` 必须提供独立的 `provider-fingerprint`、`model`、`max-input-tokens-per-text`、`deployment-revision`、`source`、`valid-from`、`valid-until`。每条输入上限会作为实际请求的 `options.num_ctx` 发送；预留量是实际批次条数乘该上限，消耗同一个 run 的 `max-tokens`。部署修订是运营方提供的声明，远端模型修订仍为未知；使用前须核验目标 Ollama 版本、模型和输入截断语义，不能从 `bge-m3` 名称推断上限。合同缺失、过期、配置变化或实际 provider/model/context 不符时，拒绝请求。

原生 `prompt_eval_count` 按该次响应的批次总数保存，不再乘输入条数，字段与截断语义参见 [Ollama embed API](https://docs.ollama.com/api/embed) 和目标部署版本的实现。完整 HTTP 响应的用量先结算，再校验向量；即使向量无效，已经发生的消耗也不消失，随后 fallback 发出的请求分别计账。调用记录以 `completionScope=PROVIDER_HTTP_RESPONSE` 区分供应商响应完成与向量可用；缺失用量、超时或错误保留预留。此口径不表示服务器内部所有重试计算，也不把本地计算资源记为零成本。

当前 embedding 的金额标记 `UNKNOWN / EMBEDDING_NOT_PRICED`，不套用 chat 费率。用量明细按各自的 `providerFingerprint` 分组；API 顶层兼容字段仍仅代表 chat 配置。DashScope embedding 尚未接入实际请求账本，因此启用 token 预算时拒绝该 provider 配置，避免静默绕过；未启用额度的原配置路径保留。

### SEC 摄取的预算检查与发布

受研究任务调用的 SEC 摄取沿用同一 `RunDeadline`：公告、章节、父子切片和向量批次开始前检查期限；gist 缓存读取后、模型返回后再次检查。预算异常穿过 gist fail-open、单公告失败汇总、语义去重和工具错误包装，不被解释为普通供应商故障继续执行。

向量写入完成后、SQL 版本替换前检查期限，事务 `beforeCommit` 再以捕获的原期限检查。已知过期时回滚 SQL，旧发布版本和旧向量保留；新写入但未发布的向量仍由现有恢复清理处理，BM25 继续在 SQL 提交后更新。这里没有新增跨存储事务或摄取调度器。

这些检查是阶段边界约束，不能中断数据库调用或提交过程，也不保证所有外部工作在截止毫秒瞬间停止。沿用 Boot 默认构建器的 gist 同步 HTTP 同时受下述传输期限约束；无研究上下文的手动或定时摄取不会获得虚构的研究期限。

### 可选 rerank 的容量边界

`stocksage.rag.rerank.max-concurrent-calls` 限制本进程同时执行的 DashScope 重排调用，必须为正数。名额覆盖同步 SDK 调用直到返回或抛出异常；满额时不请求供应商，记录 `CAPACITY_REJECTED`，按原融合顺序返回候选。有效上限进入现有 rerank 配置快照。

存在研究期限时，重排调用前后及异常出口都检查剩余预算；预算异常不会被本类解释为普通供应商失败而降级。SDK options 没有逐请求 timeout 参数，默认 Boot 装配通过下述传输层约束进行中的 HTTP。外层 RAG 降级对预算异常的处理仍需纳入对应入口；当前 DEEP 不消费该 RAG 路径，不能据此声称 DEEP 的重排质量已验收。

### 同步模型 HTTP 的研究期限

`ResearchHttpDeadlineConfiguration` 仅装饰由 Boot `HttpClientAutoConfiguration` 定义的请求工厂构建器。没有研究期限的请求继续使用原工厂；带期限的请求在正文序列化后、执行前取剩余时间，与已有本地 read timeout 取较小值。专用 JDK 工厂继承 Boot settings 的 SSL bundle、连接超时和重定向设置；只在设置超时与构造请求时短暂加锁，网络执行不串行化，也不为每次请求创建新客户端或工作线程。

Spring JDK 请求将超时固化到单个请求，并在超时时取消等待响应头的请求或关闭正在读取的响应体。研究调用前后仍检查原 deadline；同步 `ModelInvocationAdvisor` 显式建立同 run 的工具期限作用域并在退出后恢复。实际 OpenAI-compatible chat 与 DashScope rerank 的 Boot 自动装配均沿用该工厂；URL、凭据、模型选项、错误处理器及既有重试策略保持在原 SDK 装配路径。

自定义 factory builder、之后覆盖工厂的 RestClient 定制和直接绕过 Boot 的客户端不在这个保证内；不会静默替换这些 transport。供应商远端计算、计费、数据库等待与提交不受本地 HTTP 取消证明。这里也不承诺调度和请求启动零耗时的毫秒级绝对终止。

## DEEP 模型调用事实

`ModelInvocationContext` 显式携带 run、attempt、owner token、原任务 Trace、证据快照 ID 和运行截止时间；绑定在当前 `AnalysisState` 上但不序列化到 checkpoint，不进入数据/上下文哈希。恢复时重新绑定当前执行者，Bull/Bear 并行调用不通过线程局部变量传递身份。调用开始还会验证快照属于相同 run/attempt，不能引用其它运行的数据。

SEC gist 在证据快照生成前运行。后台和同步研究入口将独立的 `RunExecution` 执行身份显式传入取证线程，并在退出时恢复线程状态；身份不从活跃 Trace 注册表推断。gist 缓存未命中时，把实际用户提示的 SHA-256 作为 `PRE_EVIDENCE_INPUT` 归因，同时保存既有的完整提示指纹。此路径的 `evidence_snapshot_id` 保持 null，不伪造已完成快照，也不把输入哈希当作可重放的全文。仅 `contextual-gist` 角色允许这种输入归因，仍受相同 owner、attempt、deadline、调用次数和 token 预留约束。缓存命中不产生模型调用记录；执行身份缺失或失效不能经摄取降级路径继续调用模型。

V14 的 `research_model_invocations` 按调用 UUID 留存事实。`ModelInvocationStore.begin` 在独立事务内锁定运行中任务并校验 owner/attempt，提交后才允许模型请求；记录角色、按顺序排列的文本消息哈希和请求模型参数，不持久化原始 Prompt。`finish` 只更新该调用的首个终态，因此旧 attempt 的迟到响应可以留下用量事实，但不能修改新 attempt 的任务或报告。

`ModelInvocationAdvisor` 从真实 ChatClient 请求与响应提取信息，接在 Bull、Bear、评分、续停、综合及补证调用上。流式请求启用提供商 usage 返回；最终记录最近一次提供商用量快照，不能把累计快照逐块求和。没有用量时标为 `NO_DATA`，不填零；调用状态区分 `SUCCEEDED`、`FAILED`、`CANCELLED`，仅表示模型调用结束方式，不代表研究业务验收。实际响应模型名是提供商返回值，仍不保证固定权重修订。

请求记录包含按相同文本范围统计的 UTF-8 字节数和消息数，`inputTokenCountStatus=NOT_COUNTED`。输出控制记录 `maxTokens`、可用的 `maxCompletionTokens`、`n`、`reasoningEffort` 及白名单内的 `enable_thinking`、`thinking_budget`；任意 extraBody 内容不落库，仅记录是否存在。`optionsScope=CHAT_CLIENT_REQUEST_OPTIONS` 表示 SDK 合并模型默认值前的范围，`totalOutputTokenCeilingStatus=UNVERIFIED` 不将这些值声称为完整供应商 token 上限。

Qwen 的回答限制与包含思考的输出限制有不同语义，且支持范围因模型而异，见[官方兼容 API 参数](https://help.aliyun.com/zh/model-studio/qwen-api-via-openai-chat-completions)。整窗预留使用绑定部署、模型和有效期的输入／回答／思考上限合同，不把文本字节数或费率适用范围当作准确 token 数。

### 模型 token 额度的预留与结算

`stocksage.research.token-budget.max-tokens` 未配置时显式为 `DISABLED`；启用时必须同时提供 `provider-fingerprint`、`version`、`source`、`valid-from`、`valid-until`，以及每个请求模型的 `models[模型名].max-input-tokens`、`max-output-tokens`、`max-reasoning-tokens`。输入和回答上限为正，思考上限可为零，三项总和必须可用 long 表达。来源、适用部署与模型上限由配置者核实，不内置未经部署确认的数值。

V18 增加任务的不可变 `token_budget_json` 和调用的 `reserved_tokens`、`accounted_tokens`。新运行在提交时冻结合同；历史 null 保持未知，不能自动变成无限额度。已启用的冻结合同必须与当前配置一致才可继续调用；配置变化需要新运行。部署需先应用迁移并停止旧 worker，不能让旧调用绕过预留。

调用开始复用任务行锁，按完整输入窗、回答上限、思考上限之和预留，所有 attempt 共享额度。默认或请求级多响应、额外请求体和工具调用不属于该合同，启用时拒绝这些配置或请求。仅完整成功、供应商原始 input/output/total 字段齐全且总数一致时，以实际用量替换预留；失败、取消、缺字段或仅有 SDK 归一化数据时保留预留，已观察到的超额消耗如实计入。迟到结果可以结算原调用，重复终态不能重复释放。

SDK 的 `DefaultUsage` 会补零或推算总数。调用账本仅把原生 `OpenAiApi.Usage` 字段标为 `PROVIDER`，缺失字段保持缺失；仅有 SDK 归一化值时标为 `SDK_NORMALIZED`，不据此释放额度。预算接口的 `accountedTokens` 包含尚未结算的预留，不能当成实际消费；`EXHAUSTED` 表示余额不足任何已配置模型的一次整窗预留。

此额度当前覆盖已有账本记录的 DEEP 模型调用、研究内 SEC gist 与 Ollama embedding；任务创建前的改写、普通分析、研究外摄取仍不在其范围。整窗预留偏保守，依赖配置合同正确且供应商遵守上限，既不等于准确预分词，也不承诺远端取消或账单金额封顶。完整运行费用预算仍需继续接入。

调用终态必须在正常完成信号传给管线前落库。进程中断或终态写入失败可能留下 `RUNNING` 调用记录，表示结果/费用未确认；不能据此推断没有消耗。取消后提供商可能继续计费，本地取消记录也不能声称最终完整用量。该路径尚不覆盖普通分析师、研究任务创建前的查询改写或研究外摄取的调用级归因；现有配置快照不等于这些调用事实。费率估算见下文；统一预算与账单核对仍需单独验收。

## 独立证据快照

V15 的 `research_evidence_snapshots` 保存 query、ticker、时间要求、现有权威算法生成的数据/上下文哈希、完整 EvidenceLedger、三维有界源上下文及引用列表。payload schemaVersion=1，正文不再二次截断；来源、payload hash、观测时间和未知业务时间按原账本保存。快照是输入记录，不自动证明证据通过业务验收。

`ResearchEvidenceSnapshotService.capture` 在独立事务内校验 owner/attempt；同一 run/attempt 下相同 payload hash 复用已有行，输入变化或新 attempt 新建行，旧行不改写。后台恢复和两个执行路径在补证模型前、补证后复用查询前、辩论前保存快照。存储及提交失败停止执行并保留原 checkpoint，不能通过离线替代报告掩盖归因缺失。

任务 `final_evidence_snapshot_id` 在 RUNNING 时指向最近一次已捕获输入，终态后保留；若某次尝试未能采集，它可能仍指向更早 attempt，查询时须以快照自身 attempt 为准，不能补造本次输入。任务的该指针和 `result_report_version_id` 把观察数据与交付结果关联；复用时仍指向原报告，不改写原报告内容/模型元数据。模型调用表的 `evidence_snapshot_id` 固定引用调用开始前的输入，后续补证不会移动旧调用的引用。成功清理 checkpoint 不删除这些快照；历史调用关联列为 null，不进行猜测性回填。

## 报告生产者

V16 在报告版本上保存首次创建者的 producer_run_id、producer_attempt 和 producer_evidence_snapshot_id。仅新建报告时从服务端执行上下文赋值；正常复用、并发插入后的赢家回读及人工审核均保留原值，JPA 更新排除这三列。发布仍使用现有报告、消息与任务终态的原子事务。历史或无执行上下文的记录留空，不从后来的消费者运行推测来源。

生产者表示创建该版本的执行尝试，不保证一定调用过模型：离线报告须结合运行 resultKind、报告模型标记及实际调用记录解释。消费任务的 result_report_version_id 指向交付版本，报告生产者指向原始创建尝试；二者不能混同。

## 运行用量查询

`GET /api/research-tasks/{taskId}/usage` 按当前登录用户查所属任务；不存在与无权访问均返回 404。响应只返回汇总，不暴露请求正文、凭据或租约。`scope=RECORDED_DEEP_INVOCATIONS` 限定为该运行已记录的 DEEP 调用，包含所有 attempt，并按 attempt、角色、请求模型和响应模型拆分；复用报告的原生产成本不计入消费运行。

`observedUsage` 分别累加提供商返回的 input/output/total token 字段，同时返回各字段的已知调用数。没有数据时为 null；提供商明确返回零时才是零。不由 input/output 推测缺失的 total，也不累加同一流的多个累计快照。取消/失败的部分返回可计入已观察用量，但计入 `unconfirmedCalls`；仍为 RUNNING 的调用还计入 `unfinishedCalls`。`NO_DATA`、`PARTIAL`、`COMPLETE_RECORDED_USAGE` 描述这些记录的覆盖程度，后者不意味着任务完成、隐式 SDK 重试完全可见或供应商账单已核对。

`monetaryCostStatus` 与 `observedUsage.estimatedCost.status` 同源，取值为 `UNAVAILABLE`、`PARTIAL_ESTIMATE` 或 `COMPLETE_RECORDED_ESTIMATE`。`estimatedCost` 分币种返回十进制字符串金额、可估算/未知/未确认调用数及未知原因计数；不进行外汇换算。`providerFingerprint` 来自该运行冻结的 `chatProvider`，不随当前部署换源而变化；历史没有来源时为 null。任务创建前的路由、普通问答及异步入库不在此 scope 中。

## 历史费率估算

`stocksage.research.pricing.*` 由部署者根据实际合同或价目表配置；仓库不内置供应商价格。没有 rates 时为 `UNCONFIGURED`。配置费率时，下列字段全部必填：

| 属性（以上述前缀开头） | 含义 |
|---|---|
| `provider-fingerprint` | 对应运行用量接口返回的提供方指纹；新部署须确认实际来源仍一致 |
| `version`、`source` | 费率版本及可核对的合同/价目表来源标识，不放凭据 |
| `currency` | ISO 币种，例如 USD；不代表仓库采用任何币种的实际费率 |
| `valid-from`、`valid-until` | UTC Instant，有效区间为左闭右开 |
| `rates[请求模型名].input-per-million` | 每百万输入 token 的非负十进制名义费率 |
| `rates[请求模型名].output-per-million` | 每百万输出 token 的非负十进制名义费率 |
| `rates[请求模型名].max-input-tokens` | 这组固定费率适用的最大输入长度，必须大于零 |

调用开始时核对运行冻结的费率卡，再按提供方指纹、请求模型和当时有效期选择并保存 `request_json.priceSnapshot`。调用结束只读取该快照，用提供商返回的 input/output token 和 BigDecimal 计算 `(input × inputRate + output × outputRate) / 1,000,000`，将结果与调用终态一同保存。查询不读取当前费率重算历史，迟到终态不能覆盖已记录估算。

金额始终标记 `CONFIGURED_FLAT_RATE_ESTIMATE`，按请求型号计价，不能称供应商实际账单。取消/失败只能得到部分估算；缺 token、缺卡、型号/来源/有效期不匹配或超出输入适用上限时不填金额并保留原因。只有提供商明确返回零用量或配置了真实零费率时，估算才可以为零。此公式不模拟阶梯价格、缓存折扣、批处理优惠、税费或服务商额外计费；有这些条件时仍是固定名义费率估算，不能用来宣称最终扣款或硬金额上限。重启更换费率卡后，旧运行保留原清单并拒绝继续执行，新的主动研究使用新卡。
