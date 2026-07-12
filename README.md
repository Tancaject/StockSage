# StockSage

StockSage 是一个本地运行的 AI 投资研究助手，面向求职展示和个人投研演示场景。项目由三部分组成：

- `stocksage-backend`: Java 17 + Spring Boot + Spring AI / Spring AI Alibaba，默认端口 `8080`。
- `stocksage-data-service`: Python FastAPI 金融数据与文档解析服务，默认端口 `8001`。
- `stocksage-frontend`: Vue 3 + Vite + Element Plus 前端，默认端口 `5173`。

> 免责声明：本项目仅用于学习、演示和研究辅助。所有分析内容仅供参考，不构成投资建议。IBKR 集成保持只读边界，不执行下单、撤单或改单。

## 当前状态

已实现的主要能力：

- 对话式投研助手：`POST /api/chat/stream` 使用 JSON 请求体和 SSE 流式响应。
- 多 Agent 分层调度：Coordinator 按问题复杂度分流到直接回答、行情、基本面、新闻或深度研究路径。
- DEEP 后台研究（WS1）：聊天请求只负责受理与订阅，Redis Stream consumer group 驱动证据、辩论、综合和报告落库；MySQL checkpoint 支持按阶段/辩论轮接管，SSE 用 Redis entry id 断线回放。真实双实例数字仍待脚本验证。
- 模型分层路由：FAST / STANDARD / STRONG / VISION 模型配置，前端展示最终回答模型。
- 多模态输入：当前轮可上传或粘贴 PNG/JPG/WebP 图片，图片只用于本轮模型调用，不写入长期历史。
- RAG 管线：SEC EDGAR 财报入库、Parent-Child 分块、向量检索、关键词检索、RRF 融合、DashScope rerank、引用溯源。
- 行情与数据工具：A 股、港股经 Python 数据服务路由；A 股 K 线和技术指标优先使用 AKShare / 东方财富公开数据，BaoStock 保留为兼容降级；A 股板块接口支持 BaoStock 样本聚合；美股行情优先走 IBKR 只读工具，SEC 财报和 XBRL 走 EDGAR。
- 研究工作台：`/workbench` 提供用户画像 watchlist、页内 SSE 研究执行、K 线驾驶舱、事件影响分析、IBKR 只读持仓诊断、对比研究、AI 研究报告库、投资备忘录、RAG / RAGAS eval 快照和运行时健康检查。
- 图表与报告：Chat 内联 K 线图保持依赖-free SVG，并展示 MA5、MA20、成交量均线、区间摘要和 chart provenance；聊天页提供对话导出，Workbench 区分后端 AI 研究报告库和手动投资备忘录。
- 可观测性：后端 trace、工具调用事件、SSE 状态块、可选 Phoenix/OpenTelemetry 上报。
- RAG 评估：50-case golden set、检索评估、回答级评估、结果汇总和质量 gate。

当前本地验证边界：

- `.\init.ps1 -Mode fast` 会依次运行 backend、frontend、python 三类检查。
- 当前 checkout 已包含 Maven wrapper；如果本机没有全局 `mvn`，根脚本会使用 `stocksage-backend\mvnw.cmd` 运行 backend 检查。
- 前端 `npm run test` / `npm run build`、data-service `python -m unittest discover stocksage-data-service\tests` 和 `python -m compileall` 可以作为最小 smoke check。
- 完整 RAG eval 需要 MySQL、Redis、Milvus、Ollama、backend、data-service 全部运行。

## 快速启动

### 基础设施一键启动（推荐）

Java backend、Python data-service、Vue frontend 和 IBKR 继续在宿主机手动运行。Docker 负责 MySQL、Redis、Ollama，并复用本机已有的 `D:\milvus\docker-compose.yml` 与 `attu` 容器，不会创建第二套 Milvus/Attu。

统一入口：

```powershell
.\infra.ps1 up
.\infra.ps1 status
.\infra.ps1 logs
.\infra.ps1 stop
```

