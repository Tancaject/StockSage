"""Deterministic evidence-scope and two-user trace isolation checks; no model judge."""
from __future__ import annotations

import json
import os
import re
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta
from threading import Barrier, BrokenBarrierError

from eval_http import LiveEvalError, StockSageClient


def trace_steps(trace: dict) -> list[dict]:
    steps = trace.get("steps") or []
    return json.loads(steps) if isinstance(steps, str) else steps


def canonical_target(value: str) -> str:
    value = str(value or "").strip().upper()
    if re.fullmatch(r"\d{1,5}\.HK", value):
        return str(int(value[:-3])) + ".HK"
    return value


def expected_targets(case: dict, trace: dict) -> set[str]:
    targets = case.get("targets") or [case.get("expected_target") or case.get("targets_single") or case.get("ticker")]
    if not any(targets):
        targets = [call["args"][0] for call in case.get("calls", []) if call.get("args")]
    if not any(targets):
        # Expected identity must not be derived from the very routing result under evaluation.
        question = case.get("question") or case.get("message") or ""
        targets = re.findall(r"(?<![A-Za-z0-9])(?:(?:[sS][hH]|[sS][zZ])\.\d{6}|\d{1,5}\.HK|[A-Z]{2,5}(?:\.[A-Z]{1,2})?)(?![A-Za-z0-9])", question)
        vocabulary = {"FY", "EPS", "SEC", "USD", "HKD", "CNY", "RAG", "DEEP", "NEWS", "EBIT", "EBITDA", "ROE", "PE", "PB", "TTM", "RSI", "MACD", "DCF", "FCF", "ROIC", "NYSE"}
        targets = [target for target in targets if target not in vocabulary]
    return {canonical_target(target) for target in targets if target}


def _summary(checks: list[dict]) -> dict:
    violations = [row for row in checks if row["status"] == "FAIL"]
    missing = sum(row["status"] == "NO_DATA" for row in checks)
    checked = sum(row["status"] in {"PASS", "FAIL"} for row in checks)
    return {"status": "FAIL" if violations else "NO_DATA" if missing else "PASS" if checked else "NOT_APPLICABLE",
            "violations": violations, "checks": checks, "checked_count": checked, "no_data_count": missing}


