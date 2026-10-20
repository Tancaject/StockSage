"""
SEC EDGAR 服务：获取公告索引、解析公告 HTML，并查询 XBRL 数据。

SEC EDGAR 是美国证券交易委员会公开披露文件数据库。
所有 API 均免费使用，不需要 API Key。

注意：SEC 要求 User-Agent 请求头标识调用方。
https://www.sec.gov/os/accessing-edgar-data
"""

from app.research_budget import check_budget, bounded_timeout, budget_call

import re
import time
import logging
import threading
from datetime import date
from math import isfinite
from urllib.parse import urlparse

import httpx
from bs4 import BeautifulSoup, NavigableString, Tag

logger = logging.getLogger(__name__)

# SEC 要求描述性的 User-Agent；匿名请求容易被限流。
_HEADERS = {
    "User-Agent": "StockSage Research contact@stocksage.dev",
    "Accept-Encoding": "gzip, deflate",
}

# SEC 限流规则为每秒最多 10 次请求；这里主动保持更低频率。
_REQUEST_DELAY = 0.15

_CIK_CACHE: dict[str, str] = {}
# 只缓存成功取到的 SIC；查询失败不入缓存，下次仍会重试。
_SIC_CACHE: dict[str, str] = {}
_ALLOWED_SEC_HOSTS = {"sec.gov", "www.sec.gov", "data.sec.gov"}


