# 其余两条分析超时的复现与截断归因

本次接续[前次根因调查](../model-root-cause-20261002/RESULTS.md)，只重放原 JNJ 08、TGT 10 两条分析输入，各调用一次，没有重试。条件在 [plan.json](plan.json) 中预先冻结；复用原诊断脚本，没有改业务代码、生产设置或旧实验产物。

## 实测结果

两组均使用原问题、证据与候选系统消息，`qwen3.8-max`、温度 0.7、推理预算 32768、正文上限 4096。诊断期限为 600 秒，原分析阶段期限为 300 秒；请求期间临时阻止自动空闲睡眠，结束后均恢复原线程状态。

| 输入 | 首响应/推理 | 首正文 | 总耗时 | 推理 tokens | 实际终止 |
|---|---:|---:|---:|---:|---|
| [JNJ 08](candidate-dev-08/result.json) | 1.062 秒 | 269.828 秒 | 350.015 秒 | 11231 | `length`，正文截断 |
| [TGT 10](candidate-dev-10/result.json) | 1.219 秒 | 208.235 秒 | 297.797 秒 | 8899 | `stop`，正文正常结束 |

两组 HTTP 都为 200，均收到结束标记；最大事件间隔分别仅约 1.094、1.219 秒。**本次没有长时间等待网络或排队的表现，主要时间消耗在正文之前的推理。** JNJ 又用了约 80 秒输出正文；TGT 用了约 90 秒。

JNJ 复现了无法在 300 秒内结束的问题，即使放宽期限，正文仍触及长度上限。TGT 按预登记条件记为 **未复现超时**：本次正常结束，但仅比原期限早 2.203 秒。这不是部署环境的延迟保证，也不证明原故障已修复。

两题原来的无新增经验基线也均为 300 秒超时，因此不能把失败全部归因于学习候选。原非流式调用没有逐事件记录；新样本不能还原历史请求内部过程。Python 流式请求与 Java 非流式调用也不是完全相同的执行环境。

## 时间实际花在哪里

可见推理记录显示，两题都反复计算核心指标并多轮规划报告，没有工具调用或新增证据。本报告只概括检查结果，不展开原始推理内容。

- **JNJ**：推理前段已经完成经营现金流同比和现金余额勾稽，随后重列指标、扩展上年现金桥、各项占期初现金比例、股利与回购覆盖倍数，并反复规划章节。实际正文长达 6387 字符，在风险章节中间截断，未到数据缺口与来源章节。
- **TGT**：流动比率、净债务及现金变动至少出现三轮核算，并增加现金比率、各资产占比、多个同比指标和多轮章节规划。实际正文为 6272 字符，最终正常结束；本次没有重新做完整语义评分，正常结束不等于质量通过。

两题要求累计期间分析，单季还原经验不适用。记录中存在适用条件判断，最终没有据此强行做单季还原。这里的候选是完整开发集诊断回放的一部分；`compose_diagnostic_bundle` 将经验以附条件文字加入方法，而正式 `FundamentalsMethodRegistry.selectionReason` 另有任务、证据及能力标签检查。不能把回放输入直接当成线上选择错误。

这些记录支持“重复核算、扩展内容与高推理力度共同带来长等待”的解释，但不能量化各因素的独立耗时，也不能证明固定文本无限循环。正文长度与模型叙述本身均不是模型内部机制的直接测量。

## 新确认的代码问题：截断终止原因丢失

当前共享分析路径存在一个独立的完整性问题：

1. `FundamentalsAgent.invoke` 调用同步 `.call().chatResponse()`，保留正文、模型名称和用量，**没有保留生成结果的 finish reason**。
2. `EvolutionReplayService` 等待整个调用完成，随后以正文是否为空决定分析阶段 `COMPLETED`。
3. 普通路径 `ToolPrefetchService.appendAnalystDraft` 同样将非空正文标为 `COMPLETED`，拼入后续最终回答的上下文。

本地安装的 Spring AI OpenAI 1.1.5 类字节码核查显示，`OpenAiChatModel.buildGeneration` 会把供应商的 finish reason 放入 `ChatGenerationMetadata` 并返回正文；项目适配层没有继续传递这个信息。该结论来自当前源码及实际依赖检查，没有新增 Java 端到端截断回放。

因此，**若一个 `length` 截断响应在期限内返回，当前基本面分析路径可能将其当作已完成分析。** 这不是 JNJ 原 300 秒超时的原因，而是另一个已定位的问题：单纯提高超时，仍可能得到被误标完整的半份分析。传输结束、正文完整、语义合格必须分开。

相关源码与依赖指纹见 [result.json](result.json) 的 `terminationContract`；共享路径见 [FundamentalsAgent](../../../stocksage-backend/src/main/java/com/stocksage/agent/FundamentalsAgent.java)、[EvolutionReplayService](../../../stocksage-backend/src/main/java/com/stocksage/evolution/EvolutionReplayService.java) 和 [ToolPrefetchService](../../../stocksage-backend/src/main/java/com/stocksage/service/ToolPrefetchService.java)。

## 修正方向与验证边界

先在共享分析返回值中保留终止原因，让截断不能以完整分析身份进入评测和最终整合；再收窄基础报告模板和未发布经验中的额外计算，使用固定标准联合验证耗时、完整性与质量。不能从一次接近期限的成功推导稳定性，也不能从此前低预算的快速失败直接选定更低默认预算。

本次严格执行两次调用上限，供应商用量均已返回；总量以 [result.json](result.json) 为准。输入绑定、原产物不变、文档链接及局部差异检查见 [verification.json](verification.json)。没有业务代码修改、服务重启、经验激活或上线验收；上述截断契约修正仍待实施。
