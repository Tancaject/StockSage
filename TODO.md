# StockSage TODO

状态标记：`[ ]` 待做 · `[~]` 进行中 · `[x]` 已完成

---

## 已完成里程碑

<details>
<summary>阶段 0–7 已完成项（点击展开）</summary>

### 阶段 0：环境与基础设施

- [x] MySQL、Redis、Milvus 可独立启动
- [x] Python 数据服务可启动
- [x] Java 后端可启动
- [x] 前端可启动
- [x] 把数据库、DashScope 等敏感配置迁到环境变量

### 阶段 1：基础对话

- [x] SSE 对话链路打通
- [x] 会话持久化
- [x] 前端基础聊天界面

### 阶段 2：Tool Calling

- [x] K 线、财务指标、技术指标、市场概览
- [x] 股票对比分析
- [x] 美股数据支持（yfinance）
- [x] Web Search / News Search
- [x] IBKR 只读基础能力

### 阶段 3：RAG 与知识库（基础）

- [x] 文档导入接口（Glossary / Markdown / TXT / PDF）
- [x] DashScope embedding 分批写入 + Milvus 检索
- [x] 移除 RetrievalAugmentationAdvisor，改为参考性 SystemMessage 注入
- [x] 增量入库、语义去重、TTL 清理、对话驱动入库、定时采集
- [x] RAG 固定回归通过

### 阶段 4：Agent 编排（Plan-and-Execute）

- [x] Coordinator 意图路由 + 确定性预取 + 最终综合
- [x] 规划步骤输出到前端 + 固定路由回归

### 阶段 5：记忆系统

- [x] Redis 短期记忆 + MySQL 长期用户画像
- [x] 聊天链路接入记忆读写

### 阶段 6：Trace 与可观测性

- [x] TraceService + AOP 自动记录 + 规划步骤级 trace
- [x] 工具调用实时状态推送（SSE）
- [x] Trace 内联展示 + token/耗时摘要

### 阶段 7：打磨

- [x] 错误处理统一化（ErrorResponse + GlobalExceptionHandler）
- [x] 缓存策略细化（Cache-Aside + 分类 TTL）
- [x] Resilience4j 断路器（搜索 + IBKR）
- [x] 前端：重试 / 编辑消息 / 快速提示美股化 / Trace 内联
- [x] 免责声明和风控提示统一
- [x] README 重写

</details>

---

## Bug 修复

- [x] `LongTermMemory.java` — `NOISE_WORDS` Set.of() 中 “BEEN”、”JUST”、”THEM” 重复，导致启动崩溃

---

## 阶段 A：RAG 深度建设

> 目标：将知识库从难获取的研报迁移到公开 SEC 财报（10-K/10-Q），并深化 RAG 管线。

### A1. SEC EDGAR 财报接入（Python 端）

- [x] `GET /api/edgar/filings` — 查询最近 N 份财报索引（调用 SEC EDGAR submissions API）
- [x] `GET /api/edgar/filing-content` — 下载并解析单份财报 HTML，拆分为标准 Item 段落
- [x] `GET /api/edgar/xbrl` — 获取结构化财务数据（营收、净利润、资产负债时间序列）

### A2. 财报入库流程（Java 端）

- [x] `EdgarIngestionService`：调 Python 获取分段内容 → 二次分块 → metadata（ticker/filing_type/filing_date/section_name）→ DashScope embedding → Milvus 入库
- [x] `@Tool ingestCompanyFilings(ticker)` — LLM 可调用，触发财报入库
- [x] `@Tool getStructuredFinancials(ticker)` — 直接查 XBRL 结构化数据（不走 RAG）
- [x] `POST /api/docs/edgar/ingest` — REST 手动触发入库
- [x] `DataServiceClient` 新增 EDGAR 方法（filings / filing-content / xbrl）

### A3. 查询改写（Query Rewriting）

- [x] `QueryRewriter`：用轻量 LLM 调用将口语化问题转为适合向量检索的关键词组合
- [x] 集成到 `RagService.retrieve()` 中

### A4. Rerank

- [x] `DashScopeReranker`：调 DashScope `gte-rerank` API
- [x] 检索流程改为：向量检索 top-20 → rerank → 取 top-5 注入 prompt

### A5. 引用溯源

- [x] RAG 结果注入 prompt 时标号 `[1]`、`[2]`，附带来源（ticker、filing_type、section、date）
- [x] System prompt 指示模型使用 `[1]` 标记引用

