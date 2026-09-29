import json
import unittest
from pathlib import Path

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

    def test_market_case_requires_qualified_time_gap_and_successful_citable_data(self):
        case = json.loads((Path(__file__).parent / "ordinary_live_cases.jsonl").read_text(encoding="utf-8").splitlines()[0])
        observation = {"id": "E1", "tool": "getIbkrHistoricalBars", "status": "AVAILABLE", "citable": True,
                       "freshnessStatus": "UNKNOWN", "freshnessReason": "MARKET_SESSION_UNVERIFIED"}
        call = {"action": "getIbkrHistoricalBars", "actionInput": json.dumps(case["calls"][0]["args"]),
                "attributes": {"stepKind": "tool", "outcome": "SUCCESS"}}
        trace = {"status": "success", "taskOutcome": "DEGRADED", "steps": [
            {"attributes": {"kind": "routing-decision", "route": "MARKET"}}, call,
            {"attributes": {"kind": "ordinary-evidence", "request": case["request"], "observations": [observation]}}]}
        events = [{"type": "answer", "content": "已有行情 [E1]，最新交易周期尚未确认。"}]
        self.assertTrue(assess(case, events, trace)["passed"])
        for key, value in (("freshnessStatus", "FRESH"), ("freshnessReason", "TIME_CONTRACT_MISSING"),
                           ("status", "FAILED"), ("citable", False)):
            original = observation[key]
            observation[key] = value
            self.assertFalse(assess(case, events, trace)["passed"])
            observation[key] = original
        call["attributes"]["outcome"] = "FAILED"
        self.assertFalse(assess(case, events, trace)["passed"])


if __name__ == "__main__":
    unittest.main()
