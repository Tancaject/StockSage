import unittest
from unittest.mock import patch

from app.routers import stock
from app.services.market_resolver import resolve_stock_route


class FakeSearchProvider:
    def __init__(
        self,
        *,
        specific=True,
        eastmoney=None,
        a_catalog=None,
        hk_catalog=None,
    ):
        self.specific = specific
        self.eastmoney = eastmoney or {"results": []}
        self.a_catalog = a_catalog or {"results": []}
        self.hk_catalog = hk_catalog or {"results": []}
        self.calls = []

    def has_specific_stock_terms(self, query):
        self.calls.append(("specific", query, None))
        return self.specific

    def search_symbols(self, query, max_results=10):
        self.calls.append(("eastmoney", query, max_results))
        return self.eastmoney

    def search_a_symbols(self, query, max_results=10):
        self.calls.append(("a_catalog", query, max_results))
        return self.a_catalog

    def search_hk_symbols(self, query, max_results=10):
        self.calls.append(("hk_catalog", query, max_results))
        return self.hk_catalog


def provider_item(symbol, name, **extra):
    return {
        "symbol": symbol,
        "shortName": name,
        "longName": extra.pop("longName", name),
        "exchange": extra.pop("exchange", ""),
        "quoteType": extra.pop("quoteType", "EQUITY"),
        "score": extra.pop("score", 100),
        "provider": extra.pop("provider", "test-provider"),
        **extra,
    }