### A6. Hybrid Search（混合检索）

- [x] `KeywordSearchService`：基于 MySQL FULLTEXT 索引的关键词检索
- [x] `vector_documents` 表新增 `content_full` TEXT 列 + FULLTEXT 索引
- [x] RRF (Reciprocal Rank Fusion) 融合向量检索和关键词检索排名
- [x] 可配置开关 `stocksage.rag.hybrid-search.enabled`

### A7. Metadata Filter（元数据过滤）

- [x] 自动从用户问题中提取已知 ticker（维护常见美股 ticker 集合）
- [x] 构建 Milvus filter expression，缩小向量搜索范围
- [x] 可配置开关 `stocksage.rag.metadata-filter.enabled`

### A8. Parent-Child 分块策略

- [x] Parent chunk（默认 3000 字符，300 overlap）：提供完整上下文，带 `is_parent=true` 标记
- [x] Child chunk（默认 800 字符，120 overlap）：精细检索用，带 `parent_vector_id` 引用
- [x] 优先在段落、换行、句子边界切分，并合并过短尾块
- [x] 检索时命中子块 → 通过 `parent_vector_id` 追溯父块 → 返回完整上下文

---

## 阶段 B：多 Agent 架构

> 目标：构建分层多 Agent 系统（参考 TauricResearch/TradingAgents 架构），按问题复杂度分层调度。
> 6 个 Agent = 3 Analyst + 2 Researcher + 1 Research Manager，外加 Coordinator 路由层。

### B1. Coordinator（意图分类 + 分层调度）

- [x] `Coordinator`：LLM 意图分类，按复杂度分流
  - 知识类问题（”什么是市盈率？”）→ Coordinator 直接回答
  - 单点查询（”NVDA 今天涨了多少？”）→ 只调 Market Agent
  - 单点财报（”苹果的风险因素”）→ 只调 Fundamentals Agent
  - 深度分析（”苹果值不值得投资？”）→ 全流程：Analysts → 辩论 → Report
- [x] ChatService 改为调 Coordinator 而非直接调 ChatClient
- [x] 移除 ReActPlanningService / ReActAgent（被 Coordinator 替代）

### B2. Analyst 层（3 个专业分析 Agent）

- [x] `Fundamentals Agent`：SEC 财报深度分析，配备 RAG 管线
  - Tools: `ingestCompanyFilings`, `getStructuredFinancials`
- [x] `Market Agent`：实时行情与技术分析
  - Tools: K 线、财务指标、技术指标、对比分析、IBKR 只读
- [x] `News Agent`：新闻与市场情绪
  - Tools: `webSearch`, `newsSearch`
- [x] 从 StockTools 拆分为 `FundamentalsTools` / `MarketTools` / `NewsTools`

### B3. Research 层（Bull/Bear 辩论 + Research Manager）

- [x] `Bull Researcher`：基于 Analyst 报告构建看多论据
- [x] `Bear Researcher`：基于 Analyst 报告构建看空论据
- [x] 辩论循环：Bull/Bear 多轮交替辩论（可配置最大轮数）
- [x] `Research Manager`：裁判，综合双方辩论输出结构化 `InvestmentReport`
- [x] 结构化输出 Schema：`InvestmentReport`（recommendation + rationale + risk_factors + citations）

### B4. Agent 基础设施

- [x] `AgentConfig`：6 个 ChatClient bean，各有专属 system prompt
- [x] Agent 间状态传递：定义 `AnalysisState` 承载各 Agent 输出
- [x] Trace 集成：每个 Agent 的工具调用和辩论过程写入 trace

### B5. 验证

- [x] 知识类：”什么是市盈率” → Coordinator 直接回答，不调 Agent
- [x] 单点查询：”NVDA 最近 K 线” → 只调 Market Agent
- [x] 单点财报：”苹果的风险因素” → 只调 Fundamentals Agent
- [x] 深度分析：”苹果值不值得投资” → 3 Analyst + Bull/Bear 辩论 + Report

---

## 阶段 C：交付材料

- [x] 架构图（重点展示 RAG 管线和多 Agent 协调）
- [x] 简历项目描述
- [x] 面试高频问答素材
- [x] 演示脚本

---

## 阶段 D：竞品借鉴 —— 可靠性与可信工程（来自 juanjuandog/FinSight-AI）

