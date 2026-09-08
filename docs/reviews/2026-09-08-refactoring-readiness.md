# 代码审查与重构交付

本轮审查覆盖 Java 后端、Vue 前端、Python data-service 和 rag-eval 的主要调用链。已完成批准的五个批次、六项改动；验证命令、结果与未完成的在线验收统一见 [progress.md](../../progress.md)。本轮结果不代表逐文件安全审计、性能验收或在线回答质量证明。

| 项目 | 已交付状态 | 代码依据 |
| --- | --- | --- |
| R1：旧完成策略 | 删除无生产执行入口的 MarketCompletionPolicy、NewsCompletionPolicy、RagCompletionPolicy 及其孤立测试。保留 ResearchCompletionPolicy 接口和两个实际实现；RAG 业务完成验收仍需单独定义。 | [OrdinaryCompletionPolicy](../../stocksage-backend/src/main/java/com/stocksage/harness/OrdinaryCompletionPolicy.java)、[DeepResearchCompletionPolicy](../../stocksage-backend/src/main/java/com/stocksage/harness/DeepResearchCompletionPolicy.java) |
| R2：预取包装 | 删除丢弃结果的 Markdown 拼接包装、原样重抛的 catch、无调用构造器和专用 ToolCall。股票身份仍读取一次，保留完整原文及读取失败到空结果的行为。 | [ToolPrefetchService](../../stocksage-backend/src/main/java/com/stocksage/service/ToolPrefetchService.java) |
| R3：证据解析归属 | 将原有标的、来源、业务时间、哈希和 envelope 构造集中到一个具体组件，普通取证与 DEEP 直接共用；普通取证不再依赖 DEEP 收集服务。 | [EvidenceEnvelopeMapper](../../stocksage-backend/src/main/java/com/stocksage/service/EvidenceEnvelopeMapper.java)、[OrdinaryEvidence](../../stocksage-backend/src/main/java/com/stocksage/service/OrdinaryEvidence.java)、[DeepEvidenceCollector](../../stocksage-backend/src/main/java/com/stocksage/service/DeepEvidenceCollector.java) |
| R4：评测客户端归属 | 现有 HTTP 客户端、异常及 SSE 解析移到独立模块；普通和 DEEP runner 直接导入。Cookie、CSRF、截止时间、部分事件异常与回调规则保持原样。 | [eval_http.py](../../rag-eval/eval_http.py)、[普通 runner](../../rag-eval/run_ordinary_live_eval.py)、[DEEP runner](../../rag-eval/run_harness_live_eval.py) |
| R5：统一 RAG 门禁 | 评测端产出版本化判定，前端展示输出中的实际阈值、必需/可选标记及状态，删除前端重复阈值。 | [eval_summary.py](../../rag-eval/eval_summary.py)、[run_rag_eval.py](../../rag-eval/run_rag_eval.py)、[workbench.js](../../stocksage-frontend/src/lib/workbench.js) |
| R6：缺失数值显示 | 原始诊断中的 null、undefined 与空值显示 NO_DATA；真实数值零仍显示 0.000。 | [EvalDesk.vue](../../stocksage-frontend/src/views/EvalDesk.vue) |

R5 是明确的判定行为修复：`gate_evaluation` 使用 `rag_gates_v1`，质量门禁阈值以 [DEFAULT_GATES](../../rag-eval/eval_summary.py) 为唯一来源。缺少必需证据或全部门禁证据缺失时不能通过；可选指标缺失不阻止其他已测项通过。延迟保留为原始诊断，不参与默认质量门禁。历史文件缺少判定元数据时显示“未评定”；可通过 `eval_summary.py <result.json> --output <summary.json>` 生成包含门禁和用例的可导入结果，`--fail-on-gate` 对失败或必需证据不足返回非零退出码。已有判定中的自定义阈值保留，不被默认值覆盖。

R3 保留原有 NO_RESULTS、来源 allowlist、as-of、payload hash、approvedReadOnly 和标的规范化规则，没有增加解析接口或供应商工厂。普通与 DEEP 同输入的 envelope 对比纳入现有 [证据回归](../../stocksage-backend/src/test/java/com/stocksage/service/DeepEvidenceCollectorTest.java)，仅分别采集的 observedAt 不要求相等。

DEEP 同步恢复和后台恢复分别保留：后台路径还处理 previousAttempts、悬挂报告和历史 effect key。租约/fencing、checkpoint、原子发布、SSE 断线恢复、真实供应商 fallback 和 IBKR 只读边界均未调整。data-service 本轮没有确认新的可删除项，现有共享指标计算与供应商调用保持原样；没有新增依赖。

原有 RAG_EVALUATION.md 删除及无关工作区编辑保持原状。后续在线验收与既有阻塞见 [progress.md](../../progress.md)，不以本轮离线回归代替。
