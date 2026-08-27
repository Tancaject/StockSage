"""针对 StockSage 运行回答级 RAG 评估。

后端 /api/eval/rag 适配器会返回检索上下文、生成回答、引用信息，
以及可选的中间检索细节。本脚本同时评估检索和回答行为，包括无法回答的用例。
"""

import argparse
from collections import Counter
import json
import os
import re
import time
from datetime import datetime
from pathlib import Path

import requests

from eval_utils import (
    DEFAULT_PHOENIX_ENDPOINT,
    DEFAULT_PHOENIX_PROJECT,
    average_numeric_metrics,
    binary_ranking_metrics,
    setup_phoenix,
)


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_GOLDEN_SET = ROOT / "rag-eval" / "golden_set.jsonl"
DEFAULT_RESULTS_DIR = ROOT / "rag-eval" / "results"

NO_ANSWER_MARKERS = [
    "does not disclose",
    "doesn't disclose",
    "not disclose",
    "not disclosed",
    "not provided",
    "not available",
    "insufficient",
    "cannot determine",
    "no specific",
    "no disclosure",
    "not contain",
    "not mention",
    "no evidence",
    "未披露",
    "没有披露",
    "未提供",
    "无法确定",
    "上下文不足",
]


def load_jsonl(path):
    rows = []
    with Path(path).open("r", encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, start=1):
            stripped = line.strip()
            if not stripped:
                continue
            row = json.loads(stripped)
            if not row.get("id"):
                raise ValueError(f"Missing id at {path}:{line_number}")
            if not row.get("question"):
                raise ValueError(f"Missing question at {path}:{line_number}")
            rows.append(row)
    return rows


def call_eval_endpoint(base_url, case, timeout, include_intermediate, max_context_chars, admin_token):
    payload = {
        "question": case["question"],
        "reference_answer": case.get("reference_answer", ""),
        "answerable": case.get("answerable", True),
        "include_intermediate": include_intermediate,
        "max_context_chars": max_context_chars,
    }
    started = time.perf_counter()
    response = requests.post(
        f"{base_url.rstrip('/')}/api/eval/rag",
        json=payload,
        timeout=timeout,
        # /api/eval/** 受 AdminApiInterceptor 保护，必须携带管理令牌头。
        headers={"X-StockSage-Admin-Token": admin_token},
    )
    latency = time.perf_counter() - started
    response.raise_for_status()
    return response.json(), latency


def context_blob(context):
    parts = [str(context.get("content", ""))]
    metadata = context.get("metadata") or {}
    parts.append(json.dumps(metadata, ensure_ascii=False, sort_keys=True))
    return "\n".join(parts).casefold()


def metadata_matches(context, evidence, include_date=True):
    for key in ["ticker", "filing_type", "section"]:
        expected = evidence.get(key)
        if expected and str(context.get(key, "")).casefold() != str(expected).casefold():
            return False
    expected_date = evidence.get("filing_date")
    if include_date and expected_date:
        actual = str(context.get("filing_date", ""))
        if expected_date not in actual:
            return False
    return True


def evidence_matches(context, evidence, require_all_terms=True):
    if not metadata_matches(context, evidence, include_date=True):
        return False
    must_terms = [str(term).casefold() for term in evidence.get("must_contain", [])]
    if not must_terms:
        return True
    blob = context_blob(context)
    if require_all_terms:
        return all(term in blob for term in must_terms)
    return any(term in blob for term in must_terms)


def is_relevant_context(context, expected_evidence):
    if not expected_evidence:
        return False
    return any(evidence_matches(context, evidence, require_all_terms=False) for evidence in expected_evidence)


def retrieval_metrics(contexts, expected_evidence):
    relevance_by_rank = [
        1 if is_relevant_context(context, expected_evidence) else 0
        for context in contexts
    ]
    evidence_hits = []
    for evidence in expected_evidence:
        evidence_hits.append(any(evidence_matches(context, evidence) for context in contexts))

    context_recall = (
        sum(1 for hit in evidence_hits if hit) / len(evidence_hits)
        if evidence_hits else 0.0
    )
    return binary_ranking_metrics(relevance_by_rank) | {
        "context_recall": context_recall,
        "evidence_hits": evidence_hits,
        "relevance_by_rank": relevance_by_rank,
    }


