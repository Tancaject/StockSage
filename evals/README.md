# StockSage 评测入口

> 离线评测、实验及诊断脚本已按用户要求删除，不保留备份。本目录保留财报入库工具 [ingest_corpus.py](ingest_corpus.py)、数据与历史报告。下文作为历史评测说明保留，其中引用的已删除脚本、运行命令和离线验收流程不再可用；业务服务源码未变。

这里按评测对象维护标准、数据和运行脚本。**没有覆盖所有对象的统一评分标准或总分**：检索质量、规划正确性、执行契约、回答质量和方法改进分别评价。数值阈值以各项目的代码、配置或冻结实验策略为准，本页只链接这些来源。

命令从仓库根目录执行。现有 Python 环境仍使用 `.venv-rag-eval`：

```powershell
.\.venv-rag-eval\Scripts\Activate.ps1
New-Item -ItemType Directory -Force evals/results | Out-Null
```

真实接口评测需要对应服务和依赖可用；管理接口读取 `STOCKSAGE_ADMIN_TOKEN`。模型调用需要配置实际使用的供应商，密钥只放在环境变量或本地忽略配置中。运行与验收进度统一见 [progress.md](../progress.md)。

## 选择评测对象

| 对象 | 数据与标准来源 | 结果含义 |
|---|---|---|
| RAG 检索与回答 | [questions.jsonl](questions.jsonl)、[golden_set.jsonl](golden_set.jsonl)；指标实现 [run_rag_eval.py](run_rag_eval.py)，门禁 [rag_summary.py](rag_summary.py)；RAGAS 指标及门槛 [run_ragas_eval.py](run_ragas_eval.py) | 检索、引用、拒答等启发式指标与 `ragas_` 语义指标分别报告 |
| Planner / Agent 规划 | [agent_golden_set.jsonl](agent_golden_set.jsonl)、[PlannerEvalService](../stocksage-backend/src/main/java/com/stocksage/service/PlannerEvalService.java)；门禁 [agent_eval_gates.json](agent_eval_gates.json)，汇总 [agent_eval_summary.py](agent_eval_summary.py) | 路由、所需/禁止动作、上下文解析、fallback 和延迟；不代表实际工具执行或回答质量 |
| Ordinary 执行与回答 | [ordinary_live_cases.jsonl](ordinary_live_cases.jsonl)、[run_ordinary_live_eval.py](run_ordinary_live_eval.py)；回答标准 [ordinary_answer_quality.py](ordinary_answer_quality.py) | 执行契约与答案语义分开；没有有效复核时，回答质量保持 `NO_DATA` |
| Harness / DEEP | [harness_golden_set.jsonl](harness_golden_set.jsonl)、[harness_live_cases.jsonl](harness_live_cases.jsonl)、[harness_live_manifest.json](harness_live_manifest.json)；生产策略 [DeepResearchCompletionPolicy](../stocksage-backend/src/main/java/com/stocksage/harness/DeepResearchCompletionPolicy.java)；评价器 [harness_eval_summary.py](harness_eval_summary.py)，门禁 [harness_eval_gates.json](harness_eval_gates.json) | 完成策略的决策/违规/恢复匹配与真实任务安全终态；不能替代研究内容质量 |
| FUNDAMENTALS 方法进化 | [evolution_rubric.py](evolution_rubric.py)、[evolution_dataset.py](evolution_dataset.py)、[evolution_quality.py](evolution_quality.py)、[evolution_acceptance.py](evolution_acceptance.py)；业务事实和改善门槛来自实验自己的冻结 gold / policy | 分别评价分析稿与最终回答，再判定候选是否改善；执行通过不等于进化有效 |

脚本保留平铺入口。[eval_common.py](eval_common.py)、[eval_utils.py](eval_utils.py) 和 [eval_http.py](eval_http.py) 提供共享计算、绑定或传输能力；共享代码不提供跨项目的默认业务标准。

