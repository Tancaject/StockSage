"""
数据服务运行期配置。

集中从环境变量 / .env 文件读取配置项。密钥（如 Tavily API Key）只放在
data-service 根目录的 .env 文件里——该文件已被 .gitignore 忽略，不入库、不写进代码。

为什么单独做一个 config 模块：
- 让"密钥从哪来"只有一个出处，方便审计；
- .env 路径固定为 data-service 根目录，与 uvicorn 的启动工作目录无关。
"""

import os
from pathlib import Path

from dotenv import load_dotenv

# .env 固定位于 data-service 根目录（app/ 的上一级），不依赖启动时的当前工作目录。
# 文件不存在时 load_dotenv 静默返回 False，不会报错——此时搜索自动回退到 DuckDuckGo。
_ENV_PATH = Path(__file__).resolve().parent.parent / ".env"
load_dotenv(_ENV_PATH)

# Tavily 网页搜索 API Key。留空表示未配置，SearchService 会回退到免费的 DuckDuckGo。
TAVILY_API_KEY = os.getenv("TAVILY_API_KEY", "").strip()

# 网页搜索（/api/search/web）使用的 Tavily topic。
# finance 面向财经内容、对投研场景召回更好；若该 topic 在你的账号/套餐上不可用，
# 可在 .env 里把它设为 general。新闻路径固定用 news topic，不受此项影响。
TAVILY_WEB_TOPIC = os.getenv("TAVILY_WEB_TOPIC", "finance").strip() or "finance"
