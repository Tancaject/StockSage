"""
股票数据 API 路由。

所有接口返回 JSON，直接被 Java 后端的 DataServiceClient 消费。
接口设计原则：
- 参数简单（股票代码 + 少量选项），方便 LLM 通过 Tool Calling 传参
- 返回完整数据，由 LLM 自行从中提取需要的信息
- 错误返回 message 字段，不抛 500（LLM 需要读到错误信息来决定下一步）
"""

from dataclasses import dataclass, field

from fastapi import APIRouter, Query
from app.services.akshare_service import AkshareService
from app.services.baostock_service import BaostockService
from app.services.edgar_service import EdgarService
from app.services.search_service import SearchService
from app.services.market_resolver import (
    A_SHARE,
    HK,
    US,
    resolve_stock_route,
    route_metadata,
    search_stock_routes,
)

router = APIRouter()
bao = BaostockService()       # A 股数据（兼容）
ak_svc = AkshareService()     # 港股免费数据源
edgar = EdgarService()        # 美股 SEC 财报 / XBRL 数据源
search_svc = SearchService()  # Tavily / DDG 新闻检索，供股票新闻端点复用

_MERGEABLE_CANDIDATE_FIELDS = (
    "name",
    "shortName",
    "longName",
    "exchange",
    "quoteType",
    "provider",
    "matchedTerm",
)
_EASTMONEY_CANDIDATE_FIELDS = (
    "shortName",
    "longName",
    "exchange",
    "quoteType",
    "score",
    "provider",
    "matchedTerm",
)
_A_CATALOG_CANDIDATE_FIELDS = (
    "shortName",
    "longName",
    "companyName",
    "exchange",
    "quoteType",
    "score",
    "provider",
)
_HK_CATALOG_CANDIDATE_FIELDS = (
    "shortName",
    "longName",
    "englishName",
    "exchange",
    "quoteType",
    "score",
    "provider",
)


def _with_route(payload: dict, route) -> dict:
    """附加路由元数据，让 LLM 能看到实际使用的市场和 API。"""
    if not isinstance(payload, dict):
        payload = {"data": payload}
    result = dict(payload)
    result.update(route_metadata(route))
    return result


def _parse_indicators(indicators: str) -> list[str]:
    """解析逗号分隔的指标名称，并统一成大写。"""
    return [item.strip().upper() for item in indicators.split(",") if item.strip()]


def _financial_by_route(route) -> dict:
    """按市场路由选择对应的财务概览数据源。"""
    if route.market == A_SHARE:
        return bao.get_financial_metrics(route.api_code)
    if route.market == HK:
        try:
            return ak_svc.get_hk_stock_info(route.api_code)
        except Exception as e:
            return _provider_error("akshare", "financial metrics", e)
    return _unsupported_us_payload("financial metrics")


def _unsupported_us_payload(feature: str) -> dict:
    """返回美股不由 Python 数据服务提供的统一提示。"""
    return {
        "error": True,
        "market": US,
        "feature": feature,
        "message": (
            "US market data is intentionally not served by the Python data service. "
            "Use IBKR read-only tools for US quotes/bars and SEC EDGAR tools for US filings."
        ),
    }


def _provider_error(provider: str, feature: str, error: Exception) -> dict:
    """把供应商异常转换成工具层可读的错误 JSON。"""
    return {
        "error": True,
        "provider": provider,
        "feature": feature,
        "message": f"{provider} {feature} call failed: {error}",
    }


def _needs_provider_fallback(payload: dict) -> bool:
    """Return true when a primary provider payload has no usable market data."""
    if not isinstance(payload, dict):
        return False
    if payload.get("error"):
        return True
    if payload.get("data") == [] and int(payload.get("count") or 0) == 0:
        return True
    if payload.get("indicators") == {} and payload.get("message"):
        return True
    return False


def _a_share_with_baostock_fallback(feature: str, primary_call, fallback_call) -> dict:
    """Prefer AKShare/Eastmoney for A-share reads and keep BaoStock as compatibility fallback."""
    try:
        payload = primary_call()
        if not _needs_provider_fallback(payload):
            return payload
        raise RuntimeError(payload.get("message") or f"akshare {feature} returned no usable data")
    except Exception as e:
        fallback = fallback_call()
        result = dict(fallback) if isinstance(fallback, dict) else {"data": fallback}
        result.setdefault("provider", "baostock")
        result["primaryProvider"] = "akshare"
        result["fallbackProvider"] = result.get("provider") or "baostock"
        result["primaryProviderError"] = str(e)
        return result


