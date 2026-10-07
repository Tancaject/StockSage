# StockSage

本文件是项目约束的唯一权威来源。

三服务架构：

- stocksage-backend (Java, Spring AI Alibaba) → :8080
- stocksage-data-service (Python, FastAPI) → :8001，后端通过 REST 调用
- stocksage-frontend (Vue 3, Vite) → :5173
- evals：财报入库、离线评测与实验工具、评测数据和历史报告；入口及复现限制见 [evals/README.md](evals/README.md)

README 和后端配置使用 data-service 端口 `8001`。除非用户明确改变本地 setup，否则旧的 `8000` 引用视为过时。

## 开始工作前

- 确认当前工作目录及任务涉及的模块。
- 修改服务边界或整体架构时，阅读 `README.md` 的相关部分。
- 修改 RAG 检索或评测行为时，阅读 `evals/README.md` 的相关部分。
- 任务依赖已有进度、功能状态或已知阻塞时，读取 `progress.md` 和 `feature_list.json` 的相关条目。
- 只读分析和纯文档修改不默认运行构建或评测。

## 不要建议的做法

- 不要建议使用 RetrievalAugmentationAdvisor — 已验证不适用，RAG 结果改为参考性 SystemMessage 注入
- 不要给模型添加"写入知识库"的工具 — 已移除，知识更新由对话驱动异步入库 + 定时采集负责
- 不要假设 Spring AI 原生 API 可以直接用 — 本项目使用 Spring AI Alibaba，部分 API 签名不同，改之前先查
- text-embedding-v4 每批上限 10 条，不要调大 EMBEDDING_BATCH_SIZE
- IBKR 保持只读边界，不添加下单、撤单、改单能力；除非用户明确要求重新定义交易范围，否则不要扩展交易行为。
- 不要把密钥提交到仓库。真实密钥只能放在环境变量或 `application-local.properties` 等本地忽略文件中。

## 工作规则

- 一次只做一个功能或 bug。
- 清理测试前按 [evals/README.md](evals/README.md) 区分评测体系与自动化测试，保留评测能力及其依赖。`evals/` 下的评测脚本（含 `agent_eval_summary.py`）不是测试，不要删除；`evals/archive/` 是已归档的历史工具。
- 优先使用现有项目模式，不为局部问题引入新抽象。
- 前端、后端、data-service 和 eval 的改动要保持在各自模块边界内。
- RAG、agent 或 prompt 行为变化，需要更新或运行相关 regression/eval 路径。

## 验证原则

- 根据实际改动运行最小相关验证；可使用 `init.ps1` 的 `backend`、`frontend` 或 `python` 模式。
- 跨模块改动需要整体编译检查，或用户明确要求时，运行 `init.ps1 -Mode fast`。
- 仅在需要区分既有问题与本次改动时，运行修改前基线。
- 验证通过后，只有新增改动、失败或未解决疑点才需要重跑。
- 验证失败时，先定位原因；与本次任务无关的失败应说明，不自动扩大修复范围。

## 验证命令

整体编译检查：

```powershell
.\init.ps1 -Mode fast
```

当前自动化测试文件已按用户要求移除；以下仅检查源码编译与构建，不代表测试通过。历史评测复现的限制见 [evals/README.md](evals/README.md)。单项检查：

```powershell
cd stocksage-backend
.\mvnw.cmd compile
```

```powershell
cd stocksage-frontend
npm run build
```

```powershell
python -m compileall stocksage-data-service\main.py stocksage-data-service\app
```

改动路由、RAG 或 DEEP 完成策略后运行日常回归 `.\init.ps1 -Mode eval`（Harness 离线 + Planner LIVE + RAG 检索，缺前提的步骤记为 SKIPPED）。RAG 检索评测可通过 `.\init.ps1 -Mode rag` 运行；其他评测入口、运行条件与复现限制统一见 [evals/README.md](evals/README.md)。自动化测试仍保持移除，未经新授权不要恢复测试驱动或测试依赖。

## 完成标准

- 目标行为已实现。
- 已运行最小相关验证命令，或 blocker 已记录。
- RAG、agent 或 prompt 行为变化需要有 eval 或 manual trace 证据。
- 密钥和本地文件不能进入提交产物。

## 进度与交接

`feature_list.json` 用于查看功能状态；`progress.md` 用于查看人工可读的会话进度、验证证据和接手说明。

- 实际改动需要跨会话交接，或用户已确认新的需求、范围和待办时，更新 `progress.md`。
- 记录完成内容、相关验证证据、阻塞及必要下一步；详细事实通过链接引用已有报告。
- 仅在功能状态变化时更新 `feature_list.json`。
- 只读问答、审计建议和未采纳的想法不默认写入项目文件。
- 用户明确要求不修改文件时，在回复中提供交接信息。
