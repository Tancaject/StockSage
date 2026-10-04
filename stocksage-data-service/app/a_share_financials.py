"""BaoStock 六类财务表的 HTTP 契约；查询期间不替代实际报告日期。"""

from calendar import monthrange
from datetime import date as Date, datetime, timezone
from decimal import Decimal, InvalidOperation
from typing import Generic, Literal, TypeVar

from pydantic import (
    AwareDatetime, BaseModel, ConfigDict, Field, StrictBool, StrictInt,
    StrictStr, ValidationError, field_serializer, field_validator, model_validator,
)

from app.sec_financials import FinancialReportPeriod
from app.services.market_resolver import StockRoute, route_metadata

Number = Decimal | None
ReportStatus = Literal["SUCCESS", "PARTIAL", "EMPTY", "ERROR"]


class FinancialRow(BaseModel):
    model_config = ConfigDict(extra="ignore", allow_inf_nan=False)

    code: StrictStr
    pubDate: Date
    statDate: Date

    @field_validator("pubDate", "statDate", mode="before")
    @classmethod
    def require_business_date(cls, value):
        if type(value) is Date:
            return value
        if isinstance(value, str):
            parsed = Date.fromisoformat(value)
            if parsed.isoformat() == value:
                return parsed
        raise ValueError("BaoStock dates must be YYYY-MM-DD business dates")

    @field_validator("*", mode="before")
    @classmethod
    def normalize_number(cls, value, info):
        if info.field_name in {"code", "pubDate", "statDate"} or info.field_name.endswith("Unit"):
            return value
        if value is None or isinstance(value, Decimal):
            return value
        if isinstance(value, str):
            if not value.strip():
                return None
            try:
                number = Decimal(value)
                if not number.is_finite():
                    raise ValueError("BaoStock numeric values must be finite")
                return number
            except InvalidOperation as error:
                raise ValueError("Invalid BaoStock numeric value") from error
        raise ValueError("BaoStock numeric fields require decimal strings, not booleans or floating point values")

    @field_serializer("*", when_used="json")
    def serialize_field(self, value, info):
        # 金额不经 float；科学计数法展开后，Java BigDecimal 可以精确重建原值。
        if isinstance(value, Decimal):
            return format(value, "f")
        return value

    def has_values(self) -> bool:
        return any(getattr(self, name) is not None for name in type(self).model_fields
                   if name not in {"code", "pubDate", "statDate"} and not name.endswith("Unit"))

    @model_validator(mode="after")
    def validate_publication(self):
        if self.pubDate < self.statDate:
            raise ValueError("BaoStock publication date cannot precede its reporting period")
        return self


class ProfitRow(FinancialRow):
    roeAvg: Number = None
    npMargin: Number = None
    gpMargin: Number = None
    netProfit: Number = None
    epsTTM: Number = None
    MBRevenue: Number = None
    totalShare: Number = None
    liqaShare: Number = None
    netProfitUnit: Literal["YUAN"] = "YUAN"
    MBRevenueUnit: Literal["YUAN"] = "YUAN"


class OperationRow(FinancialRow):
    NRTurnRatio: Number = None
    NRTurnDays: Number = None
    INVTurnRatio: Number = None
    INVTurnDays: Number = None
    CATurnRatio: Number = None
    AssetTurnRatio: Number = None
    NRTurnDaysUnit: Literal["DAY"] = "DAY"
    INVTurnDaysUnit: Literal["DAY"] = "DAY"


class GrowthRow(FinancialRow):
    YOYEquity: Number = None
    YOYAsset: Number = None
    YOYNI: Number = None
    YOYEPSBasic: Number = None
    YOYPNI: Number = None


class BalanceRow(FinancialRow):
    currentRatio: Number = None
    quickRatio: Number = None
    cashRatio: Number = None
    YOYLiability: Number = None
    liabilityToAsset: Number = None
    assetToEquity: Number = None


class CashFlowRow(FinancialRow):
    CAToAsset: Number = None
    NCAToAsset: Number = None
    tangibleAssetToAsset: Number = None
    ebitToInterest: Number = None
    CFOToOR: Number = None
    CFOToNP: Number = None
    CFOToGr: Number = None


class DupontRow(FinancialRow):
    dupontROE: Number = None
    dupontAssetStoEquity: Number = None
    dupontAssetTurn: Number = None
    dupontPnitoni: Number = None
    dupontNitogr: Number = None
    dupontTaxBurden: Number = None
    dupontIntburden: Number = None
    dupontEbittogr: Number = None


