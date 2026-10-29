# StockSage 评测入口

评测回答三个问题：改动有没有造成退步、两个方案哪个更好、系统现在的实际水平。原则见 [AGENTS.md 的评测原则](../AGENTS.md#评测原则)，整体方案见[评测体系优化方案](../docs/architecture/eval-system-plan.md)。

评测脚本、评审、校准器、数据集和历史结果属于评测体系，不是自动化测试，清理测试时不能删除（[agent_eval_summary.py](agent_eval_summary.py) 曾在提交 `7eb903b` 中被误删，已恢复）。[archive/](archive/) 是已归档的历史工具，自进化 v1 的发布签名、影子运行、上线授权和观测工具在 [archive/evolution-v1-governance](archive/evolution-v1-governance/README.md)。

命令从仓库根目录执行，Python 环境用 `.venv-rag-eval`：

```powershell
.\.venv-rag-eval\Scripts\Activate.ps1
```

真实接口评测需要后端在线。管理接口和请求级评测模式读取 `STOCKSAGE_ADMIN_TOKEN`（与后端配置同一令牌）；需要登录的评测用专用账号 `STOCKSAGE_EVAL_EMAIL`、`STOCKSAGE_EVAL_PASSWORD`。模型密钥只放在环境变量或本地忽略配置中。进度见 [progress.md](../progress.md)。

## 成绩单：三档运行

统一入口是 [run_daily_eval.py](run_daily_eval.py)：

```powershell
.\init.ps1 -Mode eval                                 # daily 档，等价于 python evals/run_daily_eval.py
python evals/run_daily_eval.py --tier change          # 只跑 HEAD 以来改动影响的层和题；DEEP 只跑冒烟题，回答层带 AI 评审
python evals/run_daily_eval.py --since <git-sha>      # 同上，对比指定版本
python evals/run_daily_eval.py --case followup        # 只跑指定的普通/金标题，可重复；多轮题自动带上前面的轮次
python evals/run_daily_eval.py --tier milestone       # 全部层 + DEEP 全集；只在换模型、改思考预算、大改共用提示词、对外报告水平时跑
python evals/run_daily_eval.py --repeat 2             # 普通回答和 DEEP 取证重复生成，记录噪声；会增加费用
python evals/run_daily_eval.py --only deep_evidence   # 只跑指定层，可重复给出
python evals/run_daily_eval.py --preflight-only      # 仅探测依赖，不生成回答
python evals/run_daily_eval.py --tier change --rejudge-count 2  # 每个质量层抽两题额外复评，不重复生成
python evals/run_daily_eval.py --tier change --include-challenges  # 加入仅观察的 DEEP 挑战题
```

| 层 | 内容 | daily | change / milestone | 前提 |
|---|---|---|---|---|
| harness | 固定用例离线调用普通/DEEP 完成策略及领域时效规则，逐题保存判断 | ✓ | ✓ | JDK 17；不需要服务和模型 |
| planner | 115 题 `LIVE_COORDINATOR`，即生产意图识别。这套题已参与调参，是开发集 | ✓ | ✓ | 后端、管理令牌 |
| rag | `golden_set.jsonl` 中有标注证据的题，经 `/api/docs/search` 只算检索指标 | ✓ | ✓ | 后端 |
| deep_evidence | DEEP 只跑证据阶段（请求级 `EVIDENCE_ONLY`，不调用辩论和报告模型）：完成率、终态安全、越权调用、可用证据数 | 固定冒烟题 | change：冒烟题 + 维度覆盖评审；milestone：常规题全集 + 评审 | 后端、管理令牌、评测账号、IBKR 已登录 |
| ordinary | 普通路线执行契约，含正常 MARKET/NEWS 作答和明确标记的必过回归题；多标的对比题 `cmp-*` 需要打开多标的扇出，不在成绩单里 | — | ✓ + 五维评审 | 后端、评测账号 |
| fundamentals_gold | FY2025 基本面集的 VALIDATION 18 题走普通对话，按金标数值核对回答，失败由 `gold_triage.py` 自动分诊为 PROVIDER / DATA_GAP（证据里缺原始数，列出字段）/ DETERMINISTIC / METHOD；只有 METHOD 是方法进化的材料。HOLDOUT 不在这里跑；上次通过、这次失败的题判为退步 | — | ✓ | 后端、评测账号 |
| concurrency | 两个不同账号同时查询不同标的，检查 Trace 的用户、请求和工具事件归属 | ✓ | ✓ | 后端、两个评测账号 |

- **成本**：真实运行前按选中的题数、生成配置和评审次数说明预估费用。`--repeat` 会重复生成；`--rejudge-count` 只额外调用评审模型，默认不复评。费用以报告中的调用账本和计价依据为准，缺 usage 或费率时保留未知，不记为零。
- 缺前提的层记为 `SKIPPED`，不算通过。请求实际因数据源不可用而阻断时记为 `INCOMPLETE`，单列环境阻断题；探测失败本身不能让题目免于回归判定。数据集哈希、题数、schema 版本变化记为 `IDENTITY_CHANGED`，不判失败。
- 普通及基本面金标层分别展示实际通过题数、已知失败和新增失败；明确标记 `regression` / `must_pass` 的题必须通过。层的 `PASS` 表示回归门禁通过，不表示所有回答都正确。
- `judge_` 前缀的指标来自 AI 评审。评审在人工标签上校准之前只作诊断，不参与层的通过判定。
- Planner 的 `DETERMINISTIC` 模式评的是关键词兜底路由，不是生产识别器，成绩单不用它。

并发冒烟还需 `STOCKSAGE_EVAL_SECOND_EMAIL`、`STOCKSAGE_EVAL_SECOND_PASSWORD`；缺少第二账号时该层 `SKIPPED`。正常运行会做最小依赖探测，结果保存在 `preflight.json`，记录实际探测地址和范围：IBKR 会话、data-service、Ollama embedding、BaoStock、akshare、SEC，以及配置了密钥的模型服务。模型服务只读 `/models`，不证明生成可用；Tavily 搜索需显式传 `--probe-search`，会消耗搜索额度。探测定义见 [eval_preflight.py](eval_preflight.py)。

**成绩单内容**：每次运行写入 `evals/results/daily/<时间戳>/`（各层原始报告和 `summary.json`），并在 `history.jsonl` 追加一行。

- 各层指标、逐题结果，以及下述终态、失败归因、资源、风险和数字诊断；
- 配置身份：git SHA，以及 `/api/admin/agent/model-config` 返回的各角色模型、输出上限、思考设置和系统提示词哈希，`configSha` 是它的摘要；
- 与该层上一次有效运行的差值。`--repeat` 测得的每个指标的标准差存在 `results/daily/noise.json`；配置、题集和评审身份一致，且差值不超过 2 倍标准差时标为 within noise；
- 超过 7 天没有有效运行的层标为 STALE。

有层 `FAIL` 或 `ERROR` 时退出码为 1；没有这些失败但有 `INCOMPLETE` 时为 2。`SKIPPED` 和诊断指标不等于通过。

### 逐题诊断与离线重算

以下 Agent 过程指标都从已保存的 Trace 计算，不额外调用模型；不适用时缺省，不记 0 或 1；只作诊断和与上次、与噪声的对比，不新增门禁。普通回答、基本面金标和 DEEP 报告复用 [eval_observations.py](eval_observations.py)、[eval_risk_checks.py](eval_risk_checks.py) 和 [numeric_traceability.py](numeric_traceability.py)：

| 输出 | 口径 |
|---|---|
| 终态与降级原因 | `COMPLETED` / `DEGRADED` / `BLOCKED` / `FAILED`，缺终态单列 `UNKNOWN`；展示降级、拦截原因 Top 3 |
| 金标与完成策略 | 疑似误降级率＝金标通过但终态为 DEGRADED/BLOCKED 的题数 ÷ 金标通过题数；漏拦诊断＝金标失败但 COMPLETED 的题数 ÷ 金标失败题数。数字金标未覆盖全部质量要求，因此两项仅定位疑点 |
| 失败归因 | 按保存的 Trace 顺序记录首个可观察故障，区分数据源不可用、路由/参数、工具、数据缺口和生成；首个故障不直接等于最终失败的根因，评测标准误判需人工确认 |
| 环境阻断与无法判定 | Trace 显示题目因数据源不可用而失败时，只有本次 preflight 也探测到同一来源不可用（data-service 自身的 HTTP 5xx 不算）才从退步判定中剔除，层状态记 `INCOMPLETE`；否则列为 `environment_suspected`，照常判退步。回归检查因来源没返回可检查内容而无法判定时记入 `undetermined_checks`，不算通过也不算失败，层状态同样记 `INCOMPLETE` |
| 成本和效率 | 每题记录已观察的模型调用、输入/缓存/推理/输出 Token、工具调用、取证轮数和耗时；汇总均值、分位数及采集覆盖率。客户端调用数不包含 SDK 内部重试；费用按币种和计价依据分别统计 |
| 风险红线 | 已确认的证据标的/期间越界、搜索入库标的错配或跨请求工具事件使门禁失败；历史记录缺身份、期间或入库完成信息时为 `NO_DATA`，同时展示已检查和未能检查的数量 |
| 数字可追溯率 | 分母是能判定单位的数值出现次数（单位或写法无法识别的记为 `INDETERMINATE`，单列不计入）；分子是能直接匹配证据、由回答写出的算式核算、或由回答已引用的表格单元格派生（同一行的差额和增速；同一列的差额、比值，两格须在该数前后两句内被引用）的数值。没有局部单位的表格数按本表声明的金额单位换算。10-20 的 18 道金标全对回答约 76%，未匹配主要是多项求和与多步推算；往真实回答注入随机数时约 1% 被误判为可追溯。仅做数值匹配，不能证明公司、科目、期间和口径正确，不进入门禁 |
| 工具调用 | `tool_call_success_rate`／`empty`／`failure`／`unknown`、`tool_timeout_rate`、`tool_unavailable_rate`（数据源不可用）、`tool_call_success_rate_excl_unavailable`；按工具 `tool_success_rate:<工具>`、`tool_timeout_rate:<工具>`，诊断里有各工具错误码前 5、整体错误类型前 8 |
| 参数与路由 | `parameter_accuracy`（题目声明的请求参数和工具参数检查的通过比例）、`route_accuracy_live`（真实请求上的路由检查） |
| 数据需求 | `requirement_satisfaction_rate`（运行时计划声明的需求被满足的比例）、`frozen_requirement_coverage_rate`（题目冻结需求的覆盖比例） |
| 兜底与补救 | `fallback_trigger_rate`、`fallback_recovery_rate`（补跑后需求全部满足）；财报原文 `filing_kb_hit_rate`、`filing_fallback_rate`、`filing_fallback_recovery_rate` |
| DEEP 取证循环 | `deep_loop_tool_calls`、`deep_loop_rounds`、`deep_loop_added_rate`、`deep_loop_truncated_rate`、`deep_loop_not_added_rate`（分母只含带 ADDED/NOT_ADDED 标记的调用）、`deep_loop_failed_rate`、`deep_loop_outcome_unknown_rate`（10-14 前的 Trace 只写 EXECUTED）、`deep_loop_new_evidence_per_call`、`deep_loop_productive_round_rate`；未采纳与失败原因分布在诊断里 |
| 模型调用 | `model_call_failure_rate`、`model_output_incomplete_rate`，按范围 `model_failure_rate:<routing/ordinary-planner/analyst/fundamentals-analysis/final-answer>`、`model_incomplete_rate:<范围>` |
| 一次成功率 | `--repeat k` 时按题统计 `pass^k_rate`（每次都通过）、`pass@k_rate`（至少一次通过）、`per_trial_pass_rate`，并列出 `flaky_cases` |

保存的普通/基本面金标或 DEEP 结果可以直接重算以上诊断；不调用模型、不重新生成回答，也不写在线运行历史：

```powershell
python evals/run_daily_eval.py --replay-results <旧ordinary.json> --replay-results <旧deep.json> --output-dir <新目录>
```

缺少原文或调用记录的历史结果保留未知。`--output-dir` 必须是新目录；重算结果不能补回当时没有捕获的数据。

## 按改动选层

`--tier change`（不带 `--since` 时对比 `HEAD`）和 `--since <sha>` 用 `git diff`（含工作区改动和未跟踪文件）把改动路径映射到层。映射表是 `run_daily_eval.py` 中的 `CHANGE_MAP`：

| 改动 | 跑哪些层 |
|---|---|
| 意图识别、路由（`agent/intent`、Coordinator、路由计划） | planner，涉及执行计划的加 ordinary |
| 检索、入库（`rag`、`knowledge`） | rag、ordinary |
| DEEP 完成策略（`harness`） | harness、deep_evidence |
| 普通路线的分析师、Planner、提示词、上下文组装 | ordinary（凡触发 ordinary 全部题的改动都同时触发 fundamentals_gold、concurrency） |
| 对话记忆（`memory/`、`stocksage.memory.*`、`stocksage.chat.prompt.history-max-chars`） | ordinary 只跑多轮题，不触发 fundamentals_gold、concurrency |
| 共用工具层（`tool`、`capability`、`client`、`ibkr`、data-service） | ordinary、deep_evidence |
| DEEP 取证、取证循环、研究任务 | deep_evidence |
| 辩论、Manager、报告渲染 | deep_report：还没有可运行的层，打印 NOT COVERED，需要跑完整 DEEP |
| 换模型、改思考预算、改模型客户端（`AgentConfig` 等） | 所有回答层 |

`application.properties` 按改动的配置项映射（`PROPERTY_MAP`，前缀匹配，第一个命中生效）：例如 `stocksage.chat.prompt.*` 跑 ordinary，`stocksage.rag.*` 跑 rag、ordinary，`stocksage.ibkr.*` 跑 ordinary、deep_evidence；没有登记前缀的配置项（模型路由、思考预算、模型客户端、向量库等）跑所有回答层；只改注释不触发任何层。

Trace 改动触发 concurrency；认证、管理接口等不影响回答的代码映射为空。没有登记的产品代码打印为 unmapped，需要补进 `CHANGE_MAP`。前端改动打印 NOT COVERED，要在浏览器里看渲染结果。

**层内按题选**：`--case <id>` 只跑指定的普通或基本面金标题（可重复给出，题号不存在时直接报错，优先于 `--since` 和 `--only`）。同一会话整体选中、整体跳过：点名后面的轮次会带上前面的轮次。预检看到某题依赖的数据源不可用时（按题的预期工具调用和港股/A 股标的推断 IBKR、AKShare、BaoStock、SEC），该题记 `SKIPPED`、不发请求，层记 `INCOMPLETE`，跳过原因写在 `skipped_cases`。只跑了部分题的运行（`case_scope` 不是 `all`，或有跳过的题）保留逐题结果，用于后续回归比较；但不更新该层的指标基线、噪声和 STALE 计时。`--case` 运行不导出线上周报。

## 评审：run_evaluation_agent.py

[run_evaluation_agent.py](run_evaluation_agent.py) 用 `--rubric` 选择评分标准，评审协议相同：

| rubric | 输入 | 判定 |
|---|---|---|
| `ordinary`（默认） | `run_ordinary_live_eval.py` 或回放结果 | 五维 PASS/FAIL/NOT_APPLICABLE/NO_DATA，标准是 [ordinary_rubric()](ordinary_answer_quality.py)；可调用计算器核算派生数值 |
| `deep_facets` | `run_deep_m0.py --evidence-only` 的结果（含完整证据快照） | 每个维度回答两个是非问题：相关实质证据是否存在、是否直接且充分支持全部要求；合法组合映射到 0/1/2，标准在 [deep_facet_quality.py](deep_facet_quality.py) |

- 证据原文完整提供给评审。DEEP 用辩论和报告模型实际读到的最终证据快照（后端 `/api/admin/agent/research-tasks/{id}/evidence-snapshot`）；会暴露组别的工具名（`loop*` 等）和检索词替换成中性类型，证据按内容确定的种子打乱并编号 E1…En。
- 每个判断都要引用原文：引文必须是输入中的连续片段（DEEP 必须出自所引的那条证据）。普通回答只容忍空白差异，DEEP 另外容忍全半角和引号样式差异。格式错误、引文对不上或缺少引用的项记为技术无效，不计入分数；各维度独立计分。
- 实际评审模型、思考预算和提示词身份保存在报告的 `judge` 字段。统一入口的参数以 [run_daily_eval.py 的 JUDGE_ARGS](run_daily_eval.py) 为准；独立调用按命令行参数生效。评审模型与被测生成模型须不同家。
- 默认每题仅评一次。`--rejudge-count N --rejudge-seed S` 为可复现抽样额外评一次；`--repeats N` 重评全部已保存输入，都不重新生成回答。调用前输出 `cost_plan`，`rejudging.extra_cost` 单列额外使用量；费率未配置时金额为 `null`。
- `primary_summary` 只统计首评，避免抽样题重复加权；`summary` 包含全部尝试及重复一致率。普通五维分别报告合法 `NO_DATA`、`NOT_APPLICABLE` 和技术无效占比，分母为对应汇总的全部评审次数；技术失败不混入合法 `NO_DATA`。DEEP 没有这两个语义状态，缺失评分记为技术无效。
- 新 DEEP 输出使用 `deep_facet_judge_v2` / `deep_facet_rubric_v2`。布尔值矛盾、映射分数不一致或引文无效都不计分。旧 v1 可离线汇总，保留原身份，不能直接当作新版同标尺结果比较。

```powershell
python evals/run_evaluation_agent.py --input evals/results/ordinary-live.json --output evals/results/ordinary-judge.json --model qwen3.8-max --enable-thinking --thinking-budget 4096
python evals/run_deep_m0.py --arm C --evidence-only --output evals/results/deep-evidence.json
python evals/run_evaluation_agent.py --rubric deep_facets --input evals/results/deep-evidence.json --output evals/results/deep-facet-judge.json --model qwen3.8-max --enable-thinking --thinking-budget 4096 --max-tokens 8000
python evals/judge_calibration.py --dataset <标注集.jsonl> --report <评审结果.json> --output <校准报告.json>
python evals/run_evaluation_agent.py --summarize-report <已有评审.json> --output <新诊断汇总.json>
python evals/run_evaluation_agent.py --input <真实普通回答结果.json> --export-human-labels <人工标注.jsonl>
python evals/run_evaluation_agent.py --rubric deep_facets --input <DEEP证据结果.json> --export-human-labels <DEEP人工标注.jsonl>
```

**人工校准（S5）**：导出的标签全为空，不调用模型。人工填写 `expected_dimensions` 或 `expected_facets` 的判断与理由（DEEP 的两个布尔值须与映射 `score` 一致），再将 `label_origin` 设为 `HUMAN`，填写 `label_review.status=HUMAN_CONFIRMED`、`reviewers`、`reviewed_at`；保留输入绑定和 rubric 哈希。未审核标签不能进入校准；同一来源组不能跨 DEV/VALIDATION。用 `--dataset <标注集.jsonl>` 评审这些固定输出，再运行校准器，得到一致率、误放率、漏判率和技术无效率。AI 编写的[判官校准集](judge-pilot/v2/README.md)只供诊断，不能替代真实输出的人工标签。所有 AI 分数保持诊断用途，校准器不会自动授予发布资格。

`--dry-run` 只检查输入；输出文件已存在时拒绝覆盖；密钥从 `DASHSCOPE_API_KEY` 或后端本地忽略配置读取。普通回答评审提示词的候选生成和对比用 [propose_judge_prompt.py](propose_judge_prompt.py)：只用 DEV 生成和选择，VALIDATION 只做验证，不回流优化。

## 线上 Trace 周报与抽样

[online_trace_review.py](online_trace_review.py) 读取持久化 Trace，比较相邻两个七天窗口，按路由和市场展示请求量、终态、降级/拦截原因、工具与数据源失败、耗时和费用。变化标记仅提示复核，不作为显著性或质量门禁。评测账号（`STOCKSAGE_EVAL_*` 与 `STOCKSAGE_EVAL_SECOND_*` 登录解析出的用户，以及 `STOCKSAGE_EVAL_USER_IDS` 列出的用户）的 Trace 不进统计和抽样，只报告剔除条数；无法解析评测账号时报告标为 `ALL_ACCOUNTS_EVALUATION_IDS_UNKNOWN`。统一成绩单只在 change/milestone 档（未加 `--only`、`--case`）或显式 `--online-review` 时生成这份报告；它不是定时调度任务。

```powershell
python evals/online_trace_review.py --output evals/results/weekly.json
python evals/online_trace_review.py --input <完整14日导出-traces.json> --output <新周报.json> --sample-size 5 --seed 0
```

报告旁同时保存 `-traces.json`（在线读取时）、`-samples.json` 和 `-reviews.json`。抽样只从本周 `COMPLETED` 的普通路线回答中选择，保留种子和 Trace ID；数字检查离线运行，缺原文时为 `NO_DATA`。`-reviews.json` 是绑定到原回答的空白人工复核模板；`-samples.json` 也可交给 `run_evaluation_agent.py` 做显式付费诊断或导出 S5 标签。周报不会自动调用 AI 评审。

填写人工复核模板后，可离线计算已评样本的不合格率和复核覆盖率：

```powershell
python evals/online_trace_review.py --input <原导出-traces.json> --seed 0 --sample-size 5 --reviews <已填写-reviews.json> --output <新复核周报.json>
```

需要把已确认的失败写回失败池时，在同一命令显式加 `--submit-confirmed-failures`。该模式仅接受绑定校验通过的人工 `FAIL`，使用 `STOCKSAGE_EVAL_EMAIL/PASSWORD` 登录原 Trace 所属账号，经现有 `/api/chat/feedback` 提交；其他账号的样本、未确认的数字疑点和 AI 诊断不自动入池。重算时必须沿用原导出的窗口、种子和抽样数量，以保持复核绑定。

## 各评测对象

| 对象 | 数据与标准 | 说明 |
|---|---|---|
| RAG 检索与回答 | [golden_set.jsonl](golden_set.jsonl)、[questions.jsonl](questions.jsonl)；指标 [run_rag_eval.py](run_rag_eval.py)，门禁 [rag_summary.py](rag_summary.py)，RAGAS [run_ragas_eval.py](run_ragas_eval.py) | 检索、引用、拒答指标与 `ragas_` 语义指标分开报告 |
| Planner 路由 | [agent_golden_set.jsonl](agent_golden_set.jsonl)、[PlannerEvalService](../stocksage-backend/src/main/java/com/stocksage/eval/PlannerEvalService.java)；门禁 [agent_eval_gates.json](agent_eval_gates.json)，汇总 [agent_eval_summary.py](agent_eval_summary.py) | 路由、所需/禁止动作、上下文解析、fallback 和延迟；不代表工具执行或回答质量 |
| 普通路线 | [ordinary_live_cases.jsonl](ordinary_live_cases.jsonl)、[ordinary_compare_cases.jsonl](ordinary_compare_cases.jsonl)、[run_ordinary_live_eval.py](run_ordinary_live_eval.py)；回答标准 [ordinary_answer_quality.py](ordinary_answer_quality.py) | 执行契约和回答质量分开评价 |
| DEEP | [harness_golden_set.jsonl](harness_golden_set.jsonl)、[harness_live_cases.jsonl](harness_live_cases.jsonl)、[deep_react_cases.jsonl](deep_react_cases.jsonl)；生产策略 [DeepResearchCompletionPolicy](../stocksage-backend/src/main/java/com/stocksage/harness/DeepResearchCompletionPolicy.java) | 完成策略、真实任务终态安全、证据阶段的维度覆盖 |
| FUNDAMENTALS 方法进化 | [evolution_rubric.py](evolution_rubric.py)、[evolution_quality.py](evolution_quality.py)、[evolution_numeric_check.py](evolution_numeric_check.py) 等 | 分析稿与最终回答分别评价，再判定候选是否改善 |

[eval_common.py](eval_common.py)、[eval_utils.py](eval_utils.py) 和 [eval_http.py](eval_http.py) 提供共享计算和传输，不提供跨项目的默认业务标准。

### RAG

```powershell
python evals/run_retrieval_eval.py
python evals/run_rag_eval.py --output evals/results/rag.json
python evals/rag_summary.py evals/results/rag.json --fail-on-gate
python evals/run_ragas_eval.py --input evals/results/rag.json
```

`run_retrieval_eval.py` 只检查预期术语及排名；`run_rag_eval.py` 用标注证据评价召回、排名、来源、引用和回答关键词，`rag_summary.py` 按 `DEFAULT_GATES` 逐项判定。`run_ragas_eval.py` 读取已有结果调用判官模型，单独记录 `ragas_` 指标，不重跑检索。

### Planner

```powershell
python evals/run_agent_eval.py --mode LIVE_COORDINATOR --output evals/results/planner-live.json
python evals/run_agent_eval.py --mode DETERMINISTIC --output evals/results/planner-deterministic.json
```

规划模式、数据身份和门禁由 [agent_eval_summary.py](agent_eval_summary.py) 与 [agent_eval_gates.json](agent_eval_gates.json) 校验；`--gates` 只配置 Planner，`--harness-gates` 单独配置附加的 Harness 报告。115 题在 10-11、10-12 修失败题时用过，对外报告路由水平需要另建留出集（方案 S6）。

### 普通路线

```powershell
python evals/run_ordinary_live_eval.py --output evals/results/ordinary-live.json
python evals/ordinary_answer_quality.py --input evals/results/ordinary-live.json --review-template evals/results/ordinary-reviews.json
python evals/ordinary_answer_quality.py --input evals/results/ordinary-live.json --reviews evals/results/ordinary-reviews.json --output evals/results/ordinary-reviewed.json
```

执行检查包括路由、参数、工具、证据时效、终态和引用编号合法性；题目带 `require_requirements` 时，题目声明的数据需求必须全部满足。财报原文（FILING_TEXT）由本轮知识库召回该标的满足，没有召回时由 SEC 申报原文摘录（`getFilingText`）满足，网页搜索摘要不算；`regression-filing-text-fallback` 两条路径都接受，第一次运行走原文兜底并在后台入库，之后走知识库。人工复核用第二、三条命令：先导出绑定到该次回答的复核模板，填好后计算五维结果；标准变化后旧复核不能沿用。固定输入的最终回答消融用 [run_ordinary_answer_replay.py](run_ordinary_answer_replay.py)。完整说明见[普通路线执行说明](../docs/architecture/ordinary-agent-execution.md)。

普通回答 AI 评审的辅助试用配置是原提示词、`qwen3.8-max`、开思考、预算 4096；用户接受“最终通过/失败判定正确率超过 80% 即进入辅助试用”，统计以全部登记样本为分母，技术无效不算判对。依据见[控制变量实验](judge-pilot/controlled-20261002/RESULTS.md)。

### DEEP

```powershell
python evals/run_harness_eval.py --fail-on-gate
python evals/run_harness_live_eval.py --email "$env:STOCKSAGE_EVAL_EMAIL" --password-env STOCKSAGE_EVAL_PASSWORD --fail-on-gate
python evals/run_deep_m0.py --arm C --evidence-only --output evals/results/deep-evidence.json
```

- 离线 Harness 通过 [HarnessPolicyEval.java](harness/HarnessPolicyEval.java) 调用生产策略，不启动服务、不调用模型；`--skip-build` 复用已编译的后端。编译或产物错误返回 2，门禁未通过且带 `--fail-on-gate` 时返回 1。
- Harness live 提交真实 DEEP 任务核对终态、Trace 和 Harness 决策；`--case-limit` 缩小范围只算冒烟。
- `run_deep_m0.py` 按 `deep_react_cases.jsonl` 提交 DEEP 研究。`--evidence-only` 用请求级评测模式只跑证据阶段，并保存完整证据快照供维度评审；不带该参数时跑完整研究并保存报告。多组对照的盲评汇总用 [deep_m0_score.py](deep_m0_score.py)。
- `suite=challenge` 且 `gate_eligible=false` 的题默认不运行；显式 `--include-challenges` 后单列诊断，不影响常规回归门禁。统一入口只在 change/milestone 档接入挑战题。
- DEEP 报告质量（辩论和报告）还没有可运行的评测层；需要时用完整研究加人工复核。[S7 冻结证据回放方案](../docs/architecture/deep-report-replay-plan.md)待确认，不能把计划中的结论稳定性、反证回应和证据利用率当作已交付指标。

### FUNDAMENTALS 方法进化

```powershell
python evals/evolution_dataset.py evals/evolution/synthetic-smoke/cases.jsonl --gold evals/evolution/synthetic-smoke/gold.jsonl
```

这条命令只检查数据契约，不调用模型。真实实验流程见[进化评测工作流](evolution/README.md)，评审与改善门槛的边界见 [V1 契约](../docs/evolution/evolution-v1-contract.md)。

**不再扩展**：这条线的治理部分（冻结清单、执行清单哈希、审批与发布：`evolution_release.py`、`evolution_acceptance.py`、`evolution_experiment.py`、`evolution_build.py` 等）保持现状，不再新增功能或层级。后续进化改用统一内核方案（[deep-evidence-evolution-plan.md](../docs/architecture/deep-evidence-evolution-plan.md)），等评测方案 S4～S6 完成后再继续。

### 其他

- Research Memory 没有独立的 live 质量评测；证明注入记忆改善了回答，需要固定证据和模型做注入/不注入配对评价（见[研究记忆计划](../docs/architecture/research-memory-decay-conflict-plan.md)）。
- 模型或提示模板实验使用各自目录内的冻结标准、原始输出和复核记录，不把单次实验的标准当作默认标准。

## 历史记录

- 自动化测试已按用户要求移除。依赖已删测试驱动的历史实验入口（如 `EvolutionLiveBaselineTest`、`scoped-repeatability-20261004/run.py`）不能直接重跑；保留原计划和哈希，不改写冻结记录。
- 部分历史草稿回放没有保存模型结束原因，不能据正文非空判定生成完整（见[完成判定验证记录](diagnostics/harness-completion-20261005/RESULTS.md#解释范围)）。
- 历史制品保留原始字节，其中的 `rag-eval` 路径对应现在的 `evals` 目录；不要改写制品内部字段。
- 目录、评估器或标准变更后的新实验要重新记录实际输入和评估器身份，旧结果的结论不自动适用。
