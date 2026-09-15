import sys
import json
import types
import unittest
from unittest.mock import patch

import pandas as pd


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

from app.services.akshare_service import AkshareService
from app.services.baostock_service import BaostockService
from app.services.technical_indicators import calculate_technical_indicators


class TechnicalIndicatorCalculationTest(unittest.TestCase):
    def test_calculates_requested_indicators_with_exact_values(self):
        rising = pd.Series(range(1, 81), dtype=float)
        result = calculate_technical_indicators(rising, ["MA", "RSI"])

        self.assertEqual(
            {
                "MA5": 78.0,
                "MA10": 75.5,
                "MA20": 70.5,
                "MA60": 50.5,
                "RSI14": 100.0,
            },
            result,
        )

        constant = pd.Series([100.0] * 80)
        self.assertEqual(
            {"MACD_DIF": 0.0, "MACD_DEA": 0.0, "MACD": 0.0},
            calculate_technical_indicators(constant, ["MACD"]),
        )

    def test_missing_and_nonfinite_indicators_are_json_null_without_losing_zero(self):
        for close, indicators in (([100.0], ["MA"]), ([100.0] * 80, ["RSI"]),
                                  ([float("inf")] * 80, ["MA"])):
            result = calculate_technical_indicators(pd.Series(close), indicators)
            self.assertTrue(all(value is None for value in result.values()))
            json.dumps(result, allow_nan=False)
        self.assertEqual(0.0, calculate_technical_indicators(pd.Series([100.0] * 80), ["MACD"])["MACD"])


class TechnicalIndicatorProviderTest(unittest.TestCase):
    def setUp(self):
        self.akshare = AkshareService()
        self.baostock = BaostockService()

    def test_provider_wrappers_keep_lookback_and_response_envelopes(self):
        hk_bars = [{"Close": 100.0}] * 160
        a_share_bars = [{"close": 100.0}] * 160
        baostock_bars = [{"close": "100.0"}] * 120

        with patch.object(
            self.akshare,
            "get_hk_kline",
            return_value={"data": hk_bars},
        ) as hk_kline:
            hk_result = self.akshare.get_hk_technical_indicators(
                "0700.HK",
                ["MA", "MACD"],
            )
        hk_kline.assert_called_once_with("0700.HK", "daily", 160)
        self.assertEqual("00700", hk_result["symbol"])
        self.assertEqual("akshare", hk_result["provider"])
        self.assertEqual(100.0, hk_result["indicators"]["MA60"])
        self.assertEqual(0.0, hk_result["indicators"]["MACD"])

        with patch.object(
            self.akshare,
            "get_a_share_kline",
            return_value={"data": a_share_bars},
        ) as a_share_kline:
            a_share_result = self.akshare.get_a_share_technical_indicators(
                "sh.600519",
                ["MA"],
            )
        a_share_kline.assert_called_once_with("sh.600519", "daily", 160)
        self.assertEqual("sh.600519", a_share_result["code"])
        self.assertEqual("600519", a_share_result["symbol"])
        self.assertEqual("akshare", a_share_result["provider"])
        self.assertEqual(100.0, a_share_result["indicators"]["MA60"])

        with patch.object(
            self.baostock,
            "get_kline",
            return_value={"data": baostock_bars},
        ) as baostock_kline:
            baostock_result = self.baostock.get_technical_indicators(
                "sh.600519",
                ["MA"],
            )
        baostock_kline.assert_called_once_with("sh.600519", "daily", 120)
        self.assertEqual("sh.600519", baostock_result["code"])
        self.assertNotIn("provider", baostock_result)
        self.assertEqual(100.0, baostock_result["indicators"]["MA60"])

    def test_provider_wrappers_keep_empty_data_payloads(self):
        with patch.object(
            self.akshare,
            "get_hk_kline",
            return_value={"data": []},
        ):
            self.assertEqual(
                {
                    "symbol": "00700",
                    "indicators": {},
                    "message": "No HK kline data",
                },
                self.akshare.get_hk_technical_indicators("0700.HK", ["MA"]),
            )

        with patch.object(
            self.akshare,
            "get_a_share_kline",
            return_value={"data": []},
        ):
            self.assertEqual(
                {
                    "code": "sh.600519",
                    "symbol": "600519",
                    "indicators": {},
                    "provider": "akshare",
                    "message": "No A-share kline data",
                },
                self.akshare.get_a_share_technical_indicators(
                    "sh.600519",
                    ["MA"],
                ),
            )

        with patch.object(
            self.baostock,
            "get_kline",
            return_value={"data": []},
        ):
            self.assertEqual(
                {
                    "code": "sh.600519",
                    "indicators": {},
                    "message": "No kline data",
                },
                self.baostock.get_technical_indicators("sh.600519", ["MA"]),
            )

    def test_provider_wrappers_share_json_null_serialization(self):
        with patch.object(
            self.akshare,
            "get_a_share_kline",
            return_value={"data": [{"close": 100.0}]},
        ):
            akshare_result = self.akshare.get_a_share_technical_indicators(
                "sh.600519",
                ["MA"],
            )

        with patch.object(
            self.baostock,
            "get_kline",
            return_value={"data": [{"close": "100.0"}]},
        ):
            baostock_result = self.baostock.get_technical_indicators(
                "sh.600519",
                ["MA"],
            )

        self.assertIsNone(akshare_result["indicators"]["MA60"])
        self.assertIsNone(baostock_result["indicators"]["MA60"])
        json.dumps([akshare_result, baostock_result], allow_nan=False)


if __name__ == "__main__":
    unittest.main()
