"""
面向免费 A 股和港股市场数据的 AKShare 服务。

AKShare 封装东方财富等公开数据页面，适合作为 A 股/港股日线、技术指标和港股结构化财报的免费来源；
但它只能按尽力而为处理，不应视为交易所授权的实时行情源。
"""

from datetime import datetime, timedelta
from datetime import date as date_type
from functools import lru_cache
import concurrent.futures
import importlib
import re

import httpx
import pandas as pd

from app.services.technical_indicators import calculate_technical_indicators


# akshare 目录函数（stock_hk_spot / stock_info_*_name_code 等）内部用 requests 拉大表，
# 且不带任何超时；上游网络异常时会无限期挂起，远超 Java 客户端 10s 预算。
# 这里给目录加载套一个硬上限：超时即抛 TimeoutError，由调用方降级为“目录不可用”。
_CATALOG_TIMEOUT_SECONDS = 12.0
_catalog_executor = concurrent.futures.ThreadPoolExecutor(
    max_workers=4, thread_name_prefix="akshare-catalog")


def _call_with_timeout(fn, timeout, label):
    """在工作线程里运行阻塞调用并施加硬超时。

    超时后底层线程仍会继续跑（无法强杀），但会在 OS socket 超时后自行结束；
    目录调用很少（成功后 lru_cache 长期缓存），偶发的线程滞留可忽略。
    """
    future = _catalog_executor.submit(fn)
    try:
        return future.result(timeout=timeout)
    except concurrent.futures.TimeoutError as exc:
        raise TimeoutError(
            f"{label} timed out after {timeout}s (akshare upstream unreachable)") from exc