> 背景：FinSight-AI 与 StockSage 同生态位（Java Spring + Python AI sidecar 的股票研究 RAG，且同为校招作品集）。
> 调研结论：它的**评测/多 Agent/多市场证据**比我们薄（它 RAG 评测仅 3 例、无真多 Agent、仅 A 股），
> 但它的**后端可靠性工程与报告可信度**值得借。只选择性借，不整体迁移到 RabbitMQ/pgvector/MinIO/ES。
> 取舍判据：是否强化"后端/Agent 岗"面试叙事，且构建成本可控。

### D-P0：报告防腐（dataSnapshotHash + reportVersion）—— 改动小、面试点最亮

- [x] 为投资报告/辩论产出引入 `dataSnapshotHash`：对稳定行情/指标 + 基本面/风险 + 命中证据做复合哈希；新闻/web 搜索文本只进报告上下文，不进入复用哈希，避免搜索结果抖动让缓存永不命中
- [x] 引入 `contextHash`（prompt 组成快照）与按 ticker 递增的 `reportVersion`
- [x] 缓存复用规则：仅当快照一致才复用，底层数据一变即生成新版本，杜绝陈旧结论
- [x] 持久化报告版本（含 model 元信息 + 生成时间戳），Workbench 可查历史版本
- [x] 持久化真实 `modelName`，并对同一 snapshot 并发写入的唯一键冲突做回查复用兜底

### D-P1：轻量研究任务状态机（幂等 + 单飞 + 重试）—— 不上 MQ，先做核心语义

- [x] 持久化 `ResearchTask`：`idempotencyKey / status / stage / attempts / payload / error / 时间戳`
- [x] `status`（生命周期）与 `stage`（管线位置：取数→指标→检索→Agent→报告）分离
- [x] 仓储层 `createIfAbsent` + 唯一约束兜底，防重复提交
- [x] 单飞租约：优先复用现有 Redis（Lua 原子 check-set + owner/lease token）；无 Redis 时回退进程内 single-flight map
- [x] 失败恢复：定时扫描卡死任务，按 attempts 决定重投或标记失败（失败可见，不静默）

### D-P2：离线可演示与可复现（降低递作品集门槛）

- [x] 确定性/规则兜底报告路径：无 DashScope key 时仍能产出结构化（降级）结论，demo 不开天窗
- [x] 不依赖实时行情/SEC 的公开示例数据集 + 一键 smoke，让招聘方 clone 即跑

### 已领先项 —— 维持，勿因对标而退化

- [x] RAG 评测：50 条手标金标 + 7 类 + recall/MRR/nDCG/citation 等（对方仅 3 例）→ **保持领先，不缩水**
- [x] 多 Agent Bull/Bear 辩论（对方为线性管线，多 Agent 仅在其 roadmap）→ **核心差异化，保留**
- [x] 多市场行情 + 美股 SEC 深证据层（对方仅 A 股、数据薄）→ 保持

---

## 阶段 E：Workbench 图表优先研究台（借鉴 FinSight 的产品呈现）

> 背景：FinSight-AI 的 K 线/股票研究台第一印象更强，优势不在底层 Agent/RAG，而在“选股即看图、图旁有 AI 摘要、报告与状态可见”。
> 取舍：保留 StockSage 暖色研究台视觉、Bull/Bear 多 Agent、SEC/RAG 证据链和多市场能力；只借它的 chart-first 产品组织方式，不引入黑色面板或新图表库。

### E-P0：股票研究 cockpit —— K 线默认首屏展示

- [x] `/workbench` 研究页改为 chart-first：选中 watchlist ticker 后自动加载 K 线，不再只依赖聊天工具调用后才出现图表
- [x] 后端新增只读聚合接口 `GET /api/workbench/stocks/{ticker}/cockpit?period=daily&days=120`
- [x] A/HK 复用 `MarketTools.getStockKLine`，美股复用 `getIbkrHistoricalBars`；无数据或 IBKR 未启用时返回可见降级状态
- [x] 抽出 K 线 JSON 映射，让聊天 SSE chart 和 Workbench cockpit 共用 candlestick payload contract

### E-P1：报告、任务和证据可视化

- [x] 在 cockpit 右侧显示最新投资报告摘要、`reportVersion`、`dataSnapshotHash`、`contextHash`、`modelTier/modelName` 和生成时间
- [x] 展示 ResearchTask timeline：`status/stage/attempts/resultReportVersionId/timestamps/error`，不暴露 `leaseToken`
- [x] 从最新 `InvestmentReport.reportJson` 中提取 `evidenceItems/citations`，显示小型 evidence preview
- [x] 保留“研究 / K 线 / 风险 / 备忘录”按钮作为聊天深度研究入口

