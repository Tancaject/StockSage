# 普通 Agent 路线的执行与验收

MARKET、FUNDAMENTALS、NEWS 使用服务端拥有的工具计划，取证后调用无工具领域 Agent，再生成无工具最终回答。只索取 K 线、不要求分析的问题省去财务取证、指标取证和领域报告步骤。DEEP 保持自己的持久化任务和验收流程。

## 请求参数

`ReadRequest` 将范围、K 线粒度、财报频率与数量带入 `ExecutionPlan`。当前消息的显式参数优先于模型改写；省略项来自消歧问题。模型只提供参数候选，工具名称仍由后端控制。

原文明确的股票代码或公司别名绑定本轮标的；模型改写成其他股票时停止检索和取证并返回澄清。只改周期或粒度的追问继承只取 K 线的请求，明确增加分析或指标要求才恢复领域分析。

需要澄清时一律不检索知识库、研究记忆，也不取数。参数缺失或无效、多标的不受支持、参数不一致、改写标的不符时直接返回固定澄清并记为 `BLOCKED`。只是意图没识别出来（细分意图 `UNKNOWN`、置信度低或信号冲突）时，不返回固定话术，而是交给最终回答模型：它带着本轮可见的对话记录、会话摘要和画像，加一条本轮说明（没有取数，能用之前的对话回答就答，需要最新数据或找不到时说明并请用户补充，历史数字不能当作当前数据，不向用户提后端、摘要等内部机制）。Trace 记一步 `Intent Clarification`。可见记忆受预算限制，找不到只能说当前可见记录里没有；模型据早先对话补全了标的也不会重新路由取数。交易请求没有独立的确定性拦截，依赖最终回答模型没有工具。

| 请求 | 实际调用约束 |
|---|---|
| AAPL 最近一周小时线 | `getIbkrHistoricalBars(AAPL, 1w, 1h)` |
| A 股最近两周日线 | `getStockKLine(code, daily, 14)` |
| 最近两个季度财报 | `getFinancialReports(code, quarterly, 1)`；数据层的第三个参数表示年，验收另检查季度频率及数量 |
| A 股/港股分钟或小时线、指定历史日期区间 | 返回澄清与 `BLOCKED` |

行情相对范围上限为一年。季度数据请求不能由年度数据替代。财报响应缺少可核验的报告数量时保留已有数据，但标记缺口。A/H 股既有技术指标工具只提供日线指标；美股明确要求指标时，在确定性计算工具缺失的情况下记录缺口，不能以 K 线冒充指标结果。

## 规划器信号与澄清评估

现有 `/api/eval/agent/planner` 和 `run_agent_eval.py` 保留路由、动作与来源断言，并支持用例可选字段 `expectedClarification`。它只断言 `RoutingDecisionMetadata.needsClarification`（包括 Coordinator 的标的安全覆盖），不包含聊天入口另行计算的 `ReadRequest` 参数澄清。未提供标签时不增加断言；执行失败或缺少元数据时，实际值及匹配结果为 `null`，不能当作“不需要澄清”。此类标签依赖完整规划路径，确定性模式将它视为 live-only 用例。

`signalDiagnostics` 保留每个实际来源置信度最高的候选，选择规则与融合策略共用；同分保留先到候选。每项仅含 `source`、`targetRoute`、`fineIntent`、`confidence`，不携带问题、实体、消歧文本或模型解释。出现某来源只表示观测到了它，不表示它参与了投票；NGRAM 的替代或平局用途仍由融合策略与理由代码解释。权重与阈值的权威实现见 [`IntentFusionPolicy`](../../stocksage-backend/src/main/java/com/stocksage/agent/intent/IntentFusionPolicy.java)。

汇总产物的 `planner.intent_calibration` 按预声明路线分层，报告澄清标签覆盖、实际澄清率、未澄清的路由错误率、候选与融合结果的配对正确率差，以及分数区间内的路由准确率。来源比较只纳入未澄清、非回退、未被执行安全闸门覆盖且标签未要求澄清的样本；保护性改写不能当作某个原始语义信号分类错误。旧产物没有诊断时保留 `NO_DATA`，不从 `sourceScores` 猜测候选路由。

