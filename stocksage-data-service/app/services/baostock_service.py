"""
BaoStock 数据服务 —— A 股数据的主力来源。

BaoStock 是一个免费、开源的 A 股数据接口，提供：
- 历史 K 线数据（日/周/月线，支持前/后复权）
- 财务数据（盈利能力、运营能力、成长能力、偿债能力）
- 行业分类、指数成分股等

官方文档：http://baostock.com/baostock/index.php

注意事项：
- baostock 需要先 bs.login() 才能查询；登录由后台线程管理并自动重试
- 数据有约 1 天延迟（非实时行情），适合做分析而非盯盘
- 返回的数据需要手动遍历 ResultSet，不像 SQL 那样直接拿 DataFrame
"""

from datetime import datetime, timedelta
import socket
import zlib
from threading import Event, RLock, Thread

import baostock as bs
import baostock.common.contants as bs_cons
import baostock.common.context as bs_context
import baostock.util.socketutil as bs_socket_util
import pandas as pd

from app.services.technical_indicators import calculate_technical_indicators


BAOSTOCK_LOCK = RLock()
BAOSTOCK_LOGIN_TIMEOUT_SECONDS = 8
BAOSTOCK_RETRY_INTERVAL_SECONDS = 60
BAOSTOCK_READY_CHECK_INTERVAL_SECONDS = 300
_BAOSTOCK_LOGIN_DONE = Event()
_BAOSTOCK_RETRY_NOW = Event()
_BAOSTOCK_MANAGER_STOP = Event()
_BAOSTOCK_MANAGER_STARTED = False
_BAOSTOCK_LOGIN_STARTED = False
_BAOSTOCK_LOGIN_IN_PROGRESS = False
_BAOSTOCK_LOGGED_IN = False
_BAOSTOCK_LOGIN_ERROR = ""
_BAOSTOCK_LAST_ATTEMPT_AT: str | None = None
_BAOSTOCK_LAST_SUCCESS_AT: str | None = None
_BAOSTOCK_LOGIN_ATTEMPTS = 0
_BAOSTOCK_SOCKET_TIMEOUT_PATCHED = False

SECTOR_SAMPLES = {
    "baijiu": {
        "displayName": "Baijiu",
        "aliases": {"baijiu", "white spirits", "liquor", "白酒"},
        "members": [
            {"code": "sh.600519", "name": "Kweichow Moutai"},
            {"code": "sz.000858", "name": "Wuliangye Yibin"},
            {"code": "sz.000568", "name": "Luzhou Laojiao"},
        ],
    },
    "semiconductor": {
        "displayName": "Semiconductor",
        "aliases": {"semiconductor", "chip", "chips", "半导体", "芯片"},
        "members": [
            {"code": "sh.688981", "name": "SMIC"},
            {"code": "sh.603986", "name": "GigaDevice"},
            {"code": "sz.300782", "name": "Maxscend"},
        ],
    },
    "new_energy": {
        "displayName": "New Energy",
        "aliases": {"new energy", "新能源", "ev battery", "battery"},
        "members": [
            {"code": "sz.300750", "name": "CATL"},
            {"code": "sz.002594", "name": "BYD"},
            {"code": "sh.601012", "name": "LONGi Green Energy"},
        ],
    },
}


def start_baostock_login_manager() -> None:
    """启动后台登录循环，避免阻塞 FastAPI 启动。"""
    global _BAOSTOCK_MANAGER_STARTED
    with BAOSTOCK_LOCK:
        if _BAOSTOCK_MANAGER_STARTED:
            return
        _BAOSTOCK_MANAGER_STARTED = True
        _BAOSTOCK_MANAGER_STOP.clear()
        _BAOSTOCK_RETRY_NOW.set()
        Thread(target=_baostock_login_manager, name="baostock-login-manager", daemon=True).start()


def stop_baostock_login_manager() -> None:
    """停止重试循环，并尽力执行登出。"""
    global _BAOSTOCK_LOGGED_IN, _BAOSTOCK_MANAGER_STARTED
    _BAOSTOCK_MANAGER_STOP.set()
    _BAOSTOCK_RETRY_NOW.set()
    with BAOSTOCK_LOCK:
        was_logged_in = _BAOSTOCK_LOGGED_IN
        _BAOSTOCK_LOGGED_IN = False
        _BAOSTOCK_MANAGER_STARTED = False
    if was_logged_in:
        try:
            bs.logout()
        except Exception:
            pass