def _period_check(row: dict, question: str, section: str, complete: bool) -> tuple[str, str]:
    years = set(re.findall(r"(?:FY\s*|财年\s*)(20\d{2})|(?<!\d)(20\d{2})\s*年", question, re.I))
    years = {int(a or b) for a, b in years}
    explicit = row.get("fiscalYear") or row.get("reportPeriodEnd")
    timing = row.get("timing") if isinstance(row.get("timing"), dict) else {}
    title = str(row.get("title") or "")
    if not explicit and re.search(r"10[- ]K|annual report|年报", title, re.I):
        explicit = re.search(r"20\d{2}", title)
        explicit = explicit.group() if explicit else None
    if years:
        # A multi-year table may legitimately contain prior comparatives; reject only a disjoint period set.
        table_dates = re.findall(r"^(?:指标|科目)\s*\|([^\n]+)", section, re.M)
        actual = {int(year) for year in re.findall(r"20\d{2}", " ".join(table_dates))}
        if explicit:
            actual.update(int(year) for year in re.findall(r"20\d{2}", str(explicit)))
        if not actual or (not explicit and not complete):
            latest = str((timing.get("financial") or {}).get("latestPeriodEnd") or "")
            if re.fullmatch(r"20\d{2}-\d{2}-\d{2}", latest) and int(latest[:4]) < min(years):
                return "FAIL", f"Latest available financial period {latest} precedes all requested fiscal years {sorted(years)}."
            return "NO_DATA", "Missing complete fiscal-period evidence; fetch/publication dates are not report periods."
        if actual & years and re.search(r"20\d{2}\s*(?:年\s*[\d一二三四五六七八九十]+\s*月|[-/]\s*\d)", question):
            return "NO_DATA", "A fiscal-year match cannot establish the narrower requested month/day range."
        return ("PASS", "Requested fiscal year is present.") if actual & years else (
            "FAIL", f"Requested fiscal years {sorted(years)}; evidence periods {sorted(actual)}.")
    search = timing.get("search") or {}
    if search.get("requestedDays") or search.get("requestedWindow"):
        reason = row.get("freshnessReason")
        if reason in {"PUBLICATION_OUTSIDE_WINDOW", "FUTURE_PUBLICATION"}:
            return "FAIL", reason
        days = search.get("requestedDays") or {"d": 1, "w": 7, "m": 30, "y": 365}.get(search.get("requestedWindow"))
        fetched = timing.get("fetchedAt") or row.get("observedAt")
        dates = [search.get("earliestPublishedAt"), search.get("latestPublishedAt"),
                 search.get("earliestPublishedDate"), search.get("latestPublishedDate")]
        if days and fetched:
            try:
                upper = datetime.fromisoformat(fetched.replace("Z", "+00:00"))
                lower = upper - timedelta(days=days)
                for value in filter(None, dates):
                    if "T" not in value:
                        if value < lower.date().isoformat() or value > upper.date().isoformat():
                            return "FAIL", "Source publication date is outside the requested window."
                    else:
                        published = datetime.fromisoformat(value.replace("Z", "+00:00"))
                        if published < lower or published > upper:
                            return "FAIL", "Source publication time is outside the requested window."
                if any(dates) and not search.get("unknownCount") and not search.get("dateCount") and search.get("requestedWindow") == search.get("effectiveWindow"):
                    return "PASS", "Publication instants fall inside the explicit requested window."
            except (TypeError, ValueError):
                return "NO_DATA", "Invalid publication timing metadata."
        return "NO_DATA", "Publication range has not been verified against the requested window."
    if re.search(r"20\d{2}[-年/]\d|最近|过去|近[一二两三四五六七八九十\d]|last\s|past\s", question, re.I):
        return "NO_DATA", "Trace does not establish full coverage of the explicit requested period."
    return "NOT_APPLICABLE", "No explicit supported period constraint."


