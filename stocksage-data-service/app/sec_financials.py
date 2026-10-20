"""SEC 年度/单季事实的 HTTP 契约，保留会计期间、单位和公告版本来源。"""

from datetime import date as Date, datetime, timezone
from typing import Literal

import httpx
from pydantic import (
    AwareDatetime, BaseModel, ConfigDict, Field, StrictFloat, StrictInt,
    StrictStr, ValidationError, field_validator, model_validator,
)

from app.contract_dates import business_date
from app.services.market_resolver import StockRoute, route_metadata

FinancialReportPeriod = Literal["annual", "quarterly"]

SecMetricName = Literal[
    "Revenue", "NetIncome", "ConsolidatedNetIncome", "TotalAssets", "TotalLiabilities", "StockholdersEquity",
    "OperatingIncome", "PretaxIncome", "EPS", "OperatingCashFlow", "TotalDebt",
    "SalesRevenue", "ContinuingOperationsNetIncome", "ContinuingOperationsCashFlow", "GainOnDispositions",
    "CapitalExpenditure", "LeasedEquipmentExpenditure", "Cash", "DilutedShares", "NetInterestIncome", "CreditLossProvision",
]


class SecFact(BaseModel):
    model_config = ConfigDict(extra="ignore", allow_inf_nan=False)

    value: StrictInt | StrictFloat
    start: Date | None
    end: Date
    period_type: Literal["instant", "duration"]
    filed: Date
    form: Literal["10-K", "10-K/A", "10-Q", "10-Q/A"]
    accn: StrictStr
    fp: StrictStr | None
    frame: StrictStr | None
    fiscal_year: StrictInt | None
    filing_fiscal_year: StrictInt | None
    source_url: StrictStr

    @field_validator("start", "end", "filed", mode="before")
    @classmethod
    def require_business_date(cls, value):
        return business_date(value, "SEC fact dates must be YYYY-MM-DD business dates")

    @model_validator(mode="after")
    def validate_period(self):
        if self.filed < self.end:
            raise ValueError("SEC filing date cannot precede the fact period end")
        if self.period_type == "instant":
            if self.start is not None:
                raise ValueError("Instant SEC facts cannot have a start date")
        elif self.form.startswith("10-Q"):
            if self.start is None or not 80 <= (self.end - self.start).days <= 100:
                raise ValueError("Quarterly SEC duration facts require an actual single-quarter period")
        elif self.start is None or not 335 <= (self.end - self.start).days <= 395:
            # 与现有 SEC 年度选择器一致，允许非自然年以及 52/53 周财年。
            raise ValueError("Annual SEC duration facts require an actual full-year period")
        return self


class SecMetric(BaseModel):
    model_config = ConfigDict(extra="ignore")

    concept: StrictStr = Field(pattern=r"^us-gaap:[A-Za-z][A-Za-z0-9]*$")
    unit: Literal["USD", "USD/shares", "shares"]
    data: list[SecFact] = Field(min_length=1)


class SecFinancialsResponse(BaseModel):
    model_config = ConfigDict(extra="ignore")

    schemaVersion: Literal[1] = 1
    status: Literal["SUCCESS", "EMPTY", "UNSUPPORTED", "ERROR"]
    provider: Literal["SEC_EDGAR"] = "SEC_EDGAR"
    market: Literal["US"] = "US"
    period: Literal["annual", "quarterly"] = "annual"
    timeKind: Literal["DATE"] = "DATE"
    fetchedAt: AwareDatetime
    asOf: Date | None
    ticker: StrictStr = Field(min_length=1)
    company_name: StrictStr | None
    cik: StrictStr | None = Field(pattern=r"^[0-9]{10}$")
    metric_count: int = Field(ge=0)
    metrics: dict[SecMetricName, SecMetric]
    error: bool
    errorCode: str | None
    message: str | None
    retryable: bool | None
    requestedPeriod: FinancialReportPeriod | None = None
    requestedYears: StrictInt | None = Field(default=None, ge=1, le=10)
    input: StrictStr | None = None
    resolvedCode: StrictStr | None = None
    source: StrictStr | None = None
    routeReason: StrictStr | None = None

    @model_validator(mode="after")
    def validate_consistency(self):
        if (self.requestedPeriod is None) != (self.requestedYears is None):
            raise ValueError("SEC requestedPeriod and requestedYears must be supplied together")
        if self.requestedPeriod is not None and self.status in {"SUCCESS", "EMPTY"} \
                and self.requestedPeriod != self.period:
            raise ValueError("SEC facts must match the requested annual or quarterly period")
        if self.error != (self.status in {"ERROR", "UNSUPPORTED"}) or self.metric_count != len(self.metrics):
            raise ValueError("SEC status/error/metric_count must agree with the returned metrics")
        if self.status == "SUCCESS":
            if not self.metrics or not self.company_name or self.cik is None:
                raise ValueError("Successful SEC facts require company identity and metrics")
            facts = [fact for metric in self.metrics.values() for fact in metric.data]
            if self.asOf != max(fact.end for fact in facts):
                raise ValueError("SEC asOf must be the latest actual fact period end")
            source = f"https://data.sec.gov/api/xbrl/companyfacts/CIK{self.cik}.json"
            if any(fact.source_url != source for fact in facts):
                raise ValueError("SEC fact source must identify this company's companyfacts endpoint")
            if any(fact.form.startswith("10-Q") != (self.period == "quarterly") for fact in facts):
                raise ValueError("SEC fact forms must match the response period")
            limit = None if self.requestedYears is None else self.requestedYears * (4 if self.period == "quarterly" else 1)
            if limit is not None and any(len({fact.end for fact in metric.data}) > limit for metric in self.metrics.values()):
                raise ValueError("SEC facts exceed the requested number of periods")
        elif self.metrics or self.asOf is not None:
            raise ValueError("Non-success SEC responses cannot contain usable facts or an asOf date")
        return self


