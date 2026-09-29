# 港股财报期间选择

`/api/stock/financial-report` 的港股分支由 [HkFinancialsResponse](../../stocksage-data-service/app/hk_financials.py) 规范化，Java 在 [同名 record](../../stocksage-backend/src/main/java/com/stocksage/client/HkFinancialsResponse.java) 校验。证券代码、实际期间、财务项目和指标各有明确字段，三张报表与指标分别保留状态和错误。

[AKShare 适配器](../../stocksage-data-service/app/services/akshare_service.py) 的三大财报返回长表：同一 `REPORT_DATE` 下有多个财务项目。请求的期间数按不同的实际报告日期计算，选中一期后保留该期全部返回项目；项目行数不代表报告期数，也不证明供应商返回了完整报表。

年度请求选择最新 `years` 个报告日期。现有 `quarterly` 请求映射为供应商的“报告期”，最多选择 `years * 4` 个不同报告日期；这只是条数上限，不保证覆盖这些自然年，也不把累计或中期数值转换成单季发生额。财报和指标分别选择自己的实际日期，不能据此断言各表期间已经对齐。

非空表缺少 `REPORT_DATE`，或任一行日期无法确定时，该表通过原有 `errors` 返回失败；其他表仍独立返回。不能删除日期不明的行后宣称所选期间完整。空表仍为空结果。保留原始字段，不根据交易市场推断报告币种。

财报路径保留供应商已返回的浮点精度，不再使用行情通用转换的六位小数舍入；这不能恢复供应商在 JSON 解析或 DataFrame 构造前已经丢失的精度，也不等于十进制金额的端到端精确传输。

## 传输与证据资格

`valueEncoding=DECIMAL_STRING` 表示数据服务边界之后的数值使用十进制字符串，Java 以 BigDecimal 精确保留这些值；`numericPrecision=PROVIDER_VALUE` 明确精度起点是供应商已经返回的值。零、空值与失败不能互相替代。`valueScale=PROVIDER_RAW` 不做隐式倍率转换，报表项目的 `amountUnit=UNKNOWN`、顶层 `currency=null`、`aggregationBasis=UNKNOWN` 分别表示未确认的单位、币种与累计口径。指标中的 `CURRENCY` 仅是供应商原始元数据，不自动成为报表金额的 ISO 币种。

各表的 `SUCCESS` 表示至少一行有可用数值，其他全空数值行仍可保留；`EMPTY/ERROR` 没有可用行。四组结果全部成功时顶层为 `SUCCESS`，部分可用时为 `PARTIAL`，没有可用结果时区分 `EMPTY/ERROR`。这些状态不保证报表项目或请求期间覆盖完整。`statementCount` 是保留行数，不是财报期数。

`asOf` 只取含有效数值行的实际 `REPORT_DATE`；较新的全空行、抓取时间和 `STD_REPORT_DATE` 不能推进业务时点。`fetchedAt` 是获取时间，缓存命中不刷新它。客户端在写入缓存前和命中后校验身份、请求范围、状态、日期与数据；财报缓存版本升级以排除旧港股字典形状，部署顺序为 Python 提供方先于 Java 消费方。

普通证据允许引用部分可用事实，同时区分缺表、有效期数不足、跨表期间不一致与单季口径未确认。三张表和指标均有足够且对齐的有效日期，才通过年度期间覆盖检查；仍不代表每个财务项目齐全或财报足够新。普通回答与 DEEP 在缩减上下文时保留完整项目及身份、期间和单位，标记 `contextReduced`；这是供模型阅读的部分视图，不是可重新作为完整响应验收的传输对象。

字段与接口语义参见 [AKShare 官方财报和指标文档](https://akshare.akfamily.xyz/data/stock/stock.html)。本地适配以项目安装的供应商实现为准，不能将新版文档描述的币种补全等能力当成本地已经具备。指标提供方返回的条数可能少于请求范围，不能从请求参数推导完整覆盖。实施与验证状态统一记录在 [progress.md](../../progress.md)。