Row = TypeVar("Row", bound=FinancialRow)


class StatementResult(BaseModel, Generic[Row]):
    model_config = ConfigDict(extra="ignore")

    status: Literal["SUCCESS", "EMPTY", "ERROR"]
    data: Row | None
    errorCode: StrictStr | None = None
    message: StrictStr | None = None
    retryable: StrictBool | None = None

    @model_validator(mode="after")
    def validate_status(self):
        if self.status == "SUCCESS":
            if self.data is None or not self.data.has_values():
                raise ValueError("Successful BaoStock statements require at least one numeric value")
        elif self.data is not None:
            raise ValueError("Unavailable BaoStock statements cannot contain usable data")
        if self.status == "ERROR":
            if not self.errorCode:
                raise ValueError("Failed BaoStock statements require an error code")
        elif self.errorCode is not None or self.retryable is not None:
            raise ValueError("Successful or empty BaoStock statements cannot contain error metadata")
        return self


class FinancialStatements(BaseModel):
    model_config = ConfigDict(extra="ignore")

    profit: StatementResult[ProfitRow]
    operation: StatementResult[OperationRow]
    growth: StatementResult[GrowthRow]
    balance: StatementResult[BalanceRow]
    cashFlow: StatementResult[CashFlowRow]
    dupont: StatementResult[DupontRow]

    def results(self):
        return [getattr(self, name) for name in type(self).model_fields]


def aggregate_status(statuses: list[str]) -> ReportStatus:
    if statuses and all(status == "SUCCESS" for status in statuses):
        return "SUCCESS"
    if any(status in {"SUCCESS", "PARTIAL"} for status in statuses):
        return "PARTIAL"
    return "ERROR" if "ERROR" in statuses else "EMPTY"


class FinancialReport(BaseModel):
    model_config = ConfigDict(extra="ignore")

    year: StrictInt = Field(ge=1, le=9999)
    quarter: StrictInt = Field(ge=1, le=4)
    status: ReportStatus
    asOf: Date | None
    statements: FinancialStatements

    @model_validator(mode="after")
    def validate_period(self):
        results = self.statements.results()
        actual_dates = [result.data.statDate for result in results if result.status == "SUCCESS"]
        month = self.quarter * 3
        period_end = Date(self.year, month, monthrange(self.year, month)[1])
        if any(actual != period_end for actual in actual_dates):
            raise ValueError("BaoStock statDate must match the queried year and quarter")
        if self.status != aggregate_status([result.status for result in results]) or self.asOf != max(actual_dates, default=None):
            raise ValueError("BaoStock report status and asOf must describe its usable statements")
        return self


class AShareFinancialsResponse(BaseModel):
    model_config = ConfigDict(extra="ignore")

    schemaVersion: Literal[1] = 1
    status: ReportStatus
    provider: Literal["baostock"] = "baostock"
    market: Literal["A_SHARE"] = "A_SHARE"
    code: StrictStr
    input: StrictStr
    resolvedCode: StrictStr
    source: StrictStr
    routeReason: StrictStr
    period: FinancialReportPeriod
    requestedPeriod: FinancialReportPeriod
    requestedYears: StrictInt = Field(ge=1, le=10)
    timeKind: Literal["DATE"] = "DATE"
    fetchedAt: AwareDatetime
    asOf: Date | None
    currency: None = None
    valueScale: Literal["PROVIDER_RAW"] = "PROVIDER_RAW"
    valueEncoding: Literal["DECIMAL_STRING"] = "DECIMAL_STRING"
    aggregationBasis: Literal["UNKNOWN"] = "UNKNOWN"
    count: StrictInt = Field(ge=0)
    reports: list[FinancialReport]
    error: StrictBool
    errorCode: StrictStr | None
    message: StrictStr | None
    retryable: StrictBool | None

    @model_validator(mode="after")
    def validate_consistency(self):
        if self.code != self.resolvedCode or self.period != self.requestedPeriod:
            raise ValueError("BaoStock response identity and request scope must agree")
        if self.count != len(self.reports) or self.error != (self.status == "ERROR"):
            raise ValueError("BaoStock status/error/count must agree with its report items")
        if len(self.reports) > self.requestedYears * (1 if self.period == "annual" else 4):
            raise ValueError("BaoStock reports exceed the requested window")
        if len({(report.year, report.quarter) for report in self.reports}) != len(self.reports):
            raise ValueError("Duplicate BaoStock report periods cannot count as additional coverage")
        if self.period == "annual" and any(report.quarter != 4 for report in self.reports):
            raise ValueError("Annual BaoStock requests require quarter 4 report items")
        rows = [result.data for report in self.reports for result in report.statements.results() if result.status == "SUCCESS"]
        if any(row.code != self.code for row in rows) or self.asOf != max((row.statDate for row in rows), default=None):
            raise ValueError("BaoStock usable statements must match response code and asOf")
        # 全局登录/请求失败没有 statement，但仍应明确是 ERROR。
        if not (self.status == "ERROR" and not self.reports) and self.status != aggregate_status([report.status for report in self.reports]):
            raise ValueError("BaoStock response status must describe its returned reports")
        return self


