# DEEP 报告修复受控 HTTP 回放

从固定、已完成的辩论和 Java 锁定裁决进入实际 `ResearchDebateService`，使用真实 Spring AI HTTP 客户端连接本机受控 SSE 服务，贯穿报告生成、解析、Harness 验收、原有一次修复和复验。无需 JUnit、Mockito、真实模型密钥或数据库。

仓库根目录执行（先使用当前源码完成后端 `compile`）：

```powershell
cd stocksage-backend
.\mvnw.cmd -q dependency:build-classpath '-DincludeScope=runtime' '-Dmdep.outputFile=target/harness-eval-classpath.txt'
cd ..
.\evals\harness-runtime-20261005\deep\run.ps1
```

`artifacts/plan.json` 列出隔离边界和失败方式；`artifacts/summary.json` 汇总结果。每个场景保存实际 HTTP 请求、受控响应、checkpoint JSON、最终状态和结果，可核对第二次请求确实收到违规码、字段问题及有界失败片段。

接管场景先写完整状态 JSON，再抛出模拟中断，随后重新反序列化并创建生产服务继续既定修复。checkpoint 拒绝与执行权丢失由回调/guard 注入。该回放验证报告链及其调用边界，不证明数据库事务、Redis 租约、Bull/Bear 生成、最终发布或真实模型质量。
