import unittest

from agent_eval_summary import build_report


GATES = {
    "route_accuracy_min": 0.95,
    "required_action_recall_min": 0.98,
    "forbidden_action_rate_max": 0.0,
    "critical_failures_max": 0,
    "executable_rate_min": 1.0,
    "legacy_delta_max_drop": 0.02,
    "p95_latency_ratio_max": 1.2,
}


def planner(route=1.0, recall=1.0, forbidden=0.0, executable=1.0):
    return {
        "status": "passed",
        "mode": "INTENT_SHADOW",
        "totalCases": 2,
        "passedCases": 2,
        "criticalFailures": 0,
        "routeAccuracy": route,
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

    def test_fails_when_any_hard_gate_fails(self):
        report = build_report(planner(route=0.8), GATES, baseline_payload=planner())
        self.assertEqual("failed", report["status"])
        failed = {gate["metric"] for gate in report["gates"] if gate["status"] == "failed"}
        self.assertIn("route_accuracy", failed)
        self.assertIn("legacy_max_metric_drop", failed)


if __name__ == "__main__":
    unittest.main()
