"""Build and gate the stable agent_eval_v1 report contract."""

from __future__ import annotations

import json
import math
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from eval_utils import gate_result, is_sha256
from harness_eval_summary import evaluate_harness


SCHEMA_VERSION = "agent_eval_v1"
PLANNER_V2_SUMMARY_FIELDS = (
    "requestedCases",
    "skippedLiveOnlyCases",
    "intentEvaluatedCases",
    "intentAccuracy",
    "contextCases",
    "contextCaseAccuracy",
    "contextResolutionCases",
    "contextResolutionAccuracy",
    "nonFallbackCases",
    "nonFallbackRouteAccuracy",
    "llmSignalCases",
    "llmSignalAccuracy",
    "fallbackCases",
    "fallbackRate",
    "invalidRawRouteCases",
    "invalidRawRouteRate",
)
PLANNER_V2_RESULT_FIELDS = (
    "expectedFineIntent",
    "actualFineIntent",
    "fineIntentMatched",
    "expectedDecisionSource",
    "decisionSourceMatched",
    "requireNoFallback",
    "noFallbackMatched",
    "routeMatched",
    "contextCase",
    "fallback",
    "executionGuarded",
    "rawRouteValid",
    "rawRouteMatched",
    "expectedResolvedQueryContains",
    "actualResolvedQuery",
    "contextResolutionMatched",
)


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
    missing_v2_fields = [
        field for field in PLANNER_V2_SUMMARY_FIELDS if field not in payload
    ]
    if not isinstance(results, list):
        missing_v2_fields.append("results[]")
        results = []
    else:
        for index, result in enumerate(results):
            if not isinstance(result, dict):
                missing_v2_fields.append(f"results[{index}]")
                continue
            missing_v2_fields.extend(
                f"results[{index}].{field}"
                for field in PLANNER_V2_RESULT_FIELDS
                if field not in result
            )
    normalized = {
        "schema_version": payload.get("schemaVersion"),
        "dataset_sha256": payload.get("datasetSha256"),
        "status": payload.get("status", "failed"),
        "mode": payload.get("mode"),
        "requested_cases": int(
            payload.get("requestedCases", payload.get("totalCases", 0))
        ),
        "total_cases": int(payload.get("totalCases", 0)),
        "skipped_live_only_cases": int(payload.get("skippedLiveOnlyCases", 0)),
        "passed_cases": int(payload.get("passedCases", 0)),
        "critical_failures": int(payload.get("criticalFailures", 0)),
        "route_accuracy": float(payload.get("routeAccuracy", 0.0)),
        "macro_f1": float(payload.get("macroF1", 0.0)),
        "per_route": per_route,
        "required_action_recall": float(payload.get("requiredActionRecall", 0.0)),
        "forbidden_action_rate": float(payload.get("forbiddenActionRate", 0.0)),
        "executable_rate": float(payload.get("executableRate", 0.0)),
        "intent_evaluated_cases": int(payload.get("intentEvaluatedCases", 0)),
        "intent_accuracy": float(payload.get("intentAccuracy", 0.0)),
        "context_cases": int(payload.get("contextCases", 0)),
        "context_case_accuracy": float(payload.get("contextCaseAccuracy", 0.0)),
        "context_resolution_cases": int(payload.get("contextResolutionCases", 0)),
        "context_resolution_accuracy": float(
            payload.get("contextResolutionAccuracy", 0.0)
        ),
        "non_fallback_cases": int(payload.get("nonFallbackCases", 0)),
        "non_fallback_route_accuracy": float(
            payload.get("nonFallbackRouteAccuracy", 0.0)
        ),
        "llm_signal_cases": int(payload.get("llmSignalCases", 0)),
        "llm_signal_accuracy": float(payload.get("llmSignalAccuracy", 0.0)),
        "fallback_cases": int(payload.get("fallbackCases", 0)),
        "fallback_rate": float(payload.get("fallbackRate", 0.0)),
        "invalid_raw_route_cases": int(payload.get("invalidRawRouteCases", 0)),
        "invalid_raw_route_rate": float(payload.get("invalidRawRouteRate", 0.0)),
        "v2_fields_complete": not missing_v2_fields,
        "missing_v2_fields": missing_v2_fields,
        "duration_ms": int(payload.get("durationMs", 0)),
        "p95_latency_ms": percentile(
            [result.get("durationMs", 0) for result in results], 0.95
        ),
        "results": results,
        "intent_calibration": intent_calibration(results),
    }
    return normalized