class EdgarService:
    """SEC EDGAR 访问与公告解析服务。"""

    def __init__(self):
        """初始化共享 HTTP 客户端，复用连接并统一请求头。"""
        self._client = httpx.Client(headers=_HEADERS, timeout=30, follow_redirects=False)
        self._last_request_time = 0.0
        # 同步路由跑在线程池里并发调用，检查-休眠-更新必须串行，否则多个线程会同时放行。
        self._throttle_lock = threading.Lock()

    def _throttle(self):
        """遵守 SEC 限流要求。"""
        with self._throttle_lock:
            elapsed = time.monotonic() - self._last_request_time
            if elapsed < _REQUEST_DELAY:
                time.sleep(bounded_timeout(_REQUEST_DELAY - elapsed))
            check_budget()
            self._last_request_time = time.monotonic()

    def _get(self, url: str) -> httpx.Response:
        """执行带限流和 URL 校验的 SEC GET 请求。"""
        self._validate_sec_url(url)
        self._throttle()
        # 自动跟随会在目标校验前发出下一跳；SEC 端点变更时应明确更新调用地址。
        resp = budget_call(self._client.get, url, follow_redirects=False, timeout=bounded_timeout(30))
        resp.raise_for_status()
        return resp

    @staticmethod
    def _validate_sec_url(url: str) -> None:
        """限制只能访问 SEC 官方 HTTPS 域名，避免被用作任意 URL 抓取器。"""
        parsed = urlparse(url or "")
        host = (parsed.hostname or "").lower()
        if parsed.scheme != "https" or host not in _ALLOWED_SEC_HOSTS:
            raise ValueError(f"Only HTTPS SEC EDGAR URLs are allowed, got: {url}")

    # ------------------------------------------------------------------
    # 股票代码到 CIK 映射
    # ------------------------------------------------------------------

    def _resolve_cik(self, ticker: str) -> str:
        """将股票代码解析为补零后的 10 位 CIK。"""
        ticker = ticker.upper()
        if ticker in _CIK_CACHE:
            return _CIK_CACHE[ticker]

        url = "https://www.sec.gov/files/company_tickers.json"
        data = self._get(url).json()
        for entry in data.values():
            t = entry.get("ticker", "").upper()
            cik = str(entry.get("cik_str", "")).zfill(10)
            _CIK_CACHE[t] = cik

        if ticker not in _CIK_CACHE:
            raise ValueError(f"Ticker '{ticker}' not found in SEC company tickers")

        return _CIK_CACHE[ticker]

    # ------------------------------------------------------------------
    # 1. 公告索引
    # ------------------------------------------------------------------

    def get_filings(self, ticker: str, filing_type: str = "10-K", count: int = 3) -> dict:
        """
        查询某个股票代码最近 N 份指定类型公告。

        返回公告元数据列表，包括日期、accession 编号和主文档 URL。
        """
        cik = self._resolve_cik(ticker)
        url = f"https://data.sec.gov/submissions/CIK{cik}.json"
        data = self._get(url).json()

        company_name = data.get("name", ticker)
        recent = data.get("filings", {}).get("recent", {})

        forms = recent.get("form", [])
        dates = recent.get("filingDate", [])
        accessions = recent.get("accessionNumber", [])
        primary_docs = recent.get("primaryDocument", [])

        filings = []
        for i, form in enumerate(forms):
            if form == filing_type and len(filings) < count:
                accession = accessions[i].replace("-", "")
                doc_url = (
                    f"https://www.sec.gov/Archives/edgar/data/"
                    f"{cik.lstrip('0')}/{accession}/{primary_docs[i]}"
                )
                filings.append({
                    "filing_type": form,
                    "filing_date": dates[i],
                    "accession_number": accessions[i],
                    "document_url": doc_url,
                    "primary_document": primary_docs[i],
                })

        return {
            "ticker": ticker.upper(),
            "company_name": company_name,
            "cik": cik,
            "filing_type": filing_type,
            "count": len(filings),
            "filings": filings,
        }

    # ------------------------------------------------------------------
    # 2. 公告正文：解析为 Item 章节
    # ------------------------------------------------------------------

    # 需要提取的标准 10-K Item 标题。
    _10K_ITEMS = [
        ("Item 1.", "Business"),
        ("Item 1A.", "Risk Factors"),
        ("Item 1B.", "Unresolved Staff Comments"),
        ("Item 2.", "Properties"),
        ("Item 3.", "Legal Proceedings"),
        ("Item 4.", "Mine Safety Disclosures"),
        ("Item 5.", "Market for Registrant's Common Equity"),
        ("Item 6.", "Reserved"),
        ("Item 7.", "Management's Discussion and Analysis"),
        ("Item 7A.", "Quantitative and Qualitative Disclosures About Market Risk"),
        ("Item 8.", "Financial Statements and Supplementary Data"),
        ("Item 9.", "Changes in and Disagreements with Accountants"),
        ("Item 9A.", "Controls and Procedures"),
        ("Item 9B.", "Other Information"),
        ("Item 10.", "Directors, Executive Officers and Corporate Governance"),
        ("Item 11.", "Executive Compensation"),
        ("Item 12.", "Security Ownership"),
        ("Item 13.", "Certain Relationships and Related Transactions"),
        ("Item 14.", "Principal Accountant Fees and Services"),
        ("Item 15.", "Exhibits and Financial Statement Schedules"),
    ]

    _10Q_ITEMS = [
        ("Item 1.", "Financial Statements"),
        ("Item 2.", "Management's Discussion and Analysis"),
        ("Item 3.", "Quantitative and Qualitative Disclosures About Market Risk"),
        ("Item 4.", "Controls and Procedures"),
        # Part II。Item 1–4 与 Part I 编号重复，只登记编号唯一的几项来截断 Part I Item 4；
        # 否则风险因素会并进 "Item 4. Controls and Procedures"。
        ("Item 1A.", "Risk Factors"),
        ("Item 5.", "Other Information"),
        ("Item 6.", "Exhibits"),
    ]

    def get_filing_content(self, document_url: str, filing_type: str = "10-K") -> dict:
        """
        下载公告 HTML，并解析成 Item 级别章节。

        返回 {section, content} 字典列表。
        """
        resp = self._get(document_url)
        html = resp.text

        items = self._10K_ITEMS if filing_type == "10-K" else self._10Q_ITEMS
        soup = BeautifulSoup(html, "html.parser")
        self._remove_noisy_elements(soup)
        self._drop_non_heading_tables(soup, items)

        text = self._html_to_structured_text(soup)
        sections = self._extract_sections(text, items)
        if not sections and len(text) >= 1000:
            logger.warning(
                "Filing section extraction failed; falling back to full filing text: url=%s, chars=%s",
                document_url,
                len(text),
            )
            sections = [{
                "section": "Full Filing",
                "content": text,
                "char_count": len(text),
            }]

        return {
            "document_url": document_url,
            "filing_type": filing_type,
            "section_count": len(sections),
            "sections": sections,
        }

    def _html_to_structured_text(self, soup: BeautifulSoup) -> str:
        """
        将公告 HTML 转换成类 Markdown 文本，并尽量避开表格噪声。

        保留标题、列表标记和段落边界，有助于下游切片保留局部上下文。
        这里刻意比完整 HTML 到 Markdown 转换更轻量。调用方需先执行 _remove_noisy_elements。
        """
        for br in soup.find_all("br"):
            br.replace_with("\n")

        root = soup.body or soup
        lines: list[str] = []
        block_tags = {
            "address", "article", "aside", "blockquote", "dd", "div", "dl",
            "dt", "fieldset", "figcaption", "figure", "footer", "form", "h1",
            "h2", "h3", "h4", "h5", "h6", "header", "hr", "li", "main", "nav",
            "ol", "p", "pre", "section", "ul",
        }

        for node in root.descendants:
            if isinstance(node, NavigableString):
                continue
            if not isinstance(node, Tag):
                continue

            name = (node.name or "").lower()
            if name == "tr":
                self._append_structured_line(lines, node.get_text(" ", strip=True))
                continue

            if node.find_parent("table"):
                continue

            if name in {"h1", "h2", "h3", "h4", "h5", "h6"}:
                level = min(6, max(2, int(name[1]) + 1))
                self._append_structured_line(lines, "#" * level + " " + node.get_text(" ", strip=True))
                continue

            if name == "li":
                self._append_structured_line(lines, "- " + node.get_text(" ", strip=True))
                continue

            if name in {"p", "div", "section", "article", "blockquote", "pre"}:
                if self._has_block_child(node, block_tags):
                    continue
                self._append_structured_line(lines, node.get_text(" ", strip=True))

        if not lines:
            lines = [soup.get_text(separator="\n")]

        text = "\n\n".join(lines)
        text = self._normalize_whitespace(text)
        text = re.sub(r"\n{3,}", "\n\n", text)
        return text.strip()

    def _remove_noisy_elements(self, soup: BeautifulSoup) -> None:
        """删除脚本、隐藏节点和内联 XBRL 元数据等不会进入正文的噪声元素。"""
        for tag in soup.find_all(True):
            if tag.name is None:
                continue
            name = (tag.name or "").lower()
            attrs = tag.attrs or {}
            style = str(attrs.get("style") or "").replace(" ", "").lower()
            if name in {"script", "style", "noscript", "svg"}:
                tag.decompose()
                continue
            if name in {"ix:hidden", "ix:header", "ix:references", "ix:resources"}:
                tag.decompose()
                continue
            if "display:none" in style or "visibility:hidden" in style:
                tag.decompose()

    def _drop_non_heading_tables(self, soup: BeautifulSoup, items: list[tuple[str, str]]) -> None:
        """移除大多数表格，只保留可能承载 Item 标题的表格。"""
        for table in soup.find_all("table"):
            if not self._table_has_item_heading(table, items):
                table.decompose()

    def _table_has_item_heading(self, table: Tag, items: list[tuple[str, str]]) -> bool:
        """判断表格是否可能是正文标题，而不是目录、脚注或财务数据表。"""
        text = self._normalize_whitespace(table.get_text(" ", strip=True))
        if not text:
            return False
        if self._looks_like_table_of_contents(text, items):
            return False

        for item_num, item_title in items:
            item_pattern = self._item_number_pattern(item_num)
            title_prefix = re.escape(item_title.split()[0]) if item_title else ""
            titled_pattern = re.compile(
                rf"{item_pattern}(?:\s|[-—:.])*{title_prefix}",
                re.IGNORECASE,
            )
            if titled_pattern.search(text):
                return True

        return self._table_has_section_title(text, items)

    def _looks_like_table_of_contents(self, text: str, items: list[tuple[str, str]]) -> bool:
        """目录通常密集列出多个 Item，并带 page/part 等提示，需要从正文候选中排除。"""
        lower = text.lower()
        item_hits = sum(
            1 for item_num, _ in items
            if re.search(self._item_number_pattern(item_num), text, re.IGNORECASE)
        )
        threshold = 4 if len(items) <= 4 else 5
        if item_hits < threshold:
            return False
        return (
            "table of contents" in lower
            or (" page " in f" {lower} " and re.search(r"\bpart\s+i\b", lower) is not None)
        )

    def _table_has_section_title(self, text: str, items: list[tuple[str, str]]) -> bool:
        """用短表格文本判断是否承载章节标题。"""
        if len(text) > 500:
            return False

        normalized = self._normalize_title_text(text)
        for _, item_title in items:
            title_prefix = self._title_prefix(item_title)
            if title_prefix and re.search(rf"\b{re.escape(title_prefix)}\b", normalized):
                return True
        return False

    @staticmethod
    def _has_block_child(node: Tag, block_tags: set[str]) -> bool:
        """判断节点是否包含块级子节点，用于结构化文本换行。"""
        for child in node.find_all(recursive=False):
            if isinstance(child, Tag) and (child.name or "").lower() in block_tags:
                return True
        return False

    @staticmethod
    def _append_structured_line(lines: list[str], text: str) -> None:
        """追加规范化后的文本行，并跳过空行和连续重复行。"""
        cleaned = EdgarService._normalize_whitespace(text or "").strip()
        cleaned = re.sub(r"\n{2,}", "\n", cleaned)
        if not cleaned:
            return
        if lines and lines[-1] == cleaned:
            return
        lines.append(cleaned)

    def _extract_sections(self, text: str, items: list[tuple[str, str]]) -> list[dict]:
        """
        通过在文档文本中查找 Item 标题来抽取章节。

        策略：先定位每个 Item 标题的位置，再按相邻标题之间的文本切片。
        """
        # 将花式引号归一化为 ASCII，便于匹配。
        normalized = text.replace("\u2018", "'").replace("\u2019", "'")
        normalized = normalized.replace("\u201c", '"').replace("\u201d", '"')
        normalized = normalized.replace("\u00a0", " ")

        found = []
        for item_num, item_title in items:
            item_pattern = self._item_number_pattern(item_num)
            # 匹配标题开头的几个词，以兼容较长或变化的标题。
            # 字母间允许一个空格：分段排版的标题转文本后会变成 "RIS K FACTORS"、"B USINESS"。
            title_prefix = r" ?".join(re.escape(char) for char in item_title.split()[0]) if item_title else ""
            pattern = re.compile(
                rf"^\s*(?:#{{1,6}}\s*)?{item_pattern}(?:\s|[-—:.])*{title_prefix}",
                re.IGNORECASE | re.MULTILINE,
            )
            for match in pattern.finditer(normalized):
                found.append((match.start(), item_num, item_title))

        matched_items = {item_num for _, item_num, _ in found}
        for item_num, item_title in items:
            if item_num in matched_items:
                continue
            title_prefix = self._title_prefix(item_title)
            if not title_prefix:
                continue
            for line_match in re.finditer(r"(?m)^.*$", normalized):
                line = line_match.group(0).strip()
                line_without_heading_marks = re.sub(r"^#{1,6}\s*", "", line)
                line_title = self._normalize_title_text(line_without_heading_marks)
                if line_title.startswith(title_prefix) and len(line) <= 240:
                    found.append((line_match.start(), item_num, item_title))

        if not found:
            # 兜底：只匹配不带标题的 "Item N."。
            for item_num, item_title in items:
                item_pattern = self._item_number_pattern(item_num)
                pattern = re.compile(
                    rf"^\s*(?:#{{1,6}}\s*)?{item_pattern}(?:\s|[-—:.])*\S",
                    re.IGNORECASE | re.MULTILINE,
                )
                for match in pattern.finditer(normalized):
                    found.append((match.start(), item_num, item_title))

        # 按文档中的位置排序。
        found.sort(key=lambda x: x[0])

        # 去重：每个 item 只保留最后一次出现的位置。
        # 10-K 前部目录通常会提前列出 item。
        seen = {}
        for pos, item_num, item_title in found:
            seen[item_num] = (pos, item_num, item_title)
        found = sorted(seen.values(), key=lambda x: x[0])

        sections = []
        for i, (pos, item_num, item_title) in enumerate(found):
            end = found[i + 1][0] if i + 1 < len(found) else len(text)
            content = text[pos:end].strip()

            # 跳过过短章节，它们通常只是引用或占位。
            if len(content) < 100:
                continue

            sections.append({
                "section": f"{item_num} {item_title}",
                "content": content,
                "char_count": len(content),
            })

        return sections

    @staticmethod
    def _normalize_whitespace(text: str) -> str:
        """统一空白字符，保留换行作为章节边界线索。"""
        normalized = (text or "").replace("\u00a0", " ")
        normalized = re.sub(r"[ \t\f\v]+", " ", normalized)
        normalized = re.sub(r" *\n *", "\n", normalized)
        return normalized

    @staticmethod
    def _item_number_pattern(item_num: str) -> str:
        """为 Item 编号生成宽松匹配正则，兼容 Item 1A、Item 7. 等格式。"""
        match = re.match(r"Item\s*(\d+)\s*([A-Za-z]?)\.?", item_num, re.IGNORECASE)
        if not match:
            return re.escape(item_num)
        number, suffix = match.groups()
        suffix_pattern = rf"\s*{re.escape(suffix)}" if suffix else ""
        return rf"\bItem\s*{re.escape(number)}{suffix_pattern}\s*\.?(?![0-9A-Za-z])"

    @staticmethod
    def _normalize_title_text(text: str) -> str:
        """把标题转为可比较的纯字母数字文本。"""
        normalized = (text or "").lower()
        normalized = normalized.replace("\u2018", "'").replace("\u2019", "'")
        normalized = normalized.replace("\u201c", '"').replace("\u201d", '"')
        normalized = normalized.replace("\u00a0", " ")
        normalized = re.sub(r"[^a-z0-9]+", " ", normalized)
        return re.sub(r"\s+", " ", normalized).strip()

    @staticmethod
    def _title_prefix(title: str) -> str:
        """提取章节标题前几个词，兼容 EDGAR 标题的长短变化。"""
        tokens = EdgarService._normalize_title_text(title).split()
        if not tokens:
            return ""
        return " ".join(tokens[: min(4, len(tokens))])

    # ------------------------------------------------------------------
    # 3. XBRL 结构化财务数据
    # ------------------------------------------------------------------

    # 需要从 XBRL 中提取的关键财务概念。
    _XBRL_CONCEPTS = {
        "Revenue": [
            "us-gaap:Revenues",
            "us-gaap:RevenueFromContractWithCustomerExcludingAssessedTax",
            "us-gaap:SalesRevenueNet",
        ],
        "NetIncome": [
            "us-gaap:NetIncomeLoss",
        ],
        # 客户合同销售收入（如 CVX“销售及其他经营收入”），不含其他收益；和营收相同时不输出。
        "SalesRevenue": [
            "us-gaap:RevenueFromContractWithCustomerExcludingAssessedTax",
        ],
        # 持续经营口径（如 HON 剥离业务后）；和全口径相同时不输出。
        "ContinuingOperationsNetIncome": [
            "us-gaap:IncomeLossFromContinuingOperationsIncludingPortionAttributableToNoncontrollingInterest",
        ],
        "ContinuingOperationsCashFlow": [
            "us-gaap:NetCashProvidedByUsedInOperatingActivitiesContinuingOperations",
        ],
        # 利润表单列的资产处置净收益（如 BA），用于剔除一次性收益后的营业利润。
        "GainOnDispositions": [
            "us-gaap:GainLossOnDispositionOfAssets",
        ],
        # 现金流量表起点的合并净利润（含少数股东权益）；和归母净利润相同时不输出。
        "ConsolidatedNetIncome": [
            "us-gaap:ProfitLoss",
        ],
        "TotalAssets": [
            "us-gaap:Assets",
        ],
        "TotalLiabilities": [
            "us-gaap:Liabilities",
        ],
        "StockholdersEquity": [
            "us-gaap:StockholdersEquity",
        ],
        "OperatingIncome": [
            "us-gaap:OperatingIncomeLoss",
        ],
        # 能源、制药等不列营业利润的公司，盈利能力问题常按税前利润口径。
        "PretaxIncome": [
            "us-gaap:IncomeLossFromContinuingOperationsBeforeIncomeTaxesExtraordinaryItemsNoncontrollingInterest",
            "us-gaap:IncomeLossFromContinuingOperationsBeforeIncomeTaxesMinorityInterestAndIncomeLossFromEquityMethodInvestments",
        ],
        "EPS": [
            "us-gaap:EarningsPerShareDiluted",
            "us-gaap:EarningsPerShareBasic",
        ],
        "OperatingCashFlow": [
            "us-gaap:NetCashProvidedByUsedInOperatingActivities",
        ],
        "TotalDebt": [
            "us-gaap:LongTermDebt",
            "us-gaap:LongTermDebtNoncurrent",
        ],
        # 自由现金流和估值需要资本开支、现金和股本。
        "CapitalExpenditure": [
            "us-gaap:PaymentsToAcquirePropertyPlantAndEquipment",
            "us-gaap:PaymentsToAcquireProductiveAssets",
            "us-gaap:PaymentsToAcquireOtherPropertyPlantAndEquipment",
        ],
        # 出租设备支出单独列示（如 CAT），是否并入资本开支由分析方法决定。
        "LeasedEquipmentExpenditure": [
            "us-gaap:PaymentsToAcquireEquipmentOnLease",
        ],
        "Cash": [
            "us-gaap:CashAndCashEquivalentsAtCarryingValue",
            "us-gaap:CashCashEquivalentsRestrictedCashAndRestrictedCashEquivalents",
        ],
        "DilutedShares": [
            "us-gaap:WeightedAverageNumberOfDilutedSharesOutstanding",
        ],
    }
    _INSTANT_METRICS = {"TotalAssets", "TotalLiabilities", "StockholdersEquity", "TotalDebt", "Cash"}
    # 银行（SIC 6020-6039）的经营现金流和资本开支没有分析意义，换成净利息收入和信贷损失拨备。
    _BANK_DROPPED = {"OperatingCashFlow", "CapitalExpenditure"}
    _BANK_CONCEPTS = {
        "NetInterestIncome": ["us-gaap:InterestIncomeExpenseNet"],
        "CreditLossProvision": [
            "us-gaap:ProvisionForLoanLeaseAndOtherLosses",
            "us-gaap:ProvisionForLoanAndLeaseLosses",
        ],
    }
    # 最新期末比公司最新期末早一年以上的指标，说明公司已改用其它概念，展示出来会被当成当前数据。
    _STALE_METRIC_DAYS = 400

    def get_xbrl(self, ticker: str, period: str = "annual") -> dict:
        """
        从 XBRL companyfacts API 获取结构化财务数据。

        返回按实际 start/end 排列的年度（或单季）指标；filing_fiscal_year 只表示公告标签。
        period="quarterly" 时只取 10-Q 中约 90 天的单季事实：10-Q 不含第四财季，这里不做年报减三季的推算。
        """
        quarterly = period == "quarterly"
        cik = self._resolve_cik(ticker)
        url = f"https://data.sec.gov/api/xbrl/companyfacts/CIK{cik}.json"
        data = self._get(url).json()

        company_name = data.get("entityName", ticker)
        facts = data.get("facts", {}).get("us-gaap", {})
        concept_table = dict(self._XBRL_CONCEPTS)
        if self._is_bank(cik):
            for label in self._BANK_DROPPED:
                concept_table.pop(label, None)
            concept_table.update(self._BANK_CONCEPTS)

        # 10-K 的资产负债事实也可能含季度时点；以同份公司事实中的全年期间确认年末。
        annual_ends = set()
        for label, concepts in concept_table.items():
            if label in self._INSTANT_METRICS:
                continue
            for concept in concepts:
                units = facts.get(concept.split(":")[-1], {}).get("units", {})
                for entries in units.values():
                    annual_ends.update(row["end"] for row in self._filter_periods(entries, quarterly=quarterly))

        metrics = {}
        for label, concept_keys in concept_table.items():
            best = None
            for concept in concept_keys:
                concept_name = concept.split(":")[-1]
                if concept_name not in facts:
                    continue
                units_data = facts[concept_name].get("units", {})
                for unit_key in ["USD", "USD/shares", "shares"]:
                    if unit_key not in units_data:
                        continue
                    annual = self._filter_periods(
                        units_data[unit_key],
                        instant=label in self._INSTANT_METRICS,
                        annual_ends=annual_ends,
                        quarterly=quarterly,
                    )
                    if not annual:
                        continue
                    # 公告 fy 不是每条比较期事实的财年，最新概念按实际期间结束日选择。
                    if best is None or annual[-1]["end"] > best["data"][-1]["end"]:
                        for row in annual:
                            row["source_url"] = url
                        best = {
                            "concept": concept,
                            "unit": unit_key,
                            "data": annual[-10:],
                        }
            if best:
                metrics[label] = best

        # 细分口径与基础指标最新值相同时只保留基础指标，避免重复行。
        for variant, base in (("ConsolidatedNetIncome", "NetIncome"), ("SalesRevenue", "Revenue"),
                              ("ContinuingOperationsNetIncome", "ConsolidatedNetIncome"),
                              ("ContinuingOperationsNetIncome", "NetIncome"),
                              ("ContinuingOperationsCashFlow", "OperatingCashFlow")):
            if variant in metrics and base in metrics                     and metrics[variant]["data"][-1]["value"] == metrics[base]["data"][-1]["value"]:
                del metrics[variant]

        if metrics:
            newest = max(date.fromisoformat(metric["data"][-1]["end"]) for metric in metrics.values())
            metrics = {
                label: metric for label, metric in metrics.items()
                if (newest - date.fromisoformat(metric["data"][-1]["end"])).days <= self._STALE_METRIC_DAYS
            }

        return {
            "ticker": ticker.upper(),
            "company_name": company_name,
            "cik": cik,
            "metric_count": len(metrics),
            "metrics": metrics,
        }

    def _is_bank(self, cik: str) -> bool:
        """SEC submissions 的 SIC 6020-6039 为商业银行和储蓄机构；取不到时按一般公司处理。"""
        sic = _SIC_CACHE.get(cik)
        if sic is None:
            try:
                sic = str(self._get(f"https://data.sec.gov/submissions/CIK{cik}.json").json().get("sic") or "")
            except httpx.HTTPError:
                return False
            _SIC_CACHE[cik] = sic
        return sic.isdigit() and 6020 <= int(sic) <= 6039

    @staticmethod
    def _filter_periods(
        entries: list[dict], *, instant: bool = False, annual_ends: set[str] | None = None,
        quarterly: bool = False,
    ) -> list[dict]:
        """按实际期间取最新年度（quarterly 时为 10-Q 单季）事实；公告 fy 保留为来源，不猜测事实的 fiscal_year。"""
        annual = {}
        for e in entries:
            if e.get("form") not in ({"10-Q", "10-Q/A"} if quarterly else {"10-K", "10-K/A"}):
                continue
            value = e.get("val")
            if isinstance(value, bool) or not isinstance(value, (int, float)) or not isfinite(value):
                continue
            try:
                end = date.fromisoformat(e["end"])
                filed = date.fromisoformat(e["filed"])
                start = date.fromisoformat(e["start"]) if e.get("start") else None
            except (KeyError, TypeError, ValueError):
                continue
            if instant:
                if start is not None or end.isoformat() not in (annual_ends or set()):
                    continue
            # SEC annual-frame tolerance includes 52/53-week and non-calendar fiscal years.
            # https://www.sec.gov/search-filings/edgar-application-programming-interfaces
            elif start is None or not ((80 <= (end - start).days <= 100) if quarterly
                                       else (335 <= (end - start).days <= 395)):
                continue
            if filed < end:
                continue
            key = (start.isoformat() if start else None, end.isoformat())
            row = {
                "fiscal_year": None,
                "filing_fiscal_year": e.get("fy"),
                "start": key[0], "end": key[1],
                "period_type": "instant" if instant else "duration",
                "filed": filed.isoformat(), "form": e["form"],
                "accn": e.get("accn", ""), "fp": e.get("fp"), "frame": e.get("frame"),
                "value": value,
            }
            annual.setdefault(key, []).append(row)
        result = []
        for versions in annual.values():
            newest = max((row["filed"], row["accn"]) for row in versions)
            latest = [row for row in versions if (row["filed"], row["accn"]) == newest]
            # 同一次公告同一期间的冲突值没有可证明的赢家，不能依赖数组顺序任选。
            if len({row["value"] for row in latest}) == 1:
                result.append(latest[0])
        return sorted(result, key=lambda row: (row["end"], row["start"] or ""))


# 全服务共享一个实例，SEC 限流节奏才对所有路由生效。
edgar_service = EdgarService()
