# SEC 财务数据契约

`/api/edgar/xbrl` 和 `/api/stock/financial-report` 的美股分支复用 [Pydantic 模型](../../stocksage-data-service/app/sec_financials.py)，独立 XBRL 端点通过 OpenAPI 发布结构。Java 客户端使用 [SecFinancialsResponse](../../stocksage-backend/src/main/java/com/stocksage/client/SecFinancialsResponse.java) 校验，模型工具仍输出 JSON。此契约覆盖 SEC 年度（10-K）和单季（10-Q）指标，不代表 A/H 股财报已统一。

## 期间、版本与单位

`asOf` 为返回事实中最新的 `end`，精度为日期；它不表示每个指标都有该期数据。各事实的 `start/end/period_type` 决定该数值属于期间累计还是时点值。`filed` 和 `accn` 保留申报版本，`fetchedAt` 表示获取时间，三者不互相替代。证据账本将日期转换为 UTC 零点仅用于兼容已有 Instant 字段，不代表财报发布时间。

指标为营收、净利润（归母，`NetIncomeLoss`）、合并净利润（含少数股东，`ProfitLoss`，即现金流量表起点）、营业利润、稀释 EPS、经营现金流、资本开支、总资产、总负债、股东权益、长期债务、现金及等价物和稀释加权股数；合并与持续经营口径保留各自原始行，不因最新一期与其他口径相等而删除。银行（SEC submissions 的 SIC 6020–6039）不返回经营现金流和资本开支，改为净利息收入和信贷损失拨备。某指标最新期末比公司最新期末早 400 天以上时视为公司已改用其他概念，不返回。资本开支依次取 `PaymentsToAcquirePropertyPlantAndEquipment`、`PaymentsToAcquireProductiveAssets`、`PaymentsToAcquireOtherPropertyPlantAndEquipment` 中最新期末的概念。单位为 `USD`、`USD/shares` 或 `shares`。指标保留具体 `concept`、`unit` 和有限数值。`fiscal_year` 未知时为空，不能从申报标签 `filing_fiscal_year` 推断；`metric_count` 是指标种类数，不是报表或覆盖年度数。年度资格与修订筛选仍由 [EdgarService](../../stocksage-data-service/app/services/edgar_service.py) 负责。

`SUCCESS` 仅证明返回了符合本契约的财务事实，不证明最新申报已经收录、所有指标覆盖一致或请求的年度数已满足。`EMPTY` 表示没有合格事实；`UNSUPPORTED` 表示请求的周期不受支持；`ERROR` 表示上游调用或协议失败。可重试性根据明确异常类别记录，未知时保留空值，不解析错误文案猜测。

## 财报请求与覆盖

财报入口保留 `requestedPeriod/requestedYears` 和已解析路由信息。年度请求用核心报表选定最新 N 个不同 `end`，所有指标共用这组期末；可选指标缺期时保留缺失，不拿更早年份凑数。同一结束日期的多条事实仍属于一个期间，保留各自实际起止日期与申报信息。独立 XBRL 端点的原默认年度调用不带这两个请求约束；显式 `period/years` 查询保留实际生效的约束。季度请求返回 `period=quarterly`，单季区间为 80～100 天，每个指标最多保留 `requestedYears×4` 个期末。10-Q 不含第四财季，也不做年报减前三季的推算。年度事实与季度事实不能混在同一响应中。

季度优先使用披露的单季值；经营现金流、持续经营现金流、资本开支、出租设备支出以及合并和持续经营净利润可用半年累计减第一季、九个月累计减半年得到第二、三季。差分必须是同一 `concept/unit/start`，前期 `filed` 不晚于本期，且相邻累计跨度和所得单季跨度匹配；按实际财年起点对齐，不用申报标签推断自然年。缺少前期、期间或最新版本冲突时保留缺失；检测到累计值修订而没有同申报配对时也不派生，不用旧值兜底。

