"""Read bounded official disclosures and compare extracted blocks without a model."""
import hashlib
import ipaddress
import re
import socket
from datetime import datetime, timezone
from difflib import SequenceMatcher
from urllib.parse import unquote, urlparse

import httpx
from bs4 import BeautifulSoup, Tag

from app.research_budget import bounded_timeout, check_budget
from app.services.edgar_service import edgar_service, _HEADERS
from app.services.pdf_parser import PdfParser, MAX_TEXT_CHARACTERS

MAX_DOWNLOAD_BYTES = 20 * 1024 * 1024
# Exact official static hosts and disclosure paths, not a general website fetcher.
OFFICIAL_PATHS = {
    "www.sec.gov": r"/Archives/edgar/data/[0-9]+/[0-9]{18}/[^/]+\.(?:htm|html|pdf)",
    "static.cninfo.com.cn": r"/finalpage/[0-9-]+/[^/]+\.pdf",
    "static.sse.com.cn": r"/disclosure/listedinfo/announcement/[^?]+\.pdf",
    "disc.static.szse.cn": r"/download/disc/[^?]+\.pdf",
    "www1.hkexnews.hk": r"/listedco/listconews/[^?]+\.(?:pdf|htm|html)",
    "www.hkexnews.hk": r"/listedco/listconews/[^?]+\.(?:pdf|htm|html)",
}


def validate_url(url: str, *, resolve: bool = True) -> str:
    parsed = urlparse(url)
    path = unquote(unquote(parsed.path))
    host = (parsed.hostname or "").lower()
    if parsed.scheme != "https" or host not in OFFICIAL_PATHS or parsed.username is not None \
            or parsed.password is not None or parsed.port not in (None, 443) \
            or any(part in {".", ".."} for part in path.split("/")) or "\\" in path or "%" in path \
            or any(ord(char) < 32 for char in url) \
            or not re.fullmatch(OFFICIAL_PATHS.get(host, r"(?!)"), path, re.IGNORECASE):
        raise ValueError("Only HTTPS official SEC/CNINFO/SSE/SZSE/HKEX disclosure URLs on port 443 are supported")
    if resolve:
        addresses = socket.getaddrinfo(host, 443, type=socket.SOCK_STREAM)
        if not addresses or any(not ipaddress.ip_address(item[4][0]).is_global for item in addresses):
            raise ValueError("Official disclosure hostname resolved to a non-public address; request refused")
    return parsed._replace(fragment="").geturl()


def download(url: str) -> tuple[bytes, str, str]:
    url = validate_url(url)
    if urlparse(url).hostname == "www.sec.gov":
        edgar_service._throttle()
    content = bytearray()
    with httpx.Client(headers=_HEADERS, follow_redirects=False, timeout=bounded_timeout(25)) as client:
        with client.stream("GET", url) as response:
            if 300 <= response.status_code < 400:
                raise ValueError("Disclosure URL redirected; use the final official document URL")
            response.raise_for_status()
            length = response.headers.get("content-length")
            if length and int(length) > MAX_DOWNLOAD_BYTES:
                raise ValueError("Disclosure exceeds 20 MB; use a smaller official document")
            for chunk in response.iter_bytes():
                check_budget()
                content.extend(chunk)
                if len(content) > MAX_DOWNLOAD_BYTES:
                    raise ValueError("Disclosure exceeds 20 MB; use a smaller official document")
            return bytes(content), response.headers.get("content-type", ""), url


def _clean(text: str) -> str:
    return re.sub(r"\s+", " ", text or "").strip()


