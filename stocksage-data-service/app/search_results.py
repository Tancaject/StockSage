"""搜索响应契约：供应商时间、请求窗口与抓取时间不能互相替代。"""

from datetime import date as Date, datetime, timezone
from email.utils import parsedate_to_datetime
import re
from typing import Literal
from urllib.parse import urlsplit

from pydantic import (
    AwareDatetime, BaseModel, ConfigDict, Field, StrictBool, StrictInt, StrictStr,
    field_validator, model_validator,
)

SearchType = Literal["web", "news"]
SearchDepth = Literal["basic", "advanced"]
TimeLimit = Literal["d", "w", "m", "y"]
FallbackReason = Literal[
    "PRIMARY_NOT_CONFIGURED", "UPSTREAM_TIMEOUT", "UPSTREAM_RATE_LIMIT",
    "UPSTREAM_ERROR", "INVALID_PROVIDER_DATA",
]


def _utc_instant(value):
    if value is None:
        return None
    if isinstance(value, str):
        if re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]+)?(?:Z|\+00:00)", value) is None:
            raise ValueError("Search timestamps require UTC ISO strings")
        value = datetime.fromisoformat(value)
    if not isinstance(value, datetime) or value.tzinfo is None or value.utcoffset().total_seconds() != 0:
        raise ValueError("Search timestamps require UTC datetimes, not numeric epochs")
    return value


class SearchResult(BaseModel):
    model_config = ConfigDict(extra="ignore")

    title: StrictStr
    link: StrictStr
    snippet: StrictStr
    date: StrictStr
    source: StrictStr
    publishedTimeKind: Literal["INSTANT", "DATE", "UNKNOWN"]
    publishedAt: AwareDatetime | None
    publishedDate: Date | None
    publishedTimeBasis: Literal["PROVIDER_REPORTED"] = "PROVIDER_REPORTED"

    @field_validator("publishedAt", mode="before")
    @classmethod
    def require_utc_publication(cls, value):
        return _utc_instant(value)

    @field_validator("link")
    @classmethod
    def require_source_url(cls, value):
        parsed = urlsplit(value)
        if parsed.scheme not in {"http", "https"} or not parsed.hostname or any(char.isspace() for char in value):
            raise ValueError("Search source URLs require HTTP(S) and a host")
        parsed.hostname.encode("ascii")
        return value

    @field_validator("publishedDate", mode="before")
    @classmethod
    def require_date(cls, value):
        if value is None or type(value) is Date:
            return value
        if isinstance(value, str):
            parsed = Date.fromisoformat(value)
            if parsed.isoformat() == value:
                return parsed
        raise ValueError("Search publication dates must be YYYY-MM-DD")

    @model_validator(mode="after")
    def validate_publication(self):
        if not self.title.strip() and not self.snippet.strip():
            raise ValueError("Search results require a title or snippet")
        if self.publishedTimeKind == "INSTANT":
            if self.publishedAt is None or self.publishedDate is not None or self.publishedAt.utcoffset().total_seconds() != 0:
                raise ValueError("Instant publication times require UTC timestamps only")
        elif self.publishedTimeKind == "DATE":
            if self.publishedDate is None or self.publishedAt is not None:
                raise ValueError("Date publication times cannot invent a clock time")
        elif self.publishedAt is not None or self.publishedDate is not None:
            raise ValueError("Unknown publication times cannot contain a parsed time")
        return self