def get_baostock_status() -> dict:
    """返回当前 BaoStock 登录管理器状态，供健康检查和错误响应复用。"""
    with BAOSTOCK_LOCK:
        if _BAOSTOCK_LOGGED_IN:
            status = "ready"
        elif _BAOSTOCK_LOGIN_IN_PROGRESS:
            status = "logging_in"
        elif _BAOSTOCK_MANAGER_STARTED:
            status = "retrying"
        else:
            status = "stopped"

        return {
            "status": status,
            "loggedIn": _BAOSTOCK_LOGGED_IN,
            "loginInProgress": _BAOSTOCK_LOGIN_IN_PROGRESS,
            "attempts": _BAOSTOCK_LOGIN_ATTEMPTS,
            "lastAttemptAt": _BAOSTOCK_LAST_ATTEMPT_AT,
            "lastSuccessAt": _BAOSTOCK_LAST_SUCCESS_AT,
            "lastError": _BAOSTOCK_LOGIN_ERROR,
            "retryIntervalSeconds": BAOSTOCK_RETRY_INTERVAL_SECONDS,
        }


def _baostock_login_manager() -> None:
    """后台循环管理登录重试，避免单个请求承担长期重连职责。"""
    while not _BAOSTOCK_MANAGER_STOP.is_set():
        # 事件既可由调用方立即触发，也可按健康检查间隔自动唤醒。
        _BAOSTOCK_RETRY_NOW.wait(BAOSTOCK_READY_CHECK_INTERVAL_SECONDS)
        _BAOSTOCK_RETRY_NOW.clear()
        if _BAOSTOCK_MANAGER_STOP.is_set():
            break

        with BAOSTOCK_LOCK:
            if _BAOSTOCK_LOGGED_IN:
                continue

        _baostock_login_worker()

        with BAOSTOCK_LOCK:
            logged_in = _BAOSTOCK_LOGGED_IN
        if not logged_in:
            _BAOSTOCK_MANAGER_STOP.wait(BAOSTOCK_RETRY_INTERVAL_SECONDS)


def _baostock_login_worker() -> None:
    """执行一次 BaoStock 登录尝试，并更新全局状态快照。"""
    global _BAOSTOCK_LOGGED_IN, _BAOSTOCK_LOGIN_ERROR, _BAOSTOCK_LAST_ATTEMPT_AT
    global _BAOSTOCK_LAST_SUCCESS_AT, _BAOSTOCK_LOGIN_ATTEMPTS, _BAOSTOCK_LOGIN_IN_PROGRESS

    with BAOSTOCK_LOCK:
        if _BAOSTOCK_LOGIN_IN_PROGRESS:
            return
        _BAOSTOCK_LOGIN_IN_PROGRESS = True
        _BAOSTOCK_LOGIN_ATTEMPTS += 1
        _BAOSTOCK_LAST_ATTEMPT_AT = datetime.now().isoformat(timespec="seconds")
        _BAOSTOCK_LOGIN_DONE.clear()

    previous_timeout = socket.getdefaulttimeout()
    try:
        _patch_baostock_socket_timeout()
        socket.setdefaulttimeout(BAOSTOCK_LOGIN_TIMEOUT_SECONDS)
        result = bs.login()
        error_code = getattr(result, "error_code", "")
        error_msg = getattr(result, "error_msg", "")
        with BAOSTOCK_LOCK:
            _BAOSTOCK_LOGGED_IN = error_code == "0"
            _BAOSTOCK_LOGIN_ERROR = error_msg or error_code or "unknown baostock login result"
            if _BAOSTOCK_LOGGED_IN:
                _BAOSTOCK_LAST_SUCCESS_AT = datetime.now().isoformat(timespec="seconds")
        print(f"baostock login: {_BAOSTOCK_LOGIN_ERROR}")
    except Exception as e:
        with BAOSTOCK_LOCK:
            _BAOSTOCK_LOGGED_IN = False
            _BAOSTOCK_LOGIN_ERROR = str(e)
    finally:
        socket.setdefaulttimeout(previous_timeout)
        with BAOSTOCK_LOCK:
            _BAOSTOCK_LOGIN_IN_PROGRESS = False
        _BAOSTOCK_LOGIN_DONE.set()


