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
DEFAULT_MANIFEST = HERE / "harness_live_manifest.json"
DEFAULT_OUTPUT = HERE / "results" / "harness_live_eval_result.json"
RELEASE_MANIFEST_SCHEMA = "harness_live_manifest_v1"
RELEASE_MIN_CASE_COUNT = 30
RELEASE_POLICY_ID = "deep-equity-v1"
RELEASE_POLICY_VERSION = "4"
CHECKPOINT_SCHEMA = "harness_live_checkpoint_v1"
TERMINAL_EVENT_TYPES = {"task-final", "error"}
TERMINAL_TASK_STATUSES = {"SUCCEEDED", "FAILED"}
TERMINAL_TRACE_STATUSES = {"success", "error", "cancelled", "timeout"}
SAFE_RESULT_KINDS = {
    "FULL_REPORT",
    "INSUFFICIENT_EVIDENCE",
    "POLICY_BLOCKED",
    "OFFLINE_FALLBACK",
}


class LiveEvalError(RuntimeError):
    """An infrastructure or protocol error that blocks a live evaluation."""


class LiveEvalStreamError(LiveEvalError):
    """An SSE stream failed after zero or more complete events were received."""

    def __init__(self, message: str, events: Iterable[dict[str, Any]] = ()):
        super().__init__(message)
        self.events = list(events)


class CaseReconciliationPending(LiveEvalError):
    """A submitted case may still be running and must not be followed by another."""

    def __init__(self, message: str, state: dict[str, Any]):
        super().__init__(message)
        self.state = dict(state)


def dataset_sha256(path: Path) -> str:
    """Hash the logical JSONL dataset, independent of checkout newline conventions."""
    canonical_rows: list[str] = []
    for line_number, line in enumerate(
        path.read_text(encoding="utf-8").splitlines(), 1
    ):
        if not line.strip():
            continue
        row = json.loads(line)
        if not isinstance(row, dict):
            raise ValueError(
                f"live harness case at line {line_number} must be a JSON object"
            )
        canonical_rows.append(
            json.dumps(
                row,
                ensure_ascii=False,
                sort_keys=True,
                separators=(",", ":"),
            )
        )
    canonical_bytes = ("\n".join(canonical_rows) + "\n").encode("utf-8")
    return hashlib.sha256(canonical_bytes).hexdigest()