class AkshareService:

    EASTMONEY_SEARCH_URL = "https://searchapi.eastmoney.com/api/suggest/get"
    GENERIC_QUERY_TERMS = {
        "是否", "值得", "长期", "投资", "分析", "一下", "为什么", "为何", "原因",
        "股价", "暴涨", "大涨", "下跌", "今天", "这两天", "最近", "近期", "最新",
        "公司", "股票", "港股", "美股", "a股", "财报", "年报", "季报",
    }

    def get_hk_kline(self, symbol: str, period: str, days: int) -> dict:
        """通过 AKShare 东方财富端点获取港股日线、周线或月线。"""
        ak = self._ak()
        code = self._hk_code(symbol)
        period_map = {"daily": "daily", "weekly": "weekly", "monthly": "monthly"}
        ak_period = period_map.get(period, "daily")

        end = datetime.now()
        start = end - timedelta(days=days)
        df = ak.stock_hk_hist(
            symbol=code,
            period=ak_period,
            start_date=start.strftime("%Y%m%d"),
            end_date=end.strftime("%Y%m%d"),
            adjust="qfq",
        )
        if df is None or df.empty:
            return {"symbol": code, "count": 0, "data": [], "message": "No HK data found from AKShare"}

        df = df.rename(columns={
            "日期": "Date",
            "开盘": "Open",
            "最高": "High",
            "最低": "Low",
            "收盘": "Close",
            "成交量": "Volume",
            "成交额": "Amount",
            "涨跌幅": "PctChange",
            "涨跌额": "Change",
            "换手率": "TurnoverRate",
        })
        columns = [col for col in ["Date", "Open", "High", "Low", "Close", "Volume", "Amount", "PctChange", "Change", "TurnoverRate"] if col in df.columns]
        records = df[columns].tail(max(1, days)).to_dict(orient="records")
        return {
            "symbol": code,
            "period": period,
            "count": len(records),
            "data": records,
            "provider": "akshare",
        }

    def get_a_share_kline(self, symbol: str, period: str, days: int) -> dict:
        """Fetch A-share OHLCV bars through AKShare's Eastmoney-backed endpoint."""
        ak = self._ak()
        code = self._a_share_code(symbol)
        request_days = max(1, int(days or 30))
        period_map = {"daily": "daily", "weekly": "weekly", "monthly": "monthly"}
        ak_period = period_map.get(period, "daily")

        end = datetime.now()
        start = end - timedelta(days=request_days)
        df = ak.stock_zh_a_hist(
            symbol=code,
            period=ak_period,
            start_date=start.strftime("%Y%m%d"),
            end_date=end.strftime("%Y%m%d"),
            adjust="qfq",
        )
        if df is None or df.empty:
            return {
                "code": symbol,
                "symbol": code,
                "period": period,
                "count": 0,
                "data": [],
                "provider": "akshare",
                "message": "No A-share data found from AKShare",
            }

        df = df.rename(columns={
            "\u65e5\u671f": "date",
            "\u80a1\u7968\u4ee3\u7801": "code",
            "\u5f00\u76d8": "open",
            "\u6536\u76d8": "close",
            "\u6700\u9ad8": "high",
            "\u6700\u4f4e": "low",
            "\u6210\u4ea4\u91cf": "volume",
            "\u6210\u4ea4\u989d": "amount",
            "\u632f\u5e45": "amplitude",
            "\u6da8\u8dcc\u5e45": "pctChg",
            "\u6da8\u8dcc\u989d": "change",
            "\u6362\u624b\u7387": "turnoverRate",
        })
        columns = [
            col for col in [
                "date", "code", "open", "high", "low", "close", "volume", "amount",
                "amplitude", "pctChg", "change", "turnoverRate",
            ]
            if col in df.columns
        ]
        records = [
            {str(k): self._json_value(v) for k, v in row.items()}
            for row in df[columns].tail(request_days).to_dict(orient="records")
        ]
        return {
            "code": symbol,
            "symbol": code,
            "period": period,
            "count": len(records),
            "data": records,
            "provider": "akshare",
        }

    def get_hk_stock_info(self, symbol: str) -> dict:
        """通过轻量东方财富 suggest API 获取港股基础字段。

        这里避免拉取完整 AKShare 港股表：stock_hk_spot_em 和 stock_hk_spot
        都可能阻塞过久，在对话预取期间触发 Java 工具超时。
        """
        code = self._hk_code(symbol)
        row = self._hk_suggest_row(code)
        if not row:
            return {
                "symbol": code,
                "currency": "HKD",
                "exchange": "HKEX",
                "provider": "eastmoney_suggest",
                "message": "No lightweight HK metadata found",
            }

        return {
            "symbol": code,
            "name": row.get("shortName") or row.get("longName"),
            "shortName": row.get("shortName"),
            "longName": row.get("longName"),
            "price": None,
            "change": None,
            "pctChange": None,
            "open": None,
            "high": None,
            "low": None,
            "prevClose": None,
            "volume": None,
            "amount": None,
            "pe": None,
            "marketCap": None,
            "currency": "HKD",
            "exchange": "HKEX",
            "provider": row.get("provider") or "eastmoney_suggest",
            "message": "Lightweight HK metadata returned; use K-line or financial-report endpoints for richer data.",
            "raw": row,
        }

    def search_hk_symbols(self, query: str, max_results: int = 10) -> dict:
        """从 AKShare 港股目录按代码或名称搜索港股符号。"""
        q = (query or "").strip().lower()
        if not q:
            return {"query": query, "count": 0, "results": []}

        try:
            df = self._hk_search_catalog()
        except Exception as e:
            return {"query": query, "count": 0, "results": [], "error": str(e)}

        results: list[dict] = []
        query_compact = self._compact_text(query)
        code_query = q.replace(".hk", "").replace("hk:", "").replace("hk", "").lstrip("0")
        for _, row in df.iterrows():
            code = str(row.get("代码", "")).zfill(5)
            chinese_name = str(row.get("中文名称", row.get("名称", "")))
            english_name = str(row.get("英文名称", ""))
            score = self._match_score(query_compact, code_query, code, chinese_name, english_name)
            if score > 0:
                results.append({
                    "symbol": f"{code}.HK",
                    "shortName": chinese_name,
                    "longName": chinese_name,
                    "englishName": english_name,
                    "exchange": "HKEX",
                    "quoteType": "EQUITY",
                    "score": score,
                    "provider": "akshare_stock_hk_spot",
                })
        results.sort(key=lambda item: item.get("score", 0), reverse=True)
        return {"query": query, "count": len(results[:max_results]), "results": results[:max_results]}

    def search_symbols(self, query: str, max_results: int = 10) -> dict:
        """通过东方财富公开 suggest API 搜索 A 股、港股和美股候选。"""
        results: list[dict] = []
        seen: set[tuple[str, str]] = set()
        errors: list[str] = []

        for term in self._query_terms(query):
            try:
                resp = httpx.get(
                    self.EASTMONEY_SEARCH_URL,
                    params={"input": term, "type": "14", "count": max_results},
                    headers={"User-Agent": "Mozilla/5.0"},
                    timeout=4.0,
                )
                resp.raise_for_status()
                payload = resp.json()
            except Exception as e:
                errors.append(f"{term}: {e}")
                continue

            rows = ((payload or {}).get("QuotationCodeTable") or {}).get("Data") or []
            for row in rows:
                item = self._eastmoney_item(row, term)
                if not item:
                    continue
                key = (item["market"], item["symbol"])
                if key in seen:
                    continue
                seen.add(key)
                results.append(item)
                if len(results) >= max_results:
                    break
            if results:
                break

        response = {"query": query, "count": len(results), "results": results}
        if errors and not results:
            response["error"] = "; ".join(errors[:3])
        return response

    def search_a_symbols(self, query: str, max_results: int = 10) -> dict:
        """从 AKShare 交易所代码表按代码或名称搜索 A 股符号。"""
        q = (query or "").strip().lower()
        if not q:
            return {"query": query, "count": 0, "results": []}

        try:
            df = self._a_share_catalog()
        except Exception as e:
            return {"query": query, "count": 0, "results": [], "error": str(e)}

        results: list[dict] = []
        query_compact = self._compact_text(query)
        code_query = re.sub(r"\D", "", q)
        for _, row in df.iterrows():
            code = str(row.get("code", ""))
            short_name = str(row.get("shortName", ""))
            long_name = str(row.get("longName", ""))
            company_name = str(row.get("companyName", ""))
            score = self._match_score(query_compact, code_query, code, short_name, long_name, company_name)
            if score > 0:
                exchange = row.get("exchange", "")
                prefix = {"SH": "sh", "SZ": "sz", "BJ": "bj"}.get(exchange, "")
                results.append({
                    "symbol": f"{prefix}.{code}" if prefix else code,
                    "shortName": short_name,
                    "longName": long_name or company_name,
                    "companyName": company_name,
                    "exchange": exchange,
                    "quoteType": "EQUITY",
                    "score": score,
                    "provider": "akshare_a_share_code_table",
                })
        results.sort(key=lambda item: item.get("score", 0), reverse=True)
        return {"query": query, "count": len(results[:max_results]), "results": results[:max_results]}

    def has_specific_stock_terms(self, query: str) -> bool:
        """判断自然语言查询中是否包含可搜索的股票术语。"""
        return bool(self._query_terms(query))

    def get_hk_financial_reports(self, symbol: str, period: str = "annual", years: int = 5) -> dict:
        """获取港股资产负债表、利润表、现金流和关键指标。"""
        ak = self._ak()
        code = self._hk_code(symbol)
        indicator = "年度" if (period or "annual").lower() in {"annual", "year", "yearly", "年报", "年度"} else "报告期"
        max_rows = max(1, min(int(years or 5) * (1 if indicator == "年度" else 4), 40))

        report_types = {
            "balanceSheet": "资产负债表",
            "incomeStatement": "利润表",
            "cashFlow": "现金流量表",
        }
        statements = {}
        errors = {}
        for key, report_type in report_types.items():
            try:
                df = ak.stock_financial_hk_report_em(stock=code, symbol=report_type, indicator=indicator)
                statements[key] = self._records_from_frame(df, max_rows)
            except Exception as e:
                statements[key] = []
                errors[key] = str(e)

        try:
            indicators_df = ak.stock_financial_hk_analysis_indicator_em(symbol=code, indicator=indicator)
            indicators = self._records_from_frame(indicators_df, max_rows)
        except Exception as e:
            indicators = []
            errors["indicators"] = str(e)

        return {
            "symbol": code,
            "period": "annual" if indicator == "年度" else "report_period",
            "provider": "akshare",
            "statements": statements,
            "indicators": indicators,
            "statementCount": sum(len(value) for value in statements.values()) + len(indicators),
            "errors": errors,
            "message": None if any(statements.values()) or indicators else "No HK financial report data found from AKShare",
        }

    def get_hk_technical_indicators(self, symbol: str, indicators: list[str]) -> dict:
        kline = self.get_hk_kline(symbol, "daily", 160)
        if not kline["data"]:
            return {"symbol": self._hk_code(symbol), "indicators": {}, "message": "No HK kline data"}

        df = pd.DataFrame(kline["data"])
        df["Close"] = pd.to_numeric(df["Close"], errors="coerce")
        result = calculate_technical_indicators(
            df["Close"],
            indicators,
            self._rounded,
        )

        return {"symbol": self._hk_code(symbol), "indicators": result, "provider": "akshare"}

    def get_a_share_technical_indicators(self, symbol: str, indicators: list[str]) -> dict:
        kline = self.get_a_share_kline(symbol, "daily", 160)
        code = self._a_share_code(symbol)
        if not kline["data"]:
            return {
                "code": symbol,
                "symbol": code,
                "indicators": {},
                "provider": "akshare",
                "message": "No A-share kline data",
            }

        df = pd.DataFrame(kline["data"])
        df["close"] = pd.to_numeric(df["close"], errors="coerce")
        result = calculate_technical_indicators(
            df["close"],
            indicators,
            self._rounded,
        )

        return {"code": symbol, "symbol": code, "indicators": result, "provider": "akshare"}

    def _ak(self):
        try:
            return importlib.import_module("akshare")
        except ImportError as e:
            raise RuntimeError("AKShare is not installed. Run: pip install -r requirements.txt") from e

    @staticmethod
    def _hk_code(symbol: str) -> str:
        value = (symbol or "").upper().replace(".HK", "").replace("HK:", "").replace("HK", "")
        digits = "".join(ch for ch in value if ch.isdigit())
        return digits.zfill(5)

    @staticmethod
    def _a_share_code(symbol: str) -> str:
        value = str(symbol or "").strip()
        digits = "".join(ch for ch in value if ch.isdigit())
        return digits[-6:].zfill(6)

    def _hk_spot_row(self, code: str) -> dict:
        try:
            df = self._hk_spot()
        except Exception:
            df = self._hk_search_catalog()
        if "代码" not in df.columns:
            return {}
        matched = df[df["代码"].astype(str).str.zfill(5) == code]
        if matched.empty:
            return {}
        return {str(k): self._json_value(v) for k, v in matched.iloc[0].to_dict().items()}

    def _hk_catalog_row(self, code: str) -> dict:
        df = self._hk_search_catalog()
        if "代码" not in df.columns:
            return {}
        matched = df[df["代码"].astype(str).str.zfill(5) == code]
        if matched.empty:
            return {}
        return {str(k): self._json_value(v) for k, v in matched.iloc[0].to_dict().items()}

    def _hk_suggest_row(self, code: str) -> dict:
        result = self.search_symbols(f"{code}.HK", max_results=3)
        for item in result.get("results", []):
            if item.get("market") == "HK" and self._hk_code(item.get("symbol", "")) == code:
                return item
        return {}

    @lru_cache(maxsize=1)
    def _hk_spot(self):
        return self._ak().stock_hk_spot_em()

    @lru_cache(maxsize=1)
    def _hk_search_catalog(self):
        return _call_with_timeout(
            self._load_hk_search_catalog, _CATALOG_TIMEOUT_SECONDS, "HK stock catalog")

    def _load_hk_search_catalog(self):
        df = self._ak().stock_hk_spot()
        if "中文名称" in df.columns:
            return df
        return df.rename(columns={"名称": "中文名称"})

    @lru_cache(maxsize=1)
    def _a_share_catalog(self):
        return _call_with_timeout(
            self._load_a_share_catalog, _CATALOG_TIMEOUT_SECONDS, "A-share stock catalog")

    def _load_a_share_catalog(self):
        ak = self._ak()
        frames = []

        sh = ak.stock_info_sh_name_code().rename(columns={
            "证券代码": "code",
            "证券简称": "shortName",
            "证券全称": "longName",
            "公司简称": "companyShortName",
            "公司全称": "companyName",
            "上市日期": "listDate",
        })
        sh["exchange"] = "SH"
        frames.append(sh)

        sz = ak.stock_info_sz_name_code().rename(columns={
            "A股代码": "code",
            "A股简称": "shortName",
            "A股上市日期": "listDate",
            "所属行业": "industry",
        })
        sz["exchange"] = "SZ"
        frames.append(sz)

        bj = ak.stock_info_bj_name_code().rename(columns={
            "证券代码": "code",
            "证券简称": "shortName",
            "上市日期": "listDate",
            "所属行业": "industry",
        })
        bj["exchange"] = "BJ"
        frames.append(bj)

        columns = [
            "exchange",
            "code",
            "shortName",
            "longName",
            "companyShortName",
            "companyName",
            "industry",
            "listDate",
        ]
        catalog = pd.concat(frames, ignore_index=True)
        for column in columns:
            if column not in catalog.columns:
                catalog[column] = ""
        catalog["code"] = catalog["code"].astype(str).str.zfill(6)
        return catalog[columns]

    def _eastmoney_item(self, row: dict, matched_term: str) -> dict | None:
        code = str(row.get("Code") or row.get("UnifiedCode") or "").strip()
        name = str(row.get("Name") or "").strip()
        classify = str(row.get("Classify") or "")
        jys = str(row.get("JYS") or "")
        security_type = str(row.get("SecurityTypeName") or "")
        type_us = str(row.get("TypeUS") or "")
        quote_id = str(row.get("QuoteID") or "")

        if not code or not name:
            return None

        if classify == "HK" or jys.upper() == "HK" or quote_id.startswith("116."):
            if type_us not in {"3", ""}:
                return None
            return {
                "symbol": f"{code.zfill(5)}.HK",
                "market": "HK",
                "shortName": name,
                "longName": name,
                "exchange": "HKEX",
                "quoteType": "EQUITY",
                "score": 100,
                "provider": "eastmoney_suggest",
                "matchedTerm": matched_term,
            }

        if classify == "AStock" or "A" in security_type:
            exchange = self._a_exchange_from_eastmoney(code, security_type, quote_id)
            prefix = {"SH": "sh", "SZ": "sz", "BJ": "bj"}.get(exchange, "")
            return {
                "symbol": f"{prefix}.{code}" if prefix else code,
                "market": "A_SHARE",
                "shortName": name,
                "longName": name,
                "exchange": exchange,
                "quoteType": "EQUITY",
                "score": 100,
                "provider": "eastmoney_suggest",
                "matchedTerm": matched_term,
            }

        if classify.upper().startswith("US") or jys.upper() in {"NASDAQ", "NYSE", "AMEX"}:
            return {
                "symbol": code.upper(),
                "market": "US",
                "shortName": name,
                "longName": name,
                "exchange": jys.upper(),
                "quoteType": "EQUITY",
                "score": 100,
                "provider": "eastmoney_suggest",
                "matchedTerm": matched_term,
            }

        return None

    @staticmethod
    def _a_exchange_from_eastmoney(code: str, security_type: str, quote_id: str) -> str:
        if "沪" in security_type or quote_id.startswith("1."):
            return "SH"
        if "深" in security_type or quote_id.startswith("0."):
            return "SZ"
        if "北" in security_type or quote_id.startswith("0.") and code.startswith(("4", "8", "9")):
            return "BJ"
        if code.startswith(("5", "6", "9")):
            return "SH"
        if code.startswith(("0", "2", "3")):
            return "SZ"
        if code.startswith(("4", "8")):
            return "BJ"
        return ""

    def _query_terms(self, query: str) -> list[str]:
        raw = (query or "").strip()
        terms: list[str] = []

        def add(value: str) -> None:
            value = value.strip()
            if len(value) < 2:
                return
            if self._is_generic_query_term(value):
                return
            if value.lower() not in {item.lower() for item in terms}:
                terms.append(value)

        add(raw)
        for token in re.split(r"[\s,，。；;：:（）()【】\[\]\"']+", raw):
            add(token)
        for token in re.findall(r"[A-Za-z][A-Za-z0-9.-]{1,12}|\d{3,6}", raw):
            add(token)

        chinese_runs = re.findall(r"[\u4e00-\u9fff]{2,}", raw)
        for run in chinese_runs:
            stripped = run
            for term in self.GENERIC_QUERY_TERMS:
                stripped = stripped.replace(term, "")
            add(stripped)

        for run in chinese_runs:
            if self._is_generic_query_term(run):
                continue
            for start in range(len(run)):
                for length in (4, 3, 2, 5, 6, 7, 8):
                    if start + length <= len(run):
                        add(run[start:start + length])
                    if len(terms) >= 24:
                        return terms
        return terms

    def _is_generic_query_term(self, value: str) -> bool:
        compact = self._compact_text(value)
        if compact in self.GENERIC_QUERY_TERMS:
            return True
        stripped = compact
        for term in self.GENERIC_QUERY_TERMS:
            stripped = stripped.replace(self._compact_text(term), "")
        if not stripped and len(compact) >= 2:
            return True
        return len(stripped) < 2 and stripped != compact

    @staticmethod
    def _compact_text(value: str) -> str:
        return re.sub(r"[\s\-_·　]+", "", str(value or "")).lower()

    def _match_score(self, query_compact: str, code_query: str, code: str, *names: str) -> int:
        code_compact = str(code or "").lstrip("0").lower()
        if code_query and (code_query == code_compact or code_query == str(code).lower()):
            return 100

        best = 0
        for name in names:
            name_compact = self._compact_text(name)
            if not name_compact or name_compact == "nan":
                continue
            if query_compact == name_compact:
                best = max(best, 95)
            elif len(name_compact) >= 2 and name_compact in query_compact:
                best = max(best, 85)
            elif len(query_compact) >= 2 and query_compact in name_compact:
                best = max(best, 75)
        return best

    def _records_from_frame(self, df, max_rows: int) -> list[dict]:
        if df is None or getattr(df, "empty", True):
            return []
        normalized = df.reset_index()
        return [
            {str(k): self._json_value(v) for k, v in row.items()}
            for row in normalized.head(max_rows).to_dict(orient="records")
        ]

    @staticmethod
    def _rounded(value, digits: int = 2):
        if pd.isna(value):
            return None
        return round(float(value), digits)

    @staticmethod
    def _json_value(value):
        if pd.isna(value):
            return None
        if isinstance(value, (datetime, date_type)):
            return value.strftime("%Y-%m-%d")
        if hasattr(value, "item"):
            value = value.item()
        if isinstance(value, float):
            return round(value, 6)
        return value
