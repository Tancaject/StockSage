"""SEC 年度/单季事实的 HTTP 契约，保留会计期间、单位和公告版本来源。"""

from datetime import date as Date, datetime, timezone, timedelta
from decimal import Decimal
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
    "CapitalExpenditure", "LeasedEquipmentExpenditure", "CurrentAssets", "CurrentLiabilities",
    "ShortTermBorrowings", "CommercialPaper", "DebtCurrent", "LongTermDebtCurrent", "LongTermDebtNoncurrent",
    "LongTermDebtAndFinanceLeases", "Cash", "DilutedShares", "NetInterestIncome", "CreditLossProvision",
    "Inventory", "CostOfRevenue", "CommonSharesOutstanding",
]


class SecCumulativeSource(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)
    concept: StrictStr = Field(pattern=r"^us-gaap:[A-Za-z][A-Za-z0-9]*$")
    unit: Literal["USD"]
    value: StrictInt | StrictFloat
    start: Date
    end: Date
    filed: Date
    form: Literal["10-Q", "10-Q/A"]
    accn: StrictStr = Field(min_length=1)
    source_url: StrictStr

    @field_validator("start", "end", "filed", mode="before")
    @classmethod
    def require_business_date(cls, value):
        return business_date(value, "SEC cumulative source dates must be YYYY-MM-DD")

    @model_validator(mode="after")
    def validate_period(self):
        days = (self.end - self.start).days
        if self.filed < self.end or not (80 <= days <= 100 or 160 <= days <= 200 or 240 <= days <= 300):
            raise ValueError("SEC cumulative source must be a disclosed Q1, half-year or nine-month flow")
        return self


class SecDerivation(BaseModel):
    model_config = ConfigDict(extra="forbid")
    method: Literal["YTD_DIFFERENCE"]
    current: SecCumulativeSource
    previous: SecCumulativeSource


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
    derivation: SecDerivation | None = None

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
        if self.derivation is not None:
            current, previous = self.derivation.current, self.derivation.previous
            current_days, previous_days = (current.end - current.start).days, (previous.end - previous.start).days
            adjacent = (160 <= current_days <= 200 and 80 <= previous_days <= 100) \
                or (240 <= current_days <= 300 and 160 <= previous_days <= 200)
            if self.period_type != "duration" or current.start != previous.start or not adjacent \
                    or previous.filed > current.filed or self.start != previous.end + timedelta(days=1) \
                    or (self.end, self.filed, self.accn, self.form) != (current.end, current.filed, current.accn, current.form) \
                    or current.concept != previous.concept or current.unit != previous.unit \
                    or self.source_url != current.source_url or self.source_url != previous.source_url \
                    or self.frame is not None \
                    or Decimal(str(self.value)) != Decimal(str(current.value)) - Decimal(str(previous.value)):
                raise ValueError("SEC derived quarter must match its two cumulative source facts")
        return self


class SecMetric(BaseModel):
    model_config = ConfigDict(extra="ignore")

    concept: StrictStr = Field(pattern=r"^us-gaap:[A-Za-z][A-Za-z0-9]*$")
    unit: Literal["USD", "USD/shares", "shares"]
    data: list[SecFact] = Field(min_length=1)

    @model_validator(mode="after")
    def validate_derivation(self):
        for fact in self.data:
            if fact.derivation and (fact.derivation.current.concept != self.concept or fact.derivation.current.unit != self.unit):
                raise ValueError("SEC derived sources must match the metric concept and unit")
        return self


class SecBalanceSheet(BaseModel):
    """最新 10-K 资产负债表渲染页的原表行（“科目 | 本期末 | 上期末”），单位见首行表头。"""
    accession: StrictStr
    filed: StrictStr
    source_url: StrictStr
    lines: list[StrictStr]


class SecBreakdownMember(BaseModel):
    name: StrictStr = Field(min_length=1)
    value: StrictInt | StrictFloat


class SecBreakdownPeriod(BaseModel):
    model_config = ConfigDict(allow_inf_nan=False)

    start: Date
    end: Date
    total: StrictInt | StrictFloat
    members: list[SecBreakdownMember] = Field(min_length=2)

    @model_validator(mode="after")
    def validate_reconciled(self):
        # 只展示加总与合并数一致的拆分；对不上说明维度有重叠或缺项。
        if self.total == 0 or abs(sum(m.value for m in self.members) - self.total) > 0.005 * abs(self.total):
            raise ValueError("SEC breakdown members must reconcile to the consolidated total")
        return self


