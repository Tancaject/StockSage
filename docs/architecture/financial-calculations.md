# 财务补充计算

[FinancialAnalysisTools](../../stocksage-backend/src/main/java/com/stocksage/tool/FinancialAnalysisTools.java) 使用现有 SEC 客户端与[历史事实契约](sec-financial-contract.md)，固定公式由 [FinancialCalculations](../../stocksage-backend/src/main/java/com/stocksage/research/finance/FinancialCalculations.java) 执行。每项结果附实际输入事实、concept、unit、期间、申报来源和公式；不适用项为 `value=null`，附具体原因。

| 工具 | 范围与计算条件 |
|---|---|
| `getFinancialDiagnostics` | 营收、净利润和经营现金流 CAGR；同一期末净债务。季度不计算 CAGR，缺少净债务分项不补零。 |
| `compareCompanyFinancials` | 两家美股公司的最近共同实际期间。concept、unit、start/end、period_type 全部相同才做差；货币科目为 USD，EPS 为 USD/shares。没有共同期间或存在歧义时留缺，不能按财年标签或行号强配。 |
| `getSemiconductorOperatingMetrics` | 库存/营收、本期存货周转、库存天数及年度营收 CAGR；适合库存驱动分析，但不据此认定公司行业，也不推断销量、产能或库存数量。 |
| `getHistoricalFinancials` | 使用明确的申报信息截止日，返回原 SEC 类型化事实与派生季度的两条来源。已有财务表展示保留截止日及现金流派生口径。 |

CAGR 需要至少两个连续完整年度，下一年 start 必须等于上一年 end 的次日，起终金额为正且单位为 USD。采用实际首末期末相隔天数，年分数约定为 `elapsedDays / 365.2425`；输出为 `((ending/beginning)^(365.2425/elapsedDays)-1)×100`，同时返回天数、年分数和约定，不把行数当成年数。

净债务仅在明确的 `DebtCurrent + LongTermDebtNoncurrent - CashAndCashEquivalentsAtCarryingValue` 同日齐全时输出，负数代表净现金。既有 `TotalDebt` 只有实际 concept 就是 `LongTermDebtNoncurrent` 时才能作为非流动分项使用；不把长期债务当全部债务，不另加可能已包含的商业票据或租赁，也不把含受限现金的金额当作自由现金。

库存/营收为同一期末库存除以当期营收；存货周转为期间成本除以期初、期末库存平均值，库存天数再乘实际期间天数。期初必须是成本期间开始前一天，金额同为 USD，成本和营收分母必须为正；只有期末库存时不冒充平均库存。本期周转次数不自动年化。

## 显式估值情景

[ValuationScenarioTools](../../stocksage-backend/src/main/java/com/stocksage/tool/ValuationScenarioTools.java) 只接受 1～5 套显式假设，不抓价格、不生成终值，也不运行任意公式语言。可单独提供 PE 法或 FCF 法：

- PE 每股价格：`eps × peMultiple`；EPS 缺失或非正时留缺。
- 股权 FCF 每股价格：`equityFreeCashFlow / (dilutedShares × fcfYield)`；股数与收益率必须为正，FCF 缺失或非正时留缺。收益率用小数，如 `0.05`。

币种、期间和股权 FCF 口径由调用者提供并统一，本工具不核验外部来源，也不把企业自由现金流或任意经营现金流差额自动改称股权 FCF。输出回显全部输入和每套假设，两种方法分别显示价格及区间，不平均成单一价值。金额显示两位小数仅为格式，不代表估计置信度。数值输入限制为最多 30 位有效数字、18 位小数，情景名称不得重复。

工具返回 SUCCESS/PARTIAL/EMPTY/ERROR 和逐项缺口。可用计数、schema 版本及其他元数据不属于计算结果，不能凭它们把 EMPTY 当成有财务证据。