def _route_candidate(route, origin: str, extra: dict | None = None) -> dict:
    """把解析路由转换成搜索候选，并附加来源和供应商字段。"""
    candidate = route_metadata(route)
    candidate["origin"] = origin
    if extra:
        candidate.update(extra)
    return candidate


@dataclass(slots=True)
class _CandidateCollector:
    """按稳定身份收集候选，并合并来自不同来源的补充证据。"""

    candidates: list[dict] = field(default_factory=list)
    _seen: dict[tuple[str, str], dict] = field(default_factory=dict)

    def add(self, candidate: dict) -> None:
        key = (
            candidate.get("market", ""),
            candidate.get("resolvedCode") or candidate.get("symbol", ""),
        )
        if not key[1]:
            return

        existing = self._seen.get(key)
        if existing is None:
            self._seen[key] = candidate
            self.candidates.append(candidate)
            return

        origin = candidate.get("origin")
        if origin and origin != existing.get("origin"):
            confirmed_by = existing.setdefault("confirmedBy", [])
            if origin not in confirmed_by:
                confirmed_by.append(origin)

        for candidate_field in _MERGEABLE_CANDIDATE_FIELDS:
            if not existing.get(candidate_field) and candidate.get(candidate_field):
                existing[candidate_field] = candidate[candidate_field]


def _candidate_from_provider_item(
    item: dict,
    origin: str,
    extra_fields: tuple[str, ...],
    keep_unsupported: bool = False,
) -> dict | None:
    """把供应商搜索行转换为稳定候选，不执行任何供应商调用。"""
    symbol = item.get("symbol")
    if not symbol:
        return None

    route = resolve_stock_route(symbol)
    if not route.is_supported:
        if not keep_unsupported:
            return None
        return {
            "input": symbol,
            "market": "UNSUPPORTED",
            "resolvedCode": symbol,
            "source": origin,
            "routeReason": "search result is outside supported A/HK/US routing",
            "origin": origin,
            "symbol": symbol,
            "name": item.get("shortName") or item.get("longName"),
            "shortName": item.get("shortName"),
            "longName": item.get("longName"),
            "exchange": item.get("exchange"),
            "quoteType": item.get("quoteType"),
        }

    extra = {
        "symbol": symbol,
        "name": item.get("shortName") or item.get("longName"),
    }
    extra.update({candidate_field: item.get(candidate_field) for candidate_field in extra_fields})
    return _route_candidate(route, origin, extra)


def _merge_provider_results(
    collector: _CandidateCollector,
    search_result: dict,
    origin: str,
    extra_fields: tuple[str, ...],
    max_results: int,
    keep_unsupported: bool = False,
) -> None:
    """按原始顺序合并一个供应商响应，并保留既有截断语义。"""
    for item in search_result.get("results", []):
        candidate = _candidate_from_provider_item(
            item,
            origin,
            extra_fields,
            keep_unsupported,
        )
        if candidate is None:
            continue
        collector.add(candidate)
        if keep_unsupported and candidate.get("market") == "UNSUPPORTED":
            continue
        if len(collector.candidates) >= max_results:
            break


def _candidate_response(
    query: str,
    collector: _CandidateCollector,
    max_results: int,
) -> dict:
    return {
        "query": query,
        "count": len(collector.candidates),
        "candidates": collector.candidates[:max_results],
    }


def _resolve_with_search(value: str):
    """先直接解析；如果是公司名，再使用供应商搜索兜底。"""
    route = resolve_stock_route(value)
    if route.is_supported:
        return route

    search_result = ak_svc.search_symbols(value, max_results=1)
    for item in search_result.get("results", []):
        symbol = item.get("symbol")
        if not symbol:
            continue
        searched_route = resolve_stock_route(symbol)
        if searched_route.is_supported:
            return searched_route
    return route


def _search_prefers_a_share(query: str, candidates: list[dict]) -> bool:
    """根据用户文本和已有候选判断目录搜索是否应优先查 A 股。"""
    text = query or ""
    if any(hint in text for hint in ("A股", "a股", "沪深", "上交所", "深交所")):
        return True
    return any(item.get("market") == A_SHARE for item in candidates)


def _search_prefers_hk(query: str, candidates: list[dict]) -> bool:
    """根据用户文本和已有候选判断目录搜索是否应优先查港股。"""
    text = query or ""
    if any(hint in text for hint in ("港股", "香港", "港交所", ".HK", ".hk", "HK:", "hk:")):
        return True
    return any(item.get("market") == HK for item in candidates)


