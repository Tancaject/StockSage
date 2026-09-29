import copy
import unittest

from ordinary_answer_quality import analyst_inputs, apply_reviews, compare_reports
from run_ordinary_answer_replay import prompt_hash, run_pairs
from test_ordinary_answer_quality import completed_reviews, sample_analyst_report


class OrdinaryAnswerReplayTest(unittest.TestCase):
    def test_new_answers_and_usage_have_independent_review_bindings(self):
        inputs = analyst_inputs(sample_analyst_report())
        calls = []

        def invoke(body):
            calls.append(body)
            return {"schema": "ordinary_answer_replay_v1", "replayId": str(len(calls)), "status": "COMPLETED",
                    "answer": "new answer " + str(len(calls)), "promptSha256": prompt_hash(body["messages"]),
                    "observations": [{"kind": "model-invocation", "scope": "final-answer",
                                      "modelName": "current-model", "modelTier": "STANDARD"},
                                     {"kind": "model-usage", "scope": "final-answer", "usageSource": "PROVIDER",
                                      "totalTokens": len(calls) * 10}], "durationMs": 15, "errorCode": ""}

        result = run_pairs(inputs, invoke)
        self.assertEqual(len(calls), 2)
        self.assertEqual(calls[0]["messages"], inputs["pairs"][0]["baseline"]["messages"])
        self.assertEqual(calls[1]["messages"], inputs["pairs"][0]["without_analyst"]["messages"])
        reviewed = {variant: apply_reviews(report, completed_reviews(report)) for variant, report in result.items()}
        self.assertTrue(all(report["quality_evaluation"]["status"] == "PASS" for report in reviewed.values()))
        comparison = compare_reports(reviewed["baseline"], reviewed["without_analyst"])
        self.assertEqual(comparison["paired_count"], 1)
        pair = comparison["pairs"][0]
        self.assertEqual(pair["candidate_model_invocations"][0]["modelName"], "current-model")
        self.assertEqual(pair["baseline_model_usage"]["totalTokens"], 10)
        self.assertEqual(pair["candidate_model_usage"]["totalTokens"], 20)
        self.assertNotEqual(pair["baseline"]["answerSha256"], pair["candidate"]["answerSha256"])

        drift_calls = []
        def drift(body):
            response = invoke(body)
            drift_calls.append(body)
            response["observations"][0]["modelName"] += str(len(drift_calls))
            return response
        drifted = run_pairs(inputs, drift)
        self.assertTrue(all(report["status"] == "FAIL" for report in drifted.values()))
        self.assertEqual(compare_reports(drifted["baseline"], drifted["without_analyst"])["unpaired"][0]["reason"],
                         "EXECUTION_NOT_PASSED")

    def test_input_changes_rejected_before_calls_and_transport_failure_never_retried(self):
        inputs = analyst_inputs(sample_analyst_report())
        changed = copy.deepcopy(inputs)
        branch = changed["pairs"][0]["without_analyst"]
        branch["messages"][0]["text"] += " changed rule"
        branch["promptSha256"] = prompt_hash(branch["messages"])
        with self.assertRaisesRegex(ValueError, "more than the analyst"):
            run_pairs(changed, lambda _: self.fail("must not call model"))
        calls = []
        def timeout(body):
            calls.append(body)
            raise TimeoutError("response lost")
        blocked = run_pairs(inputs, timeout)
        self.assertEqual(len(calls), 1)
        self.assertTrue(all(report["status"] == "BLOCKED" for report in blocked.values()))
        self.assertTrue(all(report["cases"] == [] for report in blocked.values()))
        failed = run_pairs(inputs, lambda body: {
            "schema": "ordinary_answer_replay_v1", "replayId": "partial", "status": "FAILED",
            "answer": "partial answer", "promptSha256": prompt_hash(body["messages"]),
            "observations": [], "errorCode": "TIMEOUT"})
        self.assertEqual(failed["baseline"]["cases"][0]["answer"], "partial answer")
        self.assertEqual(failed["baseline"]["status"], "FAIL")
        self.assertEqual(failed["without_analyst"]["status"], "BLOCKED")


if __name__ == "__main__":
    unittest.main()
