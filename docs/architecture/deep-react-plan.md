# DEEP 取证阶段有界 ReAct 方案

状态：M0～M5 已完成（2026-10-13，结果见文末）。取证循环已按用户确认默认开启（`STOCKSAGE_AGENT_DEEP_REACT_ENABLED` 默认 true），开启后替代单步补证。
前置文档：`deep-bounded-replan-plan.md`（单步补证，默认关闭，Phase 4 线上 A/B 未做）。

## 定位

三档执行方式中的第三档：

| 请求类型 | 执行方式 |
|---|---|
| DIRECT、简单取数 | 固定取数 |
| MARKET / FUNDAMENTALS / NEWS | P&E（见 `ordinary-plan-and-execute-plan.md`） |
| DEEP 开放式研究 | 取证阶段的有界 ReAct |

本方案只替换 DEEP 的补证环节，也就是从"基础取证并通过 Harness"到 `prepareHashes` 之间这一段。不动的部分：
- 外层任务队列、租约、checkpoint 和 fencing；
- Bull/Bear/Research Manager 辩论，以及确定性裁决；
- 报告的原子发布；
- 辩论角色仍然没有任何工具权限。

## 依据（已核对原文）

- ReAct（Yao et al., 2022, arXiv:2210.03629）：推理和行动交替进行，根据观察结果修正下一步。
- Anthropic《Building effective agents》：
  - workflow 是预定义代码路径，agent 由模型自己决定流程；
  - agent 用延迟和成本换效果；
  - 每一步都要从环境拿到 ground truth；
  - 需要设置停止条件，例如最大迭代次数。
- OpenAI《A practical guide to building agents》：
  - 循环的退出条件包括：工具调用、结构化输出、错误、达到最大轮数；
  - 超过失败阈值或遇到高风险动作时，交给人工。
- Spring AI 1.1：设置 `internalToolExecutionEnabled(false)` 后，模型发起的工具调用由应用自己执行，循环写成 `while (chatResponse.hasToolCalls())`。
- 通义千问 function calling：
  - 支持 `parallel_tool_calls`；
  - 思考模式下 `tool_choice` 只能是 auto 或 none，并且后续请求要回传 `reasoning_content`。所以循环里使用不开启思考模式的模型。
- 持久化执行：LangGraph 用 checkpointer 加 thread_id 按步保存状态；Temporal 把每次调用记入事件历史，恢复时按历史回放。本项目已有的"PLANNED → EXECUTED → REVALIDATED"是同一思路的单步版本。

## 设计

### 循环形态
- 使用原生 function calling，关闭自动执行。模型提出的工具调用先经服务端校验、写入 checkpoint，再通过 `CapabilityGateway` 执行。
- 每轮最多 2 个并行调用。
- 观察结果不传原始数据，只传压缩后的证据摘要：证据 ID、状态、缺口、来源和时间。这部分内容标注为不可信内容。
- 使用 STANDARD 档模型，不开启思考模式。

### 工具菜单
- 只读、枚举，参数由服务端校验：
  - 聚焦新闻搜索
  - 财报原文检索（知识库 RAG，按表单和主题）
  - 结构化财报（年报或季报、期数）
  - K 线（窗口、粒度）
  - 关系图谱查询（供应商、竞争对手）
- 另设 `finish_evidence(reasonCode)`，用于主动结束。
- 标的固定为当前标的。模型不能指定 ticker、capability ID、超时或结果条数。

### 停止条件（代码强制执行）
满足任一条件即停止：
- 模型调用了 `finish_evidence`，或某一轮没有任何工具调用；
- 达到最多 5 轮或 8 次工具调用；
- 超过 DEEP 总截止时间的分配份额，或超过 V17 模型调用配额；
- 连续 3 轮没有新增可用证据（停滞）；
- 校验失败、非法动作或被拒绝：零执行并停止。

上面的数值是初始值。M5 会记录每轮新增证据数，再根据数据调整。

### 持久化与恢复
- `EvidenceReplanState` 从单个动作扩展为步骤列表。每步依次保存为 PLANNED（记录确切调用和 effectKey）、EXECUTED、REVALIDATED。数据存在 checkpoint JSON 里，不改数据库结构。
- 任务被其他 worker 接管时，直接复用已经计划好的步骤，不重新调用模型。只读工具允许至少执行一次，结果按证据 ID 去重。
- 模型的每次决策都记入 `ModelInvocationStore`。
- 后台 worker 和 Redis 故障时的 inline 路径共用同一个服务。

