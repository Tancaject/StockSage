"""K 线 HTTP 契约；供应商内部结构仍由各自服务和指标计算消费。"""

from datetime import date as Date, datetime, timezone
from math import isfinite
from numbers import Number
from typing import Any, Literal

from pydantic import AwareDatetime, BaseModel, ConfigDict, Field, ValidationError, model_validator

from app.services.market_resolver import A_SHARE, HK, StockRoute, route_metadata

KLinePeriod = Literal["daily", "weekly", "monthly"]


class KLinePoint(BaseModel):
    model_config = ConfigDict(extra="ignore", allow_inf_nan=False)

    date: Date | None = None
    open: float | None = None
    high: float | None = None
    low: float | None = None
    close: float | None = None
    volume: float | None = None
    amount: float | None = None
    change: float | None = None
    pctChg: float | None = None
    turnoverRate: float | None = None
    amplitude: float | None = None
    turn: float | None = None
    code: str | None = None

    @model_validator(mode="before")
    @classmethod
    def normalize_provider_fields(cls, value):
        if not isinstance(value, dict):
            return value
        row = dict(value)
        for source, target in {
            "Date": "date", "Open": "open", "High": "high", "Low": "low",
            "Close": "close", "Volume": "volume", "Amount": "amount",
            "Change": "change", "PctChange": "pctChg", "TurnoverRate": "turnoverRate",
        }.items():
            if source in row:
                row.setdefault(target, row.pop(source))
        for key, item in row.items():
            if isinstance(item, Number) and not isinstance(item, bool) and not isfinite(item):
                row[key] = None
            elif key in cls.model_fields and key not in {"date", "code"}:
                if item is None or isinstance(item, str) and item.strip() in {"", "-"}:
                    row[key] = None
                elif isinstance(item, bool):
                    raise ValueError("K-line numbers cannot be booleans")
                else:
                    number = float(item)
                    row[key] = number if isfinite(number) else None
        return row

    def has_ohlc(self) -> bool:
        return self.date is not None and all(
            value is not None for value in (self.open, self.high, self.low, self.close)
        )


class KLineResponse(BaseModel):
    model_config = ConfigDict(extra="ignore", allow_inf_nan=False)

    schemaVersion: Literal[1] = 1
    status: Literal["SUCCESS", "EMPTY", "UNSUPPORTED", "ERROR"]
    input: str
    market: str
    resolvedCode: str
    source: str
    routeReason: str
    provider: str | None
    period: KLinePeriod
    count: int = Field(ge=0)
    code: str | None = None
    symbol: str | None = None
    error: bool
    message: str | None = None
    fetchedAt: AwareDatetime
    asOf: Date | None
    timeKind: Literal["DATE"] = "DATE"
    adjustment: Literal["FORWARD_ADJUSTED"] | None
    currency: Literal["CNY", "HKD"] | None
    volumeUnit: Literal["LOT", "SHARE", "UNKNOWN"]
    retryable: bool | None = None
    errorCode: str | None = None
    data: list[KLinePoint]
    primaryProvider: str | None = None
    fallbackProvider: str | None = None
    primaryProviderError: str | None = None
    feature: str | None = None
    baostockStatus: dict[str, Any] | None = None

    @model_validator(mode="after")
    def validate_consistency(self):
        if self.error != (self.status in {"ERROR", "UNSUPPORTED"}):
            raise ValueError("K-line error must agree with status")
        if self.count != len(self.data):
            raise ValueError("K-line count must equal the returned row count")
        if self.status == "SUCCESS" and (not self.data or not all(point.has_ohlc() for point in self.data)):
            raise ValueError("Successful K-line data requires dated OHLC values in every row")
        expected_as_of = max((point.date for point in self.data), default=None) if self.status == "SUCCESS" else None
        if self.asOf != expected_as_of:
            raise ValueError("K-line asOf must be the latest successful bar date")
        if self.status in {"EMPTY", "UNSUPPORTED"} and self.data:
            raise ValueError("Empty or unsupported K-line responses cannot contain rows")
        return self


def normalize_kline(payload: dict, route: StockRoute, period: KLinePeriod,
                    provider: str | None = None, *, unsupported: bool = False) -> dict:
    """只在 HTTP 边界归一化字段；日期不被伪装成带时区的行情时刻。"""
    result = {**payload, **route_metadata(route)}
    provider = payload.get("provider") or provider
    points = []
    status = "UNSUPPORTED" if unsupported else "ERROR" if payload.get("error") else "EMPTY"
    error_code = ("UNSUPPORTED_MARKET" if route.is_supported else "UNRESOLVED_SYMBOL") if unsupported else None
    message = payload.get("message")
    retryable = False if unsupported else payload.get("retryable")
    if status == "ERROR":
        error_code = "UPSTREAM_ERROR"
    elif not unsupported:
        try:
            rows = payload.get("data", [])
            if not isinstance(rows, list):
                raise ValueError("K-line data must be a list")
            points = [KLinePoint.model_validate(row) for row in rows]
            status = "SUCCESS" if points else "EMPTY"
            if not all(point.has_ohlc() for point in points):
                status, error_code, retryable = "ERROR", "INVALID_PROVIDER_DATA", False
                message = "K-line provider returned incomplete dated OHLC rows; retry later or use another data source."
        except (ValidationError, ValueError, TypeError):
            points = []
            status, error_code, retryable = "ERROR", "INVALID_PROVIDER_DATA", False
            message = "K-line provider returned invalid dated OHLC data; retry later or use another data source."

    # 单位来自具体 AKShare 端点文档，不从市场标签推断其他供应商的单位。
    # https://akshare.akfamily.xyz/data/stock/stock.html (stock_zh_a_hist / stock_hk_hist)
    currency, volume_unit = {
        (A_SHARE, "akshare"): ("CNY", "LOT"),
        (HK, "akshare"): ("HKD", "SHARE"),
    }.get((route.market, provider), (None, "UNKNOWN"))
    result.update(
        schemaVersion=1, status=status, provider=provider, period=period,
        code=payload.get("code"), symbol=payload.get("symbol"),
        count=len(points), data=points, error=status in {"ERROR", "UNSUPPORTED"},
        message=message, fetchedAt=datetime.now(timezone.utc),
        asOf=max((point.date for point in points), default=None) if status == "SUCCESS" else None,
        timeKind="DATE", adjustment="FORWARD_ADJUSTED" if route.market in {A_SHARE, HK} else None,
        currency=currency, volumeUnit=volume_unit, retryable=retryable, errorCode=error_code,
    )
    return KLineResponse.model_validate(result).model_dump(mode="json", exclude_unset=True)
