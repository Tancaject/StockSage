import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import run_agent_eval
from agent_eval_summary import build_report


GATES = {
    "planner_schema_version_expected": "planner_eval_v2",
    "planner_requested_cases_expected": 2,
    "planner_live_total_cases_expected": 2,
    "planner_live_skipped_live_only_cases_expected": 0,
    "planner_live_context_cases_expected": 0,
    "planner_live_intent_cases_expected": 0,
    "planner_live_context_resolution_cases_expected": 0,
    "planner_deterministic_total_cases_expected": 2,
    "planner_deterministic_skipped_live_only_cases_expected": 0,
    "planner_dataset_sha256_expected": "d" * 64,
    "route_accuracy_min": 0.95,
    "macro_f1_min": 0.95,
    "required_action_recall_min": 0.98,
    "forbidden_action_rate_max": 0.0,
    "critical_failures_max": 0,
    "executable_rate_min": 1.0,
    "intent_accuracy_min": 0.95,
    "context_case_accuracy_min": 0.95,
    "context_resolution_accuracy_min": 0.95,
    "non_fallback_route_accuracy_min": 0.95,
    "llm_signal_accuracy_min": 0.95,
    "fallback_rate_max": 0.0,
    "invalid_raw_route_rate_max": 0.0,
    "baseline_delta_max_drop": 0.02,
    "p95_latency_ratio_max": 1.2,
    "harness_policy_id_expected": "deep-equity-v1",
    "harness_policy_version_expected": 3,
    "harness_case_count_min": 60,
    "harness_exact_accuracy_min": 1.0,
    "harness_decision_contract_exact_match_rate_min": 1.0,
    "harness_violation_exact_match_rate_min": 1.0,
    "harness_recovery_exact_match_rate_min": 1.0,
    "harness_unsafe_pass_count_max": 0,
    "harness_live_case_count_min": 30,
    "harness_live_dataset_sha256_expected": "b" * 64,
    "harness_live_completed_rate_min": 1.0,
    "harness_live_safe_terminal_rate_min": 1.0,
    "harness_live_unsafe_result_count_max": 0,
}


def planner_result(duration_ms=10, **overrides):
    result = {
        "durationMs": duration_ms,
        "expectedFineIntent": None,
        "actualFineIntent": "",
        "fineIntentMatched": True,
        "expectedDecisionSource": None,
        "decisionSourceMatched": True,
        "requireNoFallback": False,
        "noFallbackMatched": True,
        "routeMatched": True,
        "contextCase": False,
        "fallback": False,
        "rawRouteValid": True,
        "rawRouteMatched": True,
        "expectedResolvedQueryContains": None,
        "actualResolvedQuery": "",
        "contextResolutionMatched": True,
    }
    result.update(overrides)
    return result


def planner(
    route=1.0,
    recall=1.0,
    forbidden=0.0,
    executable=1.0,
    *,
    mode="LIVE_COORDINATOR",
    total_cases=2,
    requested_cases=None,
    skipped_live_only_cases=0,
    dataset_sha256="d" * 64,
):
    requested_cases = total_cases if requested_cases is None else requested_cases
    return {
        "schemaVersion": "planner_eval_v2",
        "datasetSha256": dataset_sha256,
        "status": "passed",
        "mode": mode,
        "requestedCases": requested_cases,
        "totalCases": total_cases,
        "skippedLiveOnlyCases": skipped_live_only_cases,
        "passedCases": total_cases,
        "criticalFailures": 0,
        "routeAccuracy": route,
        "macroF1": route,
        "perRoute": {
            "DIRECT": {
                "precision": route,
                "recall": route,
                "f1": route,
                "expectedCount": 1,
                "predictedCount": 1,
            }
        },
        "requiredActionRecall": recall,
        "forbiddenActionRate": forbidden,
        "executableRate": executable,
        "intentEvaluatedCases": 0,
        "intentAccuracy": 0.0,
        "contextCases": 0,
        "contextCaseAccuracy": 0.0,
        "contextResolutionCases": 0,
        "contextResolutionAccuracy": 0.0,
        "nonFallbackCases": total_cases,
        "nonFallbackRouteAccuracy": route,
        "llmSignalCases": total_cases,
        "llmSignalAccuracy": route,
        "fallbackCases": 0,
        "fallbackRate": 0.0,
        "invalidRawRouteCases": 0,
        "invalidRawRouteRate": 0.0,
        "durationMs": 25,
        "results": [planner_result(10 + index * 5) for index in range(total_cases)],
    }


