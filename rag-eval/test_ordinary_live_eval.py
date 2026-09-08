import json
import unittest

from run_ordinary_live_eval import assess


class OrdinaryLiveEvalTest(unittest.TestCase):
    def test_actual_arguments_and_citations_are_required_not_just_a_successful_route(self):
        case = {"id": "bars", "route": "MARKET", "outcomes": ["COMPLETED"],
                "request": {"bar": "1h"}, "calls": [{"tool": "getIbkrHistoricalBars", "args": ["AAPL", "1w", "1h"]}],
                "require_citations": True}
        steps = [{"attributes": {"kind": "routing-decision", "route": "MARKET"}},
                 {"action": "getIbkrHistoricalBars", "actionInput": '["AAPL","1w","1h"]', "attributes": {"stepKind": "tool", "outcome": "SUCCESS"}},
                 {"attributes": {"kind": "ordinary-evidence", "request": {"bar": "1h"}, "observations": [{"id": "E1", "citable": True}]}}]
        trace = {"status": "success", "taskOutcome": "COMPLETED", "steps": json.dumps(steps)}
        events = [{"type": "answer", "content": "数据 [E1]"}]
        self.assertTrue(assess(case, events, trace)["passed"])
        steps[1]["actionInput"] = '["AAPL","3m","1d"]'
        trace["steps"] = steps
        self.assertFalse(assess(case, events, trace)["passed"])
        steps[1]["actionInput"] = '["AAPL","1w","1h"]'
        events[0]["content"] = "数据 [E9]"
        self.assertFalse(assess(case, events, trace)["passed"])


if __name__ == "__main__":
    unittest.main()
