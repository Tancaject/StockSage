# Part B official-source audit

These 10 paired answers are authored calibration examples for the frozen ordinary-answer rubric. They are not captured business-Agent outputs, human gold labels, or evidence of business-Agent end-to-end quality.

- Rubric: `ordinary_answer_rubric_v2`, SHA-256 `3396bd8f16f563fd04079836ed8280f58b2e37f39a508a2189fb82321068f870`.
- Split: `VALIDATION`; two independent source groups, five variants per group. Each group has identical question and evidence, with two correct answers, one numeric/metric error, one explicit-task omission, and one unsupported certainty.
- Evidence was independently retrieved from official releases on 2026-10-02, then normalized into Chinese. Numeric and period statements retain the source's rounded release amounts. The records carry the official URL, title, section, source-check date, authored-answer origin, and AI label provenance.
- Each expected dimension was assigned under the frozen rubric before evaluator calls. Independent label review is tracked in each JSONL record; the dataset is not human-reviewed gold.
- No model-provider calls or runtime/prompt/rubric changes were made by this author.

## FedEx FY2025 fourth quarter

[Official FedEx release](https://newsroom.fedex.com/newsroom/global-english/fedex-reports-fourth-quarter-diluted-eps-of-6-88-and-adjusted-diluted-eps-of-6-07), published 2025-06-24. Title: *FedEx Reports Fourth Quarter Diluted EPS of $6.88 and Adjusted Diluted EPS of $6.07*. Sections: opening consolidated quarter table; Fourth Quarter Results; Outlook; forward-looking statements.

| Item | Checked source fact |
| --- | --- |
| Period | Fourth quarter ended 2025-05-31, compared with FY2024 fourth quarter |
| Revenue | $22.2 billion versus $22.1 billion = 222 versus 221 亿美元 |
| GAAP operating margin | 8.1% versus 7.0% |
| Adjusted operating margin | 9.1% versus 8.5%, explicitly non-GAAP |
| Federal Express | Operating results improved; DRIVE savings, U.S. volume, international export volume and base yield contributed |
| FedEx Freight | Operating results decreased; lower fuel surcharges/weight per shipment, higher healthcare/wage costs and one fewer operating day |
| FY2026 Q1 outlook | Revenue growth forecast: flat to 2% year over year; this is a quarterly forecast |
| Forecast boundary | Economic/fuel-price assumptions, completion of planned repurchases and no additional adverse economic/geopolitical/trade developments; actual outcomes can differ |

The omission variant leaves out the explicitly requested Freight decline. That independently violates task coverage and omits relevant opposing business performance. The numeric variant addresses Freight and only swaps the two accounting-basis margins; it therefore does not fail counterevidence or task coverage. The unsupported variant retains the correct forecast range but treats it as a guarantee.

## NIKE FY2025 fourth quarter

[Official NIKE release](https://investors.nike.com/investors/news-events-and-reports/investor-news/investor-news-details/2025/NIKE-Inc--Reports-Fiscal-2025-Fourth-Quarter-and-Full-Year-Results/default.aspx), published 2025-06-26. Title: *NIKE, Inc. Reports Fiscal 2025 Fourth Quarter and Full Year Results*. Sections: Fourth Quarter Income Statement Review; Consolidated Statements of Income, three months ended May 31; Forward-Looking Statements; non-GAAP footnote.

| Item | Checked source fact |
| --- | --- |
| Period | Three months ended 2025-05-31, compared with three months ended 2024-05-31 |
| Revenue | Release summary $11.1 billion = 111 亿美元; table $11,097 million is consistent after rounding |
| Reported revenue change | Down 12% |
| Currency-neutral change | Down 11%; non-GAAP |
| Gross margin | 40.3% versus 44.7%, down 440 basis points |
| Unit calculation | 44.7 − 40.3 = 4.4 percentage points; 440 basis points ÷ 100 = 4.4 percentage points |
| Disclosed primary causes | Higher discounts and changes in channel mix |
| Future boundary | The captured material gives no next-quarter margin figure or recovery guarantee; the release says future expectations involve risks and uncertainties |

The question is deliberately limited to consolidated revenue, margin and disclosed margin causes. The captured evidence has no relevant opposing business fact in that scope, so counterevidence is `NOT_APPLICABLE` for all five variants. Predictions and missing future disclosure are assessed under `unknowns`. The numeric variant's 0.44-point fall is a factual/unit error even though the margin subquestion is answered; the omission variant drops current margin, its change and causes without corrupting its remaining facts.
