import time
import threading
import unittest
from unittest.mock import MagicMock, patch

import httpx
from fastapi import FastAPI

from app import research_budget as budget
from app.routers.stock import _a_share_with_baostock_fallback
from app.services import akshare_service, baostock_service, search_service
from app.services.edgar_service import EdgarService


class ResearchBudgetTest(unittest.IsolatedAsyncioTestCase):
    async def test_asgi_sync_context_validation_expiry_and_cleanup(self):
        app = FastAPI()
        app.add_middleware(budget.ResearchBudgetMiddleware)

        @app.get("/probe")
        def probe(expire: bool = False):
            if expire:
                # Deterministic expiration in the sync worker, without sleeping.
                budget._deadline.set(time.monotonic() - 1)
                budget.check_budget()
            return {"remaining": budget.bounded_timeout(30)}

        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
            response = await client.get("/probe", headers={"X-StockSage-Remaining-Ms": "5000"})
            self.assertTrue(0 < response.json()["remaining"] <= 5)
            self.assertIsNone(budget._deadline.get())
            response = await client.get("/probe?expire=true", headers={"X-StockSage-Remaining-Ms": "5000"})
            self.assertEqual(504, response.status_code)
            self.assertEqual("RESEARCH_BUDGET_EXCEEDED", response.json()["code"])
            self.assertIsNone(budget._deadline.get())
            self.assertEqual(30, (await client.get("/probe")).json()["remaining"])
            for value in ["0", "-1", "1.2", "1800001", " 10", "99999999999999999"]:
                self.assertEqual(400, (await client.get("/probe", headers={"X-StockSage-Remaining-Ms": value})).status_code)

    async def test_actual_stock_router_does_not_convert_budget_or_fallback(self):
        from main import app
        def expired(*args, **kwargs):
            budget._deadline.set(time.monotonic() - 1)
            raise TimeoutError("upstream timed out")
        with patch("app.routers.stock.ak_svc.get_a_share_kline", side_effect=expired), patch("app.routers.stock.bao.get_kline") as fallback:
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
                response = await client.get("/api/stock/kline?code=sh.600519", headers={"X-StockSage-Remaining-Ms": "5000"})
            self.assertEqual(504, response.status_code)
            fallback.assert_not_called()

    def test_search_timeout_shrinks_and_expired_primary_never_falls_back(self):
        token = budget._deadline.set(time.monotonic() + 2)
        self.addCleanup(budget._deadline.reset, token)
        service = search_service.SearchService()
        with patch.object(search_service.httpx, "Client") as client:
            client.return_value.__enter__.return_value.post.return_value.json.return_value = {"results": []}
            service._search_tavily("q", 1, None, "general")
            self.assertTrue(0 < client.call_args.kwargs["timeout"] <= 2)
        def expire(*args):
            budget._deadline.set(time.monotonic() - 1)
            raise httpx.ReadTimeout("late")
        with patch.object(search_service, "TAVILY_API_KEY", "test"), patch.object(service, "_search_tavily", side_effect=expire), patch.object(service, "_search_ddg") as fallback:
            with self.assertRaises(budget.ResearchBudgetExceeded):
                service.search("q")
            fallback.assert_not_called()

    def test_local_timeout_still_allows_fallback(self):
        token = budget._deadline.set(time.monotonic() + 30)
        self.addCleanup(budget._deadline.reset, token)
        fallback = MagicMock(return_value={"data": [1]})
        result = _a_share_with_baostock_fallback("kline", MagicMock(side_effect=TimeoutError()), fallback)
        self.assertEqual([1], result["data"])
        fallback.assert_called_once()

    def test_sec_timeout_and_throttle_do_not_start_late_http(self):
        token = budget._deadline.set(time.monotonic() + 2)
        self.addCleanup(budget._deadline.reset, token)
        service = EdgarService()
        self.addCleanup(service._client.close)
        with patch.object(service._client, "get") as get:
            service._get("https://data.sec.gov/a")
            self.assertTrue(0 < get.call_args.kwargs["timeout"] <= 2)
            get.reset_mock()
            service._last_request_time = time.time()
            with patch("app.services.edgar_service.time.sleep", side_effect=lambda _: budget._deadline.set(time.monotonic() - 1)):
                with self.assertRaises(budget.ResearchBudgetExceeded):
                    service._get("https://data.sec.gov/a")
            get.assert_not_called()

    def test_catalog_worker_inherits_deadline_and_baostock_wait_is_bounded(self):
        token = budget._deadline.set(time.monotonic() + 2)
        self.addCleanup(budget._deadline.reset, token)
        observed = akshare_service._call_with_timeout(lambda: budget.bounded_timeout(30), 12, "test")
        self.assertTrue(0 < observed <= 2)
        def expire(timeout):
            self.assertTrue(0 < timeout <= 2)
            budget._deadline.set(time.monotonic() - 1)
            return False
        with patch.object(baostock_service, "start_baostock_login_manager"), patch.object(baostock_service, "_BAOSTOCK_LOGGED_IN", False), patch.object(baostock_service, "_BAOSTOCK_LOGIN_DONE") as done:
            done.wait.side_effect = expire
            with self.assertRaises(budget.ResearchBudgetExceeded):
                baostock_service._ensure_baostock_login()

    def test_baostock_lock_wait_expires_without_query_or_releasing_another_owner(self):
        locked = threading.Event()
        release = threading.Event()
        def holder():
            with baostock_service.BAOSTOCK_LOCK:
                locked.set()
                release.wait(5)
        worker = threading.Thread(target=holder)
        worker.start()
        self.assertTrue(locked.wait(1))
        token = budget._deadline.set(time.monotonic() + 0.02)
        try:
            with patch.object(baostock_service, "_BAOSTOCK_MANAGER_STARTED", True), patch.object(baostock_service.bs, "query_profit_data") as query:
                with self.assertRaises(budget.ResearchBudgetExceeded):
                    baostock_service.BaostockService().get_financial_metrics("sh.600519")
                query.assert_not_called()
                self.assertFalse(baostock_service.BAOSTOCK_LOCK.acquire(blocking=False))
        finally:
            budget._deadline.reset(token)
            release.set()
            worker.join(1)

    def test_financial_iteration_stops_before_next_sdk_query(self):
        token = budget._deadline.set(time.monotonic() + 2)
        self.addCleanup(budget._deadline.reset, token)
        result = MagicMock(error_code="0", fields=[])
        def expired_row():
            budget._deadline.set(time.monotonic() - 1)
            return False
        result.next.side_effect = expired_row
        with patch.object(baostock_service, "_ensure_baostock_login", return_value=None), patch.object(baostock_service.bs, "query_profit_data", return_value=result), patch.object(baostock_service.bs, "query_operation_data") as next_query:
            with self.assertRaises(budget.ResearchBudgetExceeded):
                baostock_service.BaostockService().get_financial_reports("sh.600519")
            next_query.assert_not_called()


if __name__ == "__main__":
    unittest.main()
