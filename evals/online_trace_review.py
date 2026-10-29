"""Read persisted admin traces, compare two seven-day windows and export real answer samples.

No models are called. --input replays a complete saved export; missing capture stays NO_DATA.
Only --reviews plus --submit-confirmed-failures writes feedback, through the existing user-owned API.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import random
import re
from urllib.parse import urlencode

from eval_common import capture_answer, json_hash
from agent_eval_summary import percentile
from eval_http import LiveEvalError, StockSageClient
from eval_observations import observe_case, summarize_cases
from ordinary_answer_quality import apply_reviews, context_errors, material, review_template
from numeric_traceability import check as numeric_check


def timestamp(value: str) -> datetime:
    result = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if result.tzinfo is None:
        raise ValueError("Timestamp needs a UTC offset: " + value)
    return result


def evaluation_user_ids(base_url: str) -> list[str]:
    """User ids of the evaluation accounts: the scorecard's own traffic must not read as product usage."""
    ids = {value.strip() for value in os.environ.get("STOCKSAGE_EVAL_USER_IDS", "").split(",") if value.strip()}
    for prefix in ("STOCKSAGE_EVAL_", "STOCKSAGE_EVAL_SECOND_"):
        email, password = os.environ.get(prefix + "EMAIL", ""), os.environ.get(prefix + "PASSWORD", "")
        if email and password:
            ids.add(str(StockSageClient(base_url).login(email, password)["userId"]))
    return sorted(ids)


def collect(base_url: str, token: str, now: datetime, *, header_name: str = "X-StockSage-Admin-Token") -> dict:
    if not token:
        raise ValueError("Set STOCKSAGE_ADMIN_TOKEN to read persisted traces")
    end = timestamp(now.isoformat())
    start = end - timedelta(days=14)
    client = StockSageClient(base_url, timeout_seconds=120, extra_headers={header_name: token})
    rows, ids, page, storage_zone = [], set(), 0, None
    while True:
        query = urlencode({"from": start.isoformat(), "to": end.isoformat(), "page": page, "size": 100})
        _, result = client.json_request("GET", "/api/admin/agent/traces?" + query)
        if (result.get("schema") != "online_trace_page_v1" or result.get("page") != page
                or not isinstance(result.get("hasNext"), bool) or not isinstance(result.get("rows"), list)
                or timestamp(result["from"]) != start or timestamp(result["to"]) != end):
            raise ValueError("Trace export pagination contract changed at page " + str(page))
        if storage_zone is not None and result.get("storageTimeZone") != storage_zone:
            raise ValueError("Backend storage time zone changed during export; retry the read")
        storage_zone = result.get("storageTimeZone")
        for row in result["rows"]:
            key = row["trace"]["traceId"]
            if key in ids:
                raise ValueError("Duplicate trace in paged export; retry the read: " + key)
            ids.add(key)
            rows.append(row)
        page += 1
        if not result["hasNext"]:
            break
        if not result["rows"]:
            raise ValueError("Trace export returned an empty nonterminal page")
    return {"schema": "online_trace_export_v1", "from": start.isoformat(), "to": end.isoformat(),
            "complete": True, "pages": page, "source": "/api/admin/agent/traces",
            "storage_time_zone": storage_zone, "evaluation_user_ids": evaluation_user_ids(base_url), "rows": rows}


def trace_steps(trace: dict) -> list[dict]:
    steps = trace.get("steps") or []
    steps = json.loads(steps) if isinstance(steps, str) else steps
    if not isinstance(steps, list):
        raise ValueError("Invalid trace steps: " + str(trace.get("traceId")))
    return steps


