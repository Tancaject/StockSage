"""针对 StockSage 运行仅检索质量检查。

该脚本调用后端 /api/docs/search 端点，并打分返回上下文是否包含预期或相关术语。
它是检索栈的快速冒烟测试，不是完整的回答质量评估。
"""

import argparse
import json
from datetime import datetime
from pathlib import Path

import requests

from eval_utils import (
    DEFAULT_PHOENIX_ENDPOINT,
    DEFAULT_PHOENIX_PROJECT,
    binary_ranking_metrics,
    setup_phoenix,
)


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_QUESTIONS = ROOT / "rag-eval" / "questions.jsonl"
DEFAULT_RESULTS_DIR = ROOT / "rag-eval" / "results"


def load_questions(path):
    records = []
    with Path(path).open("r", encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, start=1):
            stripped = line.strip()
            if not stripped:
                continue
            record = json.loads(stripped)
            if not record.get("query"):
                raise ValueError(f"Missing query at {path}:{line_number}")
            if not record.get("expected"):
                raise ValueError(f"Missing expected terms at {path}:{line_number}")
            records.append(record)
    return records


def fetch_contexts(base_url, query, timeout):
    response = requests.get(
        f"{base_url.rstrip('/')}/api/docs/search",
        params={"q": query, "full": "true"},
        timeout=timeout,
    )
    response.raise_for_status()
    return response.json()


def context_text(context):
    parts = [str(context.get("content", ""))]
    metadata = context.get("metadata")
    if metadata:
        parts.append(json.dumps(metadata, ensure_ascii=False, sort_keys=True))
    return "\n".join(parts).casefold()


def retrieval_metrics(contexts, expected_terms, relevance_terms):
    context_texts = [context_text(context) for context in contexts]
    expected_terms = [str(term).casefold() for term in expected_terms]
    relevance_terms = [str(term).casefold() for term in (relevance_terms or expected_terms)]

    found_terms = {
        term: any(term in text for text in context_texts)
        for term in expected_terms
    }
    context_recall = (
        sum(1 for found in found_terms.values() if found) / len(found_terms)
        if found_terms else 0.0
    )

    relevance_by_rank = []
    relevance_term_hits_by_rank = []
    for text in context_texts:
        hits = [term for term in relevance_terms if term in text]
        relevance_term_hits_by_rank.append(hits)
        relevance_by_rank.append(1 if hits else 0)

    return binary_ranking_metrics(relevance_by_rank) | {
        "context_recall": context_recall,
        "found_terms": found_terms,
        "relevance_by_rank": relevance_by_rank,
        "relevance_term_hits_by_rank": relevance_term_hits_by_rank,
    }


def emit_phoenix_case(tracer, row):
    if tracer is None:
        return
    from opentelemetry import trace

    with tracer.start_as_current_span("ragas.retrieval_eval") as span:
        span.set_attribute("openinference.span.kind", "EVALUATOR")
        span.set_attribute("input.value", row["query"])
        span.set_attribute("output.value", json.dumps({
            "name": row["name"],
            "metrics": row["metrics"],
            "term_scores": row["term_scores"],
        }, ensure_ascii=False))
        span.set_attribute("eval.case_name", row["name"])
        span.set_attribute("eval.context_recall", row["metrics"]["context_recall"])
        span.set_attribute("eval.context_precision", row["metrics"]["context_precision"])
        span.set_attribute("eval.hit_rate", row["metrics"]["hit_rate"])
        span.set_attribute("eval.mrr", row["metrics"]["mrr"])
        span.set_attribute("eval.ndcg", row["metrics"]["ndcg"])
        span.set_attribute("eval.string_presence", row["string_presence"])
        span.set_attribute("retrieval.documents.count", row["retrieved_count"])
        span.set_attribute("retrieval.documents.relevant_count", row["metrics"]["relevant_context_count"])
        span.set_status(trace.StatusCode.OK)