def gates_for_cases(cases, *, mode="DETERMINISTIC", skipped_live_only_cases=0):
    gates = dict(GATES)
    case_count = len(cases)
    gates["planner_requested_cases_expected"] = case_count + skipped_live_only_cases
    gates["planner_dataset_sha256_expected"] = run_agent_eval.cases_sha256(cases)
    if mode == "DETERMINISTIC":
        gates["planner_deterministic_total_cases_expected"] = case_count
        gates["planner_deterministic_skipped_live_only_cases_expected"] = (
            skipped_live_only_cases
        )
    else:
        gates["planner_live_total_cases_expected"] = case_count
        gates["planner_live_skipped_live_only_cases_expected"] = (
            skipped_live_only_cases
        )
    return gates


def harness_result(**overrides):
    result = {
        "schema_version": "harness_eval_v1",
        "case_schema_version": "harness_golden_case_v2",
        "engine": "java-production-policy",
        "policy_id": "deep-equity-v1",
        "policy_version": 3,
        "status": "pass",
        "case_count": 60,
        "exact_accuracy": 1.0,
        "decision_contract_exact_match_rate": 1.0,
        "violation_exact_match_rate": 1.0,
        "recovery_exact_match_rate": 1.0,
        "unsafe_pass_count": 0,
        "coverage": {"status": "pass"},
        "dataset_sha256": "a" * 64,
    }
    result.update(overrides)
    return result


def live_harness_result(**overrides):
    result = {
        "schema": "harness_live_eval_v1",
        "engine": "stocksage-live-http",
        "status": "pass",
        "dataset_sha256": "b" * 64,
        "policy_ids": ["deep-equity-v1"],
        "policy_versions": ["3"],
        "metrics": {
            "case_count": 30,
            "completed_count": 30,
            "safe_terminal_rate": 1.0,
            "unsafe_result_count": 0,
        },
    }
    result.update(overrides)
    return result


def complete_evidence():
    return {
        "rag_payload": {"status": "passed"},
        "trace_payload": {"status": "passed"},
        "dialog_payload": {"status": "passed"},
        "harness_payload": harness_result(),
        "harness_live_payload": live_harness_result(),
    }


