import copy
import json
import unittest
from datetime import datetime
from pathlib import Path
from unittest.mock import patch

import httpx
from fastapi import FastAPI
from pydantic import ValidationError

from app.routers import edgar, stock
from app.sec_financials import SecFinancialsResponse


FIXTURE = json.loads((Path(__file__).parent / "fixtures" / "sec-financials-v1.json").read_text(encoding="utf-8"))


def provider_payload():
    return copy.deepcopy({key: FIXTURE[key] for key in ("ticker", "company_name", "cik", "metric_count", "metrics")})


class SecFinancialsContractsTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        app = FastAPI()
        app.include_router(edgar.router, prefix="/api/edgar")
        app.include_router(stock.router, prefix="/api/stock")
        self.client = httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test")
        self.addAsyncCleanup(self.client.aclose)

    async def test_route_serializes_shared_fixture_without_mutating_provider_facts(self):
        raw = provider_payload()
        raw["metric_count"] = 999
        original = copy.deepcopy(raw)
        with patch.object(edgar.edgar, "get_xbrl", return_value=raw) as fetch, \
                patch("app.sec_financials.datetime") as clock:
            clock.now.return_value = datetime.fromisoformat(FIXTURE["fetchedAt"])
            response = await self.client.get("/api/edgar/xbrl", params={"ticker": "TEST"})
        self.assertEqual(200, response.status_code, response.text)
        fetch.assert_called_once_with("TEST")
        self.assertEqual(FIXTURE, response.json())
        self.assertEqual(original, raw)
        self.assertEqual(FIXTURE, SecFinancialsResponse.model_validate(FIXTURE).model_dump(mode="json"))

        raw["metrics"]["Revenue"]["data"][0]["value"] = 9007199254740993
        with patch.object(edgar.edgar, "get_xbrl", return_value=raw):
            response = await self.client.get("/api/edgar/xbrl", params={"ticker": "TEST"})
        self.assertEqual(9007199254740993, response.json()["metrics"]["Revenue"]["data"][0]["value"])

    async def test_invalid_fact_numbers_dates_periods_and_sources_cannot_be_success(self):
        changes = (
            {"value": True}, {"value": "105"}, {"value": float("nan")}, {"value": None},
            {"end": "not-a-date"}, {"end": 1769817600}, {"start": None},
            {"filed": "2025-01-01"}, {"source_url": "https://example.com/facts.json"},
            {"source_url": "https://data.sec.gov/api/xbrl/companyfacts/CIK0000000002.json"},
        )
        for change in changes:
            with self.subTest(change=change):
                raw = provider_payload()
                raw["metrics"]["Revenue"]["data"][0].update(change)
                with patch.object(edgar.edgar, "get_xbrl", return_value=raw):
                    response = await self.client.get("/api/edgar/xbrl", params={"ticker": "TEST"})
                self.assertEqual(200, response.status_code, response.text)
                payload = response.json()
                self.assertEqual(("ERROR", True, "INVALID_PROVIDER_DATA", False, 0, {}, None),
                                 tuple(payload[key] for key in ("status", "error", "errorCode", "retryable", "metric_count", "metrics", "asOf")))
                json.dumps(payload, allow_nan=False)

        for metrics in ({"UnknownMetric": FIXTURE["metrics"]["Revenue"]},
                        {"Revenue": {**FIXTURE["metrics"]["Revenue"], "data": []}}):
            with patch.object(edgar.edgar, "get_xbrl", return_value={**provider_payload(), "metrics": metrics}):
                response = await self.client.get("/api/edgar/xbrl", params={"ticker": "TEST"})
            self.assertEqual("INVALID_PROVIDER_DATA", response.json()["errorCode"])

    async def test_empty_and_upstream_errors_have_explicit_status_without_guessing_retryability(self):
        with patch.object(edgar.edgar, "get_xbrl", return_value={**provider_payload(), "metrics": {}}):
            response = await self.client.get("/api/edgar/xbrl", params={"ticker": "TEST"})
        self.assertEqual(("EMPTY", False, 0, None),
                         tuple(response.json()[key] for key in ("status", "error", "metric_count", "asOf")))
        failures = [(TimeoutError("timeout"), True), (httpx.ReadTimeout("timeout"), True),
                    (RuntimeError("timeout written in a message is not an exception type"), None)]
        for status in (429, 503, 404):
            reply = httpx.Response(status, request=httpx.Request("GET", "https://data.sec.gov/facts"))
            failures.append((httpx.HTTPStatusError("SEC response", request=reply.request, response=reply), status != 404))
        for failure, retryable in failures:
            with self.subTest(failure=type(failure).__name__, retryable=retryable):
                with patch.object(edgar.edgar, "get_xbrl", side_effect=failure):
                    response = await self.client.get("/api/edgar/xbrl", params={"ticker": "TEST"})
                payload = response.json()
                self.assertEqual(("ERROR", True, "UPSTREAM_ERROR", retryable, None),
                                 tuple(payload[key] for key in ("status", "error", "errorCode", "retryable", "asOf")))
        with patch.object(edgar.edgar, "get_xbrl") as fetch:
            response = await self.client.get("/api/edgar/xbrl", params={"ticker": ""})
        self.assertEqual(422, response.status_code)
        fetch.assert_not_called()

    def test_model_rejects_conflicting_status_count_and_fetch_time_as_business_date(self):
        for change in ({"error": True}, {"metric_count": 1}, {"asOf": "2026-09-25"},
                       {"status": "EMPTY"}, {"fetchedAt": "2026-09-25T01:02:03"},
                       {"requestedPeriod": "annual"}, {"requestedPeriod": "quarterly", "requestedYears": 1}):
            with self.subTest(change=change), self.assertRaises(ValidationError):
                SecFinancialsResponse.model_validate({**FIXTURE, **change})

    async def test_us_annual_reports_select_distinct_periods_without_dropping_same_end_facts(self):
        raw = provider_payload()
        latest = raw["metrics"]["Revenue"]["data"][0]
        previous = {**latest, "start": "2024-02-01", "end": "2025-01-31", "value": 80}
        oldest = {**latest, "start": "2023-02-01", "end": "2024-01-31", "value": 70}
        same_end = {**latest, "start": "2025-01-27", "value": 106}
        raw["metrics"]["Revenue"]["data"] = [latest, oldest, same_end, previous]
        original = copy.deepcopy(raw)
        with patch.object(stock.edgar, "get_xbrl", return_value=raw) as fetch:
            response = await self.client.get("/api/stock/financial-report", params={"code": "TEST", "period": "annual", "years": 2})
        self.assertEqual(200, response.status_code, response.text)
        fetch.assert_called_once_with("TEST")
        payload = response.json()
        self.assertEqual(("SUCCESS", "annual", "annual", 2, 3, "2026-01-31"),
                         tuple(payload[key] for key in ("status", "period", "requestedPeriod", "requestedYears", "metric_count", "asOf")))
        self.assertEqual(("TEST", "TEST", "ibkr", "SEC_EDGAR", "US ticker"),
                         tuple(payload[key] for key in ("input", "resolvedCode", "source", "provider", "routeReason")))
        rows = payload["metrics"]["Revenue"]["data"]
        self.assertEqual([80, 106, 105], [row["value"] for row in rows])
        self.assertEqual(2, len({row["end"] for row in rows}))
        self.assertEqual(1, len(payload["metrics"]["TotalAssets"]["data"]))
        self.assertEqual(original, raw)
        self.assertEqual(payload, SecFinancialsResponse.model_validate(payload).model_dump(mode="json"))
        with self.assertRaises(ValidationError):
            SecFinancialsResponse.model_validate({**payload, "requestedYears": 1})

        # 请求窗口外的损坏记录也必须先被拒绝，不能靠裁剪掩盖供应商契约错误。
        raw["metrics"]["Revenue"]["data"][1]["value"] = True
        with patch.object(stock.edgar, "get_xbrl", return_value=raw):
            response = await self.client.get("/api/stock/financial-report", params={"code": "TEST", "years": 1})
        self.assertEqual("INVALID_PROVIDER_DATA", response.json()["errorCode"])

    async def test_us_quarterly_is_unsupported_before_fetch_and_failures_keep_request_metadata(self):
        with patch.object(stock.edgar, "get_xbrl") as fetch:
            response = await self.client.get("/api/stock/financial-report", params={"code": "TEST", "period": "quarterly", "years": 3})
        fetch.assert_not_called()
        payload = response.json()
        self.assertEqual(("UNSUPPORTED", True, "UNSUPPORTED_PERIOD", False, "quarterly", 3, {}, None),
                         tuple(payload[key] for key in ("status", "error", "errorCode", "retryable", "requestedPeriod", "requestedYears", "metrics", "asOf")))
        self.assertIsNone(payload["cik"])
        self.assertEqual("TEST", payload["resolvedCode"])

        with patch.object(stock.edgar, "get_xbrl", side_effect=httpx.ReadTimeout("upstream timeout")):
            response = await self.client.get("/api/stock/financial-report", params={"code": "TEST", "years": 2})
        self.assertEqual(("ERROR", True, "annual", 2, "TEST"),
                         tuple(response.json()[key] for key in ("status", "retryable", "requestedPeriod", "requestedYears", "resolvedCode")))

    async def test_financial_report_inputs_are_validated_and_a_h_routes_use_their_contracts(self):
        with patch.object(stock.edgar, "get_xbrl") as fetch:
            for parameters in ({"period": "monthly"}, {"years": 0}, {"years": 11}):
                response = await self.client.get("/api/stock/financial-report", params={"code": "TEST", **parameters})
                self.assertEqual(422, response.status_code)
        fetch.assert_not_called()

        a_payload = {"code": "sh.600519", "period": "annual", "reports": [], "count": 0}
        hk_payload = {"symbol": "00700", "period": "report_period",
                      "statements": {name: [] for name in ("balanceSheet", "incomeStatement", "cashFlow")},
                      "indicators": [], "errors": {}}
        with patch.object(stock.bao, "get_financial_reports", return_value=a_payload) as a_fetch, \
                patch.object(stock.ak_svc, "get_hk_financial_reports", return_value=hk_payload) as hk_fetch, \
                patch.object(stock.edgar, "get_xbrl") as sec_fetch:
            a_result = (await self.client.get("/api/stock/financial-report", params={"code": "sh.600519", "years": 2})).json()
            hk_result = (await self.client.get("/api/stock/financial-report", params={"code": "0700.HK", "period": "quarterly", "years": 2})).json()
        a_fetch.assert_called_once_with("sh.600519", "annual", 2)
        hk_fetch.assert_called_once_with("0700.HK", "quarterly", 2)
        sec_fetch.assert_not_called()
        self.assertEqual(a_payload, {key: a_result[key] for key in a_payload})
        self.assertEqual(("00700", "report_period", "quarterly", 2),
                         tuple(hk_result[key] for key in ("symbol", "period", "requestedPeriod", "requestedYears")))
        self.assertEqual((1, "EMPTY"), (a_result["schemaVersion"], a_result["status"]))
        self.assertEqual((1, "EMPTY"), (hk_result["schemaVersion"], hk_result["status"]))


if __name__ == "__main__":
    unittest.main()