_ROW_TYPES = {
    "profit": ProfitRow, "operation": OperationRow, "growth": GrowthRow,
    "balance": BalanceRow, "cashFlow": CashFlowRow, "dupont": DupontRow,
}


def _normalize_statement(raw, row_type, code: str, year: int, quarter: int) -> dict:
    result = {"status": "EMPTY", "data": None, "errorCode": None, "message": None, "retryable": None}
    if raw == {}:
        return result
    if isinstance(raw, dict) and "error" in raw:
        return {**result, "status": "ERROR", "errorCode": "UPSTREAM_ERROR",
                "message": str(raw["error"])}
    try:
        row = row_type.model_validate(raw)
        month = quarter * 3
        expected_end = Date(year, month, monthrange(year, month)[1])
        if row.code != code or row.statDate != expected_end:
            raise ValueError("BaoStock row identity or reporting period does not match the request")
        if not row.has_values():
            return result
        return {**result, "status": "SUCCESS", "data": row}
    except (ValidationError, ValueError, TypeError, OverflowError):
        return {**result, "status": "ERROR", "errorCode": "INVALID_PROVIDER_DATA", "retryable": False,
                "message": "BaoStock statement contains invalid numbers, dates or identity; check the provider data."}


def normalize_a_share_financials(
    payload: dict | None, route: StockRoute, period: FinancialReportPeriod, years: int,
    *, error: Exception | None = None,
) -> dict:
    response = {
        "status": "ERROR", "code": route.api_code, **route_metadata(route),
        "period": period, "requestedPeriod": period, "requestedYears": years,
        "fetchedAt": datetime.now(timezone.utc), "asOf": None, "count": 0, "reports": [],
        "error": True, "errorCode": "UPSTREAM_ERROR", "message": None, "retryable": None,
    }
    if error is not None:
        response.update(message="BaoStock financial report request failed; check provider availability before retrying.",
                        retryable=True if isinstance(error, TimeoutError) else None)
    elif isinstance(payload, dict) and payload.get("error") is True:
        response["message"] = payload.get("message") or "BaoStock financial reports are unavailable; check the provider login status."
    else:
        try:
            if payload["code"] != route.api_code or payload["period"] != period or not isinstance(payload["reports"], list):
                raise ValueError("BaoStock response identity or period does not match the request")
            reports = []
            for raw in payload["reports"]:
                year, quarter = raw["year"], raw["quarter"]
                if type(year) is not int or not 1 <= year <= 9999 or type(quarter) is not int or not 1 <= quarter <= 4:
                    raise ValueError("Invalid BaoStock query period")
                statements = {name: _normalize_statement(raw["statements"][name], row_type, route.api_code, year, quarter)
                              for name, row_type in _ROW_TYPES.items()}
                dates = [result["data"].statDate for result in statements.values() if result["status"] == "SUCCESS"]
                reports.append({"year": year, "quarter": quarter,
                                "status": aggregate_status([result["status"] for result in statements.values()]),
                                "asOf": max(dates, default=None), "statements": statements})
            status = aggregate_status([report["status"] for report in reports])
            dates = [report["asOf"] for report in reports if report["asOf"] is not None]
            candidate = {**response, "status": status, "error": status == "ERROR", "reports": reports,
                         "count": len(reports), "asOf": max(dates, default=None),
                         "errorCode": "STATEMENTS_UNAVAILABLE" if status == "ERROR" else None,
                         "message": "Some BaoStock financial tables are unavailable; use only successful statements." if status == "PARTIAL" else payload.get("message")}
            return AShareFinancialsResponse.model_validate(candidate).model_dump(mode="json")
        except (ValidationError, ValueError, TypeError, KeyError, AttributeError):
            response.update(errorCode="INVALID_PROVIDER_DATA", retryable=False,
                            message="BaoStock financial report structure is invalid; check the data-service contract.")
    return AShareFinancialsResponse.model_validate(response).model_dump(mode="json")
