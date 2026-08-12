"""Build and gate the stable agent_eval_v1 report contract."""

from __future__ import annotations

import json
import math
import re
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


SCHEMA_VERSION = "agent_eval_v1"
SHA256_PATTERN = re.compile(r"^[0-9a-fA-F]{64}$")


def percentile(values: list[float], quantile: float) -> float | None:
    if not values:
        return None
    ordered = sorted(float(value) for value in values)
    index = max(0, math.ceil(quantile * len(ordered)) - 1)
    return ordered[index]


def not_run(reason: str) -> dict[str, Any]:
    return {"status": "not_run", "reason": reason}


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


def normalize_observed_values(values: Any) -> list[str]:
    if not isinstance(values, list):
        return []
    return sorted(
        {
            str(value).strip()
            for value in values
            if value is not None and str(value).strip()
        }
    )


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
    harness_payload: dict[str, Any] | None = None,
    harness_live_payload: dict[str, Any] | None = None,
) -> dict[str, Any]:
    planner = normalize_planner(planner_payload)
    baseline = normalize_planner(baseline_payload) if baseline_payload else None
    delta = metric_delta(planner, baseline)
    gate_results = evaluate_gates(planner, delta, gates)
    expected_policy_id = gates.get("harness_policy_id_expected", "deep-equity-v1")
    expected_policy_version = gates.get("harness_policy_version_expected", 2)
    if harness_payload is not None:
        harness_engine = harness_payload.get("engine")
        harness_schema = harness_payload.get("schema_version")
        harness_case_schema = harness_payload.get("case_schema_version")
        harness_policy_id = harness_payload.get("policy_id")
        harness_policy_version = harness_payload.get("policy_version")
        harness_status = harness_payload.get("status")
        harness_case_count = int(harness_payload.get("case_count", 0))
        harness_accuracy = float(harness_payload.get("exact_accuracy", 0.0))
        harness_decision_rate = float(
            harness_payload.get("decision_contract_exact_match_rate", 0.0)
        )
        harness_violation_rate = float(
            harness_payload.get("violation_exact_match_rate", 0.0)
        )
        harness_recovery_rate = float(
            harness_payload.get("recovery_exact_match_rate", 0.0)
        )
        harness_unsafe = int(harness_payload.get("unsafe_pass_count", 0))
        harness_coverage = harness_payload.get("coverage") or {}
        harness_coverage_status = (
            harness_coverage.get("status")
            if isinstance(harness_coverage, dict)
            else None
        )
        harness_dataset_hash = harness_payload.get("dataset_sha256")
        harness_dataset_hash_valid = is_sha256(harness_dataset_hash)
        gate_results.extend(
            [
                gate_result(
                    "harness_engine",
                    harness_engine,
                    "==",
                    "java-production-policy",
                ),
                gate_result(
                    "harness_schema",
                    harness_schema,
                    "==",
                    "harness_eval_v1",
                ),
                gate_result(
                    "harness_case_schema",
                    harness_case_schema,
                    "==",
                    "harness_golden_case_v2",
                ),
                gate_result(
                    "harness_policy_id",
                    harness_policy_id,
                    "==",
                    expected_policy_id,
                ),
                gate_result(
                    "harness_policy_version",
                    harness_policy_version,
                    "==",
                    expected_policy_version,
                    passed=str(harness_policy_version)
                    == str(expected_policy_version),
                ),
                gate_result("harness_status", harness_status, "==", "pass"),
                gate_result(
                    "harness_case_count",
                    harness_case_count,
                    ">=",
                    gates.get("harness_case_count_min", 60),
                ),
                gate_result(
                    "harness_exact_accuracy",
                    harness_accuracy,
                    ">=",
                    gates.get("harness_exact_accuracy_min", 1.0),
                ),
                gate_result(
                    "harness_decision_contract_exact_match_rate",
                    harness_decision_rate,
                    ">=",
                    gates.get(
                        "harness_decision_contract_exact_match_rate_min", 1.0
                    ),
                ),
                gate_result(
                    "harness_violation_exact_match_rate",
                    harness_violation_rate,
                    ">=",
                    gates.get("harness_violation_exact_match_rate_min", 1.0),
                ),
                gate_result(
                    "harness_recovery_exact_match_rate",
                    harness_recovery_rate,
                    ">=",
                    gates.get("harness_recovery_exact_match_rate_min", 1.0),
                ),
                gate_result(
                    "harness_unsafe_pass_count",
                    harness_unsafe,
                    "<=",
                    gates.get("harness_unsafe_pass_count_max", 0),
                ),
                gate_result(
                    "harness_coverage_status",
                    harness_coverage_status,
                    "==",
                    "pass",
                ),
                gate_result(
                    "harness_dataset_sha256",
                    harness_dataset_hash,
                    "matches",
                    "64 hexadecimal characters",
                    passed=harness_dataset_hash_valid,
                ),
            ]
        )
    if harness_live_payload is not None:
        live_metrics = harness_live_payload.get("metrics") or {}
        live_engine = harness_live_payload.get("engine")
        live_schema = harness_live_payload.get("schema")
        live_status = harness_live_payload.get("status")
        live_policy_ids = normalize_observed_values(
            harness_live_payload.get("policy_ids")
        )
        live_policy_versions = normalize_observed_values(
            harness_live_payload.get("policy_versions")
        )
        live_dataset_hash = harness_live_payload.get("dataset_sha256")
        expected_live_dataset_hash = gates.get(
            "harness_live_dataset_sha256_expected"
        )
        live_case_count = int(live_metrics.get("case_count", 0))
        live_completed = int(live_metrics.get("completed_count", 0))
        live_completed_rate = (
            live_completed / live_case_count if live_case_count > 0 else 0.0
        )
        live_safe_terminal_rate = float(
            live_metrics.get("safe_terminal_rate", 0.0)
        )
        live_unsafe = int(live_metrics.get("unsafe_result_count", 0))
        gate_results.extend(
            [
                gate_result(
                    "harness_live_engine",
                    live_engine,
                    "==",
                    "stocksage-live-http",
                ),
                gate_result(
                    "harness_live_schema",
                    live_schema,
                    "==",
                    "harness_live_eval_v1",
                ),
                gate_result(
                    "harness_live_policy_ids",
                    live_policy_ids,
                    "==",
                    [str(expected_policy_id)],
                ),
                gate_result(
                    "harness_live_policy_versions",
                    live_policy_versions,
                    "==",
                    [str(expected_policy_version)],
                ),
                gate_result(
                    "harness_live_dataset_sha256",
                    live_dataset_hash,
                    "==",
                    expected_live_dataset_hash,
                    passed=is_sha256(live_dataset_hash)
                    and is_sha256(expected_live_dataset_hash)
                    and live_dataset_hash == expected_live_dataset_hash,
                ),
                gate_result("harness_live_status", live_status, "==", "pass"),
                gate_result(
                    "harness_live_case_count",
                    live_case_count,
                    ">=",
                    gates.get("harness_live_case_count_min", 30),
                ),
                gate_result(
                    "harness_live_completed_rate",
                    live_completed_rate,
                    ">=",
                    gates.get("harness_live_completed_rate_min", 1.0),
                ),
                gate_result(
                    "harness_live_safe_terminal_rate",
                    live_safe_terminal_rate,
                    ">=",
                    gates.get("harness_live_safe_terminal_rate_min", 1.0),
                ),
                gate_result(
                    "harness_live_unsafe_result_count",
                    live_unsafe,
                    "<=",
                    gates.get("harness_live_unsafe_result_count_max", 0),
                ),
            ]
        )
    sections = {
        "rag": rag_payload or not_run("RAG evaluation was not supplied"),
        "trace": trace_payload or not_run(
            "Trace integrity evaluation was not supplied"
        ),
        "completion": harness_payload or not_run(
            "Harness completion evaluation was not supplied"
        ),
        "live_completion": harness_live_payload or not_run(
            "Live DEEP Harness evaluation was not supplied"
        ),
        "end_to_end": dialog_payload or not_run(
            "End-to-end dialog and LLM-as-Judge evaluation was not supplied"
        ),
    }
    missing_sections = [
        name
        for name, section in sections.items()
        if section.get("status") == "not_run"
    ]
    if any(gate["status"] == "failed" for gate in gate_results):
        status = "failed"
    elif missing_sections:
        status = "incomplete"
    else:
        status = "passed"
    return {
        "schema_version": SCHEMA_VERSION,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "status": status,
        "planner": planner,
        **sections,
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
