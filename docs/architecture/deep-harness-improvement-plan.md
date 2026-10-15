# DEEP 证据与上下文改造方案

状态（2026-10-14）：用户确认先改代码、不跑评测。A1–A3、B1–B5、C1–C3、D1–D3、E1、G1、G3 代码已完成并通过编译和离线检查；未做 A4（可选）、B6（入口过滤已覆盖主要来源）、E2/E3（改变续停和评分输入，需评测兜底）、F、G2、H。实施记录见 progress.md 2026-10-14 条目。依据是 10-14 的 harness review 和复核：读源码，解析 10-13 冒烟 4 题的证据快照，统计本地库中 7 次完整 DEEP 运行的调用记录、Trace、报告和最终消息。原则沿用 [AGENTS.md](../../AGENTS.md) 的评测原则；评测入口沿用 [评测体系方案](eval-system-plan.md)。

## 现状问题

### 1. 基础证据是一套不看问题的固定模板，且呈现有缺陷

美股基础证据由 `DeepEvidenceCollector.buildFundamentalsSnapshot / buildMarketSnapshot / buildNewsSnapshot` 生成：

| 证据 | 实测情况 |
|---|---|
| SEC 年报 5 年（9 项指标） | 原始 companyfacts JSON 进快照，每个数据点重复 source_url、accn、frame 等字段；4500 字上限只露出营收、净利润、总资产 3 项，共 14 个数据点。9 项本身也没有资本开支、自由现金流、现金、股本、分部数据 |
| 网页"财报"搜索 | 美股查询被拼成"代码 财报 … HKEXnews 巨潮资讯"（`FundamentalsTools.buildReportSearchQuery`），4/4 题返回别家公司的"财报"聚合页，仍被记为该标的的可引用证据 |
| IBKR 3 个月日线 | 原始 JSON 从尾部截断（`compactStructuredContext` 不处理 K 线），最近约两周缺失；证据头 asOf 却是运行当天，报告 dataFreshness 照抄 |
| IBKR 6 个月日线 | 占"技术指标"的位置，与 3 个月重叠，截断后只剩最早约 1.5 个月 |
| 估值 | 美股没有任何数据源（data-service 对美股财务指标返回不支持），也没有技术指标计算 |
| 10-K 原文 | 基础取证不取，只能靠取证循环 |
| 7 天个股新闻 / 网页搜索 | 前者每题只有 1 条泛泛的公司新闻；后者与按问题搜新闻重叠 |

快照内容分类（4 题平均，每题约 4.7 万字）：格式开销 41%（证据头、JSON 外壳、SEC 冗余字段、运维说明），弱相关 21%（截断的原始 K 线、泛泛的新闻），无关 4%，有用 34%。有用部分约 1.6 万字，其中约 1.2 万来自取证循环；基础取证的正文只有约 18% 有用。

7 份报告的后果：估值判断全部来自新闻片段，最新价从新闻文章里摘，有一份报告的行情证据只写了"价格呈现明显波动"，没有一份把缺少估值数据列为未知项。

### 2. 能进 DEEP 的问题，关注点类型多，模板覆盖不了

实际进入 DEEP 管线的只有"单标的投资判断 + 一个关注点"。比较类在 Coordinator 被改成澄清，组合诊断没有标的会被阻断。关注点按路由评测集分为：事件/监管/供应链、估值、财报反应、"现在值得买吗"、银行等特殊行业、风险分析。只有第一类和风险分析能靠取证循环补齐；估值、财报反应、"现在买"缺数据（无估值、无美股季度、行情过期）；银行的通用指标口径不对（推断，未实跑）。

### 3. 辩论上下文：同一份脏证据被 8 次调用各读一遍

