# StockSage TODO

最后复核：2026-08-12

本文件只保留当前未完成工作。已完成里程碑、旧方案和历史提交顺序见
[历史路线图](docs/archive/project-history/todo-through-2026-07.md)。项目状态与验证证据分别以
`feature_list.json` 和 `progress.md` 为准。

## P0：当前发布门禁

### Agent 路由现场评测

- [ ] 使用当前 provider 跑 `LIVE_COORDINATOR` 115-case 固定集，route accuracy 与 Macro-F1 均 `>= 0.95`
- [ ] 15 个上下文用例的 context route、typed intent 与 resolved-query ticker accuracy 均 `>= 0.95`
- [ ] 固定集必须匹配 `planner_eval_v2`、115/100+15 精确计数与 canonical JSONL SHA-256
- [ ] non-fallback route accuracy 与 raw LLM signal accuracy 均 `>= 0.95`
- [ ] fallback rate `= 0`，invalid raw route rate `= 0`
- [ ] required action recall `>= 0.98`，forbidden action rate `= 0`
- [ ] critical 用例误路由 `= 0`，结构化输出可执行率 `= 1.00`
- [ ] 相比确定性基线的关键指标下降不超过 `0.02`
- [ ] 路由 P95 不超过既有基线的 `1.2x`
- [ ] 任一门禁失败时 CLI 非零退出；运行时 provider 失败仍走确定性 fallback

### DEEP Harness live release gate

- [ ] 先跑一次最小断连复验，确认 durable task 与 Trace 均以 `success` 结束
- [ ] 从全新 checkpoint 执行固定 30-case DEEP live gate，不复用历史 singleton、中断运行或 5-case smoke
- [ ] 要求 30/30 完成、policy v3 与 manifest hash 精确匹配、safe-terminal rate `= 1.0`、release violations `= 0`
- [ ] 只有完整 live gate 明确通过后，才启动 H1 或将 G5 policy 接入真实 route

## P1：Capability、Skill 与 MCP 验收

- [ ] 配置一个可信只读 Streamable HTTP MCP server，完成 initialize、tools/list、tools/call 现场验收
- [ ] 验证 MCP 成功、MCP → 本地 fallback、双失败回 legacy 三条路径的 Trace 与指标
- [ ] 确认当前 NEWS Skills、默认 Skill 和 fallback 链完整可见
- [ ] 扫描管理 API、前端状态、日志和 Trace，确保不泄漏 query、userId、traceId、token、URL 或原始异常栈
- [ ] 验证后端不可用、403、无指标和 MCP disabled 均显示不同且准确的 UI 状态
- [ ] V1 稳定后再评估带完整候选校验、原子切换和审计记录的热更新

## P1：Research Memory live 门禁

- [ ] tenant leakage `= 0`，无来源捕获 `= 0`，同一报告重复记录 `= 0`
- [ ] Milvus 故障时报告保存成功率 `= 1.00`，恢复后可补偿索引
- [ ] 记忆 candidate `Recall@30 >= 0.95`；默认 final `Recall@6 >= 0.85`，并记录 `nDCG@6`
- [ ] 自适应最终记忆上限按 `BRIEF/STANDARD/DEEP` 使用 `4/6/8`，低相关时不得填充无关记忆
- [ ] 记忆 Prompt 不超过 `4800` 字符，暖机后新增 P95 `<= 300ms`
- [ ] 删除或撤销后立即不再召回
- [ ] 被替代旧结论、未消解冲突结论泄漏 `= 0`，不同投资期限的结论可以共存
- [ ] 开启记忆后，现有 RAG Eval gate 不回退

## P1：RAG 质量实验与门禁

- [ ] 记录当前 50-case LLM-judge 完整基线，不以 runner 已存在代替运行证据
- [ ] 增加 duplicate-rate 与 near-duplicate context 检查
- [ ] 结果中固定记录 git commit、chunking/embedding/rerank 版本、top-k 与 feature flags
- [ ] 本地 gate 稳定后，在可用 CI runner 中接入 `eval_summary.py --fail-on-gate`
- [ ] 用户完成 EDGAR v4 重入库后，对 Contextual Retrieval 的关闭、child 开启两组执行同集 A/B；达标后才默认开启

## P1：Harness 扩展

### G5：其他 route policy

- [ ] 补齐 Market/News CompletionPolicy 的 as-of、样本量、target 和 time-window 规则后再接入 route
- [ ] 补齐 RAG CompletionPolicy 的 target、citation 和 no-answer 规则后再接入 route
- [ ] DEEP 外部证据调用逐步收口到现有 `CapabilityGateway`
- [ ] 至少两个策略稳定后，再评估 Skill `completionPolicyId`

### H1：DEEP walking skeleton 完整化

- [ ] 冻结最小 `ResearchRunSpec/PolicyBundle`，运行期间不允许 policy drift
- [ ] Trace 统一记录 policy、effect、预算和业务终态
- [ ] Research Memory 物理向量删除增加有界重试与 Outbox/DLQ

### H2–H4：Shadow、Claim Ledger 与生产证据

- [ ] NEWS、MARKET、RAG 分别建立强类型 fixture、golden set 和 live cases
- [ ] Shadow 不改变用户响应，达到各自门禁后再逐个 Enforce
- [ ] 关键财务数字建立 claim → evidence → source field 确定性链路
- [ ] 完成真实双进程接管、provider/Redis/MySQL 故障、容量与 SLO 验证
- [ ] 每月或重大事故/Eval 后审查规则，支持 SHADOW/ENFORCED/RETIRED 收敛

## P1：运行与身份验收

- [ ] 完成真实 UI reconnect、Redis-stop inline fallback 和双实例四步验收
- [ ] 完成浏览器重启后的 Session 保持与更广的多实例身份隔离验收

## 变更约束

- 一次只落一个可回退切片，不同时重写 Coordinator、Eval、Skill 和 Memory
- Agent、RAG 或 prompt 行为变化必须附对应 regression/eval 证据
- 先运行相关 targeted tests，再运行 `.\init.ps1 -Mode fast`
- 涉及 Milvus、MySQL 或 Redis 的切片需补相应集成测试；未运行 live acceptance 时必须明确记录
- 会话结束前更新 `progress.md`；只有功能状态实际变化时才更新 `feature_list.json`