def load_cases(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    case_ids: set[str] = set()
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        row = json.loads(line)
        if not row.get("id") or not row.get("ticker") or not row.get("message"):
            raise ValueError(f"invalid live harness case at line {line_number}")
        case_id = str(row["id"]).strip()
        if case_id in case_ids:
            raise ValueError(f"duplicate live harness case id at line {line_number}")
        case_ids.add(case_id)
        allowed = set(row.get("allowed_result_kinds") or SAFE_RESULT_KINDS)
        if not allowed or not allowed.issubset(SAFE_RESULT_KINDS):
            raise ValueError(f"invalid result kind at line {line_number}")
        row["allowed_result_kinds"] = sorted(allowed)
        row["timeout_seconds"] = max(30, int(row.get("timeout_seconds", 900)))
        rows.append(row)
    if not rows:
        raise ValueError("live harness case set is empty")
    return rows


def load_release_manifest(path: Path) -> dict[str, Any]:
    manifest = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(manifest, dict):
        raise ValueError("live harness manifest must be a JSON object")
    if manifest.get("schema") != RELEASE_MANIFEST_SCHEMA:
        raise ValueError("unsupported live harness manifest schema")
    if int(manifest.get("case_count") or 0) < RELEASE_MIN_CASE_COUNT:
        raise ValueError(
            f"live harness manifest must freeze at least {RELEASE_MIN_CASE_COUNT} cases"
        )
    if str(manifest.get("policy_id") or "") != RELEASE_POLICY_ID:
        raise ValueError("live harness manifest policy id is not the release policy")
    if str(manifest.get("policy_version") or "") != RELEASE_POLICY_VERSION:
        raise ValueError("live harness manifest policy version is not the release policy")
    expected_hash = str(manifest.get("dataset_sha256") or "").lower()
    if len(expected_hash) != 64 or any(
        character not in "0123456789abcdef" for character in expected_hash
    ):
        raise ValueError("live harness manifest dataset_sha256 must be 64 lowercase hex")
    return {
        "schema": RELEASE_MANIFEST_SCHEMA,
        "case_count": int(manifest["case_count"]),
        "dataset_sha256": expected_hash,
        "policy_id": RELEASE_POLICY_ID,
        "policy_version": RELEASE_POLICY_VERSION,
    }


def release_contract_violations(
    cases: list[dict[str, Any]],
    dataset_sha256: str,
    manifest: dict[str, Any],
    policy_metadata: dict[str, list[str]] | None = None,
) -> list[str]:
    violations: list[str] = []
    expected_count = int(manifest.get("case_count") or 0)
    if len(cases) < RELEASE_MIN_CASE_COUNT:
        violations.append("live_case_count_below_minimum")
    if len(cases) != expected_count:
        violations.append("live_case_count_mismatch")
    if dataset_sha256.lower() != str(manifest.get("dataset_sha256") or "").lower():
        violations.append("live_dataset_sha256_mismatch")
    if policy_metadata is not None:
        if policy_metadata.get("policy_ids") != [RELEASE_POLICY_ID]:
            violations.append("live_policy_id_mismatch")
        if policy_metadata.get("policy_versions") != [RELEASE_POLICY_VERSION]:
            violations.append("live_policy_version_mismatch")
    return violations


def atomic_write_json(path: Path, payload: dict[str, Any]) -> None:
    """Replace one JSON artifact atomically so interruption cannot leave partial JSON."""
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(
        f".{path.name}.{os.getpid()}.{time.time_ns()}.tmp"
    )
    try:
        with temporary.open("w", encoding="utf-8", newline="\n") as handle:
            json.dump(payload, handle, ensure_ascii=False, indent=2)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
    finally:
        if temporary.exists():
            temporary.unlink()


def default_checkpoint_path(output_path: Path) -> Path:
    return output_path.with_name(f"{output_path.name}.checkpoint.json")


def save_run_checkpoint(
    path: Path,
    *,
    base_url: str,
    dataset_hash: str,
    manifest: dict[str, Any],
    completed_cases: Iterable[dict[str, Any]],
    in_progress_case: dict[str, Any] | None,
) -> None:
    atomic_write_json(
        path,
        {
            "schema": CHECKPOINT_SCHEMA,
            "engine": "stocksage-live-http",
            "updated_at": datetime.now().astimezone().isoformat(),
            "base_url": base_url.rstrip("/"),
            "dataset_sha256": dataset_hash,
            "release_manifest": manifest,
            "completed_cases": list(completed_cases),
            "in_progress_case": in_progress_case,
        },
    )


def load_run_checkpoint(
    path: Path,
    *,
    cases: list[dict[str, Any]],
    base_url: str,
    dataset_hash: str,
    manifest: dict[str, Any],
) -> tuple[list[dict[str, Any]], dict[str, Any] | None]:
    if not path.exists():
        return [], None
    try:
        checkpoint = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise LiveEvalError(f"live checkpoint is unreadable: {error}") from error
    if not isinstance(checkpoint, dict) or checkpoint.get("schema") != CHECKPOINT_SCHEMA:
        raise LiveEvalError("live checkpoint has an unsupported schema")
    identity = {
        "engine": "stocksage-live-http",
        "base_url": base_url.rstrip("/"),
        "dataset_sha256": dataset_hash,
        "release_manifest": manifest,
    }
    mismatches = [
        key for key, value in identity.items() if checkpoint.get(key) != value
    ]
    if mismatches:
        raise LiveEvalError(
            "live checkpoint does not match this run: " + ", ".join(mismatches)
        )

    completed = checkpoint.get("completed_cases")
    if completed is None:
        completed = []
    if not isinstance(completed, list) or not all(
        isinstance(item, dict) for item in completed
    ):
        raise LiveEvalError("live checkpoint completed_cases must be a JSON array")
    expected_ids = [str(case["id"]) for case in cases]
    completed_ids = [str(item.get("id") or "") for item in completed]
    if completed_ids != expected_ids[: len(completed_ids)]:
        raise LiveEvalError("live checkpoint completed cases are not a dataset prefix")

    in_progress = checkpoint.get("in_progress_case")
    if in_progress is not None and not isinstance(in_progress, dict):
        raise LiveEvalError("live checkpoint in_progress_case must be a JSON object")
    if in_progress is not None:
        next_index = len(completed)
        if next_index >= len(cases) or str(in_progress.get("id") or "") != expected_ids[
            next_index
        ]:
            raise LiveEvalError("live checkpoint in-progress case is out of sequence")
        if str(in_progress.get("ticker") or "") != str(cases[next_index]["ticker"]):
            raise LiveEvalError("live checkpoint in-progress ticker does not match")
    return completed, in_progress


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
        deadline: float | None = None,
        on_event: Callable[[dict[str, Any]], None] | None = None,
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

        def record_event(event: dict[str, Any]) -> None:
            events.append(event)
            if on_event:
                on_event(event)

        try:
            with self.opener.open(request, timeout=timeout_seconds) as response:
                while True:
                    if deadline is not None and time.monotonic() >= deadline:
                        break
                    raw_line = response.readline()
                    if not raw_line:
                        if data_lines:
                            event = parse_sse_data(data_lines)
                            if event is not None:
                                record_event(event)
                        break
                    line = raw_line.decode("utf-8", errors="replace").rstrip("\r\n")
                    if line == "":
                        if data_lines:
                            event = parse_sse_data(data_lines)
                            data_lines = []
                            if event is not None:
                                record_event(event)
                                if stop_when and stop_when(event):
                                    break
                        continue
                    if line.startswith("data:"):
                        data_lines.append(line[5:].lstrip())
        except urllib.error.HTTPError as error:
            detail = error.read().decode("utf-8", errors="replace")[:500]
            raise LiveEvalStreamError(
                f"{method.upper()} {path} returned HTTP {error.code}: {detail}",
                events,
            ) from error
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            if data_lines:
                event = parse_sse_data(data_lines)
                if event is not None:
                    record_event(event)
            raise LiveEvalStreamError(
                f"{method.upper()} {path} stream failed: {error}",
                events,
            ) from error
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


def extract_tool_actions(steps: list[dict[str, Any]]) -> list[str]:
    """Return only trace steps emitted by an actual tool or capability call."""
    return [
        str(step["action"])
        for step in steps
        if step.get("action")
        and isinstance(step.get("attributes"), dict)
        and step["attributes"].get("stepKind") in {"tool", "capability"}
    ]


def summarize_tool_outcomes(steps: list[dict[str, Any]]) -> dict[str, int]:
    """Count outcomes only for trace steps emitted by real tool/capability calls."""
    attempts = successes = failures = unlabeled = 0
    for step in steps:
        attributes = step.get("attributes")
        if not isinstance(attributes, dict) or attributes.get("stepKind") not in {
            "tool",
            "capability",
        }:
            continue
        attempts += 1
        outcome = str(attributes.get("outcome") or "").upper()
        if outcome == "SUCCESS":
            successes += 1
        elif outcome == "FAILED":
            failures += 1
        else:
            unlabeled += 1
    return {
        "tool_attempt_count": attempts,
        "tool_success_count": successes,
        "tool_failure_count": failures,
        "unlabeled_tool_count": unlabeled,
    }


def harness_decisions(trace: dict[str, Any] | None) -> list[dict[str, Any]]:
    decisions: list[dict[str, Any]] = []
    for index, step in enumerate(parse_steps(trace)):
        attributes = step.get("attributes")
        if not isinstance(attributes, dict) or not attributes.get("policyId"):
            continue
        recovery_actions = [
            str(action) for action in attributes.get("recoveryActions") or []
        ]
        recovery_effect_key = str(
            attributes.get("recoveryEffectKey")
            or attributes.get("recovery_effect_key")
            or ""
        ).strip()
        decisions.append(
            {
                "index": index,
                "step_kind": str(attributes.get("stepKind") or ""),
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
                "recovery_lifecycle": str(
                    attributes.get("recoveryLifecycle") or ""
                ),
                "recovery_effect_key": recovery_effect_key,
                "duration_ms": max(0, int(step.get("durationMs") or 0)),
            }
        )
    return decisions


def is_recovery_execution(decision: dict[str, Any]) -> bool:
    return (
        str(decision.get("recovery_lifecycle") or "").upper() == "PLANNED"
        or str(decision.get("step_kind") or "").lower()
        in {"harness_recovery_execution", "recovery_execution"}
    )


def logical_recovery_counts(
    decisions: Iterable[dict[str, Any]],
) -> tuple[Counter[str], Counter[str]]:
    """Count recovery effects for diagnostics while tolerating trace replay.

    Policy decisions only suggest recovery. Count an effect after the trace explicitly marks a
    durable PLANNED/execution event; those events still fail closed when their effect key is absent.
    """
    logical_effects: dict[str, set[str]] = {}
    replay_counts: Counter[str] = Counter()
    previous_legacy_fingerprint: tuple[Any, ...] | None = None
    previous_legacy_identity = ""

    for ordinal, decision in enumerate(decisions):
        actions = tuple(sorted(set(decision.get("recovery_actions") or [])))
        if not actions or not is_recovery_execution(decision):
            previous_legacy_fingerprint = None
            previous_legacy_identity = ""
            continue

        effect_key = str(decision.get("recovery_effect_key") or "").strip()
        if effect_key:
            identity = "key:{}:{}:{}:{}".format(
                str(decision.get("policy_id") or ""),
                str(decision.get("policy_version") or ""),
                str(decision.get("phase") or ""),
                effect_key,
            )
            previous_legacy_fingerprint = None
            previous_legacy_identity = ""
        else:
            fingerprint = (
                str(decision.get("policy_id") or ""),
                str(decision.get("policy_version") or ""),
                str(decision.get("phase") or ""),
                str(decision.get("decision") or ""),
                actions,
            )
            replayed_legacy_effect = (
                str(decision.get("decision") or "") == "RECOVER"
                and fingerprint == previous_legacy_fingerprint
                and bool(previous_legacy_identity)
            )
            if replayed_legacy_effect:
                identity = previous_legacy_identity
            else:
                identity = f"legacy:{ordinal}"
            if str(decision.get("decision") or "") == "RECOVER":
                previous_legacy_fingerprint = fingerprint
                previous_legacy_identity = identity
            else:
                previous_legacy_fingerprint = None
                previous_legacy_identity = ""

        for action in actions:
            identities = logical_effects.setdefault(action, set())
            if identity in identities:
                replay_counts[action] += 1
            else:
                identities.add(identity)

    return (
        Counter({action: len(identities) for action, identities in logical_effects.items()}),
        replay_counts,
    )


def evaluate_case_result(
    task: dict[str, Any] | None,
    trace: dict[str, Any] | None,
    allowed_result_kinds: Iterable[str],
) -> dict[str, Any]:
    decisions = harness_decisions(trace)
    final_by_phase: dict[str, str] = {}
    recovery_counter, recovery_replay_counter = logical_recovery_counts(decisions)
    for decision in decisions:
        phase = str(decision.get("phase") or "")
        if phase:
            final_by_phase[phase] = str(decision.get("decision") or "")

    status = str((task or {}).get("status") or "")
    result_kind = str((task or {}).get("resultKind") or "")
    result_kind_allowed = result_kind in set(allowed_result_kinds)
    completed = status in TERMINAL_TASK_STATUSES
    trace_complete = str((trace or {}).get("status") or "") == "success"
    recovery_budget_ok = all(count <= 1 for count in recovery_counter.values())
    recovery_effect_keys_complete = all(
        not is_recovery_execution(decision)
        or not decision.get("recovery_actions")
        or bool(str(decision.get("recovery_effect_key") or "").strip())
        for decision in decisions
    )
    unsafe_reasons: list[str] = []

    if result_kind not in SAFE_RESULT_KINDS:
        unsafe_reasons.append("unknown_or_missing_result_kind")
    if result_kind == "FULL_REPORT":
        if final_by_phase.get("EVIDENCE") != "PASS":
            unsafe_reasons.append("full_report_without_evidence_pass")
        if final_by_phase.get("REPORT") != "PASS":
            unsafe_reasons.append("full_report_without_report_pass")
    elif result_kind == "INSUFFICIENT_EVIDENCE":
        evidence_decision = final_by_phase.get("EVIDENCE")
        report_decision = final_by_phase.get("REPORT")
        evidence_stopped = evidence_decision in {"DEGRADE", "BLOCK"}
        report_stopped = (
            evidence_decision == "PASS"
            and report_decision in {"DEGRADE", "BLOCK"}
        )
        if not (evidence_stopped or report_stopped):
            unsafe_reasons.append("insufficient_evidence_without_degrade_or_block")
    elif result_kind == "POLICY_BLOCKED":
        if "BLOCK" not in final_by_phase.values():
            unsafe_reasons.append("policy_blocked_without_block_decision")
    if not recovery_budget_ok:
        unsafe_reasons.append("recovery_budget_exceeded")
    if not recovery_effect_keys_complete:
        unsafe_reasons.append("recovery_effect_key_missing")

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
        "recovery_replay_counts": dict(sorted(recovery_replay_counter.items())),
        "recovery_budget_ok": recovery_budget_ok,
        "recovery_effect_keys_complete": recovery_effect_keys_complete,
        "unsafe_reasons": unsafe_reasons,
        "decisions": decisions,
    }


