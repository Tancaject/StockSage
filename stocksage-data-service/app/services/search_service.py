"""Tavily 优先、DuckDuckGo 降级；共享出口验收结果与实际检索参数。"""

from app.research_budget import check_budget, bounded_timeout

from datetime import datetime, timezone
import logging
from typing import Optional
from urllib.parse import urlparse

import httpx
from duckduckgo_search import DDGS
from duckduckgo_search.exceptions import RatelimitException, TimeoutException
from pydantic import ValidationError

from app.config import TAVILY_API_KEY, TAVILY_WEB_TOPIC
from app.search_results import SearchResponse, normalize_search_result

logger = logging.getLogger(__name__)

_TEXT_TIMELIMITS = {"d", "w", "m", "y"}
_NEWS_TIMELIMITS = {"d", "w", "m"}
_TAVILY_URL = "https://api.tavily.com/search"
_TAVILY_TIMEOUT = 8.0
_TAVILY_TIME_RANGE = {"d": "day", "w": "week", "m": "month", "y": "year"}
_TAVILY_DEPTHS = {"basic", "advanced"}


def _normalize_timelimit(value: Optional[str], allowed: set) -> Optional[str]:
    if not value:
        return None
    normalized = value.strip().lower()
    return normalized if normalized in allowed else None


def _domain_of(url: str) -> str:
    try:
        domain = urlparse(url).hostname or ""
        return domain[4:] if domain.startswith("www.") else domain
    except (TypeError, ValueError, AttributeError):
        return ""


class InvalidProviderData(ValueError):
    """供应商结果结构不满足搜索契约，不能视为合法空搜索。"""


def _failure_kind(error: Exception) -> tuple[str, bool | None]:
    if isinstance(error, (httpx.TimeoutException, TimeoutException, TimeoutError)):
        return "UPSTREAM_TIMEOUT", True
    if isinstance(error, RatelimitException):
        return "UPSTREAM_RATE_LIMIT", True
    if isinstance(error, httpx.HTTPStatusError):
        code = error.response.status_code
        return ("UPSTREAM_RATE_LIMIT", True) if code == 429 else ("UPSTREAM_ERROR", code >= 500)
    if isinstance(error, (InvalidProviderData, ValidationError, ValueError)):
        return "INVALID_PROVIDER_DATA", False
    return "UPSTREAM_ERROR", None


class SearchService:
    def search(self, query: str, max_results: int = 5,
               timelimit: Optional[str] = None, depth: str = "basic") -> dict:
        return self._search(query, max_results, timelimit, depth, "web")

    def search_news(self, query: str, max_results: int = 5,
                    timelimit: Optional[str] = None, depth: str = "basic") -> dict:
        return self._search(query, max_results, timelimit, depth, "news")

    def _search(self, query, max_results, timelimit, depth, search_type) -> dict:
        check_budget()
        requested_time = _normalize_timelimit(timelimit, _TEXT_TIMELIMITS)
        effective_time = _normalize_timelimit(requested_time, _NEWS_TIMELIMITS if search_type == "news" else _TEXT_TIMELIMITS)
        requested_depth = depth if depth in _TAVILY_DEPTHS else "basic"
        context = {
            "query": query, "searchType": search_type, "requestedMaxResults": max_results,
            "requestedTimelimit": requested_time, "effectiveTimelimit": effective_time,
            "timelimit": effective_time, "requestedDepth": requested_depth,
        }
        fallback_reason = "PRIMARY_NOT_CONFIGURED"
        if TAVILY_API_KEY:
            try:
                topic = "news" if search_type == "news" else TAVILY_WEB_TOPIC
                raw = self._search_tavily(query, max_results, effective_time, topic, requested_depth)
                return self._response(context, "tavily", topic, requested_depth, raw)
            except Exception as error:
                check_budget(error)
                fallback_reason, _ = _failure_kind(error)
                # 不把供应商异常正文或请求头写入日志/降级元数据。
                logger.warning("Tavily %s search failed; falling back to DuckDuckGo (%s)", search_type, fallback_reason)
        topic = "news" if search_type == "news" else "general"
        try:
            raw = self._search_news_ddg(query, max_results, effective_time) if search_type == "news" else self._search_ddg(query, max_results, effective_time)
            return self._response(context, "ddg", topic, None, raw, fallback_reason)
        except Exception as error:
            check_budget(error)
            error_code, retryable = _failure_kind(error)
            return self._response(context, "ddg", topic, None, [], fallback_reason,
                                  error_code=error_code, retryable=retryable)

    @staticmethod
    def _response(context, provider, topic, depth, raw, fallback_reason=None, *, error_code=None, retryable=None):
        check_budget()
        # 验收位于 Tavily 的 try 范围内，坏行触发既有降级，不静默丢弃或伪装 EMPTY。
        results = [normalize_search_result(row) for row in raw] if error_code is None else []
        return SearchResponse.model_validate({
            **context, "provider": provider, "topic": topic, "effectiveDepth": depth, "depth": depth,
            "status": "ERROR" if error_code else ("SUCCESS" if results else "EMPTY"),
            "count": len(results), "results": results, "fetchedAt": datetime.now(timezone.utc),
            "fallbackFrom": "tavily" if fallback_reason is not None else None, "fallbackReason": fallback_reason,
            "error": error_code is not None, "errorCode": error_code, "retryable": retryable,
            "message": "Search provider request failed; retry later or use another source." if error_code else None,
        }).model_dump(mode="json")

    def _search_tavily(self, query: str, max_results: int, tl: Optional[str], topic: str, depth: str = "basic") -> list[dict]:
        payload = {"query": query, "max_results": max_results, "topic": topic, "search_depth": depth}
        if tl is not None:
            # time_range 是实际发送的供应商过滤条件，不保证每条结果都有可检测的发布时间。
            payload["time_range"] = _TAVILY_TIME_RANGE[tl]
        with httpx.Client(timeout=bounded_timeout(_TAVILY_TIMEOUT)) as client:
            response = client.post(_TAVILY_URL, json=payload, headers={"Authorization": f"Bearer {TAVILY_API_KEY}"})
            response.raise_for_status()
            data = response.json()
        if not isinstance(data, dict) or not isinstance(data.get("results"), list):
            raise InvalidProviderData("Tavily results must be an array")
        return self._adapt_results(data["results"], "tavily", topic == "news")

    def _search_ddg(self, query: str, max_results: int, tl: Optional[str]) -> list[dict]:
        with DDGS(timeout=bounded_timeout(10)) as ddgs:
            rows = ddgs.text(query, timelimit=tl, max_results=max_results)
        return self._adapt_results(rows, "ddg", False)

    def _search_news_ddg(self, query: str, max_results: int, tl: Optional[str]) -> list[dict]:
        with DDGS(timeout=bounded_timeout(10)) as ddgs:
            rows = ddgs.news(query, timelimit=tl, max_results=max_results)
        return self._adapt_results(rows, "ddg", True)

    @staticmethod
    def _adapt_results(rows, provider: str, news: bool) -> list[dict]:
        if not isinstance(rows, list) or any(not isinstance(row, dict) for row in rows):
            raise InvalidProviderData("Search results must be an array of objects")
        results = []
        for row in rows:
            url = row.get("url" if provider == "tavily" or news else "href")
            result = {
                "title": row.get("title"), "link": url,
                "snippet": row.get("content" if provider == "tavily" else "body"),
                "date": row.get("published_date" if provider == "tavily" else "date") if provider == "tavily" or news else "",
                "source": row.get("source") if provider == "ddg" and news else _domain_of(url),
            }
            results.append({key: "" if value is None else value for key, value in result.items()})
        return results
