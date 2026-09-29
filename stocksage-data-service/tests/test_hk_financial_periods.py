import unittest
from datetime import date, datetime
from types import SimpleNamespace
from unittest.mock import Mock, patch

import pandas as pd

from app.services.akshare_service import AkshareService


class HKFinancialPeriodsTest(unittest.TestCase):
    def setUp(self):
        self.service = AkshareService()

    def fetch(self, frame, indicators=None, period="annual", years=2):
        provider = SimpleNamespace(
            stock_financial_hk_report_em=Mock(return_value=frame),
            stock_financial_hk_analysis_indicator_em=Mock(return_value=indicators),
        )
        with patch.object(self.service, "_ak", return_value=provider):
            result = self.service.get_hk_financial_reports("0700.HK", period, years)
        return result, provider

    def test_annual_period_selection_preserves_every_item_and_duplicate_period_row(self):
        frame = pd.DataFrame([
            {"REPORT_DATE": "2024-12-31 00:00:00", "STD_ITEM_CODE": "ASSET", "AMOUNT": "11.123456789"},
            {"REPORT_DATE": "2023-12-31 00:00:00", "STD_ITEM_CODE": "ASSET", "AMOUNT": "9"},
            {"REPORT_DATE": "2025-12-31 00:00:00", "STD_ITEM_CODE": "CASH", "AMOUNT": "12"},
            {"REPORT_DATE": "2024-12-31 00:00:00", "STD_ITEM_CODE": "CASH", "AMOUNT": None},
            {"REPORT_DATE": "2025-12-31 00:00:00", "STD_ITEM_CODE": "ASSET", "AMOUNT": "13"},
            {"REPORT_DATE": "2025-12-31 00:00:00", "STD_ITEM_CODE": "ASSET", "AMOUNT": "14"},
        ], index=[7, 2, 2, 8, 1, 0])
        frame["FLOAT_VALUE"] = 0.123456789012345
        original = frame.copy(deep=True)
        indicators = pd.DataFrame([
            {"REPORT_DATE": "2024-12-31", "STD_REPORT_DATE": "2026-09-01", "ROE": "1.1"},
            {"REPORT_DATE": "2025-12-31", "STD_REPORT_DATE": "2026-08-01", "ROE": "1.2"},
            {"REPORT_DATE": "2023-12-31", "STD_REPORT_DATE": "2026-10-01", "ROE": "1.0"},
            {"REPORT_DATE": "2025-12-31", "STD_REPORT_DATE": "2026-08-02", "ROE": "1.3"},
        ])
        result, provider = self.fetch(frame, indicators)
        expected = frame.iloc[[2, 4, 5, 0, 3]].to_dict(orient="records")
        expected[-1]["AMOUNT"] = None
        self.assertEqual([], list(result["errors"]))
        self.assertEqual("annual", result["period"])
        for rows in result["statements"].values():
            self.assertEqual(expected, rows)
            self.assertEqual(0.123456789012345, rows[0]["FLOAT_VALUE"])
            self.assertTrue(all(set(row) == set(frame.columns) for row in rows))
        self.assertEqual(["1.2", "1.3", "1.1"], [row["ROE"] for row in result["indicators"]])
        self.assertEqual(18, result["statementCount"])
        self.assertEqual(["年度"] * 3, [call.kwargs["indicator"] for call in provider.stock_financial_hk_report_em.call_args_list])
        provider.stock_financial_hk_analysis_indicator_em.assert_called_once_with(symbol="00700", indicator="年度")
        pd.testing.assert_frame_equal(original, frame)

    def test_report_period_budget_selects_distinct_dates_without_relabeling_quarters(self):
        dates = ["2023-12-31", "2025-06-30", "2024-12-31", "2025-12-31", "2024-06-30", "2023-06-30"]
        frame = pd.DataFrame([{"REPORT_DATE": when, "STD_ITEM_CODE": item, "AMOUNT": "0"}
                              for when in dates for item in ("A", "B")])
        result, provider = self.fetch(frame, frame, period="quarterly", years=1)
        self.assertEqual("report_period", result["period"])
        for rows in [*result["statements"].values(), result["indicators"]]:
            self.assertEqual(8, len(rows))
            self.assertEqual(["2025-12-31", "2025-06-30", "2024-12-31", "2024-06-30"],
                             list(dict.fromkeys(row["REPORT_DATE"] for row in rows)))
        self.assertEqual(["报告期"] * 3, [call.kwargs["indicator"] for call in provider.stock_financial_hk_report_em.call_args_list])

    def test_invalid_or_unknown_dates_fail_the_whole_table_without_hiding_other_tables(self):
        valid = pd.DataFrame([{"REPORT_DATE": "2025-12-31", "AMOUNT": "12"}])
        for bad_date in (None, pd.NaT, float("nan"), "", "not-a-date", "2025-02-30", True, 1767139200):
            with self.subTest(bad_date=bad_date):
                invalid = pd.DataFrame([{"REPORT_DATE": "2025-12-31", "AMOUNT": "12"},
                                        {"REPORT_DATE": bad_date, "AMOUNT": "10"}])
                provider = SimpleNamespace(
                    stock_financial_hk_report_em=Mock(side_effect=[invalid, valid, valid]),
                    stock_financial_hk_analysis_indicator_em=Mock(return_value=invalid),
                )
                with patch.object(self.service, "_ak", return_value=provider):
                    result = self.service.get_hk_financial_reports("0700.HK", "annual", 1)
                self.assertEqual([], result["statements"]["balanceSheet"])
                self.assertEqual([], result["indicators"])
                self.assertEqual({"balanceSheet", "indicators"}, set(result["errors"]))
                self.assertTrue(all("REPORT_DATE" in message for message in result["errors"].values()))
                self.assertEqual(valid.to_dict(orient="records"), result["statements"]["incomeStatement"])
                self.assertEqual(valid.to_dict(orient="records"), result["statements"]["cashFlow"])
        missing = pd.DataFrame([{"STD_REPORT_DATE": "2025-12-31", "AMOUNT": "10"}])
        result, _ = self.fetch(missing, missing)
        self.assertEqual({"balanceSheet", "incomeStatement", "cashFlow", "indicators"}, set(result["errors"]))
        self.assertEqual(0, result["statementCount"])

    def test_empty_tables_and_supported_date_objects_keep_existing_output_values(self):
        for frame in (None, pd.DataFrame()):
            result, _ = self.fetch(frame, frame)
            self.assertEqual({}, result["errors"])
            self.assertEqual(0, result["statementCount"])
        frame = pd.DataFrame([
            {"REPORT_DATE": date(2024, 12, 31), "AMOUNT": "3.123456789"},
            {"REPORT_DATE": datetime(2025, 12, 31), "AMOUNT": "4.123456789"},
            {"REPORT_DATE": pd.Timestamp("2025-12-31 00:00:00"), "AMOUNT": None},
        ])
        result, _ = self.fetch(frame, frame, years=1)
        self.assertEqual([{"REPORT_DATE": "2025-12-31", "AMOUNT": "4.123456789"},
                          {"REPORT_DATE": "2025-12-31", "AMOUNT": None}], result["statements"]["balanceSheet"])


if __name__ == "__main__":
    unittest.main()