7 次完整运行平均每题：约 17.4 万输入 token、6.8 万输出 token；通常辩 2 轮，共 8 次调用（Bull、Bear、续停各 2 次，评分、综合各 1 次）。证据约占输入的 70%。续停只输出 CONTINUE/STOP，却读全部证据和 1.4 万字辩论，16 次调用合计 33 万输入 token，是输入最多的角色。各角色系统提示词不同、轮次号放在证据之前，前缀缓存基本命不中。按 DeepSeek 官方价，输出（主要是思考）约占费用一半多。

### 4. 可观测性：看得到"发生了什么"，看不到"模型看到和说了什么"

已记录：工具调用、Harness 判定、取证循环每轮、辩论摘要（约 1100 字/方/轮）、续停理由、评分、调用账本（角色、参数、提示词哈希、token、费用估算）、完整证据快照、报告。

缺：原始提示词和输出（只存哈希，Bull/Bear 完整 JSON 只留摘要）；成功后 checkpoint 被删，结构化辩论消失；快照截断/丢弃不留痕；思考 token 和缓存命中 token 不记录（SDK `CompletionTokenDetails.reasoningTokens`、`PromptTokensDetails.cachedTokens` 都有）；无法回答"哪次调用实际用了哪几条证据"。

### 5. 评测：最贵、最该证明的辩论和报告层没有评测

成绩单没有 `deep_report` 层；"多轮辩论比单轮或单 Agent 好多少"没有数据；评级由 Manager 打分经 Java 阈值换算，打分开着思考，评级稳定性从未测过；DEEP 证据层已饱和（每题 10～13 条，循环组接近满分）。

## 目标

1. 证据：每题快照约 2 万字，覆盖财务全表、估值、最新行情摘要和问题相关证据；无他公司内容；最新行情距 asOf 不超过 3 个交易日。
2. 成本：在不降模型档和思考预算的前提下，整题费用降三到四成（测量口径见 A1）。
3. 可观测：评测模式下任一 DEEP 运行可完整复盘每次调用的输入输出；截断、丢弃有记录。
4. 质量：用冻结证据回放给出"新证据 vs 旧证据""多轮辩论 vs 1 轮 vs 单 Agent"的配对结论和评级稳定性。

## 不做的事

- 不降生产模型档和思考预算；思考是否浪费由 F2 的数据决定。
- 不把 DEEP 改成自由 ReAct，不改外层任务队列、租约、checkpoint 和原子发布。
- 不新增治理层；生产环境继续只存提示词哈希，完整输入输出只在评测模式保存。
- 不做多标的 DEEP、组合诊断；它们仍按现状澄清或阻断。
- 不砍辩论轮数、不改 Bull/Bear 角色分工，除非 F2 有配对证据。

## 实施步骤

### A 可观测性（先做，后面所有验证依赖它）

| 步骤 | 内容 | 改动点 | 测试点 |
|---|---|---|---|
| A1 | 调用账本记录思考 token 和缓存命中 token；费用估算区分命中与未命中 | `ModelInvocationAdvisor`（读 `completionTokenDetails.reasoningTokens`、`promptTokensDetails.cachedTokens`）；费率卡增加命中价；DeepSeek 官方 API 先核对原始响应是否带 `prompt_tokens_details.cached_tokens`，不带则在 `ProviderRoutingChatModel` 处补读 `prompt_cache_hit_tokens` | 跑 1 题 EVIDENCE_ONLY 加 1 题完整运行，账本字段非空；缺字段时为 NO_DATA 不填 0 |
| A2 | 快照截断、丢弃写 Trace，并回传给取证循环 | `DeepEvidenceCollector.appendEvidenceSnapshotItem / appendLoopEvidence` 返回截断与丢弃原因；`EvidenceObservationCompressor` 输出原因 | 构造超预算证据，Trace 出现截断原因；循环观察里能看到"已截断 / 预算已满 / 重复" |
| A3 | 评测模式保存每次调用的完整输入输出，评测任务不删 checkpoint | 请求级评测标记（沿用 EVIDENCE_ONLY 的管理员令牌校验）；`ModelInvocationStore` 在评测模式下写原文（独立列或独立表，V20 迁移）；`deleteCompletedCheckpointBestEffort` 对评测任务跳过 | 不带令牌时生产行为不变、不落原文；带令牌的任务能按调用 ID 取回输入输出和结构化辩论 |
| A4（可选） | 管理接口拼出一次 DEEP 的完整轨迹：取证、循环、辩论、评分、报告、用量、阶段耗时 | `AgentAdminController` 新增只读接口，复用现有表 | 对一个评测任务输出可读轨迹，人工核对与 Trace 一致 |

