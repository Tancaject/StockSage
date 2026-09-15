# 代码冗余清理

已完成本轮审查确认的 10 项清理。统计相对于清理开始时的工作区，排除原有未提交修改；验证证据统一记录在 [progress.md](../../progress.md)。

| 项目 | 最终实现 | 保留的边界 |
| --- | --- | --- |
| 未使用的市场工具入口 | 删除 MarketTools 中的对比、IBKR 引导及账户查询包装，以及 DataServiceClient.compareStocks 和对应工具标签。 | IBKR 只读服务、会话保活与 Python `/compare` 接口保留。 |
| 无调用的服务包装 | 删除 ReportMarkdownRenderer 的旧运行中答复和 evidenceBasis、TraceService.listTraces 及专用仓库查询、InvestmentReportVersionService.persistReportVersion 包装。 | 报告持久化继续使用已有的带元数据入口。 |
| 重复回答客户端与重载 | [Coordinator](../../stocksage-backend/src/main/java/com/stocksage/agent/Coordinator.java) 共用一个无工具回答客户端；删除重复 Bean、无调用重载和未使用的 ragSummary 参数。 | DEEP 的模型层级提升、视觉模型选择、服务端路由契约保持。 |
| 重复 SSE 生命周期 | [sse-stream.js](../../stocksage-frontend/src/lib/sse-stream.js) 共用请求、取消、空闲超时和事件分发；两个 API 仅提供请求参数与提示。 | POST/GET、Last-Event-ID、entryId、心跳续时、主动取消和原有超时提示保持。 |
| 未注册的兼容工具 | 删除 CompatibilityTools 的 code_interpreter 占位实现。 | 模型工具权限继续由现有客户端配置和服务端执行链控制。 |
| 重复 JSON 文本处理 | [JsonText](../../stocksage-backend/src/main/java/com/stocksage/util/JsonText.java) 统一 Manager、长期记忆与意图识别中的围栏移除和对象文本提取。 | 各调用方的 schema、字段校验、严格解析和失败语义保持。 |
| 单角色计划的三路 Future 汇合 | [ToolPrefetchService](../../stocksage-backend/src/main/java/com/stocksage/service/ToolPrefetchService.java) 按普通计划执行一个分析师 Future。 | 原超时与已有证据继续回答行为保留；DEEP 内真实并行不变。 |
| Skill 内部重复处理 | [SkillRegistry](../../stocksage-backend/src/main/java/com/stocksage/skill/SkillRegistry.java) 一次完成加载、校验与去重；删除执行模式不可达分支，步骤数直接取集合大小。 | 启动清单校验、逐次 Gateway 授权和能力预算保留。 |
| 新闻检索的重复本地尝试 | [SkillExecutionService](../../stocksage-backend/src/main/java/com/stocksage/skill/SkillExecutionService.java) 在已选 Skill 的可选能力全部失败时记录 FAILED 与证据缺口；预取层仅在未选 Skill 时调用旧入口。 | 不把已尝试记为成功；MCP→本地、Tavily→DDG 的实际供应商 fallback 保留。 |
| 未使用的 BaoStock 对比方法 | 删除 BaostockService.compare_stocks。 | 实际 HTTP `/compare` 的市场解析与财务查询保持。 |

DEEP 自适应轮次、租约/fencing、checkpoint、SSE 回放、Harness fail-closed、输入信任边界和供应商切换均保留。本轮没有删除依赖，也没有引入新依赖或通用执行框架。