def assess_risks(case: dict, trace: dict, answer: str = "", evidence_snapshot: dict | None = None,
                 cited_evidence_ids: list[str] | None = None) -> dict:
    attrs = [step.get("attributes") or {} for step in trace_steps(trace)]
    targets = expected_targets(case, trace)
    question = case.get("question") or case.get("message") or (evidence_snapshot or {}).get("query") or trace.get("userQuery") or ""
    cited = set(re.findall(r"\[(E\d+)\]", answer)) | set(cited_evidence_ids or [])
    checks = []
    evidence_rows = []
    context = "\n".join(item.get("analystContext") or item.get("evidenceContext") or "" for item in attrs)
    complete = any(item.get("evidenceCaptureComplete") for item in attrs) or any(item.get("analystContext") for item in attrs)
    snapshot_sections = {}
    source_context = "\n".join(str((evidence_snapshot or {}).get(key) or "")
                               for key in ("fundamentalsReport", "marketReport", "newsReport"))
    for block in re.findall(r"\[\[STOCKSAGE_EVIDENCE_BEGIN\]\](.*?)\[\[STOCKSAGE_EVIDENCE_END\]\]", source_context, re.S):
        identity = re.search(r"^evidenceId: ([^\n]+)$", block, re.M)
        content = re.search(r"\[\[STOCKSAGE_CONTENT_BEGIN\]\]\n(.*?)\n\[\[STOCKSAGE_CONTENT_END\]\]", block, re.S)
        if identity and content:
            snapshot_sections[identity.group(1)] = content.group(1)
    for item in attrs:
        if item.get("kind") == "ordinary-evidence":
            evidence_rows.extend({**row, "partitionTarget": item.get("target")} for row in item.get("observations") or [])
    if evidence_snapshot:
        evidence_rows.extend((evidence_snapshot.get("evidenceLedger") or {}).get("evidence") or [])
    indexed = {row.get("id") or row.get("evidenceId"): row for row in evidence_rows}
    selected = cited if answer or cited_evidence_ids is not None else {
        key for key, row in indexed.items() if key and evidence_snapshot and row.get("status") == "AVAILABLE"}
    for key in sorted(selected):
        row = indexed.get(key)
        if not row:
            checks.append({"check": "evidence_reference", "evidence_id": key, "status": "FAIL" if evidence_rows else "NO_DATA",
                           "reason": "Cited evidence identity is absent from the captured ledger."})
            continue
        actual = canonical_target(row.get("target") or row.get("targetKey"))
        tool = row.get("tool") or row.get("capabilityId") or ""
        status = "NO_DATA" if not targets or not actual else "PASS" if actual in targets else "FAIL"
        partition = canonical_target(row.get("partitionTarget"))
        if actual and partition and actual != partition:
            status = "FAIL"
        # Search envelope targets can be copied from the request, not established by the returned page.
        if status == "PASS" and ("search" in tool.lower() or "news" in tool.lower()):
            status = "NO_DATA"
        checks.append({"check": "evidence_target", "evidence_id": key, "status": status,
                       "expected": sorted(targets), "actual": actual,
                       "reason": "Search result content identity is not independently captured." if status == "NO_DATA" else "Compare canonical evidence target."})
        match = re.search(r"^\[" + re.escape(str(key)) + r"\][^\n]*\n(.*?)(?=^\[E\d+\]|\Z)", context, re.M | re.S)
        section = match.group(1) if match else snapshot_sections.get(key, "")
        section_complete = (complete or key in snapshot_sections) and "[truncated]" not in section
        period_row = row
        if "search" in tool.lower() and "{" in section:
            try:
                payload, _ = json.JSONDecoder().raw_decode(section[section.index("{"):])
                source = row.get("source") or row.get("sourceRef")
                result = next((item for item in payload.get("results", []) if item.get("link") == source), {})
                period_row = {**row, **result}
            except (ValueError, TypeError, AttributeError):
                pass
        status, reason = _period_check(period_row, question, section, section_complete)
        checks.append({"check": "evidence_period", "evidence_id": key, "status": status, "reason": reason})
    if not selected:
        checks.append({"check": "cited_evidence_scope", "status": "NOT_APPLICABLE" if not answer or not re.search(r"\[\d+\]", answer) else "NO_DATA",
                       "reason": "No E-number citations; RAG numeric references lack per-reference target/period metadata."})
    elif re.search(r"\[\d+\]", answer):
        checks.append({"check": "rag_reference_scope", "status": "NO_DATA", "reason": "RAG numeric references lack per-reference target/period metadata."})
    ingest = [item for item in attrs if item.get("kind") == "search-ingest"]
    searched = any((step.get("attributes") or {}).get("stepKind") in {"tool", "capability"}
                   and re.search(r"search|news", str(step.get("action") or ""), re.I)
                   and str((step.get("attributes") or {}).get("outcome") or "").upper() == "SUCCESS"
                   for step in trace_steps(trace))
    if not ingest and searched:
        checks.append({"check": "search_ingest_scope", "status": "NO_DATA",
                       "reason": "A search succeeded but the trace has no search-ingest submission record."})
    for item in ingest:
        if item.get("status") == "SKIPPED":
            checks.append({"check": "search_ingest_scope", "status": "NOT_APPLICABLE", "reason": item.get("reason", "Ingestion skipped.")})
        elif item.get("status") not in {"SUBMITTED", "COMPLETED"}:
            checks.append({"check": "search_ingest_scope", "status": "NO_DATA", "reason": "Search ingestion was not submitted."})
        elif not item.get("items"):
            checks.append({"check": "search_ingest_scope", "status": "NOT_APPLICABLE" if item.get("items") == [] else "NO_DATA",
                           "reason": "No result items were recorded."})
        for row in item.get("items") or []:
            # SUBMITTED items are the ones handed to the knowledge base; the async outcome is not in the trace.
            if row.get("status") not in {"SUBMITTED", "INGESTED", "ingested", "skipped_unchanged", "indexed_without_new_vectors"}:
                continue
            actual = canonical_target(row.get("target") or row.get("ticker") or row.get("symbol"))
            checks.append({"check": "search_ingest_target", "source": row.get("source") or row.get("link"),
                           "status": "NO_DATA" if not actual or not targets else "PASS" if actual in targets else "FAIL",
                           "expected": sorted(targets), "actual": actual})
            timing = {"fetchedAt": item.get("fetchedAt"), "search": {
                "requestedWindow": item.get("requestedTimelimit"), "effectiveWindow": item.get("effectiveTimelimit"),
                "requestedDays": item.get("requestedDays"), "earliestPublishedAt": row.get("publishedAt"),
                "latestPublishedAt": row.get("publishedAt"), "earliestPublishedDate": row.get("publishedDate"),
                "latestPublishedDate": row.get("publishedDate"), "dateCount": int(bool(row.get("publishedDate"))),
                "unknownCount": int(not (row.get("publishedAt") or row.get("publishedDate")))}}
            status, reason = _period_check({**row, "timing": timing}, question, "", True)
            checks.append({"check": "search_ingest_period", "source": row.get("source") or row.get("link"), "status": status, "reason": reason})
    return _summary(checks)