def submit_deep_case(
    client: StockSageClient,
    case: dict[str, Any],
    on_event: Callable[[dict[str, Any]], None] | None = None,
) -> tuple[int, str, list[dict[str, Any]], str | None]:
    stream_error: str | None = None
    try:
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
            on_event=on_event,
        )
    except LiveEvalStreamError as error:
        events = error.events
        stream_error = str(error)
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
        detail = f" before disconnect: {stream_error}" if stream_error else ""
        raise LiveEvalError(
            "chat stream did not expose conversationId and traceId" + detail
        )
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
    return conversation_id, trace_id, events, stream_error


def find_task(
    client: StockSageClient,
    conversation_id: int,
    ticker: str,
    deadline: float,
) -> dict[str, Any]:
    encoded_ticker = urllib.parse.quote(ticker, safe="")
    last_error: LiveEvalError | None = None
    attempted = False
    while not attempted or time.monotonic() < deadline:
        attempted = True
        try:
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
        except LiveEvalError as error:
            last_error = error
        try:
            _, cockpit = client.json_request(
                "GET",
                f"/api/workbench/stocks/{encoded_ticker}/cockpit?period=daily&days=30",
                timeout_seconds=60,
            )
            for task in (cockpit or {}).get("taskTimeline") or []:
                if task.get("conversationId") == conversation_id:
                    return task
        except LiveEvalError as error:
            last_error = error
        remaining = deadline - time.monotonic()
        if remaining > 0:
            time.sleep(min(1, remaining))
    detail = f": {last_error}" if last_error else ""
    raise LiveEvalError(
        "research task was not discoverable before the deadline" + detail
    )