class StockSearchTest(unittest.TestCase):
    def run_search(self, query, provider, local_routes, max_results=10):
        with (
            patch.object(stock, "ak_svc", provider),
            patch.object(
                stock,
                "search_stock_routes",
                return_value=local_routes,
            ) as local_search,
        ):
            result = stock.search_stock(q=query, max_results=max_results)
        local_search.assert_called_once_with(query, max_results=max_results)
        return result

    def test_local_only_candidate_keeps_order_and_provider_sequence(self):
        provider = FakeSearchProvider()
        route = resolve_stock_route("AAPL")

        result = self.run_search("AAPL", provider, [route], max_results=5)

        self.assertEqual(
            [
                ("eastmoney", "AAPL", 5),
                ("hk_catalog", "AAPL", 5),
                ("a_catalog", "AAPL", 5),
            ],
            provider.calls,
        )
        self.assertEqual(1, result["count"])
        self.assertEqual("AAPL", result["candidates"][0]["resolvedCode"])
        self.assertEqual("local_resolver", result["candidates"][0]["origin"])

    def test_no_specific_terms_returns_before_provider_search(self):
        provider = FakeSearchProvider(specific=False)

        result = self.run_search("分析一下", provider, [])

        self.assertEqual([("specific", "分析一下", None)], provider.calls)
        self.assertEqual(
            {
                "query": "分析一下",
                "count": 0,
                "candidates": [],
                "message": "No specific stock name or code detected in query",
            },
            result,
        )

    def test_eastmoney_hit_merges_duplicate_and_short_circuits_catalogs(self):
        provider = FakeSearchProvider(
            eastmoney={
                "results": [
                    provider_item(
                        "AAPL",
                        "Apple",
                        longName="Apple Inc.",
                        exchange="NASDAQ",
                        provider="eastmoney_suggest",
                        matchedTerm="apple",
                    ),
                    provider_item(
                        "0700.HK",
                        "腾讯控股",
                        exchange="HKEX",
                        provider="eastmoney_suggest",
                        matchedTerm="腾讯",
                    ),
                ]
            }
        )

        result = self.run_search(
            "apple 腾讯",
            provider,
            [resolve_stock_route("AAPL")],
            max_results=5,
        )

        self.assertEqual([("eastmoney", "apple 腾讯", 5)], provider.calls)
        self.assertEqual(["AAPL", "0700.HK"], [
            candidate["resolvedCode"] for candidate in result["candidates"]
        ])
        apple = result["candidates"][0]
        self.assertEqual(["eastmoney_suggest"], apple["confirmedBy"])
        self.assertEqual("Apple", apple["name"])
        self.assertEqual("Apple Inc.", apple["longName"])
        self.assertEqual("NASDAQ", apple["exchange"])
        self.assertEqual("eastmoney_suggest", apple["provider"])
        self.assertEqual("apple", apple["matchedTerm"])
        self.assertNotIn("score", apple)

    def test_a_and_hk_hints_preserve_catalog_priority(self):
        a_provider = FakeSearchProvider(
            a_catalog={
                "results": [
                    provider_item(
                        "sh.600519",
                        "贵州茅台",
                        longName="贵州茅台酒股份有限公司",
                        companyName="贵州茅台酒股份有限公司",
                        exchange="SH",
                        provider="akshare_a_share_code_table",
                    )
                ]
            },
            hk_catalog={
                "results": [
                    provider_item(
                        "0700.HK",
                        "腾讯控股",
                        englishName="Tencent Holdings",
                        exchange="HKEX",
                        provider="akshare_stock_hk_spot",
                    )
                ]
            },
        )
        a_result = self.run_search(
            "A股 贵州茅台",
            a_provider,
            [resolve_stock_route("sh.600519")],
            max_results=5,
        )

        self.assertEqual(
            [
                ("eastmoney", "A股 贵州茅台", 5),
                ("a_catalog", "A股 贵州茅台", 5),
                ("hk_catalog", "A股 贵州茅台", 5),
            ],
            a_provider.calls,
        )
        self.assertEqual(
            ["sh.600519", "0700.HK"],
            [candidate["resolvedCode"] for candidate in a_result["candidates"]],
        )
        self.assertEqual(
            ["akshare_a_catalog"],
            a_result["candidates"][0]["confirmedBy"],
        )
        self.assertNotIn("companyName", a_result["candidates"][0])
        self.assertEqual(
            "Tencent Holdings",
            a_result["candidates"][1]["englishName"],
        )

        hk_provider = FakeSearchProvider(
            hk_catalog={
                "results": [
                    provider_item(
                        "0700.HK",
                        "腾讯控股",
                        exchange="HKEX",
                    )
                ]
            }
        )
        hk_result = self.run_search("港股 腾讯", hk_provider, [], max_results=5)

        self.assertEqual(
            [
                ("specific", "港股 腾讯", None),
                ("eastmoney", "港股 腾讯", 5),
                ("hk_catalog", "港股 腾讯", 5),
            ],
            hk_provider.calls,
        )
        self.assertEqual("0700.HK", hk_result["candidates"][0]["resolvedCode"])

    def test_unsupported_hk_candidate_keeps_shape_and_original_limit_semantics(self):
        provider = FakeSearchProvider(
            hk_catalog={
                "results": [
                    provider_item(
                        "1234567.HK",
                        "Unsupported HK",
                        exchange="HKEX",
                    ),
                    provider_item(
                        "0700.HK",
                        "腾讯控股",
                        exchange="HKEX",
                    ),
                ]
            }
        )

        result = self.run_search("港股代码", provider, [], max_results=1)

        self.assertEqual(
            [
                ("specific", "港股代码", None),
                ("eastmoney", "港股代码", 1),
                ("hk_catalog", "港股代码", 1),
            ],
            provider.calls,
        )
        self.assertEqual(2, result["count"])
        self.assertEqual(1, len(result["candidates"]))
        self.assertEqual(
            {
                "input": "1234567.HK",
                "market": "UNSUPPORTED",
                "resolvedCode": "1234567.HK",
                "source": "akshare_hk_catalog",
                "routeReason": "search result is outside supported A/HK/US routing",
                "origin": "akshare_hk_catalog",
                "symbol": "1234567.HK",
                "name": "Unsupported HK",
                "shortName": "Unsupported HK",
                "longName": "Unsupported HK",
                "exchange": "HKEX",
                "quoteType": "EQUITY",
            },
            result["candidates"][0],
        )

    def test_provider_errors_keep_all_public_field_names(self):
        provider = FakeSearchProvider(
            eastmoney={"results": [], "error": "eastmoney unavailable"},
            a_catalog={"results": [], "error": "A catalog unavailable"},
            hk_catalog={"results": [], "error": "HK catalog unavailable"},
        )

        result = self.run_search("A股 某公司", provider, [], max_results=5)

        self.assertEqual(
            [
                ("specific", "A股 某公司", None),
                ("eastmoney", "A股 某公司", 5),
                ("a_catalog", "A股 某公司", 5),
                ("hk_catalog", "A股 某公司", 5),
            ],
            provider.calls,
        )
        self.assertEqual("eastmoney unavailable", result["eastmoneySearchError"])
        self.assertEqual("A catalog unavailable", result["akshareASearchError"])
        self.assertEqual("HK catalog unavailable", result["akshareSearchError"])
        self.assertEqual([], result["candidates"])


if __name__ == "__main__":
    unittest.main()
