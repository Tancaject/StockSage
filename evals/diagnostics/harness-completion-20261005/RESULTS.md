# Harness 完成判定与报告修复

本次补齐三处已有流程：DEEP 的一次报告修复读取具体验收反馈；普通生成保存实际结束原因并拒绝把未完成正文记为成功；离线 Harness 评测直接调用生产策略，不再依赖 JUnit。评测 Agent、评分标准、原始用例及门禁配置保持不变。

## 验证结果

| 范围 | 结果 | 原始产物 |
|---|---|---|
| 后端生产源码 | Maven `compile` 退出 0，JDK 17.0.12 | [构建与源码身份](verification.json) |
| Python → Java 生产策略 → 报告 → 门禁汇总 | 82/82 用例匹配，14/14 门禁通过 | [报告](../../../target/harness-recovery-20261005/harness.json)、[汇总](../../../target/harness-recovery-20261005/harness.summary.json)、[运行命令](../../../target/harness-recovery-20261005/execution.json) |
| 普通生成：受控 HTTP → Spring AI → 领域分析/最终回答 → 完成判定 | 37 项检查通过 | [完整请求、响应与结果](../../../target/ordinary-completion-e2e-20261005/result.json) |
| DEEP：受控 HTTP → Manager → 验收 → 一次修复 → 复验 | 10 个场景通过 | [汇总](../../harness-runtime-20261005/deep/artifacts/summary.json)、[场景及执行说明](../../harness-runtime-20261005/deep/README.md) |
| 离线评测失败处理 | 评分不匹配退出 1；非法输入退出 2，保留已有成功报告 | [故障记录](../../../target/harness-recovery-20261005/failure-paths.json) |

普通生成检查覆盖明确结束、截断、缺失/未知结束原因、空正文、内容筛选、意外工具调用、HTTP 错误、断流、取消、观察记录失败，以及已有证据状态和引用检查。当前 SDK 会把 `stop` 暴露为 `STOP`；首次驱动误用了大小写敏感的断言，修正检查后通过，未为此修改生产逻辑。[首次结果](../../../target/ordinary-completion-e2e-20261005/first-run.json)保留。SDK 无法解析的新枚举值在进入业务层前失败；SDK 已识别的 `UNKNOWN` 和缺失原因进入未知状态，两者均不能判为完整回答。

DEEP 检查覆盖首次通过、字段错误/空输出/未知引用后的修复、修复耗尽、保存 `PLANNED` 后的 JSON 重建接管、checkpoint 拒绝、旧 checkpoint 缺少新增反馈、历史报告复验，以及执行权中断。它检查实际发给模型的反馈、调用次数和复验结果。

## 复现

从仓库根目录执行，使用 JDK 17+ 和 Python 3。三个入口均不调用外部模型；HTTP 回放只监听和连接 `127.0.0.1`。

```powershell
python evals/run_harness_eval.py --output target/harness-recovery-20261005/harness.json --fail-on-gate
.\evals\diagnostics\ordinary-completion-20261005\run.ps1
.\evals\harness-runtime-20261005\deep\run.ps1
```

第一条命令编译后端并生成运行依赖清单。若生产源码已经编译且清单存在，可给第一条命令加 `--skip-build`，但它仍会重新编译并执行 Java 评测驱动。后两个入口复用这些生产类，分别重新编译自己的回放驱动。Windows 启动器优先使用 `JAVA_HOME`，长 classpath 通过参数文件传入。

## 解释范围

这些结果证明受控响应下的生成结束判定、修复反馈传递和生产策略执行；不证明真实模型能修好报告、研究内容正确或整站可以发布。本次没有外部模型调用，没有恢复 JUnit、Mockito 或单元测试套件。

当前服务与数据库端口未连通，未进行浏览器、真实 MySQL/Redis、`ChatService` 消息持久化和报告最终发布的整站验收。DEEP 接管检查使用实际状态 JSON 重建，未模拟真实数据库行锁和 Redis 租约。普通回放使用实际 SDK、Coordinator 和最终回答回放服务；进化回放的组装代码经过编译，共享草稿与结果规则在受控链路中检查，未执行完整进化实验。

旧的 `draft-budget-verification/DraftBudgetReplay.java` 只提供草稿正文，没有结束原因，不能满足新的完整生成条件。冻结历史源码保持原样；不能伪造正常结束元数据使旧检查通过。其他历史入口限制见 [评测说明](../../README.md)。