def regression_checks(case: dict, trace: dict) -> dict[str, bool | None]:
    if not case.get("regression"):
        return {}
    attrs = [step.get("attributes") or {} for step in trace_steps(trace)]
    evidence = [item for item in attrs if item.get("kind") == "ordinary-evidence"]
    rows = [row for item in evidence for row in item.get("observations") or []]
    expected = canonical_target(case.get("expected_target"))
    actual = {canonical_target(row.get("target")) for row in rows if row.get("target")}
    # Without an expected target the case does not pin identity; comparing against "" would always fail.
    checks = {"regression:resolved_target": actual == {expected} if actual else None} if expected else {}
    context = "\n".join(item.get("analystContext") or item.get("evidenceContext") or "" for item in attrs)
    for tool, fragments in case.get("evidence_contains", {}).items():
        matching = [row for row in rows if row.get("tool") == tool]
        available = matching and all(row.get("status") == "AVAILABLE" for row in matching)
        checks["regression:content:" + tool] = (all(fragment in context for fragment in fragments)
                                                if available and context else None)
    return checks


def assess_concurrency(rows: list[dict]) -> dict:
    checks = []
    if len(rows) != 2:
        return _summary([{"check": "concurrent_pair", "status": "NO_DATA", "reason": "Two completed requests are required."}])
    traces = [row.get("trace") or {} for row in rows]
    for field in ("traceId", "userId", "conversationId"):
        values = [trace.get(field) for trace in traces]
        checks.append({"check": "distinct_" + field, "status": "NO_DATA" if not all(values) else "PASS" if values[0] != values[1] else "FAIL"})
    starts, ends = [row.get("started_monotonic") for row in rows], [row.get("ended_monotonic") for row in rows]
    overlap = all(value is not None for value in starts + ends) and max(starts) < min(ends)
    checks.append({"check": "overlapping_requests", "status": "PASS" if overlap else "NO_DATA"})
    direct_tools = {"getFinancialReports", "getStructuredFinancials", "getFinancialMetrics", "getIbkrHistoricalBars", "getStockKLine", "getTechnicalIndicators"}
    for row, trace in zip(rows, traces):
        target = canonical_target(row["ticker"])
        ownership = all(trace.get(field) is not None for field in ("userId", "userQuery")) and row.get("expected_user_id") is not None
        checks.append({"check": "request_owner", "target": target,
                       "status": "NO_DATA" if not ownership else "PASS" if trace.get("userId") == row.get("expected_user_id") and trace.get("userQuery") == row.get("question") else "FAIL"})
        calls = [step for step in trace_steps(trace) if (step.get("attributes") or {}).get("stepKind") == "tool"]
        if not calls:
            checks.append({"check": "tool_event_target", "target": target, "status": "NO_DATA", "reason": "No tool event was captured."})
        for call in calls:
            try:
                args = json.loads(call.get("actionInput") or "null")
            except (ValueError, TypeError):
                args = None
            actual = canonical_target(args[0]) if call.get("action") in direct_tools and isinstance(args, list) and args else ""
            checks.append({"check": "tool_event_target", "target": target, "tool": call.get("action"), "actual": actual,
                           "status": "NO_DATA" if not actual else "PASS" if actual == target else "FAIL"})
    return _summary(checks)