这些指标的范围是规划器决定，不能代表实际工具错误执行率。置信分数也不是已校准的正确概率；来源与融合结果的差值是同样本描述，不是移除该来源后重新运行的消融收益。仍需带语义标签的真实结果和独立验证集，才能据此调整权重、阈值或信号组合。

## 证据和业务状态

普通新闻检索与网页搜索通过 `CapabilityGateway` 的注册表、只读策略和调用观察执行；未选中 Skill 时也不绕过该入口。后端按计划选择 `local.news.searchNews` 或 `local.news.webSearch`，allowlist 不接受模型提供的能力名。策略拒绝与未知能力直接终止调用；提供方失败形成证据缺口，截断结果不能作为完整证据或写入搜索知识。已有 Skill 的 MCP→本地 fallback 仍由清单拥有，本地失败后不重复直调工具。

`OrdinaryEvidence` 与 DEEP 共用 [`EvidenceEnvelopeMapper`](../../stocksage-backend/src/main/java/com/stocksage/evidence/adapter/EvidenceEnvelopeMapper.java) 的业务数据、来源、标的和哈希解析，`OrdinaryCompletionPolicy` 复用 `ResearchHarness`。财务响应必须含有业务数值（零值有效）；日期、计数和证券身份不能代替业务数据。K 线至少有一条包含有限开高低收数值的记录才能作为行情证据。没有新增模型 Judge 或自动恢复循环。

DEEP 的财务数据归入基本面快照，市场维度需要自己的行情或技术指标。旧策略 checkpoint 的证据必须在剩余恢复预算内重新取得，预算用尽则返回不评级；恢复次数和辩论授权继续沿用持久化记录。

| 状态 | 确定性判定 |
|---|---|
| `COMPLETED` | 本轮取证通过来源、标的、支持的参数/响应契约和上下文完整性检查；需要模型生成时，正文非空且供应商明确正常结束；有可引用证据时，最终回答必须引用本轮编号 |
| `DEGRADED` | 有可用数据，但存在部分失败、数量无法核验、领域分析未完成、最终回答截断或结束原因未知，或最终引用缺失/未知 |
| `BLOCKED` | 请求组合不支持，或标的/权限检查不通过 |
| `FAILED` | 未取得符合本次请求的可用数据，或最终生成失败、返回空正文 |

正常返回的空新闻结果与检索失败分别记录；前者可以完成“没有找到结果”的查询。API/生成流程的技术 `status` 和业务 `taskOutcome` 分开保存。回答引用 `[E1]` 等本轮证据编号，来源、采集时间、源时间字段和缺口进入 Trace。

取证完成由 `OrdinaryCompletionPolicy` 统一判断需求、来源、时效和必要内容是否纳入。存在可执行的既有备用步骤时返回 `RECOVER`，执行层补取一轮后重新验收；同一需求已被合格替代证据满足时，先前失败的尝试只保留在 Trace 中。答案生成与引用检查仍在生成结束后执行。

模型是否写完由 [`ModelCompletion`](../../stocksage-backend/src/main/java/com/stocksage/agent/runtime/ModelCompletion.java) 统一判断，保存供应商实际结束原因，区分正常完成、截断、未知和失败；流连接正常关闭或正文非空都不能单独证明生成完整。不完整的领域分析不进入最终回答上下文，已有证据缺口也不能因最终回答正常结束而消失。最终生成未完成时返回具体错误，Trace 保留生成状态；独立的答案质量评测仍按自己的标准进行。

这些检查不等于语义事实核验，也不保证供应商返回了请求区间内每一根交易 K 线。请求参数回显、来源时间字段不能代替交易日历覆盖率与数据新鲜度评测；答案质量仍需独立标签或评审。

### 可恢复的时间事实

已迁移的数据契约通过 [`EvidenceTiming`](../../stocksage-backend/src/main/java/com/stocksage/evidence/EvidenceTiming.java) 将时间事实附在证据账本中。行情保留日期或瞬时精度、粒度和已知/未知延迟；财务数据分开保存报告期间与披露日期，`period` 沿用响应的选择口径，不额外认证独立季度或最新披露覆盖；搜索结果分别统计瞬时、日期、未知时间的数量和范围。搜索聚合覆盖整批结果，证据头的来源和时间仍绑定同一篇文章。`fetchedAt` 与 `observedAt` 不替代业务时间，IBKR 请求处理耗时不用于推断行情延迟。

