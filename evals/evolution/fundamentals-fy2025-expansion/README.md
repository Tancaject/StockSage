# FY2025 年报基本面扩充集

36 道中文问题，来自 12 家此前各集未用过的美股公司的 FY2025 10-K。每家公司 3 题：增长与盈利能力、利润现金转化与资本开支、期末流动性与债务。目的是补足 VALIDATION / HOLDOUT 的发行人组数和行业覆盖，供自进化候选的验证和一次性留出评测使用。

| 划分 | 公司 | 题数 |
|---|---|---:|
| VALIDATION | CVX、CAT、LLY、MCD、NFLX、INTC | 18 |
| HOLDOUT | ABBV、HON、VZ、UBER、BA、UNH | 18 |

行业覆盖能源、工业机械、制药、餐饮、流媒体、半导体、电信、出行平台、航空航天、医疗保险。题目刻意包含真实报表中的常见难点，并写入 `requiredPoints`：
- 亏损年份不算亏损同比（INTC、BA）；
- 资产处置收益推高营业利润（BA）；
- 资本开支分两行列示（CAT）；
- 只用持续经营口径（HON）；
- 千美元或美元原始单位（NFLX、HON）；
- 52/53 周财年（INTC）；
- 流动比率低于 1 或股东权益为负（MCD、ABBV、VZ、UNH）；
- 债务按分部列示（CAT）。

金融机构未纳入：银行报表结构不同，没有流动比率口径。同一家公司的三题共享报告族，属于同一个分组，不能当作 3 个独立样本。

## 来源与标准答案

- `fetch_sources.py` 从 SEC EDGAR 下载每份 10-K 的 XBRL 报表渲染页（`R*.htm`），按行抽取为 `label | 值 | 值` 文本，原文件和抽取文本都保存在 `sources/`。已有文件不会覆盖；`sources/index.json` 记录 accession、URL、下载哈希和取得时间。
- `prepare_dataset.py` 负责生成 gold。题目、取值位置、公式和要点都写在该脚本里。每个数值按“行名 + 第几次出现 + 第几列”从抽取文本读取，不手工录入，再精确换算为百万美元。
- 公式复用 [batch-learning 冻结器](../batch-learning-20261002/prepare_dataset.py) 的 `gold_for()`：四位小数、HALF_EVEN、绝对容差 0.01。
- 第二条取证路径是 SEC companyfacts，按同一 accession、概念和期间比对，全部数值一致。CVX 现金（公司自定义概念）和 CAT 的分部债务（companyfacts 不含维度值）共 5 个数无法比对，已记录在 `source-manifest.json` 的 `xbrlCrossCheck`。
- 申报日没有可靠时刻，`availableAt` 记为申报日次日 UTC 零点。

标准答案状态为 `AI_SOURCE_CHECKED / humanReviewStatus: UNREVIEWED`，未经人工审阅，不能冒充正式门禁要求的人工 gold。简化自由现金流统一为经营现金流减资本开支毛额，不等于公司自己的非 GAAP 口径。净债务只减现金及现金等价物，不减短期投资。

## 使用

从 `evals` 目录校验：

```powershell
python evolution_dataset.py evolution/fundamentals-fy2025-expansion/cases.jsonl
```

要重建，请换一个新的输出目录，冻结脚本会拒绝覆盖：

```powershell
python evolution/fundamentals-fy2025-expansion/prepare_dataset.py --output-dir <新目录>
```

执行登记和真实回放的方法与 [common-fundamentals-2025-v1](../common-fundamentals-2025-v1/README.md) 相同。HOLDOUT 题只能在选定候选后消费一次，不得用于调方法或调评测器。
