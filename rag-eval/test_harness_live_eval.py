import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import run_harness_live_eval as harness_live
from run_harness_live_eval import (
    CaseReconciliationPending,
    LiveEvalError,
    LiveEvalStreamError,
    RELEASE_MANIFEST_SCHEMA,
    aggregate_policy_metadata,
    dataset_sha256,
    default_checkpoint_path,
    evaluate_case_result,
    exit_code_for_status,
    load_run_checkpoint,
    parse_sse_data,
    percentile,
    release_contract_violations,
    run_case,
    save_run_checkpoint,
)


def trace_with(*decisions):
    steps = []
    for decision in decisions:
        phase, outcome, recoveries = decision[:3]
        effect_key = decision[3] if len(decision) > 3 else ""
        attributes = {
            "policyId": "deep-equity-v1",
            "policyVersion": "2",
            "phase": phase,
            "decision": outcome,
            "policyAllowsRecommendation": outcome == "PASS",
            "violationCodes": [],
            "recoveryActions": recoveries,
        }
        if effect_key:
            attributes["recoveryEffectKey"] = effect_key
        steps.append(
            {
                "action": "harness:deep-research",
                "durationMs": 2,
                "attributes": attributes,
            }
        )
    return {"status": "success", "steps": steps}


class HarnessLiveEvalTest(unittest.TestCase):
    def release_manifest(self, dataset_hash="a" * 64):
        return {
            "schema": RELEASE_MANIFEST_SCHEMA,
            "case_count": 30,
            "dataset_sha256": dataset_hash,
            "policy_id": "deep-equity-v1",
            "policy_version": "2",
        }

    def live_case(self, case_id="live-1", ticker="AAPL", timeout_seconds=30):
        return {
            "id": case_id,
            "ticker": ticker,
            "message": f"Analyze {ticker}",
            "allowed_result_kinds": ["FULL_REPORT"],
            "timeout_seconds": timeout_seconds,
        }

    def test_full_report_requires_both_final_passes(self):
        result = evaluate_case_result(
            {"status": "SUCCEEDED", "resultKind": "FULL_REPORT"},
            trace_with(("EVIDENCE", "PASS", []), ("REPORT", "PASS", [])),
            ["FULL_REPORT"],
        )
        self.assertEqual("pass", result["status"])
        self.assertTrue(result["safe_terminal"])

        unsafe = evaluate_case_result(
            {"status": "SUCCEEDED", "resultKind": "FULL_REPORT"},
            trace_with(("EVIDENCE", "PASS", [])),
            ["FULL_REPORT"],
        )
        self.assertEqual("fail", unsafe["status"])
        self.assertIn("full_report_without_report_pass", unsafe["unsafe_reasons"])

    def test_insufficient_evidence_accepts_report_gate_stop_after_evidence_pass(self):
        for report_decision in ("DEGRADE", "BLOCK"):
            with self.subTest(report_decision=report_decision):
                result = evaluate_case_result(
                    {
                        "status": "SUCCEEDED",
                        "resultKind": "INSUFFICIENT_EVIDENCE",
                    },
                    trace_with(
                        ("EVIDENCE", "PASS", []),
                        ("REPORT", report_decision, []),
                    ),
                    ["INSUFFICIENT_EVIDENCE"],
                )
                self.assertEqual("pass", result["status"])
                self.assertTrue(result["safe_terminal"])

    def test_insufficient_evidence_rejects_missing_or_passing_report_gate(self):
        traces = (
            trace_with(("EVIDENCE", "PASS", [])),
            trace_with(("EVIDENCE", "PASS", []), ("REPORT", "PASS", [])),
        )
        for trace in traces:
            with self.subTest(trace=trace):
                result = evaluate_case_result(
                    {
                        "status": "SUCCEEDED",
                        "resultKind": "INSUFFICIENT_EVIDENCE",
                    },
                    trace,
                    ["INSUFFICIENT_EVIDENCE"],
                )
                self.assertEqual("fail", result["status"])
                self.assertIn(
                    "insufficient_evidence_without_degrade_or_block",
                    result["unsafe_reasons"],
                )

    def test_bounded_recovery_can_finish_with_pass(self):
        result = evaluate_case_result(
            {"status": "SUCCEEDED", "resultKind": "FULL_REPORT"},
            trace_with(
                ("EVIDENCE", "RECOVER", ["REFRESH_MARKET"], "effect-1"),
                ("EVIDENCE", "PASS", []),
                ("REPORT", "PASS", []),
            ),
            ["FULL_REPORT"],
        )
        self.assertEqual("pass", result["status"])
        self.assertEqual({"REFRESH_MARKET": 1}, result["recovery_counts"])
        self.assertTrue(result["recovery_effect_keys_complete"])

    def test_duplicate_recovery_exceeds_budget(self):
        result = evaluate_case_result(
            {"status": "SUCCEEDED", "resultKind": "INSUFFICIENT_EVIDENCE"},
            trace_with(
                ("EVIDENCE", "RECOVER", ["REFRESH_MARKET"], "effect-1"),
                ("EVIDENCE", "DEGRADE", ["REFRESH_MARKET"], "effect-2"),
            ),
            ["INSUFFICIENT_EVIDENCE"],
        )
        self.assertEqual("fail", result["status"])
        self.assertIn("recovery_budget_exceeded", result["unsafe_reasons"])

    def test_same_key_replay_counts_as_one_logical_recovery(self):
        result = evaluate_case_result(
            {"status": "SUCCEEDED", "resultKind": "FULL_REPORT"},
            trace_with(
                ("EVIDENCE", "RECOVER", ["REFRESH_MARKET"], "effect-1"),
                ("EVIDENCE", "RECOVER", ["REFRESH_MARKET"], "effect-1"),
                ("EVIDENCE", "PASS", []),
                ("REPORT", "PASS", []),
            ),
            ["FULL_REPORT"],
        )

        self.assertEqual("pass", result["status"])
        self.assertEqual({"REFRESH_MARKET": 1}, result["recovery_counts"])
        self.assertEqual({"REFRESH_MARKET": 1}, result["recovery_replay_counts"])

    def test_distinct_effect_keys_still_expose_budget_overrun(self):
        result = evaluate_case_result(
            {"status": "SUCCEEDED", "resultKind": "FULL_REPORT"},
            trace_with(
                ("EVIDENCE", "RECOVER", ["REFRESH_MARKET"], "effect-1"),
                ("EVIDENCE", "RECOVER", ["REFRESH_MARKET"], "effect-2"),
                ("EVIDENCE", "PASS", []),
                ("REPORT", "PASS", []),
            ),
            ["FULL_REPORT"],
        )

        self.assertEqual("fail", result["status"])
        self.assertEqual({"REFRESH_MARKET": 2}, result["recovery_counts"])
        self.assertIn("recovery_budget_exceeded", result["unsafe_reasons"])

    def test_legacy_adjacent_replay_is_diagnostic_only_and_fails_closed(self):
        replayed = evaluate_case_result(
            {"status": "SUCCEEDED", "resultKind": "FULL_REPORT"},
            trace_with(
                ("EVIDENCE", "RECOVER", ["REFRESH_MARKET"]),
                ("EVIDENCE", "RECOVER", ["REFRESH_MARKET"]),
                ("EVIDENCE", "PASS", []),
                ("REPORT", "PASS", []),
            ),
            ["FULL_REPORT"],
        )

        self.assertEqual("fail", replayed["status"])
        self.assertEqual({"REFRESH_MARKET": 1}, replayed["recovery_counts"])
        self.assertEqual({"REFRESH_MARKET": 1}, replayed["recovery_replay_counts"])
        self.assertTrue(replayed["recovery_budget_ok"])
        self.assertFalse(replayed["recovery_effect_keys_complete"])
        self.assertIn("recovery_effect_key_missing", replayed["unsafe_reasons"])
        self.assertEqual(1, exit_code_for_status(replayed["status"], fail_on_gate=True))

    def test_single_keyless_recovery_cannot_pass_release_gate(self):
        result = evaluate_case_result(
            {"status": "SUCCEEDED", "resultKind": "FULL_REPORT"},
            trace_with(
                ("EVIDENCE", "RECOVER", ["REFRESH_MARKET"]),
                ("EVIDENCE", "PASS", []),
                ("REPORT", "PASS", []),
            ),
            ["FULL_REPORT"],
        )

        self.assertEqual("fail", result["status"])
        self.assertEqual({"REFRESH_MARKET": 1}, result["recovery_counts"])
        self.assertTrue(result["recovery_budget_ok"])
        self.assertFalse(result["recovery_effect_keys_complete"])
        self.assertEqual(
            ["recovery_effect_key_missing"],
            result["unsafe_reasons"],
        )
        self.assertEqual(1, exit_code_for_status(result["status"], fail_on_gate=True))

    def test_offline_fallback_is_safe_but_partial(self):
        result = evaluate_case_result(
            {"status": "SUCCEEDED", "resultKind": "OFFLINE_FALLBACK"},
            trace_with(("EVIDENCE", "PASS", [])),
            ["OFFLINE_FALLBACK"],
        )
        self.assertEqual("partial", result["status"])
        self.assertTrue(result["safe_terminal"])

    def test_sse_and_percentile_helpers(self):
        self.assertEqual(
            {"type": "meta", "conversationId": 7},
            parse_sse_data(['{"type":"meta","conversationId":7}']),
        )
        self.assertIsNone(parse_sse_data(["[DONE]"]))
        self.assertEqual(9.0, percentile([1.0, 5.0, 9.0], 0.95))

    def test_sse_observation_deadline_is_enforced_between_comment_lines(self):
        class HeartbeatResponse:
            def __init__(self):
                self.readline_count = 0

            def __enter__(self):
                return self

            def __exit__(self, exc_type, exc_value, traceback):
                return False

            def readline(self):
                self.readline_count += 1
                return b": heartbeat\n"

        class HeartbeatOpener:
            def __init__(self, response):
                self.response = response

            def open(self, request, timeout):
                return self.response

        response = HeartbeatResponse()
        client = harness_live.StockSageClient("http://localhost:8080")
        client.opener = HeartbeatOpener(response)
        with patch.object(
            harness_live.time,
            "monotonic",
            side_effect=[0.0, 2.0],
        ):
            events = client.stream_json(
                "GET",
                "/api/research-tasks/42/events",
                None,
                timeout_seconds=30,
                deadline=1.0,
            )

        self.assertEqual([], events)
        self.assertEqual(1, response.readline_count)

    def test_dataset_hash_is_stable_across_checkout_newlines(self):
        lf_payload = b'{"id":"case-1","ticker":"AAPL"}\n{"id":"case-2","ticker":"MSFT"}\n'
        crlf_payload = lf_payload.replace(b"\n", b"\r\n")
        with tempfile.TemporaryDirectory() as directory:
            lf_path = Path(directory) / "lf.jsonl"
            crlf_path = Path(directory) / "crlf.jsonl"
            lf_path.write_bytes(lf_payload)
            crlf_path.write_bytes(crlf_payload)
            self.assertEqual(dataset_sha256(lf_path), dataset_sha256(crlf_path))

            changed_path = Path(directory) / "changed.jsonl"
            changed_path.write_bytes(lf_payload.replace(b"MSFT", b"NVDA"))
            self.assertNotEqual(
                dataset_sha256(lf_path),
                dataset_sha256(changed_path),
            )

    def test_checkpoint_round_trip_is_atomic_and_identity_bound(self):
        cases = [
            self.live_case("live-1", "AAPL"),
            self.live_case("live-2", "MSFT"),
        ]
        manifest = self.release_manifest()
        completed = [{"id": "live-1", "ticker": "AAPL", "status": "pass"}]
        in_progress = {
            "id": "live-2",
            "ticker": "MSFT",
            "phase": "polling_terminal_task",
            "task_id": 42,
        }
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "result.json"
            checkpoint = default_checkpoint_path(output)
            save_run_checkpoint(
                checkpoint,
                base_url="http://localhost:8080/",
                dataset_hash="a" * 64,
                manifest=manifest,
                completed_cases=completed,
                in_progress_case=in_progress,
            )

            loaded_completed, loaded_in_progress = load_run_checkpoint(
                checkpoint,
                cases=cases,
                base_url="http://localhost:8080",
                dataset_hash="a" * 64,
                manifest=manifest,
            )
            self.assertEqual(completed, loaded_completed)
            self.assertEqual(in_progress, loaded_in_progress)
            self.assertEqual([], list(Path(directory).glob(".*.tmp")))

            with self.assertRaisesRegex(
                LiveEvalError, "does not match this run"
            ):
                load_run_checkpoint(
                    checkpoint,
                    cases=cases,
                    base_url="http://localhost:8080",
                    dataset_hash="b" * 64,
                    manifest=manifest,
                )

    def test_task_sse_disconnect_reconciles_terminal_task_and_trace(self):
        checkpoints = []

        class DisconnectingClient:
            def __init__(self):
                self.post_count = 0
                self.task_stream_count = 0

            def stream_json(
                self,
                method,
                path,
                body,
                timeout_seconds,
                stop_when=None,
                deadline=None,
                on_event=None,
            ):
                if method == "POST":
                    self.post_count += 1
                    event = {
                        "type": "meta",
                        "conversationId": 7,
                        "traceId": "trace-7",
                        "metadata": {"route": "DEEP"},
                    }
                    if on_event:
                        on_event(event)
                    return [event]
                self.task_stream_count += 1
                raise LiveEvalStreamError(
                    "task SSE disconnected",
                    [{"type": "thought"}],
                )

            def json_request(
                self, method, path, body=None, timeout_seconds=None
            ):
                if path.startswith("/api/research-tasks/active"):
                    return 200, {
                        "taskId": 42,
                        "ticker": "AAPL",
                        "status": "RUNNING",
                        "stage": "RESEARCH",
                    }
                if path.startswith("/api/workbench/stocks/"):
                    return 200, {
                        "taskTimeline": [
                            {
                                "id": 42,
                                "conversationId": 7,
                                "status": "SUCCEEDED",
                                "stage": "DONE",
                                "resultKind": "FULL_REPORT",
                                "resultReportVersionId": 99,
                            }
                        ],
                        "latestReport": {"id": 99},
                    }
                if path == "/api/trace/trace-7":
                    return 200, trace_with(
                        ("EVIDENCE", "PASS", []),
                        ("REPORT", "PASS", []),
                    )
                raise AssertionError(f"unexpected request: {method} {path}")

        client = DisconnectingClient()
        result = run_case(
            client,
            self.live_case(),
            checkpoint_case=checkpoints.append,
        )

        self.assertEqual("pass", result["status"])
        self.assertTrue(result["completed"])
        self.assertEqual(1, client.post_count)
        self.assertEqual(1, client.task_stream_count)
        self.assertEqual(["thought"], result["task_event_types"])
        self.assertIn("task SSE disconnected", result["task_event_stream_error"])
        self.assertEqual("SUCCEEDED", result["task_status"])
        self.assertEqual("success", result["trace_status"])
        self.assertIn(
            "submission_streaming",
            [state["phase"] for state in checkpoints],
        )
        self.assertIn(
            "polling_terminal_task",
            [state["phase"] for state in checkpoints],
        )

    def test_resume_with_task_id_polls_without_resubmitting_or_reopening_sse(self):
        class ResumeClient:
            def __init__(self):
                self.stream_calls = 0

            def stream_json(self, *args, **kwargs):
                self.stream_calls += 1
                raise AssertionError("resume must not reopen a submission or task SSE")

            def json_request(
                self, method, path, body=None, timeout_seconds=None
            ):
                if path.startswith("/api/workbench/stocks/"):
                    return 200, {
                        "taskTimeline": [
                            {
                                "id": 42,
                                "conversationId": 7,
                                "status": "SUCCEEDED",
                                "stage": "DONE",
                                "resultKind": "FULL_REPORT",
                                "resultReportVersionId": 99,
                            }
                        ],
                        "latestReport": {"id": 99},
                    }
                if path == "/api/trace/trace-7":
                    return 200, trace_with(
                        ("EVIDENCE", "PASS", []),
                        ("REPORT", "PASS", []),
                    )
                raise AssertionError(f"unexpected request: {method} {path}")

        client = ResumeClient()
        result = run_case(
            client,
            self.live_case(),
            resume_state={
                "id": "live-1",
                "ticker": "AAPL",
                "phase": "polling_terminal_task",
                "conversation_id": 7,
                "trace_id": "trace-7",
                "task_id": 42,
                "task_status": "RUNNING",
                "task_stage": "RESEARCH",
                "elapsed_seconds": 2.0,
                "submission_event_types": ["meta"],
                "task_event_types": ["thought"],
                "task_event_stream_error": "prior disconnect",
            },
        )

        self.assertEqual("pass", result["status"])
        self.assertTrue(result["resumed"])
        self.assertEqual(0, client.stream_calls)
        self.assertEqual("prior disconnect", result["task_event_stream_error"])
        self.assertGreaterEqual(result["wall_seconds"], 2.0)

    def test_unresolved_resumed_task_remains_reconciliation_pending(self):
        class RunningClient:
            def stream_json(self, *args, **kwargs):
                raise AssertionError("resume must poll instead of reopening SSE")

            def json_request(
                self, method, path, body=None, timeout_seconds=None
            ):
                if path.startswith("/api/workbench/stocks/"):
                    return 200, {
                        "taskTimeline": [
                            {
                                "id": 42,
                                "conversationId": 7,
                                "status": "RUNNING",
                                "stage": "RESEARCH",
                            }
                        ]
                    }
                raise AssertionError(f"unexpected request: {method} {path}")

        with self.assertRaises(CaseReconciliationPending) as context:
            run_case(
                RunningClient(),
                self.live_case(timeout_seconds=0),
                resume_state={
                    "id": "live-1",
                    "ticker": "AAPL",
                    "phase": "polling_terminal_task",
                    "conversation_id": 7,
                    "trace_id": "trace-7",
                    "task_id": 42,
                    "task_status": "RUNNING",
                    "task_stage": "RESEARCH",
                },
            )

        self.assertEqual(42, context.exception.state["task_id"])
        self.assertEqual(
            "reconciliation_pending",
            context.exception.state["phase"],
        )

    def test_main_stops_batch_when_submitted_case_is_not_reconciled(self):
        class HealthyClient:
            def __init__(self, base_url):
                self.base_url = base_url

            def json_request(
                self, method, path, body=None, timeout_seconds=None
            ):
                self.assert_health_path(path)
                return 200, {"status": "UP"}

            @staticmethod
            def assert_health_path(path):
                if path != "/actuator/health":
                    raise AssertionError(f"unexpected request: {path}")

            def login(self, email, password):
                return {"userId": 1, "email": email}

        submitted = []

        def pending_case(client, case, resume_state=None, checkpoint_case=None):
            submitted.append(case["id"])
            raise CaseReconciliationPending(
                "task is still running",
                {
                    "id": case["id"],
                    "ticker": case["ticker"],
                    "phase": "reconciliation_pending",
                    "conversation_id": 7,
                    "trace_id": "trace-7",
                    "task_id": 42,
                },
            )

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            cases_path = root / "cases.jsonl"
            rows = [
                {
                    "id": f"live-{index:02d}",
                    "ticker": f"T{index:02d}",
                    "message": f"Analyze T{index:02d}",
                    "allowed_result_kinds": ["FULL_REPORT"],
                    "timeout_seconds": 30,
                }
                for index in range(30)
            ]
            cases_path.write_text(
                "".join(json.dumps(row) + "\n" for row in rows),
                encoding="utf-8",
            )
            dataset_hash = dataset_sha256(cases_path)
            manifest_path = root / "manifest.json"
            manifest_path.write_text(
                json.dumps(self.release_manifest(dataset_hash)),
                encoding="utf-8",
            )
            output_path = root / "result.json"
            checkpoint_path = default_checkpoint_path(output_path)
            argv = [
                "run_harness_live_eval.py",
                "--cases",
                str(cases_path),
                "--manifest",
                str(manifest_path),
                "--output",
                str(output_path),
            ]

            with (
                patch.object(harness_live, "StockSageClient", HealthyClient),
                patch.object(harness_live, "run_case", pending_case),
                patch.object(sys, "argv", argv),
            ):
                self.assertEqual(0, harness_live.main())

            self.assertEqual(["live-00"], submitted)
            result = json.loads(output_path.read_text(encoding="utf-8"))
            checkpoint = json.loads(
                checkpoint_path.read_text(encoding="utf-8")
            )
            self.assertEqual("blocked", result["status"])
            self.assertEqual("blocked", result["run_state"])
            self.assertEqual(
                "live-00",
                checkpoint["in_progress_case"]["id"],
            )

    def test_main_case_limit_completes_smoke_without_claiming_release_pass(self):
        class HealthyClient:
            def __init__(self, base_url):
                self.base_url = base_url

            def json_request(
                self, method, path, body=None, timeout_seconds=None
            ):
                if path != "/actuator/health":
                    raise AssertionError(f"unexpected request: {path}")
                return 200, {"status": "UP"}

            def login(self, email, password):
                return {"userId": 1, "email": email}

        submitted = []

        def passing_case(
            client,
            case,
            resume_state=None,
            checkpoint_case=None,
        ):
            submitted.append(case["id"])
            return {
                "id": case["id"],
                "ticker": case["ticker"],
                "status": "pass",
                "completed": True,
                "safe_terminal": True,
                "result_kind": "FULL_REPORT",
                "wall_seconds": 1.0,
                "tool_action_count": 1,
                "decisions": [
                    {
                        "policy_id": "deep-equity-v1",
                        "policy_version": 2,
                    }
                ],
            }

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            cases_path = root / "cases.jsonl"
            rows = [
                {
                    "id": f"live-{index:02d}",
                    "ticker": f"T{index:02d}",
                    "message": f"Analyze T{index:02d}",
                }
                for index in range(30)
            ]
            cases_path.write_text(
                "".join(json.dumps(row) + "\n" for row in rows),
                encoding="utf-8",
            )
            dataset_hash = dataset_sha256(cases_path)
            manifest_path = root / "manifest.json"
            manifest_path.write_text(
                json.dumps(self.release_manifest(dataset_hash)),
                encoding="utf-8",
            )
            output_path = root / "result.json"
            checkpoint_path = default_checkpoint_path(output_path)
            argv = [
                "run_harness_live_eval.py",
                "--cases",
                str(cases_path),
                "--manifest",
                str(manifest_path),
                "--output",
                str(output_path),
                "--case-limit",
                "5",
            ]

            with (
                patch.object(harness_live, "StockSageClient", HealthyClient),
                patch.object(harness_live, "run_case", passing_case),
                patch.object(sys, "argv", argv),
            ):
                self.assertEqual(0, harness_live.main())

            result = json.loads(output_path.read_text(encoding="utf-8"))
            checkpoint = json.loads(
                checkpoint_path.read_text(encoding="utf-8")
            )
            self.assertEqual(
                [f"live-{index:02d}" for index in range(5)],
                submitted,
            )
            self.assertEqual("smoke", result["run_mode"])
            self.assertEqual("smoke_complete", result["run_state"])
            self.assertEqual("pass", result["smoke_status"])
            self.assertEqual("fail", result["status"])
            self.assertFalse(result["release_eligible"])
            self.assertEqual(5, result["metrics"]["case_count"])
            self.assertIn(
                "live_release_case_limit_applied",
                result["release_gate_violations"],
            )
            self.assertEqual(5, len(checkpoint["completed_cases"]))
            self.assertIsNone(checkpoint["in_progress_case"])

    def test_policy_metadata_is_aggregated_from_observed_decisions(self):
        result = aggregate_policy_metadata(
            [
                {
                    "decisions": [
                        {
                            "policy_id": "deep-equity-v1",
                            "policy_version": "2",
                        },
                        {
                            "policy_id": "deep-equity-v1",
                            "policy_version": 2,
                        },
                    ]
                },
                {"decisions": []},
            ]
        )
        self.assertEqual(["deep-equity-v1"], result["policy_ids"])
        self.assertEqual(["2"], result["policy_versions"])

    def test_release_contract_rejects_single_case_diagnostic_dataset(self):
        violations = release_contract_violations(
            [{"id": "historical-singleton"}],
            "a" * 64,
            self.release_manifest(),
        )
        self.assertIn("live_case_count_below_minimum", violations)
        self.assertIn("live_case_count_mismatch", violations)

    def test_release_contract_rejects_dataset_hash_drift(self):
        violations = release_contract_violations(
            [{"id": f"case-{index}"} for index in range(30)],
            "b" * 64,
            self.release_manifest(),
        )
        self.assertEqual(["live_dataset_sha256_mismatch"], violations)

    def test_release_contract_rejects_stale_policy_trace(self):
        violations = release_contract_violations(
            [{"id": f"case-{index}"} for index in range(30)],
            "a" * 64,
            self.release_manifest(),
            {
                "policy_ids": ["deep-equity-v1"],
                "policy_versions": ["1"],
            },
        )
        self.assertEqual(["live_policy_version_mismatch"], violations)

    def test_fail_on_gate_requires_full_pass_status(self):
        self.assertEqual(0, exit_code_for_status("pass", fail_on_gate=True))
        for status in ("partial", "incomplete", "blocked", "fail"):
            with self.subTest(status=status):
                self.assertEqual(1, exit_code_for_status(status, fail_on_gate=True))

    def test_status_does_not_change_exit_code_without_gate_flag(self):
        for status in ("pass", "partial", "incomplete", "blocked", "fail"):
            with self.subTest(status=status):
                self.assertEqual(0, exit_code_for_status(status, fail_on_gate=False))


if __name__ == "__main__":
    unittest.main()
