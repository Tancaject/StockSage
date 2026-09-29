import copy
import json
import unittest
from datetime import datetime
from pathlib import Path
from unittest.mock import patch

import httpx
from fastapi import FastAPI
from pydantic import ValidationError

from app.hk_financials import HkFinancialsResponse
from app.routers import stock


FIXTURE = json.loads((Path(__file__).parent / "fixtures" / "hk-financials-v1.json").read_text(encoding="utf-8"))


def provider_payload():
    return copy.deepcopy({
        "symbol": FIXTURE["symbol"], "period": FIXTURE["period"], "provider": "akshare",
        "statements": {name: table["data"] for name, table in FIXTURE["statements"].items()},
        "indicators": FIXTURE["indicators"]["data"], "statementCount": 999,
        "errors": {"cashFlow": "Synthetic provider failure"},
    })


class HKFinancialsContractsTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        app = FastAPI()
        app.include_router(stock.router, prefix="/api/stock")
        self.client = httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test")
        self.addAsyncCleanup(self.client.aclose)

    async def request(self, raw, **parameters):
        with patch.object(stock.ak_svc, "get_hk_financial_reports", return_value=raw):
            response = await self.client.get("/api/stock/financial-report", params={"code": "0700.HK", "years": 2, **parameters})
        self.assertEqual(200, response.status_code, response.text)
        return response.json()

    async def test_shared_fixture_exact_asgi_preserves_decimal_strings_null_rows_and_actual_asof(self):
        raw = provider_payload()
        for rows in [*raw["statements"].values(), raw["indicators"]]:
            for row in rows:
                row["REPORT_DATE"] += " 00:00:00"
                row["FISCAL_YEAR"] = int(row["FISCAL_YEAR"])
                row.pop("amountUnit", None)
        original = copy.deepcopy(raw)
        class FrozenDatetime(datetime):
            @classmethod
            def now(cls, tz=None):
                return datetime.fromisoformat(FIXTURE["fetchedAt"])
        with patch.object(stock.ak_svc, "get_hk_financial_reports", return_value=raw) as fetch, \
                patch("app.hk_financials.datetime", FrozenDatetime):
            response = await self.client.get("/api/stock/financial-report", params={"code": "0700.HK", "years": 2})
        self.assertEqual(200, response.status_code, response.text)
        fetch.assert_called_once_with("0700.HK", "annual", 2)
        self.assertEqual(FIXTURE, response.json())
        self.assertEqual(original, raw)
        self.assertEqual(FIXTURE, HkFinancialsResponse.model_validate(FIXTURE).model_dump(mode="json"))
        self.assertEqual("2024-12-31", response.json()["asOf"])
        self.assertEqual(9, response.json()["statementCount"])

    async def test_provider_numbers_keep_their_current_precision_and_do_not_infer_currency(self):
        raw = provider_payload()
        raw["statements"]["balanceSheet"][2]["AMOUNT"] = 9007199254740993
        raw["statements"]["balanceSheet"][3]["AMOUNT"] = 0
        raw["indicators"][1]["BPS"] = 0.123456789012345
        raw["indicators"][1]["OPERATE_INCOME"] = "1.23456789E+10"
        result = await self.request(raw)
        self.assertEqual("9007199254740993", result["statements"]["balanceSheet"]["data"][2]["AMOUNT"])
        self.assertEqual("0", result["statements"]["balanceSheet"]["data"][3]["AMOUNT"])
        self.assertEqual("0.123456789012345", result["indicators"]["data"][1]["BPS"])
        self.assertEqual("12345678900", result["indicators"]["data"][1]["OPERATE_INCOME"])
        self.assertEqual((None, "PROVIDER_VALUE", "UNKNOWN"),
                         tuple(result[key] for key in ("currency", "numericPrecision", "aggregationBasis")))
        self.assertEqual("provider currency label", result["indicators"]["data"][1]["CURRENCY"])

    async def test_invalid_rows_fail_their_entire_table_and_keep_other_tables(self):
        for change in ({"AMOUNT": True}, {"AMOUNT": "NaN"}, {"AMOUNT": float("inf")},
                       {"AMOUNT": "not numeric"}, {"REPORT_DATE": "2025-02-30"},
                       {"REPORT_DATE": 1767139200}, {"START_DATE": "2026-01-01"},
                       {"STD_REPORT_DATE": "invalid"}, {"SECURITY_CODE": 700},
                       {"SECUCODE": "00701.HK"}, {"STD_ITEM_CODE": ""}, {"STD_ITEM_NAME": None},
                       {"STD_ITEM_CODE": " "}, {"STD_ITEM_NAME": "\t"}):
            with self.subTest(change=change):
                raw = provider_payload()
                raw["statements"]["balanceSheet"][0].update(change)
                result = await self.request(raw)
                table = result["statements"]["balanceSheet"]
                self.assertEqual(("ERROR", [], "INVALID_PROVIDER_DATA", False),
                                 tuple(table[key] for key in ("status", "data", "errorCode", "retryable")))
                self.assertEqual(("PARTIAL", False, "SUCCESS"),
                                 (result["status"], result["error"], result["statements"]["incomeStatement"]["status"]))
        for change in ({"BASIC_EPS": True}, {"ROE_AVG": "Infinity"}, {"IS_CNY_CODE": True}, {"IS_CNY_CODE": "0"}):
            raw = provider_payload()
            raw["indicators"][0].update(change)
            self.assertEqual("INVALID_PROVIDER_DATA", (await self.request(raw))["indicators"]["errorCode"])

    async def test_success_empty_and_upstream_errors_are_distinct(self):
        raw = provider_payload()
        raw["errors"] = {}
        raw["statements"]["cashFlow"] = [copy.deepcopy(raw["statements"]["balanceSheet"][3])]
        self.assertEqual("SUCCESS", (await self.request(raw))["status"])
        raw["statements"] = {name: [] for name in raw["statements"]}
        raw["indicators"] = []
        empty = await self.request(raw)
        self.assertEqual(("EMPTY", False, None, 0), tuple(empty[key] for key in ("status", "error", "asOf", "statementCount")))
        null_only = provider_payload()
        null_only["errors"] = {}
        null_only["statements"] = {name: [row for row in rows if row["REPORT_DATE"] == "2025-12-31"]
                                   for name, rows in null_only["statements"].items()}
        null_only["indicators"] = null_only["indicators"][:1]
        self.assertEqual("EMPTY", (await self.request(null_only))["status"])
        raw["errors"] = {name: "Synthetic upstream failure" for name in [*raw["statements"], "indicators"]}
        failed = await self.request(raw)
        self.assertEqual(("ERROR", True, "TABLES_UNAVAILABLE", None),
                         tuple(failed[key] for key in ("status", "error", "errorCode", "asOf")))
        for error, retryable in ((TimeoutError("timeout"), True), (RuntimeError("timeout"), None)):
            with patch.object(stock.ak_svc, "get_hk_financial_reports", side_effect=error):
                response = await self.client.get("/api/stock/financial-report", params={"code": "0700.HK", "years": 2})
            self.assertEqual(("ERROR", "UPSTREAM_ERROR", retryable),
                             tuple(response.json()[key] for key in ("status", "errorCode", "retryable")))

    async def test_scope_uses_distinct_report_dates_and_keeps_report_period_semantics(self):
        raw = provider_payload()
        raw["period"] = "report_period"
        result = await self.request(raw, period="quarterly", years=1)
        self.assertEqual(("report_period", "quarterly", 1, "UNKNOWN", 9),
                         tuple(result[key] for key in ("period", "requestedPeriod", "requestedYears", "aggregationBasis", "statementCount")))
        raw["period"] = "annual"
        result = await self.request(raw, years=1)
        self.assertEqual("ERROR", result["status"])
        self.assertEqual("INVALID_PROVIDER_DATA", result["statements"]["balanceSheet"]["errorCode"])
        for change in ({"symbol": "0700"}, {"symbol": "00701"}, {"period": "report_period"}, {"statements": None}):
            result = await self.request({**provider_payload(), **change})
            self.assertEqual(("ERROR", "INVALID_PROVIDER_DATA"), (result["status"], result["errorCode"]))

    def test_model_rejects_conflicting_coverage_metadata_and_noncanonical_wire(self):
        for change in ({"status": "SUCCESS"}, {"error": True}, {"statementCount": 2}, {"asOf": "2025-12-31"},
                       {"requestedYears": 1}, {"requestedPeriod": "quarterly"}, {"resolvedCode": "0701.HK"},
                       {"currency": "HKD"}, {"numericPrecision": "EXACT"}, {"fetchedAt": "2026-09-25T01:02:03"},
                       {"errorCode": "UPSTREAM_ERROR"}, {"retryable": True}):
            with self.subTest(change=change), self.assertRaises(ValidationError):
                HkFinancialsResponse.model_validate({**FIXTURE, **change})
        for amount in (True, 12.34, "1E3", "NaN"):
            fixture = copy.deepcopy(FIXTURE)
            fixture["statements"]["balanceSheet"]["data"][0]["AMOUNT"] = amount
            with self.subTest(amount=amount), self.assertRaises(ValidationError):
                HkFinancialsResponse.model_validate(fixture)
        for code in (-(2**63) - 1, 2**63):
            fixture = copy.deepcopy(FIXTURE)
            fixture["indicators"]["data"][0]["IS_CNY_CODE"] = code
            with self.subTest(code=code), self.assertRaises(ValidationError):
                HkFinancialsResponse.model_validate(fixture)
        fixture = copy.deepcopy(FIXTURE)
        fixture["statements"]["cashFlow"]["errorCode"] = "\t "
        with self.assertRaises(ValidationError):
            HkFinancialsResponse.model_validate(fixture)


if __name__ == "__main__":
    unittest.main()