class SearchResponse(BaseModel):
    model_config = ConfigDict(extra="ignore")

    schemaVersion: Literal[1] = 1
    status: Literal["SUCCESS", "EMPTY", "ERROR"]
    query: StrictStr
    provider: Literal["tavily", "ddg"] | None
    searchType: SearchType
    requestedTimelimit: TimeLimit | None
    effectiveTimelimit: TimeLimit | None
    timelimit: TimeLimit | None
    requestedDepth: SearchDepth
    effectiveDepth: SearchDepth | None
    depth: SearchDepth | None
    topic: Literal["general", "finance", "news"] | None
    requestedMaxResults: StrictInt = Field(ge=1, le=20)
    count: StrictInt = Field(ge=0)
    fetchedAt: AwareDatetime
    results: list[SearchResult]
    fallbackFrom: Literal["tavily"] | None
    fallbackReason: FallbackReason | None
    error: StrictBool
    errorCode: StrictStr | None
    message: StrictStr | None
    retryable: StrictBool | None
    requestedDays: StrictInt | None = Field(default=None, ge=1)
    input: StrictStr | None = None
    market: StrictStr | None = None
    resolvedCode: StrictStr | None = None
    source: StrictStr | None = None
    routeReason: StrictStr | None = None

    @field_validator("schemaVersion", mode="before")
    @classmethod
    def require_schema_version(cls, value):
        if type(value) is not int or value != 1:
            raise ValueError("Search schemaVersion must be integer 1")
        return value

    @field_validator("fetchedAt", mode="before")
    @classmethod
    def require_utc_fetch_time(cls, value):
        return _utc_instant(value)

    @model_validator(mode="after")
    def validate_consistency(self):
        if self.count != len(self.results) or self.count > self.requestedMaxResults or self.error != (self.status == "ERROR"):
            raise ValueError("Search status/error/count must agree with its results and request")
        if self.status == "SUCCESS":
            if not self.results:
                raise ValueError("Successful searches require usable results")
        elif self.results:
            raise ValueError("Empty or failed searches cannot contain usable results")
        if self.status == "ERROR":
            if self.errorCode is None or not self.errorCode.strip():
                raise ValueError("Failed searches require an error code")
        elif self.errorCode is not None or self.retryable is not None or self.provider is None:
            raise ValueError("Successful or empty searches require an actual provider and no error metadata")
        if self.timelimit != self.effectiveTimelimit or self.depth != self.effectiveDepth:
            raise ValueError("Search compatibility aliases must match effective parameters")
        if (self.fallbackFrom is None) != (self.fallbackReason is None):
            raise ValueError("Search fallback provider and reason must be supplied together")
        if self.provider is None:
            if any(value is not None for value in (self.effectiveTimelimit, self.effectiveDepth, self.topic, self.fallbackFrom)):
                raise ValueError("Unknown search providers cannot claim effective parameters")
        else:
            expected_time = None if self.searchType == "news" and self.requestedTimelimit == "y" else self.requestedTimelimit
            if self.effectiveTimelimit != expected_time:
                raise ValueError("Search effective time filter differs from supported request semantics")
            if self.provider == "tavily":
                if self.effectiveDepth != self.requestedDepth or self.topic is None or self.fallbackFrom is not None:
                    raise ValueError("Tavily responses require the actual requested depth and topic")
                if self.searchType == "news" and self.topic != "news":
                    raise ValueError("News searches require the news topic")
            elif self.effectiveDepth is not None or self.topic != ("news" if self.searchType == "news" else "general") or self.fallbackFrom != "tavily":
                raise ValueError("DuckDuckGo cannot claim Tavily search depth or omit fallback provenance")
        return self


def normalize_search_result(raw: dict) -> SearchResult:
    result = {**raw, "publishedTimeKind": "UNKNOWN", "publishedAt": None, "publishedDate": None}
    value = raw.get("date", "")
    if isinstance(value, str) and value.strip():
        candidate = value.strip()
        try:
            if re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}", candidate):
                result.update(publishedTimeKind="DATE", publishedDate=Date.fromisoformat(candidate))
            else:
                try:
                    timestamp = datetime.fromisoformat(candidate)
                except ValueError:
                    timestamp = parsedate_to_datetime(candidate)
                if timestamp.tzinfo is not None and timestamp.utcoffset() is not None:
                    result.update(publishedTimeKind="INSTANT", publishedAt=timestamp.astimezone(timezone.utc))
        except (ValueError, TypeError, OverflowError):
            # 供应商无日期、相对日期或未给时区时，原文仍可显示但不伪造可比较时刻。
            pass
    return SearchResult.model_validate(result)