def _patch_baostock_socket_timeout() -> None:
    """BaoStock 的 connect() 没有超时；这里补上超时，让登录失败后可重试。"""
    global _BAOSTOCK_SOCKET_TIMEOUT_PATCHED
    with BAOSTOCK_LOCK:
        if _BAOSTOCK_SOCKET_TIMEOUT_PATCHED:
            return

        def connect_with_timeout(self):
            """替换 BaoStock 原始连接逻辑，为 socket 建立阶段增加超时。"""
            sock = None
            try:
                sock = socket.create_connection(
                    (bs_cons.BAOSTOCK_SERVER_IP, bs_cons.BAOSTOCK_SERVER_PORT),
                    timeout=BAOSTOCK_LOGIN_TIMEOUT_SECONDS,
                )
                sock.settimeout(BAOSTOCK_LOGIN_TIMEOUT_SECONDS)
            except Exception as e:
                print(f"BaoStock server connection failed: {e}")
            setattr(bs_context, "default_socket", sock)

        def send_msg_with_timeout(msg):
            """替换 BaoStock 原始发送逻辑，为读取响应阶段增加超时。"""
            try:
                if not hasattr(bs_context, "default_socket"):
                    print("you don't login.")
                    return None

                default_socket = getattr(bs_context, "default_socket")
                if default_socket is None:
                    return None

                default_socket.settimeout(BAOSTOCK_LOGIN_TIMEOUT_SECONDS)
                default_socket.send(bytes(msg + "\n", encoding="utf-8"))
                receive = b""
                while True:
                    chunk = default_socket.recv(8192)
                    if not chunk:
                        return None
                    receive += chunk
                    if receive.endswith(b"<![CDATA[]]>\n") or receive.endswith(b"\n"):
                        break

                head_bytes = receive[0:bs_cons.MESSAGE_HEADER_LENGTH]
                head_str = bytes.decode(head_bytes)
                head_arr = head_str.split(bs_cons.MESSAGE_SPLIT)
                if len(head_arr) > 2 and head_arr[1] in bs_cons.COMPRESSED_MESSAGE_TYPE_TUPLE:
                    head_inner_length = int(head_arr[2])
                    body_str = bytes.decode(
                        zlib.decompress(
                            receive[
                                bs_cons.MESSAGE_HEADER_LENGTH:
                                bs_cons.MESSAGE_HEADER_LENGTH + head_inner_length
                            ]
                        )
                    )
                    return head_str + body_str
                return bytes.decode(receive)
            except Exception as e:
                print(e)
                print("接收数据异常，请稍后再试。")
                return None

        bs_socket_util.SocketUtil.connect = connect_with_timeout
        bs_socket_util.send_msg = send_msg_with_timeout
        _BAOSTOCK_SOCKET_TIMEOUT_PATCHED = True


def _ensure_baostock_login(timeout_seconds: int = BAOSTOCK_LOGIN_TIMEOUT_SECONDS) -> dict | None:
    """确保 BaoStock 已登录；无法及时登录时返回可并入业务响应的错误对象。"""
    global _BAOSTOCK_LOGIN_STARTED
    start_baostock_login_manager()
    with BAOSTOCK_LOCK:
        if _BAOSTOCK_LOGGED_IN:
            return None
        if not _BAOSTOCK_LOGIN_STARTED:
            _BAOSTOCK_LOGIN_STARTED = True
        if not _BAOSTOCK_LOGIN_IN_PROGRESS:
            # 当前请求等待有限时间；真正的重试生命周期交给后台管理线程继续推进。
            _BAOSTOCK_LOGIN_DONE.clear()
            _BAOSTOCK_RETRY_NOW.set()

    if not _BAOSTOCK_LOGIN_DONE.wait(timeout_seconds):
        return {
            "error": True,
            "provider": "baostock",
            "baostockStatus": get_baostock_status(),
            "message": (
                "BaoStock login is still pending or timed out. "
                "The background login manager will keep retrying; HK/US tools remain available."
            ),
        }

    with BAOSTOCK_LOCK:
        if _BAOSTOCK_LOGGED_IN:
            return None
        return {
            "error": True,
            "provider": "baostock",
            "baostockStatus": get_baostock_status(),
            "message": f"BaoStock login failed: {_BAOSTOCK_LOGIN_ERROR}",
        }


