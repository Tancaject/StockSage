"""Download FY2025 10-K statement pages from SEC EDGAR and extract them into evidence text.

Each issuer keeps the SEC-rendered XBRL statement page (R*.htm) and a row-wise text extraction:
statement title, column headers, then `label | value | value ...`. A sidecar JSON records each
row's XBRL concept so prepare_dataset.py can cross-check values against SEC companyfacts.
Existing downloads are never overwritten.
"""
from __future__ import annotations

import hashlib
import html
import json
import re
import time
import urllib.request
from datetime import datetime, timezone
from html.parser import HTMLParser
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SOURCES = ROOT / "sources"
HEADERS = {"User-Agent": "StockSage Research contact@stocksage.dev"}
STATEMENTS = ("income", "balance", "cashflow")
# 10-K accession and the three statement pages, read from each filing's FilingSummary.xml.
FILINGS = {
    "CVX": (93410, "0000093410-26-000078", "2026-02-24", {"income": "R3", "balance": "R5", "cashflow": "R7"}),
    "CAT": (18230, "0000018230-26-000008", "2026-02-13", {"income": "R3", "balance": "R5", "cashflow": "R9"}),
    "LLY": (59478, "0000059478-26-000013", "2026-02-12", {"income": "R3", "balance": "R5", "cashflow": "R9"}),
    "MCD": (63908, "0000063908-26-000035", "2026-02-24", {"income": "R3", "balance": "R7", "cashflow": "R9"}),
    "NFLX": (1065280, "0001065280-26-000034", "2026-01-23", {"income": "R3", "balance": "R6", "cashflow": "R5"}),
    "INTC": (50863, "0000050863-26-000011", "2026-01-23", {"income": "R3", "balance": "R5", "cashflow": "R7"}),
    "ABBV": (1551152, "0001551152-26-000008", "2026-02-20", {"income": "R3", "balance": "R6", "cashflow": "R9"}),
    "HON": (773840, "0000773840-26-000013", "2026-02-17", {"income": "R3", "balance": "R5", "cashflow": "R7"}),
    "VZ": (732712, "0000732712-26-000007", "2026-02-17", {"income": "R3", "balance": "R6", "cashflow": "R8"}),
    "UBER": (1543151, "0001543151-26-000015", "2026-02-13", {"income": "R5", "balance": "R3", "cashflow": "R8"}),
    "BA": (12927, "0001628280-26-004357", "2026-01-30", {"income": "R3", "balance": "R6", "cashflow": "R8"}),
    "UNH": (731766, "0000731766-26-000062", "2026-03-02", {"income": "R5", "balance": "R3", "cashflow": "R9"}),
}


def get(url: str) -> bytes:
    time.sleep(0.2)  # SEC fair-access limit is 10 requests per second
    return urllib.request.urlopen(urllib.request.Request(url, headers=HEADERS), timeout=60).read()


def filing_url(ticker: str, page: str) -> str:
    cik, accession, _, _ = FILINGS[ticker]
    return f"https://www.sec.gov/Archives/edgar/data/{cik}/{accession.replace('-', '')}/{page}.htm"


class ReportTable(HTMLParser):
    """Collect the rows of an SEC R-page `table.report`, with the XBRL concept of each row."""

    def __init__(self) -> None:
        super().__init__()
        self.rows: list[dict] = []
        self.row: dict | None = None
        self.cell: list[str] | None = None
        self.depth = 0  # nested footnote tables are skipped
        self.done = False  # XBRL definition tables follow the report table

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if self.done:
            return
        if tag == "table":
            self.depth += 1
            if self.depth == 1 and "report" not in (attrs.get("class") or "").split():
                self.depth, self.done = 0, bool(self.rows)
                return
        if self.depth != 1:
            return
        if tag == "tr":
            self.row = {"cells": [], "concept": None}
        elif tag in ("td", "th") and self.row is not None:
            self.cell = []
        elif tag == "a" and self.row is not None and "onclick" in attrs:
            found = re.search(r"defref_([A-Za-z0-9_-]+)", attrs["onclick"] or "")
            if found and self.row["concept"] is None:
                self.row["concept"] = found.group(1).replace("_", ":", 1)
        elif tag == "br" and self.cell is not None:
            self.cell.append(" ")

    def handle_endtag(self, tag):
        if self.done:
            return
        if tag == "table":
            self.depth -= 1
            self.done = self.depth == 0 and bool(self.rows)
        if self.depth != 1:
            return
        if tag in ("td", "th") and self.cell is not None and self.row is not None:
            self.row["cells"].append(re.sub(r"\s+", " ", "".join(self.cell)).strip())
            self.cell = None
        elif tag == "tr" and self.row is not None:
            if any(self.row["cells"]):
                self.rows.append(self.row)
            self.row = None

    def handle_data(self, data):
        if self.cell is not None and self.depth == 1 and not self.done:
            self.cell.append(data)


def extract(page: bytes) -> tuple[str, list[dict]]:
    parser = ReportTable()
    parser.feed(page.decode("utf-8", errors="strict"))
    rows = [row for row in parser.rows if row["cells"]]
    lines = [" | ".join(cell for cell in row["cells"]) for row in rows]
    return "\n".join(lines) + "\n", rows


def main() -> int:
    SOURCES.mkdir(exist_ok=True)
    index = {}
    for ticker, (cik, accession, filed, pages) in FILINGS.items():
        for statement in STATEMENTS:
            stem = f"{ticker}-fy2025-{statement}"
            download, text_path, rows_path = (SOURCES / f"{stem}.htm", SOURCES / f"{stem}.txt", SOURCES / f"{stem}.rows.json")
            url = filing_url(ticker, pages[statement])
            if not download.exists():
                download.write_bytes(get(url))
                retrieved = datetime.now(timezone.utc).isoformat()
            else:
                retrieved = json.loads((SOURCES / "index.json").read_text(encoding="utf-8"))[stem]["retrievedAt"]
            text, rows = extract(download.read_bytes())
            if not text_path.exists():
                text_path.write_text(text, encoding="utf-8", newline="\n")
                rows_path.write_text(json.dumps(rows, ensure_ascii=False, indent=1) + "\n", encoding="utf-8", newline="\n")
            elif text_path.read_text(encoding="utf-8") != text:
                raise SystemExit("Extraction changed for an existing source: " + stem)
            index[stem] = {"ticker": ticker, "cik": cik, "accession": accession, "filedAt": filed, "statement": statement,
                           "sourceRef": url, "retrievedAt": retrieved,
                           "downloadSha256": hashlib.sha256(download.read_bytes()).hexdigest(),
                           "title": html.unescape(text.splitlines()[0])}
            print(stem, len(text), "chars")
    (SOURCES / "index.json").write_text(json.dumps(index, ensure_ascii=False, indent=1) + "\n", encoding="utf-8", newline="\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