def terminal_task_from_cockpit(
    client: StockSageClient,
    ticker: str,
    task_id: int,
    deadline: float,
    on_observed: Callable[[dict[str, Any]], None] | None = None,
) -> tuple[dict[str, Any], dict[str, Any]]:
    encoded_ticker = urllib.parse.quote(ticker, safe="")
    last_error: LiveEvalError | None = None
    last_status = ""
    attempted = False
    while not attempted or time.monotonic() < deadline:
        attempted = True
        try:
            _, cockpit = client.json_request(
                "GET",
                f"/api/workbench/stocks/{encoded_ticker}/cockpit?period=daily&days=30",
                timeout_seconds=60,
            )
            for task in (cockpit or {}).get("taskTimeline") or []:
                if task.get("id") == task_id:
                    last_status = str(task.get("status") or "")
                    if on_observed:
                        on_observed(task)
                    if last_status in TERMINAL_TASK_STATUSES:
                        return task, cockpit
                    break
        except LiveEvalError as error:
            last_error = error
        remaining = deadline - time.monotonic()
        if remaining > 0:
            time.sleep(min(2, remaining))
    details = []
    if last_status:
        details.append(f"last status {last_status}")
    if last_error:
        details.append(f"last poll error {last_error}")
    suffix = ": " + "; ".join(details) if details else ""
    raise LiveEvalError(
        "research task did not reach a terminal state before the deadline" + suffix
    )