派生事实的 `start/end/value` 表示所得单季，附 `derivation.method=YTD_DIFFERENCE` 和 `current/previous` 两条原始累计来源，每条保留 `concept/unit/value/start/end/filed/form/accn/source_url`。两端契约校验来源、期间和精确差值；原始单季事实的 `derivation` 为空。展示在金额旁标明累计差额、两个原始金额与申报号，完整日期和来源 URL 保留在结构化事实中。

实际财务数据来源由 `provider=SEC_EDGAR` 和事实的 SEC URL 表达。保留的 `source` 是既有市场路由提示，美股可能为 `ibkr`；它不是本次财报提供方，也不能替代 `provider` 用于来源归因。

普通证据验收先核对响应中的请求周期、年数与本轮参数，再检查核心营收、净利润、总资产、经营现金流的期数和共同期间；经营现金流保留银行和单季缺失例外。核心项缺失、期数不足或未对齐仍记录缺口，可选分项缺期不再导致整体降级。具体检查见 [`OrdinaryEvidence`](../../stocksage-backend/src/main/java/com/stocksage/agent/ordinary/OrdinaryEvidence.java)。期间数量不证明年度连续性、最新申报时效或所有财务指标完整。

## 历史申报信息截止日与附加科目

独立 XBRL 端点支持 `asOf=YYYY-MM-DD`，响应以 `requestedAsOf` 保存该信息截止日；它与财务期间的 `asOf=end` 分开。在选择最新版本、最新 concept、年度期末及派生 Q2/Q3 之前，先移除 `filed` 晚于截止日的原始事实。两端契约再次检查截止日，不允许混入今天的营收拆分或截止日之后的报表附件。历史读取不使用今天的银行 SIC 分类删科目；原当前查询仍保留银行科目处理。

历史查询使用今天仍可取得的 companyfacts 历史记录，不保证完整重建当年数据档案。没有截止日前合格事实时返回 EMPTY，不回退当前数值。缓存键同时包括公司、年度/季度、窗口、截止日和附加科目开关，抓取时间仍如实记录本次读取时间。

`financialExtras=true` 为专门的财务工具附加 `Inventory`、`CostOfRevenue` 和 `CommonSharesOutstanding`，原默认科目不变。`CommonSharesOutstanding` 是已申报期末的普通股数，不能称为今天的实时流通股数，也不能与期间加权的 `DilutedShares` 混用。派生计算与工具范围见[财务补充计算](financial-calculations.md)。

## 消费与演进

[EvidencePresenter](../../stocksage-backend/src/main/java/com/stocksage/evidence/adapter/EvidencePresenter.java) 在同起止日、同 USD 单位的事实之间计算现金转化比率、资本开支占比与简化自由现金流，表中附公式与所用口径。默认净利润逐期优先合并口径，缺失时明确退回归母；持续经营使用独立成对科目。净利润不大于零或绝对值不足经营现金流绝对值 5% 时改报差额，经营现金流不大于零时不报资本开支占比。只有资本开支概念为 `PaymentsToAcquireOtherPropertyPlantAndEquipment` 时加上同期间出租设备支出；缺该分项则标注可能低估，总 PP&E / productive assets 不重复相加。估值中的简化自由现金流收益率复用这一合计口径。

证据提取使用显式状态、标的和业务日期，不递归从其他日期字段寻找替代值。普通上下文压缩时按完整事实保留数值、期间、申报版本、来源及其指标单位和概念；缩减后的上下文标记 `contextReduced`，不能宣称完整展示所有指标和历史期间。

Java 使用[客户端定义的版本化缓存命名空间](../../stocksage-backend/src/main/java/com/stocksage/client/DataServiceClient.java)，缓存命中保留原始 `fetchedAt`，失败不作为成功缓存。混合市场财报入口的 SEC 响应在写缓存前和读取后均校验；[A 股摘要](a-share-financial-contract.md)与[港股财报](hk-financial-periods.md)分别使用各自的领域契约。部署时先升级 Python 提供方，再升级 Java 消费方；缺少版本或字段矛盾返回协议错误，不回退猜测旧结构。完整实施范围及验证状态见 [progress.md](../../progress.md)。
