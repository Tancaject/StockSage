# AI 来源核查口径

只阅读指定六个题目包，每题包含原问题、冻结证据、AI 核过的答案草稿和四份匿名回答。不要读取映射、候选方法、历史评审或其他题目；不得调用模型服务或外部搜索。来源与答案草稿冲突时以原始报表为准，明确记录冲突。评审者可能已有会话背景，因此匿名呈现不等同于严格独立盲评。

逐份完整阅读，使用同一标准，不推测属于哪一组。只检查普通题目的实际错误，不增加极端场景要求，也不因风格、篇幅或缺少无关指标判错。

- `requiredFactsCorrect`：问题要求的原始金额、期间、币种和科目均正确，缺失另计覆盖。
- `derivedCorrect`：必答公式和结果正确。金额允许等价换算及说明过的舍入；百分比容差 0.1 个百分点，比率容差 0.01。额外数字错误记在证据支持及 notes 中，不能漏掉。
- `coverageComplete`：问题要求的项目均有回应，包括明确要求的有限度结论。结尾列出资料缺口不能抵消前文违反该要求的确定性断言。
- `evidenceGrounded`：所有重要事实、额外合计/比率、单位和解释可由所给证据或有效计算支持。合理的会计勾稽和明确的条件性机制不自动算错；从总额确认具体公司业务原因、投资目的、管理意图、资金可用性或绝对偿债保证，缺少依据则不能通过。利润表费用不自动等于现金流出。
- `verdict`：四项均为 true 且未发现其他实质错误时为 `PASS`，否则 `ISSUES`。每个问题须给出原句及可核实的原因/正确数值；不支持不等于现实中必然为假。

输出 JSON：`{reviewType:"AI_SOURCE_GROUNDED",humanReviewed:false,publicationAuthorized:false,reviews:[{caseId,answerId,requiredFactsCorrect,derivedCorrect,coverageComplete,evidenceGrounded,verdict,notes:[...]}]}`。24 个答案 ID 必须完整、唯一，不包含组别推测。保留题不参与核查。