自动化测试文件、测试辅助文件与测试产物已按用户要求移除；评测实现、数据和历史记录保留。依赖 `EvolutionLiveBaselineTest` 或后端 `target/test-classes` 的历史实验入口无法直接重跑，需要先从版本历史恢复对应测试驱动及测试依赖。历史记录中的测试结果不代表当前工作区仍包含这些测试。

## RAG

```powershell
python evals/run_retrieval_eval.py
python evals/run_rag_eval.py --output evals/results/rag.json
python evals/rag_summary.py evals/results/rag.json --fail-on-gate
python evals/run_ragas_eval.py --input evals/results/rag.json
```

`run_retrieval_eval.py` 只检查检索结果中的预期术语及排名。`run_rag_eval.py` 使用已标注证据评价召回、排名、来源、引用和回答关键词等；`rag_summary.py` 按自己的 `DEFAULT_GATES` 逐项判定，缺失必需指标不会通过。其案例均值只用于最差案例排序，不是全域质量总分。

`run_ragas_eval.py` 读取已有结果并调用判官模型，单独记录 `ragas_` 指标、判官配置和评估器版本。它不会重跑检索，也不参与普通 Agent 或基本面进化的默认评分。

## Planner / Agent 规划

```powershell
python evals/run_agent_eval.py --mode DETERMINISTIC --output evals/results/planner-deterministic.json
python evals/run_agent_eval.py --mode LIVE_COORDINATOR --output evals/results/planner-live.json
```

两种模式都调用后端规划评测接口；`DETERMINISTIC` 不代表完整 Agent 已经过真实模型验证。规划模式、数据身份和门禁由 [agent_eval_summary.py](agent_eval_summary.py) 与 [agent_eval_gates.json](agent_eval_gates.json) 校验。可附加 RAG、Harness 等报告进行分区汇总，附加报告仍保留各自的判断标准。`--gates` 只配置 Planner，`--harness-gates` 单独配置 Harness，默认各自读取上表中的文件。

## Ordinary 执行与回答

先通过环境变量配置专用评测账号 `STOCKSAGE_EVAL_EMAIL`、`STOCKSAGE_EVAL_PASSWORD`，再采集真实聊天和 Trace：

```powershell
python evals/run_ordinary_live_eval.py --output evals/results/ordinary-live.json
python evals/ordinary_answer_quality.py --input evals/results/ordinary-live.json --review-template evals/results/ordinary-reviews.json
```

执行检查包括路由、参数、工具、证据时效、终态和引用编号合法性。填妥绑定到该次回答的复核记录后，再计算语义质量：

```powershell
python evals/ordinary_answer_quality.py --input evals/results/ordinary-live.json --reviews evals/results/ordinary-reviews.json --output evals/results/ordinary-reviewed.json
```

普通回答使用自己的五维标准：论断支持、数字与期间正确、反证处理、不确定性表达、任务完成度。具体判据、适用状态和错误归属统一由 [ordinary_rubric()](ordinary_answer_quality.py) 定义，标注、判官输入和评分器引用同一份标准并绑定哈希。标准变化后必须重新复核标签，旧评审不能沿用。标签、理由、回答及证据绑定共同决定复核是否有效；程序检查绑定和汇总，不能凭引用编号自动确定语义正确。固定输入的分析稿消融由 [run_ordinary_answer_replay.py](run_ordinary_answer_replay.py) 执行，仅覆盖最终回答生成。完整步骤和解释边界见[普通路线执行说明](../docs/architecture/ordinary-agent-execution.md)。

### 评测 Agent 与判官校准

[run_evaluation_agent.py](run_evaluation_agent.py) 根据已登记的 Ordinary 结果 schema 读取实际答案、Trace 和证据，沿用上述标准。完整冻结证据直接提供给判官，需要核算派生数值时可调用 `calculate`；每轮启用 JSON 输出模式，最后一轮不再提供工具。绑定核查、摘录校验和状态汇总固定执行；工具不访问网络来源或执行任意脚本。本入口目前只支持普通最终答案，其他领域仍使用各自入口。

