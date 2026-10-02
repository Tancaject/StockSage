# 2026-10-02 evaluator retest — part A sources

This file records the source boundary for the 10 authored calibration answers in [part-a.jsonl](part-a.jsonl). These are controlled answer variants, not captured outputs of a business model. All examples are VALIDATION, use the same frozen ordinary rubric (`3396bd8f16f563fd04079836ed8280f58b2e37f39a508a2189fb82321068f870`), and expose the normalized evidence directly to the judge. Within each company group the question and evidence are identical. The answers and five-dimensional labels are AI-authored and source-checked; an independent AI review is required before use. No provider calls were made by this author.

## Home Depot FY2025 Q1

- Official source: [The Home Depot Announces First Quarter Fiscal 2025 Results; Reaffirms Fiscal 2025 Guidance](https://ir.homedepot.com/news-releases/2025/05-20-2025-110127912).
- Published 2025-05-20; accessed 2026-10-02.
- Report period: three months ended 2025-05-04, compared with three months ended 2024-04-28. This is Home Depot fiscal Q1, not calendar Q1.
- Source sections: opening sales and earnings paragraphs; CEO commentary; Condensed Consolidated Statements of Earnings.
- Normalization: the statement reports millions of USD. Net sales 39,856 and 36,418 become 398.56 and 364.18 亿美元; net earnings 3,433 and 3,600 become 34.33 and 36.00 亿美元. Preserve the release's rounded growth rates: sales +9.4%, earnings −4.6%.
- The opening paragraph reports total-company comparable sales −0.3% and an approximately 70-basis-point adverse currency effect. The total-company figure is distinct from the U.S.-only comparable-sales measure.
- CEO commentary reports continued customer participation in smaller projects and spring events; it does not quantify consumer renovation-demand recovery as the main cause of total-sales growth.
- Question scope includes total sales, total-company comparable sales, the currency factor, net earnings, and whether sales and earnings improved together. The five variants share these exact requirements.

## UPS CY2025 Q1

- Official source: [UPS Releases 1Q 2025 Earnings](https://investors.ups.com/news-events/press-releases/detail/2142/ups-releases-1q-2025-earnings).
- Published 2025-04-29; accessed 2026-10-02.
- Report period: three months ended 2025-03-31, identified in the release's reconciliation headings.
- Source sections: International Segment table and growth bullet; 2025 Outlook; Forward-Looking Statements.
- Normalization: international revenue 4,373 million USD = 43.73 亿美元; prior-year revenue is 4,256 million USD. Preserve the company's rounded +2.7% revenue growth and +7.1% average daily volume growth.
- The release attributes international revenue growth to the increase in average daily volume. It does not give an international Q2 volume-growth forecast. The outlook paragraph says macroeconomic uncertainty prevents an update to the previously issued consolidated full-year outlook and refers Q2 financial-performance expectations to the earnings call.
- Question scope explicitly asks about international revenue and business volume and excludes profitability assessment. Both observed indicators increase; an unrelated profitability fact is not required counterevidence. Prediction uncertainty is assessed under unknowns.
- All evidence consists of short Chinese factual normalizations. English release text is not reproduced.

## Preparation checks

Only one existing v2 case was read for JSON shape; prior result files and business HOLDOUT material were not read. The source values were checked against the official releases before labels were assigned. Local checks confirm ten unique IDs, two groups of five, identical paired questions/evidence, all five variants per group, and the frozen rubric hash. These checks establish artifact structure and bindings, not evaluator quality.

