# 组合、ETF 与条件筛选

工作台的「持仓」接收用户提供的 JSON 表，直接调用确定性计算。它不读取券商账户，也不保存持仓表。HTTP 接口沿用项目的登录与 CSRF 保护。三个工具也可通过「工具 / Skill」入口使用。

## 持仓与情景

工具 `analyzePortfolio(inputJson)`，HTTP `POST /api/workbench/portfolio/analysis`。HTTP 请求体为下列对象，工具的 `inputJson` 为该对象的 JSON 字符串。

```json
{
  "asOf": "2026-10-28",
  "positions": [
    {"symbol":"AAPL","currency":"USD","quantity":2,"price":100,"kind":"SECURITY"},
    {"symbol":"USD","currency":"USD","quantity":100,"price":1,"kind":"CASH"}
  ],
  "scenarios": [{"name":"价格下降10%","returns":{"AAPL":-0.1}}],
  "prices": {"AAPL":[{"date":"2026-10-28","close":100}]}
}
```

示例数值仅说明格式，不是实际行情。`positions` 必填、最多 200 条，代码和币种组合不可重复。数量可为负以表示空头或现金借款；价格必须为正。现金数量表示本币金额，价格必须为 1。同一证券的多笔持仓应先合并为一行。

每个币种分别计算：

- 市值 = 数量 × 输入价格；净值 = 市值之和；总绝对敞口 = 绝对市值之和。
- 净值权重 = 市值 / 净值；净值为 0 时返回 `null`。
- 绝对敞口权重 = 绝对市值 / 总绝对敞口；HHI 为绝对敞口权重的平方和，包含现金。
- 情景损益 = 市值 × 价格变化率。最多 20 个情景，变化率不得低于 -1。本币现金变化率为 0。证券缺少变化率时，`totalPnl` 为 `null`，同时返回 `knownPnl` 和 `unknownSymbols`，不会把未知变化率补成 0。

`prices` 可选，最多 20 个证券、每个最多 1000 个收盘价。日期不能重复或晚于组合日期，收盘价必须为正。相关性先交集收盘日期，再计算相同区间的价格收益率并做 Pearson 相关性。至少需要 4 个共同收盘日期；样本不足或收益率无变化返回 `null` 和原因。价格按用户输入处理，不自动复权、补分红、换汇。`returnCount` 是共同区间数量，不保证每个区间恰为一个交易日。

## ETF 单层穿透

工具 `lookThroughEtfs(inputJson)`，HTTP `POST /api/workbench/portfolio/etf-lookthrough`。

```json
{
  "asOf":"2026-10-28",
  "positions":[
    {"symbol":"DEMO-ETF","currency":"USD","marketValue":1000,"isEtf":true},
    {"symbol":"AAPL","currency":"USD","marketValue":500,"isEtf":false}
  ],
  "funds":[{"symbol":"DEMO-ETF","asOf":"2026-10-27","holdings":[
    {"symbol":"AAPL","weight":0.4},
    {"symbol":"MSFT","weight":0.35}
  ]}]
}
```

持仓最多 200 条，市值必须非负；ETF 表最多 50 个，每个最多 2000 个成分。成分代码不可重复，权重为 0 到 1 的小数，权重之和不能超过 1，成分日期不能晚于组合日期。

直接持仓和 `ETF 市值 × 成分权重` 按同一代码合并，币种分开。未披露权重对应 `uncoveredValue`，缺少整张成分表时 `coverage` 为 `null`，整笔 ETF 计入未知暴露。成分中若含另一个 ETF，只保留其代码，不继续猜测穿透。输出保留每张成分表的实际日期。

ETF 两两重叠为已披露相同代码的 `min(权重A, 权重B)` 之和。任何一张表未完全覆盖时，结果只是重叠的下界；缺少成分表时返回 `null`。`complete` 只表示两张输入表权重都达到 100%，不保证两个日期相同或代表最新成分。

## 显式范围条件筛选

工具 `screenStocks(inputJson)`，HTTP `POST /api/workbench/portfolio/screen`。

```json
{
  "asOf":"2026-10-28",
  "universe":["AAPL","MSFT"],
  "rows":[{"symbol":"AAPL","asOf":"2026-10-28","metrics":{"PE":25}}],
  "rules":[{"metric":"PE","operator":"LT","value":30}]
}
```

范围最多 1000 个证券，规则最多 20 条。只筛选明确的 `universe`，不会自动搜索全市场。`rows` 可留空，不能出现范围外或重复代码。指标数值必须为 JSON 数字或 `null`；指标的单位、财期和估值口径由输入者保持一致。

支持 `GT/GTE/LT/LTE/EQ/BETWEEN`；`BETWEEN` 还要求 `maxValue`，包含两个端点。规则之间为 AND：存在已知失败则 `FAIL`；没有失败但有缺项则 `UNKNOWN`；全部符合才为 `PASS`。缺少整行、指标或指标日期晚于筛选日期都保留为 `UNKNOWN`，结果保留逐规则状态及数据日期。

三个结果均返回 `source=USER_PROVIDED`，不会把输入表当成供应商验证的数据。工具输入最多 500000 字符；无效输入返回结构化错误及待修正字段。