class SecBreakdownTable(BaseModel):
    metric: Literal["Revenue", "OperatingIncome"]
    axis: Literal["segment", "geography", "product"]
    periods: list[SecBreakdownPeriod] = Field(min_length=1)


class SecRevenueBreakdown(BaseModel):
    """最新一期申报 XBRL 实例中的单一维度拆分（营收、分部营业利润）；不可用时只带原因。"""
    accession: StrictStr = Field(pattern=r"^[0-9]{10}-[0-9]{2}-[0-9]{6}$")
    form: Literal["10-K", "10-K/A", "10-Q", "10-Q/A"]
    source_url: StrictStr = Field(pattern=r"^https://www\.sec\.gov/Archives/edgar/data/")
    status: Literal["AVAILABLE", "UNAVAILABLE"]
    reason: StrictStr | None = None
    tables: list[SecBreakdownTable]

    @model_validator(mode="after")
    def validate_status(self):
        if (self.status == "AVAILABLE") != bool(self.tables) or (self.status == "UNAVAILABLE") != bool(self.reason):
            raise ValueError("SEC breakdown status must agree with its tables and reason")
        return self


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
    balanceSheet: SecBalanceSheet | None = None
    revenueBreakdown: SecRevenueBreakdown | None = None
    # SEC 申报索引中最新 10-K/10-Q 的申报日期；与事实的最新申报日期对比，判断是否已拿到最新报告。
    latestFilings: dict[Literal["10-K", "10-Q"], Date] | None = None
    # 申报信息截止日，与 asOf（会计期间期末）分开；未提供时保持原来的当前查询。
    requestedAsOf: Date | None = None

    @field_validator("requestedAsOf", mode="before")
    @classmethod
    def require_cutoff_date(cls, value):
        return business_date(value, "SEC requestedAsOf must be YYYY-MM-DD")

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
            if self.requestedAsOf is not None:
                if any(fact.filed > self.requestedAsOf for fact in facts):
                    raise ValueError("SEC facts must have been filed by the requested cutoff")
                if self.latestFilings and any(filed > self.requestedAsOf for filed in self.latestFilings.values()):
                    raise ValueError("SEC filing metadata exceeds the requested cutoff")
                if self.balanceSheet and Date.fromisoformat(self.balanceSheet.filed) > self.requestedAsOf:
                    raise ValueError("SEC balance sheet exceeds the requested cutoff")
                if self.revenueBreakdown is not None:
                    raise ValueError("Historical SEC responses cannot attach an unverified current breakdown")
            limit = None if self.requestedYears is None else self.requestedYears * (4 if self.period == "quarterly" else 1)
            if limit is not None and any(len({fact.end for fact in metric.data}) > limit for metric in self.metrics.values()):
                raise ValueError("SEC facts exceed the requested number of periods")
        elif self.metrics or self.asOf is not None:
            raise ValueError("Non-success SEC responses cannot contain usable facts or an asOf date")
        return self


def normalize_sec_financials(
    payload: dict | None, ticker: str, *, error: Exception | None = None,
    requested_period: FinancialReportPeriod | None = None, requested_years: int | None = None,
    route: StockRoute | None = None, requested_as_of: str | None = None,
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
        "requestedAsOf": requested_as_of,
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
                # 全部科目共用核心报表的请求期末；缺年科目不能拿更老的年份凑足份数。
                core_metrics = [metric for name, metric in validated.metrics.items()
                                if name in {"Revenue", "NetIncome", "TotalAssets", "OperatingCashFlow"}]
                ends = set(sorted({fact.end for metric in core_metrics or validated.metrics.values()
                                   for fact in metric.data})[-periods:])
                for name, metric in validated.metrics.items():
                    # 同一 end 的不同 start/公告事实仍有业务含义，不能任意去掉一条或多算一期。
                    facts = sorted((fact for fact in metric.data if fact.end in ends),
                                   key=lambda fact: (fact.end, fact.start or Date.min, fact.filed, fact.accn))
                    if facts:
                        selected_metrics[name] = metric.model_copy(update={"data": facts})
                candidate.update(metrics=selected_metrics, metric_count=len(selected_metrics),
                                 asOf=max(fact.end for metric in selected_metrics.values() for fact in metric.data))
            return SecFinancialsResponse.model_validate(candidate).model_dump(mode="json")
        except (ValidationError, ValueError, TypeError, KeyError, AttributeError):
            response.update(errorCode="INVALID_PROVIDER_DATA", retryable=False,
                            message="SEC EDGAR XBRL returned invalid facts or source metadata; check the data-service contract.")
    return SecFinancialsResponse.model_validate(response).model_dump(mode="json")