def emit_phoenix_summary(tracer, summary):
    if tracer is None:
        return
    with tracer.start_as_current_span("ragas.retrieval_eval.summary") as span:
        span.set_attribute("openinference.span.kind", "EVALUATOR")
        span.set_attribute("eval.case_count", summary["case_count"])
        span.set_attribute("eval.average_string_presence", summary["average_string_presence"])
        span.set_attribute("eval.average_context_recall", summary["average_context_recall"])
        span.set_attribute("eval.average_context_precision", summary["average_context_precision"])
        span.set_attribute("eval.average_hit_rate", summary["average_hit_rate"])
        span.set_attribute("eval.average_mrr", summary["average_mrr"])
        span.set_attribute("eval.average_ndcg", summary["average_ndcg"])
        span.set_attribute("output.value", json.dumps({
            "average_context_recall": summary["average_context_recall"],
            "average_context_precision": summary["average_context_precision"],
            "average_hit_rate": summary["average_hit_rate"],
            "average_mrr": summary["average_mrr"],
            "average_ndcg": summary["average_ndcg"],
        }, ensure_ascii=False))


def set_phoenix_run_attributes(span, summary, output):
    if span is None:
        return
    span.set_attribute("openinference.span.kind", "EVALUATOR")
    span.set_attribute("eval.case_count", summary["case_count"])
    span.set_attribute("eval.average_string_presence", summary["average_string_presence"])
    span.set_attribute("eval.average_context_recall", summary["average_context_recall"])
    span.set_attribute("eval.average_context_precision", summary["average_context_precision"])
    span.set_attribute("eval.average_hit_rate", summary["average_hit_rate"])
    span.set_attribute("eval.average_mrr", summary["average_mrr"])
    span.set_attribute("eval.average_ndcg", summary["average_ndcg"])
    span.set_attribute("eval.output_path", str(output))
    span.set_attribute("output.value", json.dumps({
        "average_context_recall": summary["average_context_recall"],
        "average_context_precision": summary["average_context_precision"],
        "average_hit_rate": summary["average_hit_rate"],
        "average_mrr": summary["average_mrr"],
        "average_ndcg": summary["average_ndcg"],
        "output_path": str(output),
    }, ensure_ascii=False))


def run_eval(base_url, questions, timeout, tracer):
    rows = []
    for record in questions:
        # 每行保留原始上下文和派生指标，方便运行后检查回归，而不只是看标准输出摘要。
        query = record["query"]
        contexts = fetch_contexts(base_url, query, timeout)
        expected_terms = record["expected"]
        relevance_terms = record.get("relevance") or expected_terms
        metrics = retrieval_metrics(contexts, expected_terms, relevance_terms)
        retrieved_text = "\n".join(context_text(context) for context in contexts)
        term_scores = {
            str(term): float(str(term).casefold() in retrieved_text)
            for term in expected_terms
        }
        mean_score = sum(term_scores.values()) / len(term_scores) if term_scores else 0.0
        row = {
            "name": record.get("name", query),
            "query": query,
            "expected": expected_terms,
            "relevance": relevance_terms,
            "retrieved_count": len(contexts),
            "string_presence": mean_score,
            "term_scores": term_scores,
            "metrics": metrics,
            "contexts": contexts,
        }
        rows.append(row)
        emit_phoenix_case(tracer, row)
        print(
            f"{record.get('name', query)}: "
            f"recall={metrics['context_recall']:.3f}, "
            f"precision={metrics['context_precision']:.3f}, "
            f"hit={metrics['hit_rate']:.0f}, "
            f"mrr={metrics['mrr']:.3f}, "
            f"ndcg={metrics['ndcg']:.3f}, "
            f"contexts={len(contexts)}, terms={term_scores}"
        )
    return rows


