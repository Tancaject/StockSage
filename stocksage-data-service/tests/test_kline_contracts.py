import copy
import json
import unittest
from datetime import datetime
from pathlib import Path
from unittest.mock import patch

import httpx
from fastapi import FastAPI
from pydantic import ValidationError

from app.kline import KLineResponse
from app.routers import stock


FIXTURE = json.loads((Path(__file__).parent / "fixtures" / "kline-v1.json").read_text(encoding="utf-8"))


class KLineContractsTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        app = FastAPI()
        app.include_router(stock.router, prefix="/api/stock")
        self.client = httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test")
        self.addAsyncCleanup(self.client.aclose)

    async def test_fallback_route_serializes_the_shared_java_python_fixture(self):
        rows = [{key: str(value) for key, value in row.items()} for row in FIXTURE["data"]]
        raw = {"code": FIXTURE["code"], "period": "daily", "count": 999, "data": rows}
        original = copy.deepcopy(raw)
        with patch.object(stock.ak_svc, "get_a_share_kline", side_effect=TimeoutError("primary provider timed out")), \
                patch.object(stock.bao, "get_kline", return_value=raw), \
                patch("app.kline.datetime") as clock:
            clock.now.return_value = datetime.fromisoformat(FIXTURE["fetchedAt"])
            response = await self.client.get("/api/stock/kline", params={"code": FIXTURE["code"]})
        self.assertEqual(200, response.status_code, response.text)
        self.assertEqual(FIXTURE, response.json())
        self.assertEqual(original, raw)
        self.assertEqual(FIXTURE, KLineResponse.model_validate(FIXTURE).model_dump(mode="json", exclude_unset=True))

    async def test_hk_rows_keep_business_numbers_and_only_documented_units(self):
        raw = {"symbol": "00700", "provider": "akshare", "period": "weekly", "count": 100,
               "data": [{"Date": "2026-09-24", "Open": "10", "High": 12, "Low": 9,
                         "Close": 11, "Volume": 0, "Amount": float("nan"), "PctChange": 10,
                         "Change": 1, "TurnoverRate": 0.1}]}
        with patch.object(stock.ak_svc, "get_hk_kline", return_value=raw) as fetch:
            response = await self.client.get("/api/stock/kline", params={"code": "0700.HK", "period": "weekly", "days": 30})
        payload = response.json()
        self.assertEqual(200, response.status_code, response.text)
        fetch.assert_called_once_with("0700.HK", "weekly", 30)
        self.assertEqual(("SUCCESS", 1, "2026-09-24", "DATE"),
                         (payload["status"], payload["count"], payload["asOf"], payload["timeKind"]))
        self.assertEqual(("HKD", "SHARE", "FORWARD_ADJUSTED"),
                         (payload["currency"], payload["volumeUnit"], payload["adjustment"]))
        self.assertEqual({"date": "2026-09-24", "open": 10, "high": 12, "low": 9, "close": 11,
                          "volume": 0, "amount": None, "pctChg": 10, "change": 1, "turnoverRate": 0.1}, payload["data"][0])
        self.assertTrue(payload["fetchedAt"].endswith("Z"))
        self.assertNotIn("freshness", payload)
        json.dumps(payload, allow_nan=False)

        a_row = {**FIXTURE["data"][0], "amplitude": 2, "pctChg": 1, "turnoverRate": 0.2}
        with patch.object(stock.ak_svc, "get_a_share_kline", return_value={"data": [a_row]}):
            response = await self.client.get("/api/stock/kline", params={"code": "600519"})
        self.assertEqual(("CNY", "LOT"), (response.json()["currency"], response.json()["volumeUnit"]))
        self.assertEqual(2, response.json()["data"][0]["amplitude"])

    async def test_empty_unsupported_and_provider_errors_have_explicit_status(self):
        with patch.object(stock.ak_svc, "get_hk_kline", return_value={"data": [], "message": "No bars"}):
            response = await self.client.get("/api/stock/kline", params={"code": "0700.HK"})
        self.assertEqual(("EMPTY", False, None), (response.json()["status"], response.json()["error"], response.json()["asOf"]))
        with patch.object(stock.ak_svc, "get_hk_kline", side_effect=TimeoutError("upstream timeout")):
            response = await self.client.get("/api/stock/kline", params={"code": "0700.HK"})
        self.assertEqual(("ERROR", True, "UPSTREAM_ERROR"),
                         (response.json()["status"], response.json()["retryable"], response.json()["errorCode"]))
        with patch.object(stock.ak_svc, "get_hk_kline", side_effect=RuntimeError("provider failed")):
            response = await self.client.get("/api/stock/kline", params={"code": "0700.HK"})
        self.assertIsNone(response.json()["retryable"])
        with patch.object(stock.ak_svc, "get_a_share_kline") as a_fetch, patch.object(stock.ak_svc, "get_hk_kline") as hk_fetch:
            response = await self.client.get("/api/stock/kline", params={"code": "AAPL"})
        a_fetch.assert_not_called()
        hk_fetch.assert_not_called()
        self.assertEqual(("UNSUPPORTED", True, False, None, []),
                         tuple(response.json()[key] for key in ("status", "error", "retryable", "adjustment", "data")))

    async def test_invalid_required_bar_data_cannot_be_success_and_inputs_are_validated(self):
        good = FIXTURE["data"][0]
        for invalid in ({**good, "close": float("nan")}, {**good, "open": True}, {**good, "date": "not-a-date"}):
            with self.subTest(invalid=invalid):
                with patch.object(stock.ak_svc, "get_hk_kline", return_value={"data": [good, invalid]}):
                    response = await self.client.get("/api/stock/kline", params={"code": "0700.HK"})
                self.assertEqual(200, response.status_code, response.text)
                payload = response.json()
                self.assertEqual(("ERROR", True, "INVALID_PROVIDER_DATA", None),
                                 tuple(payload[key] for key in ("status", "error", "errorCode", "asOf")))
                if invalid.get("date") == good["date"] and not isinstance(invalid.get("open"), bool):
                    self.assertEqual(2, payload["count"])
                    self.assertIsNone(payload["data"][1]["close"])
        with patch.object(stock.ak_svc, "get_hk_kline") as fetch:
            for parameters in ({"period": "minute"}, {"days": 0}):
                response = await self.client.get("/api/stock/kline", params={"code": "0700.HK", **parameters})
                self.assertEqual(422, response.status_code)
        fetch.assert_not_called()
        for change in ({"error": True}, {"count": 99}, {"asOf": "2026-09-25"}):
            with self.assertRaises(ValidationError):
                KLineResponse.model_validate({**FIXTURE, **change})


if __name__ == "__main__":
    unittest.main()