普通路线在上下文与逐项 Trace 中显示这些事实及路由的 `timeSensitivity`。DEEP 将时间要求写入任务提交载荷和 `AnalysisState`，恢复时以提交载荷为准；旧载荷缺失要求时保持 `UNSPECIFIED`，旧证据缺少时间事实时保持未知。

[`EvidenceFreshness`](../../stocksage-backend/src/main/java/com/stocksage/evidence/EvidenceFreshness.java) 根据这些事实和请求条件统一给出 `FRESH / STALE / UNKNOWN / NOT_APPLICABLE` 及原因。普通路线使用证据的观察时点，DEEP 使用账本最后观察时点；恢复同一账本不会随着系统时钟变化重新计算年龄。普通上下文、Trace 与 DEEP 快照展示实际判断；缺少时间契约保持 `UNKNOWN`，不从旧 `asOf` 猜测精度。

新闻按最新一篇的发布时刻或日期（北京时间业务日）判断：最新一篇在窗口内为 `FRESH`，全部在窗口外为 `STALE`，全部没有发布时间或出现未来时间为 `UNKNOWN`；混入的旧文章和无日期文章不让整条证据作废，逐篇日期已写在证据里。未请求窗口时，近期要求按 30 天、实时要求按 1 天判断。财务期间事实在历史或未指定时效的分析中不做最新性认证；近期要求下"新鲜"指拿到的是最新一份已申报报告：SEC 财务和知识库财报原文都附带申报索引中同类报告（年度 10-K、单季 10-Q）最新一份的申报日期，事实或段落的最新申报日期不早于它即 `FRESH`；结构化财务落后于最新申报为 `STALE`，原文段落都来自较早申报为 `UNKNOWN`。没有申报索引时（港股、A 股或索引读取失败）按市场披露期限估算：美股季报期末后 3 个月加 62 天、年报 1 年加 120 天；A 股年报次年 4 月底、其他定期报告两个月内；港股按半年报，中期两个月、年度三个月内。源数据须在评估前一天内取得。行情按市场时区与 bar 周期判断：A 股日线用交易所日历给出的最近交易日；没有日历的市场用两个工作日的宽限，港股长假仍可能降级。实时要求只在常规交易时段内检查延迟和 5 分钟内的时刻，休市时按最近交易日判断。具体窗口与原因以 [`EvidenceFreshness`](../../stocksage-backend/src/main/java/com/stocksage/evidence/EvidenceFreshness.java) 为准。

普通路线所需证据没有满足时效要求的合格替代时，完成状态降级，事实及有效引用仍保留。正常空搜索表示检索完成但无结果，不等于没有相关事件。DEEP 的同一论点只要引用了 `UNKNOWN` 证据，时效分最高为 0.5；引用 `STALE` 时为 0，并列入待解决问题，不能靠混入其他新证据恢复强评级资格。`FRESH` 和不适用时效的证据仍受既有时间范围评分约束。来源、标的与证据可用性验收独立保留；实施与验证进度见 [progress.md](../../progress.md)。

## 上下文预算

[`ChatPromptAssembler`](../../stocksage-backend/src/main/java/com/stocksage/conversation/ChatPromptAssembler.java) 负责提示顺序、字符预算、动态资料信任边界和引用格式，只接收已读取的上下文及明确日期。它不查询数据库、调用模型或写 Trace。`ChatService` 负责取得会话/画像输入，并将同一组装结果交给模型和答案归因记录；会话操作、流式传输和任务发布的职责不进入组装器。

[`ConversationMessageService`](../../stocksage-backend/src/main/java/com/stocksage/conversation/ConversationMessageService.java) 是会话 SQL 边界，依赖会话和消息两个仓储；`ChatService` 通过它进行归属校验和读写，不直接访问仓储。重生成裁剪旧分支、读取路由历史、写入当前问题及更新时间同事务提交，因此当前未回答问题不会进入路由历史，失败也不会只留下分支删除。助手消息和会话更新时间同事务提交；后台报告仍加入原有发布事务，并保留同一报告的去重规则。

