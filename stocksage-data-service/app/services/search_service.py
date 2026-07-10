"""
网页搜索服务。

provider 策略：优先使用 Tavily（需要 API Key，面向 LLM 检索设计、稳定、基本不限流）；
当未配置 Tavily Key 或 Tavily 调用失败时，自动回退到免费的 DuckDuckGo。
DuckDuckGo 免费接口很容易触发 202 Ratelimit，因此只作兜底，不作主力。

对外返回结构在两个 provider 间保持一致（query/timelimit/provider/count/results），
Java 后端与模型无需感知具体来源；results 里多一个 provider 字段方便排查实际走了哪条路。

支持 timelimit 时间范围过滤（d/w/m/y），把"最新"类问题约束到近期结果，
避免通用网页检索按相关性返回陈旧的权威页面。
"""

import logging
from typing import Optional
from urllib.parse import urlparse

import httpx
from duckduckgo_search import DDGS

from app.config import TAVILY_API_KEY, TAVILY_WEB_TOPIC

logger = logging.getLogger(__name__)

# DuckDuckGo 接受的时间范围取值。news 接口不支持 'y'。
_TEXT_TIMELIMITS = {"d", "w", "m", "y"}
_NEWS_TIMELIMITS = {"d", "w", "m"}

# Tavily 检索接口。
_TAVILY_URL = "https://api.tavily.com/search"
# Tavily basic 检索通常 1~3 秒返回；8 秒超时既留足余量，又能在 Tavily 卡住时
# 尽快回退到 DuckDuckGo，避免拖垮 Java 端 10 秒的数据服务调用超时。
_TAVILY_TIMEOUT = 8.0

# timelimit 短码 -> Tavily 通用检索的 time_range 取值。
_TAVILY_TIME_RANGE = {"d": "day", "w": "week", "m": "month", "y": "year"}
# topic=news 时 Tavily 用 days（回溯天数）而非 time_range。
_TAVILY_NEWS_DAYS = {"d": 1, "w": 7, "m": 30}

# Tavily 检索深度合法取值。advanced 召回更全、相关性更好，但每次消耗 2 credit（basic 为 1）。
_TAVILY_DEPTHS = {"basic", "advanced"}


def _normalize_timelimit(value: Optional[str], allowed: set) -> Optional[str]:
    """把外部传入的时间范围参数收敛为认可的取值，非法或空值按不过滤处理。"""
    if not value:
        return None
    normalized = value.strip().lower()
    return normalized if normalized in allowed else None


def _domain_of(url: str) -> str:
    """从 URL 粗取域名，作为新闻 source 的兜底显示值。"""
    if not url:
        return ""
    try:
        netloc = urlparse(url).netloc
        return netloc[4:] if netloc.startswith("www.") else netloc
    except Exception:
        return ""


