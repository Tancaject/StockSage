"""
SEC EDGAR API 路由。

提供以下端点：
- 查询公告索引（10-K、10-Q）
- 下载并解析公告正文为 Item 章节
- 获取结构化 XBRL 财务数据
"""

from app.research_budget import check_budget

from datetime import date
from fastapi import APIRouter, Query, HTTPException
from app.sec_financials import FinancialReportPeriod, SecFinancialsResponse, normalize_sec_financials
from app.services.edgar_service import edgar_service

router = APIRouter()
edgar = edgar_service


@router.get("/filings")
def get_filings(
    ticker: str = Query(..., description="股票代码，例如 AAPL"),
    type: str = Query("10-K", description="公告类型：10-K 或 10-Q"),
    count: int = Query(3, ge=1, le=10, description="最近公告数量"),
):
    """查询某个股票代码最近 N 份公告。"""
    try:
        return edgar.get_filings(ticker, filing_type=type, count=count)
    except Exception as e:
        check_budget(e)
        return {"error": True, "message": str(e)}


@router.get("/filing-content")
def get_filing_content(
    url: str = Query(..., description="来自 /filings 端点的公告文档 URL"),
    type: str = Query("10-K", description="用于章节解析的公告类型"),
):
    """下载并解析公告为 Item 级别章节。"""
    try:
        return edgar.get_filing_content(url, filing_type=type)
    except Exception as e:
        check_budget(e)
        return {"error": True, "message": str(e)}


@router.get("/xbrl", response_model=SecFinancialsResponse)
def get_xbrl(
    ticker: str = Query(..., min_length=1, description="美股代码，例如 AAPL"),
    period: FinancialReportPeriod = Query("annual"),
    years: int | None = Query(None, ge=1, le=10),
    as_of: str | None = Query(None, alias="asOf", pattern=r"^\d{4}-\d{2}-\d{2}$", description="仅采用此日及此前已申报的事实"),
    financial_extras: bool = Query(False, alias="financialExtras", description="附加库存、成本和期末普通股数；不改变默认科目"),
):
    """获取具有期间、单位、申报版本及可选信息截止日的 XBRL 财务事实。"""
    if as_of is not None:
        try:
            cutoff = date.fromisoformat(as_of)
            if cutoff > date.today():
                raise ValueError("future cutoff")
        except ValueError:
            raise HTTPException(status_code=400, detail="asOf 必须是有效的 YYYY-MM-DD 日期，且不能晚于今天。")
    effective_years = years if years is not None else (10 if period == "quarterly" else None)
    try:
        payload = edgar.get_xbrl(ticker, period, as_of=as_of, financial_extras=financial_extras)
    except Exception as e:
        check_budget(e)
        return normalize_sec_financials(None, ticker, error=e, requested_period=period if effective_years else None,
                                        requested_years=effective_years, requested_as_of=as_of)
    return normalize_sec_financials(payload, ticker, requested_period=period if effective_years else None,
                                    requested_years=effective_years, requested_as_of=as_of)