数据库提交后，`ChatService` 才重建或添加 Redis 派生记忆；助手消息写入后提交后台压缩，用户请求路径和缓存重建不等待摘要模型。标题先异步生成，再以短事务校验归属并更新。取消流仅保存已经生成的非空部分答案及更新时间，保持不追加记忆、不生成标题、不提取画像的行为。会话删除先提交消息和会话删除，再清除派生记忆；MySQL 继续作为消息真源。

最终回答历史按角色限制单条长度，再从新到旧选入；助手历史正文前标注原回答日期，用户消息保持原样。滚动摘要单独留额并放在历史最前面，仍作为不可信资料。总历史额度由 `stocksage.chat.prompt.history-max-chars` 控制。用户画像在工具和 RAG 分配前预留所需空间，实际区段位置不变；RAG 只纳入完整段落，注明未纳入的段数。工具证据仍不可截断，`prompt-budget` 记录实际区段字数、装入前剩余预算和占比；预算规则由 `ChatPromptAssembler` 统一维护。

长期画像只从明确的第一人称自述提取：模型成功时采用模型结果，关闭或失败时使用严格规则；画像摘要合并现有内容。持仓仅通过显式撤销失效，Prompt 标注最后确认日期及可能已经变化；风险偏好和画像摘要继续按时间衰减。短期滚动摘要及覆盖到的消息 ID 保存在 MySQL 会话中，Redis 过期时直接恢复摘要和未覆盖消息。摘要输入按时间顺序分批，每批约 20000 字（含旧摘要和任务指令）；模型关闭或失败时完整保留旧摘要，仅截短新增内容。重生成删除已覆盖消息时，原摘要同步失效。Redis 窗口通过带 TTL 的临时列表和 `RENAME` 原子替换；压缩快照之后的并发追加仍可能被覆盖，完整历史以 MySQL 为准。

下表从 2026-10-20 至 23 日保存的评测产物中汇总历史调用的最大 token 观测，未改变字符预算机制。原始数据、模型分组和复现脚本见[本地用量记录](../../evals/results/context-budget-20261025/historical-token-summary.json)。各列分别取最大值，未必来自同一次调用；总量列是单次调用的实际总量，输出中已包含供应商计入的思考 token，不能重复相加。

| 调用点 | 最大输入 | 最大输出 | 单次最大总量 |
|---|---:|---:|---:|
| 路由 | 1750 | 365 | 1942 |
| Replan | 1659 | 1071 | 2729 |
| 普通分析师 | 3157 | 1812 | 4969 |
| 基本面分析 | 4532 | 11320 | 15345 |
| 普通最终回答 | 13394 | 6548 | 17910 |
| DEEP 取证循环 | 3627 | 358 | 3824 |
| Bull | 21843 | 16446 | 34355 |
| Bear | 21887 | 16510 | 32633 |
| 续停判断 | 25298 | 2749 | 27721 |
| 论点评分 | 27060 | 15009 | 40475 |
| Manager | 24942 | 12053 | 34529 |
| 向量化 | 32 | 0 | 32 |
| 摘要、画像提取、标题生成 | 缺用量记录 | 缺用量记录 | 无法计算 |

这份历史样本混合不同模型，不能证明理论最坏输入上限。实际模型名为 `deepseek-v4-pro` 的 MSFT 样本中，Bull、Bear、续停、评分、Manager 的最大输入分别为 14979、14989、18308、20895、17363；如果各次输出都用满 49152，总量估算分别为 64131、64141、67460、70047、66515。该估算以已观察输入为前提，不是未来调用保证。DeepSeek 官方没有独立思考 token 上限，记录标为 `NO_SEPARATE_LIMIT`，总输出约束采用实际 `max_tokens`；运行参数以配置接口和调用记录中的路由后选项为准。

[`ChatStreamSession`](../../stocksage-backend/src/main/java/com/stocksage/conversation/ChatStreamSession.java) 按请求持有 SSE 终态门、事件链路切换和心跳关闭信号；它不调用模型、读写会话或更新记忆。`ChatService` 保留回答完成、失败及取消时的业务操作，使用同一个终态门，并在普通回答结束后通知传输层清理。预取和模型订阅仍在弹性线程上执行，避免同步准备阻塞心跳；背压只允许丢弃心跳，工具事件和回答不使用丢弃策略。

