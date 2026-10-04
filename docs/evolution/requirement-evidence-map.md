# 自进化需求与证据落点

本表按[原始计划](implementation-plan-v1.0.md)的条目定位实现、检查和仍需取得的证据，不代替验收结论。命令结果、审计覆盖进度和阻塞只维护在 [progress.md](../../progress.md)。代码或测试文件存在不等于该项已通过；测试数据不能证明真实收益、部署权限或隔离环境已经成立。

后端路径以下述包根为起点：[实现](../../stocksage-backend/src/main/java/com/stocksage/)、[测试](../../stocksage-backend/src/test/java/com/stocksage/)。Python 文件位于 [evals](../../evals/)。未列出的计划条目仍须审计，不因本表覆盖了基础组件而视为完成。

## E00：基线

| 计划条目 | 权威落点与检查 | 证据边界 |
|---|---|---|
| E00.1 工作区、HEAD、约束 | [baseline.md](baseline.md)、根目录 `CLAUDE.md`；读取 `git status --short` / `git rev-parse HEAD` | 脏工作区必须保留；Git SHA 本身不能标识未提交源码，构建另由 `evolution_build.py` 绑定。 |
| E00.2 普通与 DEEP 调用链 | 基线调用链；`conversation.ChatService` → `service.ToolPrefetchService` → `agent.FundamentalsAgent` → `conversation.ChatPromptAssembler` → `agent.Coordinator`；DEEP 为 `research.DeepEvidenceCollector` / `ResearchDebateService` | 普通分析师演化不能据此归为 DEEP 改进。 |
| E00.3 非敏感实际配置 | `agent.AgentRuntimeConfiguration`、`evolution.FundamentalsRuntimeIdentity`、`config.RuntimeArtifactIdentity`；回放结果的 `comparisonIdentity` 与 `runContext` | 配置默认值不等于供应商实测值；缺实际响应、用量或构建清单时保留未知。 |
| E00.4 既有验证与真实基线 | `progress.md` 引用的 fast baseline、修复后后端日志和普通 HTTP runner 产物 | 初始失败、后续修复和真实 runner 的 BLOCKED 分开；不以旧测试数证明当前整库通过。 |
| E00.5 交付与回滚 | [baseline.md](baseline.md)列出接入点、已有环境限制和证据位置 | 本步无运行行为变更；后续 HEAD/工作区变化需重新核对。 |

## E01：范围与权限

| 计划条目 | 权威落点与检查 | 证据边界 |
|---|---|---|
| E01.1 唯一可变字段 | [V1 契约](evolution-v1-contract.md)、`evolution_candidates.compile_candidate/validate_record`；`test_evolution_candidates` 的目标与未知字段拒绝检查 | 只接受完整 `fundamentals.method`，不接受整套系统配置。 |
| E01.2 三类材料 | V1 契约；候选来源、方法经验独立制品；`evolution_reflection.py` 的独立开发反馈绑定 | 经验不是财务事实；用户反馈未经核验不能当作 gold。 |
| E01.3 字段、范围、父版本、哈希 | `evolution_candidates.py`、`evolution.AgentPolicyBundle`；`test_evolution_candidates`、`AgentPolicyBundleTest` | 正则只拦截明确的越权语法，不能证明任意自然语言都安全；仍需独立评审。 |
| E01.4 优化器/执行器/评测者/发布者 | [离线工作流](../../evals/evolution/README.md)；`evolution_acceptance.require_gate`、发布者签名加载、profile 和管理令牌 | 进程/文件权限、保留集不挂载、凭据分离必须由实际部署证明，不能以不同角色字符串替代。 |
| E01.5 状态和退出 | `evolution_experiment.py` / `run_evolution_experiment.py` 的持久状态及 CLI；`evolution_acceptance.py` 的比较结果 | 完成执行不等于 IMPROVED；全状态转换的审计字段还须随 E08—E17 逐项核对。 |
| E01 验收、交付、回滚 | `test_evolution_candidates` 的模型/工具/路径/发布越权负例；`FundamentalsMethodRegistry` 默认基线与独立激活 | 恶意候选被静态拒绝的覆盖范围与真实模型注入防护分开。关闭实验不改变基线。 |

## E02：提示词迁移

| 计划条目 | 权威落点与检查 | 证据边界 |
|---|---|---|
| E02.1 逐字迁移 | [提示词资源](../../stocksage-backend/src/main/resources/prompts/)、`agent.FundamentalsPrompts`；`FundamentalsPromptsTest` 内原始 system/user 快照 | 比较真实 ChatClient 传给模型的消息，不比较模型生成答案是否逐字相同。 |
| E02.2 组合、编码、角色 | `FundamentalsPrompts.system/task/read`；原始消息顺序与候选方法不二次格式化检查 | 资源 CRLF 规范化为原 Java 文本块的 LF；方法正文中的格式字符原样保留。 |
| E02.3 基线包与快照 | `AgentPolicyBundle.baseline`、`FundamentalsPromptsTest.actualClientSendsTheOriginalOrderedMessages` | 固定 system/user 顺序、文本和客户端选项；供应商边界使用受控模型。 |
| E02.4 空值/中文/换行/长输入/坏资源 | 同一快照参数化检查；`resourceLoadingPreservesTextBlockNewlinesAndRejectsBrokenResources`；`EvolutionReplayService` 的输入预算检查 | UTF-8 无效、资源缺失、空资源拒绝加载；不能声称失败进程自身能继续提供旧服务。 |
| E02.5 回归、交付、回滚 | `FundamentalsPromptsTest`、`EvolutionReplayServiceTest`、`conversation.OrdinaryEvolutionDrillTest`；受控普通链路导出的 Trace | 真实模型普通回归、其他路线语义非退化需独立验收。资源回退不涉及数据库迁移。 |

