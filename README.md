# StockSage

StockSage 是一个本地可运行的 AI 股票研究工作台，整合多 Agent 分析、SEC 财报 RAG、市场数据工具和可追踪的后台研究任务。

> 仅用于学习、研究和工程演示，不构成投资建议；IBKR 集成始终保持只读。

## 核心能力

- **上下文意图识别与多 Agent 研究**：结合最近 3 轮对话、LLM、Embedding 与高精度规则，直接选择受控的五类研究路由；DEEP 模式要求 Bull/Bear 提交绑定 Evidence ID 的结构化论点，由 Research Manager 匿名逐论点评分、Java 策略锁定评级，再由 Manager 解释裁决并接受运行时 Harness 验收。
- **可溯源 RAG**：SEC EDGAR 财报经过 Parent-Child 分块、Milvus 向量与 Lucene 标准 BM25 混合检索、RRF 融合和 rerank 后，为回答提供编号引用。
- **后台研究任务**：Redis Stream、MySQL checkpoint 与 SSE 回放支持后台执行、断线恢复和进度追踪；证据不足时返回 `NOT_RATED`。
- **投研工作台**：提供自选股、K 线、事件与对比研究、报告版本/审核，以及 IBKR 只读持仓诊断。
- **质量评估**：记录模型、工具和研究链路，并提供检索评估、RAGAS、Agent golden set 与可选 Phoenix trace。

## 架构

```mermaid
flowchart LR
    UI["Vue Chat / Workbench"] --> API["Spring Boot Backend"]
    API --> INTENT["Intent Recognition<br/>LLM + Embedding + Pattern"]
    HISTORY["Recent 3 Turns"] --> INTENT
    INTENT -->|validated targetRoute| CO["Coordinator"]
    INTENT -->|resolvedQuery| RAG["Hybrid RAG"]
    CO --> PLAN["RoutePlanCatalog<br/>server-owned actions"]
    PLAN --> AG["Specialist Agents"]
    PLAN --> DEEP["DEEP Research"]
    AG --> DATA["FastAPI Data Service"]
    RAG --> STORE["Milvus + Lucene BM25<br/>(MySQL source)"]
    DEEP --> QUEUE["Redis Stream"]
    QUEUE --> WORKER["Research Worker"]
    WORKER --> SNAP["Evidence Snapshots<br/>stable Evidence IDs"]
    SNAP --> DEBATE["Structured Bull / Bear<br/>THESIS + REBUTTAL"]
    DEBATE --> SCORE["Manager Blind Scoring<br/>per thesis"]
    SCORE --> DECIDE["Java DecisionPolicy<br/>locked verdict"]
    DECIDE --> REPORT["Manager Narrative + Harness"]
    REPORT --> DB["MySQL Checkpoints / Reports"]
```

| 目录 | 技术与职责 |
|---|---|
| `stocksage-backend` | Java 17、Spring Boot、Spring AI / Spring AI Alibaba；对话、Agent、RAG、任务和报告 |
| `stocksage-data-service` | Python、FastAPI；行情、指标、新闻、EDGAR 与 XBRL 数据 |
| `stocksage-frontend` | Vue 3、Vite、Element Plus；Chat 与 Workbench |
| `rag-eval` | 检索、回答和 Agent/Harness 质量评估 |

运行依赖 MySQL、Redis 和 Milvus；本地 embedding 默认使用 Ollama `bge-m3`。

## 快速启动

准备 JDK 17+、Python 3.11+、Node.js 20+、Docker Desktop、Milvus standalone Compose 和 DashScope API Key。

### 1. 配置

```powershell
git clone https://github.com/Tancaject/StockSage.git
cd StockSage

Copy-Item .env.example .env
Copy-Item stocksage-backend\src\main\resources\application-local.properties.example `
  stocksage-backend\src\main\resources\application-local.properties
```

在 `application-local.properties` 中填写 DashScope API Key 和 MySQL 密码。真实密钥只放在环境变量或本地配置中；使用管理、入库或评估接口时，还需设置 `STOCKSAGE_ADMIN_TOKEN`。

### 2. 启动基础设施

```powershell
.\infra.ps1 up -MilvusComposePath "D:\path\to\milvus\docker-compose.yml"
```

### 3. 启动三个服务

分别在三个 PowerShell 窗口运行：

```powershell
# Data service (:8001)
cd stocksage-data-service
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
uvicorn main:app --port 8001 --reload
```

```powershell
# Backend (:8080)
cd stocksage-backend
.\mvnw.cmd spring-boot:run
```

```powershell
# Frontend (:5173)
cd stocksage-frontend
npm install
npm run dev
```

打开 [Chat](http://localhost:5173) 或 [Workbench](http://localhost:5173/workbench)。演示账号：`demo@stocksage.local` / `demo1234`。Data service OpenAPI 位于 [http://localhost:8001/docs](http://localhost:8001/docs)。
