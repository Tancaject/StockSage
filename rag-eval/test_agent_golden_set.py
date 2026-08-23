import json
import re
import unittest
from pathlib import Path

from run_agent_eval import cases_sha256


GOLDEN_SET = Path(__file__).with_name("agent_golden_set.jsonl")
GATES_FILE = Path(__file__).with_name("agent_eval_gates.json")
ROUTES = {"DIRECT", "MARKET", "FUNDAMENTALS", "NEWS", "DEEP"}
FINE_INTENTS = {
    "KNOWLEDGE_EXPLANATION",
    "MARKET_DATA",
    "TECHNICAL_ANALYSIS",
    "FUNDAMENTALS",
    "NEWS_EVENT",
    "COMPARISON",
    "PORTFOLIO_DIAGNOSIS",
    "DEEP_RESEARCH",
    "UNKNOWN",
}


class AgentGoldenSetTest(unittest.TestCase):
    def test_has_exactly_one_hundred_legacy_and_fifteen_context_cases(self):
        cases = [
            json.loads(line)
            for line in GOLDEN_SET.read_text(encoding="utf-8").splitlines()
            if line.strip()
        ]

        context_cases = [case for case in cases if case.get("recentTurns")]
        self.assertEqual(115, len(cases))
        self.assertEqual(100, len(cases) - len(context_cases))
        self.assertEqual(15, len(context_cases))
        self.assertEqual(len(cases), len({case["id"] for case in cases}))
        self.assertEqual(ROUTES, {case["expectedRoute"] for case in cases})
        for case in cases:
            self.assertTrue(case["query"].strip())
            self.assertIsInstance(case["requiredActions"], list)
            self.assertIsInstance(case["forbiddenActions"], list)
            self.assertIsInstance(case["critical"], bool)

    def test_semantic_sha256_matches_the_pinned_planner_gate(self):
        cases = [
            json.loads(line)
            for line in GOLDEN_SET.read_text(encoding="utf-8").splitlines()
            if line.strip()
        ]
        gates = json.loads(GATES_FILE.read_text(encoding="utf-8"))

        self.assertEqual(
            gates["planner_dataset_sha256_expected"],
            cases_sha256(cases),
        )

    def test_has_fifteen_strict_multiturn_live_cases_with_history_resolution(self):
        cases = [
            json.loads(line)
            for line in GOLDEN_SET.read_text(encoding="utf-8").splitlines()
            if line.strip()
        ]
        context_cases = [case for case in cases if case.get("recentTurns")]

        self.assertEqual(15, len(context_cases))
        self.assertEqual(ROUTES, {case["expectedRoute"] for case in context_cases})
        for route in ROUTES:
            self.assertGreaterEqual(
                sum(case["expectedRoute"] == route for case in context_cases),
                3,
            )
        for case in context_cases:
            recent_turns = case["recentTurns"]
            self.assertGreaterEqual(len(recent_turns), 2)
            self.assertLessEqual(len(recent_turns), 6)
            self.assertTrue(all(len(turn) <= 600 for turn in recent_turns))
            self.assertTrue(
                all(
                    turn.startswith("user: ") or turn.startswith("assistant: ")
                    for turn in recent_turns
                )
            )
            self.assertIn(case["expectedFineIntent"], FINE_INTENTS)
            self.assertEqual("INTENT_FUSION", case["expectedDecisionSource"])
            self.assertIs(case["requireNoFallback"], True)

            history_tickers = set(
                re.findall(r"\b[A-Z][A-Z.]{1,4}\b", " ".join(recent_turns))
            )
            expected_resolution = case["expectedResolvedQueryContains"]
            self.assertIn(expected_resolution, history_tickers)
            for ticker in history_tickers:
                self.assertIsNone(
                    re.search(rf"\b{re.escape(ticker)}\b", case["query"]),
                    f"{case['id']} repeats context ticker {ticker} in current query",
                )


if __name__ == "__main__":
    unittest.main()
