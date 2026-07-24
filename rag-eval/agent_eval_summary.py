"""Build and gate the stable agent_eval_v1 report contract."""

from __future__ import annotations

import json
import math
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


SCHEMA_VERSION = "agent_eval_v1"


def percentile(values: list[float], quantile: float) -> float | None:
    if not values:
        return None
    ordered = sorted(float(value) for value in values)
    index = max(0, math.ceil(quantile * len(ordered)) - 1)
    return ordered[index]


def not_run(reason: str) -> dict[str, Any]:
    return {"status": "not_run", "reason": reason}


def normalize_planner(payload: dict[str, Any]) -> dict[str, Any]:
    results = payload.get("results") or []
    normalized = {
        "status": payload.get("status", "failed"),
        "mode": payload.get("mode"),
        "total_cases": int(payload.get("totalCases", 0)),
        "passed_cases": int(payload.get("passedCases", 0)),
        "critical_failures": int(payload.get("criticalFailures", 0)),
        "route_accuracy": float(payload.get("routeAccuracy", 0.0)),
        "required_action_recall": float(payload.get("requiredActionRecall", 0.0)),
        "forbidden_action_rate": float(payload.get("forbiddenActionRate", 0.0)),
        "executable_rate": float(payload.get("executableRate", 0.0)),
        "duration_ms": int(payload.get("durationMs", 0)),
        "p95_latency_ms": percentile(
            [result.get("durationMs", 0) for result in results], 0.95
        ),
        "results": results,
    }
    return normalized


def metric_delta(current: dict[str, Any], baseline: dict[str, Any] | None) -> dict[str, Any]:
    if not baseline:
        return not_run("legacy baseline was not provided")
    fields = ("route_accuracy", "required_action_recall", "executable_rate")
    return {
        "status": "completed",
        "metrics": {
            field: float(current.get(field, 0.0)) - float(baseline.get(field, 0.0))
            for field in fields
        },
        "p95_latency_ratio": (
            None
            if not baseline.get("p95_latency_ms")
            else float(current.get("p95_latency_ms") or 0.0)
            / float(baseline["p95_latency_ms"])
        ),
    }


def evaluate_gates(
    planner: dict[str, Any],
    delta: dict[str, Any],
    gates: dict[str, Any],
) -> list[dict[str, Any]]:
    checks = [
        ("route_accuracy", planner["route_accuracy"], ">=", gates["route_accuracy_min"]),
        (
            "required_action_recall",
            planner["required_action_recall"],
            ">=",
            gates["required_action_recall_min"],
        ),
        (
            "forbidden_action_rate",
            planner["forbidden_action_rate"],
            "<=",
            gates["forbidden_action_rate_max"],
        ),
        (
            "critical_failures",
            planner["critical_failures"],
            "<=",
            gates["critical_failures_max"],
        ),
        (
            "executable_rate",
            planner["executable_rate"],
            ">=",
            gates["executable_rate_min"],
        ),
    ]
    results = [
        {
            "metric": name,
            "value": value,
            "operator": operator,
            "threshold": threshold,
            "status": "passed"
            if (value >= threshold if operator == ">=" else value <= threshold)
            else "failed",
        }
        for name, value, operator, threshold in checks
    ]
    if delta.get("status") == "completed":
        metric_values = delta["metrics"].values()
        max_drop = gates["legacy_delta_max_drop"]
        results.append(
            {
                "metric": "legacy_max_metric_drop",
                "value": min(metric_values),
                "operator": ">=",
                "threshold": -max_drop,
                "status": "passed" if min(metric_values) >= -max_drop else "failed",
            }
        )
        latency_ratio = delta.get("p95_latency_ratio")
        results.append(
            {
                "metric": "p95_latency_ratio",
                "value": latency_ratio,
                "operator": "<=",
                "threshold": gates["p95_latency_ratio_max"],
                "status": "not_run"
                if latency_ratio is None
                else (
                    "passed"
                    if latency_ratio <= gates["p95_latency_ratio_max"]
                    else "failed"
                ),
            }
        )
    else:
        results.extend(
            [
                {
                    "metric": "legacy_max_metric_drop",
                    "status": "not_run",
                    "reason": delta["reason"],
                },
                {
                    "metric": "p95_latency_ratio",
                    "status": "not_run",
                    "reason": delta["reason"],
                },
            ]
        )
    return results


def build_report(
    planner_payload: dict[str, Any],
    gates: dict[str, Any],
    baseline_payload: dict[str, Any] | None = None,
    rag_payload: dict[str, Any] | None = None,
    trace_payload: dict[str, Any] | None = None,
) -> dict[str, Any]:
    planner = normalize_planner(planner_payload)
    baseline = normalize_planner(baseline_payload) if baseline_payload else None
    delta = metric_delta(planner, baseline)
    gate_results = evaluate_gates(planner, delta, gates)
    status = "failed" if any(gate["status"] == "failed" for gate in gate_results) else "passed"
    return {
        "schema_version": SCHEMA_VERSION,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "status": status,
        "planner": planner,
        "rag": rag_payload or not_run("RAG evaluation was not supplied"),
        "trace": trace_payload or not_run("Trace integrity evaluation was not supplied"),
        "baseline_delta": delta,
        "gates": gate_results,
    }


def load_json(path: str | Path) -> dict[str, Any]:
    return json.loads(Path(path).read_text(encoding="utf-8"))