def metadata_accuracy(contexts, expected_evidence):
    expected_tickers = expected_values(expected_evidence, "ticker")
    expected_types = expected_values(expected_evidence, "filing_type")
    expected_dates = expected_values(expected_evidence, "filing_date")
    expected_sections = expected_values(expected_evidence, "section")
    return {
        "source_accuracy": value_accuracy(contexts, "ticker", expected_tickers),
        "filing_type_accuracy": value_accuracy(contexts, "filing_type", expected_types),
        "filing_date_accuracy": date_accuracy(contexts, expected_dates),
        "section_accuracy": value_accuracy(contexts, "section", expected_sections),
    }


def expected_values(expected_evidence, key):
    return {str(item[key]).casefold() for item in expected_evidence if item.get(key)}


def value_accuracy(contexts, key, expected):
    if not expected:
        return None
    if not contexts:
        return 0.0
    return sum(
        1 for context in contexts
        if str(context.get(key, "")).casefold() in expected
    ) / len(contexts)


def date_accuracy(contexts, expected_dates):
    if not expected_dates:
        return None
    if not contexts:
        return 0.0
    return sum(
        1 for context in contexts
        if any(date in str(context.get("filing_date", "")) for date in expected_dates)
    ) / len(contexts)


def answer_metrics(case, response, expected_evidence):
    answer = str(response.get("answer") or "")
    answer_text = answer.casefold()
    reference = str(case.get("reference_answer") or "")
    reference_terms = [str(term).casefold() for term in case.get("reference_terms", [])]
    answerable = bool(case.get("answerable", True))

    term_hits = {term: term in answer_text for term in reference_terms}
    answer_keyword_recall = (
        sum(1 for hit in term_hits.values() if hit) / len(term_hits)
        if term_hits else None
    )
    answer_token_f1 = token_f1(answer, reference) if reference else None
    no_answer_accuracy = None
    unsupported_answer_rate = None

    if not answerable:
        refused = any(marker.casefold() in answer_text for marker in NO_ANSWER_MARKERS)
        no_answer_accuracy = 1.0 if refused else 0.0
        unsupported_answer_rate = 0.0 if refused else 1.0

    citations = response.get("citations") or []
    contexts = response.get("retrieved_contexts") or []
    cited_ranks = {int(item.get("rank", 0)) for item in citations if item.get("rank")}
    cited_contexts = [
        contexts[rank - 1]
        for rank in cited_ranks
        if 1 <= rank <= len(contexts)
    ]
    relevant_cited = [
        context for context in cited_contexts
        if is_relevant_context(context, expected_evidence)
    ]
    citation_precision = (
        len(relevant_cited) / len(cited_contexts)
        if cited_contexts else (0.0 if answerable else None)
    )
    citation_recall = (
        1.0 if relevant_cited else 0.0
        if answerable and expected_evidence else None
    )

    return {
        "answer_keyword_recall": answer_keyword_recall,
        "answer_token_f1": answer_token_f1,
        "answer_reference_term_hits": term_hits,
        "citation_precision": citation_precision,
        "citation_recall": citation_recall,
        "cited_count": len(cited_contexts),
        "no_answer_accuracy": no_answer_accuracy,
        "unsupported_answer_rate": unsupported_answer_rate,
    }


def token_f1(answer, reference):
    answer_tokens = tokenize(answer)
    reference_tokens = tokenize(reference)
    if not answer_tokens or not reference_tokens:
        return 0.0
    common = sum((Counter(answer_tokens) & Counter(reference_tokens)).values())
    if common == 0:
        return 0.0
    precision = common / len(answer_tokens)
    recall = common / len(reference_tokens)
    return 2 * precision * recall / (precision + recall)


def tokenize(text):
    return re.findall(r"[a-z0-9]+", str(text).casefold())


def emit_case_span(tracer, row):
    if tracer is None:
        return
    from opentelemetry import trace

    metrics = row["metrics"]
    with tracer.start_as_current_span("ragas.answer_eval") as span:
        span.set_attribute("openinference.span.kind", "EVALUATOR")
        span.set_attribute("input.value", row["question"])
        span.set_attribute("output.value", json.dumps({
            "id": row["id"],
            "answer": row["answer"],
            "metrics": metrics,
        }, ensure_ascii=False))
        span.set_attribute("eval.case_id", row["id"])
        for key, value in metrics.items():
            if isinstance(value, (int, float)) and value is not None:
                span.set_attribute(f"eval.{key}", float(value))
        span.set_attribute("retrieval.documents.count", metrics["retrieved_count"])
        span.set_status(trace.StatusCode.OK)


def emit_summary_span(tracer, summary, output):
    if tracer is None:
        return
    with tracer.start_as_current_span("ragas.answer_eval.summary") as span:
        span.set_attribute("openinference.span.kind", "EVALUATOR")
        span.set_attribute("eval.case_count", summary["case_count"])
        for key, value in summary["averages"].items():
            if value is not None:
                span.set_attribute(f"eval.average_{key}", float(value))
        span.set_attribute("eval.output_path", str(output))
        span.set_attribute("output.value", json.dumps(summary["averages"], ensure_ascii=False))