### 第二版：关联实体扩展
用于链式问题，例如先查出供应商，再查供应商的情况。规则如下：
- 只有关系图谱返回、并且带出处的标的，才能成为新的查询对象；
- 这些证据记为关联实体证据，不并入目标标的的证据账。

第一版不做。

## 实施步骤

| 步骤 | 内容 | 验收 |
|---|---|---|
| M0 | 冻结 10～12 道 DEEP 题（其中 3～4 道需要多步取证），为每题标注必需分析维度；跑 A（补证关闭）和 B（单步补证）对照 | 拿到单步补证的真实收益，以及新证据被报告采用的比例 |
| M1 | 工具菜单：新增财报原文检索、关系图谱查询两项能力，加观察压缩器 | 非法参数、错误标的、未授权能力全部零执行 |
| M2 | 循环本体，含停止条件和预算 | 每种停止条件都能单独触发，并记录原因 |
| M3 | 逐步 checkpoint、接管恢复，后台与 inline 共用同一路径 | 在各个阶段杀掉进程后接管：不重新调用模型，证据不重复 |
| M4 | Trace、SSE 和前端时间线逐步展示 | 浏览器里亲眼核对 |
| M5 | C（ReAct）分别与 B、A 对照 | 达到默认开启门槛才开启 |

**默认开启门槛**：
- 必需维度覆盖率高于 B；
- 新增证据被报告实际采用；
- 非法或越权动作数为 0；
- p95 延迟和模型调用次数在预算内。

**M0 的分支判断**：如果单步补证带来的新证据基本没有被报告采用，说明问题出在证据进入报告的环节，而不是补证步数不够。这种情况下，先改证据摘要和报告的衔接，再做 M2。

## M0 结果（2026-10-13）

模型为 deepseek-v4-pro-0813（思考上限 8192）。题目是 GOOGL 反垄断、NVDA 出口管制、AAPL App Store，三题都是单跳。结果文件在 `evals/results/deep-m0/`。

| 题目 | A 盲评 | B 盲评 | B 补证 | 新增证据被采用 | 耗时 A / B |
|---|---|---|---|---|---|
| GOOGL | 3 | 3 | FOCUSED_NEWS_SEARCH 补到广告技术案补救（A 缺这一维度） | 1/1 | 476s / 653s |
| NVDA | 5 | 4 | STOP（INVALID_PLAN） | — | 692s / 575s |
| AAPL | 4 | 4 | FOCUSED_NEWS_SEARCH | 1/1 | 601s / 686s |

- 盲评合计 A 12/18、B 11/18。只有 3 题，而且两次运行之间新闻检索结果本身就不一样，这个差距不能算结论。
- 分支判断：新增证据被采用的比例为 1.0，问题不在证据进入报告的环节，按计划进入 M1/M2。
- 补证出现过一次 INVALID_PLAN，做 M1 前先查原因。

## M1 结果（2026-10-13）

- 补证 INVALID_PLAN 的原因：`normalizeQuery` 只要在检索词里识别出任意 ticker 就拒绝。目标公司自己的名字（"英伟达"），以及 DOJ、EU、TPU、DMA、ATT、AWS 这类缩写，都会被当成 ticker。已改为 `hasConflictingExplicitTicker`：只拒绝别名表里出现的其他公司。代价是 AMD 这类不在别名表里的代码不再被拦截。这类代码和 DOJ 这种缩写本来就分不开。`validatePlanState` 也同步做了修改。
- 新增 4 项只读能力，登记在 `capabilities/research-capabilities.yml`：
  - `local.filing.searchText`：知识库 10-K/10-Q，标的和表单作为过滤条件，主题只参与召回；
  - `local.relation.query`；
  - `local.fundamentals.getFinancialReports`；
  - `local.market.priceHistory`：美股用 IBKR，其他市场用普通 K 线。
  适配器自己再校验一次参数（`ResearchCapabilityArguments`），绕过菜单直接调用时也不会执行。
- `DeepEvidenceToolMenu`：
  - 共 6 个工具：新闻、财报原文、关系图谱、结构化财报、K 线、finish_evidence；
  - 标的由服务端填入；参数字段必须和定义完全一致，枚举和整数都要在范围内，主题最长 80 个字符、不能含控制字符、不能出现其他公司；
  - 财报原文和关系图谱只对 SEC 美股开放，美股不能选 quarterly；
  - `ALLOWED_CAPABILITIES` 作为网关 allowlist。
- `EvidenceObservationCompressor`：
  - 只输出证据 ID 前 16 位、维度、状态、来源、asOf，以及最多 5 条缺口；
  - 整段包在不可信标记里，外部文本会先去掉边界标记和控制字符。
