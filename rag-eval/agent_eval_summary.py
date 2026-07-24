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
    per_route = payload.get("perRoute") or {}
    normalized = {
        "status": payload.get("status", "failed"),
        "mode": payload.get("mode"),
        "total_cases": int(payload.get("totalCases", 0)),
        "passed_cases": int(payload.get("passedCases", 0)),
        "critical_failures": int(payload.get("criticalFailures", 0)),
        "route_accuracy": float(payload.get("routeAccuracy", 0.0)),
        "macro_f1": float(payload.get("macroF1", 0.0)),
        "per_route": per_route,
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
        return not_run("routing baseline was not provided")
    fields = ("route_accuracy", "macro_f1", "required_action_recall", "executable_rate")
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
        ("macro_f1", planner["macro_f1"], ">=", gates["macro_f1_min"]),
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
        max_drop = gates.get("baseline_delta_max_drop", gates.get("legacy_delta_max_drop", 0.02))
        results.append(
            {
                "metric": "baseline_max_metric_drop",
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
                    "metric": "baseline_max_metric_drop",
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
    dialog_payload: dict[str, Any] | None = None,
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
        "end_to_end": dialog_payload or not_run(
            "End-to-end dialog and LLM-as-Judge evaluation was not supplied"
        ),
        "baseline_delta": delta,
        "gates": gate_results,
        "recommendations": recommendations(planner, delta),
    }


def recommendations(planner: dict[str, Any], delta: dict[str, Any]) -> list[str]:
    """EchoMind-style actionable hints based on route and regression metrics."""
    items: list[str] = []
    if planner["route_accuracy"] < 0.90:
        items.append("路由准确率低于 90%，优先补充误路由类别的 few-shot 和边界样本")
    low_routes = [
        route
        for route, values in planner.get("per_route", {}).items()
        if float(values.get("f1", 0.0)) < 0.90
    ]
    if low_routes:
        items.append("低 F1 路由需要补充样本或调整路由提示词：" + ", ".join(sorted(low_routes)))
    if planner["required_action_recall"] < 0.98:
        items.append("必要动作召回不足，检查后端 route 到固定执行计划的映射")
    if planner["forbidden_action_rate"] > 0:
        items.append("出现禁止动作，检查 Action 白名单和固定计划边界")
    if delta.get("status") == "completed" and any(
        float(value) < -0.05 for value in delta.get("metrics", {}).values()
    ):
        items.append("相比 routing baseline 出现超过 5% 的指标退化，检查最近的 prompt 或路由规则变更")
    return items or ["路由分类、固定动作和回归指标均达标"]


def load_json(path: str | Path) -> dict[str, Any]:
    return json.loads(Path(path).read_text(encoding="utf-8"))
