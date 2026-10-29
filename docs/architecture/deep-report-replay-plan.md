# DEEP 冻结证据回放方案（S7，待确认）

目标：固定取证结果，只重跑多空辩论、Manager 评分和报告，区分“证据变化导致结论变化”和“同一证据下结论不稳定”。本文件只提出方案；用户确认前不实现后端入口、不调用付费模型。评测原则和整体安排分别以 [AGENTS.md](../../AGENTS.md#评测原则)、[评测体系方案](eval-system-plan.md) 为准。

## 最小实现路径

沿用[普通回答回放](../../stocksage-backend/src/main/java/com/stocksage/conversation/OrdinaryAnswerReplayService.java)的方式，在已有 [AgentEvalController](../../stocksage-backend/src/main/java/com/stocksage/controller/AgentEvalController.java) 下增加管理员评测入口；输入冻结证据，返回完整结果，由本地脚本保存。复用 [ResearchDebateService.runDebate](../../stocksage-backend/src/main/java/com/stocksage/debate/ResearchDebateService.java)，保留生产使用的提示词、解析器、续停判断、Java 裁决、报告 Harness 和最多一次报告修复。

每次回放从新的 `AnalysisState` 开始，传 `startRound=1`、`authorizedThroughRound=0`，不带历史辩论、评分、裁决、报告或恢复预算。checkpoint 回调只收集本次阶段输出，不写生产 checkpoint；不进入 [DeepResearchPipeline](../../stocksage-backend/src/main/java/com/stocksage/research/deep/DeepResearchPipeline.java) 的取证、任务恢复、缓存及发布流程。首版只提供“辩论到最终报告”这一种范围，不另做仅重跑评分、仅生成报告或固定辩论轮数等模式。

Python 侧扩展 [run_deep_m0.py](../../evals/run_deep_m0.py) 读取冻结输入，并将 `deep_report` 接入 [run_daily_eval.py](../../evals/run_daily_eval.py)；语义评审扩展 [run_evaluation_agent.py](../../evals/run_evaluation_agent.py)。不新增一套运行器、数据库表或实验目录体系。

## 冻结什么

以 [ResearchEvidenceSnapshotService](../../stocksage-backend/src/main/java/com/stocksage/research/deep/ResearchEvidenceSnapshotService.java) 导出的原始快照为输入，不从摘要或最终报告反推证据。现有脚本只导出快照的部分字段，实施时需要保留完整载荷。

| 内容 | 要求 |
|---|---|
| 用户问题、标的、时间要求 | 保存 `query`、`ticker`、`timeSensitivity`，不重新路由或解析标的。缺失的时间要求不得猜成当前时间。 |
| 模型实际可见的证据 | 逐字保存 `fundamentalsReport`、`marketReport`、`newsReport`，保留顺序、截断结果、Evidence ID；不再压缩或改写。 |
| 结构化来源 | 完整保存 `evidenceLedger`、`citations`、`dataSnapshotHash`、`contextHash`；保留主体、期间、来源、业务时间和采集时间。 |
| 取证循环新增证据 | 从原运行的循环状态或 Trace 取 `addedEvidenceIds`，保留去重后的集合及来源绑定；它用于利用率统计，不作为新增模型事实。 |
| 运行身份 | 题集与快照哈希、源运行标识、实际后端代码身份、各角色模型/供应商、温度、思考与输出设置、提示词哈希、辩论硬上限和决策策略身份。脏工作区不能只记提交 SHA。 |

[DebateDecisionPolicy](../../stocksage-backend/src/main/java/com/stocksage/debate/DebateDecisionPolicy.java) 已使用账本中最新 `observedAt` 评估时效，不用墙钟改变重放结果，因此保留账本时间即可，不额外设计“虚拟当前时间”。

三次回放固定相同证据和实际运行配置；后续轮次的论点、续停结果和 Manager 输入是本次生成结果，不能沿用上次输出，否则测不到整段链路的波动。每次调用另外保存实际有序消息及哈希，记录真正送给模型的内容。比较提示词或模型时，证据保持一致，候选配置单独登记；不能把当前部署回放写成“复现历史模型”。

## 权限、费用和副作用

入口沿用 `/api/eval/**` 的管理令牌保护，仅接收受限的证据载荷，不接受任意模型地址、工具或客户端提交的已评分状态。模型使用后端实际配置，输入缺字段、哈希不匹配、证据超限等问题在调用模型前明确报错。

不创建用户会话、研究任务或报告版本，不写知识库、研究记忆、报告缓存，不执行 search-ingest、RAG 检索及外部数据工具。Trace/SSE 不使用真实会话 ID；回放的 Harness 计数也要与线上指标隔离。[HarnessObserver](../../stocksage-backend/src/main/java/com/stocksage/harness/HarnessObserver.java) 即使没有 traceId 仍会累加计数，不能仅靠传空 ID 宣称没有污染。

调用记录是明确需要补的后端适配点：[ModelInvocationAdvisor](../../stocksage-backend/src/main/java/com/stocksage/agent/runtime/ModelInvocationAdvisor.java) 的现有持久化依赖真实任务和租约，传空 `ModelInvocationContext` 会跳过记录。拟复用其请求、响应和 usage 采集逻辑，为评测返回独立的调用记录；不伪造生产任务/租约，也不重写一套 token 计算规则。回放必须仍有总超时、调用次数限制和取消传播；记录缺失时费用为 `NO_DATA`，不能填零。

每次结果保存：最终状态、完整报告及展示文本、所有辩论轮次、续停决定、Manager 原始评分、Java 裁决、Harness/修复结果，以及每次模型调用的输入输出、完成原因、耗时、输入/缓存/推理/输出 token 和费用估算。缓存 token 通常是输入 token 的子集，推理 token 的计费关系按供应商字段处理，不能简单将四类相加。费用沿用本次价格配置并标注币种；缺 usage 或价格时保留已知部分和缺失原因。

运行前显示题数、每题 3 次、预计调用量和费用范围，获准后再运行。按现有最多 8 轮推算（2026-10-25 起），一次成功走完的回放最多约 28 次模型调用（多空 16、续停 7、评分含修正 2、报告含修复 2，另加至多一次被取消的推测轮），不含临时错误重试和独立评审。实际费用须依据真实输入长度、角色配置和价格估算，不把历史完整研究的单题费用套过来。请求结果不明时停止并保留该次为未完成，不自动重试付费调用。

## 指标和分母

| 指标 | 定义与判定 |
|---|---|
| 三次评级一致 | 每题列出 3 次评级和分析期限。汇总分子为三次都成功产出有效评级、且评级相同的题数，分母为全部登记题数；失败、缺失和 `NOT_RATED` 不算一致成功，并单列数量。期限一致另列。 |
| 多空分差波动 | 每次取 Java 裁决中的 `bullScore - bearScore`，每题计算 3 次的样本标准差（分母为 2），展示原始分差和距评级阈值的距离。未凑齐 3 个有效值则为 `NO_DATA`。 |
| 数字可追溯 | 复用数字追溯诊断，列出无法在冻结证据中找到或由证据简单计算出的数字。完成 S5 人工误报核对后才作为必过门禁。 |
| 引用合法 | 每个结构化辩论论点至少有一条可用 Evidence ID，摘录属于该证据；报告每个 `evidenceItem` 的来源 ID 都在本题冻结账本中。检查生成原文与解析结果，防止解析器丢弃非法项后误报全部合法。结构违规为必过项。 |
| 评级方向一致 | 报告的评级、期限须等于本次 Java 裁决，并通过相同决策策略复核。不能仅用多空分差的正负判定，因为策略还考虑分数、阈值、证据资格和覆盖。结构化方向一致为必过项。 |
| 循环证据利用率 | 最终报告合法引用的循环新增 Evidence ID 数 / 冻结快照中仍可用的循环新增 Evidence ID 数；ID 去重。没有新增证据记为 `NOT_APPLICABLE`，来源信息缺失记为 `NO_DATA`，均不填 0。 |
| 语义质量 | 分开判断“回应对方最强论点”和“将证据中的重大反向事实写入风险”；必须引用本次辩论、报告和对应证据，给出是/否及理由。无有效辩论或报告为 `NO_DATA`，不能判通过。 |

评级三次相同只说明重复稳定，不能等同于三次都答对，也不是 `pass^3`。如需报告三次全过，必须另用已校准的质量标准逐次判定。少量题先展示逐题结果及计数，不能用三次重复推断普遍准确率或显著提升。

还有一个需要明确的边界：[InvestmentReport](../../stocksage-backend/src/main/java/com/stocksage/research/report/InvestmentReport.java) 的 `bullFactors`、`bearFactors` 是纯文本列表，并未逐项绑定 Evidence ID。因此上述确定性引用检查覆盖结构化辩论与 `evidenceItems`；“每条报告观点都受到对应证据支持”需语义复核。若要把每条报告观点的引用绑定做成确定性必过项，需要另外确认报告字段与前端兼容改动，不能用“报告中存在合法引用”代替。

评审沿用已有独立供应商要求；在真实输出的人机校准完成前，语义项和未校准数字追溯只作诊断。同时保存评审技术无效率、`NO_DATA/NOT_APPLICABLE` 数量及抽样复评一致率。三次生成的波动与同一输出的评审波动分开统计。

## 确认后的交付与验证

实施范围仅含：评测控制器及薄回放服务、DEEP 调用记录与观测隔离、完整快照导出、现有运行器/评分入口接线及文档。复用生产辩论、评分、裁决与报告逻辑，不修改取证策略、模型档位或在线报告字段。

先离线核对冻结输入和字段契约，再对最小代表样本执行真实回放 3 次，保留输入、配置、原始输出和逐题指标。验收必须同时确认：没有取证工具调用或线上业务写入、各次使用同一冻结证据、真实调用记录完整、失败不被当作稳定成功。只有这些证据和用户确认的质量判定具备后，`deep_report` 才能从 `NOT COVERED` 变成可运行层；编译成功或报告 JSON 合法不能代替报告质量。

待用户决定：是否按上述“管理员无持久化回放 + 保持在线报告结构”范围实施；首批选哪些题、允许多少模型与评审费用。用户确认前保持方案阶段。
