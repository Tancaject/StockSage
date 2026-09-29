# 项目代码审查与修复交付

本轮审查列出的 A01–A16、P3 建表冗余、前端依赖公告和 RSI 测试断言问题均已落实代码修复。范围覆盖请求与标的约束、Agent 取证及恢复、RAG 发布一致性、前端异步状态、数据服务与隐私导出。

本文件记录最终行为及回归入口；测试数量、执行日志、环境阻塞和后续在线验收统一记录在 [progress.md](../../progress.md)。离线回归通过不等于真实供应商质量、线上账号隔离或 MySQL/Milvus 故障注入已经通过。

## 修复清单

| 编号 | 原级别 | 修复后的行为 | 实现与验证入口 |
| --- | --- | --- | --- |
| A01 | P1 | SEC 年度财务按实际报告期间去重，区分全年累计值与年末时点，采用最新公告并拒绝同公告冲突值。公告财年不再冒充事实期间。 | [SEC 服务](../../stocksage-data-service/app/services/edgar_service.py)、[期间契约回归](../../stocksage-data-service/tests/test_edgar_contracts.py) |
| A02 | P1 | 原问题明确的标的约束模型改写；原问 AAPL、改写 MSFT 时，在取证前返回 BLOCKED 并要求明确标的。改写遗漏标的时仍绑定原始对象。 | [Coordinator](../../stocksage-backend/src/main/java/com/stocksage/agent/Coordinator.java)、[入口回归](../../stocksage-backend/src/test/java/com/stocksage/agent/CoordinatorPlanTest.java) |
| A03 | P1 | 普通路线与 DEEP 共用业务有效性判断。来源、计数、日期或空容器不能算有效财务；行情要求有效 OHLC。真实零保留，新闻无结果保持独立语义。 | [EvidenceEnvelopeMapper](../../stocksage-backend/src/main/java/com/stocksage/evidence/adapter/EvidenceEnvelopeMapper.java)、[证据回归](../../stocksage-backend/src/test/java/com/stocksage/service/EvidenceEnvelopeMapperTest.java) |
| A04 | P1 | 财务只计入基本面，并通过统一接口采集一次；SEC 成功、行情失败时仍缺少 MARKET 证据，不能跨维度放行。 | [DeepEvidenceCollector](../../stocksage-backend/src/main/java/com/stocksage/research/DeepEvidenceCollector.java)、[取证回归](../../stocksage-backend/src/test/java/com/stocksage/research/DeepEvidenceCollectorTest.java) |
| A05 | P1 | Agent 汇总直接消费现有 RAG 门禁：fail 为 failed，缺失或未知为 incomplete；前端也明确显示未完成。 | [汇总器](../../rag-eval/agent_eval_summary.py)、[生产者到消费者回归](../../rag-eval/test_agent_eval_summary.py)、[工作台状态](../../stocksage-frontend/src/lib/workbench.js) |
| A06 | P2 | Collector 按证据项形成有界快照，Bull、Bear、Manager 使用同一正文，不再整体头截断。聚焦补证的 ID 和正文进入实际 prompt；超长来源字段也有预算。 | [上下文契约](../architecture/ordinary-agent-execution.md)、[实际 prompt 回归](../../stocksage-backend/src/test/java/com/stocksage/research/DeepEvidenceCollectorTest.java) |
| A07 | P2 | 恢复扫描纳入超时的初始 PENDING，补齐保存成功、首次入队前退出的投递缺口；重复投递仍由现有 CAS、lease 和 fencing 约束。 | [ResearchTaskService](../../stocksage-backend/src/main/java/com/stocksage/research/ResearchTaskService.java)、[初始 PENDING 回归](../../stocksage-backend/src/test/java/com/stocksage/research/ResearchTaskServiceTest.java) |
| A08 | P2 | 新向量先写，SQL 提交后发布并清理旧版本；失败残留按来源版本重试清理。向量与 BM25 候选均经过 SQL 镜像校验，正文和父块不能跨版本串用。 | [摄取](../../stocksage-backend/src/main/java/com/stocksage/knowledge/KnowledgeIngestionService.java)、[检索](../../stocksage-backend/src/main/java/com/stocksage/rag/RagService.java)、[事务故障回归](../../stocksage-backend/src/test/java/com/stocksage/knowledge/KnowledgeIngestionTransactionTest.java) |
| A09 | P2 | embedding 必须非空、维度匹配、有限且非零；同一完整文本重试仍失败就报错，不再删除词语或返回零向量伪装成功。旧来源下次摄取时重新验证 embedding。 | [LocalEmbeddingModel](../../stocksage-backend/src/main/java/com/stocksage/rag/LocalEmbeddingModel.java)、[无效向量及重试回归](../../stocksage-backend/src/test/java/com/stocksage/rag/LocalEmbeddingModelTest.java) |
| A10 | P2 | 会话和股票新闻请求同时校验请求代次与目标；旧请求的成功、失败和 finally 均不能覆盖新对象，新建会话也会使旧请求失效。 | [ChatView](../../stocksage-frontend/src/views/ChatView.vue)、[NewsPanel](../../stocksage-frontend/src/components/workbench/NewsPanel.vue)、[实际组件函数回归](../../stocksage-frontend/src/lib/async-ownership.test.mjs) |
| A11 | P2 | 工作台消费 task-final，用最终答案替换受理消息并结束运行；失败或停止状态不会被改写为成功。 | [事件归约](../../stocksage-frontend/src/lib/researchRun.js)、[终态回归](../../stocksage-frontend/src/lib/researchRun.test.mjs) |
| A12 | P2 | 共享指标计算将 NaN/Infinity 转为 None，真实零不变；供应商不再各自传入舍入和非有限值处理回调。 | [指标计算](../../stocksage-data-service/app/services/technical_indicators.py)、[严格 JSON 回归](../../stocksage-data-service/tests/test_technical_indicators.py) |
| A13 | P2 | PDF 解析移到受限且可取消的子进程，不阻塞异步路由；上传量、页数、提取字符量、分块参数和解析时长均有明确边界，超限返回可操作错误。 | [路由](../../stocksage-data-service/app/routers/document.py)、[解析器](../../stocksage-data-service/app/services/pdf_parser.py)、[ASGI 与真实 PDF 回归](../../stocksage-data-service/tests/test_document_contracts.py) |
| A14 | P2，条件性 | SEC 客户端及每次请求均禁止自动跟随重定向，3xx 在发送第二跳之前被拒绝；模拟同域和跨域跳转均只发送初始请求。 | [SEC 请求](../../stocksage-data-service/app/services/edgar_service.py)、[重定向回归](../../stocksage-data-service/tests/test_edgar_contracts.py) |
| A15 | P2，需启用 | Phoenix 默认只导出类型、长度、计数、耗时与 traceId。用户/会话 ID、问题、工具结果、路由原文和错误原文不默认外发；正文导出须单独启用。 | [配置说明](../../README.md)、[导出实现](../../stocksage-backend/src/main/java/com/stocksage/trace/PhoenixTraceService.java)、[实际 span 属性回归](../../stocksage-backend/src/test/java/com/stocksage/trace/PhoenixTraceServiceTest.java) |
| A16 | P2 | 仅调整周期或粒度时继承 barsOnly；用户明确增加分析或风险需求时再扩大任务，避免恢复无关取证动作。 | [ReadRequest](../../stocksage-backend/src/main/java/com/stocksage/agent/ReadRequest.java)、[追问回归](../../stocksage-backend/src/test/java/com/stocksage/agent/CoordinatorPlanTest.java) |
| P3 | P3 | 删除两个 Repository 的启动期建表代码，由 Flyway 负责；兼容迁移为历史 baseline 库补齐缺失表，不修改已执行的 V1。 | [V11 迁移](../../stocksage-backend/src/main/resources/db/migration/V11__ensure_rag_index_tables.sql)、[缺表与重复执行回归](../../stocksage-backend/src/test/java/com/stocksage/knowledge/KnowledgeIngestionTransactionTest.java) |