class AgentEvalSummaryTest(unittest.TestCase):
    def test_missing_critical_sections_is_incomplete_and_preserves_not_run(self):
        report = build_report(planner(), GATES, baseline_payload=planner())
        self.assertEqual("agent_eval_v1", report["schema_version"])
        self.assertEqual("incomplete", report["status"])
        self.assertEqual("not_run", report["rag"]["status"])
        self.assertEqual("not_run", report["trace"]["status"])
        self.assertEqual("not_run", report["completion"]["status"])
        self.assertEqual("not_run", report["live_completion"]["status"])
        self.assertEqual("not_run", report["end_to_end"]["status"])
        self.assertEqual(1.0, report["planner"]["macro_f1"])
        self.assertEqual("passed", report["planner"]["gate_status"])

    def test_v2_intent_context_and_source_quality_metrics_are_hard_gated(self):
        payload = planner()
        payload.update(
            {
                "intentEvaluatedCases": 2,
                "intentAccuracy": 0.5,
                "contextCases": 2,
                "contextCaseAccuracy": 0.5,
                "contextResolutionCases": 2,
                "contextResolutionAccuracy": 0.5,
                "fallbackCases": 1,
                "fallbackRate": 0.5,
                "invalidRawRouteCases": 1,
                "invalidRawRouteRate": 0.5,
            }
        )

        report = build_report(payload, GATES)

        failed = {gate["metric"] for gate in report["gates"] if gate["status"] == "failed"}
        self.assertTrue(
            {
                "intent_accuracy",
                "context_case_accuracy",
                "context_resolution_accuracy",
                "fallback_rate",
                "invalid_raw_route_rate",
            }.issubset(failed)
        )
        self.assertEqual("failed", report["planner"]["gate_status"])

    def test_final_fused_route_and_raw_llm_signal_have_separate_accuracy(self):
        payload = planner()
        payload["nonFallbackRouteAccuracy"] = 1.0
        payload["llmSignalAccuracy"] = 0.5

        report = build_report(payload, GATES)

        statuses = {gate["metric"]: gate["status"] for gate in report["gates"]}
        self.assertEqual("passed", statuses["non_fallback_route_accuracy"])
        self.assertEqual("failed", statuses["llm_signal_accuracy"])

    def test_v1_schema_fails_even_when_optional_v2_gates_are_not_run(self):
        payload = planner()
        payload["schemaVersion"] = "planner_eval_v1"
        for field in (
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
        ):
            payload.pop(field)
        payload["mode"] = "DETERMINISTIC"

        report = build_report(payload, GATES)

        optional = {
            gate["metric"]: gate["status"]
            for gate in report["gates"]
            if gate["metric"] in {
                "intent_accuracy",
                "context_case_accuracy",
                "context_resolution_accuracy",
                "non_fallback_route_accuracy",
                "llm_signal_accuracy",
                "fallback_rate",
                "invalid_raw_route_rate",
            }
        }
        self.assertTrue(optional)
        self.assertEqual({"not_run"}, set(optional.values()))
        self.assertEqual("failed", report["planner"]["gate_status"])
        statuses = {gate["metric"]: gate["status"] for gate in report["gates"]}
        self.assertEqual("failed", statuses["planner_schema_version"])

    def test_live_payload_fails_when_any_v2_summary_or_result_field_is_missing(self):
        payload = planner()
        payload.pop("fallbackRate")
        payload["results"][0].pop("rawRouteValid")

        report = build_report(payload, GATES)

        self.assertFalse(report["planner"]["v2_fields_complete"])
        self.assertIn("fallbackRate", report["planner"]["missing_v2_fields"])
        self.assertIn(
            "results[0].rawRouteValid",
            report["planner"]["missing_v2_fields"],
        )
        statuses = {gate["metric"]: gate["status"] for gate in report["gates"]}
        self.assertEqual("failed", statuses["planner_v2_fields_complete"])
        self.assertEqual("failed", report["planner"]["gate_status"])

    def test_rejects_wrong_case_count_or_planner_dataset_hash(self):
        payload = planner(
            total_cases=1,
            requested_cases=1,
            dataset_sha256="c" * 64,
        )

        report = build_report(payload, GATES)

        statuses = {gate["metric"]: gate["status"] for gate in report["gates"]}
        self.assertEqual("failed", statuses["planner_requested_cases"])
        self.assertEqual("failed", statuses["planner_total_cases"])
        self.assertEqual("failed", statuses["planner_dataset_sha256"])
        self.assertEqual("failed", report["planner"]["gate_status"])

    def test_supplied_failed_section_fails_unified_report(self):
        evidence = complete_evidence()
        evidence["rag_payload"] = {"status": "failed"}

        report = build_report(
            planner(),
            GATES,
            baseline_payload=planner(),
            **evidence,
        )

        self.assertEqual("failed", report["status"])

    def test_fails_when_any_hard_gate_fails(self):
        report = build_report(planner(route=0.8), GATES, baseline_payload=planner())
        self.assertEqual("failed", report["status"])
        failed = {gate["metric"] for gate in report["gates"] if gate["status"] == "failed"}
        self.assertIn("route_accuracy", failed)
        self.assertIn("baseline_max_metric_drop", failed)

    def test_reports_per_route_metrics_and_actionable_recommendations(self):
        report = build_report(planner(route=0.8), GATES)
        self.assertEqual(0.8, report["planner"]["per_route"]["DIRECT"]["f1"])
        self.assertTrue(any("低 F1 路由" in item for item in report["recommendations"]))

    def test_harness_completion_section_is_hard_gated_when_supplied(self):
        report = build_report(
            planner(),
            GATES,
            harness_payload={
                "status": "fail",
                "schema_version": "harness_eval_v1",
                "engine": "python-rule-mirror",
                "exact_accuracy": 0.9,
                "unsafe_pass_count": 1,
            },
        )
        self.assertEqual("failed", report["status"])
        failed = {gate["metric"] for gate in report["gates"] if gate["status"] == "failed"}
        self.assertIn("harness_engine", failed)
        self.assertIn("harness_exact_accuracy", failed)
        self.assertIn("harness_unsafe_pass_count", failed)

    def test_accepts_harness_result_only_from_java_production_policy(self):
        report = build_report(
            planner(),
            GATES,
            harness_payload=harness_result(),
        )
        harness_gates = {
            gate["metric"]: gate["status"]
            for gate in report["gates"]
            if gate["metric"].startswith("harness_")
        }
        self.assertEqual("incomplete", report["status"])
        self.assertEqual(
            {
                "harness_engine": "passed",
                "harness_schema": "passed",
                "harness_case_schema": "passed",
                "harness_policy_id": "passed",
                "harness_policy_version": "passed",
                "harness_status": "passed",
                "harness_case_count": "passed",
                "harness_exact_accuracy": "passed",
                "harness_decision_contract_exact_match_rate": "passed",
                "harness_violation_exact_match_rate": "passed",
                "harness_recovery_exact_match_rate": "passed",
                "harness_unsafe_pass_count": "passed",
                "harness_coverage_status": "passed",
                "harness_dataset_sha256": "passed",
            },
            harness_gates,
        )

    def test_complete_qualified_evidence_passes(self):
        report = build_report(
            planner(),
            GATES,
            baseline_payload=planner(),
            **complete_evidence(),
        )
        self.assertEqual("passed", report["status"])
        self.assertTrue(
            all(gate["status"] != "failed" for gate in report["gates"])
        )

    def test_completion_gate_contract_preserves_order_schema_and_thresholds(self):
        evidence = complete_evidence()
        evidence["harness_payload"] = harness_result(policy_version="3")
        report = build_report(
            planner(),
            GATES,
            baseline_payload=planner(),
            **evidence,
        )

        self.assertEqual(
            [
                "schema_version",
                "generated_at",
                "status",
                "planner",
                "rag",
                "trace",
                "completion",
                "live_completion",
                "end_to_end",
                "baseline_delta",
                "gates",
                "recommendations",
            ],
            list(report),
        )
        self.assertEqual(
            [
                "planner_schema_version",
                "planner_status",
                "planner_requested_cases",
                "planner_results_count",
                "route_accuracy",
                "macro_f1",
                "required_action_recall",
                "forbidden_action_rate",
                "critical_failures",
                "executable_rate",
                "planner_dataset_sha256",
                "planner_total_cases",
                "planner_skipped_live_only_cases",
                "planner_context_cases",
                "planner_intent_evaluated_cases",
                "planner_context_resolution_cases",
                "planner_v2_fields_complete",
                "intent_accuracy",
                "context_case_accuracy",
                "context_resolution_accuracy",
                "non_fallback_route_accuracy",
                "llm_signal_accuracy",
                "fallback_rate",
                "invalid_raw_route_rate",
                "baseline_max_metric_drop",
                "p95_latency_ratio",
            ],
            [gate["metric"] for gate in report["gates"][:26]],
        )

        fields = ("metric", "value", "operator", "threshold", "status")
        completion_gates = report["gates"][26:]
        self.assertTrue(all(list(gate) == list(fields) for gate in completion_gates))
        self.assertEqual(
            [
                (
                    "harness_engine",
                    "java-production-policy",
                    "==",
                    "java-production-policy",
                    "passed",
                ),
                (
                    "harness_schema",
                    "harness_eval_v1",
                    "==",
                    "harness_eval_v1",
                    "passed",
                ),
                (
                    "harness_case_schema",
                    "harness_golden_case_v2",
                    "==",
                    "harness_golden_case_v2",
                    "passed",
                ),
                (
                    "harness_policy_id",
                    "deep-equity-v1",
                    "==",
                    "deep-equity-v1",
                    "passed",
                ),
                ("harness_policy_version", "3", "==", 3, "passed"),
                ("harness_status", "pass", "==", "pass", "passed"),
                ("harness_case_count", 60, ">=", 60, "passed"),
                ("harness_exact_accuracy", 1.0, ">=", 1.0, "passed"),
                (
                    "harness_decision_contract_exact_match_rate",
                    1.0,
                    ">=",
                    1.0,
                    "passed",
                ),
                (
                    "harness_violation_exact_match_rate",
                    1.0,
                    ">=",
                    1.0,
                    "passed",
                ),
                (
                    "harness_recovery_exact_match_rate",
                    1.0,
                    ">=",
                    1.0,
                    "passed",
                ),
                ("harness_unsafe_pass_count", 0, "<=", 0, "passed"),
                ("harness_coverage_status", "pass", "==", "pass", "passed"),
                (
                    "harness_dataset_sha256",
                    "a" * 64,
                    "matches",
                    "64 hexadecimal characters",
                    "passed",
                ),
                (
                    "harness_live_engine",
                    "stocksage-live-http",
                    "==",
                    "stocksage-live-http",
                    "passed",
                ),
                (
                    "harness_live_schema",
                    "harness_live_eval_v1",
                    "==",
                    "harness_live_eval_v1",
                    "passed",
                ),
                (
                    "harness_live_policy_ids",
                    ["deep-equity-v1"],
                    "==",
                    ["deep-equity-v1"],
                    "passed",
                ),
                (
                    "harness_live_policy_versions",
                    ["3"],
                    "==",
                    ["3"],
                    "passed",
                ),
                (
                    "harness_live_dataset_sha256",
                    "b" * 64,
                    "==",
                    "b" * 64,
                    "passed",
                ),
                ("harness_live_status", "pass", "==", "pass", "passed"),
                ("harness_live_case_count", 30, ">=", 30, "passed"),
                ("harness_live_completed_rate", 1.0, ">=", 1.0, "passed"),
                (
                    "harness_live_safe_terminal_rate",
                    1.0,
                    ">=",
                    1.0,
                    "passed",
                ),
                ("harness_live_unsafe_result_count", 0, "<=", 0, "passed"),
            ],
            [tuple(gate[field] for field in fields) for gate in completion_gates],
        )

    def test_rejects_old_offline_policy_version(self):
        report = build_report(
            planner(),
            GATES,
            harness_payload=harness_result(policy_version=1),
        )
        self.assertEqual("failed", report["status"])
        failed = {
            gate["metric"]
            for gate in report["gates"]
            if gate["status"] == "failed"
        }
        self.assertEqual({"harness_policy_version"}, failed)

    def test_rejects_failed_harness_even_when_all_metrics_pass(self):
        report = build_report(
            planner(),
            GATES,
            harness_payload=harness_result(status="fail"),
        )
        self.assertEqual("failed", report["status"])
        failed = {
            gate["metric"]
            for gate in report["gates"]
            if gate["status"] == "failed"
        }
        self.assertEqual({"harness_status"}, failed)

    def test_rejects_small_uncovered_or_unhashed_harness_set(self):
        report = build_report(
            planner(),
            GATES,
            harness_payload=harness_result(
                case_count=59,
                coverage={"status": "fail"},
                dataset_sha256="",
            ),
        )
        self.assertEqual("failed", report["status"])
        failed = {
            gate["metric"]
            for gate in report["gates"]
            if gate["status"] == "failed"
        }
        self.assertTrue(
            {
                "harness_case_count",
                "harness_coverage_status",
                "harness_dataset_sha256",
            }.issubset(failed)
        )

    def test_rejects_any_decision_contract_match_regression(self):
        report = build_report(
            planner(),
            GATES,
            harness_payload=harness_result(
                decision_contract_exact_match_rate=0.99,
                violation_exact_match_rate=0.98,
                recovery_exact_match_rate=0.97,
            ),
        )
        self.assertEqual("failed", report["status"])
        failed = {
            gate["metric"]
            for gate in report["gates"]
            if gate["status"] == "failed"
        }
        self.assertTrue(
            {
                "harness_decision_contract_exact_match_rate",
                "harness_violation_exact_match_rate",
                "harness_recovery_exact_match_rate",
            }.issubset(failed)
        )

    def test_rejects_wrong_case_schema_version(self):
        report = build_report(
            planner(),
            GATES,
            harness_payload=harness_result(case_schema_version="harness_golden_case_v1"),
        )
        self.assertEqual("failed", report["status"])
        failed = {
            gate["metric"]
            for gate in report["gates"]
            if gate["status"] == "failed"
        }
        self.assertIn("harness_case_schema", failed)

    def test_live_harness_result_is_hard_gated_when_supplied(self):
        report = build_report(
            planner(),
            GATES,
            harness_live_payload=live_harness_result(),
        )
        live_gates = {
            gate["metric"]: gate["status"]
            for gate in report["gates"]
            if gate["metric"].startswith("harness_live_")
        }
        self.assertEqual("incomplete", report["status"])
        self.assertTrue(live_gates)
        self.assertTrue(all(value == "passed" for value in live_gates.values()))

    def test_rejects_single_live_case(self):
        report = build_report(
            planner(),
            GATES,
            harness_live_payload=live_harness_result(
                metrics={
                    "case_count": 1,
                    "completed_count": 1,
                    "safe_terminal_rate": 1.0,
                    "unsafe_result_count": 0,
                }
            ),
        )
        self.assertEqual("failed", report["status"])
        failed = {
            gate["metric"]
            for gate in report["gates"]
            if gate["status"] == "failed"
        }
        self.assertEqual({"harness_live_case_count"}, failed)

    def test_rejects_wrong_live_policy_version(self):
        report = build_report(
            planner(),
            GATES,
            harness_live_payload=live_harness_result(policy_versions=["1"]),
        )
        self.assertEqual("failed", report["status"])
        failed = {
            gate["metric"]
            for gate in report["gates"]
            if gate["status"] == "failed"
        }
        self.assertEqual({"harness_live_policy_versions"}, failed)

    def test_rejects_live_result_without_dataset_hash(self):
        report = build_report(
            planner(),
            GATES,
            harness_live_payload=live_harness_result(dataset_sha256=""),
        )
        self.assertEqual("failed", report["status"])
        failed = {
            gate["metric"]
            for gate in report["gates"]
            if gate["status"] == "failed"
        }
        self.assertEqual({"harness_live_dataset_sha256"}, failed)

    def test_rejects_wrong_but_well_formed_live_dataset_hash(self):
        report = build_report(
            planner(),
            GATES,
            harness_live_payload=live_harness_result(dataset_sha256="c" * 64),
        )
        self.assertEqual("failed", report["status"])
        failed = {
            gate["metric"]
            for gate in report["gates"]
            if gate["status"] == "failed"
        }
        self.assertEqual({"harness_live_dataset_sha256"}, failed)

    def test_live_harness_partial_or_unsafe_result_fails_agent_eval(self):
        report = build_report(
            planner(),
            GATES,
            harness_live_payload=live_harness_result(
                status="partial",
                metrics={
                    "case_count": 30,
                    "completed_count": 30,
                    "safe_terminal_rate": 1.0,
                    "unsafe_result_count": 1,
                },
            ),
        )
        self.assertEqual("failed", report["status"])
        failed = {
            gate["metric"]
            for gate in report["gates"]
            if gate["status"] == "failed"
        }
        self.assertIn("harness_live_status", failed)
        self.assertIn("harness_live_unsafe_result_count", failed)