class BaostockService:
    """A 股数据访问门面，负责在每次查询前确认 BaoStock 登录可用。"""

    def get_kline(self, code: str, period: str, days: int) -> dict:
        """
        获取 K 线数据（OHLCV：开盘、最高、最低、收盘、成交量）。

        参数：
            code:   baostock 格式的股票代码，如 "sh.600519"（上交所用 sh，深交所用 sz）
            period: K 线周期，"daily" / "weekly" / "monthly"
            days:   往回取多少天的数据

        返回：
            {"code": "sh.600519", "period": "daily", "count": 30, "data": [...]}
        """
        login_error = _ensure_baostock_login()
        if login_error:
            return {"code": code, "period": period, "count": 0, "data": [], **login_error}

        # baostock 的频率参数：d=日线 w=周线 m=月线
        freq_map = {"daily": "d", "weekly": "w", "monthly": "m"}
        frequency = freq_map.get(period, "d")

        end_date = datetime.now().strftime("%Y-%m-%d")
        start_date = (datetime.now() - timedelta(days=days)).strftime("%Y-%m-%d")

        with BAOSTOCK_LOCK:
            # adjustflag="2" 表示前复权（消除分红送股对价格的影响，方便做技术分析）
            rs = bs.query_history_k_data_plus(
                code,
                "date,open,high,low,close,volume,amount,turn",
                start_date=start_date,
                end_date=end_date,
                frequency=frequency,
                adjustflag="2",
            )

            # baostock 返回的是迭代器，需要逐行读取
            rows = []
            while (rs.error_code == "0") and rs.next():
                rows.append(rs.get_row_data())
            fields = rs.fields

        df = pd.DataFrame(rows, columns=fields) if rows else pd.DataFrame()
        return {
            "code": code,
            "period": period,
            "count": len(df),
            "data": df.to_dict(orient="records"),
        }

    def get_financial_metrics(self, code: str) -> dict:
        """
        获取最新一季的财务指标。
        通过 baostock 的盈利能力数据接口获取（ROE、毛利率、净利率等）。
        """
        login_error = _ensure_baostock_login()
        if login_error:
            return {"code": code, "metrics": {}, **login_error}

        year = datetime.now().year
        quarter = (datetime.now().month - 1) // 3
        if quarter == 0:
            quarter = 4
            year -= 1

        with BAOSTOCK_LOCK:
            rs = bs.query_profit_data(code=code, year=year, quarter=quarter)
            rows = []
            while (rs.error_code == "0") and rs.next():
                rows.append(rs.get_row_data())
            fields = rs.fields

        if not rows:
            return {"code": code, "metrics": {}, "message": "No data available"}

        df = pd.DataFrame(rows, columns=fields)
        return {
            "code": code,
            "metrics": df.iloc[0].to_dict() if len(df) > 0 else {},
        }

    def get_financial_reports(self, code: str, period: str = "annual", years: int = 5) -> dict:
        """
        获取 A 股结构化财务报表摘要。

        baostock 不提供原始年报 PDF/正文，这里返回利润、营运、成长、资产负债、
        现金流和杜邦分析等结构化表。原文公告检索应结合交易所/巨潮网页搜索。
        """
        normalized_period = (period or "annual").lower()
        annual = normalized_period in {"annual", "year", "yearly", "年报", "年度"}
        max_years = max(1, min(int(years or 5), 10))
        login_error = _ensure_baostock_login()
        if login_error:
            return {
                "code": code,
                "period": "annual" if annual else "quarterly",
                "count": 0,
                "reports": [],
                **login_error,
            }
        periods = self._financial_periods(annual=annual, years=max_years)

        reports = []
        for year, quarter in periods:
            with BAOSTOCK_LOCK:
                statements = {
                    "profit": self._query_financial_table(bs.query_profit_data, code, year, quarter),
                    "operation": self._query_financial_table(bs.query_operation_data, code, year, quarter),
                    "growth": self._query_financial_table(bs.query_growth_data, code, year, quarter),
                    "balance": self._query_financial_table(bs.query_balance_data, code, year, quarter),
                    "cashFlow": self._query_financial_table(bs.query_cash_flow_data, code, year, quarter),
                    "dupont": self._query_financial_table(bs.query_dupont_data, code, year, quarter),
                }

            if any(bool(value) for value in statements.values()):
                reports.append({
                    "year": year,
                    "quarter": quarter,
                    "statements": statements,
                })

        return {
            "code": code,
            "period": "annual" if annual else "quarterly",
            "count": len(reports),
            "reports": reports,
            "message": None if reports else "No structured A-share financial data found from baostock",
        }

    def get_technical_indicators(self, code: str, indicators: list[str]) -> dict:
        """
        计算技术分析指标。

        先获取近 120 天的日线数据，然后用 pandas 计算各类指标。
        为什么取 120 天？因为 MA60 需要至少 60 个数据点，加上缓冲取 120 天。

        支持的指标：
        - MA:   移动平均线（5/10/20/60 日）—— 判断趋势方向
        - RSI:  相对强弱指标（14 日）—— 判断超买/超卖（>70 超买，<30 超卖）
        - MACD: 异同移动平均线 —— 判断趋势强度和买卖信号
        """
        # 先拿到原始 K 线数据
        kline = self.get_kline(code, "daily", 120)
        if not kline["data"]:
            return {"code": code, "indicators": {}, "message": "No kline data"}

        df = pd.DataFrame(kline["data"])
        df["close"] = pd.to_numeric(df["close"], errors="coerce")
        result = calculate_technical_indicators(df["close"], indicators)

        return {"code": code, "indicators": result}

    def _query_financial_table(self, query_func, code: str, year: int, quarter: int) -> dict:
        """调用 BaoStock 财报表函数，并把 ResultSet 转成字典列表。"""
        rs = query_func(code=code, year=year, quarter=quarter)
        rows = []
        while (rs.error_code == "0") and rs.next():
            rows.append(rs.get_row_data())
        if rs.error_code != "0":
            return {"error": rs.error_msg}
        if not rows:
            return {}
        df = pd.DataFrame(rows, columns=rs.fields)
        return df.iloc[0].to_dict() if len(df) > 0 else {}

    @staticmethod
    def _financial_periods(annual: bool, years: int) -> list[tuple[int, int]]:
        """生成需要查询的财报年份和季度序列。"""
        now = datetime.now()
        current_year = now.year
        latest_quarter = (now.month - 1) // 3
        if latest_quarter == 0:
            latest_quarter = 4
            current_year -= 1

        if annual:
            return [(current_year - offset, 4) for offset in range(years)]

        periods = []
        year = current_year
        quarter = latest_quarter
        for _ in range(years * 4):
            periods.append((year, quarter))
            quarter -= 1
            if quarter == 0:
                quarter = 4
                year -= 1
        return periods

    def get_sector_performance(self, sector: str) -> dict:
        """返回 A 股板块样本股聚合表现，保持旧字段兼容。"""
        requested = sector or ""
        mapping = _resolve_sector_mapping(requested)
        if not mapping:
            return _empty_sector_payload(
                requested,
                "No supported A-share sector mapping was found for this sector.",
            )

        login_error = _ensure_baostock_login()
        if login_error:
            payload = _empty_sector_payload(requested, login_error.get("message", "BaoStock unavailable"))
            payload.update(login_error)
            return payload

        rows = []
        for member in mapping["members"]:
            kline = self.get_kline(member["code"], "daily", 30)
            performance = _member_performance(member, kline)
            if performance:
                rows.append(performance)

        if not rows:
            return _empty_sector_payload(requested, "No recent kline data was available for sector sample stocks.")

        rows.sort(key=lambda item: item["pctChg"], reverse=True)
        average_pct = round(sum(item["pctChg"] for item in rows) / len(rows), 4)
        total_amount = round(sum(item.get("amount") or 0 for item in rows), 4)
        total_volume = round(sum(item.get("volume") or 0 for item in rows), 4)

        return {
            "sector": requested,
            "resolvedSector": mapping["displayName"],
            "source": "baostock",
            "asOf": rows[0].get("tradeDate") or datetime.now().strftime("%Y-%m-%d"),
            "summary": {
                "sampleCount": len(rows),
                "averagePctChg": average_pct,
                "totalAmount": total_amount,
                "totalVolume": total_volume,
                "leader": rows[0]["code"],
                "laggard": rows[-1]["code"],
            },
            "leaders": rows[:3],
            "laggards": sorted(rows, key=lambda item: item["pctChg"])[:3],
            "data": rows,
            "message": None,
        }

    def compare_stocks(self, codes: list[str], dimensions: list[str]) -> dict:
        """按指定维度对比多只股票。"""
        results = []
        for code in codes:
            metrics = self.get_financial_metrics(code)
            results.append({"code": code, "metrics": metrics.get("metrics", {})})
        return {"codes": codes, "dimensions": dimensions, "comparison": results}

    def get_market_overview(self) -> dict:
        """获取主要 A 股指数数据。"""
        end_date = datetime.now().strftime("%Y-%m-%d")
        login_error = _ensure_baostock_login()
        if login_error:
            return {"date": end_date, "lookbackDays": 30, "indices": {}, **login_error}

        indices = {
            "sse_composite": {"name": "SSE Composite", "code": "sh.000001"},
            "szse_component": {"name": "SZSE Component", "code": "sz.399001"},
            "chinext_index": {"name": "ChiNext Index", "code": "sz.399006"},
        }
        overview = {}
        start_date = (datetime.now() - timedelta(days=30)).strftime("%Y-%m-%d")

        for key, index in indices.items():
            code = index["code"]
            with BAOSTOCK_LOCK:
                rs = bs.query_history_k_data_plus(
                    code, "date,close,volume,amount",
                    start_date=start_date, end_date=end_date,
                    frequency="d",
                )
                rows = []
                while (rs.error_code == "0") and rs.next():
                    rows.append(rs.get_row_data())
            if rows:
                latest = rows[-1]
                previous = rows[-2] if len(rows) > 1 else None
                pct_chg = None
                if previous:
                    latest_close = float(latest[1])
                    previous_close = float(previous[1])
                    if previous_close:
                        pct_chg = round((latest_close - previous_close) / previous_close * 100, 4)

                overview[key] = {
                    "name": index["name"],
                    "code": code,
                    "tradeDate": latest[0],
                    "close": latest[1],
                    "pctChg": pct_chg,
                    "volume": latest[2],
                    "amount": latest[3],
                }

        return {"date": end_date, "lookbackDays": 30, "indices": overview}