```powershell
python evals/run_evaluation_agent.py --input evals/results/ordinary-live.json --output evals/results/ordinary-ai-review.json
python evals/run_evaluation_agent.py --dataset evals/judge-pilot/v2/cases.jsonl --split DEV --output evals/results/judge-dev.json
python evals/judge_calibration.py --dataset evals/judge-pilot/v2/cases.jsonl --report evals/results/judge-dev.json --output evals/results/judge-dev-calibration.json
python evals/propose_judge_prompt.py --dataset evals/judge-pilot/v2/cases.jsonl --baseline evals/results/judge-dev.json --output evals/results/judge-candidate
python evals/run_evaluation_agent.py --dataset evals/judge-pilot/v2/cases.jsonl --split DEV --prompt evals/results/judge-candidate/candidate-prompt.txt --output evals/results/judge-candidate-dev.json
python evals/judge_calibration.py --dataset evals/judge-pilot/v2/cases.jsonl --baseline evals/results/judge-dev.json --report evals/results/judge-candidate-dev.json --output evals/results/judge-comparison.json
```

凭据复用现有 `DASHSCOPE_API_KEY` / 本地忽略配置读取方式；`--model`、`--endpoint`、调用上限及超时可通过 `--help` 查看。报告记录实际返回的模型、提示词和标准身份、每次响应、工具调用及供应商用量；缺用量时不虚构费用。`--dry-run` 只检查输入，不调用模型。输出文件已存在会拒绝覆盖。

判官默认关闭思考；`--enable-thinking` 可开启，`--thinking-budget` 可选指定供应商思考预算。报告保存请求实际使用的配置，工具续轮保留供应商返回的 `reasoning_content`。这些开关用于显式实验，不自动改变业务模型或启用候选提示词；[提示词提案入口](propose_judge_prompt.py)仍只接受其已有固定配置契约。

### 普通回答辅助试用

当前接受的试用配置使用原提示词、`qwen3.8-max`、开启思考和 4096 思考预算。下面的显式命令覆盖兼容性 CLI 默认值，读取真实保存的 Ordinary 回答及证据：

```powershell
python evals/run_evaluation_agent.py --input evals/results/ordinary-live.json --output evals/results/ordinary-ai-trial.json --model qwen3.8-max --prompt evals/prompts/ordinary_judge.txt --enable-thinking --thinking-budget 4096 --timeout-seconds 180 --max-tokens 2400 --max-calls 4
```

用户接受最终通过／失败判定正确率超过 80% 即进入辅助试用，并在实际使用中完善。统计时以全部登记样本为分母，技术无效不算判对；逐维标签一致与五维全部一致另外报告，不能混称准确率。[控制变量实验](judge-pilot/controlled-20261002/RESULTS.md)提供这套配置的样例证据与适用边界；详细归因继续用于分析，不直接授权业务候选发布。业务 Agent 自进化的流程与当前迭代记录见[自进化评测入口](evolution/README.md)。

AI 诊断采用独立 `ordinary_judge_diagnostic_v2` schema，始终标记 `diagnostic_only: true`、`release_eligible: false` 和人工复核未完成，不直接回填人工评审或发布门禁。摘录要求来自冻结标准：提供的片段必须能在原文中找到。单项摘录或格式错误只使该项技术无效；输入绑定损坏、接口失败和不可解析的整份输出影响全部维度。`technical_status`、全局 `errors` 与逐维 `errors` 明确区分这些情况，原始判断保存在 `reported_status`，无效项不计作语义判断。合理的材料不足属于有效 `NO_DATA`；有出处的摘录只能证明定位，不能单独证明语义判断正确。

