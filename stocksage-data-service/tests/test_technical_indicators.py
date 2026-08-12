import sys
import types
import unittest
from unittest.mock import patch

import pandas as pd


fake_baostock = types.ModuleType("baostock")
fake_common = types.ModuleType("baostock.common")
fake_constants = types.ModuleType("baostock.common.contants")
fake_context = types.ModuleType("baostock.common.context")
fake_util = types.ModuleType("baostock.util")
fake_socketutil = types.ModuleType("baostock.util.socketutil")

fake_baostock.login = lambda: types.SimpleNamespace(error_code="0", error_msg="success")
fake_baostock.logout = lambda: None
fake_baostock.query_history_k_data_plus = lambda *args, **kwargs: None
fake_baostock.query_profit_data = lambda *args, **kwargs: None
fake_baostock.query_operation_data = lambda *args, **kwargs: None
fake_baostock.query_growth_data = lambda *args, **kwargs: None
fake_baostock.query_balance_data = lambda *args, **kwargs: None
fake_baostock.query_cash_flow_data = lambda *args, **kwargs: None
fake_baostock.query_dupont_data = lambda *args, **kwargs: None

fake_constants.BAOSTOCK_SERVER_IP = "127.0.0.1"
fake_constants.BAOSTOCK_SERVER_PORT = 0
fake_constants.MESSAGE_HEADER_LENGTH = 0
fake_constants.MESSAGE_SPLIT = "|"
fake_constants.COMPRESSED_MESSAGE_TYPE_TUPLE = ()
fake_context.default_socket = None
fake_socketutil.SocketUtil = types.SimpleNamespace()
fake_socketutil.send_msg = lambda msg: None

fake_baostock.common = fake_common
fake_common.contants = fake_constants
fake_common.context = fake_context
fake_baostock.util = fake_util
fake_util.socketutil = fake_socketutil

sys.modules.setdefault("baostock", fake_baostock)
sys.modules.setdefault("baostock.common", fake_common)
sys.modules.setdefault("baostock.common.contants", fake_constants)
sys.modules.setdefault("baostock.common.context", fake_context)
sys.modules.setdefault("baostock.util", fake_util)
sys.modules.setdefault("baostock.util.socketutil", fake_socketutil)

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

    def test_rounder_preserves_provider_specific_nan_behavior(self):
        close = pd.Series([100.0])
        baostock_result = calculate_technical_indicators(close, ["MA"])
        akshare_result = calculate_technical_indicators(
            close,
            ["MA"],
            lambda value, digits: (
                None if pd.isna(value) else round(float(value), digits)
            ),
        )

        self.assertTrue(all(pd.isna(value) for value in baostock_result.values()))
        self.assertEqual(
            {"MA5": None, "MA10": None, "MA20": None, "MA60": None},
            akshare_result,
        )


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

    def test_provider_wrappers_keep_distinct_nan_serialization(self):
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
        self.assertTrue(pd.isna(baostock_result["indicators"]["MA60"]))


if __name__ == "__main__":
    unittest.main()
