export const RECENT_RAG_EVAL_SNAPSHOT = {
  created_at: '2026-05-13T15:57:58',
  case_count: 50,
  averages: {
    answer_keyword_recall: 0.98,
    citation_precision: 0.867,
    citation_recall: 0.9795918367346939,
    context_precision: 0.8059999999999999,
    context_recall: 0.91,
    filing_type_accuracy: 0.884,
    hit_rate: 0.96,
    latency_seconds: 10.355394035999488,
    mrr: 0.9033333333333333,
    ndcg: 0.9141133586249423,
    no_answer_accuracy: 1.0,
    source_accuracy: 1.0,
    unsupported_answer_rate: 0.0,
  },
  cases: [
    {
      id: 'amzn_10k_operating_segments',
      category: 'single_filing_fact',
      metrics: { context_recall: 1.0, context_precision: 0.4, mrr: 1.0, ndcg: 0.85, citation_precision: 1.0 },
    },
    {
      id: 'meta_not_msft_advertising',
      category: 'ticker_disambiguation',
      metrics: { context_recall: 1.0, context_precision: 0.8, mrr: 1.0, ndcg: 1.0, citation_precision: 1.0 },
    },
  ],
}