### B 证据呈现与基础证据选择

| 步骤 | 内容 | 改动点 | 测试点 |
|---|---|---|---|
| B1 | 行情：最新优先的确定性摘要（最新收盘、1/3/6/12 个月涨跌、距 52 周高点、波动率、相对 SPY 或 QQQ）加最近 20 根日线；删掉 6 个月原始序列 | `DeepEvidenceCollector.buildMarketSnapshot`；摘要计算放 data-service 或后端新的确定性方法（与普通路线共用）；基准指数行情经 IBKR 取 | 最后一根 K 线距 asOf ≤ 3 个交易日；摘要数值与原始 bars 逐项核对；A/H 股 K 线截断方向同时核对 |
| B2 | SEC 财务压成多年度紧凑表，来源只写一次；补资本开支、现金、稀释股本 | data-service `edgar_service._XBRL_CONCEPTS` 加 3 个概念；DEEP 侧渲染紧凑表（可复用 `OrdinaryEvidence` 的 SEC 压缩思路，但保留全部年份） | 9+3 项全部可见；数值与 companyfacts 抽检一致；辩论引用的摘录仍能逐字命中 |
| B3 | 美股删网页"财报"搜索；已入库标的改为按用户问题从知识库取 10-K 段落 2 段；A/H 股修正查询 | `buildFundamentalsSnapshot`；`FundamentalsTools.buildReportSearchQuery` 去掉美股分支里的港股、A 股站名 | 4 题快照中无他公司内容；未入库美股标的走降级说明而不是网页搜索 |
| B4 | 新闻：保留按问题搜索；跨工具按 URL 去重；正文不提标的名称或代码的结果剔除；7 天个股新闻只列标题；删与之重叠的网页搜索 | `buildNewsSnapshot`；标的名称取 `TickerResolutionService` 别名 | 重复 URL 为 0；剔除率记录进 Trace |
| B5 | 压缩证据头：timing 改紧凑写法，去掉 Java toString；运维说明合并为一行 | `appendEvidenceSnapshotItem`、`appendNonCitableSnapshotNote` | 证据头占比从约 19% 降到 8% 以下；`DebateContractParser` 仍能定位所有证据段 |
| B6 | 内容级标的检查：证据正文不涉及目标公司时记违规 | `DeepResearchCompletionPolicy` 新增规则，按设计文档 0.6 的规则分级先 SHADOW，只记 Trace | SHADOW 期间统计命中与误杀；达标后再讨论执法 |

B 完成后用 EVIDENCE_ONLY 跑现有 11 题，比较新旧快照的字数、分类占比和维度评审分。

### C 补缺失的数据能力

| 步骤 | 内容 | 改动点 | 测试点 |
|---|---|---|---|
| C1 | 估值：用 IBKR 最新价和 XBRL 的 EPS、股本、自由现金流算市盈率、市销率、自由现金流收益率，附 5 年区间；明确口径为"最近财年" | 后端确定性计算（与 B1、B2 同处），作为 VALUATION 维度证据进基础快照 | 抽 3 只股票与公开数据交叉核对，误差在口径说明范围内 |
| C2 | 美股季度财务：10-Q 的 XBRL 数据，加入取证循环菜单 | data-service companyfacts 按 10-Q 过滤；`DeepEvidenceToolMenu` 放开 SEC 的 quarterly | "最新财报反应"类题能取到最近季度数据 |
| C3 | 特殊行业最小处理：按 SIC 识别银行和保险，通用指标中不适用的标注不适用，并补净利息收入等 2～3 个概念 | data-service 概念表按行业分组 | 跑 1 道银行题，快照里没有口径错误的指标 |

