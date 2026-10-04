# 批量学习任务的数据与来源

本批共 24 个正常基本面任务，按公司分为 12 个学习／开发题和 12 个新公司迁移题。题目与公司在本轮业务基线运行前确定；不根据基线对错筛题。原值、公式、必答项及来源文本以 [dataset-draft.json](dataset-draft.json) 为数据准备的权威来源，冻结后的执行文件由 `prepare_dataset.py` 生成。独立来源复核见 [source-review.json](source-review.json)。标签均为 AI 离线核查，未作人工复核或发布授权。

| 分组 | 公司 | 原报告族 | 每家公司任务 |
|---|---|---|---|
| DEVELOPMENT | IBM、JNJ、PEP、TGT | 各自 FY2025 Q2/Q3 财报；IBM 使用 Q3 已披露单季列 | 单季计算或基础对照、累计利润现金转化、期末债务与流动性 |
| VALIDATION | CSCO、ORCL、KO、LOW | 各自 FY2025 Q2/Q3 财报；CSCO 使用 Q3 已披露单季利润列 | 同上 |

六题需要由年内累计现金流还原单季；IBM 与 CSCO 两题直接读取报表单季列，作为基础能力对照。八题利润现金转化和八题期末资产负债分析同时检查方法适用边界。资产负债表期末余额不能被累计现金流的相减规则替代。是否错误应用规则是判错依据，不要求回答机械复述这句限制。

开发与迁移的公司、报告族完全分离，旧实验的 MSFT/WMT、GOOGL/COST 以及 HOLDOUT 的 AAPL/AMZN 均未复用。每家公司三题共享报告族，不能当作三个独立公司样本。来源整理人员读取迁移报告是为了建立答案依据；迁移模型输出不得用于本批候选生成或筛选。

## 官方原始来源

| 公司 | Q2／前累计期间来源 | Q3来源 |
|---|---|---|
| IBM | 不需要；单季列为基础对照 | [官方 Q3 公告](https://newsroom.ibm.com/2025-10-22-IBM-RELEASES-THIRD-QUARTER-RESULTS) |
| JNJ | [官方 Q2 10-Q PDF](https://s203.q4cdn.com/636242992/files/doc_financials/2025/q2/2Q-10Q-06-29-25-Final.pdf) | [官方 Q3 10-Q PDF](https://s203.q4cdn.com/636242992/files/doc_financials/2025/q3/3Q-10Q-09-28-25-5.pdf) |
| PEP | [官方 Q2 公告 PDF](https://www.pepsico.com/docs/pepsico-5v9wci20/media/Files/investors/q2-2025-earnings-release.pdf) | [官方 Q3 公告 PDF](https://www.pepsico.com/docs/pepsico-5v9wci20/media/Files/investors/q3-2025-earnings-release.pdf) |
| TGT | [官方 Q2 公告](https://corporate.target.com/press/release/2025/08/target-corporation-reports-second-quarter-earnings) | [官方 Q3 公告](https://corporate.target.com/press/release/2025/11/target-corporation-reports-third-quarter-earnings) |
| CSCO | 不需要；单季利润表为基础对照 | [官方 Q3 公告](https://investor.cisco.com/news/news-details/2025/CISCO-REPORTS-THIRD-QUARTER-EARNINGS/default.aspx) |
| ORCL | [官方 Q2 公告 PDF](https://s23.q4cdn.com/440135859/files/doc_financials/2025/q2/2q25-pressrelease-December-final.pdf) | [官方 Q3 公告](https://investor.oracle.com/investor-news/news-details/2025/Oracle-Announces-Fiscal-2025-Third-Quarter-Financial-Results/default.aspx) |
| KO | [公司托管 Q2 SEC 公告](https://investors.coca-colacompany.com/filings-reports/all-sec-filings/content/0000021344-25-000054/a2025q2earningsreleaseex-9.htm) | [公司托管 Q3 SEC 公告](https://investors.coca-colacompany.com/filings-reports/all-sec-filings/content/0001628280-25-045577/a2025q3earningsreleaseex-9.htm) |
| LOW | [官方 Q2 公告](https://corporate.lowes.com/newsroom/press-releases/lowes-reports-second-quarter-2025-sales-and-earnings-results-08-20-25) | [官方 Q3 公告](https://corporate.lowes.com/newsroom/press-releases/lowes-reports-third-quarter-2025-sales-and-earnings-results-11-19-25) |

各来源对象保存实际下载文件位置及 SHA-256。HTML 按原表逐行提取单元格文本，保留表名、列头、单位、原值、相邻调整项目与必要脚注；UTF-8 解码后合并排版空白。JNJ、PEP 使用 PDF 原页表格提取，并合并连续排版空白。ORCL Q2 PDF 使用默认文本提取保留两列数值顺序，原提取落到页末的 `2024 2023` 列头也移到期间标题后；不能采用其会挤连或倒序金额的 layout 提取结果。

KO 利润现金转化题另附同份公告中的 fairlife 支付说明。该补充段落保存在题目来源正文内，可由原始 HTML 复核；基础 `KO-q3-cashflow.txt` 只保存现金流表。该段不改变报表现金流金额，也不把非 GAAP 剔除口径作为 GAAP 参考值。每题提供真实原表片段，而非只给整理后的单值。最大可见来源文本约 5,840 字符。

## 事先固定的计算口径

- 购建支出在原表通常用括号表示流出，答案字段 `CapitalSpendingOutflowAmount` 使用支出正金额；简化自由现金流为经营现金流减支出毛额，不扣资产出售回款，也不照抄公司的其他自由现金流定义。
- 累计还原使用同一财政年度起点、同科目和合并口径。派生字段单独声明还原后第三季度的起止日。PEP 的 24 周／36 周、零售公司的财务周以及 ORCL 的跨自然年财年均按原报告，不改成自然季度。
- 利润现金转化以现金流量表起点的合并净利润为准。PEP 与 KO 的合并净利润不能无说明地换成归母净利润。期末净债务严格按各题指定的债务和现金／投资范围计算，不把总负债当作有息债务。
- LOW 资产负债表的对照列为上年同季度 `2024-11-01`，不是上财年末。其他题也按实际表头列示日期判定。
- 比率和百分比要求保留两位小数，数值核验采用统一 `absoluteTolerance=0.01`、`decimalPlaces=4`。金额允许等值单位换算。IBM 现金流桥接存在披露到百万美元产生的 1 百万美元舍入差，允许声明源表舍入，不要求虚构调节项；披露现金流总额仍是比率计算的依据。
- `requiredFactFields` 只标识题目要求输出的数字。内部原值与中间结果用于复算，不因答案未逐项复述而判失败。可选解释和禁止错误推断另存 `reviewNotes`；答案没有机械写出限制语句，不构成缺答。

SEC `companyfacts` 的原始 XBRL 记录用于第二条取证路径，按申报 accession、财政起止日、USD 单位和报告期间筛选，不能只取接口最后一条值。其核查记录由独立复核文件维护。主模型的证据仍是上述原始报表片段。