def run_concurrency_smoke(base_url: str, timeout_seconds: int = 180) -> dict:
    credentials = [(os.environ.get(prefix + "EMAIL", ""), os.environ.get(prefix + "PASSWORD", ""))
                   for prefix in ("STOCKSAGE_EVAL_", "STOCKSAGE_EVAL_SECOND_")]
    report = {"schema": "concurrent_trace_isolation_v1", "status": "SKIPPED", "cases": [], "metrics": {}}
    if not all(email and password for email, password in credentials):
        report["error"] = "Set STOCKSAGE_EVAL_EMAIL/PASSWORD and STOCKSAGE_EVAL_SECOND_EMAIL/PASSWORD for two dedicated accounts."
        return report
    if credentials[0][0].casefold() == credentials[1][0].casefold():
        report["error"] = "Concurrency evaluation requires two different accounts."
        return report
    try:
        clients = [StockSageClient(base_url, timeout_seconds) for _ in credentials]
        users = [client.login(*credential) for client, credential in zip(clients, credentials)]
        if users[0]["userId"] == users[1]["userId"]:
            raise LiveEvalError("The two logins resolve to the same user.")
        barrier = Barrier(2)

        def run(index: int) -> dict:
            ticker = ("AAPL", "MSFT")[index]
            question = f"查询 {ticker} 最近两年的年度营收和净利润。"
            barrier.wait(timeout=timeout_seconds)
            started = time.monotonic()
            events = clients[index].stream_json("POST", "/api/chat/stream", {"message": question}, timeout_seconds)
            ended = time.monotonic()
            meta = next((event for event in events if event.get("type") == "meta" and event.get("traceId")), None)
            if not meta:
                raise LiveEvalError("Concurrent request returned no trace id.")
            _, trace = clients[index].json_request("GET", "/api/trace/" + meta["traceId"])
            return {"ticker": ticker, "question": question, "expected_user_id": users[index]["userId"],
                    "started_monotonic": started, "ended_monotonic": ended, "trace": trace, "events": events}

        with ThreadPoolExecutor(max_workers=2) as executor:
            futures = [executor.submit(run, index) for index in range(2)]
            errors = []
            for future in futures:
                try:
                    report["cases"].append(future.result())
                except (LiveEvalError, ValueError, KeyError, TypeError, OSError, BrokenBarrierError) as error:
                    errors.append(str(error))
        report["risk_checks"] = assess_concurrency(report["cases"])
        report["status"] = report["risk_checks"]["status"]
        report["metrics"] = {"cross_request_violations": len(report["risk_checks"]["violations"]),
                             "isolation_no_data_checks": report["risk_checks"]["no_data_count"]}
        if errors:
            report["status"], report["error"] = "ERROR", "; ".join(errors)
    except (LiveEvalError, ValueError, KeyError, TypeError, OSError) as error:
        report["status"], report["error"] = "ERROR", str(error)
    return report
