"""Exercise preflight over local HTTP only; never call a real provider."""
from __future__ import annotations

import hashlib
import json
import os
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "evals"))
import eval_preflight as preflight


def main():
    calls = []

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_GET(self):
            self.respond()

        def do_POST(self):
            self.respond()

        def respond(self):
            parsed = urlsplit(self.path)
            body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or "null")
            params = parse_qs(parsed.query)
            calls.append({"method": self.command, "path": parsed.path, "params": params, "body": body,
                          "authorization_present": "Authorization" in self.headers})
            response, status = {}, 200
            if parsed.path == "/data/health":
                response = {"status": "ok", "dataSources": {"baostock": {"status": "retrying"}}}
            elif parsed.path == "/data/api/stock/financial":
                assert params == {"code": ["sh.600519"]}, params
                response = {"code": "sh.600519", "metrics": {"roeAvg": "15.2"}}
            elif parsed.path == "/data/api/stock/financial-report":
                assert params == {"code": ["0700.HK"], "period": ["annual"], "years": ["1"]}, params
                response = {"status": "PARTIAL", "provider": "akshare", "error": False,
                            "statements": {"incomeStatement": {"status": "SUCCESS", "data": [{"OPERATE_INCOME": 12}]}}}
            elif parsed.path == "/data/api/edgar/filings":
                assert params == {"ticker": ["AAPL"], "type": ["10-K"], "count": ["1"]}, params
                response = {"count": 1, "filings": [{"document_url": "https://www.sec.gov/mock.htm", "filing_type": "10-K"}]}
            elif parsed.path == "/ollama/api/embed":
                assert self.command == "POST" and body == {"model": "fixture-embedding", "input": "probe"}
                response = {"embeddings": [[0.1, 0.2]]}
            elif parsed.path == "/ibkr/v1/api/iserver/auth/status":
                assert self.command == "POST" and body == {}
                response = {"authenticated": True, "connected": True}
            elif parsed.path in {"/deepseek/models", "/bailian/v1/models"}:
                assert self.command == "GET" and "Authorization" in self.headers
                response = {"object": "list", "data": [{"id": "fixture-model"}]}
            elif parsed.path == "/data/api/search/web":
                response = {"status": "SUCCESS", "provider": "ddg", "fallbackFrom": "tavily",
                            "fallbackReason": "UPSTREAM_ERROR", "results": [{"title": "Fallback result"}]}
            elif parsed.path == "/invalid":
                self.send_response(200)
                self.end_headers()
                self.wfile.write(b"not JSON")
                return
            else:
                status, response = 404, {"error": True}
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps(response).encode())

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    base = f"http://127.0.0.1:{server.server_port}"
    values = {"STOCKSAGE_DEEPSEEK_API_KEY": "fixture-key", "DASHSCOPE_API_KEY": "fixture-key",
              "STOCKSAGE_DEEPSEEK_BASE_URL": base + "/deepseek", "STOCKSAGE_OPENAI_BASE_URL": base + "/bailian/v1",
              "NO_PROXY": "127.0.0.1,localhost"}
    previous = {key: os.environ.get(key) for key in values}
    try:
        os.environ.update(values)
        result = preflight.run(data_url=base + "/data/", ollama_url=base + "/ollama/",
                               embedding_model="fixture-embedding", ibkr_gateway=base + "/ibkr")
        rows = result["providers"]
        assert all(rows[name]["status"] == "AVAILABLE" for name in ("data_service", "ollama", "baostock", "sec", "ibkr")), rows
        assert rows["akshare"]["status"] == "UNKNOWN" and rows["akshare"]["reason"] == "PARTIAL_DATA", rows
        assert all(rows[name]["status"] == "UNKNOWN" for name in ("deepseek", "bailian")), rows
        assert rows["tavily"]["status"] == "SKIPPED" and not result["generation_checked"]
        assert len(calls) == 8 and all("chat/completions" not in call["path"] for call in calls), calls
        assert not any("search" in call["path"] for call in calls), calls
        fallback = preflight.probe("tavily", base + "/data/api/search/web")
        assert fallback["status"] != "AVAILABLE" and "FALLBACK" in fallback["reason"], fallback
        assert preflight.probe("sec", base + "/invalid")["reason"] == "INVALID_RESPONSE"
        assert preflight.probe("sec", base + "/missing")["reason"] == "HTTP_404"
        for provider, payload in (("sec", [1]), ("sec", {"filings": [1]}),
                                  ("sec", {"filings": [{"document_url": True}]}),
                                  ("akshare", {"status": "SUCCESS", "statements": {}}),
                                  ("akshare", {"status": "SUCCESS", "statements": [1]}),
                                  ("tavily", {"provider": "tavily", "status": "SUCCESS", "results": [1]}),
                                  ("baostock", {"metrics": {"code": "sh.600519", "statDate": "2025-12-31"}}),
                                  ("ollama", {"embeddings": [[]]}), ("ibkr", {}), ("data_service", [])):
            assert preflight.classify_payload(provider, payload)[0] == "UNKNOWN", (provider, payload)
        assert preflight.classify_payload("ibkr", {"authenticated": False}) == ("UNAVAILABLE", "LOGIN_REQUIRED")
        assert preflight.classify_payload("ibkr", {"authenticated": True, "connected": False})[0] != "AVAILABLE"
        assert preflight.classify_payload("baostock", {"error": True, "errorCode": "LOGIN_FAILED"}) == ("UNAVAILABLE", "LOGIN_FAILED")
        assert preflight.classify_payload("tavily", {"provider": "tavily", "status": "SUCCESS", "results": [{"title": "A"}]})[0] == "AVAILABLE"
        assert "fixture-key" not in json.dumps(result)
        output = {"schema": "preflight_local_http_check_v1", "status": "PASS", "external_requests": 0,
                  "helper_sha256": hashlib.sha256((ROOT / "evals/eval_preflight.py").read_bytes()).hexdigest(),
                  "requests": calls, "result": result, "search_fallback": fallback}
        Path(__file__).with_name("results.json").write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({"status": "PASS", "local_requests": len(calls), "external_requests": 0}))
    finally:
        for key, value in previous.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value
        server.shutdown()
        server.server_close()
        thread.join()


if __name__ == "__main__":
    main()
