"""Exercise ordinary /api/chat/stream requests and verify persisted execution traces.

Execution checks and separately bound human answer reviews remain distinct.
Credentials are read from the environment and are never included in the output.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import time
from datetime import datetime, timezone
from pathlib import Path

from eval_http import StockSageClient, LiveEvalError
from eval_common import capture_answer

HERE = Path(__file__).resolve().parent

# 冻结需求标签的满足条件，与后端 DataRequirement 一致；在 runner 侧独立计算，
# 使 FIXED 与 DYNAMIC 共用同一分母，不受规划器运行时补充的需求影响。
REQUIREMENT_TOOLS = {
    "PRICE_HISTORY": {"getStockKLine", "getIbkrHistoricalBars"},
    "VALUATION": {"getFinancialMetrics", "getStructuredFinancials"},
    "TECHNICAL": {"getTechnicalIndicators"},
    "FINANCIAL_STATEMENTS": {"getFinancialReports", "getStructuredFinancials"},
    "NEWS": {"searchNews", "webSearch", "local.news.searchNews", "local.news.webSearch", "mcp.news.search"},
}


def requirement_met(requirement: str, observations: list[dict], filing_retrieved: bool) -> bool:
    if requirement == "FILING_TEXT":
        return filing_retrieved
    for row in observations:
        if row.get("tool") not in REQUIREMENT_TOOLS.get(requirement, set()):
            continue
        if row.get("citable") and (row.get("gap") == "" or row.get("gap") == row.get("freshnessReason")):
            return True
        if requirement == "NEWS" and row.get("status") == "NO_RESULTS":
            return True
    return False


def target_matches(target: str, key: str) -> bool:
    return bool(key) and target.upper().split(".")[0] in key.upper()


def assess(case: dict, events: list[dict], trace: dict) -> dict:
    steps = trace.get("steps", [])
    if isinstance(steps, str):
        steps = json.loads(steps)
    attrs = [step.get("attributes") or {} for step in steps]
    route = next((item for item in attrs if item.get("kind") == "routing-decision"), {})
    evidences = [item for item in attrs if item.get("kind") == "ordinary-evidence"]
    evidence = evidences[0] if evidences else {}
    plan = next((item for item in attrs if item.get("kind") == "ordinary-plan"), {})
    comparison = next((item for item in attrs if item.get("kind") == "ordinary-comparison"), {})
    retrieval = next((item for item in attrs if item.get("kind") == "knowledge-retrieval"), {})
    calls = [step for step in steps if (step.get("attributes") or {}).get("stepKind") == "tool"]
    routes = case["route"] if isinstance(case["route"], list) else [case["route"]]
    checks = {"route": route.get("route") in routes,
              "outcome": trace.get("taskOutcome") in case["outcomes"],
              "stream": bool(events) and not any(event.get("type") == "error" for event in events),
              "terminal_trace": trace.get("status") in {"success", "error"}}
    request = evidence.get("request") or route.get("readRequest") or {}
    checks["parameters"] = all(request.get(key) == value for key, value in case.get("request", {}).items())
    for expected in case.get("calls", []):
        matches = [call for call in calls if call.get("action") == expected["tool"]]
        parsed = []
        for call in matches:
            try:
                parsed.append(json.loads(call.get("actionInput") or "null"))
            except (ValueError, TypeError):
                parsed.append(None)
        checks["call:" + expected["tool"]] = expected["args"] in parsed
    if case.get("no_tools"):
        checks["no_tools"] = not calls and not any(item.get("stepKind") == "capability" for item in attrs)
    if case.get("require_tool_success") or (trace.get("taskOutcome") == "COMPLETED" and case.get("calls")):
        checks["tool_success"] = bool(calls) and all(
            call.get("attributes", {}).get("outcome") == "SUCCESS" for call in calls)
    for expected in case.get("freshness", []):
        matches = [row for row in evidence.get("observations", []) if row.get("tool") == expected["tool"]]
        checks["freshness:" + expected["tool"]] = bool(matches) and all(
            row.get("status") == "AVAILABLE" and row.get("citable")
            and row.get("freshnessStatus") == expected["status"]
            and row.get("freshnessReason") == expected["reason"] for row in matches)
    answer = "".join(event.get("content", "") for event in events if event.get("type") == "answer")
    checks["answer"] = bool(answer.strip())
    all_rows = [row for item in evidences for row in item.get("observations", [])]
    cited = set(re.findall(r"\[(E\d+)\]", answer))
    if case.get("require_citations"):
        valid = {row["id"] for row in all_rows if row.get("citable")}
        checks["citations"] = bool(cited) and cited <= valid
    by_target = {item["target"]: item for item in evidences if item.get("target")}
    if "targets" in case:
        checks["targets"] = sorted(plan.get("targets", [])) == sorted(case["targets"])
        # 证据隔离：每个标的分区里的证据都必须属于该标的。
        checks["isolation"] = all(target_matches(target, row.get("target", ""))
                                  for target, item in by_target.items()
                                  for row in item.get("observations", []) if row.get("target"))
        ids = [row["id"] for row in all_rows]
        checks["unique_evidence_ids"] = len(ids) == len(set(ids))
    for target, allowed in case.get("target_outcomes", {}).items():
        checks["target_outcome:" + target] = by_target.get(target, {}).get("taskOutcome") in allowed
    retrieved = {str(row.get("ticker", "")).upper() for row in retrieval.get("retrieved", [])}
    coverage = {}
    for requirement in case.get("requirements", []):
        if by_target:
            coverage[requirement] = {target: requirement_met(requirement, item.get("observations", []),
                                                             target.upper() in retrieved)
                                     for target, item in by_target.items()}
        else:
            ticker = str(case.get("targets_single", "")).upper()
            coverage[requirement] = {ticker or "single": requirement_met(
                requirement, evidence.get("observations", []), bool(retrieved) if not ticker else ticker in retrieved)}
    target_citations = {target: bool(cited & {row["id"] for row in item.get("observations", [])})
                        for target, item in by_target.items()}
    return {"id": case["id"], "passed": all(checks.values()), "checks": checks,
            "raw_route": route.get("rawRoute"), "final_route": route.get("route"),
            "models": [{key: event.get(key) for key in ("modelName", "modelTier")}
                       for event in events if event.get("type") == "model"],
            "actual_outcome": trace.get("taskOutcome"), "plan_source": plan.get("source"),
            "plan_steps": plan.get("steps"), "plan_diagnostics": plan.get("diagnostics"),
            "runtime_requirements": plan.get("requirements"), "requirement_coverage": coverage,
            "target_outcomes": {target: item.get("taskOutcome") for target, item in by_target.items()},
            "target_cited": target_citations, "comparison": comparison,
            "trace": trace, "events": events,
            **capture_answer(case, answer)}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--cases", type=Path, default=HERE / "ordinary_live_cases.jsonl")
    parser.add_argument("--output", type=Path, default=HERE / "ordinary_live_result.json")
    parser.add_argument("--timeout-seconds", type=int, default=180)
    parser.add_argument("--label", default="", help="本次运行的配置标签，例如 FIXED 或 DYNAMIC")
    args = parser.parse_args()
    if args.timeout_seconds < 1:
        parser.error("--timeout-seconds must be positive")
    cases = [json.loads(line) for line in args.cases.read_text(encoding="utf-8").splitlines() if line.strip()]
    if not cases or len({case["id"] for case in cases}) != len(cases):
        parser.error("cases must be nonempty with unique ids")
    canonical = json.dumps(cases, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    report = {"schema": "ordinary_execution_eval_v1", "evidence_kind": "GOLDEN_SET",
              "evaluator": "ordinary-http-trace-v2", "generated_at": datetime.now(timezone.utc).isoformat(),
              "dataset_sha256": hashlib.sha256(canonical.encode()).hexdigest(), "sample_count": len(cases),
              "status": "NOT_RUN", "answer_quality": "NO_DATA", "label": args.label, "cases": []}
    client = StockSageClient(args.base_url, args.timeout_seconds)
    try:
        email = os.environ.get("STOCKSAGE_EVAL_EMAIL", "")
        password = os.environ.get("STOCKSAGE_EVAL_PASSWORD", "")
        if not email or not password:
            raise LiveEvalError("Set STOCKSAGE_EVAL_EMAIL and STOCKSAGE_EVAL_PASSWORD for a dedicated evaluation user.")
        client.login(email, password)
        conversations = {}
        for case in cases:
            body = {"message": case["question"]}
            if case.get("conversation") in conversations:
                body["conversationId"] = conversations[case["conversation"]]
            started = time.monotonic()
            events = client.stream_json("POST", "/api/chat/stream", body, args.timeout_seconds)
            latency_ms = int((time.monotonic() - started) * 1000)
            meta = next((event for event in events if event.get("type") == "meta" and event.get("traceId")), None)
            if not meta:
                raise LiveEvalError("Chat stream did not return a trace id")
            if case.get("conversation"):
                conversations[case["conversation"]] = meta["conversationId"]
            _, trace = client.json_request("GET", "/api/trace/" + meta["traceId"])
            result = assess(case, events, trace)
            result["latency_ms"] = latency_ms
            report["cases"].append(result)
        report["status"] = "PASS" if all(case["passed"] for case in report["cases"]) else "FAIL"
    except (LiveEvalError, ValueError, KeyError, TypeError) as error:
        report["status"] = "BLOCKED"
        report["error"] = str(error)
    report["completed_count"] = len(report["cases"])
    # Incomplete execution cannot be represented as a passing full-set score.
    report["execution_contract_pass_rate"] = (
        sum(case["passed"] for case in report["cases"]) / len(cases)
        if len(report["cases"]) == len(cases) else None
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": report["status"], "completed": report["completed_count"], "output": str(args.output)}))
    return 0 if report["status"] == "PASS" else 2


if __name__ == "__main__":
    raise SystemExit(main())
