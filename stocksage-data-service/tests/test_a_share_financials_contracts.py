import copy
import json
import unittest
from datetime import datetime
from pathlib import Path
from unittest.mock import patch

import httpx
from fastapi import FastAPI
from pydantic import ValidationError

from app.a_share_financials import AShareFinancialsResponse
from app.routers import stock


FIXTURE = json.loads((Path(__file__).parent / "fixtures" / "a-share-financials-v1.json").read_text(encoding="utf-8"))


def provider_payload():
    reports = []
    for report in FIXTURE["reports"]:
        statements = {}
        for name, result in report["statements"].items():
            if result["status"] == "SUCCESS":
                statements[name] = {key: value for key, value in result["data"].items() if not key.endswith("Unit")}
            elif result["status"] == "ERROR":
                statements[name] = {"error": result["message"]}
            else:
                statements[name] = {}
        reports.append({"year": report["year"], "quarter": report["quarter"], "statements": statements})
    return {"code": FIXTURE["code"], "period": "annual", "count": 999, "reports": reports}


class AShareFinancialsContractsTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        app = FastAPI()
        app.include_router(stock.router, prefix="/api/stock")
        self.client = httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test")
        self.addAsyncCleanup(self.client.aclose)

    async def request(self, raw, **parameters):
        with patch.object(stock.bao, "get_financial_reports", return_value=raw):
            response = await self.client.get("/api/stock/financial-report", params={"code": "sh.600519", "years": 2, **parameters})
        self.assertEqual(200, response.status_code, response.text)
        return response.json()

    async def test_shared_fixture_exact_asgi_decimal_precision_and_raw_data_are_preserved(self):
        raw = provider_payload()
        raw["reports"][0]["statements"]["profit"]["liqaShare"] = ""
        original = copy.deepcopy(raw)
        with patch.object(stock.bao, "get_financial_reports", return_value=raw) as fetch, \
                patch("app.a_share_financials.datetime") as clock:
            clock.now.return_value = datetime.fromisoformat(FIXTURE["fetchedAt"])
            response = await self.client.get("/api/stock/financial-report", params={"code": "sh.600519", "years": 2})
        self.assertEqual(200, response.status_code, response.text)
        fetch.assert_called_once_with("sh.600519", "annual", 2)
        self.assertEqual(FIXTURE, response.json())
        self.assertEqual(original, raw)
        self.assertEqual(FIXTURE, AShareFinancialsResponse.model_validate(FIXTURE).model_dump(mode="json"))
        self.assertEqual("9007199254740993.123456789", response.json()["reports"][0]["statements"]["profit"]["data"]["netProfit"])
        self.assertEqual("0", response.json()["reports"][1]["statements"]["profit"]["data"]["netProfit"])
        self.assertEqual(1, sum(report["status"] == "SUCCESS" for report in response.json()["reports"]))

    async def test_invalid_row_numbers_identity_or_dates_remain_explicit_statement_failures(self):
        for change in ({"netProfit": True}, {"netProfit": "NaN"}, {"netProfit": "Infinity"},
                       {"netProfit": float("inf")}, {"netProfit": "not a number"},
                       {"netProfit": 12.34}, {"pubDate": "2025-01-01"}, {"statDate": "2025-09-30"},
                       {"statDate": 1767139200}, {"statDate": "not-a-date"}, {"code": "sz.000001"}):
            with self.subTest(change=change):
                raw = provider_payload()
                raw["reports"][0]["statements"]["profit"].update(change)
                result = await self.request(raw)
                statement = result["reports"][0]["statements"]["profit"]
                self.assertEqual(("ERROR", None, "INVALID_PROVIDER_DATA", False),
                                 tuple(statement[key] for key in ("status", "data", "errorCode", "retryable")))
                self.assertEqual(("PARTIAL", False, "PARTIAL"),
                                 (result["status"], result["error"], result["reports"][0]["status"]))
                json.dumps(result, allow_nan=False)

    async def test_empty_unknown_values_and_zero_are_not_confused_with_success_or_error(self):
        raw = provider_payload()
        raw["reports"] = [raw["reports"][0]]
        statements = raw["reports"][0]["statements"]
        for name, row in statements.items():
            statements[name] = {key: value if key in {"code", "pubDate", "statDate"} else "" for key, value in row.items()}
        empty = await self.request(raw)
        self.assertEqual(("EMPTY", False, None, 1), tuple(empty[key] for key in ("status", "error", "asOf", "count")))
        self.assertTrue(all(result["status"] == "EMPTY" and result["data"] is None
                            for result in empty["reports"][0]["statements"].values()))
        statements["profit"]["netProfit"] = "0"
        partial = await self.request(raw)
        self.assertEqual("PARTIAL", partial["status"])
        self.assertEqual("0", partial["reports"][0]["statements"]["profit"]["data"]["netProfit"])
        statements["profit"]["netProfit"] = "1.234E+4"
        self.assertEqual("12340", (await self.request(raw))["reports"][0]["statements"]["profit"]["data"]["netProfit"])

    async def test_upstream_statement_global_errors_and_empty_data_remain_distinct(self):
        raw = provider_payload()
        raw["reports"] = [raw["reports"][0]]
        raw["reports"][0]["statements"] = {name: {"error": "Synthetic query error"} for name in raw["reports"][0]["statements"]}
        failed = await self.request(raw)
        self.assertEqual(("ERROR", True, None, 1, "STATEMENTS_UNAVAILABLE"),
                         tuple(failed[key] for key in ("status", "error", "asOf", "count", "errorCode")))
        self.assertIsNone(failed["reports"][0]["statements"]["profit"]["retryable"])
        login = await self.request({"error": True, "message": "BaoStock login unavailable", "reports": []})
        self.assertEqual(("ERROR", "UPSTREAM_ERROR", [], None),
                         tuple(login[key] for key in ("status", "errorCode", "reports", "retryable")))
        empty = await self.request({"code": "sh.600519", "period": "annual", "reports": [], "count": 0})
        self.assertEqual(("EMPTY", False, None, []), tuple(empty[key] for key in ("status", "error", "asOf", "reports")))
        for failure, retryable in ((TimeoutError("timeout"), True), (RuntimeError("timeout"), None)):
            with patch.object(stock.bao, "get_financial_reports", side_effect=failure):
                response = await self.client.get("/api/stock/financial-report", params={"code": "sh.600519", "years": 2})
            self.assertEqual(("ERROR", "UPSTREAM_ERROR", retryable),
                             tuple(response.json()[key] for key in ("status", "errorCode", "retryable")))

    async def test_request_scope_period_count_and_actual_quarter_are_checked(self):
        raw = provider_payload()
        raw["reports"] = [raw["reports"][0]]
        success = await self.request(raw)
        self.assertEqual(("SUCCESS", 1, "2025-12-31"), tuple(success[key] for key in ("status", "count", "asOf")))
        for alteration in ("duplicate", "excess", "annual_quarter", "identity"):
            broken = copy.deepcopy(raw)
            if alteration == "duplicate":
                broken["reports"].append(copy.deepcopy(broken["reports"][0]))
            elif alteration == "excess":
                broken = provider_payload()
            elif alteration == "annual_quarter":
                broken["reports"][0]["quarter"] = 3
            else:
                broken["code"] = "sz.000001"
            result = await self.request(broken, years=1 if alteration == "excess" else 2)
            self.assertEqual(("ERROR", "INVALID_PROVIDER_DATA", []),
                             tuple(result[key] for key in ("status", "errorCode", "reports")))
        raw["period"] = "quarterly"
        raw["reports"][0]["quarter"] = 3
        for row in raw["reports"][0]["statements"].values():
            row["statDate"] = "2025-09-30"
        quarterly = await self.request(raw, period="quarterly", years=1)
        self.assertEqual(("SUCCESS", "quarterly", 1, "2025-09-30", "UNKNOWN"),
                         tuple(quarterly[key] for key in ("status", "requestedPeriod", "requestedYears", "asOf", "aggregationBasis")))

    def test_model_rejects_conflicting_status_scope_units_and_claimed_coverage(self):
        for change in ({"status": "SUCCESS"}, {"error": True}, {"count": 1}, {"asOf": "2026-09-25"},
                       {"period": "quarterly"}, {"requestedYears": 1}, {"currency": "CNY"},
                       {"valueEncoding": "NUMBER"}, {"fetchedAt": "2026-09-25T01:02:03"}):
            with self.subTest(change=change), self.assertRaises(ValidationError):
                AShareFinancialsResponse.model_validate({**FIXTURE, **change})
        for path, value in (("netProfitUnit", "CNY"), ("statDate", "2024-12-31"), ("code", "sz.000001")):
            payload = copy.deepcopy(FIXTURE)
            payload["reports"][0]["statements"]["profit"]["data"][path] = value
            with self.subTest(path=path), self.assertRaises(ValidationError):
                AShareFinancialsResponse.model_validate(payload)


if __name__ == "__main__":
    unittest.main()