## 跨模块与历史数据契约

### SEC 年度事实

`start/end` 与 `period_type` 描述实际期间，`filing_fiscal_year` 仅保留公告的 `fy`。无法可靠确定财政年度标签时，`fiscal_year` 为 null，不按日历年猜测。年度累计值必须符合 SEC 的全年期间范围；时点值的年末日期要有同一公司年度累计事实支持。选择规则与数组顺序无关，并保留 `filed/form/accn/fp/frame/source_url`。

后端以 `concept.data[].value` 验证 SEC 业务值，新增的公告年份等元数据不能把 `value: null` 变成 AVAILABLE。范围依据见 [SEC EDGAR API 说明](https://www.sec.gov/search-filings/edgar-application-programming-interfaces)；回归覆盖非自然财年、比较期、季度排除、修订公告、乱序和年末时点。

### DEEP checkpoint

完成策略升级后，旧版本或没有版本标记的证据 checkpoint 会失效，并在既有剩余恢复预算内重取证据。当前采集成功路径会保存新版本标记，避免接管时重复取证。已有尝试次数、轮次、续停授权和发布状态继续由原执行器与 fencing 管理；升级不重置这些限制。证据不足或旧结论不能满足当前引用与策略时，继续返回 NOT_RATED。

运行时策略版本由 [DeepResearchCompletionPolicy](../../stocksage-backend/src/main/java/com/stocksage/harness/DeepResearchCompletionPolicy.java) 定义，对应评估配置及 manifest 已同步。离线回归覆盖初次采集后的重评、恢复、接管与现有报告发布路径；真实模型输出质量仍需在线验收。

### RAG 来源发布与清理

来源索引和 SQL 文档镜像是发布依据。每个来源先持久化可恢复的索引记录，再持有来源行锁写入新版本向量；SQL 回滚时旧索引与旧镜像继续存在，旧向量不提前删除。提交后的清理重新取得同一来源锁，按来源与当前版本删除未发布或过期版本；清理失败由下次摄取或现有维护任务重试。

向量与 Lucene 是派生索引：候选 ID 不在 SQL 中就不能成为回答证据，候选正文和版本元数据从 SQL 读取。父块扩展还要求来源和版本一致，禁止从 Milvus 绕过 SQL 读取已删除的父块。维护任务保留空来源记录，使首次摄取失败或 TTL 删除后的残留也有可恢复的清理范围。

内容指纹包含 embedding 校验代次，因此旧来源下次摄取时不会因旧 hash 相同而跳过。此次没有连接实际用户向量库，也没有批量重建历史数据；历史来源需由现有摄取入口重新处理。没有 SQL 镜像的历史孤立向量不会继续作为回答证据。

此方案没有把 SQL 和 Milvus 包装成同一个事务。回归使用真实 H2 SQL 事务、Spring 事务代理及独立模拟向量存储，覆盖分批写入失败、SQL 回滚、清理失败及重试、首次失败后的新实例恢复、旧 BM25 正文与父块版本不匹配。真实 MySQL/Milvus 的一致性、锁竞争及断电恢复仍需集成环境验收。

### PDF 与隐私导出

PDF 使用现有 AnyIO 子进程执行器及受限并发，遵循 [PyMuPDF 不支持多线程使用的约束](https://pymupdf.readthedocs.io/en/latest/recipes-multiprocessing.html)。参数及工作量限制直接定义在文档路由和解析器中；错误分别指明输入无效、内容超限或解析超时。回归实际解析 PDF，并检查解析期间事件循环可继续执行。

Phoenix 的正文导出选项与隐私说明见 [README](../../README.md)，配置默认值以 [application.properties](../../stocksage-backend/src/main/resources/application.properties) 为准。回归通过实际 OpenTelemetry span 检查默认关闭正文与显式开启两条路径；不声称已经检查远程 collector 的留存政策。

## 重复实现与依赖

- 普通和 DEEP 取证共用业务有效性检查；基本面财务只采集一次；证据正文由 Collector 一次分配预算。
- 指标共享层统一 JSON 数值契约；Agent 报告导入权威 RAG 判定，不复制评估阈值。
- 移除零向量成功转换及有损文本变体；移除 Repository 运行时 DDL，保留 Flyway 历史迁移不可修改的约束。
- 前端只更新受影响依赖的补丁版本，锁文件已同步。依赖公告检查及构建结果见 [验证记录](../../progress.md)，不把扫描无命中解释为不存在应用安全风险。
- RSI golden-set 回归改为检查样本声明的真实标的，不再把所有大写词视为股票代码；没有更改该样本的业务路由期待。

DEEP lease/fencing、checkpoint、双方轮次原子写入、受控续停、报告原子发布、SSE 重放与断线恢复、供应商 fallback 和 IBKR 只读边界均保留。本次没有重建三服务架构，也没有因文件较长而增加接口、工厂或微服务。

## 验证边界与后续验收

相关自动回归及前端构建已通过，详情见 [progress.md](../../progress.md)。安全源码复核未确认默认配置下的 IDOR 或前端 XSS；这不替代在线多账号检查、动态渗透或 Maven/Python 依赖审计。

上线验收还需要健康的真实服务与评估账号：执行 MySQL/Milvus 的替换失败、恢复和并发场景，重摄取历史来源，再运行既有检索/RAG 与 Agent live gates。不能用模拟 HTTP 测试生成的 smoke/release 文件替代这些结果。
