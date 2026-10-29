"""
文档解析 API。

通过 PyMuPDF 提供结构感知的 PDF 解析能力，
由 Java 后端的 DocumentLoader 在知识库摄取期间调用。
"""

from fastapi import APIRouter, File, Form, HTTPException, UploadFile
from anyio import CapacityLimiter, fail_after, to_process
from anyio import to_thread
from pydantic import BaseModel, ConfigDict, Field, StrictBool, StrictInt, StrictStr

from app.services.pdf_parser import PdfLimitError, PdfParser
from app.services import disclosure_reader
from app.research_budget import bounded_timeout, check_budget

router = APIRouter()
MAX_PDF_BYTES = 20 * 1024 * 1024
READ_CHUNK_BYTES = 1024 * 1024
MAX_PARSE_SECONDS = 30
_PDF_PROCESS_LIMITER = CapacityLimiter(1)


class DisclosureReadRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    url: StrictStr = Field(min_length=1, max_length=2000)
    filing_type: StrictStr = Field(default="DISCLOSURE", max_length=30)
    section: StrictStr = Field(default="", max_length=250)
    tables_only: StrictBool = False
    offset: StrictInt = Field(default=0, ge=0)
    max_chars: StrictInt = Field(default=12000, ge=256, le=24000)


class DisclosureCompareRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    older_url: StrictStr = Field(min_length=1, max_length=2000)
    newer_url: StrictStr = Field(min_length=1, max_length=2000)
    filing_type: StrictStr = Field(default="DISCLOSURE", max_length=30)
    section: StrictStr = Field(default="", max_length=250)
    max_changes: StrictInt = Field(default=20, ge=1, le=50)


async def _read_disclosure(url: str, filing_type: str) -> dict:
    content, content_type, final_url = await to_thread.run_sync(disclosure_reader.download, url)
    with fail_after(bounded_timeout(MAX_PARSE_SECONDS)):
        return await to_process.run_sync(disclosure_reader.parse_document, content, content_type, final_url, filing_type,
                                         cancellable=True, limiter=_PDF_PROCESS_LIMITER)


@router.post("/disclosure/read")
async def read_disclosure(request: DisclosureReadRequest):
    """Read an official SEC/A-share/HK disclosure by exact source, section or block ID."""
    try:
        document = await _read_disclosure(request.url, request.filing_type)
        return disclosure_reader.read_result(document, request.section, request.tables_only, request.offset, request.max_chars)
    except Exception as error:
        check_budget(error)
        return {"schemaVersion": 1, "status": "ERROR", "error": True, "provider": "OFFICIAL_DISCLOSURE",
                "documentUrl": request.url, "message": f"Disclosure read failed: {type(error).__name__}: {error}"}


@router.post("/disclosure/compare")
async def compare_disclosures(request: DisclosureCompareRequest):
    """Compare complete selected sections, then limit the returned additions/removals."""
    try:
        older = await _read_disclosure(request.older_url, request.filing_type)
        newer = await _read_disclosure(request.newer_url, request.filing_type)
        with fail_after(bounded_timeout(MAX_PARSE_SECONDS)):
            return await to_process.run_sync(disclosure_reader.compare_documents, older, newer, request.section, request.max_changes,
                                             cancellable=True, limiter=_PDF_PROCESS_LIMITER)
    except Exception as error:
        check_budget(error)
        return {"schemaVersion": 1, "status": "ERROR", "error": True, "provider": "OFFICIAL_DISCLOSURE_DIFF",
                "message": f"Disclosure comparison failed: {type(error).__name__}: {error}"}


@router.post("/parse")
async def parse_pdf(
    file: UploadFile = File(...),
    chunk_size: int = Form(3000),
    overlap: int = Form(300),
):
    """将 PDF 解析为带章节元数据的结构化重叠切片。"""
    content = await _read_limited_upload(file)
    try:
        parser = PdfParser(target_chunk_size=chunk_size, overlap=overlap)
        # PyMuPDF 不支持多线程；已有 AnyIO 进程执行器隔离解析并允许超时终止 worker。
        with fail_after(MAX_PARSE_SECONDS):
            chunks = await to_process.run_sync(
                parser.parse, content, file.filename or "",
                cancellable=True, limiter=_PDF_PROCESS_LIMITER,
            )
    except PdfLimitError as error:
        raise HTTPException(status_code=413, detail=str(error)) from error
    except ValueError as error:
        raise HTTPException(status_code=422, detail=str(error)) from error
    except TimeoutError as error:
        raise HTTPException(
            status_code=504,
            detail=f"PDF parsing exceeded {MAX_PARSE_SECONDS}s; split the document and retry",
        ) from error
    return {
        "filename": file.filename,
        "total_chunks": len(chunks),
        "chunks": chunks,
    }


async def _read_limited_upload(file: UploadFile) -> bytes:
    chunks: list[bytes] = []
    total = 0
    while True:
        chunk = await file.read(READ_CHUNK_BYTES)
        if not chunk:
            break
        total += len(chunk)
        if total > MAX_PDF_BYTES:
            raise HTTPException(status_code=413, detail="PDF file is too large; max size is 20 MB")
        chunks.append(chunk)
    return b"".join(chunks)