def labels(trace: dict) -> tuple[str, str]:
    attrs = [step.get("attributes") or {} for step in trace_steps(trace)]
    route = next((a.get("route") for a in attrs if a.get("kind") == "routing-decision"), None)
    route = route or next((a.get("route") for a in attrs if a.get("kind") == "ordinary-evidence"), None) or "UNKNOWN"
    markets, tickers = set(), set()
    for item in attrs:
        entities = item.get("entities") or {}
        for market in (item.get("market"), entities.get("market")):
            if market in {"US", "HK", "CN", "A_SHARE"}:
                markets.add("CN" if market == "A_SHARE" else market)
        for value in (entities.get("ticker"), item.get("target")):
            if isinstance(value, str) and value:
                tickers.add(value.upper())
        tickers.update(str(row["target"]).upper() for row in item.get("observations", []) if row.get("target"))
    for ticker in tickers:
        if re.fullmatch(r"(?:HK\.?\d{4,5}|\d{4,5}\.HK)", ticker):
            markets.add("HK")
        elif re.fullmatch(r"(?:(?:SH|SZ|BJ)\.?\d{6}|\d{6}\.(?:SH|SZ|BJ))", ticker):
            markets.add("CN")
        elif re.fullmatch(r"[A-Z]{1,6}(?:[.-][A-Z])?(?:\.US)?", ticker):
            markets.add("US")
    return route, next(iter(markets)) if len(markets) == 1 else "MULTI" if markets else "UNKNOWN"


def observed(row: dict) -> dict:
    trace = row["trace"]
    route, market = labels(trace)
    case = {"id": trace["traceId"], "trace": trace, "actual_outcome": trace.get("taskOutcome")}
    if route == "DEEP" and "modelInvocations" in row:
        case.update(model_invocations=row["modelInvocations"], model_invocations_complete=True,
                    model_capture_scope="DEEP_TASK_LEDGER_EXCLUDES_ROUTING")
    elif any((step.get("attributes") or {}).get("modelObservation") for step in trace_steps(trace)):
        case["model_capture_scope"] = "MAIN_REQUEST_CHAT_EXCLUDES_BACKGROUND_AND_EMBEDDING"
    # running 的 0ms 是占位值；失败、取消的终态耗时和成功的一起计入。
    if trace.get("status") != "running" and trace.get("durationMs") is not None:
        case["latency_ms"] = trace["durationMs"]
    return {"row": row, "case": case, "route": route, "market": market, "observation": observe_case(case)}


def rate_rows(counts: dict[str, Counter]) -> dict:
    result = {}
    for key, values in sorted(counts.items()):
        attempts = sum(values.values())
        known = values["SUCCESS"] + values["FAILED"]
        result[key] = {"attempts": attempts, "successes": values["SUCCESS"], "failures": values["FAILED"],
                       "unknown": attempts - known, "failure_rate": values["FAILED"] / known if known else None,
                       "outcome_coverage": known / attempts if attempts else None}
    return result


AGENT_RATE_KEYS = {
    "tool_call_success_rate", "tool_call_empty_rate", "tool_call_failure_rate", "tool_call_unknown_rate", "tool_timeout_rate",
    "tool_unavailable_rate", "tool_call_success_rate_excl_unavailable", "tool_success_rate", "tool_timeout_rate",
    "parameter_accuracy", "route_accuracy_live", "requirement_satisfaction_rate", "fallback_trigger_rate",
    "fallback_recovery_rate", "filing_kb_hit_rate", "filing_fallback_rate", "filing_fallback_recovery_rate",
    "deep_loop_added_rate", "deep_loop_truncated_rate", "deep_loop_not_added_rate", "deep_loop_failed_rate",
    "deep_loop_outcome_unknown_rate", "deep_loop_new_evidence_per_call", "deep_loop_productive_round_rate",
    "model_call_failure_rate", "model_output_incomplete_rate", "model_failure_rate", "model_incomplete_rate"}


