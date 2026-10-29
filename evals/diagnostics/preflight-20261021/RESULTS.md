# 依赖预检：路由核对与本机 HTTP 验证

实现：[eval_preflight.py](../../eval_preflight.py)。验证命令（仓库根目录）：

```powershell
python evals/diagnostics/preflight-20261021/check.py
```

[check.py](check.py) 启动临时 `127.0.0.1` HTTP 服务，设置仅本进程有效的假密钥和本地模型端点，执行真实 requests 调用，最后关闭服务并恢复环境。需要允许本机回环连接。结果保存在 [results.json](results.json)，含 helper 哈希、请求方法/路径/参数及预检输出，不保存 Authorization 内容。

## 修正与依据

| 对象 | 实际入口与判定 |
|---|---|
| BaoStock | 改为 `/api/stock/financial?code=sh.600519`，只调用一张盈利表。成功载荷是 `metrics`，没有 `status=SUCCESS`；有数值才记可用。依据：[路由](../../../stocksage-data-service/app/routers/stock.py)、[get_financial_metrics](../../../stocksage-data-service/app/services/baostock_service.py)。 |
| AKShare | 港股年度财报，显式指定 `period=annual&years=1`。除总体 SUCCESS 外还要求存在可用表数据；PARTIAL 单列为 UNKNOWN，不当成完全不可用。依据：[normalize_hk_financials](../../../stocksage-data-service/app/hk_financials.py)。 |
| SEC | `/api/edgar/filings?ticker=AAPL&type=10-K&count=1` 返回对象中的 `filings` 列表，须有文档 URL；任意非空列表不算成功。仅验证索引，不验证正文或 XBRL。依据：[get_filings](../../../stocksage-data-service/app/services/edgar_service.py)。 |
| Tavily | 搜索端点会回退 DuckDuckGo，DDG 有结果不能证明 Tavily 可用；检测返回 provider/fallbackFrom。默认不执行可能扣搜索额度的探测。依据：[SearchService](../../../stocksage-data-service/app/services/search_service.py)。 |
| Ollama | 一条本地 embedding 请求必须返回非空有限数值向量，空向量不算成功。 |
| IBKR | 只检查会话认证；明确未连接或 competing 时不能记可用，缺字段为 UNKNOWN。自签名 TLS 例外仅限 loopback，远程地址正常验证证书。会话成功不代表行情权限或所有工具可用。 |

模型环境变量与 [application.properties](../../../stocksage-backend/src/main/resources/application.properties) 一致：`STOCKSAGE_DEEPSEEK_API_KEY` / `STOCKSAGE_DEEPSEEK_BASE_URL`，`DASHSCOPE_API_KEY` / `STOCKSAGE_OPENAI_BASE_URL`。DeepSeek 直接在配置 base 后加 `/models`，百炼兼容接口在需要时补 `/v1`；已含 `/v1` 时不重复拼接。

这些是评测进程的参数及环境变量，不是后端已生效配置。后端若只在本地 properties 保存密钥，预检可能 SKIPPED，而后端仍能调用模型。新增 `configuration_source` 和说明明确此边界，不读取或复制本地密钥。

## 验证结果与范围

本机 HTTP 链路通过：8 个默认探测请求，加搜索回退、非 JSON、HTTP 404 三个边界，共 11 次本机请求；外部请求 0 次。额外断言覆盖错误 payload、错误结构、空财报、空向量、会话断开以及凭据不进入产物。

未增加付费生成探针。模型列表返回成功仍为 `UNKNOWN / MODEL_LIST_ONLY_GENERATION_NOT_CHECKED`，`generation_checked=false`；实际生成可用性须由任务自身的真实调用证据确认。Tavily 的可选搜索探针会使用额度，默认保持关闭；本次只对本机假服务验证该分类路径。

预检失败只说明这一请求失败，不能单凭它把回归失败排除。空财报可能是最新季度尚未披露，不等于数据源故障。本次没有检查真实供应商、模型生成权限或线上完整可用率。
