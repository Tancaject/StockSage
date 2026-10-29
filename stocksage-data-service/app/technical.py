"""从后端提供的 IBKR 日线计算指标；这里不请求行情供应商。"""

from datetime import datetime, timezone
from math import isfinite
from typing import Any

import pandas as pd
from pydantic import BaseModel, Field, StrictStr

from app.services.technical_indicators import calculate_technical_indicators


class TechnicalCalculationRequest(BaseModel):
    history: dict[str, Any]
    indicators: list[StrictStr] = Field(default_factory=lambda: ["MA", "MACD", "RSI"])


def _instant(value: Any) -> datetime:
    if not isinstance(value, str):
        raise ValueError("行情时间必须是带时区的 ISO 时间")
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.utcoffset() is None:
        raise ValueError("行情时间缺少时区")
    return parsed


def calculate_supplied_technical(request: TechnicalCalculationRequest) -> dict:
    history = request.history
    requested = list(dict.fromkeys(item.strip().upper() for item in request.indicators))
    result = {
        "schemaVersion": 1, "status": "ERROR", "provider": "IBKR_WEB_API", "method": "STOCKSAGE_DAILY_V1",
        **{key: history.get(key) for key in ("symbol", "market", "conid", "exchange", "period", "bar", "source",
                                           "requestedOutsideRth", "fetchedAt", "currency", "adjustment", "delayed")},
        "timeKind": "INSTANT", "asOf": None, "firstBarAt": None, "count": 0,
        "requestedIndicators": requested, "indicators": {}, "unavailable": {},
        "error": True, "errorCode": None, "message": None, "retryable": False,
        "definitions": {
            "MA": "MA5/10/20/60：日线收盘价简单均值，各需对应条数。",
            "RSI": "RSI14：涨跌额的14期简单滚动均值，至少15个收盘价；非 Wilder 平滑。横盘0/0时留空。",
            "MACD": "EMA12/26、DEA9，pandas ewm adjust=True；柱值=2×(DIF−DEA)。至少26个收盘价，值受历史起点影响。",
            "source": "使用源行情返回的日线；最新一条不保证已经收盘，复权状态以源行情为准。",
        },
    }

    def fail(status: str, code: str, message: str, retryable=False) -> dict:
        result.update(status=status, error=status in {"ERROR", "UNSUPPORTED"}, errorCode=code,
                      message=message, retryable=retryable)
        return result

    if not requested or any(item not in {"MA", "MACD", "RSI"} for item in requested):
        return fail("UNSUPPORTED", "INDICATOR_UNSUPPORTED", "仅支持 MA、MACD、RSI；请修改指标列表。")
    if history.get("bar") != "1d":
        return fail("UNSUPPORTED", "INDICATOR_GRANULARITY_UNSUPPORTED", "技术指标只支持日线；请将指标请求改为日线。")
    if type(history.get("schemaVersion")) is not int or history["schemaVersion"] != 1 or history.get("provider") != "IBKR_WEB_API" or history.get("timeKind") != "EPOCH_MILLIS":
        return fail("ERROR", "INVALID_SOURCE_HISTORY", "技术指标需要 IBKR v1 毫秒时间戳行情；请检查后端提交的来源。")
    status = history.get("status")
    if status in {"ERROR", "UNSUPPORTED", "EMPTY"}:
        return fail(status, history.get("errorCode") or "SOURCE_HISTORY_" + status,
                    history.get("message") or "源行情没有可计算的日线；请检查 IBKR 会话或请求窗口。", history.get("retryable"))
    if history.get("market") != "US":
        return fail("UNSUPPORTED", "INDICATOR_MARKET_UNSUPPORTED", "此计算接口仅接收美股 IBKR 日线；A股和港股请使用现有技术指标接口。")
    try:
        if status != "SUCCESS" or history.get("error") is not False:
            raise ValueError("源行情必须是成功状态")
        if any(not isinstance(history.get(key), str) or not history[key].strip() for key in ("symbol", "conid", "period")):
            raise ValueError("源行情缺少股票、合约或请求窗口")
        fetched_at, as_of = _instant(history.get("fetchedAt")), _instant(history.get("asOf"))
        rows = history.get("data")
        if not isinstance(rows, list) or not rows or type(history.get("count")) is not int or history["count"] != len(rows):
            raise ValueError("源行情条数与数据不一致或没有日线")
        times = set()
        for row in rows:
            if not isinstance(row, dict) or type(row.get("t")) is not int or row["t"] <= 0:
                raise ValueError("每条日线必须有正的毫秒时间戳")
            if row["t"] in times:
                raise ValueError("日线时间戳重复；不能任意选择其中一条")
            times.add(row["t"])
            close = row.get("c")
            if type(close) not in {int, float} or not isfinite(close) or close <= 0:
                raise ValueError("每条日线收盘价必须是有限的正数")
        rows = sorted(rows, key=lambda row: row["t"])
        latest = datetime.fromtimestamp(rows[-1]["t"] / 1000, timezone.utc)
        if as_of != latest or latest > fetched_at:
            raise ValueError("源行情 asOf 与最新日线不一致或晚于抓取时间")
        if history.get("delayed") is not None and type(history["delayed"]) is not bool:
            raise ValueError("源行情 delayed 必须是布尔值或未知")
        if history.get("requestedOutsideRth") is not None and type(history["requestedOutsideRth"]) is not bool:
            raise ValueError("源行情 requestedOutsideRth 必须是布尔值或未知")
        values = calculate_technical_indicators(pd.Series([row["c"] for row in rows], dtype=float), requested)
    except (ValueError, TypeError, OverflowError, OSError) as error:
        return fail("ERROR", "INVALID_SOURCE_HISTORY", f"无法计算技术指标：{error}。请检查源日线数据后重试。")

    count = len(rows)
    minimum = {"MA5": 5, "MA10": 10, "MA20": 20, "MA60": 60, "RSI14": 15,
               "MACD_DIF": 26, "MACD_DEA": 26, "MACD": 26}
    unavailable = {}
    for name in values:
        if count < minimum[name]:
            values[name] = None
            unavailable[name] = f"日线不足：需要 {minimum[name]} 条，实际 {count} 条。"
        elif values[name] is None:
            unavailable[name] = "当前价格序列下该指标无定义。"
    available = any(value is not None for value in values.values())
    result.update(status="PARTIAL" if available and unavailable else "SUCCESS" if available else "EMPTY",
                  error=False, count=count, asOf=history["asOf"],
                  firstBarAt=datetime.fromtimestamp(rows[0]["t"] / 1000, timezone.utc).isoformat(),
                  indicators=values, unavailable=unavailable,
                  message="部分指标没有可用值；请查看 unavailable 中的原因。" if unavailable else None)
    return result