class SearchService:

    def search(self, query: str, max_results: int = 5,
               timelimit: Optional[str] = None, depth: str = "basic") -> dict:
        """
        搜索网页并返回靠前结果。每条结果包含标题、链接和摘要。

        timelimit 限定结果时间范围：d=24 小时 / w=一周 / m=一月 / y=一年；None 表示不限。
        depth 为 Tavily 检索深度（basic/advanced），仅对 Tavily 生效，DuckDuckGo 兜底时忽略。
        优先 Tavily，失败或未配置 Key 时回退 DuckDuckGo。
        """
        tl = _normalize_timelimit(timelimit, _TEXT_TIMELIMITS)
        if TAVILY_API_KEY:
            try:
                return self._search_tavily(query, max_results, tl,
                                           topic=TAVILY_WEB_TOPIC, depth=depth)
            except Exception as e:
                logger.warning("Tavily web search failed, falling back to DuckDuckGo: %s", e)
        return self._search_ddg(query, max_results, tl)

    def search_news(self, query: str, max_results: int = 5,
                    timelimit: Optional[str] = None, depth: str = "basic") -> dict:
        """
        搜索近期新闻文章。

        timelimit 限定结果时间范围：d=24 小时 / w=一周 / m=一月；None 表示不限。
        depth 为 Tavily 检索深度（basic/advanced），仅对 Tavily 生效，DuckDuckGo 兜底时忽略。
        优先 Tavily，失败或未配置 Key 时回退 DuckDuckGo。
        """
        tl = _normalize_timelimit(timelimit, _NEWS_TIMELIMITS)
        if TAVILY_API_KEY:
            try:
                return self._search_tavily(query, max_results, tl,
                                           topic="news", depth=depth)
            except Exception as e:
                logger.warning("Tavily news search failed, falling back to DuckDuckGo: %s", e)
        return self._search_news_ddg(query, max_results, tl)

    # ===== Tavily（主力）=====

    def _search_tavily(self, query: str, max_results: int,
                       tl: Optional[str], topic: str, depth: str = "basic") -> dict:
        """
        调用 Tavily /search 接口。

        topic 取 general/finance（通用网页/财经）或 news（新闻）；
        depth 取 basic（1 credit/次）或 advanced（2 credit/次，召回更全、相关性更好），
        非法值收敛为 basic。

        任何 HTTP 错误（401 无效 Key、429/432 额度耗尽、超时等）都会抛异常，
        由上层 search/search_news 捕获并回退到 DuckDuckGo。
        """
        search_depth = depth if depth in _TAVILY_DEPTHS else "basic"
        payload = {
            "query": query,
            "max_results": max_results,
            "topic": topic,
            "search_depth": search_depth,
        }
        if topic == "news":
            if tl in _TAVILY_NEWS_DAYS:
                payload["days"] = _TAVILY_NEWS_DAYS[tl]
        elif tl in _TAVILY_TIME_RANGE:
            payload["time_range"] = _TAVILY_TIME_RANGE[tl]

        headers = {"Authorization": f"Bearer {TAVILY_API_KEY}"}
        with httpx.Client(timeout=_TAVILY_TIMEOUT) as client:
            resp = client.post(_TAVILY_URL, json=payload, headers=headers)
            resp.raise_for_status()
            data = resp.json()

        results = []
        for r in data.get("results", []):
            item = {
                "title": r.get("title", ""),
                "link": r.get("url", ""),
                "snippet": r.get("content", ""),
            }
            if topic == "news":
                item["date"] = r.get("published_date", "")
                item["source"] = _domain_of(r.get("url", ""))
            results.append(item)

        return {
            "query": query,
            "timelimit": tl,
            "provider": "tavily",
            "topic": topic,
            "depth": search_depth,
            "count": len(results),
            "results": results,
        }

    # ===== DuckDuckGo（兜底）=====

    def _search_ddg(self, query: str, max_results: int, tl: Optional[str]) -> dict:
        """DuckDuckGo 通用网页检索兜底。被限流时返回 count=0 并带 error 字段。"""
        try:
            with DDGS() as ddgs:
                results = list(ddgs.text(query, timelimit=tl, max_results=max_results))
            return {
                "query": query,
                "timelimit": tl,
                "provider": "ddg",
                "count": len(results),
                "results": [
                    {
                        "title": r.get("title", ""),
                        "link": r.get("href", ""),
                        "snippet": r.get("body", ""),
                    }
                    for r in results
                ],
            }
        except Exception as e:
            return {"query": query, "timelimit": tl, "provider": "ddg",
                    "count": 0, "results": [], "error": str(e)}

    def _search_news_ddg(self, query: str, max_results: int, tl: Optional[str]) -> dict:
        """DuckDuckGo 新闻检索兜底。被限流时返回 count=0 并带 error 字段。"""
        try:
            with DDGS() as ddgs:
                results = list(ddgs.news(query, timelimit=tl, max_results=max_results))
            return {
                "query": query,
                "timelimit": tl,
                "provider": "ddg",
                "count": len(results),
                "results": [
                    {
                        "title": r.get("title", ""),
                        "link": r.get("url", ""),
                        "snippet": r.get("body", ""),
                        "date": r.get("date", ""),
                        "source": r.get("source", ""),
                    }
                    for r in results
                ],
            }
        except Exception as e:
            return {"query": query, "timelimit": tl, "provider": "ddg",
                    "count": 0, "results": [], "error": str(e)}