### D 取证循环

| 步骤 | 内容 | 改动点 | 测试点 |
|---|---|---|---|
| D1 | 观察里加每条新证据的标题、约 300 字片段，以及已有数据的覆盖范围（如"已有 SEC 年报 5 期"），避免重复取数 | `EvidenceObservationCompressor`、`DeepEvidenceLoopService.executeCall` | 重复取数（如已有 5 年年报又取 3 年）在 11 题中为 0 |
| D2 | 维度清单：第 1 轮声明 2～4 个必答维度，`finish_evidence` 逐维度说明已覆盖还是确认缺失；清单写入 checkpoint 和 Trace | `DeepEvidenceToolMenu` 新增声明工具；`EvidenceLoopState` 加字段 | 模型清单与评测金标维度的重合率；接管后清单不丢 |
| D3 | 菜单加入 C1、C2 的能力 | `DeepEvidenceToolMenu`、`research-capabilities.yml` | 非法参数仍零执行 |

维度清单是否传给辩论和报告门，等 F2 有数据后另行决定。

### E 辩论上下文

| 步骤 | 内容 | 改动点 | 测试点 |
|---|---|---|---|
| E1 | 共享前缀：各角色共用一个 system；用户消息统一以逐字节相同的"问题 + 证据块"开头，轮次、辩论历史、角色说明、输出格式全部放后面；统一各角色的证据标题措辞 | `AgentConfig` 各角色客户端；`BullResearcher`、`BearResearcher`、`ResearchManager` 的提示词模板 | A1 统计命中率；F2 回放验证质量不退 |
| E2 | 续停只带辩论和未回应论点，不带全文证据 | `ResearchManager.buildContinuationPrompt` | F2 回放：续停决定与原方案的一致率、轮数分布、最终评级变化 |
| E3（可选） | 评分和综合只带被论点引用过的证据 | `buildScoringPrompt`、`buildPrompt` | 同上，且引用核对规则不变 |

### F 评测

| 步骤 | 内容 | 改动点 | 测试点 |
|---|---|---|---|
| F1 | 冻结证据回放：按证据快照 ID 直接进入辩论和报告；评测模式下评分连跑 3 次，只用第 1 次定级，其余记 Trace | `DeepResearchPipeline` 新入口（请求级评测模式）；`ResearchDebateService` 评分循环 | 同一快照两次回放的输入哈希一致；不带令牌时不可用 |
| F2 | 实验（8 题，同一批新快照）：① 旧证据 vs 新证据；② 完整自适应辩论 vs 强制 1 轮（`STOCKSAGE_AGENT_DEBATE_MAX_ROUNDS=1` 单独重启）vs 单 Agent 直接写报告（评测专用提示词）；③ 评级翻转率；④ E1、E2 前后对照 | 只运行；单 Agent 组需要一个评测专用提示词 | 成对盲评由用户评 8～10 对，兼作评审校准集；其余由已校准评审判；报告差值与噪声 |
| F3 | `deep_report` 层接入 `run_daily_eval.py` 的 change 档，改辩论、报告提示词时自动跑 | `run_daily_eval.py` 的 `CHANGE_MAP` 与层定义 | `--since` 命中辩论文件时选中该层 |
| F4 | DEEP 题集按关注点补题：估值、财报反应、"现在买"、银行、风险、事件，各 2～3 题，按公司分开发集与留出集；加确定性的来源质量统计（权威来源占比） | `evals/deep_react_cases.jsonl`、维度评审 | schema 校验；开发集与留出集无公司重叠 |

### G 相关但独立：普通路线