普通领域分析提交后会发出等待分析的阶段提示；等待超时或请求中断会取消实际工作任务，请求中断不再继续最终生成。这保证本地中断传递，不代表供应商已停止远端推理或计费。普通回答断流时，当前页面保留已接收正文并标记未完成，复制和导出也保留该标记；错误分片没有新增半截回答持久化保证。

模型流式 HTTP 日志只记录请求标识、状态、响应头及首末数据到达时间、字节数和终止原因，不记录正文、思考内容或凭据。已观察到的传输错误优先于伴随的取消信号；SDK 读到结束标记后的正常取消本身不代表失败。对应证据与边界见[超时和断流诊断](../../evals/diagnostics/model-stream-20261002/RESULTS.md)、[睡眠中断与推理耗时的根因调查](../../evals/diagnostics/model-root-cause-20261002/RESULTS.md)及[其余超时与截断归因](../../evals/diagnostics/remaining-timeouts-20261003/RESULTS.md)。

新提交的 DEEP 任务由 worker 结束任务 Trace；SSE 取消不会发布任务终态或关闭其事件总线。观察既有任务时，传输层从请求 Trace 切换至任务 Trace，订阅结束只关闭独立的 observer Trace。后台受理答复完成后仍观察任务事件，直至任务终态、事件转发失败或客户端取消；转发失败即使通过错误分片正常结束，也保留 observer 的 error 状态。

同步降级与后台研究由同一研究管线管理阶段，其恢复、发布和交付差异见[研究入口与模块边界](research-execution-boundaries.md)。

K 线的跨语言字段、时间精度与单位语义见[行情数据契约](market-data-contracts.md)。
SEC 年度指标的期间、申报版本与数值单位见[财务数据契约](sec-financial-contract.md)。
A 股六类财务摘要的部分成功、完整期数和原始数值口径见[A 股财务摘要契约](a-share-financial-contract.md)。
港股长表的实际报告期、部分结果与单季口径限制见[港股财报契约](hk-financial-periods.md)。
新闻与网页检索的实际过滤窗口、供应商时间精度及来源绑定见[搜索契约](search-contract.md)。

每项工具证据按完整 JSON 字段/记录缩减，记录实际字符数、是否纳入、是否裁减与舍弃原因；裁减不能记为完整完成。领域 Agent 只接收有界证据，完整分析稿交由最终提示词总预算检查，不再局部截断。最终提示词先为近期历史分配空间，并为已命中的 RAG 预留预算；普通工具区段若整体无法装入，明确返回预算错误，不截断证据后继续生成。

Trace 的 `ordinary-evidence` 保存请求和逐项观察，`prompt-budget` 保存实际总字符数、历史字符数与动态上下文字符数。预算使用字符数，不把它当作精确 token 计费。

DEEP 在采集时将每条正文限定为 2,000–4,500 字符，每个维度（包括证据 ID、来源和边界）最多 20,000 字符，三个维度合计最多 60,000 字符。最多追加一项聚焦新闻补证。Bull、Bear 和 Manager 使用同一份有界正文，不再按整段前缀二次截断；引用只能指向快照中实际可见的证据内容。

## 真实接口评估

先启动 README 中的服务与所需数据源，设置专用账号环境变量 `STOCKSAGE_EVAL_EMAIL`、`STOCKSAGE_EVAL_PASSWORD`，再运行：

```powershell
python evals/run_ordinary_live_eval.py --output evals/results/ordinary-live.json
```

固定用例位于 `evals/ordinary_live_cases.jsonl`，覆盖显式周期、多轮改参、季度频率、不支持的粒度、历史区间和多标的边界。脚本真实调用 `/api/chat/stream` 与 `/api/trace/{id}`，检查实际工具参数、工具状态、引用和业务结果；保留数据集哈希、时间、实际最终模型、原始事件与 Trace。没有账号或服务不可用时输出 `BLOCKED`，不使用离线结果代替真实执行。