def _resolve_sector_mapping(sector: str) -> dict | None:
    normalized = (sector or "").strip().lower()
    for mapping in SECTOR_SAMPLES.values():
        aliases = {alias.lower() for alias in mapping["aliases"]}
        if normalized in aliases:
            return mapping
    return None


def _empty_sector_payload(sector: str, message: str) -> dict:
    return {
        "sector": sector,
        "data": [],
        "summary": {
            "sampleCount": 0,
            "averagePctChg": None,
            "totalAmount": 0,
            "totalVolume": 0,
        },
        "leaders": [],
        "laggards": [],
        "source": "baostock",
        "asOf": datetime.now().strftime("%Y-%m-%d"),
        "message": message,
    }


def _member_performance(member: dict, kline: dict) -> dict | None:
    rows = kline.get("data") or []
    if len(rows) < 2:
        return None
    first = rows[0]
    latest = rows[-1]
    first_close = _float_or_none(first.get("close"))
    latest_close = _float_or_none(latest.get("close"))
    if not first_close or latest_close is None:
        return None
    pct_chg = round((latest_close - first_close) / first_close * 100, 4)
    return {
        "code": member["code"],
        "name": member.get("name", member["code"]),
        "tradeDate": latest.get("date"),
        "close": latest_close,
        "pctChg": pct_chg,
        "volume": _float_or_none(latest.get("volume")) or 0,
        "amount": _float_or_none(latest.get("amount")) or 0,
    }


def _float_or_none(value):
    if value is None or value == "":
        return None
    try:
        return float(str(value).replace(",", ""))
    except (TypeError, ValueError):
        return None