def normalize_sec_financials(
    payload: dict | None, ticker: str, *, error: Exception | None = None,
    requested_period: FinancialReportPeriod | None = None, requested_years: int | None = None,
    route: StockRoute | None = None,
) -> dict:
    """只在 HTTP 边界验收服务输出；抓取时间不能充当财务期间或披露日期。"""
    response = {
        "schemaVersion": 1, "provider": "SEC_EDGAR", "market": "US",
        "period": "quarterly" if requested_period == "quarterly" else "annual",
        "timeKind": "DATE", "fetchedAt": datetime.now(timezone.utc), "asOf": None,
        "ticker": ticker.upper(), "company_name": None, "cik": None,
        "metric_count": 0, "metrics": {}, "status": "ERROR", "error": True,
        "errorCode": "UPSTREAM_ERROR", "message": None, "retryable": None,
        "requestedPeriod": requested_period, "requestedYears": requested_years,
        **(route_metadata(route) if route is not None else {}),
    }
    if error is not None:
        response["message"] = "SEC EDGAR XBRL request failed; check the ticker and SEC availability before retrying."
        if isinstance(error, (TimeoutError, httpx.TimeoutException)):
            response["retryable"] = True
        elif isinstance(error, httpx.HTTPStatusError):
            status = error.response.status_code
            response["retryable"] = status == 429 or status >= 500
    else:
        try:
            metrics = {key: SecMetric.model_validate(value) for key, value in payload["metrics"].items()}
            candidate = {
                **response, "ticker": payload["ticker"], "company_name": payload["company_name"],
                "cik": payload["cik"], "metrics": metrics, "metric_count": len(metrics),
                "status": "SUCCESS" if metrics else "EMPTY", "error": False, "errorCode": None,
                "asOf": max((fact.end for metric in metrics.values() for fact in metric.data), default=None),
            }
            # 先验收完整供应商事实；请求窗口约束在裁剪后单独验收。
            validated = SecFinancialsResponse.model_validate({**candidate, "requestedPeriod": None, "requestedYears": None})
            if requested_years is not None and validated.status == "SUCCESS":
                selected_metrics = {}
                periods = requested_years * (4 if requested_period == "quarterly" else 1)
                for name, metric in validated.metrics.items():
                    ends = set(sorted({fact.end for fact in metric.data})[-periods:])
                    # 同一 end 的不同 start/公告事实仍有业务含义，不能任意去掉一条或多算一期。
                    facts = sorted((fact for fact in metric.data if fact.end in ends),
                                   key=lambda fact: (fact.end, fact.start or Date.min, fact.filed, fact.accn))
                    selected_metrics[name] = metric.model_copy(update={"data": facts})
                candidate.update(metrics=selected_metrics,
                                 asOf=max(fact.end for metric in selected_metrics.values() for fact in metric.data))
            return SecFinancialsResponse.model_validate(candidate).model_dump(mode="json")
        except (ValidationError, ValueError, TypeError, KeyError, AttributeError):
            response.update(errorCode="INVALID_PROVIDER_DATA", retryable=False,
                            message="SEC EDGAR XBRL returned invalid facts or source metadata; check the data-service contract.")
    return SecFinancialsResponse.model_validate(response).model_dump(mode="json")