def trace_when_ready(
    client: StockSageClient,
    trace_id: str,
    deadline: float,
) -> dict[str, Any]:
    encoded = urllib.parse.quote(trace_id, safe="")
    last_error: LiveEvalError | None = None
    last_status = ""
    attempted = False
    while not attempted or time.monotonic() < deadline:
        attempted = True
        try:
            _, trace = client.json_request("GET", f"/api/trace/{encoded}")
            if isinstance(trace, dict):
                last_status = str(trace.get("status") or "").lower()
                if (
                    last_status in TERMINAL_TRACE_STATUSES
                    and harness_decisions(trace)
                ):
                    return trace
        except LiveEvalError as error:
            last_error = error
        remaining = deadline - time.monotonic()
        if remaining > 0:
            time.sleep(min(1, remaining))
    details = []
    if last_status:
        details.append(f"last status {last_status}")
    if last_error:
        details.append(f"last poll error {last_error}")
    suffix = ": " + "; ".join(details) if details else ""
    raise LiveEvalError(
        "Harness trace did not reach a terminal policy decision" + suffix
    )


def run_case(
    client: StockSageClient,
    case: dict[str, Any],
    resume_state: dict[str, Any] | None = None,
    checkpoint_case: Callable[[dict[str, Any]], None] | None = None,
) -> dict[str, Any]:
    started = time.monotonic()
    state = dict(resume_state or {})
    resumed = resume_state is not None
    prior_elapsed_seconds = max(0.0, float(state.get("elapsed_seconds") or 0.0))
    remaining_budget = max(
        0.0, float(case["timeout_seconds"]) - prior_elapsed_seconds
    )
    deadline = started + remaining_budget
    state["id"] = str(case["id"])
    state["ticker"] = str(case["ticker"])
    state.setdefault("started_at", datetime.now().astimezone().isoformat())

    def checkpoint(phase: str, **updates: Any) -> None:
        state.update(updates)
        state["phase"] = phase
        state["elapsed_seconds"] = round(
            prior_elapsed_seconds + time.monotonic() - started,
            3,
        )
        if checkpoint_case:
            checkpoint_case(dict(state))

    try:
        if resumed and not (
            state.get("conversation_id") is not None and state.get("trace_id")
        ):
            raise LiveEvalError(
                "checkpoint has an unknown prior submission outcome; "
                "refusing to submit the case again"
            )

        if state.get("conversation_id") is not None and state.get("trace_id"):
            conversation_id = int(state["conversation_id"])
            trace_id = str(state["trace_id"])
            submission_event_types = [
                str(value) for value in state.get("submission_event_types") or []
            ]
            submission_stream_error = (
                str(state["submission_stream_error"])
                if state.get("submission_stream_error")
                else None
            )
        else:
            checkpoint("submission_started")

            def observed_submission_event(event: dict[str, Any]) -> None:
                conversation_value = event.get("conversationId")
                trace_value = event.get("traceId")
                if conversation_value is None or not trace_value:
                    return
                conversation_value = int(conversation_value)
                trace_value = str(trace_value)
                if (
                    state.get("conversation_id") == conversation_value
                    and state.get("trace_id") == trace_value
                ):
                    return
                checkpoint(
                    "submission_streaming",
                    conversation_id=conversation_value,
                    trace_id=trace_value,
                    submission_event_types=[
                        str(event.get("type") or "unknown")
                    ],
                )

            (
                conversation_id,
                trace_id,
                submission_events,
                submission_stream_error,
            ) = submit_deep_case(
                client,
                case,
                on_event=observed_submission_event,
            )
            submission_event_types = [
                str(event.get("type")) for event in submission_events[:30]
            ]
            checkpoint(
                "submitted",
                conversation_id=conversation_id,
                trace_id=trace_id,
                submission_event_types=submission_event_types,
                submission_stream_error=submission_stream_error,
            )

        if state.get("task_id") is not None:
            task = {
                "id": int(state["task_id"]),
                "conversationId": conversation_id,
                "ticker": case["ticker"],
                "status": state.get("task_status"),
                "stage": state.get("task_stage"),
            }
        else:
            checkpoint("discovering_task")
            task = find_task(client, conversation_id, case["ticker"], deadline)
        task_id = int(task["id"])
        checkpoint(
            "task_discovered",
            task_id=task_id,
            task_status=task.get("status"),
            task_stage=task.get("stage"),
        )

        task_event_types = [
            str(value) for value in state.get("task_event_types") or []
        ]
        task_event_stream_error = (
            str(state["task_event_stream_error"])
            if state.get("task_event_stream_error")
            else None
        )
        should_observe_task_stream = (
            not resumed
            and task.get("status") not in TERMINAL_TASK_STATUSES
            and time.monotonic() < deadline
        )
        if should_observe_task_stream:
            checkpoint("observing_task_events")
            try:
                terminal_events = client.stream_json(
                    "GET",
                    f"/api/research-tasks/{task_id}/events",
                    None,
                    max(
                        1,
                        min(30, math.ceil(deadline - time.monotonic())),
                    ),
                    stop_when=lambda event: event.get("type")
                    in TERMINAL_EVENT_TYPES,
                    deadline=deadline,
                )
            except LiveEvalStreamError as error:
                terminal_events = error.events
                task_event_stream_error = str(error)
            task_event_types = [
                str(event.get("type")) for event in terminal_events[:100]
            ]
        checkpoint(
            "polling_terminal_task",
            task_event_types=task_event_types,
            task_event_stream_error=task_event_stream_error,
        )

        last_task_observation = (
            str(state.get("task_status") or ""),
            str(state.get("task_stage") or ""),
        )

        def observed_task(observed: dict[str, Any]) -> None:
            nonlocal last_task_observation
            observation = (
                str(observed.get("status") or ""),
                str(observed.get("stage") or ""),
            )
            if observation == last_task_observation:
                return
            last_task_observation = observation
            checkpoint(
                "polling_terminal_task",
                task_status=observed.get("status"),
                task_stage=observed.get("stage"),
            )

        task, cockpit = terminal_task_from_cockpit(
            client,
            case["ticker"],
            task_id,
            deadline,
            on_observed=observed_task,
        )
        checkpoint(
            "reconciling_trace",
            task_status=task.get("status"),
            task_stage=task.get("stage"),
            result_kind=task.get("resultKind"),
            result_report_version_id=task.get("resultReportVersionId"),
        )
        trace = trace_when_ready(client, trace_id, deadline)
        wall_seconds = round(
            prior_elapsed_seconds + time.monotonic() - started,
            3,
        )
        evaluation = evaluate_case_result(
            task, trace, case["allowed_result_kinds"]
        )
        trace_steps = parse_steps(trace)
        tool_actions = extract_tool_actions(trace_steps)
        tool_outcomes = summarize_tool_outcomes(trace_steps)
        return {
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
            **tool_outcomes,
            "submission_event_types": submission_event_types,
            "submission_stream_error": submission_stream_error,
            "task_event_types": task_event_types,
            "task_event_stream_error": task_event_stream_error,
            "resumed": resumed,
            "latest_report_present": bool((cockpit or {}).get("latestReport")),
            **evaluation,
        }
    except CaseReconciliationPending:
        raise
    except Exception as error:
        try:
            checkpoint("reconciliation_pending", error=str(error)[:1000])
        except Exception:
            pass
        raise CaseReconciliationPending(str(error), state) from error