## E03：版本、运行身份与缓存

| 计划条目 | 权威落点与检查 | 证据边界 |
|---|---|---|
| E03.1 不可变 bundle 与内容哈希 | `AgentPolicyBundle`、`FundamentalsMethodRegistry`；`AgentPolicyBundleTest` 和回放方法清单加载检查 | 哈希不包含批准状态；同一启动清单禁止覆盖基线或重复 ID。发布跨制品身份随 E13 核对。 |
| E03.2 请求固定版本 | `ToolPrefetchService` 先选包再执行；`FundamentalsAgent.analyzeObserved` 返回实际包和输入；普通 Trace 的方法选择与调用身份 | `MethodActivationTest.concurrentAccountsKeepTheirOwnMethodWhenWithdrawalOccursDuringBothCalls` 覆盖并发账号及调用中撤回；账号会话真实性不由该单测证明。 |
| E03.3 内容身份与执行身份 | `EvolutionReplayService` 的注册 runs、`runContext`、`comparisonIdentity`；`evolution_experiment.py` 账本；`test_evolution_dataset` 的独立 run plan | 每次 repeat 有不同 runId；V1 无实验上下文时不能冒充 V2 的签名验收输入。 |
| E03.4 不复用答案作重复样本 | `EvolutionReplayService.consumedRuns`、`evolution_experiment.py` 的提交/恢复记录；`OrdinaryEvolutionDrillTest` 的四次独立分析与最终调用 | 恢复已有响应保留原 runId，不是新样本；远端可能已计费，不能保证 exactly-once。 |
| E03.5 DEEP 缓存边界 | 基线调用链与普通 `ToolPrefetchService` 接入；演化不改变 DEEP 报告缓存/checkpoint 契约 | DEEP 后续演化另立切片；普通请求测试不能证明 DEEP 质量提升。 |
| E03 验收、交付、回滚 | `AgentPolicyBundleTest`、`MethodActivationTest`、普通链路演练导出；未知 ID 拒绝、已固定包继续、新请求回基线 | 并发与文件标记检查属于离线机制；跨进程撤回依赖保留标记/撤回配置。 |

## E04：快照与标签

| 计划条目 | 权威落点与检查 | 证据边界 |
|---|---|---|
| E04.1 smoke 与研究集 | [synthetic-smoke](../../evals/evolution/synthetic-smoke/)、`test_evolution_dataset.test_fixture_coverage_does_not_claim_quality` | 12 个合成样本只验机制；计划中的 120 是建议，真实样本量必须由冻结采样计划和方差支持。 |
| E04.2 分组防泄漏与挑战集 | `evolution_dataset.validate_cases`、跨 split 冲突检查；E07 的独立 `samplingPlan` 证明 | 自动检查泄漏组、发行人/报告家族、源地址、原文及可见正文哈希；不能自动发现漏标的翻译/近重复。真实挑战集和人工分组仍需审查。 |
| E04.3 正文、范围、时点与来源 | `import_evolution_snapshots.py`、`evolution_dataset.validate_case/execution_payload`；`test_evolution_real` 的实际临时文件读取及时间检查 | 原始 source manifest 保存 Unicode 范围并绑定哈希；原始及可见正文都保留。文件导入无网络访问。 |
| E04.4 gold 分离 | `evolution_dataset.validate_gold/execution_payload`；`EvolutionReplayServiceTest.authorizedSourceContractPreservesMetadataButNeverImportsRawOrGold` | 输出字段白名单只能证明序列化隔离；进程无法读取 gold/holdout 需部署权限证据。 |
| E04.5 特殊值与负例 | smoke 场景清单；`test_evolution_dataset` 数值/单位/缺值；`test_evolution_real` 两阶段源绑定评审负例 | 恶意文档被传输不等于模型成功抵抗指令；确定性核验与语义评审分别验收。 |
| E04.6 授权、脱敏与审查 | 源 provenance、`origin`、gold reviewer；E07 独立签名绑定源和标签 | V1 通用经验仅来自公共/合成授权材料。声明 `PUBLIC_AUTHORIZED` 本身不授予数据使用权。 |
| E04 交付、验收、回滚 | JSONL、独立 gold、执行清单、source manifest；导入器新建输出、哈希绑定 | 独立人工复核、真实分组与数据许可属于真实验收。错误数据换版本，不修改旧实验来维持通过。 |

## E05：隔离回放

| 计划条目 | 权威落点与检查 | 证据边界 |
|---|---|---|
| E05.1 profile、管理权限、注册 ID | `EvolutionReplayController` / `EvolutionReplayService`；`EvolutionReplayControllerTest.actualWebAndSecurityConfigurationProtectTheProfileOnlyRoute` | 使用实际 Spring Security、WebConfig 和拦截器，检查非评测 profile、无效令牌、关闭/未配置及未知请求字段；服务与账号存储为测试替身。 |
| E05.2 真实分析与固定最终组件 | `EvolutionReplayService` → `FundamentalsAgent` → `OrdinaryAnswerReplayService`；回放测试和 `OrdinaryEvolutionDrillTest` | 组件级冻结证据回放与普通请求完整链路分别检查；供应商仍需实际调用。 |
| E05.3 禁止重新取证与非允许出网 | 回放只依赖登记快照、无工具分析客户端及固定最终回答；独立 `modelOnlyEgress` 操作证明 | Java 调用链不取证不等于整个 Spring 应用有网络沙箱；实际出口规则仍须验收。 |
| E05.4 无业务写入与独立存储 | 回放类不持有会话、报告或记忆写入依赖；工作流要求专用实例/目录/存储；E07 `isolation` 和影子独立证明 | 路径校验不是 ACL，依赖检查不是数据库计数不变实测；后台任务、缓存与租户隔离不可由 mock 宣告通过。 |
| E05.5 实际输入输出、用量、终态 | `EvolutionReplayService.StageResult/Result`；错误/限时/容量/重复 run 检查；`FundamentalsAgent` 记录 provider 或未知用量 | 无响应必须失败；不把 SDK 补零或缺失 token 当真实用量；不索取隐藏思维链。 |
| E05 交付、验收、回滚 | 回放服务和受保护入口；profile 检查、真实文件契约检查、普通链路 Trace 导出 | 关闭评测 profile 移除入口。真实供应商断开、生产数据计数及网络/文件权限证据仍需在专用环境取得。 |

