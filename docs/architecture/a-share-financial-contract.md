# A 股财务摘要契约

`/api/stock/financial-report` 的 A 股分支由 [Pydantic 模型](../../stocksage-data-service/app/a_share_financials.py) 规范化，Java 使用 [AShareFinancialsResponse](../../stocksage-backend/src/main/java/com/stocksage/client/AShareFinancialsResponse.java) 校验。六类结果是 BaoStock 的财务摘要和指标，不是完整原始财务报表。

## 事实和状态

每期保留 `year/quarter` 查询身份。各摘要的 `data` 包含实际 `code/pubDate/statDate` 与对应数值；统计日期必须与该期一致，发布日期不能替代统计日期。顶层和报告的 `asOf` 只取可用摘要的真实统计日期；`fetchedAt` 是本次获取时间，缓存命中保留原值。没有可用摘要时不生成业务日期。

每张摘要区分 `SUCCESS/EMPTY/ERROR`。至少有一个非空有效数值才能成为成功摘要，证券代码和日期不构成数值证据；错误保留在所属摘要，不能用非空错误字典假装有数据。一期六张摘要均成功时报告为 `SUCCESS`；只有部分可用时为 `PARTIAL`；无可用数据时再区分无结果与失败。顶层按报告状态汇总，部分成功的实际数据仍可引用。

请求周期和年数显式保留。普通证据验收将部分摘要标记为 `REPORT_TABLES_INCOMPLETE`；完整期数按成功报告的不同实际日期计数，少于请求时标记 `REPORT_COUNT_INCOMPLETE`。可用的部分事实仍可解释，但不能宣称请求已完整满足。六张摘要有数值也不代表每项可选指标齐全，亦不证明最新财报已披露。

## 数值与口径

供应商的数值字符串在 Python 边界以 Decimal 规范化，空值保留为空，布尔值和非有限数值不能成为财务事实。`valueEncoding=DECIMAL_STRING` 明确这些数值字段在 JSON 中使用不含指数的十进制字符串，避免大金额经过二进制浮点数时丢失小数。Java 使用 BigDecimal，工具输出仍保持相同字符串契约；模型上下文只按完整摘要缩减，保留其日期、单位和错误状态，并标记缩减。

`valueScale=PROVIDER_RAW` 表示不隐式乘除或换算供应商数值。`netProfitUnit/MBRevenueUnit=YUAN` 和 `NRTurnDaysUnit/INVTurnDaysUnit=DAY` 对应官方明确的金额和天数单位。没有确认的 ISO 币种保持空值；`aggregationBasis=UNKNOWN` 不将季频接口结果一概解释为单季发生额。每股收益字段仍保留其 `epsTTM` 名称，不能按单季收益使用。

官方比率表述包含百分号，但示例同时出现小数值，因此此契约不自动转换为百分数展示。原始字段名与公式说明用于解释，不能依据量级推断尺度。六类字段来源如下：

- [盈利能力](https://www.baostock.com/mainContent?file=seasonProfit.md)
- [营运能力](https://www.baostock.com/mainContent?file=seasonOperation.md)
- [成长能力](https://www.baostock.com/mainContent?file=seasonGrowth.md)
- [偿债能力](https://www.baostock.com/mainContent?file=seasonBalance.md)
- [现金流量](https://www.baostock.com/mainContent?file=seasonCashFlow.md)
- [杜邦指标](https://www.baostock.com/mainContent?file=seasonDupont.md)

## 消费边界

客户端在缓存写入前和读取后校验本契约，部分成功可以缓存，但必须保留状态、缺口和原始抓取时间；失败不进入成功缓存。先部署 Python 提供方，再部署 Java 消费方，缺少版本或请求范围不一致是协议错误。HK 财报和 [SEC 财报](sec-financial-contract.md) 各自保留独立语义。实施范围与验证证据见 [progress.md](../../progress.md)。
