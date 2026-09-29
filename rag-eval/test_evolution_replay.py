import unittest
import uuid

from run_evolution_replay import run_cases


class EvolutionReplayTest(unittest.TestCase):
    def execution(self):
        return {"schemaVersion": 1, "cases": [{"caseId": "smoke", "route": "FUNDAMENTALS",
                "caseSha256": "a" * 64, "origin": "SYNTHETIC", "executionScope": "SYNTHETIC_COMPONENT_CHAIN",
                "query": "原问题", "resolvedQuery": "已消歧问题"}],
                "runs": [{"runId": str(uuid.uuid4()), "caseId": "smoke", "bundleId": "baseline-v1", "repeatId": 1}]}

    def response(self, request):
        return {"schema": "fundamentals_evolution_replay_v1", **request, "caseSha256": "a" * 64,
                "executionFileSha256": "b" * 64, "origin": "SYNTHETIC", "query": "原问题", "repeatId": 1,
                "executionScope": "SYNTHETIC_COMPONENT_CHAIN",
                "resolvedQuery": "已消歧问题", "methodBundle": {"bundleId": request["bundleId"]},
                "status": "COMPLETED", "analysis": {"status": "COMPLETED"},
                "finalAnswer": {"status": "COMPLETED"}}

    def test_execution_does_not_claim_quality_and_each_repeat_has_a_new_identity(self):
        first = run_cases(self.execution(), "baseline-v1", "b" * 64, self.response)
        second = run_cases(self.execution(), "baseline-v1", "b" * 64, self.response)
        self.assertEqual(first["status"], "PASS")
        self.assertEqual(first["answer_quality"], "NO_DATA")
        self.assertEqual(first["dataset_sha256"], second["dataset_sha256"])
        self.assertNotEqual(first["cases"][0]["request"]["runId"], second["cases"][0]["request"]["runId"])

    def test_wrong_registered_file_fails_and_lost_response_is_not_retried(self):
        def drift(request):
            return {**self.response(request), "executionFileSha256": "c" * 64}
        self.assertEqual(run_cases(self.execution(), "baseline-v1", "b" * 64, drift)["status"], "FAIL")
        calls = []
        def lost(request):
            calls.append(request)
            raise TimeoutError("unknown remote completion")
        report = run_cases(self.execution(), "baseline-v1", "b" * 64, lost)
        self.assertEqual(len(calls), 1)
        self.assertEqual(report["status"], "BLOCKED")
        self.assertEqual(report["blocked"]["usage_status"], "UNKNOWN")

    def test_duplicate_execution_plan_is_rejected_before_any_call(self):
        execution = self.execution()
        execution["runs"].append(execution["runs"][0].copy())
        with self.assertRaises(ValueError):
            run_cases(execution, "baseline-v1", "b" * 64, lambda _: self.fail("must not invoke"))

    def test_runner_binds_comparison_before_call_and_selects_registered_arm(self):
        from test_evolution_compare import experiment
        from ordinary_answer_quality import json_hash
        manifest, baseline, candidate = experiment(group_count=1, repeats=1)
        row = baseline["cases"][0]
        definition = {key: value for key, value in row["case_definition"].items() if key not in ("bundleId", "repeatId")}
        execution = {"schemaVersion": 1, "cases": [definition], "runs": [
            {**report["cases"][0]["request"], "repeatId": 1} for report in (baseline, candidate)]}
        result = run_cases(execution, manifest["baselineBundleId"], row["replay"]["executionFileSha256"],
                           lambda request: row["replay"], manifest)
        self.assertEqual("PASS", result["status"])
        self.assertEqual(json_hash(manifest), result["comparison_manifest_sha256"])
        manifest["cases"][0]["repeatIds"] = [1, 2]
        with self.assertRaises(ValueError):
            run_cases(execution, manifest["baselineBundleId"], "b" * 64, lambda _: self.fail("must not invoke"), manifest)


if __name__ == "__main__":
    unittest.main()
