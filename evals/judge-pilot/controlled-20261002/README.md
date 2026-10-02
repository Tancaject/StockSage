# 提示词与思考模式控制变量实验

本目录诊断普通回答判官的引文、数值和维度归因问题。运行条件、任务顺序和指标在模型评审前冻结于 [plan.json](plan.json)，执行复用现有 `judge_case()` 与 `calibrate()`，入口为 [experiment.py](experiment.py)。

实际指标、配对故障及结论见[控制变量结果](RESULTS.md)。

| 组 | 提示词 | 思考 |
| --- | --- | --- |
| A_base_off | 原提示词 | 关闭 |
| B_prompt_off | 候选提示词 | 关闭 |
| C_base_on | 原提示词 | 开启 |
| D_prompt_on | 候选提示词 | 开启 |

比较 B−A、D−C 得到固定思考条件下的提示词差异；比较 C−A、D−B 得到固定提示词下的思考差异。四臂共同有效且已知的维度上另报告 D−C−B+A，作为描述性交互量。技术有效率与语义一致率分别报告，不能以剔除失败样本来宣称质量提升。

所有组使用同一模型、证据、回答、五维标准、计算工具及预算。每组对[既有 20 个样例](../retest-20261002/cases.jsonl)各评审两次，四臂按固定种子交错调度。标签沿用[既有独立 AI 核查记录](../retest-20261002/label-review.json)，没有送入判官。

[原提示词快照](prompt-baseline.txt)与[候选提示词](prompt-candidate.txt)在运行前冻结。候选只明确连续原文摘录、派生数值/单位换算的工具核验，以及逐维适用性和归因，不修改评分标准；不调用计算器本身不会新增为评分失败。这是一个提示词整体处理，不能分别证明三条改动各自的贡献。

供应商的[模型说明](https://help.aliyun.com/en/model-studio/qwen3-8-max)、[结构化输出](https://help.aliyun.com/zh/model-studio/qwen-structured-output)、[深度思考](https://help.aliyun.com/zh/model-studio/deep-thinking)和[Chat 参数](https://help.aliyun.com/zh/model-studio/qwen-api-via-openai-chat-completions)说明该模式的适用条件。共同 `max_tokens` 约束最终回答；共同 `thinking_budget=4096` 在开启思考时对应 low。工具续轮原样保留返回的 `reasoning_content`。[兼容性探针](compatibility-probe.json)独立于评测数据，不计入质量分母；费用/用量核算应包含它。

这里使用已经观察过结果的样例，属于诊断回放，**不是新的独立验证或人工金标**。四个来源组、两次重复不能支持总体显著性或普遍模型能力结论。开启思考的差异也不能证明内部推理的具体机制；供应商实际返回的思考字段与用量必须单独核查。实验不自动启用候选、不改变业务默认配置，不授予发布资格。

可复核命令（使用配置了项目依赖的 Python）：

```powershell
python evals/judge-pilot/controlled-20261002/experiment.py check
python evals/judge-pilot/controlled-20261002/experiment.py run
python evals/judge-pilot/controlled-20261002/experiment.py summarize
```

`run` 拒绝覆盖已有原始结果；`summarize` 只重建派生统计。使用其他数据或修改冻结内容时，应建立新实验目录。