@router.get("/kline")
def get_kline(
    code: str = Query(..., description="Stock code/name, e.g. AAPL, TSLA, NVDA"),
    period: str = Query("daily", description="daily/weekly/monthly"),
    days: int = Query(30, description="Number of days"),
):
    """通过自动市场路由获取 K 线（OHLCV）数据。"""
    route = _resolve_with_search(code)
    if not route.is_supported:
        return route.error_payload()
    if route.market == A_SHARE:
        payload = _a_share_with_baostock_fallback(
            "kline",
            lambda: ak_svc.get_a_share_kline(route.api_code, period, days),
            lambda: bao.get_kline(route.api_code, period, days),
        )
    elif route.market == HK:
        try:
            payload = ak_svc.get_hk_kline(route.api_code, period, days)
        except Exception as e:
            payload = _provider_error("akshare", "kline", e)
    else:
        payload = _unsupported_us_payload("kline")
    return _with_route(payload, route)


@router.get("/resolve")
def resolve_stock(
    query: str = Query(..., description="Stock code/name/free-form query"),
):
    """将代码、名称或自由文本查询解析为对应市场的 API 代码。"""
    route = _resolve_with_search(query)
    if not route.is_supported:
        return route.error_payload()
    return route_metadata(route)


@router.get("/search")
def search_stock(
    q: str = Query(..., description="Stock code/name/free-form query"),
    max_results: int = Query(10, ge=1, le=20, description="Max candidates"),
):
    """
    跨 A 股、港股和美股市场搜索股票候选。

    搜索顺序刻意从确定性到高成本：先走本地解析器，再走东方财富 suggest，
    最后才查较慢的 AKShare 目录。这样常见代码/公司名能快速返回，同时保留别名兜底。
    """
    collector = _CandidateCollector()

    # 阶段 1：本地确定性解析器速度快，且无需网络。
    for route in search_stock_routes(q, max_results=max_results):
        collector.add(_route_candidate(route, "local_resolver"))

    if not collector.candidates and not ak_svc.has_specific_stock_terms(q):
        return {
            "query": q,
            "count": 0,
            "candidates": [],
            "message": "No specific stock name or code detected in query",
        }

    # 阶段 2：东方财富 suggest 覆盖跨市场的中英文别名；可用时优先使用。
    em_search = ak_svc.search_symbols(q, max_results=max_results)
    _merge_provider_results(
        collector,
        em_search,
        "eastmoney_suggest",
        _EASTMONEY_CANDIDATE_FIELDS,
        max_results,
    )

    if em_search.get("results"):
        # suggest 命中通常已经包含跨市场候选；直接返回可避免慢目录搜索拖慢对话工具调用。
        return _candidate_response(q, collector, max_results)

    a_search = {"results": []}
    hk_search = {"results": []}

    # 阶段 3：较慢的目录搜索用于补缺，并由市场提示决定先查 A 股还是港股。
    prefer_a = _search_prefers_a_share(q, collector.candidates)
    prefer_hk = _search_prefers_hk(q, collector.candidates)

    if prefer_a and not prefer_hk:
        a_search = ak_svc.search_a_symbols(q, max_results=max_results)
        _merge_provider_results(
            collector,
            a_search,
            "akshare_a_catalog",
            _A_CATALOG_CANDIDATE_FIELDS,
            max_results,
        )
        if len(collector.candidates) < max_results:
            hk_search = ak_svc.search_hk_symbols(q, max_results=max_results)
            _merge_provider_results(
                collector,
                hk_search,
                "akshare_hk_catalog",
                _HK_CATALOG_CANDIDATE_FIELDS,
                max_results,
                keep_unsupported=True,
            )
    else:
        hk_search = ak_svc.search_hk_symbols(q, max_results=max_results)
        _merge_provider_results(
            collector,
            hk_search,
            "akshare_hk_catalog",
            _HK_CATALOG_CANDIDATE_FIELDS,
            max_results,
            keep_unsupported=True,
        )
        if len(collector.candidates) < max_results and not hk_search.get("results"):
            a_search = ak_svc.search_a_symbols(q, max_results=max_results)
            _merge_provider_results(
                collector,
                a_search,
                "akshare_a_catalog",
                _A_CATALOG_CANDIDATE_FIELDS,
                max_results,
            )

    response = _candidate_response(q, collector, max_results)
    if em_search.get("error"):
        response["eastmoneySearchError"] = em_search.get("error")
    if a_search.get("error"):
        response["akshareASearchError"] = a_search.get("error")
    if hk_search.get("error"):
        response["akshareSearchError"] = hk_search.get("error")
    return response


