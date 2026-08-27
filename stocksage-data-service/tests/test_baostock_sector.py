import sys
import types
import unittest
from unittest.mock import patch

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

from app.services.baostock_service import BaostockService


class FakeSectorService(BaostockService):
    def get_kline(self, code: str, period: str, days: int) -> dict:
        closes = {
            "sh.600519": (100, 110, 1000, 10000),
            "sz.000858": (50, 48, 900, 9000),
            "sz.000568": (30, 33, 700, 7000),
        }
        first, last, volume, amount = closes[code]
        return {
            "code": code,
            "period": period,
            "data": [
                {"date": "2026-05-29", "close": first, "volume": volume, "amount": amount},
                {"date": "2026-05-30", "close": last, "volume": volume * 2, "amount": amount * 2},
            ],
        }


class BaostockSectorTest(unittest.TestCase):
    def test_sector_performance_aggregates_sample_stocks(self):
        service = FakeSectorService()

        with patch("app.services.baostock_service._ensure_baostock_login", return_value=None):
            result = service.get_sector_performance("baijiu")

        self.assertEqual(result["sector"], "baijiu")
        self.assertEqual(result["source"], "baostock")
        self.assertEqual(result["summary"]["sampleCount"], 3)
        self.assertAlmostEqual(result["summary"]["averagePctChg"], 5.3333, places=4)
        self.assertEqual(result["leaders"][0]["code"], "sh.600519")
        self.assertEqual(result["laggards"][0]["code"], "sz.000858")
        self.assertEqual(len(result["data"]), 3)

    def test_sector_performance_returns_compatible_empty_payload_for_unknown_sector(self):
        service = FakeSectorService()

        with patch("app.services.baostock_service._ensure_baostock_login", return_value=None):
            result = service.get_sector_performance("unknown-sector")

        self.assertEqual(result["sector"], "unknown-sector")
        self.assertEqual(result["data"], [])
        self.assertEqual(result["summary"]["sampleCount"], 0)
        self.assertIn("No supported A-share sector mapping", result["message"])


if __name__ == "__main__":
    unittest.main()
