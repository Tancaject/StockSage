import json
import unittest
from pathlib import Path


GOLDEN_SET = Path(__file__).with_name("agent_golden_set.jsonl")
ROUTES = {"DIRECT", "MARKET", "FUNDAMENTALS", "NEWS", "DEEP"}


class AgentGoldenSetTest(unittest.TestCase):
    def test_has_at_least_one_hundred_unique_typed_cases(self):
        cases = [
            json.loads(line)
            for line in GOLDEN_SET.read_text(encoding="utf-8").splitlines()
            if line.strip()
        ]

        self.assertGreaterEqual(len(cases), 100)
        self.assertEqual(len(cases), len({case["id"] for case in cases}))
        self.assertEqual(ROUTES, {case["expectedRoute"] for case in cases})
        for case in cases:
            self.assertTrue(case["query"].strip())
            self.assertIsInstance(case["requiredActions"], list)
            self.assertIsInstance(case["forbiddenActions"], list)
            self.assertIsInstance(case["expectedSecondaryIntents"], list)
            self.assertIsInstance(case["critical"], bool)


if __name__ == "__main__":
    unittest.main()