def _html_blocks(content: bytes) -> list[dict]:
    soup = BeautifulSoup(content, "html.parser")
    edgar_service._remove_noisy_elements(soup)
    blocks, section, part = [], "Full Document", ""
    for node in (soup.body or soup).descendants:
        if not isinstance(node, Tag) or node.find_parent("table"):
            continue
        name = (node.name or "").lower()
        if name not in {"table", "h1", "h2", "h3", "h4", "h5", "h6", "p", "div", "li", "section", "article", "pre"}:
            continue
        if name != "table" and node.find(["p", "div", "table", "section", "article", "h1", "h2", "h3", "h4", "h5", "h6", "li"]):
            continue
        text = _clean(node.get_text(" ", strip=True))
        if not text:
            continue
        is_part = re.fullmatch(r"PART\s+[IVX]+(?:\s*[-—:]\s*.*)?", text, re.IGNORECASE)
        is_item = len(text) < 250 and re.match(r"Item\s+\d+[A-Z]?[.\s:]", text, re.IGNORECASE)
        heading = name in {"h1", "h2", "h3", "h4", "h5", "h6"} or is_part or is_item \
            or len(text) < 120 and re.match(r"第[一二三四五六七八九十百0-9]+[节章]", text)
        if heading:
            if is_part:
                part, section = text, text
            else:
                section = f"{part} — {text}" if part and is_item else text
            blocks.append({"kind": "HEADING", "section": section, "text": text})
        elif name == "table":
            rows = [[_clean(cell.get_text(" ", strip=True)) for cell in row.find_all(["td", "th"], recursive=False)]
                    for row in node.find_all("tr") if row.find_parent("table") is node]
            rows = [row for row in rows if row]
            if rows and any(cell for row in rows for cell in row):
                blocks.append({"kind": "TABLE", "section": section, "rows": rows,
                               "text": "\n".join(" | ".join(row) for row in rows)})
        else:
            blocks.append({"kind": "PARAGRAPH", "section": section, "text": text})
    return blocks


def parse_document(content: bytes, content_type: str, url: str, filing_type: str) -> dict:
    if len(content) > MAX_DOWNLOAD_BYTES:
        raise ValueError("Disclosure exceeds 20 MB")
    if content.lstrip().startswith(b"%PDF-"):
        blocks = PdfParser().disclosure_blocks(content)
        format_name = "PDF"
    elif "html" in content_type.lower() or re.search(br"<!doctype\s+html|<html[\s>]", content[:1024], re.IGNORECASE):
        blocks = _html_blocks(content)
        format_name = "HTML"
    else:
        raise ValueError("Disclosure is not a readable HTML/PDF document; use the direct official document URL")
    if sum(len(block["text"]) for block in blocks) > MAX_TEXT_CHARACTERS:
        raise ValueError("Disclosure text exceeds the parsing limit; use a smaller document")
    for index, block in enumerate(blocks):
        block["id"] = f"b{index + 1:05d}"
    return {"schemaVersion": 1, "status": "SUCCESS" if blocks else "EMPTY", "provider": "OFFICIAL_DISCLOSURE",
            "documentUrl": url, "documentHash": hashlib.sha256(content).hexdigest(), "filingType": filing_type,
            "format": format_name, "fetchedAt": datetime.now(timezone.utc).isoformat(), "blocks": blocks,
            "extractionNotes": "PDF text and table detection do not perform OCR; an undetected table is not evidence of no disclosure."
            if format_name == "PDF" else "HTML table cells retain source order; merged cells are not inferred."}


def _selection(document: dict, section: str, tables_only: bool = False) -> tuple[list[dict], list[str]]:
    blocks = document["blocks"]
    if section.startswith("block:"):
        blocks = [block for block in blocks if block["id"] == section[6:]]
    elif section.strip():
        query = _clean(section).casefold()
        names = list(dict.fromkeys(block["section"] for block in blocks))
        exact = [name for name in names if _clean(name).casefold() == query]
        matches = exact or [name for name in names if query in _clean(name).casefold()]
        if len(matches) > 1:
            return [], matches
        blocks = [block for block in blocks if block["section"] in matches
                  or any(block["section"].startswith(name + " — ") for name in exact)]
    if tables_only:
        blocks = [block for block in blocks if block["kind"] == "TABLE"]
    return blocks, []