### E-P2：离线 demo 兜底（后置）

- [x] 准备 API-independent sample dataset，让招聘方 clone 后即使没有实时行情/SEC/DashScope key，也能看到 cockpit 样例
- [x] 与 D-P2 的离线报告兜底合并设计，不提前引入额外基础设施

---

## 阶段 F：Agent 决策与运营闭环（借鉴 EchoMind）

> 背景：StockSage 当前已经是“LLM Coordinator 优先、确定性规则兜底”的 Plan-and-Execute，
> 不是纯关键词路由。本阶段不重写现有 Agent 主链，而是拆分意图识别与执行规划，
> 补齐决策解释、统一评测、Skill 运维和有来源的跨会话研究记忆。
>
> 演进策略：保留 `Coordinator.plan(...)` 作为兼容门面，采用 branch-by-abstraction 和 shadow mode；
> 新旧决策并行比较并通过门禁后才切换，不允许 LLM 绕过 Action 白名单、必要步骤补全和 IBKR 只读边界。

### F0（P0）：修正执行计划的路由契约

- [x] 在 `ExecutionPlan` 中增加显式 `PlanRoute route`，保留兼容构造器，避免现有测试和调用点一次性破坏
- [x] `Coordinator.parsePlan(...)` 与确定性 fallback 都显式写入 `route`，不再依赖自由文本 `taskType`
- [x] `SkillResolver` 直接读取 `executionPlan.route()`；禁止通过中文任务名称反推 `PlanRoute`
- [x] 新增真实链路回归：中文 `taskType=新闻与事件分析` 的 NEWS 计划仍能解析并命中默认 NEWS Skill
- [x] 检查所有 `new ExecutionPlan(...)` 调用点，统一 `route/taskType/actions/modelTier` 的语义

验收：

- [x] LLM 路由和规则兜底生成的 NEWS 计划都能进入 `latest-news-mcp -> local-latest-news` 回退链
- [x] 未知模型 route 安全归一化，不能产生未注册动作
- [x] 现有 Coordinator、Skill、ToolPrefetch 和 DEEP 提交测试保持通过

### F1（P0）：路由决策可解释性与 Trace

- [x] 新增详细 `RoutingDecisionMetadata`：`decisionSource / rawRoute / route / intentSummary / rationale / confidence / matchedSignals / ragHitCount / fallbackReason / durationMs`
- [x] 决策来源使用稳定枚举：`ROUTING_LLM / DETERMINISTIC_FALLBACK`
- [x] `ExecutionPlan` 携带脱敏后的决策元数据；不记录完整 prompt、模型原始思维链或用户私密正文
- [x] 扩展 `AgentStep` 的可选结构化 attributes，保持旧 Trace JSON 可反序列化
- [x] 增加 `route_decision` SSE/Trace 展示：LLM 理解的意图、原始/最终 route、依据、confidence、RAG 命中、决策来源、是否降级、耗时
- [x] 增加低基数指标：按 `source/route/outcome` 记录次数、失败和耗时；禁止使用 userId、ticker、query、traceId 作为指标标签

验收：

- [x] 任意对话 Trace 都能解释“为何走该 route、是否使用 fallback”
- [x] Trace 持久化并在重启后可读取；旧 Trace 不因新增字段解析失败
- [x] 响应、日志、SSE 和指标中不出现密钥、MCP URL、完整 prompt 或原始异常栈

### F2（P0）：单一 LLM 路由决策器

- [x] 只保留一个 fast-model Coordinator 路由调用，输入为当前用户问题、RAG 命中数和最多 3 条受限检索摘要
- [x] 路由模型先用自然语言概括 `intent`，再输出 `route / rationale / confidence`；不引入第二套 typed Intent，route 固定为 `DIRECT / MARKET / FUNDAMENTALS / NEWS / DEEP`
- [x] `PlanAction / ModelTier / taskType` 全部由后端按 route 固定映射，模型不能生成工具名、Agent 或模型名
- [x] 路由专用配置使用低温度和 256 token 小预算，不复用最终回答模型的全局生成参数
- [x] 未知 route 安全归一化为 DIRECT；空响应、非法 JSON、网络或 provider 异常进入确定性 fallback
- [x] 保留关键词规则作为可用性和安全兜底，不再承担正常请求的主语义识别
- [x] 删除独立 `IntentType/IntentRecognitionService/IntentPlanAssembler` 以及 `LEGACY/SHADOW/ACTIVE` 双轨架构
- [x] DEEP 等执行工作流仍由后端动作白名单决定，不交给 LLM 改写

