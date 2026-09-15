# StockSage 与 EchoMind 的 Agent 架构比较

以下为改动前的比较快照；前四项优化实施后的行为和评估入口见[普通路线执行与验收](../architecture/ordinary-agent-execution.md)，验证结果统一记录于 `progress.md`。

本次结论：保留 StockSage 的“模型做受约束判断、服务端拥有执行权”架构。优先补齐请求参数传递、普通路线完成契约和真实执行评测；复杂问题再考虑有限主辅协作。没有证据支持迁移框架、替换向量库或全面引入 ReAct。

## 证据范围

- 对比对象是 StockSage 当前工作树，以及 `D:\programming\EchoMind所有代码+详细文档+简历` 中的 Python、Java 和本地 wiki 文档。两套 EchoMind 实现分开判断。
- [用户提供的飞书页面](https://my.feishu.cn/wiki/Nc7ewUrXIizIEvkhtxwcI6y9nld)跳转至登录页，未取得在线正文；不能确认其与本地 wiki 一致。
- `RAG_EVALUATION.md` 在起始工作树中已删除，没有恢复。RAG 结论以实际生产代码和 `rag-eval` 脚本为依据。
- 这是源码比较；模型、存储、MCP 的配置声明不等于当前进程实际生效状态。运行验证和接手信息统一记在 [progress.md](../../progress.md)。

## 真实调用链

**StockSage 普通请求**：ChatService → 最近对话消歧及意图融合 → 必要时澄清 → 按路由和标的门控 RAG/研究记忆 → RoutePlanCatalog → ToolPrefetchService 确定性取证 → 一个无工具领域 Agent → 无工具最终回答 → Trace/SSE/记忆。

**StockSage DEEP**：持久化研究任务 → Redis Stream/worker → 证据快照及 Harness → 有界补证路径 → 领域分析 → 每轮并行 Bull/Bear → Manager 判断 CONTINUE/STOP → 匿名评分 → Java 锁定评级 → 报告合成与验收。checkpoint、lease、fencing 和 SSE replay 负责恢复边界；补证开关的默认值为 false，不能据此推断现场开关值。

**EchoMind Python**：FastAPI chat → MemoryManager → IntentRecognizer → AgentOrchestrator 的领域评分 → 单角色或主辅并行 → 各角色最多三次模型调用的 tool-use 循环 → 多角色 ResponseComposer → 记忆与观测。共享 RAG 工具通过角色工具白名单进入执行链。

**EchoMind Java**：Controller → 记忆 → 意图识别 → Controller 门控 RAG → Orchestrator 单角色/并行角色 → 文本合并 → AnswerVerifier → 记忆。BaseAgent 直接调用 LlmGateway，不具备 Python 版相同的模型工具循环；并行结果用字符串拼接，不能套用 Python ResponseComposer 的描述。

## 按模块比较

| 模块 | EchoMind 实现 | StockSage 实现 | 判断 |
|---|---|---|---|
| 运行框架 | Python 为 FastAPI、Anthropic SDK、自写编排；Java 为 Spring Boot、Spring AI，LangChain4j 实际用于文档切分 | Java Spring Boot/Spring AI 掌握 Agent 与业务执行，Python FastAPI 提供金融数据 | 不需要为“有 Agent 框架”换技术栈；EchoMind 的 Java 依赖名不等于 LangChain4j Agent 编排 |
| 模型调用 | Python 可按角色指定模型；Java 通过 ChatModel 网关 | 按 FAST/STANDARD/STRONG 配置职责；当前聊天客户端使用 OpenAI compatible-mode，DashScope/Alibaba 仍用于相关能力如 rerank | 以现有配置与调用类为准，不能沿用旧版“全部 DashScope ChatClient”的描述；优化应比较相同样本的质量、耗时和调用成本 |
| 意图识别 | LLM、Embedding、Pattern 融合；Python 的 embedding 在标准客户端无 embeddings 资源时落到本地字符 n-gram 哈希 | typed IntentDecision、真实 Embedding 匹配分支、Pattern/n-gram、近期对话消歧、冲突澄清 | 已具备融合机制；重点应是歧义、多轮及参数正确性，而非新增一层 Router |
| 计划 | 根据意图、关键词、实体评分确定 primary/supporting roles | targetRoute 决定服务端固定动作、顺序、角色和模型层级 | 固定执行权值得保留，但动作与参数过粗，见 P0 |
| 多 Agent | Python 支持领域主辅并行并合成；默认每类只有一个实例 | 普通路线至多一个领域 Agent；DEEP 才执行多个角色和辩论 | 可以借鉴有限主辅协作，但“跨领域”与“多股票对比”是两个独立问题 |
| 工具 | Python 角色白名单、参数校验、tool-use；很多领域工具是字段检查和建议，不能等同真实退款/订单操作 | 行情、财报、新闻、IBKR 只读数据；普通链路由后端取证，角色模型无工具 | 不应为增加自主性重新开放所有工具；先让受控参数表达用户需求 |
| RAG | Python：Chroma 召回、三路 query rewrite、并行检索、去重、LLM rerank；Java：内存文档的自写 BM25 与本地向量融合，可选写入 Spring AI VectorStore | Parent-Child、Milvus、Lucene BM25、RRF、专用 reranker、标的过滤、编号来源；使用单个改写 query 检索 | 保留现有检索结构；可以验证原 query 与改写 query 联合召回，不直接复制三路扩展 |
| 短期/长期记忆 | Python Redis 工作记忆，15 条触发压缩并保留最近 5 条；Chroma 情景记忆和画像 | MySQL 对话事实、Redis 压缩窗口、用户画像事实确认/撤销/遗忘，以及带冲突治理的研究记忆 | StockSage 对投研事实的生命周期更明确；是否扩展普通聊天的跨会话语义记忆取决于真实需求 |
| 上下文 | EchoMind 按角色生成输入包，Skill 文本按匹配注入并限长 | 最终回答统一字符预算、保留当前问题、动态上下文标为不可信；领域 Agent 在此前消费证据 | 总预算已存在，但原始工具内容优先占用，且不覆盖所有模型调用，见 P1 |
| Skill / MCP | Skill 是可热加载的业务 SOP prompt；Python mcp 目录主要是本地工具管理，不构成 MCP 协议互操作证据 | Skill 是受 allowlist、预算约束的确定性 CAPABILITY 步骤；真实 MCP client adapter 接入新闻能力 | 两者的 Skill 含义不同，不应把 SOP、取证、Agent 编排揉成一个执行 DSL |
| 验收与恢复 | Java 有回答校验器，但异常返回 pass=true；其结果主要作为返回字段，未形成阻止错误回答的硬门 | DEEP 有证据/报告双阶段 Harness、NOT_RATED、持久化恢复；普通路线尚无同等完成判定 | 借鉴“明确验收边界”的目的，不复制 fail-open 校验行为 |
| 可观测与评测 | 角色成功率/延迟影响实例评分；默认单实例限制了选择作用；对话 evaluator 直接调用 Orchestrator | 持久 Trace、工具/Capability 观测、SSE 回放、可选 Phoenix；Planner、执行、任务质量分开表达 | API 成功、角色生成成功、任务完成必须分开；不要将一次响应成功作为业务成功标签 |

## 按收益与依赖排序的改进

### P0：用户约束必须进入工具参数

**证据**：`ToolPrefetchService` 的 `GET_FINANCIAL_REPORTS` 固定传 `annual, 5`；`getMarketKLine()` 固定传 `3m, 1d` 或 `daily, 60`；`getMarketTechnicalContext()` 固定取 `6m, 1d` 或 `MA,MACD,RSI`。`ExecutionPlan` 没有与这些调用对应的 typed 参数。

**影响**：用户说“近一周小时线”或“最近两个季度”，路由即使命中 MARKET/FUNDAMENTALS，取回数据仍可能不满足问题。把周期写进 resolvedQuery 或提示词，不能改变硬编码调用。

**最小方案**：先只修 MARKET，把 period/bar 或区间和粒度作为经服务端校验的参数贯穿识别、计划、工具调用和 Trace；不支持的组合明确澄清。财报频率/数量作为后续独立修改。动作仍来自 RoutePlanCatalog，不让模型生成任意工具名。

**验收**：至少覆盖一个用户显式周期、一个不支持的粒度，以及实际发给数据层的参数与最终答复一致。Route Accuracy 不能代替这个检查。

### P0：普通路线接上真正的完成契约

**当前契约**：普通预取通过 `OrdinaryCompletionPolicy` 验收证据，并返回独立业务 taskOutcome；已接入的参数、引用和上下文约束见[执行说明](../architecture/ordinary-agent-execution.md)。

**影响**：最终模型正常返回只能证明生成完成，不能证明数据覆盖了目标问题。工具失败提示也不能代替程序化验收。

**最小方案**：沿现有 EvidenceLedger/Harness 接入一条普通路线；保留结构化标的、来源、时间范围、工具结果和数据缺口，再得出 COMPLETED/DEGRADED/BLOCKED/FAILED。现有策略需按该路线需求复核，不能只检查“至少一条市场证据”便宣布完整成功。

**验收**：无数据、错标的、周期不匹配、部分工具失败，都不能记为完整业务成功。先做确定性检查，不给每次回答额外加一个 LLM Judge。

### P1：收紧证据输入，减少无收益的模型步骤

**证据**：普通路线固定取整组工具结果、生成领域报告，再生成最终回答；`ChatService` 先给 TOOL_OBSERVATIONS 使用剩余预算，之后才分配 RAG、历史和画像。该总预算在领域 Agent 调用之后才生效。

**影响**：简单价格查询可能执行超出需求的分析；长工具结果可能挤出 RAG 或近期上下文，按字符截断还可能丢失末尾来源。这里是静态风险，不代表已测得线上延迟或错误率。

**最小方案**：按具体任务整理必要字段、来源和缺口，完整保留每条纳入的证据；观测每段实际字符数与舍弃原因。对纯事实查询评估跳过领域报告，直接让最终回答消费证据；分析问题保留领域步骤。先测同组样本，不做推测性重构。

### P1：增加普通路线的真实执行评测

**证据**：当前 Agent runner 主要验证 Planner；DEEP 有独立 live Harness evaluator。EchoMind 的对话 evaluator 也直接调用 Orchestrator，并非覆盖 API、持久记忆、流式输出的黑盒验收。

**最小方案**：复用当前 Trace 与评测脚本，增加普通路线的 API 样本，检查实际调用、参数、证据、输出来源与最终 taskOutcome。保留原始 LLM 判断、融合路由、工具执行、任务完成四个层次。

**验收**：固定数据集、模型配置、判定规则与原始 Trace；覆盖周期、歧义、多轮、失败和越界。无标签的质量指标继续 NO_DATA，不用运行时请求成功率补位。

### P2：有限主辅协作与双标的对比，分别实施

EchoMind 的“技术问题 + 重复扣款”确实能选择两个领域角色；StockSage 的普通路线只取首个角色，单纯给计划增加 supportingAgents 不会执行第二个角色。

对于“解释某公司财报与今天下跌的关系”，可设计有限的 FUNDAMENTALS + NEWS/MARKET 配方，共享同一份服务端证据，最后明确综合双方结论；先证明单角色或现有 DEEP 没有覆盖再增加。

股票对比另需独立参数与证据隔离、同口径指标、币种/周期对齐及缺项处理。可以先做两标的固定对比；不能只移除 Coordinator 的多标的澄清门。是否实施这两项仍是待确认产品范围。

### P2：对 RAG 做小规模联合召回实验

StockSage 当前把单个改写 query 同时用于向量与 BM25。若改写丢失年份、指标原词或专有名称，两个通道会共同失去该信息。EchoMind 的多查询召回提供了实验方向。

先保留原 query，与一个改写 query 去重后联合召回，复用 RRF 和 reranker。用真实 qrels 对比 Recall@K、nDCG@K、回答忠实度与耗时，再决定是否采用；没有失败样本时不先引入三路改写。

## 不应照搬的内容

1. **动态 Agent 池**：EchoMind 默认每种角色一个实例，性能评分没有多个同类候选可选；其 success 主要来自生成流程未抛异常，不是任务验收结果。
2. **Java AnswerVerifier 的 fallback**：异常返回通过会掩盖“没有评估成功”；若使用 Judge，失败必须保留为未知/失败。
3. **把所有角色改成 tool-use**：会重新分散取证所有权，增加重复调用和追踪成本，当前没有必要。
4. **双框架或更换向量数据库**：本地源码没有提供迁移能改善 StockSage 的证据；LangChain4j 在 EchoMind Java 的明确用途是文档切分。
5. **简历中的效果数字**：Python 默认源码可见 11 条意图样例；本次未找到“约 500 条”数据集和对应 91.3% Accuracy、0.89 Macro-F1、88.7% RAG 准确率的可复现产物。这些是文档声称，不能用于项目高低比较。
6. **扩大 Scope 以增加名词**：优先兑现一条完整请求中的参数正确、取证正确和结果可验收，再考虑 Agent 数量、SOP 热更新或更多 Skill 类型。

## 主要源码入口

StockSage：

- [请求与上下文](../../stocksage-backend/src/main/java/com/stocksage/service/ChatService.java)
- [固定执行计划](../../stocksage-backend/src/main/java/com/stocksage/agent/RoutePlanCatalog.java)、[执行参数边界](../../stocksage-backend/src/main/java/com/stocksage/agent/ExecutionPlan.java)
- [普通路线执行与固定取数参数](../../stocksage-backend/src/main/java/com/stocksage/service/ToolPrefetchService.java)
- [意图融合](../../stocksage-backend/src/main/java/com/stocksage/agent/intent/IntentFusionPolicy.java)
- [RAG](../../stocksage-backend/src/main/java/com/stocksage/rag/RagService.java)
- [完成契约](../../stocksage-backend/src/main/java/com/stocksage/harness/OrdinaryCompletionPolicy.java)、[有界补证](../../stocksage-backend/src/main/java/com/stocksage/service/DeepEvidenceReplanService.java)
- [MCP 适配器](../../stocksage-backend/src/main/java/com/stocksage/mcp/McpNewsSearchCapabilityAdapter.java)
- [Planner runner](../../rag-eval/run_agent_eval.py)、[DEEP live evaluator](../../rag-eval/run_harness_live_eval.py)

EchoMind，相对于用户提供的根目录：Python 的 `EchoMind/agents/agent_orchestrator.py`、`api/main.py`、`core/intent_recognizer.py`、`core/skill_loader.py`、`mcp/tool_manager.py`、`memory/conversation_memory.py`、`evaluation/evaluator.py`；Java 的 `EchoMindJava/src/main/java/com/echomind/` 下 `api/EchoMindController.java`、`agent/BaseAgent.java`、`agent/AgentOrchestrator.java`、`agent/AnswerVerifier.java`、`knowledge/KnowledgeBaseService.java`、`llm/SpringAiLlmGateway.java`。文档交叉核对了 `EchoMind/wiki/EchoMind定位与技术亮点.md` 与 `带数据指标的加强版简历模板.md`。