def summarize(rows: list[dict]) -> dict:
    outcomes = Counter(row["observation"]["terminal_outcome"] for row in rows)
    statuses = Counter(str(row["row"]["trace"].get("status") or "UNKNOWN") for row in rows)
    reasons = Counter(reason for row in rows for reason in set(row["observation"]["degradation_reasons"]))
    tools = defaultdict(Counter)
    observation = summarize_cases([row["case"] for row in rows])
    for row in rows:
        for step in trace_steps(row["row"]["trace"]):
            attr = step.get("attributes") or {}
            if attr.get("stepKind") in {"tool", "capability"}:
                state = str(attr.get("outcome") or "UNKNOWN").upper()
                state = "FAILED" if state in {"FAILED", "ERROR", "TIMEOUT"} else state
                tools[str(step.get("action") or "UNKNOWN")][state if state in {"SUCCESS", "FAILED"} else "UNKNOWN"] += 1
    sources = observation["data_sources"]
    for source in sources.values():
        known = source["successes"] + source["failures"]
        source["failure_rate"] = source["failures"] / known if known else None
        source["outcome_coverage"] = known / source["attempts"] if source["attempts"] else None
    durations = sorted(float(row["case"]["latency_ms"]) for row in rows if "latency_ms" in row["case"])
    return {"requests": len(rows), "terminal_outcomes": dict(sorted(outcomes.items())),
            "terminal_rates": {key: count / len(rows) for key, count in sorted(outcomes.items())},
            "technical_statuses": dict(sorted(statuses.items())), "reason_top5": reasons.most_common(5),
            "tools": rate_rows(tools), "data_sources": sources,
            "latency": {"known": len(durations), "terminal_requests": len(rows) - statuses["running"],
                        "p50_ms": percentile(durations, .5), "p95_ms": percentile(durations, .95)},
            # Agent process rates (tools, parameters, requirements, recovery, loop, models); see eval_observations.
            "agent_metrics": {key: value for key, value in observation["metrics"].items()
                              if key.split(":", 1)[0] in AGENT_RATE_KEYS},
            "agent_process": observation["agent_process"],
            "cost_requests_with_any_estimate": sum(bool(row["observation"]["resources"]["cost_estimates"]) for row in rows),
            "cost_requests_complete_within_capture_scope": sum(row["observation"]["resources"]["cost_status"] == "COMPLETE_ESTIMATE" for row in rows),
            "resources": observation["resources"]}


def window(rows: list[dict]) -> dict:
    result = {"all": summarize(rows)}
    for dimension in ("route", "market"):
        groups = defaultdict(list)
        for row in rows:
            groups[row[dimension]].append(row)
        result["by_" + dimension] = {key: summarize(group) for key, group in sorted(groups.items())}
    return result


def changes(current: dict, previous: dict) -> list[dict]:
    results = []
    pairs = [("all", current["all"], previous["all"])]
    for dimension in ("by_route", "by_market"):
        pairs += [(dimension + "." + key, current[dimension].get(key), previous[dimension].get(key))
                  for key in sorted(current[dimension].keys() | previous[dimension].keys())]
    for scope, after, before in pairs:
        if after is None or before is None:
            results.append({"scope": scope, "kind": "NEW_GROUP" if before is None else "NO_CURRENT_REQUESTS"})
            continue
        metrics = [("requests", before["requests"], after["requests"], "relative")]
        metrics += [("terminal." + outcome, before["terminal_rates"].get(outcome, 0), after["terminal_rates"].get(outcome, 0), "rate")
                    for outcome in sorted(before["terminal_rates"].keys() | after["terminal_rates"].keys())]
        metrics += [("latency." + key, before["latency"][key], after["latency"][key], "relative") for key in ("p50_ms", "p95_ms")]
        metrics += [("agent." + key, before["agent_metrics"][key], after["agent_metrics"][key], "rate")
                    for key in sorted(before["agent_metrics"].keys() & after["agent_metrics"].keys())]
        for dimension in ("tools", "data_sources"):
            metrics += [(dimension + "." + key + ".failure_rate", before[dimension][key]["failure_rate"],
                         after[dimension][key]["failure_rate"], "rate") for key in sorted(before[dimension].keys() & after[dimension].keys())]
        old_costs = {(c["currency"], c["basis"]): c for c in before["resources"]["cost_estimates"]}
        new_costs = {(c["currency"], c["basis"]): c for c in after["resources"]["cost_estimates"]}
        metrics += [("mean_recorded_cost." + ".".join(key), old_costs[key]["mean_recorded_amount"],
                     new_costs[key]["mean_recorded_amount"], "relative") for key in sorted(old_costs.keys() & new_costs.keys())]
        for name, old, new, kind in metrics:
            if old is None or new is None:
                continue
            delta = new - old
            relative = delta / old if old else None
            noteworthy = abs(delta) >= .1 if kind == "rate" else relative is not None and abs(relative) >= .25
            results.append({"scope": scope, "metric": name, "previous": old, "current": new,
                            "delta": delta, "relative_change": relative, "review_change": noteworthy,
                            "small_sample": min(before["requests"], after["requests"]) < 20})
    return results


