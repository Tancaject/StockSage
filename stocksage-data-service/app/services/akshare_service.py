"""
面向免费 A 股和港股市场数据的 AKShare 服务。

AKShare 封装东方财富等公开数据页面，适合作为 A 股/港股日线、技术指标和港股结构化财报的免费来源；
但它只能按尽力而为处理，不应视为交易所授权的实时行情源。
"""

from app.research_budget import check_budget, bounded_timeout, budget_call

from datetime import datetime, timedelta
from datetime import date as date_type
from functools import lru_cache
from contextvars import copy_context
import concurrent.futures
import importlib
import re
from math import isfinite

import httpx
import pandas as pd

from app.services.technical_indicators import kline_technical_indicators


# akshare 目录函数（stock_hk_spot / stock_info_*_name_code 等）内部用 requests 拉大表，
# 且不带任何超时；上游网络异常时会无限期挂起，远超 Java 客户端 10s 预算。
# 这里限制调用方等待时长；底层 SDK 线程无法强制取消，不能保证其退出时间。
_CATALOG_TIMEOUT_SECONDS = 12.0
_HK_INDICATOR_TIMEOUT_SECONDS = 8.0
_catalog_executor = concurrent.futures.ThreadPoolExecutor(
    max_workers=4, thread_name_prefix="akshare-catalog")


def _call_with_timeout(fn, timeout, label):
    """Bound caller waiting; running SDK threads cannot be forcibly stopped."""
    timeout = bounded_timeout(timeout)
    future = _catalog_executor.submit(copy_context().run, budget_call, fn)
    try:
        result = future.result(timeout=timeout)
        check_budget()
        return result
    except concurrent.futures.TimeoutError as exc:
        future.cancel()
        check_budget(exc)
        raise TimeoutError(f"{label} timed out after {timeout}s (akshare upstream unreachable)") from exc