- 验收：用 jshell 加载编译后的类逐项检查，共 38 项全部通过。
  - 菜单 26 项：合法调用 7 项；非法参数、错误标的、未知工具、不适用于当前市场的工具全部拒绝。
  - 适配器直接调用 9 项：都在用到依赖之前就以 DENIED 拒绝。
  - 网关策略 2 项：allowlist 之外的能力会被拒绝。
  - 压缩器防注入 1 项。
  - 另外后端能正常启动，说明 YAML 和适配器一一对应。
- 还没做：新能力连真实知识库、关系图谱的执行，会在 M2 循环接入后验证。
- M2 注意：现在的 STANDARD 模型 deepseek-v4-pro-0813 默认开启思考。思考模式下做工具调用，必须回传 reasoning_content，所以循环调用要显式传 `enable_thinking=false`，这和本方案"循环使用非思考模型"的设定一致。

## M2 结果（2026-10-13）

- `DeepEvidenceLoopService` 是循环本体，挂在 `DeepEvidenceReplanService.replan` 的入口处，后台 worker 和 inline 两条路径共用。开关 `STOCKSAGE_AGENT_DEEP_REACT_ENABLED` 默认关闭。已有单步补证状态的旧任务，仍然按单步路径收尾。
- 模型适配器 `DeepEvidenceLoopModel`：关闭 Spring AI 的自动执行，工具回调只提供定义；使用 STANDARD 模型，`enable_thinking=false`，temperature 0，最多输出 1024 个 token。角色 `evidence-loop` 已登记进 `AgentRuntimeConfiguration.ROLES`。
- 每轮的流程：
  1. 先把整轮调用全部校验一遍，有任意一个不合法就零执行并停止；
  2. 通过 CapabilityGateway 执行（allowlist 为菜单能力）；
  3. 用 `appendLoopEvidence` 入账，规则如下：
     - 结构化工具沿用基础取证的工具名解析；
     - 和已有证据载荷相同的不入账；
     - 快照预算不够的不入账；
     - 入账后重跑 Evidence Gate；
  4. 把压缩后的观察回传给模型。
- 停止条件和原因代码：
  - `FINISHED:<reason>`、`NO_TOOL_CALLS`
  - `MAX_ROUNDS`（5）、`MAX_TOOL_CALLS`（8）
  - `TIME_BUDGET`：循环自身 4 分钟，并且要给后续流程留出 15 分钟；
  - `MODEL_CALL_QUOTA`：给辩论和打分留出 16 次调用，剩余次数由 `ModelInvocationStore.remainingModelCalls` 只读查询；
  - `STAGNATION`：连续 3 轮没有新增证据；
  - `REJECTED:<code>`，包括 `TOO_MANY_PARALLEL_CALLS`、`FINISH_WITH_OTHER_CALLS`；
  - `MODEL_TIMEOUT`、`MODEL_ERROR`、`CAPACITY_UNAVAILABLE`、`HARNESS_NOT_PASS`。
  每轮和停止时都写 Trace：`stepKind=loop_round` 和 `loop_stop`。
- 验收：
  - 用 jshell 加 Mockito 逐个触发上面的停止条件，18 项全部通过；所有非法调用的网关调用次数都是 0。
  - 真跑 1 题（nvda_supplier_capacity，deepseek-v4-pro-0813）：
    - 第 1 轮：关系图谱 SUPPLIER + 新闻；
    - 第 2 轮：10-K 原文 + 新闻；
    - 第 3 轮：模型调用 `finish_evidence`，停止原因 `FINISHED:SUFFICIENT`。
    - 新增 4 条证据，全部被报告证据表引用（关系图谱 1 条、10-K 风险因素 1 条、CoWoS/HBM 产能新闻 2 条）。
    - 结果为 FULL_REPORT，全程 741 秒。
    - 循环的 3 次模型调用合计约 6.4k token，执行两轮工具分别用了 6.7 秒和 12.1 秒。
- 已知限制：
  - 还没有逐步 checkpoint（M3），中途崩溃后接管会重跑整个循环（证据按载荷去重）。M3 完成前不要在生产开启。
  - 一旦配置 `stocksage.research.token-budget`，`ModelInvocationStore` 会拒绝带 extraBody 或 tools 的调用。这会同时影响思考上限和循环，开启前要先改这一处。

## M3 结果（2026-10-13）

