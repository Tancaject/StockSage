import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import run_agent_eval
from agent_eval_summary import build_report


GATES = {
    "route_accuracy_min": 0.95,
    "macro_f1_min": 0.95,
    "required_action_recall_min": 0.98,
    "forbidden_action_rate_max": 0.0,
    "critical_failures_max": 0,
    "executable_rate_min": 1.0,
    "baseline_delta_max_drop": 0.02,
    "p95_latency_ratio_max": 1.2,
    "harness_policy_id_expected": "deep-equity-v1",
    "harness_policy_version_expected": 2,
    "harness_case_count_min": 60,
    "harness_exact_accuracy_min": 1.0,
    "harness_decision_contract_exact_match_rate_min": 1.0,
    "harness_violation_exact_match_rate_min": 1.0,
    "harness_recovery_exact_match_rate_min": 1.0,
    "harness_unsafe_pass_count_max": 0,
    "harness_live_case_count_min": 30,
    "harness_live_completed_rate_min": 1.0,
    "harness_live_safe_terminal_rate_min": 1.0,
    "harness_live_unsafe_result_count_max": 0,
}


def planner(route=1.0, recall=1.0, forbidden=0.0, executable=1.0):
    return {
        "status": "passed",
        "mode": "LIVE_COORDINATOR",
        "totalCases": 2,
        "passedCases": 2,
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
        "durationMs": 25,
        "results": [{"durationMs": 10}, {"durationMs": 15}],
    }


def harness_result(**overrides):
    result = {
        "schema_version": "harness_eval_v1",
        "case_schema_version": "harness_golden_case_v2",
        "engine": "java-production-policy",
        "policy_id": "deep-equity-v1",
        "policy_version": 2,
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
        "policy_versions": ["2"],
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
    def test_cli_returns_nonzero_for_incomplete_report(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            cases_path = root / "cases.jsonl"
            gates_path = root / "gates.json"
            output_path = root / "result.json"
            cases_path.write_text('{"id":"case-1"}\n', encoding="utf-8")
            gates_path.write_text(json.dumps(GATES), encoding="utf-8")
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
                patch.object(run_agent_eval, "invoke", return_value=planner()),
                patch.object(run_agent_eval.sys, "argv", argv),
                patch("builtins.print"),
            ):
                exit_code = run_agent_eval.main()

            self.assertEqual(2, exit_code)
            report = json.loads(output_path.read_text(encoding="utf-8"))
            self.assertEqual("incomplete", report["status"])


if __name__ == "__main__":
    unittest.main()
