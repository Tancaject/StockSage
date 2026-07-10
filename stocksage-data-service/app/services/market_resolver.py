"""
将用户或模型提供的股票标识解析成对应市场的 API 代码。

Java 工具层刻意只暴露一个 code 参数。本模块让 Python 路由可以接收常见的
A 股、港股和美股格式，再分发到 BaoStock、AKShare、IBKR 或 SEC 专用工具。
"""

from dataclasses import dataclass
import re


A_SHARE = "A_SHARE"
HK = "HK"
US = "US"
UNKNOWN = "UNKNOWN"


@dataclass(frozen=True)
class StockRoute:
    """一次股票标识解析的结果。"""

    input: str
    market: str
    api_code: str
    source: str
    reason: str

    @property
    def is_supported(self) -> bool:
        """判断该路由是否能被当前数据源处理。"""
        return self.market != UNKNOWN and bool(self.api_code)

    def error_payload(self) -> dict:
        """生成无法解析时返回给 Java 工具层的错误对象。"""
        return {
            "input": self.input,
            "market": self.market,
            "code": self.api_code,
            "source": self.source,
            "message": (
                "Unable to identify stock market. Use examples like "
                "sh.600519/600519, 0700.HK/00700, or AAPL."
            ),
        }


_MARKET_HINTS = {
    A_SHARE: ("a股", "A股", "沪深", "上交所", "深交所", "上海证券", "深圳证券"),
    HK: ("港股", "香港", "港交所", "HK", "hk", "HKG", "hkg"),
    US: ("美股", "美国", "纳斯达克", "纽交所", "NASDAQ", "Nasdaq", "NYSE", "nyse"),
}


_ALIASES: dict[str, dict[str, str]] = {
    "贵州茅台": {A_SHARE: "sh.600519"},
    "茅台": {A_SHARE: "sh.600519"},
    "平安银行": {A_SHARE: "sz.000001"},
    "招商银行": {A_SHARE: "sh.600036", HK: "3968.HK"},
    "宁德时代": {A_SHARE: "sz.300750"},
    "比亚迪": {A_SHARE: "sz.002594", HK: "1211.HK"},
    "腾讯控股": {HK: "0700.HK"},
    "腾讯": {HK: "0700.HK"},
    "阿里巴巴": {HK: "9988.HK", US: "BABA"},
    "阿里": {HK: "9988.HK", US: "BABA"},
    "美团": {HK: "3690.HK"},
    "小米": {HK: "1810.HK"},
    "小米集团": {HK: "1810.HK"},
    "快手": {HK: "1024.HK"},
    "网易": {HK: "9999.HK", US: "NTES"},
    "京东健康": {HK: "6618.HK"},
    "京东": {HK: "9618.HK", US: "JD"},
    "百度": {HK: "9888.HK", US: "BIDU"},
    "中芯国际": {A_SHARE: "sh.688981", HK: "0981.HK"},
    "中芯": {A_SHARE: "sh.688981", HK: "0981.HK"},
    "香港交易所": {HK: "0388.HK"},
    "港交所": {HK: "0388.HK"},
    "汇丰控股": {HK: "0005.HK"},
    "汇丰": {HK: "0005.HK"},
    "友邦保险": {HK: "1299.HK"},
    "友邦": {HK: "1299.HK"},
    "中国移动": {A_SHARE: "sh.600941", HK: "0941.HK"},
    "中国海洋石油": {A_SHARE: "sh.600938", HK: "0883.HK"},
    "中国平安": {A_SHARE: "sh.601318", HK: "2318.HK"},
    "工商银行": {A_SHARE: "sh.601398", HK: "1398.HK"},
    "建设银行": {A_SHARE: "sh.601939", HK: "0939.HK"},
    "中国银行": {A_SHARE: "sh.601988", HK: "3988.HK"},
    "药明生物": {HK: "2269.HK"},
    "药明康德": {A_SHARE: "sh.603259", HK: "2359.HK"},
    "小鹏汽车": {HK: "9868.HK", US: "XPEV"},
    "小鹏": {HK: "9868.HK", US: "XPEV"},
    "理想汽车": {HK: "2015.HK", US: "LI"},
    "理想": {HK: "2015.HK", US: "LI"},
    "蔚来": {HK: "9866.HK", US: "NIO"},
    "泡泡玛特": {HK: "9992.HK"},
    "苹果": {US: "AAPL"},
    "apple": {US: "AAPL"},
    "特斯拉": {US: "TSLA"},
    "tesla": {US: "TSLA"},
    "微软": {US: "MSFT"},
    "microsoft": {US: "MSFT"},
    "英伟达": {US: "NVDA"},
    "英偉達": {US: "NVDA"},
    "nvidia": {US: "NVDA"},
    "谷歌": {US: "GOOGL"},
    "alphabet": {US: "GOOGL"},
    "亚马逊": {US: "AMZN"},
    "amazon": {US: "AMZN"},
    "meta": {US: "META"},
}