def sample_report(rows: list[dict], seed: int, sample_size: int) -> dict:
    eligible = sorted((row for row in rows if row["observation"]["terminal_outcome"] == "COMPLETED"
                       and row["route"] in {"DIRECT", "MARKET", "NEWS", "FUNDAMENTALS"}),
                      key=lambda row: row["row"]["trace"]["traceId"])
    selected = random.Random(seed).sample(eligible, min(sample_size, len(eligible)))
    cases = []
    for row in selected:
        source, trace = row["row"], row["row"]["trace"]
        definition = {"id": "online-" + trace["traceId"], "question": trace.get("userQuery") or "",
                      "source": {"trace_id": trace["traceId"], "message_id": source.get("messageId"),
                                 "created_at": source["createdAt"], "source_report": source.get("sourceReport")}}
        answer = source.get("answer") or ""
        cases.append({"id": definition["id"], "trace": trace, "actual_outcome": trace.get("taskOutcome"),
                      "source": source.get("answerSource", "PERSISTED_TRACE_AND_ASSISTANT_MESSAGE"), "passed": None,
                      "events": [{"type": "answer", "content": answer, "source": source.get("answerSource", "PERSISTED_MESSAGE")}],
                      **capture_answer(definition, answer)})
        captured = material(cases[-1])
        context = captured["answer_context"]
        capture_errors = context_errors(context)
        evidence = context.get("evidenceContext", "") if not capture_errors else ""
        cases[-1]["numeric_traceability"] = numeric_check(answer, evidence)
        if capture_errors:
            cases[-1]["numeric_traceability"].update(status="NO_DATA", traceable_rate=None,
                                                   capture_errors=capture_errors)
    return {"schema": "ordinary_execution_eval_v1", "status": "NO_DATA", "sample_count": len(cases),
            "dataset_sha256": json_hash([case["case_definition"] for case in cases]),
            "sampling": {"seed": seed, "eligible_completed_ordinary": len(eligible), "requested": sample_size,
                         "trace_ids": [row["row"]["trace"]["traceId"] for row in selected],
                         "quality_status": "UNREVIEWED"}, "cases": cases}


