# StockSage

三服务架构：
- stocksage-backend (Java, Spring AI Alibaba) → :8080
- stocksage-data-service (Python, FastAPI) → :8001，后端通过 REST 调用
- stocksage-frontend (Vue 3, Vite) → :5173
- rag-eval：RAG 检索与回答质量评估脚本

README 和后端配置使用 data-service 端口 `8001`。除非用户明确改变本地 setup，否则旧的 `8000` 引用视为过时。

## 开始工作前
- 确认当前目录是仓库根目录：`D:\programming\StockSage`。
- 先读 `CLAUDE.md`。本文件是项目约束的唯一权威来源；`AGENTS.md` 只指向本文件。
- 架构或 RAG 相关工作，需要同时阅读 `README.md` 和 `RAG_EVALUATION.md` 的相关部分。
- 检查 `feature_list.json` 和 `progress.md` 了解当前功能状态、最近进展、验证证据和已知阻塞。
- 本地工具链可用时，先运行 `.\init.ps1 -Mode fast` 建立基线。
- 如果基线验证失败，在扩大范围前把失败原因记录到 `progress.md`。

## 项目进度
- 如果需要了解当前项目进度、最近完成的工作、验证结果或已知阻塞，先阅读仓库根目录的 `progress.md`。
- `feature_list.json` 用于查看功能状态；`progress.md` 用于查看人工可读的会话进度、验证证据和接手说明。
- 如果讨论过程中新增了项目需求、功能想法、范围决定或后续待办，结束前要同步写入 `progress.md`。

## 不要建议的做法
- 不要建议使用 RetrievalAugmentationAdvisor — 已验证不适用，RAG 结果改为参考性 SystemMessage 注入
- 不要给模型添加"写入知识库"的工具 — 已移除，知识更新由对话驱动异步入库 + 定时采集负责
- 不要假设 Spring AI 原生 API 可以直接用 — 本项目使用 Spring AI Alibaba，部分 API 签名不同，改之前先查
- text-embedding-v4 每批上限 10 条，不要调大 EMBEDDING_BATCH_SIZE
- IBKR 保持只读边界，不添加下单、撤单、改单能力；除非用户明确要求重新定义交易范围，否则不要扩展交易行为。
- 不要把密钥提交到仓库。真实密钥只能放在环境变量或 `application-local.properties` 等本地忽略文件中。

## 工作规则
- 一次只做一个功能或 bug。
- 优先使用现有项目模式，不为局部问题引入新抽象。
- 前端、后端、data-service 和 eval 的改动要保持在各自模块边界内。
- RAG、agent 或 prompt 行为变化，需要更新或运行相关 regression/eval 路径。
- 讨论过程中新增的项目需求、功能想法、范围决定或后续待办，结束前要写入 `progress.md`。
- 跨会话或较大工作结束前，更新 `progress.md` 和 `feature_list.json`。

## 验证命令
优先使用 harness：

```powershell
.\init.ps1 -Mode fast
```

单项检查：

```powershell
cd stocksage-backend
.\mvnw.cmd test
```

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

RAG evaluation 需要 backend、data-service、MySQL、Redis、Milvus 和 Ollama 运行：

```powershell
.\.venv-rag-eval\Scripts\Activate.ps1
python .\rag-eval\run_retrieval_eval.py
python .\rag-eval\run_rag_eval.py
```

## 完成标准
- 目标行为已实现。
- 已运行最小相关验证命令，或 blocker 已记录。
- RAG、agent 或 prompt 行为变化需要有 eval 或 manual trace 证据。
- 密钥和本地文件不能进入提交产物。
- 跨会话工作完成时，`progress.md` 和 `feature_list.json` 已反映最新状态。

## 结束会话前
- 更新 `progress.md`：完成内容、验证证据、blocker 和新产生的需求/待办。
- 如果功能状态变化，更新 `feature_list.json`。
- 留下下一次接手需要知道的明确下一步。

## 项目定位
求职导向的简历项目，当前以美股投研演示为主，同时已有 A 股/港股数据路由和 A 股板块能力。
当前已引入轻量登录、Redis Session 与用户数据隔离，用于演示多账号画像、会话和报告隔离；不做真实交易，也不把它扩展成生产级多用户并发平台。
