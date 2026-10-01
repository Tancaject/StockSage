"""Summarize StockSage RAG results and export the authoritative rag_gates_v1 decision.

DEFAULT_GATES defines required quality minima; latency is diagnostic only. An
explicit required=False gate may be absent. Missing required or entirely absent
evidence yields missing, never pass. --fail-on-gate rejects fail and missing.
Generated results carry gate_evaluation (thresholds, required flags and statuses)
for EvalDesk to display. Older files can acquire this contract via --output;
until evaluated, the UI labels them unrated rather than inventing thresholds.
"""

import argparse
import json
import math
from pathlib import Path


DEFAULT_GATES = {
    "context_recall": {"minimum": 0.85, "required": True},
    "context_precision": {"minimum": 0.50, "required": True},
    "mrr": {"minimum": 0.70, "required": True},
    "ndcg": {"minimum": 0.75, "required": True},
    "citation_precision": {"minimum": 0.85, "required": True},
    "no_answer_accuracy": {"minimum": 0.90, "required": True},
}


def load_result(path):
    with Path(path).open("r", encoding="utf-8") as handle:
        return json.load(handle)


def summarize_results(result, gates=None):
    averages = result.get("averages", {})
    gate_result = result.get("gate_evaluation") if gates is None else None
    if gate_result is None:
        gate_result = evaluate_gates(averages, gates)
    if gate_result.get("schema_version") != "rag_gates_v1":
        raise ValueError("Unsupported RAG gate evaluation schema; regenerate the evaluation result.")
    cases = result.get("cases", [])
    return {
        "created_at": result.get("created_at", ""),
        "case_count": int(result.get("case_count", len(cases))),
        "averages": averages,
        "gate_evaluation": gate_result,
        "gate_status": gate_result["status"],
        "failed_gates": gate_result["failed"],
        "missing_gates": gate_result["missing"],
        "category_counts": category_counts(cases),
        "worst_cases": worst_cases(cases),
        "cases": cases,
    }


def evaluate_gates(averages, gates=None):
    gates = DEFAULT_GATES if gates is None else gates
    failed = []
    missing = []
    rows = []
    for metric, target in gates.items():
        minimum = target["minimum"]
        value = averages.get(metric)
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
            value = None
        if value is None:
            missing.append(metric)
        elif value < minimum:
            failed.append({"metric": metric, "value": value, "minimum": minimum})
        rows.append({
            "metric": metric, "value": value, "operator": ">=", "threshold": minimum,
            "required": target["required"],
            "status": "missing" if value is None else "fail" if value < minimum else "pass",
        })
    incomplete = (not any(row["value"] is not None for row in rows)
                  or any(row["required"] and row["status"] == "missing" for row in rows))
    return {
        "schema_version": "rag_gates_v1",
        "status": "fail" if failed else "missing" if incomplete else "pass",
        "gates": rows,
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
    parser.add_argument("--fail-on-gate", action="store_true", help="Exit 1 when gates fail or required evidence is missing")
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