- `AnalysisState.evidenceLoopState` 记录每一轮、每个调用的状态，跟证据账本放在同一个 checkpoint 里，没有改数据库结构。每个调用保存以下字段：
  - toolCallId、工具名、原始参数、effectKey；
  - 状态（PLANNED / REVALIDATED）、结果；
  - 新增证据 ID，以及回传给模型的压缩观察。
- 落盘时点：
  1. 一整轮调用校验通过后，记为 PLANNED；
  2. 每个调用执行完、证据入账后，记为 REVALIDATED，证据账本和快照一起写入；
  3. 一轮全部做完后更新停滞计数；
  4. 循环停止时，记为 STOPPED 并写入原因。
- 接管时：
  - 已经 STOPPED 的直接返回；
  - 还是 PLANNED 的调用，先重新校验（参数、effectKey 都要一致），再执行；
  - 对话历史从已保存的轮次重建；
  - 初始提示里的"基础证据"会去掉循环自己新增的证据，保证重建出来的对话和第一次运行时一致。
- 只有一种情况会多调一次模型：模型已经返回、但 PLANNED 还没落盘时进程崩溃，接管后会重新请求这一轮。LangGraph 也是同样的语义。
- 验收：
  - Mockito 结合 Jackson 真实序列化往返，模拟 10 个崩溃点，全部通过：
    - 8 个落盘点之后崩溃，模型和工具都不会重复调用，账本里没有重复证据；
    - 工具执行中途崩溃，只补跑这一个工具；
    - PLANNED 落盘前崩溃，模型多调一次。
  - 停止条件 18 项重测，全部通过，每种原因都以 STOPPED 落盘。
  - 真实杀进程测试（生产默认参数，NVDA 供应商题，task 53）：
    - 第 1 轮完成后杀掉后端，大约 3.5 分钟后被接管；
    - Trace 里有 `loop_resume`，记录 completedRounds=1；
    - 第 1 轮没有重新请求模型，被杀时正在进行中的第 2 轮决策请求了一次；
    - 最终 FULL_REPORT，证据快照 11 条，ID 两两不同。
- 配套调整生产默认值，写在 `application.properties`：
  - 租约 TTL 从 30 分钟改为 3 分钟；
  - 停滞判定从 15 分钟改为 3 分钟；
  - 恢复扫描从每 5 分钟改为每 1 分钟；
  - 心跳仍为 60 秒（TTL 的 1/3，上限 60 秒）。
  原来的默认值下，任务崩溃后要 15～20 分钟才会被接管，30 分钟的预算基本已经用完。
  `queue.claim-min-idle-ms` 保持 30 分钟不变：接管走的是数据库标记加重新入队，缩短它反而会让健康任务被反复领取。

## M4 结果（2026-10-13）

- 后端：
  - 循环写每条 Trace 时，把同一份 attributes 作为 SSE 的 metadata 推给前端，涉及 `loop_round`、`loop_resume`、`loop_stop` 三种步骤；
  - `ChatStreamEmitter` 新增可带 metadata 的 `emit` 重载；
  - 原来逐轮推送的纯文本提示已删除。
- 前端 `buildResearchTimeline` 按 `stepKind` 识别循环步骤，新增三类时间线行：
  - "补充取证 · 第 N 轮"：显示用了哪些工具、结果，以及新增几条证据；被拒绝的轮次用警示色标出；
  - "从断点恢复"：只在接管续跑时出现；
  - "补充取证结束"：显示中文停止原因，以及轮数、调用数和新增证据数。
  流式块和 Trace 回放中，同一步可能出现多次，按 `stepKind` 加轮次去重。
- 验收：在聊天页用 Playwright 驱动本机 Chrome，真跑了一题（NVDA 供应商，FULL_REPORT），亲眼核对了截图：
  - 流式过程中，循环结束后三行同时出现，在"数据获取"和"综合分析"之间；
  - 完成后刷新页面、重开会话，从 Trace 回放出来的三行与流式时一致，没有重复。
- 发现的已有问题，本次未修改：
  1. `summarizeDataStage` 会扫描所有 observation 中的"失败/部分/超时"等词，多空辩论的论点文本也会被当成数据告警。本次在"数据获取"一行显示出了 `[pt-…]`。
  2. DEEP 任务受理后，"已受理"文本被当成回答，"生成结论"一行过早出现。

## M5 进度（2026-10-13，未完成）