## E06：独立质量评估

| 计划条目 | 权威落点与检查 | 证据边界 |
|---|---|---|
| E06.1 执行契约与硬门禁 | `evolution_quality.input_errors`、两阶段实际上下文适配、`HARD_GATES`；`test_evolution_quality` / `test_evolution_real` 的篡改与失败检查 | 哈希和字段检查只能确认契约；权限、租户、证券身份等语义/环境门禁还需独立事实证据，不能只签一个 PASS。 |
| E06.2 数值、口径、公式 | `evolution_dataset.validate_facts/derived_value/validate_real_expectations`；`test_evolution_dataset` 与 `test_evolution_real` | gold 冻结单位、币种、期间、舍入与容差；零值、负值、缺值分开；不能用隐藏原文要求回答。 |
| E06.3 主张支持 | `evolution_quality` 的源/gold/回答绑定、独立主张提取和 `claim_checks`；`test_evolution_real` 的支持、摘录、错值检查 | 引用 ID 合法不是支持率；人工评审必须核对实际可见证据，程序不自称能判定任意金融语义。 |
| E06.4 rubric 与人工裁决 | `evolution_rubric.DIMENSIONS`、源绑定 requiredPoints/response_kind；`evolution_review.py` 的盲评和争议裁决 | 固定四维评分与事实/覆盖/拒答指标分开；模型自评、偏好票或点赞不能独自授予发布资格。 |
| E06.5 负向控制 | `test_evolution_real` 的错币种/期间/公式、缺事实、空答案、非法引用、不当拒答、评分指令负例；E07 `negativeControls` 原始制品绑定 | 本地标签检查与真实模型在恶意文档下的输出验收分开。 |
| E06.6 失败归因 | `evolution_candidates.ATTRIBUTIONS`、`evolution_reflection.TARGETS`；`test_evolution_reflection` 的非方法缺陷分流 | 供应商、执行器、能力、数据与评估故障不能改名为方法经验；失败记录仍保留成本。 |
| E06 交付、验收、回滚 | 评估器、独立标签契约与测试；`evolution_acceptance.evaluator_hash/require_gate` | 没有标签保持 NO_DATA；评估相关代码变化使旧门禁失效，需重新独立验收，不能补填新哈希沿用旧批准。 |

## E07：真实基线与冻结政策

| 计划条目 | 权威落点与检查 | 证据边界 |
|---|---|---|
| E07.1 smoke、开发/验证基线与 repeats | `freeze_baseline`、`validate_policy`；`test_evolution_acceptance` 的完整基线、构建及运行身份检查 | `UNIT_TEST` 只能签出 MECHANISM_READY，不能授权实际优化；真实基线和每例重复次数须由实际报告证明。 |
| E07.2 先切分及基线缺陷 | 冻结 policy cohorts、source/gold hashes；独立 `samplingPlan`、`reviewCalibration` 和 findings 制品 | 程序绑定原始证明，不自动证明采样确实早于观察；独立方须检查原始时间、分组与评审分歧。 |
| E07.3 主指标、非退化、样本与预算 | `validate_policy` / `require_gate`、`validate_comparison_gate`；冻结策略篡改负例 | rationale 需基于业务目标和基线波动。验证/保留关键分组必填；关键事实、覆盖与拒答的退化幅度为零。 |
| E07.4 角色用量及未知费用 | `provider_usage`、`ExperimentLedger`、`seal_resource_audit/compare_resources`；`test_evolution_experiment` / `test_evolution_acceptance` | 四个角色共用账本；缺用量/价格不填零，不把按冻结费率计算的估计称为真实账单。 |
| E07.5 有界候选、轮次及总开销 | policy `limits`；`validate_plan` 将搜索上限限制在门禁内；SQLite 提交前预留 | 文档中的 4/2/1000 为建议，运行必须使用审定 manifest 的具体值；回调不能原地改变冻结字典。 |
| E07 交付、验收、回滚 | 源/gold/报告/证明绑定的独立签名门禁；缺样本/标签/负向控制拒绝检查 | 完整真实基线未取得时不可声明完成 E07。更改实验预算/条件需新实验，不修改已产生结果的政策。 |

## E08：方法经验库