验收：

- [x] 模型超时、空响应、围栏 JSON、未知枚举和网络异常均能稳定 fallback
- [x] 用户问题和 RAG 摘要只触发一次路由模型调用
- [x] 模型输出额外的 `actions/modelTier` 字段也不会改变后端固定执行计划
- [x] Trace 能区分 `ROUTING_LLM` 与 `DETERMINISTIC_FALLBACK`

### F3（P0）：统一 Agent Eval、基线和切换门禁

- [x] 新增 typed `PlannerEvalCase/Request/Result/Response`，断言使用 `PlanRoute/PlanAction`，不再比较展示文案
- [x] 新增 `POST /api/eval/agent/planner`，支持 `DETERMINISTIC / LIVE_COORDINATOR`
- [x] 让现有 4 条 `CoordinatorRegressionService` 用例委托给 typed evaluator；保留旧 `/api/chat/regression/**` 兼容入口
- [x] 新建 `rag-eval/agent_golden_set.jsonl`，至少 100 例，覆盖五条现有 route、复合问题、上下文追问、中英混合、模糊请求、无 ticker 和 prompt injection
- [x] 每例只保存：expectedRoute、requiredActions、forbiddenActions、是否 critical；不得收录真实用户隐私
- [x] 新增 `run_agent_eval.py / agent_eval_summary.py / agent_eval_gates.json`，统一输出 `agent_eval_v1`
- [x] 参考 EchoMind `EndToEndEvaluator`：新增 route Macro-F1、逐 route Precision/Recall/F1、逐例 intent/confidence/reasoning，并输出可操作优化建议
- [x] 为 EchoMind 式单轮/多轮回答质量评测保留 `end_to_end` 分区；未提供 LLM-as-Judge 报告时必须明确标记 `not_run`
- [x] 统一报告聚合 Planner、既有 RAG、可选 Trace 完整性、延迟和基线 delta；未运行的分区必须标为 `not_run`
- [x] `EvalDesk` 兼容导入旧 RAG JSON 和新统一报告，展示失败用例、缺失/多余 Action 与基线变化
- [x] LLM Judge 只作补充指标，不作为唯一质量门禁

路由模型上线与持续回归门禁：

- [ ] route accuracy `>= 0.95`
- [ ] required action recall `>= 0.98`
- [ ] forbidden action rate `= 0`
- [ ] critical 用例误路由 `= 0`
- [ ] 相比确定性基线的关键指标下降不超过 `0.02`
- [ ] 结构化输出经校验后的可执行率 `= 1.00`
- [ ] LIVE_COORDINATOR 路由 P95 不超过既有线上基线的 `1.2x`
- [ ] 任一门禁失败时 CLI 以非零退出码结束；运行时 provider 失败继续走确定性 fallback

### F4（P0–P1）：只读 Skill / Capability / MCP 管理与监控

- [x] 新增 `GET /api/admin/agent/skills`：展示 id/version/routes/mode/model tier/policy/steps/fallback 链和当前默认关系
- [x] 新增 `GET /api/admin/agent/runtime`：展示脱敏 Capability、MCP、Skill 当前进程状态和稳定错误码
- [x] `/api/admin/**` 同步接入 `SecurityConfig` 与 `AdminApiInterceptor`；无 Admin Token 返回 403
- [x] MCP 状态只返回 `DISABLED/UNCONFIGURED/READY/DEGRADED`、approved tool 数和协议版本；不返回 URL、token、原始参数或完整 tool schema
- [x] 新增 `SkillExecutionObserver`，记录 `SUCCESS/FALLBACK_SUCCESS/LEGACY_PATH/FAILED`、调用数、fallback 数和耗时
- [x] Capability Timer 启用可用的 percentile/histogram，再展示 P95；无样本显示 `NO_DATA`，不能显示为 0% 成功率
- [x] 在 `EvalDesk` 增加 Skills 与 Capabilities/MCP 开发者面板；Admin Token 只保存在当前页面内存
- [x] MCP 关闭显示“已关闭”而非系统故障，管理接口不得为了刷新状态主动调用外部工具
- [x] V1 不做 Skill 在线编辑、启停、上传、热更新、任意 MCP URL 注册或基于延迟自动修改语义 route
- [ ] V1 稳定后再评估“完整候选集校验 -> 原子切换 -> 审计记录”的热更新

