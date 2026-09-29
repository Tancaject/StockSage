# 行情数据契约

A/H 股 Python K 线字段结构以 [Pydantic 模型](../../stocksage-data-service/app/kline.py) 和它生成的 `/openapi.json` 为准。Java 在数据客户端边界反序列化为对应领域类型；模型工具仍输出 JSON，图表仍使用原有 `points` 协议。provider 内部结果不充当跨服务契约，技术指标计算可以继续消费各自 provider 的原始数据。

## 时间与状态

Python 日线契约的 `asOf` 表示结果中最新一根 bar 的业务日期；`timeKind=DATE` 明确精度只有日期，不能据此推断收盘时刻或分钟级实时性。`fetchedAt` 是数据服务本次取数完成的 UTC 时间，缓存命中保留原值。研究证据的 `observedAt` 是本次研究看到结果的时间。这三个字段不可互相替代。

现有证据账本的 `asOf` 字段使用 `Instant`，日期转为 UTC 零点仅用于兼容该存储类型，不代表真实交易时刻。原始工具结果和压缩上下文仍保留 `timeKind=DATE`，不得将这个兼容值解释成分钟级新鲜度。

缓存 TTL 控制重新获取频率，不表示数据足够新。`SUCCESS` 表示拿到了符合结构要求的数据，不表示最新交易日已收盘、bar 已完成或没有行情延迟。`EMPTY`、`UNSUPPORTED` 和 `ERROR` 分别表达没有数据、能力不支持和调用/契约失败；`error` 为旧消费者保留的失败投影。未知可重试性保留 `null`，不能从异常自然语言猜测应否重试。

`period` 是 bar 粒度，`days` 请求参数是自然日回看窗口，不承诺返回同样数量的交易记录。空值不补零，非有限数值不作为有效行情。发生 provider fallback 时保留实际 provider 和切换原因，不用路由预期来源覆盖实际来源。

## 价格与成交量口径

当前 Python 的 AKShare 调用使用 `qfq`，BaoStock 调用使用 `adjustflag=2`；契约显式报告前复权。前复权历史价格会随后续权益事件调整，不能仅凭相同日期认定两次抓取或两个 provider 的序列完全相同。

AKShare 的 `stock_zh_a_hist` 文档将成交量定义为手、成交额定义为元；`stock_hk_hist` 将价格/成交额定义为港元、成交量定义为股。因此记录币种和成交量单位，保留供应商数值，不在边界默默换算。未知单位或币种保持未知，不按市场名称推断。依据：[AKShare 官方字段说明](https://akshare.akfamily.xyz/data/stock/stock.html)。

普通回答缩减长数据时保留时间、币种、成交量单位和复权口径，避免模型只看到脱离含义的数值。图表协议携带这些元数据；不同口径的数据不能因为字段名相同就直接拼接。

## IBKR 历史行情

IBKR 的历史响应由 [IbkrHistoricalResponse](../../stocksage-backend/src/main/java/com/stocksage/ibkr/IbkrHistoricalResponse.java) 定义，独立于 Python 的日期契约。`period` 是实际请求窗口，`bar` 是粒度；bar 的 `t` 保持 epoch 毫秒，`asOf` 取最新 bar 时间，`fetchedAt` 记录获取时间。时间戳不证明最后一根 bar 已结束。

`historyMetadata` 保留供应商返回的价格/成交量因子、可用性、处理延迟与覆盖信息。`requestedOutsideRth` 记录请求，供应商的 `outsideRth` 记录响应；请求盘前盘后数据不等于供应商确实提供了这部分数据。缺失币种、复权或延迟资格保持未知，不从静态市场配置补成已确认值。

原始 OHLC 与成交量不做隐式二次缩放；因子随数据保留。`mktDataDelay` 的官方说明是处理历史请求的延迟，不能直接当作报价延迟分钟数，`mdAvailability` 也保留原码供解释。依据：[IBKR 历史行情字段说明](https://ibkrcampus.com/docs/web-api/v1/endpoints/market-data/historical-market-data)和[OHLC 接口示例](https://www.interactivebrokers.com/docs/web-api/api-reference/trading/trading-market-data/get-md-history)。

## 演进边界

Python `/api/stock/kline` 负责 A 股和港股，美股请求明确返回不支持并指向现有只读 IBKR 能力。IBKR 的时间戳、权限、合约和延迟语义属于其适配边界，不能拿 Python 的日线约定覆盖它。

Python K 线缓存使用独立版本命名空间，避免新消费者读到旧结构；失败不写入成功缓存。IBKR 保持原有独立调用路径。schema 变更要同步检查 Java 领域类型、证据提取、图表映射和同一份跨语言样本。其他数据能力逐项迁移，不从本契约推定财报和新闻已完成统一。

迁移时先部署提供规范响应的 Python 服务，再部署强类型 Java 消费者。新 Java 不接受缺少版本的 Python 响应，会返回明确的协议错误；不要通过猜测旧字段掩盖服务版本不匹配。Java 自己产生的 HTTP/协议错误无法确认上游市场、供应商或标的，相关字段允许为空；请求周期本身非法时也保留未知，不伪造 `daily`。

实施与验证状态见 [progress.md](../../progress.md)；这里不维护运行状态或测试通过数量。