| 计划条目 | 权威落点与检查 | 证据边界 |
|---|---|---|
| E08.1 独立契约与加载 | `evolution_candidates` 的 V2 RESEARCH_EXPERIENCE；`evolution_experiences.load_registry/load_approved` | 不复用公司报告记忆或其冲突键。签名注册表与方法正文内容身份分开。 |
| E08.2 版本文件与实验存储 | `empty_registry/_next`、新签名 JSON 快照和 parentRegistrySha256；`save_immutable/write_once` | 不需要向量库或新消息队列；Git/制品仓权限仍由实际环境控制。 |
| E08.3 方法、条件、来源、反例、验证 | V2 experience 字段；`register` 复核开发轨迹与独立反馈；生命周期 `transition` 绑定评价候选 | 版本、父经验、禁止推断、sourceRunIds、外部反馈和验证证据可追溯；存在声明不等于方法有效。 |
| E08.4 规则匹配及基线选择 | `experience_matches/select_approved`；`test_evolution_experiences` 的无匹配、冲突及撤回父版本 | 多个适用方法不交给模型任挑；无唯一已批准匹配返回有原因的基线。运行时只使用共同验过的完整 bundle。 |
| E08.5 长度与命名空间 | `validate_method` 的 Java UTF-16 长度与 Evidence ID 拒绝；`AgentPolicyBundle.MAX_METHOD_CHARS` | 方法字符上限独立于证据预算；经验记录/版本哈希不充当本轮业务引用。 |
| E08.6 公共/合成授权范围 | `_sources`、`register` 与来源审核；开发反馈只允许本轮开发 cohort | 私有用户经验捕获/删除/共享仍属单独切片；不能把已有报告自动提炼成全局方法。 |
| E08 交付、验收、回滚 | `test_evolution_experiences` 生命周期、匹配、冲突、去重、加载失败检查；append-only 事件及新注册表版本 | 经验撤回不修改旧正文；生成新包引用。经验状态事件具备 actor/时间/from/to/原因/证据，不代表其他实验或发布状态也自动具备。 |

## E09：独立反馈与候选经验

| 计划条目 | 权威落点与检查 | 证据边界 |
|---|---|---|
| E09.1 真实输入、失败及独立反馈 | `development_trace/feedback_template/sign_feedback/verify_feedback`；固定开发 cohort、运行身份、原始正文哈希 | 拒绝验证/保留集轨迹和改动后的摘录；模型自述不是外部反馈。 |
| E09.2 归因与缺陷分流 | `reflect`、`TARGETS`；`test_evolution_reflection` 的缺数据/能力/执行/供应商/评估故障路径 | 非方法问题进入明确队列；不生成虚构的 prompt 修复。输出队列记录不等于外部缺陷已经修好。 |
| E09.3 具体、有条件的规则 | 固定 reflection system contract、严格 LESSON_FIELDS、`validate_method` | 正则/Schema 无法证明自然语言“足够具体”，仍由独立静态、适用性及效果评审判定。 |
| E09.4 支持案例、边界与父版本 | `reflect/build_experience` 的 sourceRunIds、反馈哈希、counterexamples、parents/version | 一条支持案例不能证明泛化；已撤回或冲突的父经验不能编入新候选。 |
| E09.5 结构、去重、注入审查 | `compile_candidate` / `register` / `transition(VALIDATED)`；新增经验固定 PROPOSED | 静态拒绝与独立审核分开；内容去重保留来源，不能重复计为多个有效经验。 |
| E09 交付、验收、回滚 | 反思 CLI 保存提案或缺陷结果；`test_evolution_reflection`；经验可 REJECTED | 本地失败不能触碰线上注册表或 Research Memory；真实提案是否有效仍需真实评估。 |

## E10：单组件生成

| 计划条目 | 权威落点与检查 | 证据边界 |
|---|---|---|
| E10.1 唯一 changes 字段 | `evolution_generator.proposal` → `compile_registered/compile_candidate` | JSON 必须包含唯一允许方法变更及来源说明；未知字段、工具调用和截断输出在执行前拒绝。 |
| E10.2 单次差异与父候选 | 候选的 parentBundleId/parentMethodSha256/sourceExplanation；`validate_method` 禁止来源公司及财务答案字面量 | 同一搜索各轮以固定基线为父版本，保留差异；静态文本过滤不能取代硬编码答案的人工审核。 |
| E10.3 有界流程与替换点 | `reflect`、`run_search`、注入 `invoke` 及固定 HTTP adapter | V1 采用现有 stdlib 适配，不依赖 GEPA；替换搜索器仍需遵守相同回放与预算接口。 |
| E10.4 GEPA 条件项 | 原计划“选择 GEPA 时”才要求锁定其依赖；实际生成器为 `urllib.request` | 未选择该可选依赖，不新增无用锁文件或另写 Python 分析 Agent；质量执行仍在 Java。 |
| E10.5 执行前静态拒绝 | `test_evolution_candidates`、`test_evolution_search`；字段、边界、长度、脚本/路径、工具/模型/门禁越权检查 | 合法本地候选证明机制可用；真实生成器候选必须先通过 E07 且不持有发布凭据。 |
| E10 交付、验收、回滚 | 生成器、差异记录、候选池、固定适配接口；`--stop-file` 停止后续生成 | HTTP adapter 无自动重试、不跟随重定向；暂停生成不改变已批准在线版本。 |

## E11：预算、恢复与搜索