def run_cases(args, cases, tracer):
    rows = []
    for case in cases:
        # 后端执行与对话相同的 RagService 路径，评估器则把打分规则保留在本脚本内。
        response, latency = call_eval_endpoint(
            args.base_url,
            case,
            args.timeout,
            args.include_intermediate,
            args.max_context_chars,
            args.admin_token,
        )
        contexts = response.get("retrieved_contexts") or []
        expected_evidence = case.get("expected_evidence") or []
        metrics = {}
        metrics.update(retrieval_metrics(contexts, expected_evidence))
        metrics.update(metadata_accuracy(contexts, expected_evidence))
        metrics.update(answer_metrics(case, response, expected_evidence))
        metrics["latency_seconds"] = latency

        row = {
            "id": case["id"],
            "category": case.get("category", ""),
            "question": case["question"],
            "answerable": case.get("answerable", True),
            "reference_answer": case.get("reference_answer", ""),
            "answer": response.get("answer", ""),
            "trace_id": response.get("trace_id", ""),
            "rewritten_query": response.get("rewritten_query", ""),
            "filter_expression": response.get("filter_expression", ""),
            "metrics": metrics,
            "expected_evidence": expected_evidence,
            "retrieved_contexts": contexts,
            "citations": response.get("citations") or [],
            "retrieval": response.get("retrieval") or {},
        }
        rows.append(row)
        emit_case_span(tracer, row)
        print_case(row)
    return rows


def print_case(row):
    metrics = row["metrics"]
    print(
        f"{row['id']}: "
        f"recall={metrics['context_recall']:.3f}, "
        f"precision={metrics['context_precision']:.3f}, "
        f"mrr={metrics['mrr']:.3f}, "
        f"ndcg={metrics['ndcg']:.3f}, "
        f"citation_p={fmt_metric(metrics['citation_precision'])}, "
        f"answer_terms={fmt_metric(metrics['answer_keyword_recall'])}, "
        f"contexts={metrics['retrieved_count']}, "
        f"latency={metrics['latency_seconds']:.1f}s"
    )


def fmt_metric(value):
    return "--" if value is None else f"{value:.3f}"


def write_results(rows, output):
    output = Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    summary = {
        "created_at": datetime.now().isoformat(timespec="seconds"),
        "case_count": len(rows),
        "averages": average_numeric_metrics(rows),
        "cases": rows,
    }
    output.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    return summary


def parse_args():
    parser = argparse.ArgumentParser(
        description="Run full StockSage RAG evaluation against /api/eval/rag."
    )
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--golden-set", default=str(DEFAULT_GOLDEN_SET))
    parser.add_argument("--timeout", type=int, default=240)
    parser.add_argument("--output", default=None)
    parser.add_argument("--max-context-chars", type=int, default=6000)
    parser.add_argument("--include-intermediate", action="store_true")
    parser.add_argument("--phoenix-endpoint", default=DEFAULT_PHOENIX_ENDPOINT)
    parser.add_argument("--phoenix-project", default=DEFAULT_PHOENIX_PROJECT)
    parser.add_argument("--no-phoenix", action="store_true")
    parser.add_argument(
        "--admin-token",
        default=os.environ.get("STOCKSAGE_ADMIN_TOKEN", ""),
        help="X-StockSage-Admin-Token header value; defaults to STOCKSAGE_ADMIN_TOKEN and must be configured.",
    )
    args = parser.parse_args()
    if not args.admin_token.strip():
        parser.error("--admin-token or STOCKSAGE_ADMIN_TOKEN is required")
    return args


def main():
    args = parse_args()
    cases = load_jsonl(args.golden_set)
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    output = args.output or DEFAULT_RESULTS_DIR / f"rag_eval_{timestamp}.json"

    tracer, provider = setup_phoenix(args, "stocksage-rag-answer-eval")
    rows = run_cases(args, cases, tracer)
    summary = write_results(rows, output)
    emit_summary_span(tracer, summary, output)

    if provider is not None:
        provider.force_flush()

    print("")
    print("Averages:")
    for key, value in summary["averages"].items():
        print(f"  {key}: {fmt_metric(value)}")
    print(f"Saved results: {output}")
    if tracer is not None:
        print(f"Sent metrics to Phoenix project: {args.phoenix_project}")


if __name__ == "__main__":
    main()