| 步骤 | 内容 | 改动点 | 测试点 |
|---|---|---|---|
| G1 | FIXED 模式按问题里明确提到的数据需求确定性补步骤，零模型调用 | `Coordinator` 生成 `OrdinaryPlan.fixed` 时按 `DataRequirement.coveredBy` 的映射补动作 | 跨类别 4 题的需求覆盖率与 DYNAMIC 对比；单标的回归 35 题不退 |
| G2 | 分析师阶段消融：另跑 10～15 道单标的题做来源，用现成工具链回放 | 只运行 | 质量不退才删分析师阶段 |
| G3 | 普通路线的 K 线也给最终回答一份确定性摘要（现在只给首尾各 2 根） | `OrdinaryEvidence.compact` 复用 B1 的摘要 | 行情题回答引用摘要数值 |

### H 清理（单独拍板）

- 删除 inline 兜底：只在 Redis 入队失败时触发，而会话也存在 Redis。
- 删除单步补证：等旧 checkpoint 排空；删除后 B 组对照无法复现。
- `research-harness-design.md` 改为一页现状说明，旧版归档；`progress.md` 按月归档。
- 不变量回归网三选一：评测脚本可用本地 Mockito、恢复只含这几十项的最小测试集、或至少把 jshell 验收脚本存进仓库。现有 Java 评测驱动是单文件启动、只带运行期 classpath，手写假对象覆盖不了取证循环。

## 顺序与门槛

```
A1 → A2 → A3 ─┐
B1～B5 ───────┼→ EVIDENCE_ONLY 11 题对比 → C1 → C2/C3 → D1～D3 → EVIDENCE_ONLY 复测
              └→ F1 → F2①（旧证据 vs 新证据）
E1、E2 → F2②～④ → F3 → F4
G、H 与上面独立，可穿插
```

| 门槛 | 判定 |
|---|---|
| B 完成 | 4 题快照：最新 K 线距 asOf ≤ 3 个交易日；SEC 指标全部可见；无他公司内容；每题快照 ≤ 2.5 万字；EVIDENCE_ONLY 维度评审分不低于旧快照（差值超出噪声才算提升） |
| C、D 完成 | 估值、财报反应两类题的必答维度有直接证据；重复取数为 0；循环调用次数与耗时在现有预算内 |
| E 采用 | F2 中质量不退（差值落在噪声内即视为不退），且 A1 统计的整题费用下降 |
| 辩论结构是否调整 | 只看 F2② 的配对结论；没有显著差异时保持现状，只在对外表述中如实说明 |

## 预算

- B～D 的验证：EVIDENCE_ONLY 每题约 0.05 元，11 题 × 2～3 轮约 2 元。
- F2：完整 DEEP 每题约 2.4 元。8 题 × 新旧证据 2 组 + 1 轮组 + 单 Agent 组 + 评分重复，约 50～60 元；E1 生效后会更低。开跑前按预检规则逐题列前提和预估费用。
- G2：约 10～15 元。
- 用户参与：F2 盲评 8～10 对，约 1 小时。

## 风险

- 证据格式变化会改变数据快照哈希，旧报告不再被复用；旧 checkpoint 仍按旧格式恢复，不受影响。
- 紧凑表和摘要必须保持证据段标记不变，否则辩论的逐字引用校验会失败；B1、B2 的测试点覆盖这一点。
- 估值按最近财年计算，与市场常用的 TTM 口径不同，必须在证据里写明口径，报告不能把它说成实时估值。
- 内容级标的检查可能误杀提到子公司或品牌名的结果，所以先 SHADOW。
- F2 样本只有 8 题，只能报逐题结果和方向，不报百分比提升。

## 需要用户拍板

1. 整体顺序和范围（A～F 是否全部做；C3 是否做）。
2. A3 新增评测模式原文存储（一次 Flyway 迁移）。
3. E1、E2 会改辩论提示词结构，需经 F2 验证后才默认启用。
4. F2 的预算和是否亲自盲评。
5. H 中各项是否执行，以及不变量回归网选哪种方式。