| 计划条目 | 权威落点与检查 | 证据边界 |
|---|---|---|
| E11.1 冻结批次及失败成本 | `validate_plan/stage_plan`；开发 cohort 固定，所有角色共用 `ExperimentLedger` | 不在看到结果后重抽样，不丢弃失败/无响应成本；完整逐例报告仍在独立比较制品。 |
| E11.2 淘汰与质量/成本门禁 | `reviewed_decision` 复用 `verified_comparison`；`test_evolution_search` 的签名、指标、排除样本篡改拒绝 | 完整比较报告绑定后才推进；只保留签名结论、另改指标也必须拒绝。 |
| E11.3 有限验证候选与重复 | `validationLimit`、固定 repeats/runId、开发通过后生成完整 validation 执行清单 | 每轮最多一候选，控制器提名不等于独立最终选优；重复不可复用答案作新样本。 |
| E11.4 停止条件 | `roundLimit/noImprovementLimit/validationLimit`、预算预留、`stop_requested`；`test_evolution_search` | 停止保留已有制品及账本；只在原冻结上限内恢复，不能自动换模型/增预算。 |
| E11.5 持久恢复与 UNKNOWN | SQLite `BEGIN IMMEDIATE`、action/request 绑定、提交前持久化、完成响应恢复；`test_evolution_experiment` 的中断/并发/未知调用与持久事件检查 | 模型 action 事件与状态在同一事务写入，带进程账号、时间、前后状态、原因和原始证明；恢复不追加执行。SUBMITTED/UNKNOWN 不自动重试，外部推理无 exactly-once 承诺。 |
| E11.6 谱系、结果与淘汰原因 | immutable search-contract、round candidate/bundles、comparison/execution、outcome 和内容寻址 report；账本 `searchEvents`、`search_history_proofs` 与 `test_evolution_search` | 等待、停止、失败及提名均有事件和证明；已消费的候选里程碑不能用另一签名结果替换。完成后恢复原结论不重新生成；同一账本不能换 manifest 重置轮次。提名仍不等于保留集选定或发布。 |
| E11 交付、验收、回滚 | 共享账本和内容寻址搜索报告；`test_evolution_search/test_evolution_experiment` | 单元样例不是“首轮真实搜索报告”。停止实验保留成本与来源，不能选择性擦除历史。 |

## E12：独立选优与最终保留集

| 计划条目 | 权威落点与检查 | 证据边界 |
|---|---|---|
| E12.1 验证选一候选 | `select_candidate` 复核完整签名报告并生成 CANDIDATE_SELECTION；`holdout_reservation` 固定候选/计划 | 提名非发布；保留集所有权按源/组持久记录，不按可随意更换的 experimentId 放行。 |
| E12.2 配对固定输入与交替执行 | `evolution_compare.pair_errors`、`run_evolution_replay` 配对循环、独立用量顺序核对 | 源证据、历史、记忆、模型、固定最终规则和构建身份一致；实际模型别名可变仍是复现限制。 |
| E12.3 盲评、平局、争议 | `blind_material_hash/apply_blind_reviews`、`evolution_review.adjudication_template/finalize_reviews`；`test_evolution_review` | 原始意见和未评案例保留；独立评审身份/权限还须操作证明，多个账号名不自动等于独立。 |
| E12.4 组件与最终回答分开 | `decide_comparison.stage_decisions` 与整体 `status`；组件改善但最终未改善的负例 | 组件主指标、最终主指标和含硬门禁的总结果不同，不能互相替代。 |
| E12.5 硬门禁、覆盖、事实、拒答、资源 | `decide_comparison` 的 failures/missing、关键事实错误计数、GUARD_METRICS；独立资源审计 | 主指标提高不能抵消硬失败、漏事实、过度拒答或未知成本；候选方法失败仍计入分母。 |
| E12.6 分组不确定性 | `grouped_summary`；发行人/报告家族组重采样、固定随机种子、minimumGroups/confidenceLevel | repetitions 不增加独立家族数；分组不足、区间不支持改善即 INCONCLUSIVE。 |
| E12.7 保留集不回流 | 搜索只接收开发/验证 sources，生成器只接收开发决策；持久 holdout source/group 预占 | 物理挂载和数据权限另行实测；分析旧保留集用于下一轮时必须准备新独立集合。 |
| E12.8 发布证据绑定 | `decide_comparison` 的 RELEASE_EVIDENCE、`comparison_report_hash/verified_comparison` | 方法、政策、数据、评估器、资源、评审及整份结果签名绑定；合格 HOLDOUT 只到 PENDING_HUMAN_APPROVAL。 |
| E12 交付、验收、回滚 | 比较/盲评/裁决/资源/发布证据；`test_evolution_acceptance/test_evolution_compare/test_evolution_review` | IMPROVED、NO_IMPROVEMENT、INCONCLUSIVE 都是合法结果；真实结果缺失不能制造结案。评估器改变必须重新验收。 |

## E13：批准包

| 计划条目 | 权威落点与检查 | 证据边界 |
|---|---|---|
| E13.1 已验内容编包 | `evolution_release.prepare_package` 复核候选、经验、E07、HOLDOUT 与构建；`test_evolution_release` | 封装原方法正文，不重新组合未共同验过的经验；scope 由已验经验字段产生。 |
| E13.2 独立审批证据 | `approve_package`、独立 publisher envelope 与 package/approval hashes；`ApprovedMethodArtifactTest` | 生成器提案、评估结论和发布者批准是不同制品；内容变化使签名或固定哈希失效。 |
| E13.3 人工检查及构建权限 | 固定 scope/negativeCases/cost/privacy/dependencies/rollback 六项原始证明；发布者独立密钥 | 审批检查原始证明而非只收一个 PASS。真实构建权限、独立性和受保护流程需部署证据。 |
| E13.4 启动固定加载 | `ApprovedMethodArtifact.load`、`RuntimeArtifactIdentity`、`FundamentalsMethodRegistry`；签名/构建/篡改/撤回检查 | 只信任发布者签名、固定制品哈希及实际 JAR 身份；新包/新授权需重启加载，普通 HTTP 不能登记候选。 |
| E13.5 待验证状态与非适用回退 | 批准包 `APPROVED_PENDING_VALIDATION`，`MethodActivation` 单独授权；`MethodActivationTest` | 批准不等于 ACTIVE；范围外请求用 baseline，进入 SERVING 必须已通过影子与回滚验收。 |
| E13 交付、验收、回滚 | 包、审批、构建、scope、rollback 字段及[发布说明](../../evals/evolution/README.md) | 真实包审批、构建和部署尚须独立证明；拒绝部署或撤回不能原地修改旧包。 |

