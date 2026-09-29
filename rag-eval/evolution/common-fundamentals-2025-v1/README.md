# 常见基本面问题评测集

18 道中文问题，来自 6 家美股公司的 FY2025 官方财务报表。每家公司 3 题：增长及营业利润率、经营现金流及现金资本开支、资产负债表及流动性。问题要求简短解释和必要计算，均有相应报表证据可用，不加入攻击、极端缺数或罕见会计案例。

| 划分 | 公司 | 题数 | 用途 |
|---|---|---:|---|
| DEVELOPMENT | Microsoft、Walmart | 6 | 观察常见错误，后续改方法 |
| VALIDATION | Alphabet、Costco | 6 | 首轮基线；后续独立比较候选 |
| HOLDOUT | Apple、Amazon | 6 | 预留；本次执行文件不包含它们 |

同一家公司、同一报告家族及其三个问题整体进入一个划分。18 题只有 6 个发行人组，不能当作 18 个独立公司样本。公司覆盖软件/云、广告、零售和消费科技，但这套小样本不代表金融、能源、A 股/港股或全部普通路线；不适合据此声称统计显著改善。

`cases.jsonl` 是题目及证据权威文件；`draft-answer-key.jsonl` 是运行前固定的标准答案草稿，含必答事实、Decimal 计算结果和解释要点。`source-index.json` 记录官方 URL、原始文件哈希、文本提取方式、原文字符范围及摘录哈希；`sources/` 保留对应完整报表摘录。原始 PDF/Word 文件存于本地 `stocksage-backend/target/evolution-public-2025-sources/`，不需要业务数据库或实时行情。

财年按公司定义，题目不把 FY2025 等同自然年。金额原值均为百万美元，允许答案明确换算成其他美元单位。营业利润率与同比允许 0.1 个百分点的明确四舍五入差异，流动比率允许 0.01；标准答案仍保存原值。现金流题统一使用经营现金流减购建固定资产现金支出的**毛额**，不是净利润，也不保证与公司采用资产出售回款等调整的非 GAAP 自由现金流完全相同。债务题明确使用非流动长期债务，不将其称为总负债。

来源包括正式年报和全年业绩发布所附报表；后者保留原文 `Unaudited` 标记，不称为审计报告。来源只有公开日期而没有可靠时刻时，元数据使用次日 UTC 零点作本次日粒度可见性约定，并在索引注明，不假称精确发布时刻。本次研究时点在所有来源发布后，不作历史预测回测。

运行时只送题目、可见报表及必要元数据，标准答案不进入模型。首轮每题运行一次，12 个案例正常共 24 次模型调用，分别保留基本面分析稿和最终回答。使用现有 Java 两阶段组件和本地模型配置；不启动检索、登录或正式会话存储。实际模型、参数、耗时及用量以运行产物为准，不在此处重复维护。

从仓库根目录验证数据：

```powershell
python rag-eval/evolution_dataset.py rag-eval/evolution/common-fundamentals-2025-v1/cases.jsonl
```

从 `stocksage-backend` 显式运行真实基线（输出目录须全新，父目录已存在）：

```powershell
.\mvnw.cmd -q '-Dtest=EvolutionLiveBaselineTest' '-Devolution.live.execution=D:\programming\StockSage\rag-eval\evolution\common-fundamentals-2025-v1\baseline-execution.json' '-Devolution.live.output=D:\programming\StockSage\stocksage-backend\target\evolution-live-common-baseline-v1' test
```

默认测试不提供 `evolution.live.execution` 时跳过，不会发模型请求。显式运行读取项目配置及被忽略的 `application-local.properties`；不得把密钥写入数据集或命令。失败停止且保留已完成输出，不能重用同一登记文件冒充首次运行或自动重试未知调用；另一次试验须生成新的 run ID 并保留两次记录。

本集由 AI 查阅官方来源并核对算术，标准答案明确 `AI_SOURCE_CHECKED_DRAFT / humanReviewStatus: UNREVIEWED`。可据此做透明的来源核对与初步答案评审，不冒充已有人类审阅或已满足独立发布门禁。严格 gold 接口需要真正完成相应评审后另行导出。本次运行结果与下一步统一见根目录 [progress.md](../../../progress.md)。
