# StockSage RAG Evaluation Plan

This document describes how to evaluate StockSage RAG quality in a systematic way.

## Goal

RAG evaluation should answer five questions:

- Did ingestion preserve the right evidence from SEC filings?
- Did retrieval find the right company, filing type, filing date, and section?
- Did reranking move the best evidence to the top?
- Did the final answer stay faithful to the retrieved context?
- Did citations and no-answer behavior work correctly?

The current `rag-eval/run_retrieval_eval.py` is only a retrieval smoke test. It verifies that `/api/docs/search` can return contexts containing expected keywords. It is not a full answer-level RAG evaluation.

## Evaluation Layers

### 1. Ingestion Quality

Checks whether documents were parsed, chunked, and indexed correctly.

Recommended checks:

- Filing coverage: expected 10-K / 10-Q filings exist for each ticker.
- Chunk count sanity: each filing produces nonzero chunks.
- Metadata completeness: `ticker`, `filing_type`, `filing_date`, `section`, `source_id`, `parent_vector_id`.
- Parent-child linkage: child chunks can resolve to parent chunks.
- Section extraction: important sections such as Item 1A, Item 7, Item 8, notes, and legal proceedings are retained.

Useful failure examples:

- AMZN / XOM / JNJ filings produce `0 chunks`.
- Section headings are trapped inside HTML tables and skipped.
- Parent chunks are stored but child chunks cannot resolve back to them.

### 2. Retrieval Quality

Checks whether the retriever finds useful evidence before answer generation.

Core metrics:

| Metric | Meaning |
|---|---|
| `Recall@K` | Required evidence appears somewhere in top-k contexts. |
| `Precision@K` | Fraction of top-k contexts that are actually useful. |
| `Hit Rate@K` | At least one relevant context appears in top-k. |
| `MRR` | First relevant context appears early in the ranking. |
| `nDCG@K` | Relevant contexts are ranked near the top. |
| `Source Accuracy` | Retrieved contexts come from the correct company / ticker. |
| `Filing Accuracy` | Retrieved contexts come from the correct 10-K / 10-Q and year / quarter. |
| `Section Accuracy` | Retrieved contexts come from the expected filing section. |
| `Duplicate Rate` | Measures repeated or near-duplicate contexts. |

Current smoke-test metrics:

- `context_recall`
- `context_precision`
- `hit_rate`
- `mrr`
- `ndcg`
- `string_presence`

These are currently keyword-based. They are useful for quick checks but should be replaced or supplemented with evidence-level labels.

### 3. Reranking Quality

Checks whether reranking improves the order of retrieved evidence.

Run the same golden set with:

- rerank disabled
- rerank enabled
- different candidate top-k values

Compare:

- `MRR`
- `nDCG@K`
- `Precision@K`
- latency

Useful experiments:

```text
rerank=false vs rerank=true
candidate_top_k=10 vs 20 vs 50
top_k=3 vs 5 vs 10
```

### 4. Answer Quality

Checks whether the final generated answer is correct and grounded.

Required fields:

```json
{
  "user_input": "AMZN 2025 10-K 中披露的主要业务分部有哪些？",
  "retrieved_contexts": [
    "retrieved filing context 1",
    "retrieved filing context 2"
  ],
  "response": "model generated answer",
  "reference": "gold answer"
}
```

Recommended Ragas metrics:

| Metric | Meaning |
|---|---|
| `Faithfulness` | The answer is supported by retrieved contexts. |
| `ResponseRelevancy` | The answer addresses the user question. |
| `LLMContextRecall` | Retrieved contexts contain evidence needed for the reference answer. |
| `LLMContextPrecisionWithReference` | Retrieved contexts are relevant to the reference answer. |
| `FactualCorrectness` | The answer matches the reference facts. |

Project-specific answer checks:

- Does the answer cite SEC evidence rather than general knowledge?
- Does it avoid unsupported numbers?
- Does it distinguish 10-K from 10-Q?
- Does it avoid mixing companies?
- Does it explain when the filing does not disclose something?

### 5. Citation Quality

Checks whether citations actually support the claims.

Metrics:

| Metric | Meaning |
|---|---|
| `Citation Precision` | Cited contexts support the claims they are attached to. |
| `Citation Recall` | Important claims have citations. |
| `Citation Source Accuracy` | Citations point to the correct ticker, filing, date, and section. |

Failure examples:

- Answer says "AWS is a segment" but citation points to unrelated risk text.
- Answer compares 2024 and 2025 but only cites one year.
- Answer cites AMZN evidence for AAPL.

### 6. No-Answer Quality

Checks whether the system refuses to answer when filings do not contain the requested information.

Metrics:

| Metric | Meaning |
|---|---|
| `No-answer Accuracy` | Correctly says the answer is not disclosed. |
| `False Claim Rate` | Makes unsupported claims for unanswerable questions. |
| `Unsupported Answer Rate` | Provides answers without evidence. |

Good no-answer examples:

```text
AMZN 10-K 有没有披露 2030 年 AWS 收入预测？
NVDA 10-K 有没有给出 Blackwell 每季度具体出货量？
AAPL 10-K 有没有承诺未来三年 iPhone 销量增长率？
XOM 10-K 有没有披露未来每口油井的产量预测？
JNJ 10-Q 有没有给出所有未决诉讼的最终赔偿金额？
```

Expected behavior:

- Say the filing does not directly disclose the requested information.
- Avoid inventing numbers.
- Cite relevant absence or related disclosure only if useful.

## Golden Set Design

The first production-quality golden set should contain about 50 cases.

Recommended mix:

| Category | Count | Purpose |
|---|---:|---|
| Single-filing fact lookup | 10 | Tests precise evidence retrieval. |
| Risk factors | 10 | Tests Item 1A / risk retrieval. |
| Cross-year or cross-quarter comparison | 10 | Tests multi-document retrieval. |
| Multi-company comparison | 5 | Tests ticker isolation and contrast. |
| No-answer questions | 5 | Tests refusal and hallucination control. |
| Ticker ambiguity / distraction | 5 | Tests metadata filtering. |
| Numeric / table questions | 5 | Tests table and financial evidence handling. |

### Golden Set Schema

Use JSONL. One line per case.

```json
{
  "id": "amzn_2025_segments",
  "category": "single_filing_fact",
  "ticker": "AMZN",
  "filing_type": "10-K",
  "filing_date": "2025-02-07",
  "question": "AMZN 2025 10-K 中披露的主要业务分部有哪些？",
  "answerable": true,
  "reference_answer": "Amazon reports three segments: North America, International, and AWS.",
  "expected_evidence": [
    {
      "ticker": "AMZN",
      "filing_type": "10-K",
      "filing_date": "2025-02-07",
      "section": "Item 8",
      "must_contain": ["North America", "International", "AWS"]
    }
  ]
}
```

For no-answer cases:

```json
{
  "id": "nvda_blackwell_quarterly_shipments_no_answer",
  "category": "no_answer",
  "ticker": "NVDA",
  "filing_type": "10-K",
  "question": "NVDA 10-K 有没有给出 Blackwell 每季度具体出货量？",
  "answerable": false,
  "reference_answer": "The filing does not disclose specific quarterly Blackwell shipment quantities.",
  "expected_behavior": "refuse_or_state_not_disclosed"
}
```

## Example Questions

### Fact Lookup

```text
AMZN 2025 10-K 中披露的主要业务分部有哪些？
AAPL 2025 10-K 中按产品类别披露了哪些收入来源？
NVDA 2025 10-K 中 Data Center 收入增长的主要原因是什么？
XOM 最近 10-K 中 capital and exploration expenditures 是怎么描述的？
JNJ 最近 10-Q 中 litigation 相关披露有哪些？
```

### Risk Factors

```text
NVDA 10-K 中提到的供应链风险有哪些？
AAPL 10-K 中关于中国市场或供应链依赖的风险是什么？
AMZN 10-K 中关于竞争压力的风险是什么？
META 10-K 中关于隐私、监管或广告业务的风险是什么？
TSLA 10-K 中关于生产扩张和供应链的风险是什么？
```

### Cross-Document Comparison

```text
比较 AMZN 最近两份 10-K 中 AWS 增长描述有什么变化。
比较 NVDA 2024 和 2025 10-K 中对 AI demand 的表述变化。
比较 AAPL 最近两年 10-K 中 Greater China 相关描述。
比较 XOM 最近 10-K 和 10-Q 中 capital expenditure 的表述是否一致。
比较 JNJ 最近 10-K 和 10-Q 中 litigation 风险是否有新增变化。
```

### Ticker Disambiguation

```text
苹果公司的 supply chain risk 是什么？
Apple 和 Amazon 的 operating segments 有什么不同？
比较 META 和 GOOGL 在广告业务风险上的披露。
XOM 的 capital expenditures，不要引用 AMZN 或 JNJ 的内容。
MSFT 的 cloud business risk 和 AMZN AWS risk 有什么区别？
```

## Required Eval Adapter

Full RAG evaluation needs a stable way to collect:

- original question
- rewritten query
- retrieved contexts
- final answer
- citations
- trace id

Recommended backend endpoint:

```text
POST /api/eval/rag
```

Response shape:

```json
{
  "question": "...",
  "rewritten_query": "...",
  "retrieved_contexts": [
    {
      "content": "...",
      "ticker": "AMZN",
      "filing_type": "10-K",
      "filing_date": "2025-02-07",
      "section": "Item 1A",
      "source_id": "...",
      "rank": 1
    }
  ],
  "answer": "...",
  "citations": [
    {
      "claim": "...",
      "source_id": "...",
      "rank": 1
    }
  ],
  "trace_id": "..."
}
```

Alternative:

- Call `/api/chat/stream`.
- Parse SSE.
- Extract only final `answer`.
- Fetch trace details from `/api/trace/{traceId}`.
- Join answer with retrieved contexts.

The dedicated `/api/eval/rag` endpoint is preferred because it avoids parsing streamed UI events during evaluation.

## Phoenix Usage

Use two Phoenix projects:

| Project | Purpose |
|---|---|
| `stocksage-rag` | Manual / live backend traces. |
| `stocksage-rag-eval` | Batch evaluation traces and metrics. |

For retrieval eval, check:

```text
eval.context_recall
eval.context_precision
eval.hit_rate
eval.mrr
eval.ndcg
eval.string_presence
```

For full answer eval, add:

```text
eval.faithfulness
eval.response_relevancy
eval.context_recall_llm
eval.context_precision_llm
eval.factual_correctness
eval.citation_precision
eval.citation_recall
eval.no_answer_accuracy
```

## Version Comparisons

Evaluate the same golden set across controlled variants.

Recommended experiments:

```text
chunk_size=800 vs 1200
parent_chunk_size=3000 vs 5000
top_k=3 vs 5 vs 10
rerank=false vs true
query_rewrite=false vs true
hybrid_search=false vs true
metadata_filter=false vs true
edgar-v3 vs next chunking version
```

Record:

- git commit
- chunking version
- embedding model
- rerank model
- top-k
- test set version
- average metrics
- failed cases

## Suggested Acceptance Gates

Initial target:

| Metric | Target |
|---|---:|
| `Recall@5` / `context_recall` | >= 0.85 |
| `Precision@5` / `context_precision` | >= 0.50 |
| `MRR` | >= 0.70 |
| `nDCG@5` | >= 0.75 |
| `Faithfulness` | >= 0.85 |
| `FactualCorrectness` | >= 0.80 |
| `Citation Precision` | >= 0.85 |
| `No-answer Accuracy` | >= 0.90 |
| `P95 latency` | <= 45s |

These gates should be tightened after the golden set stabilizes.

## Current Status

Implemented:

- Phoenix tracing for backend chat and `/api/docs/search`.
- Retrieval smoke test in `rag-eval/run_retrieval_eval.py`.
- Keyword-based retrieval metrics:
  - `context_recall`
  - `context_precision`
  - `hit_rate`
  - `mrr`
  - `ndcg`
  - `string_presence`
- Phoenix export to `stocksage-rag-eval`.
- Dedicated full-eval endpoint: `POST /api/eval/rag`.
- Evidence-level 50-case golden set: `rag-eval/golden_set.jsonl`.
- Full eval runner: `rag-eval/run_rag_eval.py`.
- Project-specific answer/citation/no-answer metrics:
  - `source_accuracy`
  - `filing_type_accuracy`
  - `filing_date_accuracy`
  - `section_accuracy`
  - `answer_keyword_recall`
  - `answer_token_f1`
  - `citation_precision`
  - `citation_recall`
  - `no_answer_accuracy`
  - `unsupported_answer_rate`
- Result summarizer and CI-style quality gate helper: `rag-eval/eval_summary.py`.
- Offline LLM-judge runner: `rag-eval/run_ragas_eval.py`. It consumes an existing full-eval result and adds prefixed Faithfulness, Factual Correctness, LLM Context Recall/Precision, and optional Response Relevancy metrics without rerunning retrieval.

Still missing / to improve:

- Run and record a current full 50-case LLM-judge baseline. The runner exists, but script availability alone is not evidence that the current model and data revision pass its gates.
- Add duplicate-rate and near-duplicate context checks.
- Add controlled experiment metadata: git commit, chunking version, embedding model, rerank model, top-k, and feature flags.
- Wire `eval_summary.py --fail-on-gate` into CI once a CI runner exists for this local demo project.

## Recommended Next Steps

1. Run `python .\rag-eval\run_rag_eval.py` after backend and Phoenix are up.
2. Review failed cases in `rag-eval/results/rag_eval_<timestamp>.json`.
3. Summarize a run with `python .\rag-eval\eval_summary.py .\rag-eval\results\rag_eval_<timestamp>.json --fail-on-gate`.
4. Run `python .\rag-eval\run_ragas_eval.py --dry-run`, then a limited judge run, before recording the full LLM-judge baseline.
5. Run controlled experiments for rerank, top-k, query rewrite, and chunking.