_US_TOKEN_STOPWORDS = {
    "A", "AI", "API", "CFO", "CEO", "ETF", "GDP", "HK", "IPO", "K", "MA",
    "MACD", "NAV", "PB", "PE", "Q", "ROA", "ROE", "ROI", "RSI", "SEC", "US",
}


def resolve_stock_route(value: str) -> StockRoute:
    """根据股票代码或名称推断市场和对应 API 代码。"""
    return _resolve_stock_route(value, allow_embedded=True)


def search_stock_routes(query: str, max_results: int = 10) -> list[StockRoute]:
    """从自由文本查询中返回本地解析出的股票候选。"""
    hint = _detect_market_hint(query or "")
    routes: list[StockRoute] = []
    seen: set[tuple[str, str]] = set()

    for candidate in _candidate_inputs(query or ""):
        route = _resolve_stock_route(candidate, allow_embedded=False, hint_override=hint)
        if not route.is_supported:
            continue
        key = (route.market, route.api_code)
        if key in seen:
            continue
        seen.add(key)
        routes.append(route)
        if len(routes) >= max_results:
            break

    return routes


def _resolve_stock_route(
    value: str,
    allow_embedded: bool,
    hint_override: str | None = None,
) -> StockRoute:
    """按从最明确格式到最模糊文本的顺序应用路由规则。"""
    raw = (value or "").strip()
    compact = re.sub(r"\s+", "", raw)
    upper = compact.upper()
    lower = compact.lower()
    hint = hint_override or _detect_market_hint(raw)

    if not compact:
        return _unknown(raw, "empty input")

    # 人类可读公司名先于正则检查，确保“腾讯”“英伟达”等常见中文别名能解析到预期市场。
    alias = _resolve_alias(raw, hint)
    if alias:
        return alias

    # 前缀格式：SH:600519、HK:0700、NASDAQ:AAPL。
    prefixed_a = re.fullmatch(r"(SH|SSE|SZ|SZSE|BJ|BSE)[:.]?(\d{6})", upper)
    if prefixed_a:
        exchange = _a_share_exchange(prefixed_a.group(2), prefixed_a.group(1))
        return _route(raw, A_SHARE, f"{exchange}.{prefixed_a.group(2)}", "baostock", "explicit A-share prefix")

    prefixed_hk = re.fullmatch(r"(HK|HKG|HKEX)[:.]?0*(\d{1,5})", upper)
    if prefixed_hk:
        return _route(raw, HK, _hk_symbol(prefixed_hk.group(2)), "akshare", "explicit HK prefix")

    prefixed_us = re.fullmatch(r"(US|NASDAQ|NYSE|NYSEARCA|AMEX)[:.]?([A-Z][A-Z0-9.-]{0,9})", upper)
    if prefixed_us:
        return _route(raw, US, _us_symbol(prefixed_us.group(2)), "ibkr", "explicit US prefix")

    # 后缀格式：600519.SH、0700.HK、AAPL.US。
    suffixed_a = re.fullmatch(r"(\d{6})\.(SH|SS|SSE|SZ|SZSE|BJ|BSE)", upper)
    if suffixed_a:
        exchange = _a_share_exchange(suffixed_a.group(1), suffixed_a.group(2))
        return _route(raw, A_SHARE, f"{exchange}.{suffixed_a.group(1)}", "baostock", "explicit A-share suffix")

    suffixed_hk = re.fullmatch(r"0*(\d{1,5})\.(HK|HKG|HKEX)", upper)
    if suffixed_hk:
        return _route(raw, HK, _hk_symbol(suffixed_hk.group(1)), "akshare", "explicit HK suffix")

    suffixed_us = re.fullmatch(r"([A-Z][A-Z0-9.-]{0,9})\.(US|NASDAQ|NYSE|NYSEARCA|AMEX)", upper)
    if suffixed_us:
        return _route(raw, US, _us_symbol(suffixed_us.group(1)), "ibkr", "explicit US suffix")

    # BaoStock 原生格式。
    if re.fullmatch(r"(sh|sz|bj)\.\d{6}", lower):
        return _route(raw, A_SHARE, lower, "baostock", "baostock code")

    # 纯数字代码。
    if re.fullmatch(r"\d{6}", compact):
        exchange = _infer_a_share_exchange(compact)
        if exchange:
            return _route(raw, A_SHARE, f"{exchange}.{compact}", "baostock", "6-digit A-share code")
        if hint == HK:
            return _route(raw, HK, _hk_symbol(compact), "akshare", "HK hint with numeric code")
        return _unknown(raw, "unsupported 6-digit code prefix")

    if re.fullmatch(r"\d{1,5}", compact):
        return _route(raw, HK, _hk_symbol(compact), "akshare", "numeric HK code")

    if "." in upper and not re.fullmatch(r"[A-Z]{1,4}\.[A-Z]", upper):
        return _unknown(raw, "unsupported exchange suffix")

    # 美股代码，包括 BRK.B/BRK-B 这类分级股符号。
    if re.fullmatch(r"[A-Z][A-Z0-9-]{0,9}", upper) or re.fullmatch(r"[A-Z]{1,4}\.[A-Z]", upper):
        return _route(raw, US, _us_symbol(upper), "ibkr", "US ticker")

    if allow_embedded:
        # 完整自然语言问题常把股票代码或别名埋在句子里；只有顶层解析器尝试抽取。
        embedded = _resolve_embedded(raw, hint)
        if embedded:
            return embedded

    return _unknown(raw, "no matching market rule")


