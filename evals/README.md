# StockSage 评测入口

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

脚本保留平铺入口，相关 `test_*.py` 验证各自评测实现。[eval_common.py](eval_common.py)、[eval_utils.py](eval_utils.py) 和 [eval_http.py](eval_http.py) 提供共享计算、绑定或传输能力；共享代码不提供跨项目的默认业务标准。离线回归入口为 `python -m unittest discover -s evals`，不调用真实模型。

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

普通回答使用自己的四维标准：论断支持、数字与期间正确、反证处理、不确定性表达。标签、理由、回答及证据绑定共同决定复核是否有效；程序检查绑定和汇总，不能凭引用编号自动确定语义正确。固定输入的分析稿消融由 [run_ordinary_answer_replay.py](run_ordinary_answer_replay.py) 执行，仅覆盖最终回答生成。完整步骤和解释边界见[普通路线执行说明](../docs/architecture/ordinary-agent-execution.md)。

## Harness / DEEP

```powershell
python evals/run_harness_eval.py --fail-on-gate
python evals/run_harness_live_eval.py --email "$env:STOCKSAGE_EVAL_EMAIL" --password-env STOCKSAGE_EVAL_PASSWORD --fail-on-gate
```

离线 runner 通过 [HarnessGoldenSetTest](../stocksage-backend/src/test/java/com/stocksage/harness/HarnessGoldenSetTest.java) 调用生产策略，核对完整预期决策。live runner 提交真实 DEEP 任务并核对终态、Trace 和 Harness 决策；数据集与策略身份由 manifest 约束。用 `--case-limit` 缩小运行范围只构成 smoke，不能视为完整发布门禁。

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