- 计划：C 组跑 3 道单跳题；A、B 两组各补跑 1 道多步供应商题；C 组的供应商题复用 M2 的 task 52（`results/deep-m5/supplier_c.json`）。评分脚本 `deep_m0_score.py` 已支持 `--c`，三组随机标为 X/Y/Z，两组时的输出与 M0 一致。
- 已完成：C 组 GOOGL 跑通，结果为 FULL_REPORT，用时 800 秒；循环 3 轮（新闻 + 10-K），以 `FINISHED:SUFFICIENT` 结束。
- 中断：C 组 NVDA 出口管制题运行期间，电脑睡眠了 43 分钟（Hikari 报告时钟跳变 43m15s），30 分钟预算耗尽，任务 FAILED。这与代码无关，需要重跑。睡醒后 IBKR 交易会话断开：`reauthenticate` 无效，`ssodh/init` 必须带 compete=true，需要用户确认，评测因此暂停。
- 待办：IBKR 恢复后，补跑 C 组 NVDA 和 AAPL，以及 A、B 两组的供应商题（`run-m5.ps1`；先删掉 `c_react.json` 中的 NVDA 失败行，脚本会跳过已完成的题目）。之后盲评，按默认开启门槛判定。

## 预算与新选项的兼容（2026-10-13）

- 之前的问题：配置 `stocksage.research.token-budget` 后，`ModelInvocationStore` 会以 "Unbounded" 为由拒绝所有带 extraBody 或 tools 的调用。而现在每次 DEEP 调用都带 thinking_budget，取证循环还带 tools，所以一开启预算，DEEP 就全部失败。
- 改动：新增 `ModelTokenBudgetProperties.requireBoundedChatRequest`，只放行能被"输入+输出+思考"上限覆盖的选项：
  - n 只能为 1；
  - 校验供应商路由后的请求选项，`max_tokens` 不超过输出上限加思考上限；extraBody 允许百炼的 `enable_thinking` / `thinking_budget` 和 DeepSeek 的 `thinking`，具体语义见[调用记录](research-execution-boundaries.md)；
  - 带 tools 时必须 `internalToolExecutionEnabled=false`。
  - 缺少标志的旧记录一律按"存在"处理，因此仍会被拒绝。
- `ModelInvocationAdvisor` 额外记录 extraBody 的键名（不记录值）和 `internalToolExecutionEnabled`。
- 验证：用 jshell 跑了 13 项。11 项是放行或拒绝的边界用例；另外 2 项用 advisor 实际记录下来的思考请求和循环请求，确认它们会被放行。

## M5 结果（2026-10-13）

完整报告跑法每题约 2.4 元，99% 花在辩论和报告上。因此 M5 新增了评测专用的"只跑证据阶段"模式（`stocksage.research.eval.evidence-only`，结果类型 `EVIDENCE_ONLY`，写回证据摘要），每题只需几分钱。用这个模式在同一套 4 道题上对比了 A（不补证）、B（单步补证）、C（取证循环），结果在 `evals/results/deep-m5-evidence/`。

| 题目 | A | B | C |
|---|---|---|---|
| GOOGL 反垄断 | 2 | 2 | 5 |
| NVDA 出口管制 | 2 | 3 | 5 |
| AAPL App Store | 2 | 3 | 5 |
| NVDA 供应商（多步） | 1 | 3 | 6 |
| 合计（满分 24） | 7 | 11 | 21 |
| 有证据直接支撑的维度占比 | 0% | 17% | 75% |

默认开启门槛：
- 必需维度覆盖率高于 B：21 对 11，达标。
- 新增证据被报告实际采用：用 3 次完整运行统计，循环新增 14 条证据，其中 10 条被报告引用（71%），达标。
- 非法或越权动作数为 0：今天所有循环都以 `FINISHED:SUFFICIENT` 结束，没有出现 REJECTED，达标。
- p95 延迟和模型调用次数在预算内：证据阶段 C 用时 39～58 秒，B 为 38～42 秒，A 为 21～32 秒；循环每题 2～4 次模型调用，费用不到全流程的 1%，达标。

局限：
- 只有 4 道题。
- 证据名称（`loop*`、`focusedNewsSearch`）会暴露组别，所以不算真正的盲评。
- 只有我一个评审，而 C 是我实现的，可能偏向 C。
- 摘要每条只保留正文前 500 字，打分主要依据标题和主题。
- 盲评时发现关系图谱和财报原文的空结果被计为可用证据，C 的证据条数因此偏高（打分按内容，不受影响）。已修复：结果为空时返回空内容，证据记为 EMPTY。

同期修复：辩论单个论点缺必填字段或字段超长时，改为只舍弃该论点，不再让整轮失败（C 组完整跑法下的 NVDA 曾因此失败）。

