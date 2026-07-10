from pathlib import Path

from playwright.sync_api import sync_playwright, expect


ROOT = Path(__file__).resolve().parents[1]
SCREENSHOT = ROOT / "tmp" / "webapp-workbench-sample.png"


COCKPIT_SAMPLE = {
    "ticker": "NVDA",
    "chart": {
        "chartType": "candlestick",
        "sourceTool": "offlineDemoSample",
        "title": "NVDA offline sample K-line",
        "symbol": "NVDA",
        "period": "daily",
        "provider": "StockSage offline sample",
        "points": [
            {"date": "2026-06-03", "open": 126.0, "high": 129.6, "low": 125.3, "close": 128.8, "volume": 489000000},
            {"date": "2026-06-04", "open": 129.0, "high": 131.2, "low": 127.5, "close": 130.1, "volume": 501000000},
            {"date": "2026-06-05", "open": 130.4, "high": 132.8, "low": 128.9, "close": 131.6, "volume": 477000000},
        ],
    },
    "chartStatus": "SAMPLE",
    "chartMessage": "Loaded offline sample cockpit. Live market, SEC, and model APIs were not required.",
    "latestReport": {
        "ticker": "NVDA",
        "reportVersion": 1,
        "recommendation": "HOLD",
        "dataSnapshotHash": "d" * 64,
        "contextHash": "c" * 64,
        "modelTier": "DEMO",
        "modelName": "offline-rule-fallback",
        "generatedAt": "2026-06-05T10:00:00",
        "preview": "offline demo fallback report",
        "citations": ["[offline sample] deterministic fixture"],
    },
    "taskTimeline": [
        {
            "ticker": "NVDA",
            "status": "SUCCEEDED",
            "stage": "COMPLETE",
            "attempts": 1,
            "completedAt": "2026-06-05T10:00:00",
        }
    ],
    "evidencePreview": [
        {
            "dimension": "Price trend",
            "evidence": "The sample close rises across the fixture window.",
            "implication": "Demonstrates the chart-first cockpit without live providers.",
            "source": "offline-sample",
        }
    ],
    "generatedAt": "2026-06-05T10:00:00",
}


def main():
    SCREENSHOT.parent.mkdir(parents=True, exist_ok=True)
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True)
        page = browser.new_page(viewport={"width": 1440, "height": 960})

        page.route("**/api/workbench/stocks/**/cockpit**", lambda route: route.fulfill(json=COCKPIT_SAMPLE))
        page.route("**/api/reports/investment**", lambda route: route.fulfill(json=[]))
        page.route("**/api/chat/conversations**", lambda route: route.fulfill(json=[]))
        page.route("**/api/user/me/profile**", lambda route: route.fulfill(json={}))
        page.route("**/api/docs/search**", lambda route: route.fulfill(json=[]))

        page.goto("http://127.0.0.1:5173/workbench")
        page.wait_for_load_state("networkidle", timeout=15000)

        status = page.locator(".chart-status-strip .status-pill.sample")
        expect(status).to_have_text("SAMPLE")
        expect(page.locator(".chart-cockpit-panel .kline-panel svg")).to_have_count(1)

        brief_text = page.locator(".report-brief-card").inner_text(timeout=10000)
        assert "NVDA v1" in brief_text
        assert "offline-rule-fallback" in brief_text

        task_text = page.locator(".task-list").inner_text(timeout=10000)
        assert "SUCCEEDED" in task_text
        assert "COMPLETE" in task_text

        evidence_text = page.locator(".evidence-list").inner_text(timeout=10000).lower()
        assert "price trend" in evidence_text
        assert "offline-sample" in evidence_text

        page.screenshot(path=str(SCREENSHOT), full_page=True)
        browser.close()

    print(f"webapp workbench SAMPLE smoke passed; screenshot={SCREENSHOT}")


if __name__ == "__main__":
    main()
