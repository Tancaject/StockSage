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

## 待定项（低优先级）

- [x] 提示词再收敛一轮
- [x] 前端细节 polish
- [x] 上下文压缩接入 LLM 摘要
- [x] 长期画像提取接入 LLM
- [x] 补齐 IBKR placeholder 能力