def intent_calibration(results: list[dict], stratify: bool = True) -> dict:
    """Describe observed candidates; neither source ablation nor calibrated probabilities."""
    rows = [row for row in results if isinstance(row, dict)]
    known = [row for row in rows if row.get("executable") is True
             and type(row.get("actualClarification")) is bool]
    labeled = [row for row in rows if type(row.get("expectedClarification")) is bool]
    evaluated = [row for row in known if type(row.get("expectedClarification")) is bool]
    accepted = [row for row in known if row["actualClarification"] is False]
    comparable = [row for row in accepted if row.get("executionGuarded") is False
                  and row.get("fallback") is False and row.get("expectedClarification") is not True
                  and row.get("expectedRoute") in {"DIRECT", "FUNDAMENTALS", "MARKET", "NEWS", "DEEP"}]
    comparable_rows = {id(row) for row in comparable}

    def rate(numerator, denominator):
        return numerator / denominator if denominator else None

    sources = {}
    invalid = 0
    for row in known:
        signals = row.get("signalDiagnostics") or []
        if not isinstance(signals, list):
            invalid += 1
            continue
        seen = set()
        for signal in signals:
            if (not isinstance(signal, dict)
                    or signal.get("source") not in {"LLM", "EMBEDDING", "PATTERN", "NGRAM", "FALLBACK"}
                    or signal.get("targetRoute") not in {"DIRECT", "FUNDAMENTALS", "MARKET", "NEWS", "DEEP"}
                    or type(signal.get("confidence")) not in (int, float)
                    or not math.isfinite(signal["confidence"]) or not 0 <= signal["confidence"] <= 1
                    or signal["source"] in seen):
                invalid += 1
                continue
            seen.add(signal["source"])
            summary = sources.setdefault(signal["source"], {"observed_case_count": 0,
                "route_comparable_case_count": 0, "signal_route_match_count": 0,
                "fusion_corrects_signal_count": 0, "fusion_loses_signal_count": 0})
            summary["observed_case_count"] += 1
            if id(row) not in comparable_rows or signal["source"] == "FALLBACK":
                continue
            correct = signal["targetRoute"] == row["expectedRoute"]
            fused_correct = row.get("actualRoute") == row["expectedRoute"]
            summary["route_comparable_case_count"] += 1
            summary["signal_route_match_count"] += int(correct)
            summary["fusion_corrects_signal_count"] += int(fused_correct and not correct)
            summary["fusion_loses_signal_count"] += int(correct and not fused_correct)
    for summary in sources.values():
        count = summary["route_comparable_case_count"]
        summary["signal_route_agreement_rate"] = rate(summary["signal_route_match_count"], count)
        summary["paired_fused_minus_signal_accuracy"] = rate(
            summary["fusion_corrects_signal_count"] - summary["fusion_loses_signal_count"], count)
    bins = []
    for lower, upper in ((0, 0.25), (0.25, 0.5), (0.5, 0.75), (0.75, 1.0)):
        scored = [row for row in comparable if type(row.get("confidence")) in (int, float)
                  and math.isfinite(row["confidence"])
                  and lower <= row["confidence"] and (row["confidence"] < upper
                       or upper == 1.0 and row["confidence"] == 1.0)]
        bins.append({"lower": lower, "upper": upper, "upper_inclusive": upper == 1.0,
                     "case_count": len(scored), "route_accuracy": rate(
                         sum(row.get("actualRoute") == row["expectedRoute"] for row in scored), len(scored))})
    summary = {"status": "NO_DATA" if not known else "OBSERVED" if len(known) == len(rows) else "PARTIAL",
               "scope": "PLANNER_DECISIONS_NOT_TOOL_EXECUTIONS",
               "clarification_scope": "ROUTING_METADATA_ONLY",
               "score_semantics": "HEURISTIC_NOT_CALIBRATED_PROBABILITY",
               "case_count": len(rows), "decision_observed_count": len(known),
               "decision_missing_count": len(rows) - len(known),
               "clarification_labeled_count": len(labeled), "clarification_evaluated_count": len(evaluated),
               "clarification_accuracy": rate(sum(row["expectedClarification"] == row["actualClarification"]
                                                   for row in evaluated), len(evaluated)),
               "clarification_rate": rate(len(known) - len(accepted), len(known)),
               "nonclarified_route_comparable_count": len(comparable),
               "nonclarified_route_error_rate": rate(sum(row.get("actualRoute") != row["expectedRoute"]
                                                          for row in comparable), len(comparable)),
               "invalid_signal_count": invalid,
               "signal_observation_status": "NO_DATA" if not sources else "PARTIAL" if invalid else "OBSERVED",
               "sources": sources, "score_bins": bins}
    if stratify:
        summary["expected_route_strata"] = {
            route: intent_calibration([row for row in rows if (row.get("expectedRoute") or "UNKNOWN") == route], False)
            for route in sorted({row.get("expectedRoute") or "UNKNOWN" for row in rows})}
    return summary


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
        (
            "planner_schema_version",
            planner["schema_version"],
            "==",
            gates["planner_schema_version_expected"],
        ),
        ("planner_status", planner["status"], "==", "passed"),
        (
            "planner_requested_cases",
            planner["requested_cases"],
            "==",
            gates["planner_requested_cases_expected"],
        ),
        (
            "planner_results_count",
            len(planner["results"]),
            "==",
            planner["total_cases"],
        ),
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
    results = [gate_result(name, value, operator, threshold) for name, value, operator, threshold in checks]
    expected_dataset_hash = gates["planner_dataset_sha256_expected"]
    results.append(
        gate_result(
            "planner_dataset_sha256",
            planner["dataset_sha256"],
            "==",
            expected_dataset_hash,
            passed=is_sha256(planner["dataset_sha256"])
            and is_sha256(expected_dataset_hash)
            and planner["dataset_sha256"] == expected_dataset_hash,
        )
    )
    if planner["mode"] == "LIVE_COORDINATOR":
        results.extend(
            [
                gate_result(
                    "planner_total_cases",
                    planner["total_cases"],
                    "==",
                    gates["planner_live_total_cases_expected"],
                ),
                gate_result(
                    "planner_skipped_live_only_cases",
                    planner["skipped_live_only_cases"],
                    "==",
                    gates["planner_live_skipped_live_only_cases_expected"],
                ),
                gate_result(
                    "planner_context_cases",
                    planner["context_cases"],
                    "==",
                    gates["planner_live_context_cases_expected"],
                ),
                gate_result(
                    "planner_intent_evaluated_cases",
                    planner["intent_evaluated_cases"],
                    "==",
                    gates["planner_live_intent_cases_expected"],
                ),
                gate_result(
                    "planner_context_resolution_cases",
                    planner["context_resolution_cases"],
                    "==",
                    gates["planner_live_context_resolution_cases_expected"],
                ),
                gate_result(
                    "planner_v2_fields_complete",
                    planner["v2_fields_complete"],
                    "==",
                    True,
                ),
            ]
        )
    elif planner["mode"] == "DETERMINISTIC":
        results.extend(
            [
                gate_result(
                    "planner_total_cases",
                    planner["total_cases"],
                    "==",
                    gates["planner_deterministic_total_cases_expected"],
                ),
                gate_result(
                    "planner_skipped_live_only_cases",
                    planner["skipped_live_only_cases"],
                    "==",
                    gates["planner_deterministic_skipped_live_only_cases_expected"],
                ),
            ]
        )
    else:
        results.append(
            gate_result(
                "planner_mode",
                planner["mode"],
                "in",
                ["DETERMINISTIC", "LIVE_COORDINATOR"],
                passed=False,
            )
        )
    optional_checks = [
        (
            "intent_accuracy",
            planner["intent_accuracy"],
            ">=",
            "intent_accuracy_min",
            planner["intent_evaluated_cases"] > 0,
            "no evaluated case supplied expectedFineIntent",
        ),
        (
            "context_case_accuracy",
            planner["context_case_accuracy"],
            ">=",
            "context_case_accuracy_min",
            planner["context_cases"] > 0,
            "no evaluated case supplied recentTurns",
        ),
        (
            "context_resolution_accuracy",
            planner["context_resolution_accuracy"],
            ">=",
            "context_resolution_accuracy_min",
            planner["context_resolution_cases"] > 0,
            "no evaluated case supplied expectedResolvedQueryContains",
        ),
        (
            "non_fallback_route_accuracy",
            planner["non_fallback_route_accuracy"],
            ">=",
            "non_fallback_route_accuracy_min",
            planner["non_fallback_cases"] > 0,
            "no non-fallback routing result was observed",
        ),
        (
            "llm_signal_accuracy",
            planner["llm_signal_accuracy"],
            ">=",
            "llm_signal_accuracy_min",
            planner["llm_signal_cases"] > 0,
            "no valid raw LLM route signal was observed",
        ),
        (
            "fallback_rate",
            planner["fallback_rate"],
            "<=",
            "fallback_rate_max",
            planner["mode"] == "LIVE_COORDINATOR",
            "fallback rate is not a live-LLM gate in DETERMINISTIC mode",
        ),
        (
            "invalid_raw_route_rate",
            planner["invalid_raw_route_rate"],
            "<=",
            "invalid_raw_route_rate_max",
            planner["mode"] == "LIVE_COORDINATOR",
            "raw LLM route validity is not gated in DETERMINISTIC mode",
        ),
    ]
    for metric, value, operator, gate_key, applicable, reason in optional_checks:
        if gate_key not in gates:
            continue
        if not applicable:
            results.append({"metric": metric, "status": "not_run", "reason": reason})
            continue
        results.append(gate_result(metric, value, operator, gates[gate_key]))
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
    *,
    harness_gates: dict[str, Any] | None = None,
) -> dict[str, Any]:
    planner = normalize_planner(planner_payload)
    baseline = normalize_planner(baseline_payload) if baseline_payload else None
    delta = metric_delta(planner, baseline)
    gate_results = evaluate_gates(planner, delta, gates)
    planner["gate_status"] = (
        "failed"
        if any(gate["status"] == "failed" for gate in gate_results)
        else "passed"
    )
    harness_checks, quality = evaluate_harness(
        harness_payload, harness_live_payload, gates=harness_gates,
    )
    gate_results.extend(harness_checks)
    rag_section = dict(rag_payload) if rag_payload else not_run("RAG evaluation was not supplied")
    if rag_payload:
        evaluation = rag_payload.get("gate_evaluation")
        rag_section["status"] = (
            {"pass": "passed", "fail": "failed"}.get(str(evaluation.get("status")), "incomplete")
            if isinstance(evaluation, dict) and evaluation.get("schema_version") == "rag_gates_v1"
            else "incomplete"
        )
    sections = {
        "rag": rag_section,
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
        if section.get("status") in {"not_run", "incomplete", "missing"}
    ]
    failed_sections = [
        name
        for name, section in sections.items()
        if str(section.get("status", "")).strip().lower() in {"fail", "failed"}
    ]
    if any(gate["status"] == "failed" for gate in gate_results) or failed_sections:
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
        "quality": quality,
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
    if planner["fallback_rate"] > 0:
        items.append("LIVE 路由出现确定性回退，不能把 fallback 的正确路由计作 LLM 识别成功")
    if planner["invalid_raw_route_rate"] > 0:
        items.append("路由模型返回了非法或空 rawRoute，检查结构化输出约束和解析失败样例")
    if delta.get("status") == "completed" and any(
        float(value) < -0.05 for value in delta.get("metrics", {}).values()
    ):
        items.append("相比 routing baseline 出现超过 5% 的指标退化，检查最近的 prompt 或路由规则变更")
    return items or ["路由分类、固定动作和回归指标均达标"]


def load_json(path: str | Path) -> dict[str, Any]:
    return json.loads(Path(path).read_text(encoding="utf-8"))
