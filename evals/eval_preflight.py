"""Small dependency probes; a failed probe is evidence about this request, not every provider API."""
from __future__ import annotations

import os
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from math import isfinite
from urllib.parse import urlsplit

import requests


def classify_payload(provider: str, payload) -> tuple[str, str]:
    if not isinstance(payload, dict):
        return "UNKNOWN", "UNRECOGNIZED_RESPONSE"
    status = str(payload.get("status", "")).upper()
    if payload.get("error") or status in {"ERROR", "FAILED"}:
        return "UNAVAILABLE", str(payload.get("errorCode") or "PROVIDER_ERROR")
    if provider == "ibkr":
        if payload.get("authenticated") is False:
            return "UNAVAILABLE", "LOGIN_REQUIRED"
        if payload.get("connected") is False or payload.get("competing") is True:
            return "UNAVAILABLE", "BROKER_SESSION_NOT_READY"
        return (("AVAILABLE", "AUTHENTICATED") if payload.get("authenticated") is True
                else ("UNKNOWN", "AUTHENTICATION_UNCONFIRMED"))
    if provider == "data_service":
        return ("AVAILABLE", "HEALTH_OK") if payload.get("status") == "ok" else ("UNKNOWN", "HEALTH_UNCONFIRMED")
    if provider == "ollama":
        embeddings = payload.get("embeddings")
        valid = isinstance(embeddings, list) and len(embeddings) == 1 and isinstance(embeddings[0], list) and bool(embeddings[0])
        valid = valid and all(type(value) in {int, float} and isfinite(value) for value in embeddings[0])
        return (("AVAILABLE", "EMBEDDING_RETURNED") if valid
                else ("UNKNOWN", "NO_EMBEDDING"))
    if provider in {"deepseek", "bailian"}:
        return "UNKNOWN", "MODEL_LIST_ONLY_GENERATION_NOT_CHECKED"
    if status == "PARTIAL":
        return "UNKNOWN", "PARTIAL_DATA"
    if provider == "baostock":
        # /financial calls BaoStock's one-table query; its successful response has no status field.
        metrics = payload.get("metrics") or {}
        if isinstance(metrics, dict):
            for key, value in metrics.items():
                if key in {"code", "pubDate", "statDate"}:
                    continue
                try:
                    if type(value) is not bool and isfinite(float(value)):
                        return "AVAILABLE", "DATA_RETURNED"
                except (ValueError, TypeError):
                    continue
        return "UNKNOWN", "NO_USABLE_DATA"
    if provider == "akshare":
        statements = payload.get("statements") or {}
        if not isinstance(statements, dict):
            return "UNKNOWN", "UNRECOGNIZED_RESPONSE"
        tables = list(statements.values()) + [payload.get("indicators") or {}]
        usable = any(isinstance(table, dict) and table.get("status") == "SUCCESS"
                     and isinstance(table.get("data"), list) and any(isinstance(row, dict) and row for row in table["data"])
                     for table in tables)
        return (("AVAILABLE", "DATA_RETURNED") if status == "SUCCESS" and usable
                else ("UNKNOWN", "NO_USABLE_DATA"))
    if provider == "tavily":
        if payload.get("provider") != "tavily":
            return "UNKNOWN", "SEARCH_FALLBACK_NOT_TAVILY" if payload.get("fallbackFrom") == "tavily" else "PROVIDER_UNCONFIRMED"
        results = payload.get("results")
        return (("AVAILABLE", "SEARCH_RETURNED") if status == "SUCCESS" and isinstance(results, list)
                and any(isinstance(row, dict) and (row.get("title") or row.get("snippet")) for row in results)
                else ("UNKNOWN", "NO_RESULTS"))
    if provider == "sec":
        filings = payload.get("filings")
        return (("AVAILABLE", "FILINGS_RETURNED") if isinstance(filings, list)
                and any(isinstance(row, dict) and isinstance(row.get("document_url"), str)
                        and row["document_url"].startswith(("https://", "http://")) for row in filings)
                else ("UNKNOWN", "NO_FILINGS"))
    return "UNKNOWN", "UNRECOGNIZED_OR_EMPTY_RESPONSE"


