"""Shared helpers for the StockSage evaluation scripts."""

import math
import re
from typing import Any


DEFAULT_PHOENIX_ENDPOINT = "http://localhost:6006/v1/traces"
DEFAULT_PHOENIX_PROJECT = "stocksage-rag-eval"


def binary_ranking_metrics(relevance_by_rank):
    retrieved_count = len(relevance_by_rank)
    relevant_count = sum(relevance_by_rank)
    first_relevant_rank = next(
        (index for index, relevant in enumerate(relevance_by_rank, start=1) if relevant),
        0,
    )
    dcg = sum(
        relevant / math.log2(index + 2)
        for index, relevant in enumerate(relevance_by_rank)
    )
    idcg = sum(
        1 / math.log2(index + 2)
        for index in range(relevant_count)
    )
    return {
        "context_precision": relevant_count / retrieved_count if retrieved_count else 0.0,
        "hit_rate": 1.0 if relevant_count else 0.0,
        "mrr": 1.0 / first_relevant_rank if first_relevant_rank else 0.0,
        "ndcg": dcg / idcg if idcg else 0.0,
        "retrieved_count": retrieved_count,
        "relevant_context_count": relevant_count,
        "first_relevant_rank": first_relevant_rank,
    }


def average_numeric_metrics(rows):
    metrics = [row.get("metrics", {}) for row in rows]
    metric_names = sorted({
        key
        for values in metrics
        for key, value in values.items()
        if isinstance(value, (int, float)) and value is not None
    })
    averages = {}
    for name in metric_names:
        values = [
            row[name]
            for row in metrics
            if isinstance(row.get(name), (int, float)) and row.get(name) is not None
        ]
        averages[name] = sum(values) / len(values)
    return averages


def setup_phoenix(args, tracer_name):
    if args.no_phoenix:
        return None, None
    try:
        from opentelemetry import trace
        from phoenix.otel import register

        provider = register(
            endpoint=args.phoenix_endpoint,
            project_name=args.phoenix_project,
            batch=False,
            verbose=False,
        )
        return trace.get_tracer(tracer_name), provider
    except Exception as exc:
        print(f"Phoenix export disabled: {exc}")
        return None, None


SHA256_PATTERN = re.compile(r"^[0-9a-fA-F]{64}$")


def is_sha256(value: Any) -> bool:
    return isinstance(value, str) and SHA256_PATTERN.fullmatch(value) is not None


def gate_result(
    metric: str,
    value: Any,
    operator: str,
    threshold: Any,
    *,
    passed: bool | None = None,
) -> dict[str, Any]:
    if passed is None:
        if operator == "==":
            passed = value == threshold
        elif operator == ">=":
            passed = value >= threshold
        elif operator == "<=":
            passed = value <= threshold
        else:
            raise ValueError(f"Unsupported gate operator: {operator}")
    return {
        "metric": metric,
        "value": value,
        "operator": operator,
        "threshold": threshold,
        "status": "passed" if passed else "failed",
    }