def write_results(rows, output):
    output = Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    summary = {
        "created_at": datetime.now().isoformat(timespec="seconds"),
        "case_count": len(rows),
        "average_string_presence": (
            sum(row["string_presence"] for row in rows) / len(rows) if rows else 0.0
        ),
        "average_context_recall": average(rows, "context_recall"),
        "average_context_precision": average(rows, "context_precision"),
        "average_hit_rate": average(rows, "hit_rate"),
        "average_mrr": average(rows, "mrr"),
        "average_ndcg": average(rows, "ndcg"),
        "cases": rows,
    }
    output.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    return summary


def average(rows, metric_name):
    return (
        sum(row["metrics"][metric_name] for row in rows) / len(rows)
        if rows else 0.0
    )


def parse_args():
    parser = argparse.ArgumentParser(
        description="Run a minimal Ragas retrieval evaluation against StockSage."
    )
    parser.add_argument(
        "--base-url",
        default="http://localhost:8080",
        help="StockSage backend base URL.",
    )
    parser.add_argument(
        "--questions",
        default=str(DEFAULT_QUESTIONS),
        help="JSONL file with name/query/expected records.",
    )
    parser.add_argument(
        "--timeout",
        type=int,
        default=120,
        help="HTTP timeout in seconds.",
    )
    parser.add_argument(
        "--output",
        default=None,
        help="Output JSON path. Defaults to rag-eval/results/retrieval_eval_<timestamp>.json.",
    )
    parser.add_argument(
        "--query",
        default=None,
        help="Run one ad-hoc query instead of loading the JSONL file.",
    )
    parser.add_argument(
        "--expected",
        action="append",
        default=[],
        help="Expected term for --query. Repeat this flag for multiple terms.",
    )
    parser.add_argument(
        "--relevance",
        action="append",
        default=[],
        help="Term used to judge whether each retrieved context is relevant. Repeat this flag for multiple terms.",
    )
    parser.add_argument(
        "--phoenix-endpoint",
        default=DEFAULT_PHOENIX_ENDPOINT,
        help="Phoenix OTLP HTTP traces endpoint.",
    )
    parser.add_argument(
        "--phoenix-project",
        default=DEFAULT_PHOENIX_PROJECT,
        help="Phoenix project name for evaluation traces.",
    )
    parser.add_argument(
        "--no-phoenix",
        action="store_true",
        help="Do not send evaluation metrics to Phoenix.",
    )
    return parser.parse_args()


def main():
    args = parse_args()
    if args.query:
        if not args.expected:
            raise SystemExit("--query requires at least one --expected term")
        questions = [{
            "name": "adhoc",
            "query": args.query,
            "expected": args.expected,
            "relevance": args.relevance or args.expected,
        }]
    else:
        questions = load_questions(args.questions)

    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    output = args.output or DEFAULT_RESULTS_DIR / f"retrieval_eval_{timestamp}.json"

    tracer, provider = setup_phoenix(args, "stocksage-rag-eval")
    if tracer is None:
        rows = run_eval(args.base_url, questions, args.timeout, tracer)
        summary = write_results(rows, output)
    else:
        with tracer.start_as_current_span("ragas.retrieval_eval.run") as run_span:
            run_span.set_attribute("openinference.span.kind", "EVALUATOR")
            run_span.set_attribute("input.value", str(args.questions if not args.query else args.query))
            rows = run_eval(args.base_url, questions, args.timeout, tracer)
            summary = write_results(rows, output)
            set_phoenix_run_attributes(run_span, summary, output)
            emit_phoenix_summary(tracer, summary)

    if provider is not None:
        provider.force_flush()

    print("")
    print(f"Average string_presence: {summary['average_string_presence']:.3f}")
    print(f"Average context_recall: {summary['average_context_recall']:.3f}")
    print(f"Average context_precision: {summary['average_context_precision']:.3f}")
    print(f"Average hit_rate: {summary['average_hit_rate']:.3f}")
    print(f"Average mrr: {summary['average_mrr']:.3f}")
    print(f"Average ndcg: {summary['average_ndcg']:.3f}")
    print(f"Saved results: {output}")
    if tracer is not None:
        print(f"Sent metrics to Phoenix project: {args.phoenix_project}")


if __name__ == "__main__":
    main()