## E14：受控影子批次

| 计划条目 | 权威落点与检查 | 证据边界 |
|---|---|---|
| E14.1 内部账号与同证据 | `evolution_shadow.prepare_plan/authorize` 及 `verify_authorization`；执行文件/源 hash 固定、独立账号 allowlist | 采用计划允许的离线批次，不在在线请求里重新取证。正式 baseline 答案交付仍需 baselineDelivery 原始操作证明。 |
| E14.2 只写隔离存储 | `EvolutionReplayService` 的 SHADOW mode、`outputScope`、独立 restrictedStore | 原始输出不进入正式会话/报告/记忆；整个应用后台任务的无污染证明不能只靠组件依赖检查。 |
| E14.3 有界执行与停止 | 冻结影子计划、共享预算、专用 replay executor、容量拒绝及 STOP_SHADOW/CANCEL_SHADOW | 离线实例故障不应阻塞在线主链；实际资源隔离、出口及超载行为另由环境实测。 |
| E14.4 上下文/最终回答/成本/条件 | 完整双阶段比较、批准 runtime identity 及 `accept_shadow`；`test_evolution_shadow` | 不能把影子执行成功当作质量通过；上下文、模型、固定最终规则或构建漂移都使旧条件不成立。 |
| E14.5 内容授权与保留 | 授权 endpoint、restrictedStore、retentionUntil 及独立操作证明；过期拒绝检查 | 路径存在不是 ACL；实际快照、账本副本的清理由保留策略执行，不能只删某个输出文件。 |
| E14 交付、验收、回滚 | 原始影子运行、独立 acceptance、无污染/资源/交付证明；停止入口及保留历史 | 本地虚构签名和模型结果不能替代真实影子验收。批准影子也不授权线上激活。 |

## E15：回滚演练

