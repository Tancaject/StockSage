"""
文档解析 API。

通过 PyMuPDF 提供结构感知的 PDF 解析能力，
由 Java 后端的 DocumentLoader 在知识库摄取期间调用。
"""

from fastapi import APIRouter, File, Form, HTTPException, UploadFile
from anyio import CapacityLimiter, fail_after, to_process

from app.services.pdf_parser import PdfLimitError, PdfParser

router = APIRouter()
MAX_PDF_BYTES = 20 * 1024 * 1024
READ_CHUNK_BYTES = 1024 * 1024
MAX_PARSE_SECONDS = 30
_PDF_PROCESS_LIMITER = CapacityLimiter(1)


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