class AkshareService:

    EASTMONEY_SEARCH_URL = "https://searchapi.eastmoney.com/api/suggest/get"
    GENERIC_QUERY_TERMS = {
        "是否", "值得", "长期", "投资", "分析", "一下", "为什么", "为何", "原因",
        "股价", "暴涨", "大涨", "下跌", "今天", "这两天", "最近", "近期", "最新",
        "公司", "股票", "港股", "美股", "a股", "财报", "年报", "季报",
    }

    # 东方财富中文列名 → K 线契约字段；字典顺序即输出列顺序。
    _HK_KLINE_COLUMNS = {
        "日期": "date", "开盘": "open", "最高": "high", "最低": "low", "收盘": "close",
        "成交量": "volume", "成交额": "amount", "涨跌幅": "pctChg", "涨跌额": "change",
        "换手率": "turnoverRate",
    }
    _A_SHARE_KLINE_COLUMNS = {
        "日期": "date", "股票代码": "code", "开盘": "open", "最高": "high", "最低": "low",
        "收盘": "close", "成交量": "volume", "成交额": "amount", "振幅": "amplitude",
        "涨跌幅": "pctChg", "涨跌额": "change", "换手率": "turnoverRate",
    }

    def get_hk_kline(self, symbol: str, period: str, days: int) -> dict:
        """通过 AKShare 东方财富端点获取港股日线、周线或月线。"""
        code = self._hk_code(symbol)
        records = self._fetch_kline(self._ak().stock_hk_hist, code, period, days, self._HK_KLINE_COLUMNS)
        if records is None:
            return {"symbol": code, "count": 0, "data": [], "message": "No HK data found from AKShare"}
        return {
            "symbol": code,
            "period": period,
            "count": len(records),
            "data": records,
            "provider": "akshare",
        }

    def get_a_share_kline(self, symbol: str, period: str, days: int) -> dict:
        """Fetch A-share OHLCV bars through AKShare's Eastmoney-backed endpoint."""
        code = self._a_share_code(symbol)
        records = self._fetch_kline(self._ak().stock_zh_a_hist, code, period, days, self._A_SHARE_KLINE_COLUMNS)
        if records is None:
            return {
                "code": symbol,
                "symbol": code,
                "period": period,
                "count": 0,
                "data": [],
                "provider": "akshare",
                "message": "No A-share data found from AKShare",
            }

        records = [{str(k): self._json_value(v) for k, v in row.items()} for row in records]
        return {
            "code": symbol,
            "symbol": code,
            "period": period,
            "count": len(records),
            "data": records,
            "provider": "akshare",
        }

    def get_hk_stock_info(self, symbol: str) -> dict:
        """港股名称取自轻量东方财富 suggest API，估值与盈利指标取自东方财富港股 F10 主要指标。

        这里避免拉取完整 AKShare 港股表：stock_hk_spot_em 和 stock_hk_spot
        都可能阻塞过久，在对话预取期间触发 Java 工具超时。
        """
        code = self._hk_code(symbol)
        row = self._hk_suggest_row(code)
        indicators, indicator_error = {}, None
        try:
            indicators = self._hk_main_indicators(code)
        except Exception as e:
            check_budget(e)
            indicator_error = str(e)

        payload = {
            "symbol": code,
            "name": row.get("shortName") or row.get("longName"),
            "shortName": row.get("shortName"),
            "longName": row.get("longName"),
            **indicators,
            "currency": "HKD",
            "exchange": "HKEX",
            "provider": "eastmoney_f10" if indicators else (row.get("provider") or "eastmoney_suggest"),
        }
        if indicators:
            payload["notes"] = ("pe/pb are TTM; marketCap/hkMarketCap in HKD; roe/roa/netMargin (%) are as reported "
                                "for the latest reporting period and may be interim, not annualized.")
        elif indicator_error:
            payload["message"] = f"HK valuation indicators unavailable: {indicator_error}"
        else:
            payload["message"] = "No HK valuation indicators found from Eastmoney F10"
        return payload

    # 东方财富港股 F10 主要指标列 → /financial 字段；缺失或非有限值保持 None。
    _HK_MAIN_INDICATOR_COLUMNS = {
        "市盈率": "pe", "市净率": "pb", "总市值(港元)": "marketCap", "港股市值(港元)": "hkMarketCap",
        "股东权益回报率(%)": "roe", "总资产回报率(%)": "roa", "销售净利率(%)": "netMargin",
        "股息率TTM(%)": "dividendYieldTtm",
    }

    def _hk_main_indicators(self, code: str) -> dict:
        # 该 AKShare 函数内部 requests 不带超时，需限制等待时长。
        df = _call_with_timeout(
            lambda: self._ak().stock_hk_financial_indicator_em(symbol=code),
            _HK_INDICATOR_TIMEOUT_SECONDS, "HK main indicators")
        if df is None or df.empty:
            return {}
        first = df.iloc[0]
        values = {}
        for column, field in self._HK_MAIN_INDICATOR_COLUMNS.items():
            value = self._json_value(first.get(column)) if column in first.index else None
            values[field] = (value if isinstance(value, (int, float)) and not isinstance(value, bool) and isfinite(value)
                            else None)
        return values if any(value is not None for value in values.values()) else {}

    def search_hk_symbols(self, query: str, max_results: int = 10) -> dict:
        """从 AKShare 港股目录按代码或名称搜索港股符号。"""
        return self._search_catalog(
            query, max_results, self._hk_search_catalog, "akshare_stock_hk_spot",
            lambda q: q.replace(".hk", "").replace("hk:", "").replace("hk", "").lstrip("0"),
        )

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
                    timeout=bounded_timeout(4.0),
                )
                check_budget()
                resp.raise_for_status()
                payload = resp.json()
            except Exception as e:
                check_budget(e)
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
        return self._search_catalog(
            query, max_results, self._a_share_catalog, "akshare_a_share_code_table",
            lambda q: re.sub(r"\D", "", q),
        )

    def _search_catalog(self, query: str, max_results: int, load_catalog, provider: str, code_query_of) -> dict:
        """按代码或名称给目录条目打分，同分保持目录顺序。"""
        q = (query or "").strip().lower()
        if not q:
            return {"query": query, "count": 0, "results": []}

        try:
            entries = load_catalog()
        except Exception as e:
            check_budget(e)
            return {"query": query, "count": 0, "results": [], "error": str(e)}

        results: list[dict] = []
        query_compact = self._compact_text(query)
        code_query = code_query_of(q)
        for code, compact_names, item in entries:
            score = self._match_score(query_compact, code_query, code, compact_names)
            if score > 0:
                results.append({**item, "score": score, "provider": provider})
        results.sort(key=lambda item: item.get("score", 0), reverse=True)
        return {"query": query, "count": len(results[:max_results]), "results": results[:max_results]}

    def has_specific_stock_terms(self, query: str) -> bool:
        """判断自然语言查询中是否包含可搜索的股票术语。"""
        return bool(self._query_terms(query))

    def get_hk_financial_reports(self, symbol: str, period: str = "annual", years: int = 5) -> dict:
        """获取港股财务表，按实际报告日期保留选中期间的全部项目。"""
        ak = self._ak()
        code = self._hk_code(symbol)
        indicator = "年度" if (period or "annual").lower() in {"annual", "year", "yearly", "年报", "年度"} else "报告期"
        # “报告期”包含上游披露的不同期间，不代表独立季度或单季数值。
        max_periods = max(1, min(int(years or 5) * (1 if indicator == "年度" else 4), 40))

        report_types = {
            "balanceSheet": "资产负债表",
            "incomeStatement": "利润表",
            "cashFlow": "现金流量表",
        }
        statements = {}
        errors = {}
        for key, report_type in report_types.items():
            try:
                df = budget_call(ak.stock_financial_hk_report_em, stock=code, symbol=report_type, indicator=indicator)
                statements[key] = self._records_from_frame(df, max_periods)
            except Exception as e:
                check_budget(e)
                statements[key] = []
                errors[key] = str(e)

        try:
            indicators_df = budget_call(ak.stock_financial_hk_analysis_indicator_em, symbol=code, indicator=indicator)
            indicators = self._records_from_frame(indicators_df, max_periods)
        except Exception as e:
            check_budget(e)
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

        result = kline_technical_indicators(kline["data"], indicators)
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

        result = kline_technical_indicators(kline["data"], indicators)
        return {"code": symbol, "symbol": code, "indicators": result, "provider": "akshare"}

    def _ak(self):
        try:
            return importlib.import_module("akshare")
        except ImportError as e:
            raise RuntimeError("AKShare is not installed. Run: pip install -r requirements.txt") from e

    @staticmethod
    def _fetch_kline(fetch, code: str, period: str, days: int, columns: dict[str, str]) -> list[dict] | None:
        """取前复权 K 线并改用契约字段名，保留最近 days 行；无数据时返回 None。"""
        request_days = max(1, int(days or 30))
        end = datetime.now()
        start = end - timedelta(days=request_days)
        df = budget_call(fetch,
            symbol=code,
            period=period if period in {"daily", "weekly", "monthly"} else "daily",
            start_date=start.strftime("%Y%m%d"),
            end_date=end.strftime("%Y%m%d"),
            adjust="qfq",
        )
        if df is None or df.empty:
            return None
        df = df.rename(columns=columns)
        selected = [col for col in columns.values() if col in df.columns]
        return df[selected].tail(request_days).to_dict(orient="records")

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

    def _hk_suggest_row(self, code: str) -> dict:
        result = self.search_symbols(f"{code}.HK", max_results=3)
        for item in result.get("results", []):
            if item.get("market") == "HK" and self._hk_code(item.get("symbol", "")) == code:
                return item
        return {}

    @lru_cache(maxsize=1)
    def _hk_search_catalog(self):
        return _call_with_timeout(
            self._load_hk_search_catalog, _CATALOG_TIMEOUT_SECONDS, "HK stock catalog")

    def _load_hk_search_catalog(self):
        df = budget_call(self._ak().stock_hk_spot)
        if "中文名称" not in df.columns:
            df = df.rename(columns={"名称": "中文名称"})
        # 目录随 lru_cache 常驻，逐行字符串与紧凑名称只在加载时算一次。
        entries = []
        for row in df.to_dict(orient="records"):
            code = str(row.get("代码", "")).zfill(5)
            chinese_name = str(row.get("中文名称", row.get("名称", "")))
            english_name = str(row.get("英文名称", ""))
            entries.append((code, self._compact_names(chinese_name, english_name), {
                "symbol": f"{code}.HK",
                "shortName": chinese_name,
                "longName": chinese_name,
                "englishName": english_name,
                "exchange": "HKEX",
                "quoteType": "EQUITY",
            }))
        return entries

    @lru_cache(maxsize=1)
    def _a_share_catalog(self):
        return _call_with_timeout(
            self._load_a_share_catalog, _CATALOG_TIMEOUT_SECONDS, "A-share stock catalog")

    def _load_a_share_catalog(self):
        ak = self._ak()
        frames = []

        sh = budget_call(ak.stock_info_sh_name_code).rename(columns={
            "证券代码": "code",
            "证券简称": "shortName",
            "证券全称": "longName",
            "公司简称": "companyShortName",
            "公司全称": "companyName",
            "上市日期": "listDate",
        })
        sh["exchange"] = "SH"
        frames.append(sh)

        sz = budget_call(ak.stock_info_sz_name_code).rename(columns={
            "A股代码": "code",
            "A股简称": "shortName",
            "A股上市日期": "listDate",
            "所属行业": "industry",
        })
        sz["exchange"] = "SZ"
        frames.append(sz)

        bj = budget_call(ak.stock_info_bj_name_code).rename(columns={
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
        entries = []
        for row in catalog[columns].to_dict(orient="records"):
            code = str(row.get("code", ""))
            short_name = str(row.get("shortName", ""))
            long_name = str(row.get("longName", ""))
            company_name = str(row.get("companyName", ""))
            exchange = row.get("exchange", "")
            prefix = {"SH": "sh", "SZ": "sz", "BJ": "bj"}.get(exchange, "")
            entries.append((code, self._compact_names(short_name, long_name, company_name), {
                "symbol": f"{prefix}.{code}" if prefix else code,
                "shortName": short_name,
                "longName": long_name or company_name,
                "companyName": company_name,
                "exchange": exchange,
                "quoteType": "EQUITY",
            }))
        return entries

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

    def _compact_names(self, *names: str) -> tuple[str, ...]:
        return tuple(self._compact_text(name) for name in names)

    @staticmethod
    def _match_score(query_compact: str, code_query: str, code: str, compact_names: tuple[str, ...]) -> int:
        code_compact = str(code or "").lstrip("0").lower()
        if code_query and (code_query == code_compact or code_query == str(code).lower()):
            return 100

        best = 0
        for name_compact in compact_names:
            if not name_compact or name_compact == "nan":
                continue
            if query_compact == name_compact:
                best = max(best, 95)
            elif len(name_compact) >= 2 and name_compact in query_compact:
                best = max(best, 85)
            elif len(query_compact) >= 2 and query_compact in name_compact:
                best = max(best, 75)
        return best

    def _records_from_frame(self, df, max_periods: int) -> list[dict]:
        if df is None or getattr(df, "empty", True):
            return []
        if "REPORT_DATE" not in df.columns:
            raise ValueError("HK financial table is missing REPORT_DATE; cannot select reporting periods")
        # 先验收整张表的期间，不能把窗口外的坏日期静默丢掉或按项目行截断。
        dates = [self._financial_report_date(value) for value in df["REPORT_DATE"]]
        selected = set(sorted(set(dates), reverse=True)[:max_periods])
        positions = sorted((index for index, value in enumerate(dates) if value in selected),
                           key=lambda index: dates[index], reverse=True)
        return [
            # 财务数值不再经过行情 helper 的六位舍入；上游已经是 float 的精度无法补回。
            {str(k): v if isinstance(v, float) and not pd.isna(v) else self._json_value(v)
             for k, v in row.items()}
            for row in df.iloc[positions].to_dict(orient="records")
        ]

    @staticmethod
    def _financial_report_date(value) -> date_type:
        if pd.isna(value):
            raise ValueError("HK financial table contains an empty REPORT_DATE; cannot select reporting periods")
        if isinstance(value, datetime):
            return value.date()
        if isinstance(value, date_type):
            return value
        if isinstance(value, str) and re.fullmatch(r"\d{4}-\d{2}-\d{2}(?:[ T].+)?", value):
            try:
                return datetime.fromisoformat(value).date()
            except ValueError:
                pass
        raise ValueError("HK financial table contains an invalid REPORT_DATE; expected an ISO date or datetime")

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
