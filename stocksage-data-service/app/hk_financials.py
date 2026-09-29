"""港股财务长表契约；报告期、项目行数和可用期间分别表达。"""

from datetime import date as Date, datetime, timezone
from decimal import Decimal
import math
import re
from typing import Generic, Literal, TypeVar

from pydantic import (
    AwareDatetime, BaseModel, ConfigDict, Field, StrictBool, StrictInt, StrictStr,
    ValidationError, field_serializer, field_validator, model_validator,
)

from app.sec_financials import FinancialReportPeriod
from app.services.market_resolver import StockRoute, route_metadata

Number = Decimal | None
Status = Literal["SUCCESS", "PARTIAL", "EMPTY", "ERROR"]
_DATES = {"REPORT_DATE", "START_DATE", "STD_REPORT_DATE"}
_STRING_METADATA = {"SECURITY_NAME_ABBR", "ORG_CODE", "DATE_TYPE_CODE", "FISCAL_YEAR", "CURRENCY"}


class FinancialRow(BaseModel):
    model_config = ConfigDict(extra="ignore", allow_inf_nan=False)

    SECUCODE: StrictStr = Field(pattern=r"^[0-9]{5}\.HK$")
    SECURITY_CODE: StrictStr = Field(pattern=r"^[0-9]{5}$")
    SECURITY_NAME_ABBR: StrictStr | None = None
    ORG_CODE: StrictStr | None = None
    REPORT_DATE: Date
    DATE_TYPE_CODE: StrictStr | None = None
    FISCAL_YEAR: StrictStr | None = None
    START_DATE: Date | None = None
    STD_REPORT_DATE: Date | None = None

    @field_validator("REPORT_DATE", "START_DATE", "STD_REPORT_DATE", mode="before")
    @classmethod
    def require_business_date(cls, value):
        if value is None or type(value) is Date:
            return value
        if isinstance(value, str):
            parsed = Date.fromisoformat(value)
            if parsed.isoformat() == value:
                return parsed
        raise ValueError("HK financial dates must be YYYY-MM-DD business dates")

    @field_validator("*", mode="before")
    @classmethod
    def require_decimal_string(cls, value, info):
        if cls.model_fields[info.field_name].annotation != Number or value is None or isinstance(value, Decimal):
            return value
        if not isinstance(value, str) or re.fullmatch(r"-?(0|[1-9][0-9]*)(\.[0-9]+)?", value) is None:
            raise ValueError("HK financial numeric fields require finite plain decimal strings")
        return Decimal(value)

    @field_serializer("*", when_used="json")
    def serialize_field(self, value):
        return format(value, "f") if isinstance(value, Decimal) else value

    def has_values(self) -> bool:
        return any(getattr(self, name) is not None for name, field in type(self).model_fields.items()
                   if field.annotation == Number)

    @model_validator(mode="after")
    def validate_identity_and_period(self):
        if self.SECUCODE != self.SECURITY_CODE + ".HK":
            raise ValueError("HK financial row security identifiers disagree")
        if self.START_DATE is not None and self.START_DATE > self.REPORT_DATE:
            raise ValueError("HK financial start date cannot follow its reporting date")
        return self


class StatementRow(FinancialRow):
    STD_ITEM_CODE: StrictStr = Field(min_length=1)
    STD_ITEM_NAME: StrictStr = Field(min_length=1)
    AMOUNT: Number = None
    amountUnit: Literal["UNKNOWN"] = "UNKNOWN"

    @field_validator("STD_ITEM_CODE", "STD_ITEM_NAME")
    @classmethod
    def require_item_identity(cls, value):
        if not value.strip():
            raise ValueError("HK financial item identifiers cannot be blank")
        return value


class IndicatorRow(FinancialRow):
    CURRENCY: StrictStr | None = None
    IS_CNY_CODE: StrictInt | None = Field(default=None, ge=-(2**63), le=2**63 - 1)
    PER_NETCASH_OPERATE: Number = None
    PER_OI: Number = None
    BPS: Number = None
    BASIC_EPS: Number = None
    DILUTED_EPS: Number = None
    OPERATE_INCOME: Number = None
    OPERATE_INCOME_YOY: Number = None
    GROSS_PROFIT: Number = None
    GROSS_PROFIT_YOY: Number = None
    HOLDER_PROFIT: Number = None
    HOLDER_PROFIT_YOY: Number = None
    GROSS_PROFIT_RATIO: Number = None
    EPS_TTM: Number = None
    OPERATE_INCOME_QOQ: Number = None
    NET_PROFIT_RATIO: Number = None
    ROE_AVG: Number = None
    GROSS_PROFIT_QOQ: Number = None
    ROA: Number = None
    HOLDER_PROFIT_QOQ: Number = None
    ROE_YEARLY: Number = None
    ROIC_YEARLY: Number = None
    TAX_EBT: Number = None
    OCF_SALES: Number = None
    DEBT_ASSET_RATIO: Number = None
    CURRENT_RATIO: Number = None
    CURRENTDEBT_DEBT: Number = None