验收：

- [ ] 当前两个 NEWS Skill、默认 Skill 和 fallback 链完整可见
- [ ] MCP 成功、MCP→本地 fallback、双失败回 legacy 三条路径都有正确 Trace 与指标
- [ ] 管理 API 和前端状态中扫描不到 query、userId、traceId、token、URL 和原始异常栈
- [ ] 后端不可用、403、无指标和 MCP disabled 都有不同且准确的 UI 状态

### F5（P1）：有来源的跨会话研究结论记忆

- [x] 建立独立 `ResearchMemoryEntry` 域，不复用 `LongTermMemory` 用户画像，也不写入全局 `stocksage_docs`
- [x] 首期唯一允许来源是已持久化的 `InvestmentReportVersion`，且至少含 citation 或非空 `EvidenceItem.source`
- [x] 排除普通聊天回答、短期摘要、画像摘要和 `DEMO/offline-rule-fallback` 报告
- [x] Agent 不获得“写记忆”工具；报告持久化成功后由后端事件确定性生成 memory text
- [x] MySQL 保存真值和审计字段：用户、ticker、来源报告/会话/trace、来源引用、数据截止时间、snapshot/content hash、向量状态
- [x] 唯一约束使用 `user_id + source_type + source_id`，保证重复事件幂等
- [x] 使用独立 Milvus collection `stocksage_user_research_memory_v1`；关系表是真值，向量索引必须可重建
- [x] Milvus metadata 强制 tenant filter，可叠加 ticker；不得把原始 userId 拼入可注入 filter 表达式
- [x] 遵守 embedding 每批最多 10 条；模型或维度变化时新建版本化 collection，不原地混写
- [x] 写向量失败不得影响报告保存；使用 `PENDING/INDEXED/FAILED/REVOKED` 和补偿任务恢复
- [x] 检索 Top 3、总注入不超过 2400 字符；先以 shadow retrieval 只写 Trace，不进入 Prompt
- [x] 正式注入时使用独立 `[M1]` 引用，并标记为“可能过期的历史研究证据”；当前 RAG/工具数据冲突时必须以当前证据为准
- [x] Trace 只记录 memory entry id、ticker、score、age 和 source 数量，不记录私人记忆正文
- [x] 新增用户自主管理接口：只读列表和撤销/删除；用户身份只能来自 `RequestIdentity`
- [x] 使用四个独立止损开关：`capture/index/retrieve/inject`，默认全部关闭并按顺序灰度

验收：

- [ ] tenant leakage `= 0`
- [ ] 无来源记忆捕获 `= 0`
- [ ] 同一报告重复记录 `= 0`
- [ ] Milvus 故障时报告保存成功率 `= 1.00`，恢复后可补偿索引
- [ ] 记忆 golden set `Recall@3 >= 0.80`
- [ ] 暖机后记忆检索新增 P95 `<= 300ms`
- [ ] 删除/撤销后立即不再召回
- [ ] 新旧证据冲突用例采用当前数据并明确说明历史结论已过期
- [ ] 开启记忆后现有 RAG Eval gate 不回退

### 阶段 F 推荐提交顺序

1. [x] `F0` 路由契约修复
2. [x] `F1` 决策元数据、Trace 与指标
3. [x] `F3` typed Planner Eval API 和 golden set 骨架
4. [x] `F2` Intent Recognizer + SHADOW
5. [x] `F3` 统一报告、基线比较和 ACTIVE 门禁
6. [x] `F4` Skill/Capability 只读 API、指标与前端
7. [x] `F5` 研究记忆 capture/index
8. [~] `F5` shadow retrieval、评测、用户管理和受控注入（代码完成；live Milvus golden-set 门禁待运行）

每个提交必须满足：

- [ ] 一次只落一个可回退切片，不同时重写 Coordinator、Eval、Skill 和 Memory
- [ ] Agent/prompt 行为变化必须附对应 regression/eval 证据
- [ ] 先运行相关 targeted tests，再运行 `.\init.ps1 -Mode fast`
- [ ] 涉及 Milvus/MySQL/Redis 的切片补对应集成测试；未运行 live acceptance 时必须明确记录
- [ ] 更新 `progress.md`；只有功能状态实际变化时才更新 `feature_list.json`

---

## 阶段 G：投研运行时完成 Harness

