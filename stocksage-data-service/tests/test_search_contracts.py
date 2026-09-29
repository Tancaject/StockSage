import copy
import json
import unittest
from datetime import datetime
from pathlib import Path
from unittest.mock import MagicMock, patch

import httpx
from duckduckgo_search.exceptions import RatelimitException, TimeoutException
from fastapi import FastAPI
from pydantic import ValidationError

from app.routers import search, stock
from app.search_results import SearchResponse
from app.services import search_service
from app.services.market_resolver import StockRoute, UNKNOWN


FIXTURE_DIR = Path(__file__).parent / "fixtures"
NEWS = json.loads((FIXTURE_DIR / "search-news-v1.json").read_text(encoding="utf-8"))
FALLBACK = json.loads((FIXTURE_DIR / "search-fallback-v1.json").read_text(encoding="utf-8"))


def tavily_rows():
    return [{"title": row["title"], "url": row["link"], "content": row["snippet"], "published_date": row["date"]}
            for row in NEWS["results"]]


def ddg_rows():
    return [{"title": row["title"], "url": row["link"], "body": row["snippet"], "date": row["date"], "source": row["source"]}
            for row in FALLBACK["results"]]


class SearchContractsTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        app = FastAPI()
        app.include_router(search.router, prefix="/api/search")
        app.include_router(stock.router, prefix="/api/stock")
        self.client = httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test")
        self.addAsyncCleanup(self.client.aclose)
        self.http = MagicMock()
        self.http.__enter__.return_value = self.http
        self.ddg = MagicMock()
        self.ddg.__enter__.return_value = self.ddg
        self.reply({"results": tavily_rows()})
        self.ddg.news.return_value = ddg_rows()
        self.ddg.text.return_value = [{"title": "Web example", "href": "https://example.org/web", "body": "Web search snippet"}]
        for patcher in (patch.object(search_service.httpx, "Client", return_value=self.http),
                        patch.object(search_service, "DDGS", return_value=self.ddg),
                        patch.object(search_service, "TAVILY_API_KEY", "unit-test-only"),
                        patch.object(search_service, "TAVILY_WEB_TOPIC", "finance")):
            patcher.start()
            self.addCleanup(patcher.stop)

    def reply(self, payload, status=200):
        self.http.post.return_value = httpx.Response(status, json=payload,
                                                    request=httpx.Request("POST", "https://api.tavily.com/search"))

    async def request(self, endpoint="news", **params):
        response = await self.client.get("/api/search/" + endpoint, params={"q": "TEST", "max_results": 5, **params})
        self.assertEqual(200, response.status_code, response.text)
        return response.json()

    async def test_tavily_fixture_exact_asgi_and_model_roundtrip(self):
        raw = {"results": tavily_rows()}
        before = copy.deepcopy(raw)
        self.reply(raw)
        with patch.object(search_service, "datetime") as clock:
            clock.now.return_value = datetime.fromisoformat(NEWS["fetchedAt"])
            result = await self.request(timelimit="w")
        self.assertEqual(NEWS, result)
        self.assertEqual(NEWS, SearchResponse.model_validate(result).model_dump(mode="json"))
        self.assertEqual(before, raw)
        self.assertEqual({"query": "TEST", "max_results": 5, "topic": "news", "search_depth": "basic", "time_range": "week"},
                         self.http.post.call_args.kwargs["json"])
        self.ddg.news.assert_not_called()
        self.assertNotIn("asOf", result)

    async def test_ddg_fixture_exact_after_timeout_reports_lost_depth_and_year_filter(self):
        self.http.post.side_effect = httpx.ReadTimeout("private upstream details: unit-test-only")
        with patch.object(search_service, "datetime") as clock, self.assertLogs(search_service.logger, level="WARNING") as logs:
            clock.now.return_value = datetime.fromisoformat(FALLBACK["fetchedAt"])
            result = await self.request(timelimit="y", depth="advanced")
        self.assertEqual(FALLBACK, result)
        self.assertEqual(FALLBACK, SearchResponse.model_validate(result).model_dump(mode="json"))
        self.assertNotIn("unit-test-only", json.dumps(result) + str(logs.output))
        self.ddg.news.assert_called_once_with("TEST", timelimit=None, max_results=5)
        self.assertNotIn("time_range", self.http.post.call_args.kwargs["json"])
        self.assertNotIn("days", self.http.post.call_args.kwargs["json"])

    async def test_missing_primary_web_scope_and_undetectable_dates_are_explicit(self):
        with patch.object(search_service, "TAVILY_API_KEY", ""):
            result = await self.request(endpoint="web", timelimit=" Y ", depth="advanced")
        self.http.post.assert_not_called()
        self.assertEqual(("ddg", "y", "y", "advanced", None, "general", "PRIMARY_NOT_CONFIGURED"),
                         tuple(result[key] for key in ("provider", "requestedTimelimit", "effectiveTimelimit", "requestedDepth", "effectiveDepth", "topic", "fallbackReason")))
        self.assertEqual("UNKNOWN", result["results"][0]["publishedTimeKind"])
        self.ddg.text.assert_called_once_with("TEST", timelimit="y", max_results=5)
        for date in ("2026-09-25T10:00:00", "2026-02-30", "yesterday", "", None):
            rows = tavily_rows()[:1]
            rows[0]["published_date"] = date
            self.reply({"results": rows})
            result = await self.request()
            self.assertEqual(("UNKNOWN", None, None), tuple(result["results"][0][key] for key in ("publishedTimeKind", "publishedAt", "publishedDate")))
        result = await self.request(endpoint="web", timelimit="invalid", depth="advanced")
        self.assertEqual((None, None, "finance", "advanced"),
                         tuple(result[key] for key in ("requestedTimelimit", "effectiveTimelimit", "topic", "effectiveDepth")))

    async def test_empty_is_not_failure_and_malformed_results_trigger_whole_provider_fallback(self):
        self.reply({"results": []})
        empty = await self.request()
        self.assertEqual(("EMPTY", False, 0, None), tuple(empty[key] for key in ("status", "error", "count", "fallbackFrom")))
        self.ddg.news.assert_not_called()
        malformed = [{}, {"results": {}}, {"results": ["invalid"]}]
        for change in ({"url": "javascript:alert(1)"}, {"url": "https://"}, {"url": "https://exa mple.org"},
                       {"title": " ", "content": "\t"}, {"title": True}):
            rows = tavily_rows()
            rows[1].update(change)
            malformed.append({"results": rows})
        for payload in malformed:
            with self.subTest(payload=payload):
                self.reply(payload)
                result = await self.request()
                self.assertEqual(("SUCCESS", "ddg", "INVALID_PROVIDER_DATA", 1),
                                 tuple(result[key] for key in ("status", "provider", "fallbackReason", "count")))
        self.ddg.news.return_value = [{"title": "Bad DDG result", "url": "not-a-url", "body": "unusable"}]
        failed = await self.request()
        self.assertEqual(("ERROR", True, "INVALID_PROVIDER_DATA", False, []),
                         tuple(failed[key] for key in ("status", "error", "errorCode", "retryable", "results")))

    async def test_known_failures_are_classified_without_exposing_provider_errors(self):
        for status, reason in ((401, "UPSTREAM_ERROR"), (429, "UPSTREAM_RATE_LIMIT"), (503, "UPSTREAM_ERROR")):
            self.reply({"error": "private unit-test-only response"}, status=status)
            result = await self.request()
            self.assertEqual(reason, result["fallbackReason"])
            self.assertNotIn("unit-test-only", json.dumps(result))
        for error, code, retryable in ((TimeoutException("private unit-test-only timeout"), "UPSTREAM_TIMEOUT", True),
                                        (RatelimitException("private unit-test-only limit"), "UPSTREAM_RATE_LIMIT", True),
                                        (RuntimeError("timeout is only message text unit-test-only"), "UPSTREAM_ERROR", None)):
            with patch.object(search_service, "TAVILY_API_KEY", ""):
                self.ddg.news.side_effect = error
                result = await self.request()
            self.assertEqual(("ERROR", code, retryable, "PRIMARY_NOT_CONFIGURED"),
                             tuple(result[key] for key in ("status", "errorCode", "retryable", "fallbackReason")))
            self.assertNotIn("unit-test-only", json.dumps(result))

    async def test_stock_route_exposes_requested_days_and_never_claims_exact_window(self):
        for days, tl in ((1, "d"), (3, "w"), (7, "w"), (8, "m"), (60, "m")):
            response = await self.client.get("/api/stock/news", params={"code": "AAPL", "days": days})
            self.assertEqual(200, response.status_code, response.text)
            result = response.json()
            self.assertEqual((days, tl, tl, 8, "news", "AAPL", "US", "AAPL", "ibkr", "tavily"),
                             tuple(result[key] for key in ("requestedDays", "requestedTimelimit", "effectiveTimelimit", "requestedMaxResults", "searchType", "input", "market", "resolvedCode", "source", "provider")))
        unresolved = StockRoute("?", UNKNOWN, "", "none", "unrecognized")
        with patch.object(stock, "_resolve_with_search", return_value=unresolved), patch.object(stock.search_svc, "search_news") as fetch:
            response = await self.client.get("/api/stock/news", params={"code": "?", "days": 7})
        fetch.assert_not_called()
        result = response.json()
        self.assertEqual(("ERROR", "UNSUPPORTED_MARKET", None, None),
                         tuple(result[key] for key in ("status", "errorCode", "provider", "effectiveTimelimit")))
        canonical = StockRoute("AAPL", "US", "AAPL", "ibkr", "resolved company name")
        with patch.object(stock, "_resolve_with_search", return_value=canonical):
            response = await self.client.get("/api/stock/news", params={"code": " Apple company ", "days": 7})
        self.assertEqual(("Apple company", "AAPL", "AAPL"),
                         tuple(response.json()[key] for key in ("query", "input", "resolvedCode")))
        for path, params in (("/api/search/news", {"q": "TEST", "max_results": 0}),
                             ("/api/search/news", {"q": "TEST", "max_results": 21}),
                             ("/api/search/web", {"q": "TEST", "depth": "invalid"}),
                             ("/api/search/news", {"q": ""}), ("/api/stock/news", {"code": "AAPL", "days": 0})):
            self.assertEqual(422, (await self.client.get(path, params=params)).status_code)

    def test_model_rejects_conflicting_aliases_scope_status_and_publication_fields(self):
        for change in ({"status": "EMPTY"}, {"error": True}, {"count": 2}, {"requestedMaxResults": 2},
                       {"timelimit": "d"}, {"depth": "advanced"}, {"fallbackReason": "UPSTREAM_ERROR"},
                       {"errorCode": "UPSTREAM_ERROR"}, {"retryable": True}, {"effectiveDepth": None},
                       {"provider": None}, {"fetchedAt": "2026-09-26T01:02:03"},
                       {"fetchedAt": "2026-09-26T09:02:03+08:00"}, {"fetchedAt": 1790384523},
                       {"schemaVersion": True}, {"schemaVersion": 1.0}):
            with self.subTest(change=change), self.assertRaises(ValidationError):
                SearchResponse.model_validate({**NEWS, **change})
        for change in ({"publishedTimeKind": "UNKNOWN"}, {"publishedDate": "2026-09-24"},
                       {"publishedAt": "2026-09-24T15:30:00"}, {"publishedAt": "2026-09-24T23:30:00+08:00"},
                       {"publishedAt": 1790263800}):
            fixture = copy.deepcopy(NEWS)
            fixture["results"][0].update(change)
            with self.subTest(change=change), self.assertRaises(ValidationError):
                SearchResponse.model_validate(fixture)


if __name__ == "__main__":
    unittest.main()