Row = TypeVar("Row", bound=FinancialRow)


class FinancialTable(BaseModel, Generic[Row]):
    model_config = ConfigDict(extra="ignore")

    status: Literal["SUCCESS", "EMPTY", "ERROR"]
    data: list[Row]
    errorCode: StrictStr | None = None
    message: StrictStr | None = None
    retryable: StrictBool | None = None

    @model_validator(mode="after")
    def validate_status(self):
        if self.status == "SUCCESS":
            if not self.data or not any(row.has_values() for row in self.data):
                raise ValueError("Successful HK tables require at least one actual numeric value")
        elif self.data:
            raise ValueError("Failed or empty HK tables cannot contain usable rows")
        if self.status == "ERROR":
            if self.errorCode is None or not self.errorCode.strip():
                raise ValueError("Failed HK tables require an error code")
        elif self.errorCode is not None or self.retryable is not None:
            raise ValueError("Available or empty HK tables cannot contain error metadata")
        return self


class FinancialStatements(BaseModel):
    model_config = ConfigDict(extra="ignore")

    balanceSheet: FinancialTable[StatementRow]
    incomeStatement: FinancialTable[StatementRow]
    cashFlow: FinancialTable[StatementRow]

    def tables(self):
        return [self.balanceSheet, self.incomeStatement, self.cashFlow]


def aggregate_status(statuses: list[str]) -> Status:
    if all(status == "SUCCESS" for status in statuses):
        return "SUCCESS"
    if "SUCCESS" in statuses:
        return "PARTIAL"
    return "ERROR" if "ERROR" in statuses else "EMPTY"


class HkFinancialsResponse(BaseModel):
    model_config = ConfigDict(extra="ignore")

    schemaVersion: Literal[1] = 1
    status: Status
    provider: Literal["akshare"] = "akshare"
    market: Literal["HK"] = "HK"
    symbol: StrictStr = Field(pattern=r"^[0-9]{5}$")
    input: StrictStr
    resolvedCode: StrictStr = Field(pattern=r"^[0-9]{4,5}\.HK$")
    source: StrictStr
    routeReason: StrictStr
    period: Literal["annual", "report_period"]
    requestedPeriod: FinancialReportPeriod
    requestedYears: StrictInt = Field(ge=1, le=10)
    timeKind: Literal["DATE"] = "DATE"
    fetchedAt: AwareDatetime
    asOf: Date | None
    currency: None = None
    valueScale: Literal["PROVIDER_RAW"] = "PROVIDER_RAW"
    valueEncoding: Literal["DECIMAL_STRING"] = "DECIMAL_STRING"
    numericPrecision: Literal["PROVIDER_VALUE"] = "PROVIDER_VALUE"
    aggregationBasis: Literal["UNKNOWN"] = "UNKNOWN"
    statementCount: StrictInt = Field(ge=0)
    statements: FinancialStatements
    indicators: FinancialTable[IndicatorRow]
    error: StrictBool
    errorCode: StrictStr | None
    message: StrictStr | None
    retryable: StrictBool | None

    @model_validator(mode="after")
    def validate_consistency(self):
        if self.status == "ERROR":
            if self.errorCode is None or not self.errorCode.strip():
                raise ValueError("Failed HK financial responses require an error code")
        elif self.errorCode is not None or self.retryable is not None:
            raise ValueError("Available or empty HK financial responses cannot contain error metadata")
        expected_period = "annual" if self.requestedPeriod == "annual" else "report_period"
        if self.period != expected_period or self.symbol != self.resolvedCode[:-3].zfill(5):
            raise ValueError("HK financial response identity or request scope disagrees")
        tables = [*self.statements.tables(), self.indicators]
        rows = [row for table in tables for row in table.data]
        if self.error != (self.status == "ERROR") or self.statementCount != len(rows):
            raise ValueError("HK status/error/statementCount must describe the returned rows")
        if any(row.SECURITY_CODE != self.symbol for row in rows):
            raise ValueError("HK financial rows must match the response security")
        maximum = self.requestedYears * (1 if self.period == "annual" else 4)
        if any(len({row.REPORT_DATE for row in table.data}) > maximum for table in tables):
            raise ValueError("HK financial table exceeds the requested reporting periods")
        actual = max((row.REPORT_DATE for row in rows if row.has_values()), default=None)
        if self.asOf != actual:
            raise ValueError("HK asOf must come from rows with actual numeric values")
        statuses = [table.status for table in tables]
        if not (self.status == "ERROR" and all(status == "EMPTY" for status in statuses)) and self.status != aggregate_status(statuses):
            raise ValueError("HK response status must describe its tables")
        return self


