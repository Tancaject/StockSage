import unittest

from rag_summary import DEFAULT_GATES, evaluate_gates, summarize_results


class EvalSummaryTest(unittest.TestCase):
    def test_summarize_results_finds_failures_and_categories(self):
        result = summarize_results({
            "created_at": "2026-05-13T15:57:58",
            "case_count": 2,
            "averages": {
                "context_recall": 0.91,
                "citation_precision": 0.7,
                "no_answer_accuracy": 1.0,
            },
            "cases": [
                {"id": "good", "category": "risk", "metrics": {"context_recall": 1.0, "citation_precision": 1.0}},
                {"id": "bad", "category": "risk", "metrics": {"context_recall": 0.2, "citation_precision": 0.3}},
            ],
        })

        self.assertEqual(result["case_count"], 2)
        self.assertEqual(result["category_counts"], {"risk": 2})
        self.assertEqual(result["worst_cases"][0]["id"], "bad")
        self.assertEqual(result["gate_status"], "fail")

    def test_evaluate_gates_allows_missing_optional_metrics(self):
        gates = evaluate_gates({"context_recall": 0.9}, {
            "context_recall": {"minimum": 0.85, "required": True},
            "faithfulness": {"minimum": 0.85, "required": False},
        })

        self.assertEqual(gates["status"], "pass")
        self.assertEqual(gates["missing"], ["faithfulness"])
        self.assertEqual(gates["failed"], [])

    def test_gate_contract_preserves_authority_and_rejects_missing_evidence(self):
        good = {metric: target["minimum"] for metric, target in DEFAULT_GATES.items()}
        good["latency_seconds"] = 60
        for values, expected in [(good, "pass"), ({**good, "mrr": 0}, "fail"),
                                 ({}, "missing"), ({**good, "mrr": None}, "missing"),
                                 ({**good, "mrr": float("nan")}, "missing")]:
            with self.subTest(values=values):
                evaluation = evaluate_gates(values)
                self.assertEqual(evaluation["status"], expected)
                self.assertEqual(evaluation["schema_version"], "rag_gates_v1")
                self.assertNotIn("latency_seconds", [row["metric"] for row in evaluation["gates"]])
                summary = summarize_results({"averages": values, "gate_evaluation": evaluation})
                self.assertEqual(summary["gate_status"], expected)
                self.assertEqual(summary["gate_evaluation"], evaluation)
        custom = evaluate_gates({"mrr": 0.6}, {"mrr": {"minimum": 0.5, "required": True}})
        self.assertEqual(summarize_results({"gate_evaluation": custom})["gate_status"], "pass")


if __name__ == "__main__":
    unittest.main()
