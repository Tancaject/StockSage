import unittest

from eval_summary import evaluate_gates, summarize_results


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
        gates = evaluate_gates({"context_recall": 0.9}, {"context_recall": 0.85, "faithfulness": 0.85})

        self.assertEqual(gates["status"], "pass")
        self.assertEqual(gates["missing"], ["faithfulness"])
        self.assertEqual(gates["failed"], [])


if __name__ == "__main__":
    unittest.main()
