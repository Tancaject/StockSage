# 系统提示词修订与小样本回放

本次交付是静态模板维护：修复明确的指令冲突与职责描述，保留现有模型、工具权限、JSON 字段及执行流程。它不是一次通过正式发布门禁的自动进化，也没有证明整个 Agent 的回答质量已经改善。

| 模块 | 交付的行为约束 |
|---|---|
| [最终回答](../../stocksage-backend/src/main/java/com/stocksage/conversation/ChatPromptAssembler.java) | 先完成用户要求的事实、计算和解释；缺证据时可以不作强弱、估值或经营判断，不再强制补齐同行比较与经营原因。 |
| [基本面方法](../../stocksage-backend/src/main/resources/prompts/fundamentals-method.txt) | 只在本轮证据支持时解释变化原因，否则保留未知。 |
| [新闻角色](../../stocksage-backend/src/main/java/com/stocksage/config/AgentConfig.java)与[任务模板](../../stocksage-backend/src/main/java/com/stocksage/agent/NewsAgent.java) | 分析后端已给证据；区分发布时间与事件时间，不把摘要称为已核验原文，不为固定章节补出影响或原因。 |
| [记忆与查询客户端](../../stocksage-backend/src/main/java/com/stocksage/config/AiConfig.java) | 共享记忆角色覆盖摘要、画像和标题；查询改写保留比较、否定和排除条件，不凭空补实体或期间。 |
| [短期摘要](../../stocksage-backend/src/main/java/com/stocksage/memory/ShortTermMemory.java)与[长期画像](../../stocksage-backend/src/main/java/com/stocksage/memory/LongTermMemory.java) | 摘要保留说话者、更正和不确定性；用户画像只依据明确自述，不把助手建议写成持仓。 |
| [DEEP 补证](../../stocksage-backend/src/main/java/com/stocksage/agent/DeepEvidenceReplanner.java) | ACT/STOP 使用有效 JSON 示例；STOP 的 action/query 是真正的 null，权限与解析契约不变。 |

Market、意图识别、评分、续停、Manager 裁决、关系抽取和入库 gist 已有相应边界，本轮没有做同义改写。Bull/Bear 的论点数量同时受到解析器约束，不能只改提示词放宽；是否调整该契约仍需具体故障证据。

最终回答的五处替换与[前一轮冻结对照](../evolution/common-fundamentals-2025-v1/diagnostics/final-synthesis-20260930/README.md)逐字一致；用当前编译类的实际固定规则核验过。该历史实验只支持有限范围内的规则贡献，不是本轮完整系统验收。方法内容变化会生成新哈希；同名 baseline-v1 不代表与历史 JAR 相同，身份边界见[契约](../../docs/evolution/evolution-v1-contract.md)。

本轮检查使用实际调用者与 ChatClient 捕获消息、模型选项，再通过现有 AiConfig 和 HTTP 配置调用真实供应商。存储、取证、检索和完整服务链没有运行；摘要/画像只检查模型输出，不代替后续持久化或确定性提取验收。首次八例中六例是普通合成对话，两例复用公开财报 DEVELOPMENT 输入，没有使用验证集或保留集。每例每臂仅一次，顺序交替；评审为非盲 AI 核查。

| 首次八例 | 修改前 | 首轮修改后 | 可支持的结论 |
|---|---|---|---|
| 查询改写、摘要、画像、标题、补证 STOP | 各 1/1 | 各 1/1 | 这组样本未见退化，不能据此宣称检索或记忆整体改善。 |
| 仅搜索摘要的新闻 | ISSUES | ISSUES | 核验/时间限制更明确，仍补出了无依据的收入影响或项目归属。 |
| MSFT 流动性 | ISSUES | ISSUES | 必答数值正确，但仍把递延收入的账面处理推成真实偿债压力判断，并补业务原因。 |
| WMT 现金流 | ISSUES | PASS | 新稿的覆盖结论更有边界；旧稿融资与“健康可控”措辞存在解释敏感性，单次差异不足以证明稳定收益。 |

逐条主张、数值复核和宽松解释保存在[辅助模块评审](review-auxiliary.json)与[基本面评审](review-fundamentals.json)。捕获脚本最初误把历史年份等非必答内容写进基本面覆盖条件；评审者在读取这四份回答前，依据实际用户问题记录了[范围校准](criteria-clarification-fundamentals.json)，两臂统一执行，原捕获文件保留。这批结果不能冒充严格预注册的正式验收。

新闻另有一次四调用的定向检验，包含仅摘要与完整公告两种普通合成输入；其中 before 指首轮修改版，after 指额外约束版，不能与首次八例的 before/after 混淆。额外长句仍未可靠消除项目推断，因此交付采用较短的首轮修改版，当前源码及编译资源哈希已核对一致。其[输入、计划与原始回答](news-refinement/)及[评审](news-refinement/review.json)单独保留。

相关工程回归通过 62 项，包含已修正的过期反射测试；新闻额外约束另通过 23 项，最终所选代码重新编译成功。原 fast harness 的前端 102 项、构建及 Python 编译检查通过。两组共 20 次真实调用、39,866 个供应商返回 tokens；金额未定价。实际模型、输入输出哈希、用量、最终验证和交付身份见 [assessment.json](assessment.json)。生产服务未部署，历史实验 JAR 保持原哈希，正式独立验收仍未完成。

爬山法适合这里的离线选优：固定输入和标准，每次只改一小处，用重复对照检验，只有事实正确、完整性和输出契约不退化且改善足够大时才保留。没有改善就停止；验证集有限使用，保留集不反馈给改写器。当前 [run_evolution_experiment.py](../run_evolution_experiment.py) 已有预算、候选、比较和停止机制，但每轮仍从固定基线生成，尚未让胜者继续成为下一轮父代，因此不是严格的连续爬山。本轮不新增优化器框架。

[ProTeGi](https://aclanthology.org/2023.emnlp-main.494/) 用错误反馈提出修改，并结合 beam search 与 bandit 筛选；[GEPA](https://arxiv.org/abs/2507.19457) 用执行反馈反思并保留具有不同优势的候选。这些研究支持该方向值得试验，不能代替项目自己的效果证据。当前更需要能识别具体错误的评测；继续增加措辞，不能保证模型正确计算或正确解释会计科目。

复核入口：[原始输入](capture-before.json)、[首次修改输入](capture-after.json)、[来源与编译资源哈希](provenance.json)、[16 调用记录](raw/plan.json)。捕获和回放用的临时 Java 源随本报告保留；它们不属于生产接口，也不是另一套 Agent 实现。运行必须使用新输出目录，避免覆盖现有记录。
