"""Run bounded live DEEP evaluations through the StockSage HTTP API."""

from __future__ import annotations

import argparse
import hashlib
import http.cookiejar
import json
import math
import os
import statistics
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter
from datetime import datetime
from pathlib import Path
from typing import Any, Callable, Iterable

HERE = Path(__file__).resolve().parent
DEFAULT_CASES = HERE / "harness_live_cases.jsonl"
DEFAULT_OUTPUT = HERE / "results" / "harness_live_eval_result.json"
TERMINAL_EVENT_TYPES = {"task-final", "error"}
TERMINAL_TASK_STATUSES = {"SUCCEEDED", "FAILED"}
SAFE_RESULT_KINDS = {
    "FULL_REPORT",
    "INSUFFICIENT_EVIDENCE",
    "POLICY_BLOCKED",
    "OFFLINE_FALLBACK",
}


class LiveEvalError(RuntimeError):
    """An infrastructure or protocol error that blocks a live evaluation."""


def file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_cases(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        row = json.loads(line)
        if not row.get("id") or not row.get("ticker") or not row.get("message"):
            raise ValueError(f"invalid live harness case at line {line_number}")
        allowed = set(row.get("allowed_result_kinds") or SAFE_RESULT_KINDS)
        if not allowed or not allowed.issubset(SAFE_RESULT_KINDS):
            raise ValueError(f"invalid result kind at line {line_number}")
        row["allowed_result_kinds"] = sorted(allowed)
        row["timeout_seconds"] = max(30, int(row.get("timeout_seconds", 900)))
        rows.append(row)
    if not rows:
        raise ValueError("live harness case set is empty")
    return rows


class StockSageClient:
    def __init__(self, base_url: str, timeout_seconds: int = 30):
        self.base_url = base_url.rstrip("/")
        self.timeout_seconds = timeout_seconds
        self.cookies = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.cookies)
        )

    def request(
        self,
        method: str,
        path: str,
        body: dict[str, Any] | None = None,
        timeout_seconds: int | None = None,
        accept: str = "application/json",
    ) -> tuple[int, bytes]:
        headers = {"Accept": accept}
        payload = None
        if body is not None:
            payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if method.upper() not in {"GET", "HEAD", "OPTIONS"}:
            token = self.csrf_cookie()
            if token:
                headers["X-XSRF-TOKEN"] = token
        request = urllib.request.Request(
            self.base_url + path,
            data=payload,
            headers=headers,
            method=method.upper(),
        )
        try:
            with self.opener.open(
                request, timeout=timeout_seconds or self.timeout_seconds
            ) as response:
                return response.status, response.read()
        except urllib.error.HTTPError as error:
            detail = error.read().decode("utf-8", errors="replace")[:500]
            raise LiveEvalError(
                f"{method.upper()} {path} returned HTTP {error.code}: {detail}"
            ) from error
        except urllib.error.URLError as error:
            raise LiveEvalError(
                f"{method.upper()} {path} failed: {error.reason}"
            ) from error

    def json_request(
        self,
        method: str,
        path: str,
        body: dict[str, Any] | None = None,
        timeout_seconds: int | None = None,
    ) -> tuple[int, Any]:
        status, raw = self.request(method, path, body, timeout_seconds)
        if not raw:
            return status, None
        try:
            return status, json.loads(raw.decode("utf-8"))
        except json.JSONDecodeError as error:
            raise LiveEvalError(f"{method.upper()} {path} returned invalid JSON") from error

    def csrf_cookie(self) -> str | None:
        for cookie in self.cookies:
            if cookie.name == "XSRF-TOKEN":
                return urllib.parse.unquote(cookie.value)
        return None

    def login(self, email: str, password: str) -> dict[str, Any]:
        self.json_request("GET", "/api/auth/csrf")
        _, user = self.json_request(
            "POST", "/api/auth/login", {"email": email, "password": password}
        )
        self.json_request("GET", "/api/auth/csrf")
        if not isinstance(user, dict) or not user.get("userId"):
            raise LiveEvalError("login response did not contain a user id")
        return {"userId": user["userId"], "email": user.get("email")}

    def stream_json(
        self,
        method: str,
        path: str,
        body: dict[str, Any] | None,
        timeout_seconds: int,
        stop_when: Callable[[dict[str, Any]], bool] | None = None,
    ) -> list[dict[str, Any]]:
        headers = {"Accept": "text/event-stream"}
        payload = None
        if body is not None:
            payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if method.upper() not in {"GET", "HEAD", "OPTIONS"}:
            token = self.csrf_cookie()
            if token:
                headers["X-XSRF-TOKEN"] = token
        request = urllib.request.Request(
            self.base_url + path,
            data=payload,
            headers=headers,
            method=method.upper(),
        )
        events: list[dict[str, Any]] = []
        data_lines: list[str] = []
        try:
            with self.opener.open(request, timeout=timeout_seconds) as response:
                while True:
                    raw_line = response.readline()
                    if not raw_line:
                        if data_lines:
                            event = parse_sse_data(data_lines)
                            if event is not None:
                                events.append(event)
                        break
                    line = raw_line.decode("utf-8", errors="replace").rstrip("\r\n")
                    if line == "":
                        if data_lines:
                            event = parse_sse_data(data_lines)
                            data_lines = []
                            if event is not None:
                                events.append(event)
                                if stop_when and stop_when(event):
                                    break
                        continue
                    if line.startswith("data:"):
                        data_lines.append(line[5:].lstrip())
        except urllib.error.HTTPError as error:
            detail = error.read().decode("utf-8", errors="replace")[:500]
            raise LiveEvalError(
                f"{method.upper()} {path} returned HTTP {error.code}: {detail}"
            ) from error
        except (urllib.error.URLError, TimeoutError) as error:
            raise LiveEvalError(f"{method.upper()} {path} stream failed: {error}") from error
        return events