def _provider_date(value):
    if value is None or value == "":
        return None
    if isinstance(value, datetime):
        return value.date()
    if type(value) is Date:
        return value
    if isinstance(value, str) and re.fullmatch(r"\d{4}-\d{2}-\d{2}(?:[ T].+)?", value):
        return datetime.fromisoformat(value).date()
    raise ValueError("Invalid provider business date")


def _provider_number(value):
    if value is None or value == "":
        return None
    if isinstance(value, bool) or not isinstance(value, (int, float, str, Decimal)):
        raise ValueError("Invalid provider numeric value")
    if isinstance(value, float) and not math.isfinite(value):
        raise ValueError("Non-finite provider numeric value")
    # 保留供应商当前值；已在上游变为 float 的数字不能声称恢复了原始十进制精度。
    number = Decimal(str(value))
    if not number.is_finite():
        raise ValueError("Non-finite provider numeric value")
    return number


def _normalize_table(raw, row_type, symbol: str, maximum: int, provider_error=None) -> dict:
    result = {"status": "EMPTY", "data": [], "errorCode": None, "message": None, "retryable": None}
    if provider_error is not None:
        return {**result, "status": "ERROR", "errorCode": "UPSTREAM_ERROR", "message": str(provider_error)}
    try:
        if not isinstance(raw, list):
            raise ValueError("Expected HK financial rows")
        rows = []
        for raw_row in raw:
            if not isinstance(raw_row, dict):
                raise ValueError("Expected an HK financial row object")
            values = dict(raw_row)
            for name, field in row_type.model_fields.items():
                value = values.get(name)
                if field.annotation == Number:
                    values[name] = _provider_number(value)
                elif name in _DATES:
                    values[name] = _provider_date(value)
                elif name in _STRING_METADATA and type(value) is int:
                    values[name] = str(value)
            row = row_type.model_validate(values)
            if row.SECURITY_CODE != symbol:
                raise ValueError("HK financial row does not match the requested security")
            rows.append(row)
        if len({row.REPORT_DATE for row in rows}) > maximum:
            raise ValueError("HK provider returned too many reporting periods")
        if any(row.has_values() for row in rows):
            result.update(status="SUCCESS", data=rows)
        return result
    except (ValidationError, ValueError, TypeError, ArithmeticError):
        return {**result, "status": "ERROR", "errorCode": "INVALID_PROVIDER_DATA", "retryable": False,
                "message": "HK financial table contains invalid values, identity or dates; check the provider data."}


def normalize_hk_financials(
    payload: dict | None, route: StockRoute, period: FinancialReportPeriod, years: int,
    *, error: Exception | None = None,
) -> dict:
    symbol = route.api_code[:-3].zfill(5)
    response = {
        **route_metadata(route), "symbol": symbol, "status": "ERROR", "error": True,
        "period": "annual" if period == "annual" else "report_period",
        "requestedPeriod": period, "requestedYears": years, "fetchedAt": datetime.now(timezone.utc),
        "asOf": None, "statementCount": 0, "statements": {
            name: {"status": "EMPTY", "data": []} for name in ("balanceSheet", "incomeStatement", "cashFlow")},
        "indicators": {"status": "EMPTY", "data": []},
        "errorCode": "UPSTREAM_ERROR", "message": None, "retryable": None,
    }
    if error is not None:
        response.update(message="HK financial report request failed; check AKShare availability before retrying.",
                        retryable=True if isinstance(error, TimeoutError) else None)
    else:
        try:
            if payload["symbol"] != symbol or payload["period"] != response["period"]:
                raise ValueError("HK provider identity or period does not match the request")
            errors = payload.get("errors", {})
            maximum = years * (1 if period == "annual" else 4)
            statements = {name: _normalize_table(payload["statements"][name], StatementRow, symbol, maximum, errors.get(name))
                          for name in response["statements"]}
            indicators = _normalize_table(payload["indicators"], IndicatorRow, symbol, maximum, errors.get("indicators"))
            tables = [*statements.values(), indicators]
            rows = [row for table in tables for row in table["data"]]
            status = aggregate_status([table["status"] for table in tables])
            candidate = {**response, "status": status, "error": status == "ERROR", "statements": statements,
                         "indicators": indicators, "statementCount": len(rows),
                         "asOf": max((row.REPORT_DATE for row in rows if row.has_values()), default=None),
                         "errorCode": "TABLES_UNAVAILABLE" if status == "ERROR" else None,
                         "message": "Some HK financial tables are unavailable; use only successful tables." if status == "PARTIAL" else payload.get("message")}
            return HkFinancialsResponse.model_validate(candidate).model_dump(mode="json")
        except (ValidationError, ValueError, TypeError, KeyError, AttributeError):
            response.update(errorCode="INVALID_PROVIDER_DATA", retryable=False,
                            message="HK financial report structure is invalid; check the data-service contract.")
    return HkFinancialsResponse.model_validate(response).model_dump(mode="json")