def route_metadata(route: StockRoute) -> dict:
    """把 StockRoute 转成接口响应可直接合并的元数据字段。"""
    return {
        "input": route.input,
        "market": route.market,
        "resolvedCode": route.api_code,
        "source": route.source,
        "routeReason": route.reason,
    }


def _detect_market_hint(raw: str) -> str | None:
    """从自然语言文本中识别用户显式提到的市场。"""
    for market, hints in _MARKET_HINTS.items():
        if any(hint in raw for hint in hints):
            return market
    return None


def _resolve_alias(raw: str, hint: str | None) -> StockRoute | None:
    """使用内置公司名别名字典解析常见中英文名称。"""
    compact = re.sub(r"\s+", "", raw).lower()
    for alias, choices in _ALIASES.items():
        if alias.lower() not in compact:
            continue

        if hint in choices:
            market = hint
        elif len(choices) == 1:
            market = next(iter(choices))
        elif A_SHARE in choices:
            market = A_SHARE
        elif HK in choices:
            market = HK
        else:
            market = next(iter(choices))

        return _route(raw, market, choices[market], _source_for(market), f"alias: {alias}")
    return None


def _resolve_embedded(raw: str, hint: str | None) -> StockRoute | None:
    """从完整自然语言问题中抽取可能的代码或公司别名。"""
    for candidate in _candidate_inputs(raw):
        if candidate.strip() == raw.strip():
            continue
        route = _resolve_stock_route(candidate, allow_embedded=False, hint_override=hint)
        if route.is_supported:
            return route
    return None