[判官校准集](judge-pilot/v2/README.md) 包含有官方来源的编写答案与 AI 交叉核查标签，不冒充业务 Agent 的真实输出。运行前校验标签复核状态、两个不同复核角色及标准哈希；争议或未复核标签阻止模型调用。开发和验证按公司/报告分组隔离。校准器分别报告技术有效率、已知判断覆盖率、有效已知判断上的语义一致率、合理弃权及全项目一致率，并给出各自分母。误放与误杀需结合覆盖率阅读；`--baseline` 只在共同有效且已知的项目上计算纠正/退化，覆盖增减单列，比较条件由 [COMPARISON_POLICY](judge_calibration.py) 定义。v1 历史数据和运行不重写，也不与 v2 跨标准比较。

[propose_judge_prompt.py](propose_judge_prompt.py) 收集完整 DEV 运行中已核实的分歧和无效评审，一次调用提出一个通用流程候选；没有开发集错误时不生成。生成输入只包含 DEV，标准、协议、模型和工具保持固定，原始响应及父版本身份随候选保存。使用 `--prompt` 显式评估候选；先在 DEV 选择并冻结版本，再以 `--split VALIDATION` 验证，验证结果不能回流本轮优化。生成和比较不会自动安装候选，不改变业务 Agent 的模板或发布资格。

## Harness / DEEP

```powershell
python evals/run_harness_eval.py --fail-on-gate
python evals/run_harness_live_eval.py --email "$env:STOCKSAGE_EVAL_EMAIL" --password-env STOCKSAGE_EVAL_PASSWORD --fail-on-gate
```

离线 runner 原先通过 `HarnessGoldenSetTest` 调用生产策略；该测试驱动已移除，需要恢复驱动和测试依赖后才能重跑。live runner 提交真实 DEEP 任务并核对终态、Trace 和 Harness 决策；数据集与策略身份由 manifest 约束。用 `--case-limit` 缩小运行范围只构成 smoke，不能视为完整发布门禁。

DEEP Replan 的证据支撑覆盖、新增证据利用率及配对 A/B 门槛在[有界重规划计划](../docs/architecture/deep-bounded-replan-plan.md)中定义；没有独立的已实现语义 runner，不能用 Harness 通过代替这些收益验证。

## FUNDAMENTALS 方法进化

数据契约可先用明确标记的合成机制样本检查：

```powershell
python evals/evolution_dataset.py evals/evolution/synthetic-smoke/cases.jsonl --gold evals/evolution/synthetic-smoke/gold.jsonl
```

这条命令不调用模型，也不证明真实研究质量。真实实验按[进化评测工作流](evolution/README.md)准备独立来源/gold、冻结策略和执行清单，再使用 `run_evolution_replay.py`、`evolution_quality.py`、`evolution_compare.py` 完成回放、复核和比较。

基本面评审由 [evolution_rubric.py](evolution_rubric.py) 独立维护。必答事实、单位、期间、数值容差和派生公式来自 gold；改善幅度、非退化界限、样本与资源约束来自该实验冻结的 policy。最终回答是改善判断的主终点，分析稿仍需单独复核。正式验收和单次实验的 AI 诊断有不同资格，不能互相替代；边界见 [V1 契约](../docs/evolution/evolution-v1-contract.md)。

## Research Memory 与其他专项

[研究记忆计划](../docs/architecture/research-memory-decay-conflict-plan.md)保留检索召回、排序、泄漏、字符预算和延迟的既定验收目标；当前没有独立的 Research Memory live 质量 runner。现有 Memory 定向测试证明各自代码契约，不能证明注入记忆改善了回答；后者需固定当前证据和模型条件做注入/不注入配对评价。

模型或提示模板实验使用其目录内的冻结 `criteria`、原始输出和复核记录，不将单次诊断标准提升为所有项目的默认标准。

## 历史制品与路径

历史实验制品保留原始字节，包括其中记录的 `rag-eval` 路径、哈希和签名。查找旧记录引用的本仓库路径时，将其 `rag-eval` 目录对应到现在的 `evals` 位置；不要为了使历史路径可点击而改写制品内部字段。

目录迁移、评估器或标准变更后的新实验需要重新建立并冻结实际输入、构建及评估器身份，重新满足对应验收条件；定位到旧文件不代表旧资格适用于新实验。
