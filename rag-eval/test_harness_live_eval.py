import hashlib
import tempfile
import unittest
from pathlib import Path

from run_harness_live_eval import (
    aggregate_policy_metadata,
    evaluate_case_result,
    file_sha256,
    parse_sse_data,
    percentile,
)


def trace_with(*decisions):
    steps = []
    for phase, outcome, recoveries in decisions:
        steps.append(
            {
                "action": "harness:deep-research",
                "durationMs": 2,
                "attributes": {
                    "policyId": "deep-equity-v1",
                    "policyVersion": "2",
                    "phase": phase,
                    "decision": outcome,
                    "policyAllowsRecommendation": outcome == "PASS",
                    "violationCodes": [],
                    "recoveryActions": recoveries,
                },
            }
        )
    return {"status": "success", "steps": steps}


class HarnessLiveEvalTest(unittest.TestCase):
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

    def test_bounded_recovery_can_finish_with_pass(self):
        result = evaluate_case_result(
            {"status": "SUCCEEDED", "resultKind": "FULL_REPORT"},
            trace_with(
                ("EVIDENCE", "RECOVER", ["REFRESH_MARKET"]),
                ("EVIDENCE", "PASS", []),
                ("REPORT", "PASS", []),
            ),
            ["FULL_REPORT"],
        )
        self.assertEqual("pass", result["status"])
        self.assertEqual({"REFRESH_MARKET": 1}, result["recovery_counts"])

    def test_duplicate_recovery_exceeds_budget(self):
        result = evaluate_case_result(
            {"status": "SUCCEEDED", "resultKind": "INSUFFICIENT_EVIDENCE"},
            trace_with(
                ("EVIDENCE", "RECOVER", ["REFRESH_MARKET"]),
                ("EVIDENCE", "DEGRADE", ["REFRESH_MARKET"]),
            ),
            ["INSUFFICIENT_EVIDENCE"],
        )
        self.assertEqual("fail", result["status"])
        self.assertIn("recovery_budget_exceeded", result["unsafe_reasons"])

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

    def test_dataset_hash_uses_exact_case_file_bytes(self):
        payload = b'{"id":"case-1"}\r\n{"id":"case-2"}\n'
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "cases.jsonl"
            path.write_bytes(payload)
            self.assertEqual(hashlib.sha256(payload).hexdigest(), file_sha256(path))

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


if __name__ == "__main__":
    unittest.main()