def _candidate_inputs(raw: str) -> list[str]:
    """按多种格式从原始输入中生成候选标识。"""
    text = raw or ""
    compact = re.sub(r"\s+", "", text)
    upper = text.upper()
    candidates: list[str] = []

    def add(value: str | None) -> None:
        """按大小写不敏感方式追加候选，并保持原始顺序。"""
        if value is None:
            return
        value = value.strip()
        if not value:
            return
        normalized = value.lower()
        if normalized not in {item.lower() for item in candidates}:
            candidates.append(value)

    add(text)

    compact_lower = compact.lower()
    for alias in _ALIASES:
        if alias.lower() in compact_lower:
            add(alias)

    patterns = [
        r"(?:SH|SSE|SZ|SZSE|BJ|BSE)[:.]?\d{6}",
        r"\d{6}\.(?:SH|SS|SSE|SZ|SZSE|BJ|BSE)",
        r"(?:HK|HKG|HKEX)[:.]?0*\d{1,5}",
        r"0*\d{1,5}\.(?:HK|HKG|HKEX)",
        r"(?:US|NASDAQ|NYSE|NYSEARCA|AMEX)[:.]?[A-Z][A-Z0-9.-]{0,9}",
        r"[A-Z][A-Z0-9.-]{0,9}\.(?:US|NASDAQ|NYSE|NYSEARCA|AMEX)",
    ]
    for pattern in patterns:
        for match in re.finditer(pattern, upper):
            add(match.group(0))

    for match in re.finditer(r"(?:sh|sz|bj)\.\d{6}", text, re.IGNORECASE):
        add(match.group(0))

    for match in re.finditer(r"(?<!\d)(\d{6})(?!\d)", text):
        add(match.group(1))

    for match in re.finditer(r"(?<!\d)(\d{1,5})(?!\d)", text):
        add(match.group(1))

    for match in re.finditer(r"\b[A-Z][A-Z0-9.-]{0,9}\b", text):
        token = match.group(0).upper()
        if token not in _US_TOKEN_STOPWORDS:
            add(token)

    return candidates


def _source_for(market: str) -> str:
    """返回指定市场对应的数据供应商名称。"""
    if market == A_SHARE:
        return "baostock"
    if market == HK:
        return "akshare"
    if market == US:
        return "ibkr"
    return ""


def _route(raw: str, market: str, api_code: str, source: str, reason: str) -> StockRoute:
    """构造成功解析的路由对象。"""
    return StockRoute(input=raw, market=market, api_code=api_code, source=source, reason=reason)


def _unknown(raw: str, reason: str) -> StockRoute:
    """构造无法识别市场时的路由对象。"""
    return StockRoute(input=raw, market=UNKNOWN, api_code="", source="", reason=reason)


def _a_share_exchange(code: str, exchange_hint: str) -> str:
    """根据显式交易所提示或代码前缀推断 A 股交易所。"""
    hint = exchange_hint.upper()
    if hint in {"SH", "SS", "SSE"}:
        return "sh"
    if hint in {"SZ", "SZSE"}:
        return "sz"
    if hint in {"BJ", "BSE"}:
        return "bj"
    return _infer_a_share_exchange(code) or "sh"


def _infer_a_share_exchange(code: str) -> str | None:
    """按 A 股代码段推断交易所。"""
    if code.startswith(("5", "6", "9")):
        return "sh"
    if code.startswith(("0", "2", "3")):
        return "sz"
    if code.startswith(("4", "8")):
        return "bj"
    return None


def _hk_symbol(code: str) -> str:
    """把港股数字代码规范化为四位 .HK 格式。"""
    numeric = str(int(code))
    return f"{numeric.zfill(4)}.HK"


def _us_symbol(symbol: str) -> str:
    """把美股代码规范化为 IBKR 更容易识别的符号。"""
    return symbol.upper().replace(".", "-")
