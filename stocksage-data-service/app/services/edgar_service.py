"""
SEC EDGAR 服务：获取公告索引、解析公告 HTML，并查询 XBRL 数据。

SEC EDGAR 是美国证券交易委员会公开披露文件数据库。
所有 API 均免费使用，不需要 API Key。

注意：SEC 要求 User-Agent 请求头标识调用方。
https://www.sec.gov/os/accessing-edgar-data
"""

import re
import time
import logging
from typing import Optional
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
_ALLOWED_SEC_HOSTS = {"sec.gov", "www.sec.gov", "data.sec.gov"}


class EdgarService:
    """SEC EDGAR 访问与公告解析服务。"""

    def __init__(self):
        """初始化共享 HTTP 客户端，复用连接并统一请求头。"""
        self._client = httpx.Client(headers=_HEADERS, timeout=30, follow_redirects=True)
        self._last_request_time = 0.0

    def _throttle(self):
        """遵守 SEC 限流要求。"""
        elapsed = time.time() - self._last_request_time
        if elapsed < _REQUEST_DELAY:
            time.sleep(_REQUEST_DELAY - elapsed)
        self._last_request_time = time.time()

    def _get(self, url: str) -> httpx.Response:
        """执行带限流和 URL 校验的 SEC GET 请求。"""
        self._validate_sec_url(url)
        self._throttle()
        resp = self._client.get(url)
        resp.raise_for_status()
        self._validate_sec_url(str(resp.url))
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
        这里刻意比完整 HTML 到 Markdown 转换更轻量。
        """
        self._remove_noisy_elements(soup)
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
            title_prefix = re.escape(item_title.split()[0]) if item_title else ""
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
    }

    def get_xbrl(self, ticker: str) -> dict:
        """
        从 XBRL companyfacts API 获取结构化财务数据。

        返回关键财务指标的时间序列数据。
        """
        cik = self._resolve_cik(ticker)
        url = f"https://data.sec.gov/api/xbrl/companyfacts/CIK{cik}.json"
        data = self._get(url).json()

        company_name = data.get("entityName", ticker)
        facts = data.get("facts", {}).get("us-gaap", {})

        metrics = {}
        for label, concept_keys in self._XBRL_CONCEPTS.items():
            best = None
            for concept in concept_keys:
                concept_name = concept.split(":")[-1]
                if concept_name not in facts:
                    continue
                units_data = facts[concept_name].get("units", {})
                for unit_key in ["USD", "USD/shares"]:
                    if unit_key not in units_data:
                        continue
                    annual = self._filter_annual(units_data[unit_key])
                    if not annual:
                        continue
                    # 选择最近财年的概念。
                    latest_fy = annual[-1]["fiscal_year"]
                    if best is None or latest_fy > best["data"][-1]["fiscal_year"]:
                        best = {
                            "concept": concept,
                            "unit": unit_key,
                            "data": annual[-10:],
                        }
            if best:
                metrics[label] = best

        return {
            "ticker": ticker.upper(),
            "company_name": company_name,
            "cik": cik,
            "metric_count": len(metrics),
            "metrics": metrics,
        }

    @staticmethod
    def _filter_annual(entries: list[dict]) -> list[dict]:
        """筛选 XBRL 条目，只保留 10-K 年度公告，并按财年去重。"""
        annual = {}
        for e in entries:
            form = e.get("form", "")
            if form != "10-K":
                continue
            fy = e.get("fy")
            if fy is None:
                continue
            # 每个财年保留最新公告，修订版覆盖原版。
            key = f"{fy}"
            annual[key] = {
                "fiscal_year": fy,
                "filed": e.get("filed", ""),
                "value": e.get("val"),
            }
        return sorted(annual.values(), key=lambda x: x["fiscal_year"])