def build_report(export: dict, now: datetime, seed: int = 0, sample_size: int = 5) -> tuple[dict, dict]:
    end = timestamp(now.isoformat())
    middle, start = end - timedelta(days=7), end - timedelta(days=14)
    if (export.get("schema") != "online_trace_export_v1" or export.get("complete") is not True
            or timestamp(export["from"]) > start or timestamp(export["to"]) < end):
        raise ValueError("A complete export covering both seven-day windows is required")
    if sample_size < 0:
        raise ValueError("sample_size must be nonnegative")
    evaluation = {str(value) for value in export.get("evaluation_user_ids") or []}
    ids, periods, excluded = set(), {"current": [], "previous": []}, {"current": 0, "previous": 0}
    for row in export["rows"]:
        key = row["trace"]["traceId"]
        if key in ids:
            raise ValueError("Duplicate trace in export: " + key)
        ids.add(key)
        created = timestamp(row["createdAt"])
        if start <= created < end:
            period = "current" if created >= middle else "previous"
            # Scorecard runs dominate local traffic; mixing them in would compare which evals ran, not system health.
            if str(row["trace"].get("userId")) in evaluation:
                excluded[period] += 1
            else:
                periods[period].append(observed(row))
    current, previous = window(periods["current"]), window(periods["previous"])
    samples = sample_report(periods["current"], seed, sample_size)
    report = {"schema": "online_trace_review_v1", "as_of": end.isoformat(), "status": "DIAGNOSTIC",
              "export_source": export.get("source"), "exported_trace_count": len(export["rows"]),
              "population": {"scope": "NON_EVALUATION_ACCOUNTS" if evaluation else "ALL_ACCOUNTS_EVALUATION_IDS_UNKNOWN",
                             "evaluation_user_ids": sorted(evaluation), "excluded_evaluation_traces": excluded},
              "storage_time_zone": export.get("storage_time_zone"),
              "windows": {"current": {"from": middle.isoformat(), "to": end.isoformat(), **current},
                          "previous": {"from": start.isoformat(), "to": middle.isoformat(), **previous}},
              "changes": changes(current, previous), "sampling": samples["sampling"],
              "numeric_diagnostics": {"diagnostic_only": True,
                                      "statuses": dict(Counter(case["numeric_traceability"]["status"] for case in samples["cases"])),
                                      "unmatched_sample_ids": [case["id"] for case in samples["cases"]
                                                               if case["numeric_traceability"]["counts"]["unmatched"]]},
              "comparison_rules": {"rate_absolute_change": .1, "other_relative_change": .25,
                                   "meaning": "Review hints only, not significance or a quality gate"},
              "quality": {"status": "NO_DATA", "reason": "Random samples need bound human reviews; AI diagnostics are not confirmed failures"}}
    return report, samples


def apply_sample_reviews(samples: dict, reviews: dict) -> tuple[dict, dict]:
    reviewed = apply_reviews(samples, reviews)
    rated = [case for case in reviewed["cases"] if not case["quality_review"]["errors"]
             and case["quality_review"]["status"] in {"PASS", "FAIL"}]
    failed = [case["id"] for case in rated if case["quality_review"]["status"] == "FAIL"]
    total = len(reviewed["cases"])
    return reviewed, {"status": "REVIEWED" if rated and len(rated) == total else "NO_DATA",
                      "source": "USER_SUPPLIED_BOUND_REVIEWS", "sampled": total, "rated": len(rated),
                      "review_coverage": len(rated) / total if total else None,
                      "failure_rate_among_rated": len(failed) / len(rated) if rated else None,
                      "failed_sample_ids": failed, "failure_pool_written": False}