行情用例要求成功取得可引用数据，同时明确 `MARKET_SESSION_UNVERIFIED` 并返回 `DEGRADED`；失败取证、缺少时间契约或伪称 `FRESH` 均不能通过该用例。它验收的是执行和如实报告边界，不能据此宣称最新行情覆盖已完成。用例和评估器身份随该契约更新，旧评审不能直接用于新结果。

`execution_contract_pass_rate` 只描述这组已标注执行契约；没有人工评审时 `answer_quality` 为 `NO_DATA`。Planner 数据集继续独立评估路由，不能代替这里的 API 执行验收。

### 实际回答的人工质量评审

运行结果逐条保留用例定义、实际 SSE 答案及其哈希。后端 Trace 的 `answer-context` 提供实际消息序列、动态上下文和实际纳入的原始工具/RAG 证据；模型调用参数来自 `model-invocation`。先从同一份真实结果导出评审模板：

```powershell
python evals/ordinary_answer_quality.py --input evals/results/ordinary-live.json --review-template evals/results/ordinary-reviews.json
```

模板包含实际问题、答案、上下文和版本绑定。填写 `reviewer`、带时区的 ISO 格式 `reviewed_at`，再按模板内冻结的五维标准填写 `status` 与具体 `reason`。维度、状态适用范围和错误归属以 [ordinary_rubric()](../../evals/ordinary_answer_quality.py) 为唯一来源；标准变化后需要重新导出模板并复核，不能复用旧标签。脚本检查格式和绑定，不凭 `reviewer` 字符串验证人工身份，也不自动充当语义 Judge：

```powershell
python evals/ordinary_answer_quality.py --input evals/results/ordinary-live.json --reviews evals/results/ordinary-reviews.json --output evals/results/ordinary-reviewed.json
```

评审绑定用例、答案、完整文字 prompt、上下文、源证据和模型调用参数的哈希。答案或上下文变化后必须重新评审；缺失绑定、证据捕获不完整、仅有文字指纹的图片请求、无模型上下文的规则直答均不能获得质量通过。缺项为 `NO_DATA`，任一明确失败优先记为 `FAIL`。结果保留各维状态、有效样本数与评审覆盖率；`answer_quality` 表达复核的语义结果，`quality_evaluation.status` 同时要求完整执行与完整评审，原执行 `status` 不被改写。

### 成对比较与归因

对候选结果应用其自己的评审，并传入已评审基线：

```powershell
python evals/ordinary_answer_quality.py --input evals/results/candidate-live.json --reviews evals/results/candidate-reviews.json --baseline evals/results/ordinary-reviewed.json --output evals/results/candidate-reviewed.json
```

仅用例定义和实际可见源证据哈希相同的样本进入成对比较；不匹配样本列入 `unpaired` 并注明原因，不计入差值。保留双方 prompt、模型及参数身份，报告每个维度的有效配对数和通过率差；这不是自动判定方案更优的统计检验。原始证据变化需要重新冻结或重采样，不能把不同数据的结果解释为纯 prompt 收益。

`route_strata` 按用例预声明的路线分层，不按模型实际选中的路线分组。每层分别列出有效配对数、未配对原因、配对覆盖率和逐维差值，避免一条路线的改善抵消另一条路线的退化。用例定义发生变化时计入基线的预声明路线；仅候选存在的用例使用候选路线。`coverage_scope=exported_case_union` 表示覆盖率分母只是两份产物中已导出的用例并集，两边都未导出的计划用例无法由此统计，不代表完整数据集覆盖率。没有有效维度评分的层保持 `NO_DATA`；`PAIRED` 仅表示存在可比较评审，不表示质量非劣或可以删除某一 Agent 阶段。

普通 live 脚本会重新调用工具取证，不能直接用于同证据消融。下方冻结输入回放只改变已经生成的分析草稿是否进入最终回答；它不重跑规划、取证或分析师阶段。两个结果即使能够配对，也需要检查双方模型、prompt、历史与记忆差异；这份比较本身不能证明整个 Agent 链路的收益。

### 导出同输入的分析草稿消融候选

普通预取在写入完整成功的分析师草稿时记录其范围；组装器将范围映射到实际发送消息，经上下文标记清理后，以 Unicode code point 半开区间写入 `answer-context.analystSpan`，同时记录片段哈希。旧 Trace、失败或截断的分析、图片及 DEEP 路径不提供此标记。实际回答 prompt 与执行方式保持不变。