def read_result(document: dict, section: str, tables_only: bool, offset: int, max_chars: int) -> dict:
    blocks, ambiguous = _selection(document, section, tables_only)
    result = {key: value for key, value in document.items() if key != "blocks"}
    sections = list(dict.fromkeys(block["section"] for block in document["blocks"]))
    result.update(section=section, tablesOnly=tables_only, offset=offset, blocks=[], availableSections=sections[:100],
                  sectionsTruncated=len(sections) > 100, truncated=False, nextOffset=None)
    if ambiguous:
        return {**result, "status": "AMBIGUOUS_SECTION", "message": "Section matches multiple headings; select one availableSections value",
                "availableSections": ambiguous[:100], "sectionsTruncated": len(ambiguous) > 100}
    total = sum(len(block["text"]) for block in blocks)
    result["sourceChars"] = total
    if not blocks or offset >= total:
        return {**result, "status": "EMPTY", "message": "No extracted content at this section/offset; inspect availableSections or the original document"}
    position, end = 0, min(total, offset + max_chars)
    for block in blocks:
        stop = position + len(block["text"])
        if stop > offset and position < end:
            start_in_block, end_in_block = max(0, offset - position), min(len(block["text"]), end - position)
            partial = start_in_block != 0 or end_in_block != len(block["text"])
            row = {**block, "text": block["text"][start_in_block:end_in_block], "charStart": position,
                   "charEnd": stop, "textOffset": start_in_block, "partial": partial}
            if partial:
                row.pop("rows", None)
            result["blocks"].append(row)
            if len(result["blocks"]) == 100:
                end = min(end, stop)
                break
        position = stop
    result.update(status="SUCCESS", returnedChars=end - offset, truncated=offset > 0 or end < total,
                  nextOffset=end if end < total else None)
    return result


def compare_documents(older: dict, newer: dict, section: str, max_changes: int) -> dict:
    def source(document):
        return {key: document[key] for key in ("documentUrl", "documentHash", "filingType", "fetchedAt")}
    before, old_ambiguous = _selection(older, section)
    after, new_ambiguous = _selection(newer, section)
    result = {"schemaVersion": 1, "provider": "OFFICIAL_DISCLOSURE_DIFF", "older": source(older), "newer": source(newer),
              "section": section, "changes": [], "truncated": False,
              "method": "Ordered block comparison after whitespace normalization; additions/removals do not establish a change of management intent."}
    if old_ambiguous or new_ambiguous:
        return {**result, "status": "AMBIGUOUS_SECTION", "availableSections": {"older": old_ambiguous[:100], "newer": new_ambiguous[:100]},
                "sectionsTruncated": max(len(old_ambiguous), len(new_ambiguous)) > 100}
    if not before or not after:
        return {**result, "status": "EMPTY", "message": "Selected content is missing from at least one document; no unchanged conclusion is available"}
    if max(len(before), len(after)) > 5000:
        raise ValueError("Too many blocks to compare; select a specific section")
    key = lambda block: (block["kind"], _clean(block["section"]).casefold(), _clean(block["text"]))
    changes = []
    for operation, a1, a2, b1, b2 in SequenceMatcher(None, list(map(key, before)), list(map(key, after)), autojunk=False).get_opcodes():
        if operation == "equal":
            continue
        for kind, items, document in (("REMOVED", before[a1:a2], older), ("ADDED", after[b1:b2], newer)):
            for block in items:
                changes.append({"kind": kind, "blockId": block["id"], "section": block["section"], "blockKind": block["kind"],
                                "documentUrl": document["documentUrl"], "documentHash": document["documentHash"],
                                "pageStart": block.get("pageStart"), "pageEnd": block.get("pageEnd"),
                                "text": block["text"][:2000], "sourceChars": len(block["text"]), "partial": len(block["text"]) > 2000})
    selected = changes[:max_changes]
    return {**result, "status": "SUCCESS", "totalChanges": len(changes), "changes": selected,
            "truncated": len(selected) < len(changes) or any(change["partial"] for change in selected)}