`up` 会依次启动现有 Milvus 组、现有 Attu、StockSage MySQL/Redis/Ollama，并在首次运行时自动拉取 Ollama `bge-m3` 模型。Attu 地址为 [http://localhost:8800](http://localhost:8800)，Milvus 保持使用 `localhost:19530`。

如果现有 Milvus Compose 文件以后移动了，可以显式指定：

```powershell
.\infra.ps1 up -MilvusComposePath "D:\new-path\docker-compose.yml"
```

Compose 参数可放在被 Git 忽略的根目录 `.env`：

```powershell
Copy-Item .env.example .env
```

如果宿主机 MySQL 已占用 `3306`，将 `.env` 中的 `STOCKSAGE_MYSQL_PORT` 改成 `3307`，并同步让本机 backend 使用对应数据库端口。

### 应用服务手动启动

以下命令假设仓库根目录为：

```powershell
cd D:\programming\StockSage
```

### 1. 准备本地依赖

需要：

- JDK 17+
- Maven 可使用仓库自带的 `stocksage-backend\mvnw.cmd`
- Python 3.11+
- Node.js 20+
- MySQL 8
- Redis
- Milvus
- Ollama，默认用于本地 embedding provider

Milvus 可用 Docker 单机模式启动：

```powershell
docker run -d --name milvus -p 19530:19530 -p 9091:9091 milvusdb/milvus:latest milvus run standalone
```

### 2. 初始化数据库

新库初始化：

```powershell
mysql -u root -p < sql\init.sql
```

已有库需要按需执行迁移：

```text
sql/migrations/2026-05-07-add-message-trace-id.sql
sql/migrations/2026-05-17-add-message-model-metadata.sql
sql/migrations/2026-05-20-harden-message-and-trace-storage.sql
sql/migrations/2026-06-05-add-investment-report-versions.sql
sql/migrations/2026-06-05-add-research-tasks.sql
sql/migrations/2026-06-11-add-conversation-origin.sql
```

后端使用 `spring.jpa.hibernate.ddl-auto=validate`，表结构不匹配时会启动失败。

### 3. 配置本地密钥

推荐在被 git 忽略的本地配置文件中放密钥：

```text
stocksage-backend/src/main/resources/application-local.properties
```

可从示例文件开始：

```powershell
cd stocksage-backend
Copy-Item .\src\main\resources\application-local.properties.example .\src\main\resources\application-local.properties
```

常用配置项：

```properties
spring.ai.dashscope.api-key=your-dashscope-api-key
spring.datasource.password=your-mysql-password
stocksage.admin.token=local-admin
stocksage.data-service.base-url=http://localhost:8001
stocksage.ibkr.enabled=false
```

也可以用环境变量覆盖：

```powershell
$env:DASHSCOPE_API_KEY="your-dashscope-api-key"
$env:STOCKSAGE_DB_PASSWORD="your-mysql-password"
$env:STOCKSAGE_DATA_SERVICE_BASE_URL="http://localhost:8001"
$env:STOCKSAGE_IBKR_ENABLED="false"
```

不要把真实密钥提交到仓库。`application-local.properties` 是本地文件。

### 4. 启动 Python 数据服务

```powershell
cd D:\programming\StockSage\stocksage-data-service
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
uvicorn main:app --port 8001 --reload
```

健康检查：

```powershell
Invoke-RestMethod http://localhost:8001/health
```

BaoStock 登录失败不会阻止 FastAPI 服务启动；`/health` 会暴露其后台登录状态。

### 5. 启动 Java 后端

```powershell
cd D:\programming\StockSage\stocksage-backend
.\mvnw.cmd spring-boot:run
```

首次运行 wrapper 会把 Maven 3.9.11 下载到用户目录的 `.m2\wrapper\dists`，不需要单独把 Maven 加到全局 `PATH`。

### 6. 启动前端

```powershell
cd D:\programming\StockSage\stocksage-frontend
npm install
npm run dev
```

默认页面：

- Chat: [http://localhost:5173](http://localhost:5173)
- Workbench: [http://localhost:5173/workbench](http://localhost:5173/workbench)
- Backend: [http://localhost:8080](http://localhost:8080)
- Data service docs: [http://localhost:8001/docs](http://localhost:8001/docs)

### 7. 初始化知识库

确保 backend、data-service、Milvus、Ollama 都已启动，然后执行：

```powershell
cd D:\programming\StockSage
powershell -File .\stocksage-backend\ingest.ps1
```

也可以直接触发 EDGAR 入库：

```powershell
Invoke-RestMethod -Uri "http://localhost:8080/api/docs/edgar/ingest?ticker=AMZN&type=10-K&count=3" -Method Post -Headers @{"X-StockSage-Admin-Token"="local-admin"} -TimeoutSec 600
Invoke-RestMethod -Uri "http://localhost:8080/api/docs/edgar/ingest?ticker=AMZN&type=10-Q&count=2" -Method Post -Headers @{"X-StockSage-Admin-Token"="local-admin"} -TimeoutSec 600
```

## 验证命令

推荐入口：

```powershell
.\init.ps1 -Mode fast
```

单项检查：

```powershell
.\init.ps1 -Mode frontend
.\init.ps1 -Mode python
.\init.ps1 -Mode backend
```

等价的底层命令：

```powershell
cd stocksage-frontend
npm run test
npm run build
```

```powershell
python -m compileall stocksage-data-service\main.py stocksage-data-service\app
python -m unittest discover stocksage-data-service\tests
python -m unittest discover rag-eval
```

```powershell
cd stocksage-backend
.\mvnw.cmd test
```

WS1 双实例验证入口（需要先启动共享 MySQL/Redis；可先只查看清单）：

```powershell
.\scripts\dual-instance-demo.ps1 -ChecklistOnly
.\scripts\dual-instance-demo.ps1
```

脚本在 `8080` / `8081` 启动两个后端子进程，日志写到 `tmp/dual-instance/`，退出时只停止它启动的进程树。设计取舍和未完成的实测项见 [WS1 决策记录](docs/superpowers/specs/2026-07-09-ws1-decisions.md)。

如果宿主机 `3306` 已被本地 MySQL 占用，可保持默认配置不变，仅为本次 Compose/脚本验证改用其他端口：

```powershell
$env:STOCKSAGE_MYSQL_PORT="3307"
docker compose up -d mysql redis
.\scripts\dual-instance-demo.ps1 -MySqlPort 3307
```

## 架构概览

```text
用户问题 / 图片
  |
  v
Vue Chat / Workbench
  |
  | POST /api/chat/stream
  v
ChatController -> ChatService
  |
  +-> ShortTermMemory / LongTerm Profile
  +-> RagService -> QueryRewriter -> Vector + Keyword -> RRF -> Rerank
  +-> Coordinator -> DIRECT / MARKET / FUNDAMENTALS / NEWS / DEEP
  |       |
  |       +-> Fundamentals Agent -> SEC EDGAR / RAG / XBRL
  |       +-> Market Agent -> K-line / technical / IBKR read-only
  |       +-> News Agent -> web/news search
  |       +-> DEEP submit -> MySQL ResearchTask -> Redis Stream consumer group
  |                              |
  |                              v
  |                         background worker
  |                              |
  |                              +-> evidence -> Bull / Bear -> Research Manager
  |                              +-> MySQL checkpoint / report / conversation message
  |                              +-> per-trace Redis Stream
  |                                         |
  |                     Last-Event-ID replay + live relay
  |
  v
SSE chunks: meta / thought / action / observation / chart / model / answer / task-final
```

DEEP 的消息体只在队列中携带 `taskId`，任务内容与终态以 MySQL 为事实源；DB 唯一键负责提交防重，租约负责消费防双跑，checkpoint 负责崩溃后的阶段幂等。Redis 不可用时提交与事件路径回退单实例同步执行/进程内直推，不承诺跨实例回放。

## 项目结构

```text
StockSage/
├─ stocksage-backend/          # Spring Boot 主服务
│  ├─ src/main/java/com/stocksage/agent/
│  ├─ src/main/java/com/stocksage/client/
│  ├─ src/main/java/com/stocksage/config/
│  ├─ src/main/java/com/stocksage/controller/
│  ├─ src/main/java/com/stocksage/memory/
│  ├─ src/main/java/com/stocksage/rag/
│  ├─ src/main/java/com/stocksage/repository/
│  ├─ src/main/java/com/stocksage/service/
│  ├─ src/main/java/com/stocksage/tool/
│  └─ docs/                    # 本地知识库资料，默认由 stocksage.rag.docs-path 指向
├─ stocksage-data-service/     # FastAPI 数据、搜索、EDGAR 和 PDF 解析服务
│  ├─ app/routers/
│  └─ app/services/
├─ stocksage-frontend/         # Vue 3 前端
│  ├─ src/api/
│  ├─ src/components/
│  ├─ src/lib/
│  └─ src/views/
├─ rag-eval/                   # RAG 检索和回答级评估
├─ scripts/                    # 本地 smoke、双实例和后续故障演练入口
├─ sql/                        # 初始化脚本和迁移
├─ AGENTS.md
├─ CLAUDE.md
├─ feature_list.json
├─ progress.md
├─ RAG_EVALUATION.md
└─ init.ps1
```

## 后端模块

- `controller/`: HTTP API，包括 chat、docs、eval、trace、memory、user。
- `service/`: ChatService、知识入库、EDGAR 入库、用户画像、标题生成等应用服务。
- `agent/`: Coordinator、分析师 Agent、多空研究员、Research Manager 和路由回归。
- `tool/`: 模型可调用工具，封装基本面、行情、新闻和兼容性工具。
- `client/`: `DataServiceClient`，负责调用 Python data-service。
- `rag/`: 文档加载、检索编排、查询改写、rerank、RAG 回归和本地 embedding。
- `memory/`: Redis 短期记忆和长期画像上下文。
- `trace/`: trace 记录和 Phoenix 上报。
- `repository/`: JPA repository，MySQL 持久化对话、消息、trace、向量索引元数据等。
- `model/`: entity、DTO、Agent 间结构化 schema。

硬性边界：

- 不使用 `RetrievalAugmentationAdvisor`；RAG 结果以参考性 `SystemMessage` 上下文注入。
- 不添加模型可调用的“写知识库”工具；知识更新由异步 ingestion 和 scheduled collection 处理。
- Spring AI Alibaba 和 Spring AI 原生接口签名不能混用，改 AI 集成前先查当前代码。
- 默认 embedding 走本地 Ollama `bge-m3`；批量向量化出现 NaN 时自动逐条重试，避免脏文本阻塞入库。
- IBKR 保持只读。

## 数据服务模块

FastAPI 入口是 `stocksage-data-service/main.py`，注册以下路由：

- `GET /health`: 服务健康和数据源状态。
- `/api/stock/*`: 股票解析、搜索、K 线、财务指标、财报、技术指标、新闻、板块和对比。
- `/api/search/web`: 通用网页搜索。
- `/api/search/news`: 新闻搜索。
- `/api/document/parse`: PDF 上传解析，最大 20 MB。
- `/api/edgar/filings`: SEC filing 索引。
- `/api/edgar/filing-content`: SEC filing 章节解析。
- `/api/edgar/xbrl`: SEC XBRL 结构化财务数据。

市场路由约定：

- A 股: K 线和技术指标优先 AKShare / 东方财富，AKShare 不可用或无可用数据时回退 BaoStock；财务指标、财报、板块概览仍以 BaoStock 兼容路径为主。
- 港股: AKShare。
- 美股: Python 数据服务不提供实时行情；行情走 IBKR 只读工具，财报和结构化财务走 SEC EDGAR。

A 股供应商降级：

- `GET /api/stock/kline?code=sh.600519&period=daily&days=30` 和 `GET /api/stock/technical?code=sh.600519&indicators=MA,MACD,RSI` 会先尝试 AKShare。
- 正常返回会带 `provider: "akshare"`；如果 AKShare 报错或返回空数据，会调用 BaoStock 兜底，并在响应中带 `provider`、`primaryProvider`、`fallbackProvider` 和 `primaryProviderError`，便于前端或 Agent 展示实际供应商和降级原因。
- 这不新增密钥、不接入付费 API，也不改变 Java 后端工具接口。

板块能力：

- `GET /api/stock/sector?sector=baijiu` 返回 A 股样本板块表现，当前支持别名包括 `baijiu`、`semiconductor`、`new_energy` 及对应中文别名。
- 响应保持旧字段 `sector` 和 `data`，并新增 `resolvedSector`、`summary`、`leaders`、`laggards`、`source`、`asOf` 和 `message`。
- `summary` 包含样本数、平均涨跌幅、成交额、成交量、领涨样本代码和领跌样本代码；`leaders` / `laggards` 使用样本股日 K 线涨跌幅排序。
- 未支持的板块不会伪造数据，会返回空 `data`、空领涨/领跌和 `message`，便于前端或 Agent 明确展示能力边界。

## 前端模块

前端有两个主要路由：

- `/`: 对话页面。
- `/workbench`: 研究工作台。

关键文件：

- `src/api/chat.js`: `fetch + ReadableStream` 手动解析 POST SSE，对话、会话、trace、用户画像 API。
- `src/api/workbench.js`: 工作台健康检查和 cockpit / report history API。
- `src/views/ChatView.vue`: 主对话页面、SSE 状态合并、图表和对话导出入口。
- `src/views/WorkbenchView.vue`: watchlist、页内研究执行、事件解读、只读持仓诊断、对比、AI 研究报告库、投资备忘录和 eval 快照。
- `src/components/ChatInput.vue`: 文本、上传图片、粘贴截图。
- `src/components/ChatMessage.vue`: Markdown、模型 badge、trace、chart、图片展示。
- `src/components/KLineChart.vue`: 依赖-free SVG K 线图，支持 MA overlay、成交量均线和区间摘要。
- `src/lib/workbench.js` / `src/lib/chart.js` / `src/lib/researchUi.js` / `src/lib/productUi.js`: 可测试的 prompt、报告、图表 provenance、主导航和研究 UI 元数据逻辑。

Workbench 入口：

- 研究：在工作台内启动单股研究 SSE 执行，并显示进度、模型、trace、结果和 cockpit/report 自动刷新。
- 比较：在工作台内启动多 ticker 对比研究。
- 事件：输入 ticker、来源和事件文本，在工作台内启动固定结构的事件影响分析，要求输出事件摘要、影响链路、受影响财务指标、证据引用、不确定性和后续跟踪项。
- 持仓：在工作台内启动 IBKR 只读持仓诊断，只允许使用认证状态、账户、账户摘要、持仓、行情和历史 K 线等只读工具；不生成或调用下单、撤单、改单 API。
- 报告库：展示后端持久化的 AI 研究报告版本；投资备忘录用于手动整理当前结论并下载 Markdown。聊天页的“对话导出”只导出当前对话记录。
- Eval：导入 `rag_eval_*.json` 或 `*_ragas.json`，展示 gate 状态、RAGAS 指标、失败 case、检索上下文和引用命中信息。

## API 速查

### Chat

```text
POST /api/chat/stream
Content-Type: application/json
Accept: text/event-stream
```

请求体：

```json
{
  "conversationId": 1,
  "origin": "chat",
  "title": "可选：新建会话标题",
  "message": "分析一下 NVDA 最新财报和主要风险",
  "images": [
    {
      "name": "screenshot.png",
      "mediaType": "image/png",
      "dataUrl": "data:image/png;base64,..."
    }
  ],
  "replaceLastTurn": false
}
```

响应是 SSE，常见 chunk 类型包括：

```text
meta, thought, action, observation, chart, model, answer, error, done
```

会话 API：

```text
GET    /api/chat/conversations
GET    /api/chat/conversations/{conversationId}/messages
DELETE /api/chat/conversations/{conversationId}
POST   /api/chat/regression/coordinator
```

### Knowledge / RAG

这些接口默认受 Admin token 保护。请求头：

```text
X-StockSage-Admin-Token: local-admin
```

接口：

```text
POST /api/docs/ingest
POST /api/docs/cleanup-expired
POST /api/docs/scheduled/collect
GET  /api/docs/search?q=AMZN%20risk&full=true
POST /api/docs/regression/run
POST /api/docs/edgar/ingest?ticker=AAPL&type=10-K&count=1
POST /api/eval/rag
GET  /api/memory/conversations/{conversationId}/context
GET  /api/trace/{traceId}
```

如果本地调试不想带 token，可设置：

```powershell
$env:STOCKSAGE_ADMIN_ENABLED="false"
```

### Data Service

后端通过 `stocksage.data-service.base-url` 调用 data-service。默认：

```text
http://localhost:8001
```

常用接口：

```text
GET  /api/stock/resolve?query=AAPL
GET  /api/stock/search?q=腾讯&max_results=5
GET  /api/stock/kline?code=sh.600519&period=daily&days=30
GET  /api/stock/kline?code=0700.HK&period=daily&days=30
GET  /api/stock/technical?code=sh.600519&indicators=MA,MACD,RSI
GET  /api/stock/financial-report?code=AAPL&period=annual&years=5
GET  /api/stock/news?code=NVDA&days=7
GET  /api/stock/sector?sector=baijiu
GET  /api/search/news?q=NVDA%20earnings&max_results=5&timelimit=w
GET  /api/edgar/filings?ticker=AAPL&type=10-K&count=3
POST /api/document/parse
```

`/api/stock/sector` 的兼容响应示例：

```json
{
  "sector": "baijiu",
  "resolvedSector": "Baijiu",
  "summary": {
    "sampleCount": 5,
    "averagePctChg": 1.23,
    "totalAmount": 1234567890.0,
    "totalVolume": 9876543,
    "leader": "sh.600519",
    "laggard": "sz.000596"
  },
  "leaders": [
    {
      "code": "sh.600519",
      "name": "Kweichow Moutai",
      "pctChg": 2.1,
      "close": 1500.0,
      "volume": 123456,
      "amount": 123456789.0,
      "tradeDate": "2026-05-30"
    }
  ],
  "laggards": [
    {
      "code": "sz.000596",
      "name": "Gujing Distillery",
      "pctChg": -0.8,
      "close": 180.0,
      "volume": 654321,
      "amount": 98765432.0,
      "tradeDate": "2026-05-30"
    }
  ],
  "data": [
    {
      "code": "sh.600519",
      "name": "Kweichow Moutai",
      "pctChg": 2.1,
      "close": 1500.0,
      "volume": 123456,
      "amount": 123456789.0,
      "tradeDate": "2026-05-30"
    }
  ],
  "source": "baostock",
  "asOf": "2026-05-30",
  "message": null
}
```

## RAG 管线

知识来源：

| 来源 | 说明 | 默认 TTL |
|---|---|---:|
| SEC EDGAR | 10-K/10-Q 自动获取、章节解析、Parent-Child 分块 | 永久 |
| 本地资料 | `stocksage-backend/docs` 下的 reports / glossary / articles | 永久 |
| 对话驱动搜索 | 用户问题触发 search 后异步入库 | 7 天 |
| 定时采集 | 预设关键词定时 web/news search | 7 天 |

入库流程：

```text
内容 -> SHA-256 -> doc_index 幂等检查 -> 切片 -> 语义去重 -> embedding -> Milvus -> MySQL FULLTEXT
```

SEC EDGAR 默认分块：

- Parent chunk: 3000 字符，300 overlap。
- Child chunk: 800 字符，120 overlap。
- 子块用于向量召回，命中后追溯 parent chunk 给模型提供完整上下文。

检索流程：

```text
用户问题 -> 查询改写 -> ticker metadata filter
        -> 向量检索 top-20 + MySQL FULLTEXT top-10
        -> RRF 融合去重
        -> DashScope gte-rerank
        -> parent 上下文扩展
        -> 编号引用注入最终回答上下文
```

默认 embedding provider 是本地 Ollama `bge-m3`，兜底向量维度为 `1024`。如改用 DashScope embedding 或修改维度，先确认 Milvus collection 维度一致；维度变化后需要重建 collection。

重建 Milvus collection：

```powershell
Invoke-RestMethod -Uri "http://localhost:19530/v2/vectordb/collections/drop" -Method Post -Body '{"collectionName":"stocksage_docs"}' -ContentType "application/json"
```

然后重启后端并重新执行 ingestion。

## RAG 评估

评估依赖独立虚拟环境 `.venv-rag-eval`，避免污染 data-service runtime。

首次安装：

```powershell
cd D:\programming\StockSage
& 'C:\Users\Orion\AppData\Local\Programs\Python\Python312\python.exe' -m venv .venv-rag-eval
.\.venv-rag-eval\Scripts\Activate.ps1
python -m pip install --upgrade pip setuptools wheel
pip install ragas arize-phoenix pandas requests python-dotenv openai
```

检索 smoke test：

```powershell
.\.venv-rag-eval\Scripts\Activate.ps1
python .\rag-eval\run_retrieval_eval.py
```

单问题 smoke test：

```powershell
python .\rag-eval\run_retrieval_eval.py --query "AMZN operating segments" --expected AMZN --expected operating --relevance operating
```

回答级 RAG eval：

```powershell
python .\rag-eval\run_rag_eval.py
```

包含中间检索候选：

```powershell
python .\rag-eval\run_rag_eval.py --include-intermediate
```

汇总已有结果并执行 gate：

```powershell
python .\rag-eval\eval_summary.py .\rag-eval\results\rag_eval_<timestamp>.json --fail-on-gate
```

Workbench 的 Eval 页可以直接导入：

- `rag_eval_*.json`: StockSage 检索/回答评估结果。
- `*_ragas.json`: RAGAS 指标结果。

导入后会展示 gate 状态、RAGAS 平均指标、最差或失败 case、问题/答案/期望答案、检索上下文和引用命中信息。底层仍复用 `rag-eval/eval_summary.py` 和 `run_rag_eval.py` / `run_ragas_eval.py`，不要求修改 golden set 格式。

评估输出默认写入：

```text
rag-eval/results/
```

更多指标、golden set 设计和后续改进项见 [RAG_EVALUATION.md](RAG_EVALUATION.md)。

## Phoenix 可观测性

Phoenix 只在需要观察 trace 或运行评估时开启。

启动：

```powershell
cd D:\programming\StockSage
.\.venv-rag-eval\Scripts\Activate.ps1
phoenix serve
```

打开：

```text
http://localhost:6006
```

后端上报配置：

```powershell
$env:STOCKSAGE_PHOENIX_ENABLED="true"
$env:STOCKSAGE_PHOENIX_ENDPOINT="http://localhost:6006/v1/traces"
$env:STOCKSAGE_PHOENIX_PROJECT_NAME="stocksage-rag"
```

评估脚本默认上报到：

```text
stocksage-rag-eval
```

## 常见问题

### `.\init.ps1 -Mode fast` 失败在 backend

如果输出：

```text
Required command 'mvn' was not found on PATH, MAVEN_HOME, M2_HOME, common Scoop/Chocolatey locations, or stocksage-backend\mvnw.cmd.
```

说明当前机器没有可用 Maven 命令，`MAVEN_HOME` / `M2_HOME` 未指向有效 Maven，常见 Scoop / Chocolatey shim 不存在，且当前 checkout 缺少 `stocksage-backend\mvnw.cmd`。本仓库当前版本已补充 wrapper；如果仍看到这条信息，先确认 `stocksage-backend\mvnw.cmd` 和 `.mvn\wrapper\maven-wrapper.properties` 存在。

### data-service 起来了但 BaoStock 不可用

先看：

```powershell
Invoke-RestMethod http://localhost:8001/health
```

FastAPI 服务可以健康运行，同时 BaoStock 后台登录处于 retrying。当前 A 股 K 线和技术指标会先走 AKShare / 东方财富，BaoStock 只作为兜底；财务指标、财报、板块和市场概览等 BaoStock 兼容路径仍可能返回结构化错误。服务本身不应崩溃，先用 `/health` 区分“服务不可用”和“某个供应商降级”。

### 后端启动时报 schema validate 错误

对已有数据库执行 `sql/migrations` 下的迁移脚本。新库直接执行 `sql/init.sql`。

### RAG 调试接口返回 403

Workbench 当前不再用 `/api/docs/search` 作为健康检查探针；研究页会通过 `/api/workbench/stocks/{ticker}/cockpit` 检查驾驶舱能力，并在真实研究执行时验证 RAG 引用链路。

如果你手动调用 `/api/docs/**` 或 `/api/eval/**` 调试接口，它们默认需要 `X-StockSage-Admin-Token`。本地调试可临时关闭：

```powershell
$env:STOCKSAGE_ADMIN_ENABLED="false"
```

或者在调用 admin API 时带上 `stocksage.admin.token` 配置的 token。

### 美股 K 线或实时行情从 data-service 返回 unsupported

这是预期边界。Python data-service 不提供美股实时行情；美股行情走 IBKR Client Portal Web API 只读工具，SEC 财报和 XBRL 走 EDGAR。

## 路线图

当前 feature 状态见 [feature_list.json](feature_list.json)，会话进展和验证证据见 [progress.md](progress.md)，RAG 质量计划见 [RAG_EVALUATION.md](RAG_EVALUATION.md)。