def probe(provider: str, url: str, *, method="GET", params=None, body=None,
          headers=None, verify=True, scope="READ_REQUEST") -> dict:
    started = time.monotonic()
    row = {"provider": provider, "scope": scope, "checked_at": datetime.now(timezone.utc).isoformat()}
    try:
        response = requests.request(method, url, params=params, json=body, headers=headers,
                                    timeout=(5, 20), verify=verify)
        row["http_status"] = response.status_code
        if not response.ok:
            row.update(status="UNAVAILABLE", reason="HTTP_" + str(response.status_code))
        else:
            status, reason = classify_payload(provider, response.json())
            row.update(status=status, reason=reason)
    except requests.Timeout:
        row.update(status="UNAVAILABLE", reason="TIMEOUT")
    except requests.ConnectionError:
        row.update(status="UNAVAILABLE", reason="CONNECTION_FAILED")
    except (requests.RequestException, ValueError, AttributeError):
        row.update(status="UNKNOWN", reason="INVALID_RESPONSE")
    # Never persist response bodies or exception URLs: providers sometimes echo authentication data.
    return {**row, "latency_ms": round((time.monotonic() - started) * 1000)}


def run(*, data_url: str, ollama_url: str, embedding_model: str, ibkr_gateway: str,
        probe_search: bool = False) -> dict:
    data_url, ollama_url = data_url.rstrip("/"), ollama_url.rstrip("/")
    jobs = {
        "data_service": (data_url + "/health", {"scope": "PROCESS_HEALTH_ONLY"}),
        "ollama": (ollama_url + "/api/embed", {"method": "POST", "body": {"model": embedding_model, "input": "probe"},
                                               "scope": "LOCAL_EMBEDDING"}),
        "baostock": (data_url + "/api/stock/financial", {"params": {"code": "sh.600519"}, "scope": "ONE_PROFIT_TABLE"}),
        "akshare": (data_url + "/api/stock/financial-report", {"params": {"code": "0700.HK", "period": "annual", "years": 1},
                                                              "scope": "ONE_YEAR_HK_FINANCIAL_REPORT"}),
        "sec": (data_url + "/api/edgar/filings", {"params": {"ticker": "AAPL", "type": "10-K", "count": 1},
                                                   "scope": "FILING_INDEX_ONLY"}),
    }
    rows = {}
    if ibkr_gateway:
        gateway = ibkr_gateway.rstrip("/")
        if not gateway.endswith("/v1/api"):
            gateway += "/v1/api"
        jobs["ibkr"] = (gateway + "/iserver/auth/status", {"method": "POST", "body": {},
                                                            "verify": urlsplit(gateway).hostname not in {"localhost", "127.0.0.1", "::1"},
                                                            "scope": "BROKER_SESSION_ONLY"})
    else:
        rows["ibkr"] = {"provider": "ibkr", "status": "SKIPPED", "reason": "GATEWAY_NOT_CONFIGURED"}
    if probe_search:
        jobs["tavily"] = (data_url + "/api/search/web", {"params": {"q": "Apple investor relations", "max_results": 1},
                                                           "scope": "SEARCH_MAY_USE_PROVIDER_CREDIT"})
    else:
        rows["tavily"] = {"provider": "tavily", "status": "SKIPPED", "reason": "SEARCH_CREDIT_PROBE_NOT_REQUESTED"}
    for provider, variable, base in (
            ("deepseek", "STOCKSAGE_DEEPSEEK_API_KEY", os.environ.get("STOCKSAGE_DEEPSEEK_BASE_URL", "https://api.deepseek.com")),
            ("bailian", "DASHSCOPE_API_KEY", os.environ.get("STOCKSAGE_OPENAI_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode"))):
        key = os.environ.get(variable)
        if key:
            base = base.rstrip("/")
            if provider == "bailian" and not base.endswith("/v1"):
                base += "/v1"
            jobs[provider] = (base + "/models", {"headers": {"Authorization": "Bearer " + key}, "scope": "MODEL_LIST_ONLY"})
        else:
            rows[provider] = {"provider": provider, "status": "SKIPPED", "reason": variable + "_NOT_SET"}
    with ThreadPoolExecutor(max_workers=len(jobs)) as executor:
        futures = {provider: executor.submit(probe, provider, url, **options)
                   for provider, (url, options) in jobs.items()}
        rows.update({provider: future.result() for provider, future in futures.items()})
    return {"schema": "eval_preflight_v1", "scope": "EVALUATOR_ENDPOINTS", "providers": rows,
            "endpoints": {"data_service": data_url, "ollama": ollama_url, "embedding_model": embedding_model},
            "configuration_source": "RUN_ARGUMENTS_AND_EVALUATOR_ENVIRONMENT",
            "generation_checked": False,
            "note": "These evaluator settings and environment keys are not the backend's effective configuration. "
                    "Model listing does not validate generation. Probe failure alone never exempts a case from regression."}
