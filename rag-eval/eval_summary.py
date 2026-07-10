"""Summarize StockSage RAG evaluation result files and apply quality gates."""

import argparse
import json
from pathlib import Path


DEFAULT_GATES = {
    "context_recall": 0.85,
    "context_precision": 0.50,
    "mrr": 0.70,
    "ndcg": 0.75,
    "citation_precision": 0.85,
    "no_answer_accuracy": 0.90,
}


def load_result(path):
    with Path(path).open("r", encoding="utf-8") as handle:
        return json.load(handle)


def summarize_results(result, gates=None):
    gates = gates or DEFAULT_GATES
    averages = result.get("averages", {})
    gate_result = evaluate_gates(averages, gates)
    cases = result.get("cases", [])
    return {
        "created_at": result.get("created_at", ""),
        "case_count": int(result.get("case_count", len(cases))),
        "averages": averages,
        "gate_status": gate_result["status"],
        "failed_gates": gate_result["failed"],
        "missing_gates": gate_result["missing"],
        "category_counts": category_counts(cases),
        "worst_cases": worst_cases(cases),
    }


def evaluate_gates(averages, gates=None):
    gates = gates or DEFAULT_GATES
    failed = []
    missing = []
    for metric, minimum in gates.items():
        if metric not in averages or averages[metric] is None:
            missing.append(metric)
            continue
        value = float(averages[metric])
        if value < minimum:
            failed.append({"metric": metric, "value": value, "minimum": minimum})
    return {
        "status": "fail" if failed else "pass",
        "failed": failed,
        "missing": missing,
    }


def category_counts(cases):
    counts = {}
    for case in cases:
        category = case.get("category") or "uncategorized"
        counts[category] = counts.get(category, 0) + 1
    return counts


def worst_cases(cases, limit=10):
    return sorted(
        (
            {
                "id": case.get("id", ""),
                "category": case.get("category", "uncategorized"),
                "score": case_score(case.get("metrics", {})),
                "metrics": compact_metrics(case.get("metrics", {})),
            }
            for case in cases
        ),
        key=lambda item: item["score"],
    )[:limit]


def case_score(metrics):
    keys = [
        "context_recall",
        "context_precision",
        "mrr",
        "ndcg",
        "citation_precision",
        "no_answer_accuracy",
    ]
    values = [
        float(metrics[key])
        for key in keys
        if isinstance(metrics.get(key), (int, float)) and metrics.get(key) is not None
    ]
    if not values:
        return 0.0
    return sum(values) / len(values)


def compact_metrics(metrics):
    keys = [
        "context_recall",
        "context_precision",
        "mrr",
        "ndcg",
        "citation_precision",
        "no_answer_accuracy",
        "latency_seconds",
    ]
    return {key: metrics[key] for key in keys if key in metrics}


def parse_args():
    parser = argparse.ArgumentParser(description="Summarize a StockSage RAG eval JSON result.")
    parser.add_argument("result", help="Path to rag_eval_<timestamp>.json")
    parser.add_argument("--output", help="Optional summary JSON path")
    parser.add_argument("--fail-on-gate", action="store_true", help="Exit 1 when required gates fail")
    return parser.parse_args()


def main():
    args = parse_args()
    summary = summarize_results(load_result(args.result))
    payload = json.dumps(summary, ensure_ascii=False, indent=2)
    if args.output:
        Path(args.output).write_text(payload, encoding="utf-8")
    print(payload)
    if args.fail_on_gate and summary["gate_status"] != "pass":
        raise SystemExit(1)


if __name__ == "__main__":
    main()