> 状态：G0–G4 的功能基础已实现，H0 已关闭崩溃恢复、人工否决信任边界和
> Docker/Testcontainers/PIT 门禁。按用户要求，当前 live 在 5 个 case 后停止；
> 5/5 业务任务成功，但一次受控 runner 断开暴露并修复了后台 Trace 归属缺陷，
> 修复后尚未追加 live 复验。历史单例、中断运行和 5-case smoke 都不满足当前
> 30-case 发布门禁。H1 和 G5
> 在 live 明确通过前均未就绪，策略骨架继续暂停接入真实 route。
>
> 设计与逐阶段实施清单：
> [docs/architecture/research-harness-design.md](docs/architecture/research-harness-design.md)
>
> 决策：不复制通用 Agent Loop。新增阶段门禁式 `ResearchHarness`，
> 复用现有 Coordinator、Skill/Capability、ResearchTask、checkpoint、Trace、SSE 和 Eval；
> 第一版只覆盖 DEEP 证据与报告完成判定。

### G0（P0）：结构化契约与只观察运行

- [x] 定义 `TargetIdentity / EvidenceEnvelope / EvidenceLedger`
- [x] 定义 `ResearchCompletionPolicy / HarnessDecision / RecoveryAction`
- [x] 实现 `DeepResearchCompletionPolicy` 并完成只观察阶段
- [x] 保持 G0 用户输出不变并补序列化、规则表和脱敏测试

### G1（P0）：DEEP 证据门与定向恢复

- [x] 后台 worker 与 inline fallback 共用同一 Policy
- [x] 基本面和行情均通过才允许完整五档评级
- [x] 每个缺失证据维度最多补采一次
- [x] 证据不足输出“暂不评级”，不把降级映射为 HOLD
- [x] 只展示本次真实 Ledger 来源

### G2（P0）：报告门与一次 Manager 修复

- [x] `ResearchManager` 返回 `SynthesisResult`
- [x] EvidenceItem 增加 `sourceEvidenceIds`
- [x] 校验结构、引用集合、标的一致性和缺失维度声明
- [x] Manager 最多重新综合一次，Bull/Bear 不重跑
- [x] 非法 JSON 不能成为有效 HOLD

### G3（P0–P1）：恢复、终态、缓存与记忆闭环

- [x] HarnessSnapshot 严格 owner-fenced 写入现有 checkpoint
- [x] 新增 `ResearchTask.ResultKind`，不改变 Status/Stage
- [x] policy version 和稳定 Evidence hash 纳入缓存验收
- [x] cache hit 返回前重新验收
- [x] Research Memory 只摄取 VERIFIED/FULL_REPORT
- [x] 离线报告明确标为 OFFLINE_FALLBACK
- [x] 证据补采与报告重综合的 pending recovery 在 takeover 后继续执行并重新验收
- [x] REJECTED/NEEDS_RESEARCH 报告退出 cache reuse 并撤销 Research Memory

### G4（P1）：Harness Eval、前端解释与强制切换

- [x] 将 Harness Golden Set 扩展为严格 V2：80 个语义唯一 case（Evidence 48 / Report 32），精确断言完整 decision contract，并对数量、覆盖、重复 fixture 与数据集 hash 设硬门禁
- [x] Trace/Workbench 展示验收、补采、降级和业务终态
- [x] 离线确定性门禁中关键违规拦截、无证据推荐、恢复越界和 owner-fence 违规均为 0
- [x] Eval 门禁通过后删除旧 OR/string 判断和临时观察开关
- [x] 固化真实 HTTP/SSE DEEP 验收器，并把安全终态、ResultKind、Harness Trace、恢复预算和延迟接入 Agent Eval

### G5（P1）：扩展 MARKET、NEWS 与 RAG

- [ ] 将已加入的 Market/News CompletionPolicy 骨架补齐 as-of、样本量、target/time-window 规则并接入 route
- [ ] 将已加入的 RAG CompletionPolicy 骨架补齐 target/citation/no-answer 规则并接入 route
- [ ] DEEP evidence 逐步迁移到现有 CapabilityGateway
- [ ] 至少两个策略稳定后，再评估 Skill `completionPolicyId`
- [x] LLM-as-Judge 仅用于离线 Eval，不进入请求热路径

> 2026-07-24 的真实 NVDA DEEP 任务得到 `FULL_REPORT`，evidence/report 均为
> `PASS`，同期 `verify -Pit` 通过 15/15；但该 live 数据集只有 1 例，早于当前
> 30-case/hash/policy metadata 门禁，只能说明链路曾经跑通。当前 H0
> `clean verify -Pit` 已通过 Surefire 312/312、Failsafe 17/17；5-case smoke
> 仅用于诊断，固定 30-case live 尚未完成；H1/G5 继续暂停。

