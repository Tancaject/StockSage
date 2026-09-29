"""
StockSage 金融数据服务。

本服务的职责：
- 封装 baostock（A 股）和 AKShare（港股）为统一的 REST API
- Java 后端通过 HTTP 调用本服务获取行情数据
- 独立部署，与 Java 后端解耦

为什么要单独做一个 Python 服务？
- baostock 和 AKShare 都是 Python 库，没有 Java 版本
- 独立服务方便更换数据源（如从 baostock 换成 tushare），Java 端无需改动
- Python 的 pandas/numpy 生态做数据计算（技术指标等）非常方便

启动方式：
  uvicorn main:app --port 8001 --reload
"""

from contextlib import asynccontextmanager

from fastapi import FastAPI
from app.research_budget import ResearchBudgetMiddleware

from app.routers import stock, search, document, edgar
from app.services.baostock_service import (
    get_baostock_status,
    start_baostock_login_manager,
    stop_baostock_login_manager,
)


@asynccontextmanager
async def lifespan(application: FastAPI):
    """
    应用生命周期管理。
    baostock 登录由后台线程执行并自动重试，避免网络抖动阻塞整个服务启动。
    lifespan 是 FastAPI 推荐的生命周期管理方式（替代 on_event）。
    """
    start_baostock_login_manager()
    print("data service startup complete; baostock login manager is running")
    try:
        yield
    finally:
        stop_baostock_login_manager()


app = FastAPI(
    title="StockSage Data Service",
    description="金融数据 REST API，供 Java 后端调用",
    version="0.1.0",
    lifespan=lifespan,
)

app.add_middleware(ResearchBudgetMiddleware)

# 注册路由
app.include_router(stock.router, prefix="/api/stock", tags=["stock"])
app.include_router(search.router, prefix="/api/search", tags=["search"])
app.include_router(document.router, prefix="/api/document", tags=["document"])
app.include_router(edgar.router, prefix="/api/edgar", tags=["edgar"])


@app.get("/health")
def health():
    """健康检查接口，Docker 和负载均衡器用来判断服务是否存活。"""
    return {
        "status": "ok",
        "dataSources": {
            "baostock": get_baostock_status(),
            "akshare": {"status": "lazy"},
            "edgar": {"status": "lazy"},
        },
    }


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host="0.0.0.0", port=8001, reload=True)