```powershell
python evals/ordinary_answer_quality.py --input evals/results/ordinary-live.json --analyst-inputs evals/results/analyst-inputs.json
```

该命令校验原始执行与输入绑定，再导出 `ordinary_analyst_inputs_v1`：基线为实际发送的消息，候选只删除显式范围内的草稿（含标题和尾部换行）。剩余消息、顺序、当前问题、日期、历史、RAG、研究记忆和画像均保持原样；不会重新组装提示词，也不会利用释放的空间补入更多资料。输出重算候选 prompt/context 哈希，源证据哈希保持不变。缺少完整标记或校验不通过的用例进入 `unavailable`。

`INPUTS_READY` 仅表示输入已准备好。产物中的 `source_model_invocations` 来自原调用；候选尚未运行，不能继承原答案、评审或用量，也不能当作候选回答传给成对比较。该导出只隔离草稿对最终回答的影响，不证明完整链路节省的耗时或质量非劣。

### 运行冻结输入回放

`POST /api/eval/agent/answer` 使用现有管理令牌边界，只接受白名单文本消息角色及模型层级。它复用 `Coordinator.streamAnswer` 的普通无工具回答路径，不读取或写入会话、报告或持久化 Trace。每次返回独立 `replayId`、输入指纹、答案、实际调用配置、供应商用量或 `NO_DATA`、耗时及失败类型；`completion` 保存生成状态与实际结束原因，未完成的正文保留供诊断，不记为成功回放。生成限时复用 `stocksage.agent.prefetch.timeout-seconds`，响应中的 `timeoutSeconds` 是本次实际生效值。

在本机环境变量配置 `STOCKSAGE_ADMIN_TOKEN`（自定义管理头时同时配置 `STOCKSAGE_ADMIN_HEADER_NAME`），然后运行：

```powershell
python evals/run_ordinary_answer_replay.py --input evals/results/analyst-inputs.json --output-dir evals/results/analyst-replay
```

运行前校验整份输入的源绑定，以及候选是否只删除指定草稿。基线与候选均调用当前部署重新生成，分别写入 `baseline.json` 与 `without_analyst.json`；相邻用例交替执行两支的先后顺序。请求配置不一致、输入指纹不符或生成失败时不进入有效配对；HTTP 响应丢失时停止且不重试，因为服务端可能已经调用模型。

结果使用独立的 `ordinary_answer_replay_eval_v1`，`execution_scope=FINAL_ANSWER_ONLY`，只覆盖已导出的可回放用例。其 `PASS` 表示回放输入与生成契约通过，不代表原始聊天工具执行通过或答案语义合格。`configuration_scope=OBSERVED_FINAL_ANSWER_REQUEST` 仅描述双方可见请求配置，不保证历史部署或供应商底层模型权重一致。用量与耗时均属于这两次最终回答生成，不代表删去分析师后整链路节省的资源。

对两个结果分别按上文导出并填写人工评审模板，再生成本次基线评审结果；候选比较使用本次 `baseline-reviewed.json`，不要使用原聊天答案的评审。回放结果也由 `ordinary_answer_quality.py` 处理，例如：

```powershell
python evals/ordinary_answer_quality.py --input evals/results/analyst-replay/without_analyst.json --reviews evals/results/analyst-replay/candidate-reviews.json --baseline evals/results/analyst-replay/baseline-reviewed.json --output evals/results/analyst-replay/candidate-reviewed.json
```

真实供应商结果、答案评审与多次运行稳定性仍须单独取得；离线检查不构成删除分析师阶段的依据。

`model-usage` 在流完成、失败或取消时记录最后一份供应商调用快照，整次调用只记录一次，不能逐 chunk 累加；缺失为 `NO_DATA`，失败或取消时保留的快照不代表供应商最终账单。此产物的质量范围为 `ordinary-final-answer`，用量范围仅为 `final-answer`，不代表意图、领域 Agent、记忆和整次 DEEP 的总成本。评审文件包含当前账号的实际上下文，按真实结果文件管理；本流程不调用外部 Judge 或上传评审内容。
