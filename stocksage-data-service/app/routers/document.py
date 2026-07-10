"""
文档解析 API。

通过 PyMuPDF 提供结构感知的 PDF 解析能力，
由 Java 后端的 DocumentLoader 在知识库摄取期间调用。
"""

from fastapi import APIRouter, File, Form, HTTPException, UploadFile

from app.services.pdf_parser import PdfParser

router = APIRouter()
MAX_PDF_BYTES = 20 * 1024 * 1024
READ_CHUNK_BYTES = 1024 * 1024


@router.post("/parse")
async def parse_pdf(
    file: UploadFile = File(...),
    chunk_size: int = Form(3000),
    overlap: int = Form(300),
):
    """将 PDF 解析为带章节元数据的结构化重叠切片。"""
    content = await _read_limited_upload(file)
    parser = PdfParser(target_chunk_size=chunk_size, overlap=overlap)
    chunks = parser.parse(content, filename=file.filename or "")
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