---

## 阶段 H：完整 Agent Harness 的渐进式控制面

> 目标架构、边界和规则治理见
> [docs/architecture/research-harness-design.md](docs/architecture/research-harness-design.md)。
> 原则是“稳定外壳 + 自由探索内核”：约束权限、状态、证据、终态和发布，不枚举模型
> 的全部研究路径。

### H0（P0）：关闭现有安全不变量

- [x] recovery snapshot 增加稳定 effect key、动作集合和 durable lifecycle
- [x] pending Evidence RECOVER 接管后补采并重新执行 Evidence Gate
- [x] pending Report RECOVER 接管后只使用已预留的一次 Manager 修复预算
- [x] Evidence 重新验收时保留 suspended Report recovery，跨阶段预算与 effect key 不丢失
- [x] 所有非空 checkpoint 先执行当前 Evidence Gate；当前 PASS 重新计算 evidence hash
- [x] 旧 RECOVER checkpoint 缺少新字段时 fail-safe
- [x] cache reuse 对持久化原始 JSON、当前 Ledger 和当前 Policy 执行完整 Report Gate
- [x] report/message/task owner CAS 原子发布；commit 后才清 checkpoint、发 SSE 和捕获记忆
- [x] REJECTED/NEEDS_RESEARCH 阻断复用、摄取并撤销已有 Research Memory
- [x] Research Memory capture 锁定重读当前审核状态，向量新增/删除只在 DB 提交后执行
- [x] live Harness `--fail-on-gate` 仅允许 `pass` 返回 0
- [x] live Harness 按 logical effect 计恢复预算，并单列 at-least-once replay 次数
- [x] 缺少稳定 effect key 的恢复 Trace 只能诊断，不能通过 release gate
- [x] 固定 30-case manifest、policy id/version 和跨 CRLF/LF 稳定的规范化 JSONL hash
- [x] SSE 背压只允许丢弃 heartbeat，工具/领域事件和回答 token 保持无损
- [x] Bull/Bear/Manager 模型阶段增加默认 300 秒外层硬截止并取消超时上游
- [x] worker 失败后先释放 lease/执行占用，再进入本地快速重试；Redis PEL 保留持久兜底
- [x] live runner 原子持久化逐 case checkpoint，断流后对账且不重复提交
- [x] 后台任务 Trace 终态由 task pipeline 写入；SSE 断开不再把成功任务标为 cancelled
- [x] live runner 支持 release-ineligible 的 `--case-limit` smoke，且不会提交超出上限的 case
- [x] 重跑完整 fast harness
- [x] 重跑 80-case production Policy eval
- [x] 重跑非容器 `CheckpointTakeoverIT`
- [x] 在 Docker 环境重跑完整 Testcontainers/PIT（`clean verify -Pit`：
  Surefire 312/312、Failsafe 17/17）
- [ ] 运行当前固定 30-case DEEP live release gate（5-case 诊断已停止；
  修复后的完整 release gate 尚未运行，不得以 smoke 冒充通过）

### H1（P1）：DEEP walking skeleton 完整化

- [ ] 冻结最小 `ResearchRunSpec/PolicyBundle`，运行期间不允许 policy drift
- [ ] DEEP 外部证据调用逐步收口到现有 `CapabilityGateway`
- [ ] Trace 统一记录 policy、effect、预算和业务终态
- [ ] Research Memory 物理向量删除增加有界重试与 Outbox/DLQ

### H2（P1）：其他 route 先 Shadow

- [ ] NEWS、MARKET、RAG 分别建立强类型 fixture、golden set 和 live cases
- [ ] Shadow 不改变用户响应，达到各自门禁后再逐个 Enforce

### H3–H4（P1–P2）：Claim Ledger 与生产证据

- [ ] 关键财务数字建立 claim → evidence → source field 确定性链路
- [ ] 完成真实双进程接管、provider/Redis/MySQL 故障、容量与 SLO 验证
- [ ] 每月或重大事故/Eval 后审查规则，支持 SHADOW/ENFORCED/RETIRED 收敛

---

## 待定项（低优先级）

- [x] 提示词再收敛一轮
- [x] 前端细节 polish
- [x] 上下文压缩接入 LLM 摘要
- [x] 长期画像提取接入 LLM
- [x] 补齐 IBKR placeholder 能力
