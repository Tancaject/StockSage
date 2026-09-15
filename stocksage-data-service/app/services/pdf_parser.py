"""
基于 PyMuPDF 的结构感知 PDF 解析器。

相比 Tika + 固定大小 TokenTextSplitter 的改进：
1. 通过字号识别标题，在章节边界切分，避免从句中间切开
2. 切片之间保留重叠，减少边界处上下文损失
3. 保留更丰富的元数据，例如页码和章节标题
"""

import fitz  # PyMuPDF
from statistics import median

MAX_PDF_PAGES = 1000
MAX_TEXT_CHARACTERS = 5_000_000


class PdfLimitError(ValueError):
    """The document exceeds the supported parsing workload."""


class PdfParser:

    def __init__(self, target_chunk_size: int = 3000, overlap: int = 300):
        """
        参数：
            target_chunk_size: 目标切片大小，按字符计，约 750 token。
            overlap: 相邻切片之间的重叠字符数。
        """
        if not 256 <= target_chunk_size <= 12_000 or not 0 <= overlap <= target_chunk_size // 2:
            raise ValueError("PDF chunk_size must be 256–12000 and overlap must be 0–half of chunk_size")
        self.target_chunk_size = target_chunk_size
        self.overlap = overlap

    def parse(self, pdf_bytes: bytes, filename: str = "") -> list[dict]:
        try:
            doc = fitz.open(stream=pdf_bytes, filetype="pdf")
        except fitz.FileDataError as error:
            raise ValueError("Invalid PDF; upload a readable PDF document") from error
        with doc:
            if doc.needs_pass:
                raise ValueError("Password-protected PDF; remove the password before uploading")
            if len(doc) > MAX_PDF_PAGES:
                raise PdfLimitError(f"PDF exceeds {MAX_PDF_PAGES} pages; split the document before uploading")
            blocks = self._extract_blocks(doc)
        if not blocks:
            return [{"content": "", "section_title": "", "page_start": 0,
                     "page_end": 0, "chunk_index": 0,
                     "message": "No text extracted from PDF"}]

        # 步骤 2：根据字号分布检测标题阈值
        sizes = [b["font_size"] for b in blocks]
        body_size = median(sizes)
        heading_threshold = body_size * 1.2  # 比正文大 20% 视为标题

        # 步骤 3：按标题将文本块分组为章节
        sections = self._group_sections(blocks, heading_threshold)

        # 步骤 4：带重叠切分大章节，并合并过小章节
        chunks = self._build_chunks(sections)

        return chunks

    def _extract_blocks(self, doc) -> list[dict]:
        blocks = []
        total_text = 0
        for page_num in range(len(doc)):
            page = doc[page_num]
            # 仅需文本与字号；dict 默认携带图片字节，可能解压巨大的嵌入图片。
            page_dict = page.get_text("dict", flags=fitz.TEXTFLAGS_DICT & ~fitz.TEXT_PRESERVE_IMAGES)

            for block in page_dict.get("blocks", []):
                if block.get("type") != 0:  # 只处理文本块
                    continue

                block_text = ""
                max_size = 0.0
                is_bold = False

                for line in block.get("lines", []):
                    line_text = ""
                    for span in line.get("spans", []):
                        total_text += len(span["text"])
                        if total_text > MAX_TEXT_CHARACTERS:
                            raise PdfLimitError(
                                f"PDF exceeds {MAX_TEXT_CHARACTERS} text characters; split the document before uploading"
                            )
                        line_text += span["text"]
                        max_size = max(max_size, span.get("size", 0))
                        font = span.get("font", "").lower()
                        if "bold" in font or "black" in font or "heavy" in font:
                            is_bold = True
                    block_text += line_text.strip() + "\n"

                text = block_text.strip()
                if text:
                    blocks.append({
                        "text": text,
                        "page": page_num + 1,
                        "font_size": max_size,
                        "is_bold": is_bold,
                    })
        return blocks

    def _group_sections(self, blocks: list[dict], heading_threshold: float) -> list[dict]:
        sections = []
        current = {"title": "", "text": "", "page_start": 1, "page_end": 1}

        for block in blocks:
            is_heading = (
                block["font_size"] >= heading_threshold
                and len(block["text"]) < 200
            )

            if is_heading:
                # 如果当前章节已有内容，则先保存
                if current["text"].strip():
                    sections.append(current)
                # 开始新章节
                current = {
                    "title": block["text"].replace("\n", " ").strip(),
                    "text": "",
                    "page_start": block["page"],
                    "page_end": block["page"],
                }
            else:
                current["text"] += block["text"] + "\n"
                current["page_end"] = block["page"]

        if current["text"].strip():
            sections.append(current)

        return sections

    def _build_chunks(self, sections: list[dict]) -> list[dict]:
        chunks: list[dict] = []
        idx = 0

        for section in sections:
            text = section["text"].strip()
            title = section["title"]

            if not text:
                continue

            if len(text) <= self.target_chunk_size:
                # 当前章节可放入单个切片
                content = f"{title}\n\n{text}" if title else text
                chunks.append({
                    "content": content,
                    "section_title": title,
                    "page_start": section["page_start"],
                    "page_end": section["page_end"],
                    "chunk_index": idx,
                })
                idx += 1
            else:
                # 在段落边界带重叠切分大章节
                for sub in self._split_with_overlap(text, title):
                    sub["page_start"] = section["page_start"]
                    sub["page_end"] = section["page_end"]
                    sub["chunk_index"] = idx
                    chunks.append(sub)
                    idx += 1

        return chunks

    def _split_with_overlap(self, text: str, section_title: str) -> list[dict]:
        """优先按段落边界切分文本，并保留重叠。"""
        paragraphs = [p.strip() for p in text.split("\n") if p.strip()]
        results = []
        current = ""

        for para in paragraphs:
            if len(current) + len(para) + 1 > self.target_chunk_size and current:
                content = f"{section_title}\n\n{current}" if section_title else current
                results.append({"content": content, "section_title": section_title})

                # 保留尾部作为下一切片的重叠内容
                if self.overlap > 0 and len(current) > self.overlap:
                    current = current[-self.overlap:] + "\n" + para
                else:
                    current = para
            else:
                current += ("\n" + para if current else para)

        if current.strip():
            content = f"{section_title}\n\n{current}" if section_title else current
            results.append({"content": content, "section_title": section_title})

        return results
