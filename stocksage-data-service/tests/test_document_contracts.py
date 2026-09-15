import asyncio
import sys
import unittest
from unittest.mock import patch

import fitz
import httpx
from fastapi import FastAPI

from app.routers import document
from app.services.pdf_parser import MAX_PDF_PAGES, PdfLimitError, PdfParser


def pdf_bytes(pages):
    with fitz.open() as pdf:
        for _ in range(pages):
            pdf.new_page().insert_text((72, 72), "StockSage annual filing evidence.")
        return pdf.tobytes()


class DocumentContractsTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        # AnyIO imports __main__ in its worker; unittest's package entrypoint cannot be loaded as a file.
        main_path = patch.object(sys.modules["__main__"], "__file__", None)
        main_path.start()
        self.addCleanup(main_path.stop)

    async def test_real_pdf_parse_runs_outside_event_loop_and_limits_are_user_visible(self):
        app = FastAPI()
        app.include_router(document.router)
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
            task = asyncio.create_task(client.post("/parse", files={"file": ("report.pdf", pdf_bytes(100))}))
            ticks = 0
            while not task.done():
                await asyncio.sleep(0.005)
                if not task.done():
                    ticks += 1
            response = await task
            self.assertEqual(200, response.status_code, response.text)
            self.assertGreater(ticks, 0)
            self.assertIn("annual filing evidence", response.json()["chunks"][0]["content"])

            response = await client.post("/parse", files={"file": ("large.pdf", pdf_bytes(MAX_PDF_PAGES + 1))})
            self.assertEqual(413, response.status_code, response.text)
            self.assertIn("split the document", response.json()["detail"])
            response = await client.post("/parse", files={"file": ("invalid.pdf", b"not a PDF")})
            self.assertEqual(422, response.status_code)
            with patch.object(document, "MAX_PARSE_SECONDS", 0):
                response = await client.post("/parse", files={"file": ("report.pdf", pdf_bytes(1))})
            self.assertEqual(504, response.status_code, response.text)

    def test_text_budget_and_overlap_are_bounded(self):
        with patch("app.services.pdf_parser.MAX_TEXT_CHARACTERS", 10):
            with self.assertRaises(PdfLimitError):
                PdfParser().parse(pdf_bytes(1))
        with self.assertRaises(ValueError):
            PdfParser(target_chunk_size=3000, overlap=3000)


if __name__ == "__main__":
    unittest.main()
