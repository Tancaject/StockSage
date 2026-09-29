# 新闻与网页搜索契约

新闻和网页搜索共用一个响应契约，由 Python 搜索服务提供、[Java SearchResponse](../../stocksage-backend/src/main/java/com/stocksage/client/SearchResponse.java) 校验。股票新闻额外保留路由身份和 `requestedDays`；搜索摘要仍使用 `title/link/snippet/date/source`，供工具、工作台与知识采集复用。

## 请求与实际检索

`requestedTimelimit/requestedDepth/requestedMaxResults` 记录接受的请求，`effectiveTimelimit/effectiveDepth/topic/provider` 记录实际供应商请求。兼容字段 `timelimit/depth` 分别等于对应的 effective 字段。DuckDuckGo 不具有 Tavily 的 advanced 模式，因此 `effectiveDepth` 为空。新闻接口不支持的年度窗口不会被标记为已生效。

股票新闻将正整数天数映射到供应商的日、周、月窗口：1 天使用日窗口，2—7 天使用周窗口，其他使用月窗口。`requestedDays` 仍保留原值，不能把“3 天请求使用一周过滤”描述成严格筛选了最近 3 天。检索过滤也不证明每条结果都有日期或处于所需期间。

Tavily 请求使用 `time_range`，fallback 保留实际 DDG 提供方和固定分类的原因；不把异常原文、请求头或凭证放入响应。参见 [Tavily 搜索 API](https://docs.tavily.com/documentation/api-reference/endpoint/search)。网页与新闻的提供方差异由契约表达，不通过改写用户问题掩盖。

## 文章时间与来源

每条结果保留绝对 HTTP(S) 来源链接以及至少一项非空标题或摘要。`date` 是供应商原始文本；以下字段是程序使用的时间表示：

- `INSTANT`：仅 `publishedAt` 有值，使用 UTC 时间；原始时间必须带可识别的时区。
- `DATE`：仅 `publishedDate` 有值，不擅自补午夜或时区。
- `UNKNOWN`：二者为空；相对时间、无时区时间或无法解析的文本仍保留在 `date`。

`publishedTimeBasis=PROVIDER_REPORTED` 表示供应商报告的时间，可能是估计的发布或更新时间，不是对原始出版时间的认证。`fetchedAt` 是获取时间，缓存命中不刷新。响应不设置跨文章的 `asOf`；证据头的 URL 和时刻来自同一条结果，不能取另一篇文章的最新日期，也不能用获取时间替代。

## 状态与消费

`SUCCESS` 必须包含有效结果，`EMPTY` 表示本次检索没有结果，`ERROR` 表示失败。失败没有可用结果，携带 `errorCode/message/retryable`；无法确认的可重试性保持为空。部分供应商格式损坏不会通过静默丢行伪装成完整成功。每次 fallback 是一次明确的供应商选择，不改变时间和深度元数据的含义。

Java 在写入缓存前和读取后校验，搜索响应在熔断器内部完成协议校验，使错误响应计入失败；缓存命中保留现有不触发供应商调用的行为。缓存命名空间以[客户端](../../stocksage-backend/src/main/java/com/stocksage/client/DataServiceClient.java)为准，旧形状通过版本隔离。部署顺序为 Python 提供方先于 Java 消费方。

普通与 DEEP 共用[时效判定](ordinary-agent-execution.md#可恢复的时间事实)，区分未应用窗口、请求天数与实际窗口不一致、发布时间精度不足、未来时间与窗口外结果；已有内容仍可引用，但不能声称请求已完整满足。判断使用整批文章的时间范围与精度，不能靠一篇新文章掩盖其他未确认结果。普通回答与 DEEP 缩减上下文时保留首条来源与时间，摘要截取显式标记 `snippetTruncated`。若连来源元数据也放不下，明确标记未提供可引用内容，不用另一篇文章填补原证据头。工作台将业务错误与正常空结果分开显示，知识采集统一使用顶层布尔错误判定。

验证证据、部署及后续新鲜度验收状态统一记录在 [progress.md](../../progress.md)。本契约不保证搜索结果穷尽、权威来源完整或市场信息实时。
