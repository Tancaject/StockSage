"""
SEC EDGAR API 路由。

提供以下端点：
- 查询公告索引（10-K、10-Q）
- 下载并解析公告正文为 Item 章节
- 获取结构化 XBRL 财务数据
"""

from app.research_budget import check_budget

from fastapi import APIRouter, Query
from app.sec_financials import SecFinancialsResponse, normalize_sec_financials
from app.services.edgar_service import EdgarService

router = APIRouter()
edgar = EdgarService()


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
    ticker: str = Query(..., min_length=1, description="美股代码，例如 AAPL；当前仅提供年度 XBRL 事实"),
):
    """获取具有期间、单位和公告来源的年度 XBRL 财务事实。"""
    try:
        payload = edgar.get_xbrl(ticker)
    except Exception as e:
        check_budget(e)
        return normalize_sec_financials(None, ticker, error=e)
    return normalize_sec_financials(payload, ticker)
