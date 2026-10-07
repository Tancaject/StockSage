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
from datetime import datetime, timezone
from pathlib import Path

from eval_http import StockSageClient, LiveEvalError
from eval_common import capture_answer

HERE = Path(__file__).resolve().parent


def assess(case: dict, events: list[dict], trace: dict) -> dict:
    steps = trace.get("steps", [])
    if isinstance(steps, str):
        steps = json.loads(steps)
    attrs = [step.get("attributes") or {} for step in steps]
    route = next((item for item in attrs if item.get("kind") == "routing-decision"), {})
    evidence = next((item for item in attrs if item.get("kind") == "ordinary-evidence"), {})
    calls = [step for step in steps if (step.get("attributes") or {}).get("stepKind") == "tool"]
    checks = {"route": route.get("route") == case["route"],
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
    if case.get("require_citations"):
        valid = {row["id"] for row in evidence.get("observations", []) if row.get("citable")}
        cited = set(re.findall(r"\[(E\d+)\]", answer))
        checks["citations"] = bool(cited) and cited <= valid
    return {"id": case["id"], "passed": all(checks.values()), "checks": checks,
            "raw_route": route.get("rawRoute"), "final_route": route.get("route"),
            "models": [{key: event.get(key) for key in ("modelName", "modelTier")}
                       for event in events if event.get("type") == "model"],
            "actual_outcome": trace.get("taskOutcome"), "trace": trace, "events": events,
            **capture_answer(case, answer)}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--cases", type=Path, default=HERE / "ordinary_live_cases.jsonl")
    parser.add_argument("--output", type=Path, default=HERE / "ordinary_live_result.json")
    parser.add_argument("--timeout-seconds", type=int, default=180)
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
              "status": "NOT_RUN", "answer_quality": "NO_DATA", "cases": []}
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
            events = client.stream_json("POST", "/api/chat/stream", body, args.timeout_seconds)
            meta = next((event for event in events if event.get("type") == "meta" and event.get("traceId")), None)
            if not meta:
                raise LiveEvalError("Chat stream did not return a trace id")
            if case.get("conversation"):
                conversations[case["conversation"]] = meta["conversationId"]
            _, trace = client.json_request("GET", "/api/trace/" + meta["traceId"])
            report["cases"].append(assess(case, events, trace))
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
