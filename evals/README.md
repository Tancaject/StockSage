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
python evals/run_daily_eval.py --tier change          # 加普通回答层、DEEP 证据全集和 AI 评审
python evals/run_daily_eval.py --since <git-sha>      # 只跑受改动影响的层（默认按 change 档）
python evals/run_daily_eval.py --repeat 2             # 每层跑两次，记录噪声
python evals/run_daily_eval.py --only deep_evidence   # 只跑指定层，可重复给出
```

| 层 | 内容 | daily | change / milestone | 前提 |
|---|---|---|---|---|
| harness | 82 个固定用例离线调用生产 DEEP 完成策略 | ✓ | ✓ | JDK 17；不需要服务和模型 |
| planner | 115 题 `LIVE_COORDINATOR`，即生产意图识别。这套题已参与调参，是开发集 | ✓ | ✓ | 后端、管理令牌 |
| rag | `golden_set.jsonl` 中有标注证据的题，经 `/api/docs/search` 只算检索指标 | ✓ | ✓ | 后端 |
| deep_evidence | DEEP 只跑证据阶段（请求级 `EVIDENCE_ONLY`，不调用辩论和报告模型）：完成率、终态安全、越权调用、可用证据数 | 4 题冒烟 | 11 题全集 + 维度覆盖评审 | 后端、管理令牌、评测账号、IBKR 已登录 |
| ordinary | 普通路线执行契约，生产配置下的 8 题（多标的对比题 `cmp-*` 需要打开多标的扇出，不在成绩单里）；上次通过、这次失败的题判为退步 | — | ✓ + 五维评审 | 后端、评测账号 |

- **成本**：daily 约 0.5 元；change 约 12 元（普通回答约 4 元，两个评审合计约 6～7 元，其余约 1 元；DEEP 维度评审 10-13 实测每次约 0.21 元）；milestone 目前同 change，DEEP 报告质量层还没有实现。真实运行前先说明题数和费用。
- 缺前提的层记为 `SKIPPED`，不算通过。数据集哈希、题数、schema 版本变化记为 `IDENTITY_CHANGED`，不判失败。
- `judge_` 前缀的指标来自 AI 评审。评审在人工标签上校准之前只作诊断，不参与层的通过判定。
- Planner 的 `DETERMINISTIC` 模式评的是关键词兜底路由，不是生产识别器，成绩单不用它。

**成绩单内容**：每次运行写入 `evals/results/daily/<时间戳>/`（各层原始报告和 `summary.json`），并在 `history.jsonl` 追加一行。

- 各层指标和逐题结果；
- 配置身份：git SHA，以及 `/api/admin/agent/model-config` 返回的各角色模型、输出上限、思考设置和系统提示词哈希，`configSha` 是它的摘要；
- 与该层上一次有效运行的差值。`--repeat` 测得的每个指标的标准差存在 `results/daily/noise.json`，差值不超过 2 倍标准差时标为 within noise；
- 超过 7 天没有有效运行的层标为 STALE。

有层 FAIL 或 ERROR 时退出码为 1。

## 按改动选层

`--since <sha>` 用 `git diff`（含工作区改动和未跟踪文件）把改动路径映射到层。映射表是 `run_daily_eval.py` 中的 `CHANGE_MAP`：

| 改动 | 跑哪些层 |
|---|---|
| 意图识别、路由（`agent/intent`、Coordinator、路由计划） | planner，涉及执行计划的加 ordinary |
| 检索、入库（`rag`、`knowledge`） | rag、ordinary |
| DEEP 完成策略（`harness`） | harness、deep_evidence |
| 普通路线的分析师、Planner、提示词、记忆 | ordinary |
| 共用工具层（`tool`、`capability`、`client`、`ibkr`、data-service） | ordinary、deep_evidence |
| DEEP 取证、取证循环、研究任务 | deep_evidence |
| 辩论、Manager、报告渲染 | deep_report：还没有可运行的层，打印 NOT COVERED，需要跑完整 DEEP |
| 换模型、改思考预算、改模型客户端（`application.properties`、`AgentConfig` 等） | 所有回答层 |

认证、管理接口、Trace 等不影响回答的代码映射为空。没有登记的产品代码打印为 unmapped，需要补进 `CHANGE_MAP`。前端改动打印 NOT COVERED，要在浏览器里看渲染结果。

## 评审：run_evaluation_agent.py

[run_evaluation_agent.py](run_evaluation_agent.py) 用 `--rubric` 选择评分标准，评审协议相同：

| rubric | 输入 | 判定 |
|---|---|---|
| `ordinary`（默认） | `run_ordinary_live_eval.py` 或回放结果 | 五维 PASS/FAIL/NOT_APPLICABLE/NO_DATA，标准是 [ordinary_rubric()](ordinary_answer_quality.py)；可调用计算器核算派生数值 |
| `deep_facets` | `run_deep_m0.py --evidence-only` 的结果（含完整证据快照） | 每个必需维度 0/1/2：未覆盖、部分覆盖、直接覆盖，标准在 [deep_facet_quality.py](deep_facet_quality.py) |

- 证据原文完整提供给评审。DEEP 用辩论和报告模型实际读到的最终证据快照（后端 `/api/admin/agent/research-tasks/{id}/evidence-snapshot`）；会暴露组别的工具名（`loop*` 等）和检索词替换成中性类型，证据按内容确定的种子打乱并编号 E1…En。
- 每个判断都要引用原文：引文必须是输入中的连续片段（DEEP 必须出自所引的那条证据）。普通回答只容忍空白差异，DEEP 另外容忍全半角和引号样式差异。格式错误、引文对不上或缺少引用的项记为技术无效，不计入分数；各维度独立计分。
- 评审模型用 `qwen3.8-max`，开思考、预算 4096，与生成用的 deepseek 不同家。

```powershell
python evals/run_evaluation_agent.py --input evals/results/ordinary-live.json --output evals/results/ordinary-judge.json --model qwen3.8-max --enable-thinking --thinking-budget 4096
python evals/run_deep_m0.py --arm C --evidence-only --output evals/results/deep-evidence.json
python evals/run_evaluation_agent.py --rubric deep_facets --input evals/results/deep-evidence.json --output evals/results/deep-facet-judge.json --model qwen3.8-max --enable-thinking --thinking-budget 4096 --max-tokens 8000
python evals/judge_calibration.py --dataset <标注集.jsonl> --report <评审结果.json> --output <校准报告.json>
```

**校准状态**：普通回答五维评审在 AI 编写的[判官校准集](judge-pilot/v2/README.md)上判对 90～95%，标签由 AI 交叉核查；DEEP 维度评审尚未校准。两者都还没有在系统真实输出的人工标签上校准（方案 S5），结果只作诊断。DEEP 校准报告给出一致率、相差不超过 1 分的比例、二次加权 κ、误判覆盖率（标签 0 分而评审给分）和漏判率。

`--dry-run` 只检查输入；输出文件已存在时拒绝覆盖；密钥从 `DASHSCOPE_API_KEY` 或后端本地忽略配置读取。普通回答评审提示词的候选生成和对比用 [propose_judge_prompt.py](propose_judge_prompt.py)：只用 DEV 生成和选择，VALIDATION 只做验证，不回流优化。

## 各评测对象

| 对象 | 数据与标准 | 说明 |
|---|---|---|
| RAG 检索与回答 | [golden_set.jsonl](golden_set.jsonl)、[questions.jsonl](questions.jsonl)；指标 [run_rag_eval.py](run_rag_eval.py)，门禁 [rag_summary.py](rag_summary.py)，RAGAS [run_ragas_eval.py](run_ragas_eval.py) | 检索、引用、拒答指标与 `ragas_` 语义指标分开报告 |
| Planner 路由 | [agent_golden_set.jsonl](agent_golden_set.jsonl)、[PlannerEvalService](../stocksage-backend/src/main/java/com/stocksage/service/PlannerEvalService.java)；门禁 [agent_eval_gates.json](agent_eval_gates.json)，汇总 [agent_eval_summary.py](agent_eval_summary.py) | 路由、所需/禁止动作、上下文解析、fallback 和延迟；不代表工具执行或回答质量 |
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

执行检查包括路由、参数、工具、证据时效、终态和引用编号合法性。人工复核用第二、三条命令：先导出绑定到该次回答的复核模板，填好后计算五维结果；标准变化后旧复核不能沿用。固定输入的最终回答消融用 [run_ordinary_answer_replay.py](run_ordinary_answer_replay.py)。完整说明见[普通路线执行说明](../docs/architecture/ordinary-agent-execution.md)。

普通回答 AI 评审的辅助试用配置是原提示词、`qwen3.8-max`、开思考、预算 4096；用户接受“最终通过/失败判定正确率超过 80% 即进入辅助试用”，统计以全部登记样本为分母，技术无效不算判对。依据见[控制变量实验](judge-pilot/controlled-20261002/RESULTS.md)。

### DEEP

```powershell
python evals/run_harness_eval.py --fail-on-gate
python evals/run_harness_live_eval.py --email "$env:STOCKSAGE_EVAL_EMAIL" --password-env STOCKSAGE_EVAL_PASSWORD --fail-on-gate
python evals/run_deep_m0.py --arm C --evidence-only --output evals/results/deep-evidence.json
```

- 离线 Harness 通过 [HarnessPolicyEval.java](harness/HarnessPolicyEval.java) 调用生产策略，不启动服务、不调用模型；`--skip-build` 复用已编译的后端。编译或产物错误返回 2，门禁未通过且带 `--fail-on-gate` 时返回 1。
- Harness live 提交真实 DEEP 任务核对终态、Trace 和 Harness 决策；`--case-limit` 缩小范围只算冒烟。
- `run_deep_m0.py` 按 `deep_react_cases.jsonl` 提交 DEEP 研究。`--evidence-only` 用请求级评测模式只跑证据阶段（约 0.05 元/题），并保存完整证据快照供维度评审；不带该参数时跑完整研究并保存报告。多组对照的盲评汇总用 [deep_m0_score.py](deep_m0_score.py)。
- DEEP 报告质量（辩论和报告）还没有可运行的评测层；需要时用完整研究加人工复核，冻结证据回放见方案 S7。

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
