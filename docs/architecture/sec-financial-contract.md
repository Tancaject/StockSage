# SEC 年度财务数据契约

`/api/edgar/xbrl` 和 `/api/stock/financial-report` 的美股分支复用 [Pydantic 模型](../../stocksage-data-service/app/sec_financials.py)，独立 XBRL 端点通过 OpenAPI 发布结构。Java 客户端使用 [SecFinancialsResponse](../../stocksage-backend/src/main/java/com/stocksage/client/SecFinancialsResponse.java) 校验，模型工具仍输出 JSON。此契约针对现有 SEC 年度指标，不代表 A/H 股财报或季度报表已统一。

## 期间、版本与单位

`asOf` 为返回事实中最新的 `end`，精度为日期；它不表示每个指标都有该期数据。各事实的 `start/end/period_type` 决定该数值属于期间累计还是时点值。`filed` 和 `accn` 保留申报版本，`fetchedAt` 表示获取时间，三者不互相替代。证据账本将日期转换为 UTC 零点仅用于兼容已有 Instant 字段，不代表财报发布时间。

指标保留具体 `concept`、`unit` 和原始有限数值。`fiscal_year` 未知时为空，不能从申报标签 `filing_fiscal_year` 推断；`metric_count` 是指标种类数，不是报表或覆盖年度数。年度资格与修订筛选仍由 [EdgarService](../../stocksage-data-service/app/services/edgar_service.py) 负责。

`SUCCESS` 仅证明返回了符合本契约的年度事实，不证明最新申报已经收录、所有指标覆盖一致或请求的年度数已满足。`EMPTY` 表示没有合格事实；`UNSUPPORTED` 表示请求的周期不受支持；`ERROR` 表示上游调用或协议失败。可重试性根据明确异常类别记录，未知时保留空值，不解析错误文案猜测。

## 财报请求与覆盖

财报入口保留 `requestedPeriod/requestedYears` 和已解析路由信息。年度请求对每个指标选取最新的 N 个不同 `end`，同一结束日期的多条事实仍属于一个期间；该期间内的事实保留各自实际起止日期与申报信息。独立 XBRL 端点没有这两个请求约束，字段为空。季度请求直接返回不支持，不将年度数据伪装成季度结果；用户可改查年报或使用已有 10-Q 公告检索。

实际财务数据来源由 `provider=SEC_EDGAR` 和事实的 SEC URL 表达。保留的 `source` 是既有市场路由提示，美股可能为 `ibkr`；它不是本次财报提供方，也不能替代 `provider` 用于来源归因。

普通证据验收先核对响应中的请求周期、年数与本轮参数。每个返回指标的不同结束日期不足时标记 `REPORT_COUNT_INCOMPLETE`；各指标分别够数但共同期间不足时标记 `REPORT_PERIODS_UNALIGNED`。这些数据仍可解释和引用，整体回答保持降级并说明缺口。期间数量不证明年度连续性、最新申报时效或所有财务指标完整，不能据此作更强的覆盖承诺。

## 消费与演进

证据提取使用显式状态、标的和业务日期，不递归从其他日期字段寻找替代值。普通上下文压缩时按完整事实保留数值、期间、申报版本、来源及其指标单位和概念；缩减后的上下文标记 `contextReduced`，不能宣称完整展示所有指标和历史期间。

Java 使用[客户端定义的版本化缓存命名空间](../../stocksage-backend/src/main/java/com/stocksage/client/DataServiceClient.java)，缓存命中保留原始 `fetchedAt`，失败不作为成功缓存。混合市场财报入口的 SEC 响应在写缓存前和读取后均校验；[A 股摘要](a-share-financial-contract.md)与[港股财报](hk-financial-periods.md)分别使用各自的领域契约。部署时先升级 Python 提供方，再升级 Java 消费方；缺少版本或字段矛盾返回协议错误，不回退猜测旧结构。完整实施范围及验证状态见 [progress.md](../../progress.md)。
