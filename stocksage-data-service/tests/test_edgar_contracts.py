import unittest
from unittest.mock import patch

import httpx

from app.services.edgar_service import EdgarService


class EdgarContractsTest(unittest.TestCase):
    def test_annual_facts_use_actual_periods_and_latest_filing_in_any_order(self):
        def fact(value, end, start=None, **extra):
            return {"val": value, "end": end, **({"start": start} if start else {}),
                    "fy": 2025, "fp": "FY", "form": "10-K", "filed": "2026-03-01",
                    "accn": "0000000001-26-000001", **extra}

        revenue = [
            fact(100, "2026-01-31", "2025-02-01"),
            fact(80, "2025-01-31", "2024-02-01"),
            fact(25, "2026-01-31", "2025-11-01"),
            fact(105, "2026-01-31", "2025-02-01", form="10-K/A",
                 filed="2026-04-01", accn="0000000001-26-000002"),
        ]
        assets = [fact(1000, "2026-01-31"), fact(900, "2025-01-31"), fact(950, "2025-10-31")]
        service = EdgarService()
        self.addCleanup(service._client.close)
        results = []
        for reverse in (False, True):
            payload = {"facts": {"us-gaap": {
                "Revenues": {"units": {"USD": revenue[::-1] if reverse else revenue}},
                "Assets": {"units": {"USD": assets[::-1] if reverse else assets}},
            }}}
            with patch.object(service, "_resolve_cik", return_value="0000000001"), \
                    patch.object(service, "_get", return_value=httpx.Response(200, json=payload)):
                results.append(service.get_xbrl("TEST"))
        self.assertEqual(results[0], results[1])
        metrics = results[0]["metrics"]
        self.assertEqual([80, 105], [row["value"] for row in metrics["Revenue"]["data"]])
        self.assertEqual([900, 1000], [row["value"] for row in metrics["TotalAssets"]["data"]])
        latest = metrics["Revenue"]["data"][-1]
        self.assertEqual(("2025-02-01", "2026-01-31"), (latest["start"], latest["end"]))
        self.assertIsNone(latest["fiscal_year"])
        self.assertEqual(2025, latest["filing_fiscal_year"])
        self.assertEqual("10-K/A", latest["form"])
        self.assertIn("companyfacts/CIK0000000001.json", latest["source_url"])
        self.assertEqual([], service._filter_annual(assets, instant=True))
        self.assertEqual([], service._filter_annual([revenue[0], {**revenue[0], "val": 999}]))

    def test_redirect_is_rejected_before_any_second_request(self):
        for destination in ("http://127.0.0.1:8001/health", "https://www.sec.gov/next"):
            visited = []

            def respond(request):
                visited.append(str(request.url))
                return httpx.Response(302, headers={"Location": destination})

            service = EdgarService()
            service._client.close()
            with httpx.Client(transport=httpx.MockTransport(respond), follow_redirects=True) as client:
                service._client = client
                with self.assertRaises(httpx.HTTPStatusError):
                    service._get("https://www.sec.gov/start")
            self.assertEqual(["https://www.sec.gov/start"], visited)


if __name__ == "__main__":
    unittest.main()