def submit_confirmed_failures(samples: dict, base_url: str, email: str, password: str) -> list[dict]:
    candidates = [case for case in samples["cases"] if case.get("quality_review", {}).get("status") == "FAIL"
                  and not case["quality_review"].get("errors")]
    if not candidates:
        return []
    if not email or not password:
        raise ValueError("Set STOCKSAGE_EVAL_EMAIL/PASSWORD to the reviewed traces' owning account before submitting feedback")
    client = StockSageClient(base_url)
    user = client.login(email, password)
    foreign = [case["trace"]["traceId"] for case in candidates if case["trace"].get("userId") != user["userId"]]
    if foreign:
        raise ValueError("No feedback submitted: confirmed failures include another user's traces: " + ", ".join(foreign)
                         + ". Use the owning account; leave other users' samples unreviewed in this submission.")
    # Check every binding before the first write; a deleted or replaced answer cannot inherit an older review.
    for case in candidates:
        trace_id = case["trace"]["traceId"]
        _, trace = client.json_request("GET", "/api/trace/" + trace_id)
        _, messages = client.json_request("GET", f"/api/chat/conversations/{trace['conversationId']}/messages")
        answers = [message for message in messages if message.get("traceId") == trace_id and message.get("role") == "assistant"]
        latest = max(answers, key=lambda message: message["id"]) if answers else None
        expected_message = case["case_definition"]["source"].get("message_id")
        if (latest is None or latest.get("content") != case["answer"]
                or trace.get("userQuery") != case["case_definition"]["question"]
                or expected_message is not None and latest["id"] != expected_message):
            raise ValueError("No feedback submitted: saved review no longer matches the persisted answer for " + trace_id
                             + ". Export and review the current answer again.")
    results = []
    for case in candidates:
        review = case["quality_review"]
        reasons = [name + ": " + value["reason"] for name, value in review["dimensions"].items() if value["status"] == "FAIL"]
        note = ("线上抽样用户确认 FAIL；reviewer=" + review["reviewer"] + "; reviewed_at=" + review["reviewed_at"]
                + "; " + "; ".join(reasons))[:1000]
        try:
            client.json_request("POST", "/api/chat/feedback", {"traceId": case["trace"]["traceId"], "note": note})
            results.append({"id": case["id"], "trace_id": case["trace"]["traceId"], "status": "ACCEPTED"})
        except LiveEvalError as error:
            results.append({"id": case["id"], "trace_id": case["trace"]["traceId"], "status": "ERROR", "error": str(error)})
    return results


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, help="Replay a complete saved export; only explicit feedback submission uses the network")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--now", help="Window end with UTC offset; defaults to export.to or now")
    parser.add_argument("--seed", type=int, default=0)
    parser.add_argument("--sample-size", type=int, default=5)
    parser.add_argument("--reviews", type=Path, help="Explicit reviewer judgments bound to this exact sample; no automatic failure-pool write")
    parser.add_argument("--submit-confirmed-failures", action="store_true",
                        help="Confirm valid FAIL reviews and submit through /api/chat/feedback; requires --reviews and the owning STOCKSAGE_EVAL_EMAIL/PASSWORD account for all candidates")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.submit_confirmed_failures and not args.reviews:
        parser.error("--submit-confirmed-failures requires --reviews with explicit, bound reviewer judgments")
    outputs = {name: args.output.with_name(args.output.stem + suffix) for name, suffix in
               (("samples", "-samples.json"), ("reviews", "-reviews.json"), ("traces", "-traces.json"))}
    if any(path.exists() for path in [args.output, outputs["samples"], outputs["reviews"], *([] if args.input else [outputs["traces"]])]):
        parser.error("Output exists; choose a new --output path to preserve the original review bindings")
    saved = json.loads(args.input.read_text(encoding="utf-8")) if args.input else None
    now = timestamp(args.now or (saved or {}).get("to") or datetime.now(timezone.utc).isoformat())
    exported = saved or collect(args.base_url, os.environ.get("STOCKSAGE_ADMIN_TOKEN", ""), now,
                                header_name=os.environ.get("STOCKSAGE_ADMIN_HEADER_NAME", "X-StockSage-Admin-Token"))
    report, samples = build_report(exported, now, args.seed, args.sample_size)
    if args.reviews:
        samples, report["quality"] = apply_sample_reviews(samples, json.loads(args.reviews.read_text(encoding="utf-8")))
        report["quality"]["review_file"] = str(args.reviews)
    if args.submit_confirmed_failures:
        submitted = submit_confirmed_failures(samples, args.base_url, os.environ.get("STOCKSAGE_EVAL_EMAIL", ""),
                                              os.environ.get("STOCKSAGE_EVAL_PASSWORD", ""))
        report["quality"]["feedback_submissions"] = submitted
        report["quality"]["failure_pool_written"] = any(row["status"] == "ACCEPTED" for row in submitted)
    report["sample_report"] = str(outputs["samples"])
    report["review_template"] = str(outputs["reviews"])
    report["trace_export"] = str(args.input or outputs["traces"])
    payloads = {args.output: report, outputs["samples"]: samples, outputs["reviews"]: review_template(samples)}
    if not args.input:
        payloads[outputs["traces"]] = exported
    args.output.parent.mkdir(parents=True, exist_ok=True)
    for path, value in payloads.items():
        path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"output": str(args.output), "sample_report": report["sample_report"], **report["sampling"]}, ensure_ascii=False))
    return 1 if any(row["status"] == "ERROR" for row in report["quality"].get("feedback_submissions", [])) else 0


if __name__ == "__main__":
    raise SystemExit(main())