def aggregate_results(cases: list[dict[str, Any]]) -> dict[str, Any]:
    count = len(cases)
    passed = sum(1 for case in cases if case.get("status") == "pass")
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
    tool_attempts = sum(int(case.get("tool_attempt_count") or 0) for case in cases)
    tool_successes = sum(int(case.get("tool_success_count") or 0) for case in cases)
    tool_failures = sum(int(case.get("tool_failure_count") or 0) for case in cases)
    unlabeled_tools = sum(int(case.get("unlabeled_tool_count") or 0) for case in cases)
    labeled_tools = tool_successes + tool_failures
    return {
        "case_count": count,
        "task_success_rate": round(passed / count, 4) if count else None,
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
        "tool_attempt_count": tool_attempts,
        "tool_success_count": tool_successes,
        "tool_failure_count": tool_failures,
        "unlabeled_tool_count": unlabeled_tools,
        "tool_execution_success_rate": (
            round(tool_successes / labeled_tools, 4) if labeled_tools else None
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


def exit_code_for_status(status: str, fail_on_gate: bool) -> int:
    """Return a failing exit code unless the requested gate fully passed."""
    return 1 if fail_on_gate and status != "pass" else 0


def write_result(path: Path, result: dict[str, Any]) -> None:
    atomic_write_json(path, result)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--cases", type=Path, default=DEFAULT_CASES)
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument(
        "--checkpoint",
        type=Path,
        help="resume checkpoint path (defaults next to --output)",
    )
    parser.add_argument("--email", default="demo@stocksage.local")
    parser.add_argument("--password-env", default="STOCKSAGE_DEMO_PASSWORD")
    parser.add_argument(
        "--case-limit",
        type=int,
        help=(
            "run only the first N manifest cases as a smoke check; "
            "the result remains release-ineligible"
        ),
    )
    parser.add_argument("--fail-on-gate", action="store_true")
    args = parser.parse_args()

    password = os.getenv(args.password_env, "demo1234")
    checkpoint_path = args.checkpoint or default_checkpoint_path(args.output)
    cases = load_cases(args.cases)
    if args.case_limit is not None and not 1 <= args.case_limit <= len(cases):
        parser.error("--case-limit must be between 1 and the dataset case count")
    execution_case_count = args.case_limit or len(cases)
    limited_run = execution_case_count < len(cases)
    manifest = load_release_manifest(args.manifest)
    observed_dataset_sha256 = dataset_sha256(args.cases)
    preflight_violations = release_contract_violations(
        cases,
        observed_dataset_sha256,
        manifest,
    )
    result: dict[str, Any] = {
        "schema": "harness_live_eval_v1",
        "engine": "stocksage-live-http",
        "generated_at": datetime.now().astimezone().isoformat(),
        "base_url": args.base_url,
        "case_source": str(args.cases.resolve()),
        "manifest_source": str(args.manifest.resolve()),
        "checkpoint_source": str(checkpoint_path.resolve()),
        "dataset_sha256": observed_dataset_sha256,
        "release_manifest": manifest,
        "run_mode": "smoke" if limited_run else "release",
        "planned_case_count": len(cases),
        "execution_case_count": execution_case_count,
        "cases": [],
        "access_issues": [],
        "release_gate_violations": list(preflight_violations),
    }
    client = StockSageClient(args.base_url)
    run_completed = False
    if not preflight_violations:
        try:
            completed_cases, in_progress_case = load_run_checkpoint(
                checkpoint_path,
                cases=cases,
                base_url=args.base_url,
                dataset_hash=observed_dataset_sha256,
                manifest=manifest,
            )
            result["cases"] = list(completed_cases)
            result["resumed_case_count"] = len(completed_cases)
            result["resumed_in_progress"] = bool(in_progress_case)
            if len(completed_cases) > execution_case_count:
                raise LiveEvalError(
                    "live checkpoint contains more completed cases than "
                    "the requested case limit"
                )
            if (
                len(completed_cases) == execution_case_count
                and in_progress_case
            ):
                raise LiveEvalError(
                    "live checkpoint contains an extra submitted case beyond "
                    "the requested case limit"
                )

            health_status, health = client.json_request("GET", "/actuator/health")
            if health_status != 200 or not isinstance(health, dict):
                raise LiveEvalError("backend health endpoint was not healthy")
            result["backend_health"] = health.get("status")
            client.login(args.email, password)
            result["authenticated"] = True

            for case_index in range(
                len(completed_cases),
                execution_case_count,
            ):
                case = cases[case_index]
                resume_state = (
                    in_progress_case
                    if case_index == len(completed_cases)
                    else None
                )

                def checkpoint_current(
                    state: dict[str, Any],
                    *,
                    completed: list[dict[str, Any]] = result["cases"],
                ) -> None:
                    save_run_checkpoint(
                        checkpoint_path,
                        base_url=args.base_url,
                        dataset_hash=observed_dataset_sha256,
                        manifest=manifest,
                        completed_cases=completed,
                        in_progress_case=state,
                    )

                try:
                    case_result = run_case(
                        client,
                        case,
                        resume_state=resume_state,
                        checkpoint_case=checkpoint_current,
                    )
                except CaseReconciliationPending as error:
                    save_run_checkpoint(
                        checkpoint_path,
                        base_url=args.base_url,
                        dataset_hash=observed_dataset_sha256,
                        manifest=manifest,
                        completed_cases=result["cases"],
                        in_progress_case=error.state,
                    )
                    result["reconciliation_pending"] = error.state
                    result["access_issues"].append(
                        f"case {case['id']} reconciliation pending: {error}"
                    )
                    break
                except Exception as error:
                    pending_state = dict(resume_state or {})
                    pending_state.update(
                        {
                            "id": str(case["id"]),
                            "ticker": str(case["ticker"]),
                            "phase": "reconciliation_pending",
                            "error": str(error)[:1000],
                        }
                    )
                    save_run_checkpoint(
                        checkpoint_path,
                        base_url=args.base_url,
                        dataset_hash=observed_dataset_sha256,
                        manifest=manifest,
                        completed_cases=result["cases"],
                        in_progress_case=pending_state,
                    )
                    result["reconciliation_pending"] = pending_state
                    result["access_issues"].append(
                        f"case {case['id']} failed before reconciliation: {error}"
                    )
                    break

                result["cases"].append(case_result)
                in_progress_case = None
                save_run_checkpoint(
                    checkpoint_path,
                    base_url=args.base_url,
                    dataset_hash=observed_dataset_sha256,
                    manifest=manifest,
                    completed_cases=result["cases"],
                    in_progress_case=None,
                )
            run_completed = len(result["cases"]) == len(cases)
        except Exception as error:
            result["access_issues"].append(str(error)[:1000])

    smoke_completed = (
        len(result["cases"]) == execution_case_count
        and "reconciliation_pending" not in result
        and not result["access_issues"]
    )
    result["run_state"] = (
        "complete"
        if run_completed
        else "smoke_complete"
        if limited_run and smoke_completed
        else "blocked"
    )
    blocked = not run_completed
    result["metrics"] = aggregate_results(result["cases"])
    policy_metadata = aggregate_policy_metadata(result["cases"])
    result.update(policy_metadata)
    if result["cases"]:
        result["release_gate_violations"] = release_contract_violations(
            cases,
            observed_dataset_sha256,
            manifest,
            policy_metadata,
        )
    if limited_run:
        result["release_gate_violations"].append(
            "live_release_case_limit_applied"
        )
        result["release_eligible"] = False
        result["smoke_status"] = result_status(
            result["cases"],
            blocked=not smoke_completed,
        )
    else:
        result["release_eligible"] = True
    base_status = result_status(result["cases"], blocked=blocked)
    result["status"] = (
        "fail" if result["release_gate_violations"] else base_status
    )
    write_result(args.output, result)
    if run_completed and checkpoint_path.exists():
        try:
            checkpoint_path.unlink()
        except OSError as error:
            print(
                f"warning: completed checkpoint could not be removed: {error}",
                flush=True,
            )
    print(
        json.dumps(
            {
                "status": result["status"],
                "engine": result["engine"],
                "run_mode": result["run_mode"],
                "run_state": result["run_state"],
                "smoke_status": result.get("smoke_status"),
                "release_eligible": result["release_eligible"],
                "dataset_sha256": result["dataset_sha256"],
                "policy_ids": result["policy_ids"],
                "policy_versions": result["policy_versions"],
                **result["metrics"],
                "output": str(args.output.resolve()),
            },
            ensure_ascii=False,
        )
    )
    return exit_code_for_status(result["status"], args.fail_on_gate)


if __name__ == "__main__":
    raise SystemExit(main())
