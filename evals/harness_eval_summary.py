"""Evaluate Harness completion evidence against its independent standards."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from eval_utils import gate_result, is_sha256


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


def evaluate_harness(
    harness_payload: dict[str, Any] | None = None,
    harness_live_payload: dict[str, Any] | None = None,
    *,
    gates: dict[str, Any] | None = None,
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    if gates is None:
        gates = json.loads(Path(__file__).with_name("harness_eval_gates.json").read_text(encoding="utf-8"))
    gate_results: list[dict[str, Any]] = []
    expected_policy_id = gates["harness_policy_id_expected"]
    expected_policy_version = gates["harness_policy_version_expected"]
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
                    gates["harness_case_count_min"],
                ),
                gate_result(
                    "harness_exact_accuracy",
                    harness_accuracy,
                    ">=",
                    gates["harness_exact_accuracy_min"],
                ),
                gate_result(
                    "harness_decision_contract_exact_match_rate",
                    harness_decision_rate,
                    ">=",
                    gates["harness_decision_contract_exact_match_rate_min"],
                ),
                gate_result(
                    "harness_violation_exact_match_rate",
                    harness_violation_rate,
                    ">=",
                    gates["harness_violation_exact_match_rate_min"],
                ),
                gate_result(
                    "harness_recovery_exact_match_rate",
                    harness_recovery_rate,
                    ">=",
                    gates["harness_recovery_exact_match_rate_min"],
                ),
                gate_result(
                    "harness_unsafe_pass_count",
                    harness_unsafe,
                    "<=",
                    gates["harness_unsafe_pass_count_max"],
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
    quality: dict[str, Any] = {}
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
        expected_live_dataset_hash = gates["harness_live_dataset_sha256_expected"]
        live_case_count = int(live_metrics.get("case_count", 0))
        live_completed = int(live_metrics.get("completed_count", 0))
        live_completed_rate = (
            live_completed / live_case_count if live_case_count > 0 else 0.0
        )
        live_safe_terminal_rate = float(
            live_metrics.get("safe_terminal_rate", 0.0)
        )
        live_unsafe = int(live_metrics.get("unsafe_result_count", 0))
        live_task_success_rate = live_metrics.get("task_success_rate")
        live_generated_at = str(harness_live_payload.get("generated_at") or "").strip()
        live_planned_cases = int(harness_live_payload.get("planned_case_count", 0))
        live_execution_cases = int(harness_live_payload.get("execution_case_count", 0))
        quality_eligible = (
            live_engine == "stocksage-live-http"
            and live_schema == "harness_live_eval_v1"
            and harness_live_payload.get("run_mode") == "release"
            and harness_live_payload.get("run_state") == "complete"
            and live_case_count > 0
            and live_case_count == live_planned_cases == live_execution_cases
            and is_sha256(live_dataset_hash)
            and live_dataset_hash == expected_live_dataset_hash
            and live_policy_ids == [str(expected_policy_id)]
            and live_policy_versions == [str(expected_policy_version)]
            and live_generated_at
            and isinstance(live_task_success_rate, (int, float))
            and not isinstance(live_task_success_rate, bool)
            and 0.0 <= float(live_task_success_rate) <= 1.0
        )
        if quality_eligible:
            quality = {
                "evidence_kind": "GOLDEN_SET",
                "dataset_version": live_dataset_hash,
                "evaluator_version": live_schema,
                "generated_at": live_generated_at,
                "metrics": {
                    "task_success_rate": {
                        "value": float(live_task_success_rate),
                        "sample_count": live_case_count,
                    }
                },
            }
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
                    gates["harness_live_case_count_min"],
                ),
                gate_result(
                    "harness_live_completed_rate",
                    live_completed_rate,
                    ">=",
                    gates["harness_live_completed_rate_min"],
                ),
                gate_result(
                    "harness_live_safe_terminal_rate",
                    live_safe_terminal_rate,
                    ">=",
                    gates["harness_live_safe_terminal_rate_min"],
                ),
                gate_result(
                    "harness_live_unsafe_result_count",
                    live_unsafe,
                    "<=",
                    gates["harness_live_unsafe_result_count_max"],
                ),
            ]
        )
    return gate_results, quality
