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

## 评测原则

评测要回答三个问题：改动有没有造成退步、两个方案哪个更好、系统现在的实际水平。投入按能否让这三个结论更可信来排序。

优先投入：

- **测最终结果**：用户看到的普通回答和 DEEP 研究结果必须有质量评测。路由、检索、执行成功等上游指标不能代替回答质量。
- **先知道噪声**：每个在线评测层都要记录同一配置重复运行的波动。比较结论要给出差值和噪声的对比；差值落在噪声范围内记为"无差异"，不报告为提升。
- **样本量匹配结论**：按要检测的提升幅度确定题量。题少时只报逐题结果，不报百分比提升，也不说"显著"。
- **评审先校准再使用**：AI 评审必须在系统的真实输出上、对照人工确认过的标签做校准，报告一致率、漏判错答率和技术无效率。未校准评审的结果只作诊断，不作结论。评审模型和被测的生成模型不用同一家。
- **区分开发集和留出集**：调参、修失败题用过的题集是开发集。对外报告的水平和"是否更好"的结论，用没参与调参的留出集。
- **成本分层**：日常回归只放便宜、稳定的层；贵的评测在相关改动后或里程碑时跑。省成本靠分段评测（只跑证据阶段、冻结证据回放）和减少调用次数，不靠降低生产模型档位。真实运行前先说明题数和预估费用。
- **按改动选评测**：改了什么就跑受影响的层。换模型、改思考预算或改提示词时，所有回答质量层都要跑。

不要重点投入：

- 不再新增治理层：签名、发布者密钥、多级资格认证、影子或观测工具、在冻结清单之上再加冻结。可追溯性只保留数据集哈希、运行配置身份（git SHA、模型名、提示词哈希）、原始输出和逐题结果。
- 不写成段的免责声明。每份结果用一小段列出具体局限（题数、评审是否校准、是否盲评、是否重复运行），不要逐句写"不能证明 X"。
- 不为单次实验新建运行器和目录体系，优先扩展现有统一入口（`run_daily_eval.py`、`run_evaluation_agent.py`、`run_deep_m0.py`）。
- 不把执行成功、命中经验、提示词变短、哈希一致、评审格式合法当作质量证据。
- 不为得到结论而放宽判定、删难题或改标注。标注确有错误时单独修订，并记录原因。

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

改动后运行评测成绩单：`.\init.ps1 -Mode eval` 跑 daily 档（Harness 离线、Planner LIVE、RAG 检索、DEEP 证据冒烟，缺前提的层记为 SKIPPED）；`python evals/run_daily_eval.py --since <git-sha>` 按改动选层，回答质量层和评审在 change 档。RAG 检索评测可通过 `.\init.ps1 -Mode rag` 运行；其他评测入口、运行条件与复现限制统一见 [evals/README.md](evals/README.md)。自动化测试仍保持移除，未经新授权不要恢复测试驱动或测试依赖。

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