@router.get("/financial")
def get_financial(
    code: str = Query(..., description="Stock code/name, e.g. AAPL, MSFT, NVDA"),
):
    """通过自动市场路由获取关键财务指标。"""
    route = _resolve_with_search(code)
    if not route.is_supported:
        return route.error_payload()
    return _with_route(_financial_by_route(route), route)


@router.get("/financial-report")
def get_financial_report(
    code: str = Query(..., description="Stock code/name, e.g. sh.600519, 0700.HK, AAPL"),
    period: str = Query("annual", description="annual or quarterly"),
    years: int = Query(5, ge=1, le=10, description="Number of annual periods or years of quarters"),
):
    """通过自动市场路由获取结构化财务报表。"""
    route = _resolve_with_search(code)
    if not route.is_supported:
        return route.error_payload()
    if route.market == A_SHARE:
        payload = bao.get_financial_reports(route.api_code, period, years)
    elif route.market == US:
        try:
            payload = edgar.get_xbrl(route.api_code)
            payload["period"] = "annual"
            payload["message"] = (
                "US structured financials are sourced from SEC EDGAR XBRL. "
                "Quarterly support should use SEC 10-Q ingestion/search when needed."
            )
        except Exception as e:
            payload = {"error": True, "message": str(e)}
    else:
        try:
            payload = ak_svc.get_hk_financial_reports(route.api_code, period, years)
        except Exception as e:
            payload = _provider_error("akshare", "financial report", e)
    return _with_route(payload, route)


@router.get("/technical")
def get_technical(
    code: str = Query(..., description="Stock code/name, e.g. AAPL, MSFT, NVDA"),
    indicators: str = Query("MA,MACD,RSI", description="Comma-separated indicator names"),
):
    """通过自动市场路由计算技术指标。"""
    route = _resolve_with_search(code)
    if not route.is_supported:
        return route.error_payload()
    indicator_list = _parse_indicators(indicators)
    if route.market == A_SHARE:
        payload = _a_share_with_baostock_fallback(
            "technical indicators",
            lambda: ak_svc.get_a_share_technical_indicators(route.api_code, indicator_list),
            lambda: bao.get_technical_indicators(route.api_code, indicator_list),
        )
    elif route.market == HK:
        try:
            payload = ak_svc.get_hk_technical_indicators(route.api_code, indicator_list)
        except Exception as e:
            payload = _provider_error("akshare", "technical indicators", e)
    else:
        payload = _unsupported_us_payload("technical indicators")
    return _with_route(payload, route)


@router.get("/news")
def get_news(
    code: str = Query(..., description="Stock code/name"),
    days: int = Query(7, description="Number of days"),
):
    """
    获取某只股票的近期新闻。

    通过 Tavily（DDG 兜底）按股票代码/名称做新闻检索，days 映射为时间窗：
    ``days<=1`` → 24 小时；``days<=7`` → 一周；其它 → 一月。

    历史上该端点曾抛 501 占位，导致 Java 侧 buildNewsSnapshot 每次都打一次 retry+ERROR；
    现在改为委托给 search_service.search_news，复用与 /api/search/news 完全一致的返回结构，
    Java 端无需改动即可拿到真实新闻数据。
    """
    route = _resolve_with_search(code)
    if not route.is_supported:
        return route.error_payload()

    if days <= 1:
        timelimit = "d"
    elif days <= 7:
        timelimit = "w"
    else:
        timelimit = "m"

    # 用原始输入作为查询词：Java 侧通常传 ticker（如 NVDA）或公司名，Tavily 能直接处理。
    query = code.strip()
    result = search_svc.search_news(
        query=query,
        max_results=8,
        timelimit=timelimit,
        depth="basic",
    )
    return _with_route(result, route)


@router.get("/sector")
def get_sector(
    sector: str = Query(..., description="Sector name"),
):
    """获取板块表现概览。"""
    return bao.get_sector_performance(sector)


@router.get("/compare")
def compare_stocks(
    codes: str = Query(..., description="Comma-separated stock codes/names"),
    dimensions: str = Query("PE,ROE", description="Comma-separated dimensions"),
):
    """使用混合市场路由按多个维度对比多只股票。"""
    code_list = [c.strip() for c in codes.split(",")]
    dim_list = [d.strip() for d in dimensions.split(",")]
    results = []
    for item in code_list:
        route = _resolve_with_search(item)
        if not route.is_supported:
            results.append(route.error_payload())
            continue
        results.append(_with_route(_financial_by_route(route), route))
    return {"codes": code_list, "dimensions": dim_list, "comparison": results}


@router.get("/market-overview")
def market_overview():
    """获取主要指数概览。"""
    return bao.get_market_overview()
