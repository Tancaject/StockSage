import sys
import importlib.util
import types
import unittest
from unittest.mock import patch

import pandas as pd

fake_fastapi = types.ModuleType("fastapi")


class FakeAPIRouter:
    def get(self, *args, **kwargs):
        def decorator(fn):
            return fn

        return decorator


fake_fastapi.APIRouter = FakeAPIRouter
fake_fastapi.Query = lambda default, *args, **kwargs: default
if importlib.util.find_spec("fastapi") is None:
    sys.modules.setdefault("fastapi", fake_fastapi)

fake_httpx = types.ModuleType("httpx")


class FakeHttpxClient:
    def __init__(self, *args, **kwargs):
        pass

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False


fake_httpx.Client = FakeHttpxClient
fake_httpx.Response = object
fake_httpx.get = lambda *args, **kwargs: None
if importlib.util.find_spec("httpx") is None:
    sys.modules.setdefault("httpx", fake_httpx)

fake_bs4 = types.ModuleType("bs4")
fake_bs4.BeautifulSoup = object
fake_bs4.NavigableString = str
fake_bs4.Tag = object
if importlib.util.find_spec("bs4") is None:
    sys.modules.setdefault("bs4", fake_bs4)

fake_ddg = types.ModuleType("duckduckgo_search")
fake_ddg.DDGS = object
sys.modules.setdefault("duckduckgo_search", fake_ddg)

fake_dotenv = types.ModuleType("dotenv")
fake_dotenv.load_dotenv = lambda *args, **kwargs: False
sys.modules.setdefault("dotenv", fake_dotenv)

fake_baostock = types.ModuleType("baostock")

fake_baostock.login = lambda: types.SimpleNamespace(error_code="0", error_msg="success")
fake_baostock.logout = lambda: None
fake_baostock.query_history_k_data_plus = lambda *args, **kwargs: None
fake_baostock.query_profit_data = lambda *args, **kwargs: None
fake_baostock.query_operation_data = lambda *args, **kwargs: None
fake_baostock.query_growth_data = lambda *args, **kwargs: None
fake_baostock.query_balance_data = lambda *args, **kwargs: None
fake_baostock.query_cash_flow_data = lambda *args, **kwargs: None
fake_baostock.query_dupont_data = lambda *args, **kwargs: None

sys.modules.setdefault("baostock", fake_baostock)

from app.routers import stock
from app.services.akshare_service import AkshareService


class FakeAk:
    def __init__(self):
        self.calls = []

    def stock_zh_a_hist(self, **kwargs):
        self.calls.append(kwargs)
        return pd.DataFrame([
            {
                "\u65e5\u671f": "2026-05-29",
                "\u80a1\u7968\u4ee3\u7801": "600519",
                "\u5f00\u76d8": 1500.0,
                "\u6536\u76d8": 1510.0,
                "\u6700\u9ad8": 1520.0,
                "\u6700\u4f4e": 1490.0,
                "\u6210\u4ea4\u91cf": 123456,
                "\u6210\u4ea4\u989d": 987654321.0,
                "\u632f\u5e45": 2.0,
                "\u6da8\u8dcc\u5e45": 1.2,
                "\u6da8\u8dcc\u989d": 18.0,
                "\u6362\u624b\u7387": 0.5,
            }
        ])


class AShareProviderFallbackTest(unittest.TestCase):
    def test_akshare_service_gets_a_share_kline_from_stock_zh_a_hist(self):
        service = AkshareService()
        fake_ak = FakeAk()

        with patch.object(service, "_ak", return_value=fake_ak):
            result = service.get_a_share_kline("sh.600519", "daily", 30)

        self.assertEqual(fake_ak.calls[0]["symbol"], "600519")
        self.assertEqual(fake_ak.calls[0]["period"], "daily")
        self.assertEqual(fake_ak.calls[0]["adjust"], "qfq")
        self.assertEqual(result["provider"], "akshare")
        self.assertEqual(result["code"], "sh.600519")
        self.assertEqual(result["symbol"], "600519")
        self.assertEqual(result["count"], 1)
        self.assertEqual(result["data"][0]["date"], "2026-05-29")
        self.assertEqual(result["data"][0]["open"], 1500.0)
        self.assertEqual(result["data"][0]["close"], 1510.0)
        self.assertEqual(result["data"][0]["pctChg"], 1.2)

    def test_a_share_kline_prefers_akshare_before_baostock(self):
        ak_payload = {
            "code": "sh.600519",
            "symbol": "600519",
            "period": "daily",
            "count": 1,
            "provider": "akshare",
            "data": [{"date": "2026-05-29", "close": 1510.0}],
        }

        with patch.object(stock.ak_svc, "get_a_share_kline", return_value=ak_payload, create=True) as ak_call:
            with patch.object(stock.bao, "get_kline", side_effect=AssertionError("BaoStock should not be primary")):
                result = stock.get_kline(code="sh.600519", period="daily", days=5)

        ak_call.assert_called_once_with("sh.600519", "daily", 5)
        self.assertEqual(result["market"], "A_SHARE")
        self.assertEqual(result["resolvedCode"], "sh.600519")
        self.assertEqual(result["provider"], "akshare")
        self.assertNotIn("fallbackProvider", result)

    def test_a_share_kline_falls_back_to_baostock_when_akshare_fails(self):
        fallback_payload = {
            "code": "sh.600519",
            "period": "daily",
            "count": 1,
            "data": [{"date": "2026-05-29", "close": "1510.0"}],
        }

        with patch.object(stock.ak_svc, "get_a_share_kline", side_effect=RuntimeError("akshare timeout"), create=True):
            with patch.object(stock.bao, "get_kline", return_value=fallback_payload) as fallback_call:
                result = stock.get_kline(code="sh.600519", period="daily", days=5)

        fallback_call.assert_called_once_with("sh.600519", "daily", 5)
        self.assertEqual(result["provider"], "baostock")
        self.assertEqual(result["primaryProvider"], "akshare")
        self.assertEqual(result["fallbackProvider"], "baostock")
        self.assertIn("akshare timeout", result["primaryProviderError"])

    def test_a_share_technical_prefers_akshare_before_baostock(self):
        ak_payload = {
            "code": "sh.600519",
            "symbol": "600519",
            "provider": "akshare",
            "indicators": {"MA5": 1508.0, "RSI14": 55.5},
        }

        with patch.object(stock.ak_svc, "get_a_share_technical_indicators", return_value=ak_payload, create=True) as ak_call:
            with patch.object(stock.bao, "get_technical_indicators", side_effect=AssertionError("BaoStock should not be primary")):
                result = stock.get_technical(code="sh.600519", indicators="MA,RSI")

        ak_call.assert_called_once_with("sh.600519", ["MA", "RSI"])
        self.assertEqual(result["provider"], "akshare")
        self.assertEqual(result["market"], "A_SHARE")
        self.assertEqual(result["indicators"]["MA5"], 1508.0)


if __name__ == "__main__":
    unittest.main()
