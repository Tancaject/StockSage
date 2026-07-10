# 后端一期重构：结构拆分 + 强类型契约 + 工程卫生（2026-07-04）

## 背景与目标

2026-07-04 后端架构审视结论：韧性设计（缓存/熔断/租约/幂等）高于校招平均水平，但代码组织落后——核心是 2383 行的 ChatService 上帝类、裸字符串内部契约、以及"克隆即跑"故事缺失。

**一期目标：行为保持不变的结构重构 + 工程卫生。** 对外 API、SSE 流格式、提示词内容、trace 步骤文本全部保持现状。

**非目标（明确排除）**：
- DEEP 研究后台化 + 断线重连（二期）
- 数据诚实化（独立线，spec 另存）
- RestClient 迁移、全反应式改写
- 多空辩论 showcase 的任何行为改动

## T1 拆 ChatService（2383 行 → 6 个职责单一的类）

全部放在现有 `com.stocksage.service` 包（不新建目录层级）。方法体原样搬家，不顺手改逻辑。

### 1.1 `TickerResolutionService`（新）
移入：`US_TICKER_PATTERN`、`NON_TICKER_TERMS`、`GENERIC_STOCK_QUERY_TERMS`、`TICKER_SECTOR_MAP`、中文公司名→ticker 硬编码表（resolvePrimaryTickerFromText 开头的 if 链）、`resolvePrimaryTicker(query[, conversationId])`、`resolvePrimaryTickerFromText`、`stockSearchQuery`、`resolvePrimaryTickerWithSearch`、`isLikelySecTicker`、`resolveSectorForTicker`、`firstNonBlank`。
依赖：MessageRepository、MarketTools、ObjectMapper。
新增公开方法 `containsLikelyTicker(String)` 供 Coordinator 复用（见 T3）。

### 1.2 `ImageAttachmentService`（新）
移入：`normalizeImageAttachments`、`normalizeUserMessage`、`decodeImageAttachment`、`normalizeImageMediaType`、`sanitizeAttachmentName`、`formatBytes`、`DecodedImage`、`SUPPORTED_IMAGE_MEDIA_TYPES` 及 3 个 multimodal `@Value`。

### 1.3 `ReportMarkdownRenderer`（新，无状态 @Component）
移入：`buildFinalAnswerBrief`、`buildInsufficientEvidenceReport`、`buildEvidenceFirstInvestmentReport`、`appendReportSectionList`、`appendReportSubList`、`appendEvidenceTable`、`evidenceItemsOrFallback`、`listOrFallback`、`markdownCell`、`normalizeRecommendationLabel`、`recommendationAction`、`evidenceBasis`、`sanitizeUserFacingText`、`firstParagraph`、`blankToDefault`、`shortHash`、`buildResearchTaskAlreadyRunningAnswer`。
纯文本函数，补 JUnit（评级标签、证据表兜底、单元格转义）。

### 1.4 `ToolPrefetchService`（新）
移入：`prefetchPlannedToolContext`、`collectDeterministicDeepContext`、`buildFundamentalsSnapshot`、`buildMarketSnapshot`、`buildNewsSnapshot`、`marketKLineToolName` 等三个工具名方法、`getMarketKLine` 等三个上下文方法、`appendSectorContext`、`buildNewsQuery`、`runPrefetchTool`、`isToolFailureOrEmpty`、`appendSnapshotItem`、`appendContextSection`、`appendAgentObservation`、`appendToolObservation`、`appendResolvedStockIdentity`、`withResolvedTicker`、`safeAgentCall`、`completedValue`、`asyncIngestSearchResults`、`isDeepResearchPlan`、`emitProgress`、`PreparedToolContext`（公开 record）、`EvidenceSnapshot`、`DeepEvidenceResult`、函数式接口，及 prefetch 相关 `@Value`。
`truncateForPrompt` 提为静态工具（`service` 包内 `PromptText.truncate`），Prefetch 与 Renderer 共用。

### 1.5 `DeepResearchPipeline`（新）
移入：`runResearchDebateWithTask`、`tryPersistOfflineFallbackReport`（保持包私有可测性）、`startResearchTaskHeartbeat`。
心跳调度器不再是静态字段（见 1.6）。
`ChatServiceOfflineFallbackTest` 相应移植为 `DeepResearchPipelineTest`（构造依赖从 22 个 mock 降到 4 个）。

### 1.6 线程池交给 Spring 管理（新 `AsyncConfig`）
- `agentTaskExecutor`：ThreadPoolTaskExecutor，6 线程、daemon、前缀 `agent-worker`，替换静态 `AGENT_EXECUTOR`；
- `researchHeartbeatScheduler`：ThreadPoolTaskScheduler，1 线程、前缀 `research-task-heartbeat`，替换静态心跳执行器。
应用关闭时优雅停机；行为等价（线程数、daemon 属性不变）。

### 1.7 瘦身后的 ChatService 保留
`streamChat`、会话 CRUD、`buildPromptMessages`/短期记忆装配、`safeRetrieve` 与 RAG 引用格式化、`estimateTokens`、`buildTemporalSystemPrompt`、会话生命周期 helper（saveMessage/touchConversation/标题生成/replaceLastTurn）、`toJson`。预计 ~800 行。

