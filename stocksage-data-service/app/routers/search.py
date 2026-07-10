"""
网页搜索 API 路由。
当 RAG 知识库没有相关信息时，提供互联网搜索兜底能力。
"""

from typing import Optional

from fastapi import APIRouter, Query
from app.services.search_service import SearchService

router = APIRouter()
search_svc = SearchService()


@router.get("/web")
def web_search(
    q: str = Query(..., description="Search query"),
    max_results: int = Query(5, ge=1, le=20, description="Max number of results"),
    timelimit: Optional[str] = Query(
        None, description="Time range filter: d/w/m/y; empty means no time filter"),
    depth: str = Query(
        "basic", description="Tavily search depth: basic or advanced"),
):
    """通用网页搜索。"""
    return search_svc.search(q, max_results, timelimit, depth)


@router.get("/news")
def news_search(
    q: str = Query(..., description="Search query"),
    max_results: int = Query(5, ge=1, le=20, description="Max number of results"),
    timelimit: Optional[str] = Query(
        None, description="Time range filter: d/w/m; empty means no time filter"),
    depth: str = Query(
        "basic", description="Tavily search depth: basic or advanced"),
):
    """搜索近期新闻文章。"""
    return search_svc.search_news(q, max_results, timelimit, depth)
