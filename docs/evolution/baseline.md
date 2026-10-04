# Agent Evolution 实施基线

用户提供的 [v1.0 原始计划](implementation-plan-v1.0.md) 是本次范围依据；其 TODO 表为原始规划，实际步骤进度、命令结果与阻塞只维护在 [progress.md](../../progress.md)。本文件描述接入边界，不作为部署状态清单。

## 基线与证据范围

原计划引用远端 `d1a1d4b`。实施开始时本地 HEAD 为 `fc6f5ccb5f416be590d8fc91bf5f77b0513ebdfc`，且已有大量未提交的模块迁移和评估工作。本次沿用工作区，不回退、不提交或覆盖这些工作。`RAG_EVALUATION.md` 是开始前已有的删除；当前普通路线契约以 [执行说明](../architecture/ordinary-agent-execution.md) 为准。

开始前的 `init.ps1 -Mode fast -SkipInstall` 日志在 `stocksage-backend/target/verification-evolution-baseline.log`；真实普通评测前置检查产物在 `stocksage-backend/target/evolution-ordinary-baseline-live.json`。这些本地运行产物不作为可重建的源码提交。最新验证结论见进度日志。

没有运行中的模型观测时，配置默认值不能称为实际生效模型。基线清单不得读取或展示凭据；模型请求参数、供应商响应模型和用量由实际回放结果记录。缺少供应商版本、使用量或人工标签时保留未知状态。

## 实际调用链

普通 FUNDAMENTALS：`conversation.ChatService` 完成意图与历史处理 → `service.ToolPrefetchService` 按服务器计划取证 → `agent.FundamentalsAgent.analyze(resolvedQuery, context)` → 预取层加入有界分析草稿 → `conversation.ChatPromptAssembler` 组装原始用户问题、历史、RAG、记忆等 → `agent.Coordinator.streamAnswer` 生成最终答案。

DEEP：`research.DeepEvidenceCollector` 取得证据快照，随后进入 `research.ResearchDebateService` 的 Bull/Bear/Manager 流程。普通分析师方法包不接入 DEEP；不能把字段或事件中的角色名称当作实际调用证据。

## 复用与新增

| 已有能力 | 本次增量 |
|---|---|
| 最终回答真实输入、证据和模型观察 | 补基本面方法身份、分析师实际输入/输出/调用观察 |
| `ordinary_answer_quality.py` 四维人工评审和同证据配对 | 为演化快照补数据校验、来源分类与独立标签；不重复造语义评分器 |
| `OrdinaryAnswerReplayService` 最终回答回放 | 补分析师前半段并复用真实草稿装配；结果明确区分两个阶段 |
| 原有无工具角色客户端与管理接口边界 | 只允许注册用例和方法 ID；生产请求不能提交候选文本 |
| Research Memory 历史报告结论 | 方法经验独立存储与验证，不能充当本轮事实 |

现有分析师消融脚本只允许删除原草稿，不接受任意方法替换。其结果不可改名充当两阶段演化回放。研究集、独立评审和真实供应商效果仍需单独取得；合成 smoke 集只验证机制。