## T2 强类型内部契约

### 2.1 `PlanAction` 枚举（agent 包）
覆盖现有全部动作字符串（"Fundamentals Agent"、"searchStocks"、"Final Answer" 等），`label()` 返回原字符串，`fromLabel()` 解析。
`ExecutionPlan.actions` 改为 `List<PlanAction>`，另提供 `actionLabels()`；trace observation 与 SSE 内容仍由 label 拼出，字节级不变。
模型输出的未知动作字符串：丢弃并 debug 日志（现状是原样保留但 switch 落 default 忽略；丢弃仅影响 trace 展示文本中的无效项）。
`CoordinatorRegressionService` 与 ChatService/ToolPrefetchService 的 switch 全部改用枚举。

### 2.2 `PlanRoute` 枚举（Coordinator 内部）
DIRECT/MARKET/FUNDAMENTALS/NEWS/DEEP，替换裸字符串 switch。

### 2.3 数据服务错误契约单点化
新增 `com.stocksage.cache.CacheableJson`（或 client 包 `DataServicePayloads`）：`isFailure(String)` 用 Jackson 解析顶层 `error` 字段（`true` 或非空字符串即失败；解析失败视为失败）。
- `DataServiceClient.indicatesSearchFailure` 改用该判定；
- `ToolResultCache` 删除自带 `ERROR_PATTERN`，`getOrFetch` 增加"是否可缓存"谓词参数，由 DataServiceClient 传入同一判定（缓存组件不再内嵌业务协议）。

### 2.4 手拼 JSON 清理
`ResearchTaskService.buildInvestmentReportPayload`/`jsonString` 改用 ObjectMapper 序列化。

## T3 ticker 启发式统一

删除 Coordinator 自带的 `US_TICKER_PATTERN`/`NON_TICKER_TERMS`/`containsLikelyTicker`，注入 `TickerResolutionService`。
词表取两处并集 = ChatService 现有 22 词（Coordinator 的 12 词是其子集）。对 Coordinator 是轻微收紧（ETF/EPS 等不再被当作 ticker 误路由到 MARKET）——这是修偏差，是本 spec 明示的唯一有意行为微调。

## T4 认证收口

`UserController` 删除 `/{userId}/profile` 的 GET/PUT（前端 grep 证实未使用；多用户形态的 API 面配单用户实现是安全错位）。保留 `/me/*`。MemoryController 的 `/users/{userId}/*` 已在 admin 拦截下，不动。

## T5 Coordinator 回归用例入 JUnit

新增 `CoordinatorPlanTest`：直接 `new Coordinator(null, null, null, objectMapper)` 调 `planDeterministically`（不依赖 Spring/网络）。
用例移植自 CoordinatorRegressionService 并**修正陈旧期望**：fundamentals 用例期望的 `ingestCompanyFilings` 早已不在 defaultActions 中（现为 searchCompanyReports/getFinancialReports），该用例在运行时端点上一直是 failed。RegressionService 的期望同步修正；端点保留（admin 拦截下的运行时烟测仍有价值）。

## T6 克隆即跑 + 卫生

- **docker-compose.yml**（仓库根）：mysql:8（挂载 sql/init.sql 初始化）、redis:7、Milvus standalone（etcd + minio + milvus 三容器，官方推荐拓扑）、ollama 用 profile 可选。仅基础设施容器化；Java/Python 应用仍本机跑（开发形态不变）。
- **Flyway**：pom 加 `flyway-core` + `flyway-mysql`（Spring Boot 3.4 BOM 管版本）。**实施修订**：核实发现 sql/init.sql 已是包含全部历史迁移结果的完整当前 schema，若把 8 个历史迁移一并纳入 Flyway，会与完整版 V1 在新库上双重应用冲突。故采用更稳的基线式布局：`db/migration/V1__baseline_schema.sql` = 完整当前 schema（init.sql 去掉 CREATE DATABASE/USE），`baseline-on-migrate=true` + `baseline-version=1`——既有库 baseline 后零待执行，全新空库执行 V1 一次建全；8 个历史迁移与 sql/ 目录保留为文档；未来变更从 V2__ 开始。docker-compose 的 MySQL 不再挂载 init.sql（schema 统一由 Flyway 建立）。
- **日志**：默认 profile 的 `logging.level.*` 从 DEBUG 降到 INFO（提示词与用户内容不再默认进日志）。
- **akshare 锁版本**：requirements.txt 按 .venv 实际安装版本 pin 成 `==`。

## 验证口径

1. `mvn test` 全绿（基线已确认全绿）。
2. 后端本机启动一次：验证 Flyway 对既有库 baseline 成功、对话/工作台接口 smoke；顺带完成此前欠账的端到端验证（自选添加持久化、港股删除、新闻接口）。
3. `docker compose config` 校验语法；有 Docker 环境则 `up` 冒烟。
4. 全程不改前端；SSE chunk 类型与字段不变。