class RunAgentEvalTest(unittest.TestCase):
    def test_cli_uses_planner_gate_status_instead_of_unified_incomplete_status(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            cases_path = root / "cases.jsonl"
            gates_path = root / "gates.json"
            output_path = root / "result.json"
            cases = [{"id": "case-1"}]
            cases_path.write_text('{"id":"case-1"}\n', encoding="utf-8")
            gates_path.write_text(
                json.dumps(gates_for_cases(cases)),
                encoding="utf-8",
            )
            argv = [
                "run_agent_eval.py",
                "--cases",
                str(cases_path),
                "--gates",
                str(gates_path),
                "--output",
                str(output_path),
            ]
            planner_payload = planner(
                mode="DETERMINISTIC",
                total_cases=1,
                requested_cases=1,
            )
            with (
                patch.object(
                    run_agent_eval,
                    "invoke",
                    return_value=planner_payload,
                ) as invoke,
                patch.object(run_agent_eval.sys, "argv", argv),
                patch("builtins.print"),
            ):
                exit_code = run_agent_eval.main()

            self.assertEqual(0, exit_code)
            invoke.assert_called_once_with(
                "http://localhost:8080/api/eval/agent/planner",
                "DETERMINISTIC",
                cases,
                None,
                600.0,
            )
            report = json.loads(output_path.read_text(encoding="utf-8"))
            self.assertEqual("incomplete", report["status"])
            self.assertEqual("passed", report["planner"]["gate_status"])

    def test_cli_returns_nonzero_when_planner_itself_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            cases_path = root / "cases.jsonl"
            gates_path = root / "gates.json"
            output_path = root / "result.json"
            cases = [{"id": "case-1"}]
            cases_path.write_text('{"id":"case-1"}\n', encoding="utf-8")
            gates_path.write_text(
                json.dumps(gates_for_cases(cases)),
                encoding="utf-8",
            )
            failed_planner = planner(
                route=0.5,
                mode="DETERMINISTIC",
                total_cases=1,
                requested_cases=1,
            )
            failed_planner["status"] = "failed"
            argv = [
                "run_agent_eval.py",
                "--cases",
                str(cases_path),
                "--gates",
                str(gates_path),
                "--output",
                str(output_path),
            ]
            with (
                patch.object(run_agent_eval, "invoke", return_value=failed_planner),
                patch.object(run_agent_eval.sys, "argv", argv),
                patch("builtins.print"),
            ):
                exit_code = run_agent_eval.main()

            self.assertEqual(2, exit_code)
            report = json.loads(output_path.read_text(encoding="utf-8"))
            self.assertEqual("failed", report["planner"]["gate_status"])

    def test_cli_passes_custom_timeout_to_planner_request(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            cases_path = root / "cases.jsonl"
            gates_path = root / "gates.json"
            output_path = root / "result.json"
            cases = [{"id": "case-1"}]
            cases_path.write_text('{"id":"case-1"}\n', encoding="utf-8")
            gates_path.write_text(
                json.dumps(gates_for_cases(cases)),
                encoding="utf-8",
            )
            argv = [
                "run_agent_eval.py",
                "--cases",
                str(cases_path),
                "--gates",
                str(gates_path),
                "--output",
                str(output_path),
                "--timeout-seconds",
                "42",
            ]
            with (
                patch.object(
                    run_agent_eval,
                    "invoke",
                    return_value=planner(
                        mode="DETERMINISTIC",
                        total_cases=1,
                        requested_cases=1,
                    ),
                ) as invoke,
                patch.object(run_agent_eval.sys, "argv", argv),
                patch("builtins.print"),
            ):
                exit_code = run_agent_eval.main()

            self.assertEqual(0, exit_code)
            self.assertEqual(42.0, invoke.call_args.args[-1])


if __name__ == "__main__":
    unittest.main()