| 计划条目 | 权威落点与检查 | 证据边界 |
|---|---|---|
| E15.1 预发布旧/新包与缓存 | `OrdinaryEvolutionDrillTest`、`check_evolution_drill_exports.py`、共享 `verify_drill_runs` | 四个真实普通代码路径请求各有新分析及最终调用；模型和存储是本地替身，不能称为预发布现场证明。 |
| E15.2 故障注入 | 包/哈希/配置/加载器 Java 负例；`ROLLBACK_CHECKS` 要求对应原始证明 | 当前代码分支测试与真实预发布的包损坏、评估失败、加载不可用实测分开。 |
| E15.3 新请求撤回、进行中固定 | `FundamentalsMethodRegistry` 控制文件与锁定撤回；并发 activation 测试和普通演练 | 正常撤回不改进行中版本；CANCEL_SHADOW 在安全边界取消影子。重启需保留标记/撤回配置。 |
| E15.4 独立开关 | 生成 `--stop-file`、STOP_SHADOW/CANCEL_SHADOW、PROHIBIT_ACTIVATION、STABLE_ONLY/REVOKE；回滚说明和独立测试 | 停生成、停影子、禁激活、回基线有不同作用域；不能用一个开关名代替实测。 |
| E15.5 保留版本与历史 | 不可变包、账本、原始 Trace/proofs；withdraw 只写标记 | 不删表、不擦历史、不改旧报告；撤回后不能因重试恢复候选。 |
| E15.6 完整切换/撤回序列 | DRILL 授权、四阶段原始 runId/Trace、固定案例与撤回跨越时间检查；独立 `accept_drill` | 离线导出只标机制已验证；正式验收还要求不同评审者及所有故障/操作证明。 |
| E15 交付、验收、回滚 | [回滚手册](../../evals/evolution/README.md#rollback-controls-and-recorded-drill)、演练导出、独立签名恢复验收 | 演练最终回到 baseline；没有接受的回滚证明不能签 SERVING。离线检查器不签发发布许可。 |

## E16：受限激活与观测

| 计划条目 | 权威落点与检查 | 证据边界 |
|---|---|---|
| E16.1 小范围独立授权 | `authorize_serving`、`MethodActivation.load`、账号范围与有效期；`test_evolution_deployment` | 账号是影子/回滚已验集合的子集，不支持通配账号；授权记录需独立发布者签名。 |
| E16.2 条件复核 | `FundamentalsRuntimeIdentity`、批准构建/模型/固定提示词/记忆哈希；注册表 drift 检查 | 模型/固定规则/构建改变会锁定撤回；某请求记忆不匹配则该请求回退，不扩大适用范围。 |
| E16.3 服务器选择并固定 | `ChatController` 身份、`ChatService` 实际配置/记忆、`OrdinaryEvidence` 资格标签、预取前选包 | 普通请求不接受候选 ID/方法；本地 scope 单测与真实登录/租户隔离必须分开。 |
| E16.4 质量、拒答、错误、延迟、用量、版本 | `evolution_observation.observe`、普通导出及绑定质量/操作评审；`test_evolution_observation` | 固定批次保留失败、取消和缺失；分析与最终用量分开，只认 provider 原始用量，P95 保留样本数。 |
| E16.5 硬失败撤回及不确定性 | STOP_EXPANSION_AND_WITHDRAW / HOLD_SCOPE_UNCERTAIN / MAINTAIN_REVIEWED_SCOPE；显式 `--withdraw-on-failure` 写控制文件 | 采用操作员调用的批次流程；硬失败需执行回滚，不把仅生成评估报告称为已撤回。没有自动持续监控。 |
| E16.6 扩量单独批准 | 每个 serving activation 独立账号/有效期/检查证明；输出不授予扩量 | 搜索提名、点赞、观测成功均不自动扩大范围。 |
| E16 交付、验收、回滚 | 实际 Trace 方法归属、签名授权与观测/撤回记录；普通链路及 scope 检查 | 真正上线、跨租户负例、质量样本和退回旧包效果须在授权环境取得；签名记录本身不证明已经部署。 |

## E17：闭环记录

| 计划条目 | 权威落点与检查 | 证据边界 |
|---|---|---|
| E17.1 完整来源与迭代索引 | `evolution_iteration.load_inputs/assemble`，候选/经验/开发反馈、原始比较与发布制品绑定 | search 为可选原始制品索引，不能代替独立比较；谱系完整性还需原始 lineage 检查证明。 |
| E17.2 分开工程、组件、最终回答 | 迭代 Markdown、artifact-index 的 engineering、stage metrics 和整体比较状态 | 没有证据明确 UNVERIFIED；旧评估器的机制导出不能重新标成当前评估器已验。 |
| E17.3 下一轮有界手动触发 | manifest `nextBatch.trigger=MANUAL`；搜索门禁/账本及保留集消费记录 | 不由每条在线请求无限触发，不自动复用已消费保留集。 |
| E17.4 失败分类与去向 | 复用 `attribute_failure`；unresolved 的类别、描述与已索引证据 refs | 分流记录不等于缺陷已修复；非方法问题进入相应开发流程。 |
| E17.5 状态文件与完成声明 | 根目录 `progress.md` 和 `feature_list.json`；迭代报告不更新运行权限 | 只按验收范围标状态；不能借 FUNDAMENTALS 方法组件宣布其他路线改善。 |
| E17 交付、结案与回滚 | iteration Markdown、制品/证明索引、unresolved、下一轮范围；`test_evolution_iteration` | OPEN 不能称结案。三种 CLOSED 状态要求独立结果及 lineage/engineeringLoop/runtimeEndState 原始证明；RELEASED 还需完整部署链和实际候选观测。 |

## 全局边界与验收契约

以下补充计划第 2、3、14—18 节的跨步骤要求。编号步骤中的具体落点仍以上表为准；本节不把代码检查或签名记录提升为现场验收。

| 计划条目 | 权威落点与检查 | 证据边界 |
|---|---|---|
| §2.1 在线/离线分离，方法不能成为财务证据 | `evolution_candidates.validate_record`、固定 Fundamentals 提示词、回放的可见证据与 gold 绑定；`test_evolution_candidates`、`test_evolution_real` | 仅方法文本可变；示例注入、事实来源是否正确仍须独立质量评审。默认无 Research Memory，使用时两组绑定同一冻结哈希。 |
| §2.2.1 权限不进化 | 候选严格字段/单目标校验；`AgentPolicyBundle` 固定契约及 requiredCapabilities；既有 Gateway/CapabilityPolicy | 候选不能提交新工具、网络权限、模型或预算。实际优化器进程的文件/网络权限由部署隔离证明。 |
| §2.2.2 裁判不进化 | `require_gate`、`evaluator_hash`、冻结策略/源/gold 哈希；完整 `verified_comparison` 签名校验 | 评估器变化须重新接受 E07；版本哈希不是优化器不能写评估器目录的操作系统权限证明。 |
| §2.2.3 证据不进化 | 原始源字节与 Unicode 可见范围、case/evidence/prompt 哈希；`test_evolution_dataset/real/quality` | 保存原始与实际输入，不允许靠改标的、日期、币种或原文提高分数；来源真实性另经人工核对。 |
| §2.2.4 单组件 | `compile_candidate` 仅 `fundamentals.method`；固定其余角色及实际配置身份 | 组件变化与完整最终输入变化区分；最终输入中的分析稿允许不同，固定规则/原始证据不允许漂移。 |
| §2.2.5 任务固定包 | 预取前 `selectForRequest` → 不可变 Selection → 分析师调用；`MethodActivationTest` 并发/中途撤回、`OrdinaryEvolutionDrillTest` | 不读取 latest；新请求撤回与进行中版本固定分别断言。更换启动包需重启，不能据此保证旧请求跨重启存活。 |
| §2.2.6 候选隔离 | `EvolutionReplayController` 已登记 ID 请求，真实 MVC/Security/AdminApiInterceptor 测试；普通 Chat 请求 DTO | profile、管理令牌与服务器账号选包在代码入口生效；真实登录/租户、主机 ACL 和网络出口另测。 |
| §2.2.7 无生产污染 | `EvolutionReplayService` 固定组件链、独立输出/账本/影子存储 | 回放组件无生产写依赖；完整 Spring 后台任务、SQL/Milvus 计数与网络隔离需现场证明。 |
| §2.2.8 无数据不通过 | quality 的 NO_DATA、预算 UNKNOWN、比较 INCONCLUSIVE、迭代 OPEN/UNVERIFIED；对应负例 | 运行完成、退出 0、模型答复存在均不等于改善或批准。 |
| §2.2.9 / §2.3 生成发布分权 | generator adapter 独立凭据；evaluator/publisher 签名与不同密钥检查；发布批准及激活为不同命令 | 角色字符串/本地测试密钥不证明人员独立。保留集、发布凭据和目录权限必须由受保护评测/发布环境掌握。 |
| §3 阶段依赖与交付 | E07 gate → 开发/验证 → 单候选选择/保留集 → package → shadow → drill/rollback → serving；各命令的 predecessor 哈希检查 | 离线代码可以先写，真实运行不可跨过依赖门禁。改动、命令/退出码、原始日志、未跑事项、回滚操作与交接见 progress；共享脏工作区不等于独立提交组已交付。 |
| §14 严格 Schema | Python `exact_fields` 与 Java 不可变 DTO/包校验，未知字段/版本负例 | 建议字段名不要求同名类；实际机器契约以相应实现和 CLI 文档为准。 |
| §14.1 包身份与审批分离 | `AgentPolicyBundle`、`export_eval_bundle`、`write_once`、受信启动加载器；Python/Java 同一哈希向量 | 内容哈希不包含自身或审批；包 ID/内容不能覆盖，运行时不允许用户任意路径。 |
| §14.2 内容与执行身份、缓存 | `EvolutionRunContext`、登记 repeat/run ID、账本 action ID、实际 model config/response/usage；回放与普通演练 | 每个 repeat 是新调用；恢复同一 action 不重复调用。模型别名/未公开版本保持限制，供应商未知用量不填零。 |
| §14.3 经验/实验/候选状态 | registry lifecycle、SQLite `events` / `searchEvents`、不可变阶段 milestone、`action_history_proofs/search_history_proofs` | 每步绑定执行 actor/UTC/from/to/reason/proofs；UNKNOWN 不伪装失败，COMPLETED 不等于 IMPROVED，NOMINATED 不等于独立选中。 |
| §14.3 发布及撤回状态 | 独立签名 approval/shadow/drill/rollback/serving 记录通过 predecessor 哈希关联；运行 Trace 证明实际选包；`operatorAction` 记录控制标记变更 | 记录类型及前序引用确定授权阶段；授权不是实际 ACTIVE。手动部署/控制变更需操作员原始记录；marker 已写不是运行时已 REVOKED，需后续 Trace。 |
| §15.1 原文/可见文/gold/切分 | `execution_input` allowlist、原始文本及可见范围、分组交集检查、独立 source/gold review | 隐藏 gold 不进请求体；最终保留集不挂载优化器是实际环境约束，哈希无法发现未登记语义重复。 |
| §15.2 业务时间与来源版本 | 授权源 importer/validator 分开保留 period、published/available/retrieved、sourceVersion；历史严格 as-of 拒绝未知时点 | 不把报告生成时间变成业务证据时间，不顺带改记忆排序；固定快照不能证明模型预训练没有历史信息。 |
| §15.3 经验冲突与谱系 | 独立版本 registry、trigger/prerequisite/forbidden/counterexample/source/feedback/validation、冲突/撤回父链检查 | 未解决冲突不发布；点赞、自评不替代 gate；运行时只取批准的显式方法包。 |
| §15.4 隐私/删除 | V1 导入仅 SYNTHETIC / PUBLIC_AUTHORIZED；发布 privacy proof、影子 restrictedStore/retentionUntil 与清理要求 | 私人用户材料须另行授权和去标识；保留期禁止继续运行不能替代删除。原始快照、派生可识别内容、索引与账本副本清理及关联撤回需实测并保留无正文操作证据。 |
| §16.1 指标与 Judge 边界 | 独立 hard gates、标注数字/单位/期间/币种/公式、citation support、coverage、拒答/澄清；组件/最终分项 | 无适用项保留 N/A，无评审 NO_DATA；非法 Judge 输出是评估故障，模型判断不能替代原始金融事实。 |
| §16.2 预冻结门禁与统计 | `validate_policy` rationale/阈值/分组/重复/预算/重试策略；按泄漏组 bootstrap；`test_evolution_acceptance/compare` | 重复调用不增加独立公司数；样本不足 INCONCLUSIVE。真实阈值依据基线波动独立冻结，不用虚构测试数值放行。 |
| §16.3 失败与成本 | 候选失败保留分母，基础设施问题保留 excluded 原因及完整资源审计；所有模型角色共享账本 | 配对中断不删花费；当前策略禁止自动重试未知调用，需要重新审查明确运行身份。 |
| §16.4 证据等级 | 迭代 engineering/component/final/comparison 分离；演练 checker 明确 `realAcceptance:false` 与 `activationAuthorized:false` | 静态/受控测试/固定快照真实模型/真实组件链/授权现场各自陈述。串联真实 Java 组件但替换模型/存储的演练不能升级为真实环境验收。 |
| §17.1 文件组织 | 各 E 行中的现有 Java evolution 包、rag-eval Python CLI/契约、docs/evolution 文档 | 路径属于建议，复用现有模块；不提交私有快照、密钥和保留集，不为离线 V1 新建平台。 |
| §17.2 测试矩阵 | prompts/bundle/replay/dataset/quality/experiment/release/ordinary drill 对应测试及本表 E02—E16 | 真实 MVC 过滤器、临时文件/SQLite、真实线程同步覆盖机制；本地模型/存储替身不能证明其他普通路线或 DEEP 的真实语义无退化，完整现场测试另验。 |
| §18.1—18.2 命令/退出码 | [离线工作流](../../evals/evolution/README.md)、实际 `run_evolution_experiment.py`、`run_ordinary_live_eval.py` 与测试 | compile 不等于 tests，运行完整不等于质量通过；实现采用现有 rag-eval 路径。缺专用环境/凭据不填真实通过。 |
| §18.3—18.4 执行与交接 | 一名开发 Agent 可顺序完成；进度记录保存基线 SHA/改动/测试/未跑/下一步/回滚 | 开发子 Agent 不能替代独立评审者。共享目录不等于权限隔离，用户批准实施不等于批准候选上线。 |