def parse_sse_data(data_lines: Iterable[str]) -> dict[str, Any] | None:
    payload = "\n".join(data_lines).strip()
    if not payload or payload == "[DONE]":
        return None
    try:
        parsed = json.loads(payload)
    except json.JSONDecodeError:
        return {"type": "unparsed", "metadata": {"size": len(payload)}}
    return parsed if isinstance(parsed, dict) else None


def percentile(values: list[float], percentile_value: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = max(0, math.ceil(percentile_value * len(ordered)) - 1)
    return round(ordered[index], 3)


def parse_steps(trace: dict[str, Any] | None) -> list[dict[str, Any]]:
    if not trace:
        return []
    value = trace.get("steps")
    if isinstance(value, str):
        try:
            value = json.loads(value)
        except json.JSONDecodeError:
            return []
    return [step for step in value or [] if isinstance(step, dict)]


def harness_decisions(trace: dict[str, Any] | None) -> list[dict[str, Any]]:
    decisions: list[dict[str, Any]] = []
    for index, step in enumerate(parse_steps(trace)):
        attributes = step.get("attributes")
        if not isinstance(attributes, dict) or not attributes.get("policyId"):
            continue
        recovery_actions = [
            str(action) for action in attributes.get("recoveryActions") or []
        ]
        decisions.append(
            {
                "index": index,
                "policy_id": attributes.get("policyId"),
                "policy_version": attributes.get("policyVersion"),
                "phase": attributes.get("phase"),
                "decision": attributes.get("decision"),
                "recommendation_allowed": bool(
                    attributes.get("policyAllowsRecommendation")
                ),
                "violation_codes": [
                    str(code) for code in attributes.get("violationCodes") or []
                ],
                "recovery_actions": recovery_actions,
                "duration_ms": max(0, int(step.get("durationMs") or 0)),
            }
        )
    return decisions


def evaluate_case_result(
    task: dict[str, Any] | None,
    trace: dict[str, Any] | None,
    allowed_result_kinds: Iterable[str],
) -> dict[str, Any]:
    decisions = harness_decisions(trace)
    final_by_phase: dict[str, str] = {}
    recovery_counter: Counter[str] = Counter()
    for decision in decisions:
        phase = str(decision.get("phase") or "")
        if phase:
            final_by_phase[phase] = str(decision.get("decision") or "")
        recovery_counter.update(decision.get("recovery_actions") or [])

    status = str((task or {}).get("status") or "")
    result_kind = str((task or {}).get("resultKind") or "")
    result_kind_allowed = result_kind in set(allowed_result_kinds)
    completed = status in TERMINAL_TASK_STATUSES
    trace_complete = str((trace or {}).get("status") or "") == "success"
    recovery_budget_ok = all(count <= 1 for count in recovery_counter.values())
    unsafe_reasons: list[str] = []

    if result_kind not in SAFE_RESULT_KINDS:
        unsafe_reasons.append("unknown_or_missing_result_kind")
    if result_kind == "FULL_REPORT":
        if final_by_phase.get("EVIDENCE") != "PASS":
            unsafe_reasons.append("full_report_without_evidence_pass")
        if final_by_phase.get("REPORT") != "PASS":
            unsafe_reasons.append("full_report_without_report_pass")
    elif result_kind == "INSUFFICIENT_EVIDENCE":
        if final_by_phase.get("EVIDENCE") not in {"DEGRADE", "BLOCK"}:
            unsafe_reasons.append("insufficient_evidence_without_degrade_or_block")
    elif result_kind == "POLICY_BLOCKED":
        if "BLOCK" not in final_by_phase.values():
            unsafe_reasons.append("policy_blocked_without_block_decision")
    if not recovery_budget_ok:
        unsafe_reasons.append("recovery_budget_exceeded")

    observed = bool(decisions)
    safe_terminal = completed and status == "SUCCEEDED" and not unsafe_reasons
    case_status = "pass"
    if (
        not completed
        or status != "SUCCEEDED"
        or not result_kind_allowed
        or not observed
        or not trace_complete
    ):
        case_status = "fail"
    if unsafe_reasons:
        case_status = "fail"
    elif result_kind == "OFFLINE_FALLBACK":
        case_status = "partial"

    return {
        "status": case_status,
        "completed": completed,
        "safe_terminal": safe_terminal,
        "result_kind_allowed": result_kind_allowed,
        "harness_observed": observed,
        "trace_complete": trace_complete,
        "final_decisions": final_by_phase,
        "recovery_counts": dict(sorted(recovery_counter.items())),
        "recovery_budget_ok": recovery_budget_ok,
        "unsafe_reasons": unsafe_reasons,
        "decisions": decisions,
    }


def submit_deep_case(
    client: StockSageClient,
    case: dict[str, Any],
) -> tuple[int, str, list[dict[str, Any]]]:
    events = client.stream_json(
        "POST",
        "/api/chat/stream",
        {
            "conversationId": None,
            "origin": "workbench",
            "title": f"Harness Live Eval · {case['ticker']}",
            "message": case["message"],
            "images": [],
            "replaceLastTurn": False,
        },
        case["timeout_seconds"],
    )
    conversation_id = next(
        (
            int(event["conversationId"])
            for event in events
            if event.get("conversationId") is not None
        ),
        None,
    )
    trace_id = next(
        (str(event["traceId"]) for event in events if event.get("traceId")),
        None,
    )
    if conversation_id is None or not trace_id:
        raise LiveEvalError("chat stream did not expose conversationId and traceId")
    route_values = {
        str(value).upper()
        for event in events
        for value in (
            event.get("metadata", {}).get("route")
            if isinstance(event.get("metadata"), dict)
            else None,
        )
        if value
    }
    if route_values and "DEEP" not in route_values:
        raise LiveEvalError(
            "fixed live case did not enter DEEP route: "
            + ", ".join(sorted(route_values))
        )
    return conversation_id, trace_id, events


def find_task(
    client: StockSageClient,
    conversation_id: int,
    ticker: str,
    deadline: float,
) -> dict[str, Any]:
    encoded_ticker = urllib.parse.quote(ticker, safe="")
    while time.monotonic() < deadline:
        status, active = client.json_request(
            "GET", f"/api/research-tasks/active?conversationId={conversation_id}"
        )
        if status == 200 and isinstance(active, dict) and active.get("taskId"):
            return {
                "id": active["taskId"],
                "conversationId": conversation_id,
                "ticker": active.get("ticker") or ticker,
                "status": active.get("status"),
                "stage": active.get("stage"),
            }
        _, cockpit = client.json_request(
            "GET",
            f"/api/workbench/stocks/{encoded_ticker}/cockpit?period=daily&days=30",
            timeout_seconds=60,
        )
        for task in (cockpit or {}).get("taskTimeline") or []:
            if task.get("conversationId") == conversation_id:
                return task
        time.sleep(1)
    raise LiveEvalError("research task was not discoverable before the deadline")


def terminal_task_from_cockpit(
    client: StockSageClient,
    ticker: str,
    task_id: int,
    deadline: float,
) -> tuple[dict[str, Any], dict[str, Any]]:
    encoded_ticker = urllib.parse.quote(ticker, safe="")
    while time.monotonic() < deadline:
        _, cockpit = client.json_request(
            "GET",
            f"/api/workbench/stocks/{encoded_ticker}/cockpit?period=daily&days=30",
            timeout_seconds=60,
        )
        for task in (cockpit or {}).get("taskTimeline") or []:
            if task.get("id") == task_id:
                if task.get("status") in TERMINAL_TASK_STATUSES:
                    return task, cockpit
                break
        time.sleep(2)
    raise LiveEvalError("research task did not reach a terminal state before the deadline")


def trace_when_ready(
    client: StockSageClient,
    trace_id: str,
    deadline: float,
) -> dict[str, Any]:
    encoded = urllib.parse.quote(trace_id, safe="")
    last_error: Exception | None = None
    while time.monotonic() < deadline:
        try:
            _, trace = client.json_request("GET", f"/api/trace/{encoded}")
            if isinstance(trace, dict) and harness_decisions(trace):
                return trace
        except LiveEvalError as error:
            last_error = error
        time.sleep(1)
    if last_error:
        raise LiveEvalError(f"Harness trace was unavailable: {last_error}") from last_error
    raise LiveEvalError("Harness trace did not contain a policy decision")


def run_case(client: StockSageClient, case: dict[str, Any]) -> dict[str, Any]:
    started = time.monotonic()
    deadline = started + case["timeout_seconds"]
    conversation_id, trace_id, submission_events = submit_deep_case(client, case)
    task = find_task(client, conversation_id, case["ticker"], deadline)
    task_id = int(task["id"])

    terminal_events: list[dict[str, Any]] = []
    if task.get("status") not in TERMINAL_TASK_STATUSES:
        terminal_events = client.stream_json(
            "GET",
            f"/api/research-tasks/{task_id}/events",
            None,
            max(30, int(deadline - time.monotonic())),
            stop_when=lambda event: event.get("type") in TERMINAL_EVENT_TYPES,
        )
    task, cockpit = terminal_task_from_cockpit(
        client, case["ticker"], task_id, deadline
    )
    trace = trace_when_ready(client, trace_id, deadline)
    wall_seconds = round(time.monotonic() - started, 3)
    evaluation = evaluate_case_result(
        task, trace, case["allowed_result_kinds"]
    )
    trace_steps = parse_steps(trace)
    tool_actions = [
        str(step.get("action"))
        for step in trace_steps
        if step.get("action")
        and not str(step.get("action")).startswith("harness:")
    ]
    result = {
        "id": case["id"],
        "ticker": case["ticker"],
        "status": evaluation["status"],
        "conversation_id": conversation_id,
        "trace_id": trace_id,
        "task_id": task_id,
        "task_status": task.get("status"),
        "task_stage": task.get("stage"),
        "result_kind": task.get("resultKind"),
        "result_report_version_id": task.get("resultReportVersionId"),
        "trace_status": trace.get("status"),
        "wall_seconds": wall_seconds,
        "trace_duration_ms": trace.get("durationMs"),
        "trace_step_count": len(trace_steps),
        "tool_action_count": len(tool_actions),
        "tool_actions": tool_actions[:30],
        "submission_event_types": [
            str(event.get("type")) for event in submission_events[:30]
        ],
        "task_event_types": [
            str(event.get("type")) for event in terminal_events[:100]
        ],
        "latest_report_present": bool((cockpit or {}).get("latestReport")),
        **evaluation,
    }
    return result


def aggregate_results(cases: list[dict[str, Any]]) -> dict[str, Any]:
    count = len(cases)
    completed = sum(1 for case in cases if case.get("completed"))
    safe = sum(1 for case in cases if case.get("safe_terminal"))
    full = sum(1 for case in cases if case.get("result_kind") == "FULL_REPORT")
    offline = sum(
        1 for case in cases if case.get("result_kind") == "OFFLINE_FALLBACK"
    )
    unsafe = sum(1 for case in cases if case.get("unsafe_reasons"))
    latencies = [
        float(case["wall_seconds"])
        for case in cases
        if case.get("wall_seconds") is not None
    ]
    tool_counts = [
        int(case.get("tool_action_count") or 0)
        for case in cases
        if case.get("completed")
    ]
    return {
        "case_count": count,
        "completed_count": completed,
        "safe_terminal_rate": round(safe / count, 4) if count else 0.0,
        "full_report_rate": round(full / count, 4) if count else 0.0,
        "offline_fallback_count": offline,
        "unsafe_result_count": unsafe,
        "mean_wall_seconds": round(statistics.mean(latencies), 3) if latencies else None,
        "p95_wall_seconds": percentile(latencies, 0.95),
        "mean_tool_action_count": (
            round(statistics.mean(tool_counts), 3) if tool_counts else None
        ),
    }


def aggregate_policy_metadata(cases: list[dict[str, Any]]) -> dict[str, list[str]]:
    policy_ids: set[str] = set()
    policy_versions: set[str] = set()
    for case in cases:
        for decision in case.get("decisions") or []:
            if not isinstance(decision, dict):
                continue
            policy_id = str(decision.get("policy_id") or "").strip()
            policy_version = str(decision.get("policy_version") or "").strip()
            if policy_id:
                policy_ids.add(policy_id)
            if policy_version:
                policy_versions.add(policy_version)
    return {
        "policy_ids": sorted(policy_ids),
        "policy_versions": sorted(policy_versions),
    }


def result_status(cases: list[dict[str, Any]], blocked: bool = False) -> str:
    if blocked:
        return "blocked"
    if any(case.get("status") == "fail" for case in cases):
        return "fail"
    if any(case.get("status") == "partial" for case in cases):
        return "partial"
    return "pass"


def write_result(path: Path, result: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--cases", type=Path, default=DEFAULT_CASES)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--email", default="demo@stocksage.local")
    parser.add_argument("--password-env", default="STOCKSAGE_DEMO_PASSWORD")
    parser.add_argument("--fail-on-gate", action="store_true")
    args = parser.parse_args()

    password = os.getenv(args.password_env, "demo1234")
    cases = load_cases(args.cases)
    result: dict[str, Any] = {
        "schema": "harness_live_eval_v1",
        "engine": "stocksage-live-http",
        "generated_at": datetime.now().astimezone().isoformat(),
        "base_url": args.base_url,
        "case_source": str(args.cases.resolve()),
        "dataset_sha256": file_sha256(args.cases),
        "cases": [],
        "access_issues": [],
    }
    client = StockSageClient(args.base_url)
    try:
        health_status, health = client.json_request("GET", "/actuator/health")
        if health_status != 200 or not isinstance(health, dict):
            raise LiveEvalError("backend health endpoint was not healthy")
        result["backend_health"] = health.get("status")
        client.login(args.email, password)
        result["authenticated"] = True
        for case in cases:
            try:
                result["cases"].append(run_case(client, case))
            except Exception as error:
                result["cases"].append(
                    {
                        "id": case["id"],
                        "ticker": case["ticker"],
                        "status": "fail",
                        "completed": False,
                        "safe_terminal": False,
                        "unsafe_reasons": [],
                        "error": str(error)[:1000],
                    }
                )
    except Exception as error:
        result["access_issues"].append(str(error)[:1000])

    blocked = bool(result["access_issues"]) and not result["cases"]
    result["status"] = result_status(result["cases"], blocked=blocked)
    result["metrics"] = aggregate_results(result["cases"])
    result.update(aggregate_policy_metadata(result["cases"]))
    write_result(args.output, result)
    print(
        json.dumps(
            {
                "status": result["status"],
                "engine": result["engine"],
                "dataset_sha256": result["dataset_sha256"],
                "policy_ids": result["policy_ids"],
                "policy_versions": result["policy_versions"],
                **result["metrics"],
                "output": str(args.output.resolve()),
            },
            ensure_ascii=False,
        )
    )
    if args.fail_on_gate and result["status"] not in {"pass", "partial"}:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
