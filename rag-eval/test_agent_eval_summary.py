import unittest

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


class AgentEvalSummaryTest(unittest.TestCase):
    def test_passes_and_marks_optional_sections_not_run(self):
        report = build_report(planner(), GATES, baseline_payload=planner())
        self.assertEqual("agent_eval_v1", report["schema_version"])
        self.assertEqual("passed", report["status"])
        self.assertEqual("not_run", report["rag"]["status"])
        self.assertEqual("not_run", report["trace"]["status"])
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


if __name__ == "__main__":
    unittest.main()
