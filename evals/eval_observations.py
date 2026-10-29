"""Offline outcome, first-observed-fault and resource summaries from captured traces."""
from __future__ import annotations

import json
import math
import re
import statistics
from collections import Counter, defaultdict
from decimal import Decimal, InvalidOperation

from agent_eval_summary import percentile

OUTCOMES = ("COMPLETED", "DEGRADED", "BLOCKED", "FAILED", "UNKNOWN")
TOKEN_FIELDS = {"input_tokens": "inputTokens", "cached_input_tokens": "cachedInputTokens",
                "reasoning_tokens": "reasoningTokens", "output_tokens": "outputTokens"}
# 评测侧固定需求与工具对应，不能用运行时新增需求替换题目的分母。
REQUIREMENT_TOOLS = {
    "PRICE_HISTORY": {"getStockKLine", "getIbkrHistoricalBars"},
    "VALUATION": {"getFinancialMetrics", "getStructuredFinancials"},
    "TECHNICAL": {"getTechnicalIndicators"},
    "FINANCIAL_STATEMENTS": {"getFinancialReports", "getStructuredFinancials"},
    "NEWS": {"searchNews", "webSearch", "local.news.searchNews", "local.news.webSearch", "mcp.news.search"},
    # 知识库召回另行判断；没有召回时只有 SEC 申报原文摘录满足，网页搜索摘要不算。
    "FILING_TEXT": {"getFilingText"},
}
UNAVAILABLE = re.compile(r"AUTH_REQUIRED|UNAUTHENTICATED|CIRCUIT_OPEN|TIMEOUT|CONNECTION_REFUSED|"
                         r"SERVICE_UNAVAILABLE|HTTP_(?:401|403|429|5\d\d)|CONNECTION REFUSED|"
                         r"CONNECTTIMEOUT|READTIMEOUT|CONNECTION RESET|TIMED OUT", re.I)


def trace_steps(trace: dict) -> list[dict]:
    steps = trace.get("steps") or []
    return json.loads(steps) if isinstance(steps, str) else steps


def _number(value) -> bool:
    return type(value) in (int, float) and math.isfinite(value) and value >= 0


def _payload(step: dict) -> dict:
    text = step.get("observation") or ""
    if text.startswith("Error payload after ") and ": " in text:
        text = text.split(": ", 1)[1]
    try:
        value = json.loads(text)
        if isinstance(value, str):
            value = json.loads(value)
        return value if isinstance(value, dict) else {}
    except (ValueError, TypeError):
        return {}


def _invocations(row: dict, steps: list[dict]) -> tuple[list[dict], bool]:
    if isinstance(row.get("model_invocations"), list):
        return [{**call, "usage": call.get("response") or {}} for call in row["model_invocations"]], bool(
            row.get("model_invocations_complete"))
    calls, pending = [], defaultdict(list)
    for step in steps:
        attr = step.get("attributes") or {}
        if attr.get("kind") == "model-invocation":
            calls.append({"id": attr.get("invocationId"), "scope": attr.get("scope"), "request": attr, "usage": {}})
            pending[attr.get("scope")].append(calls[-1])
        elif attr.get("kind") == "model-usage":
            queue = pending[attr.get("scope")]
            matching = next((call for call in calls if attr.get("invocationId") and call.get("id") == attr["invocationId"]), None)
            if matching is not None:
                matching["usage"] = attr
                if matching in queue:
                    queue.remove(matching)
            elif queue:
                queue.pop(0)["usage"] = attr
            else:
                calls.append({"id": attr.get("invocationId"), "scope": attr.get("scope"), "request": {}, "usage": attr})
        for observation in (attr.get("modelObservation"), (attr.get("diagnostics") or {}).get("modelObservation")):
            if observation:
                calls.append({"id": observation.get("invocationId"), "scope": observation.get("scope"),
                              "request": observation, "usage": observation})
        if attr.get("analystInvocation"):
            calls.append({"id": attr["analystInvocation"].get("invocationId"),
                          "scope": attr["analystInvocation"].get("scope"), "request": attr["analystInvocation"],
                          "usage": attr.get("analystUsage") or {}})
    unique, identified = [], {}
    for call in calls:
        if call.get("id") and call["id"] in identified:
            previous = identified[call["id"]]
            previous["usage"] = {**previous["usage"], **call["usage"]}
        else:
            unique.append(call)
            if call.get("id"):
                identified[call["id"]] = call
    return unique, bool(row.get("model_invocations_complete"))


def _resources(row: dict, steps: list[dict]) -> dict:
    calls, complete = _invocations(row, steps)
    captured = bool(row.get("trace")) or isinstance(row.get("model_invocations"), list)
    result = {"observed_model_calls": len(calls) if captured else None, "model_capture_complete": complete,
              "model_capture_scope": row.get("model_capture_scope", "CAPTURED_TRACE_INVOCATIONS"),
              "call_count_semantics": "CLIENT_INVOCATIONS_EXCLUDES_SDK_RETRIES",
              "model_calls": len(calls) if complete else None}
    for name, field in TOKEN_FIELDS.items():
        values = [call["usage"][field] for call in calls if call["usage"].get("usageSource") in {"PROVIDER", "SDK_NORMALIZED"}
                  and type(call["usage"].get(field)) is int and call["usage"][field] >= 0]
        result[name] = {"known_total": sum(values) if values else None,
                        "total": sum(values) if complete and len(values) == len(calls) else None,
                        "known_calls": len(values), "calls": len(calls),
                        "coverage": len(values) / len(calls) if calls else None}
    costs = defaultdict(lambda: {"amount": Decimal(0), "known_calls": 0})
    for call in calls:
        cost = call["usage"].get("costEstimate") or {}
        if cost.get("status") not in {"COMPLETE_RECORDED_ESTIMATE", "PARTIAL_ESTIMATE"}:
            continue
        try:
            amount = Decimal(str(cost.get("amount")))
        except InvalidOperation:
            continue
        if not amount.is_finite() or amount < 0 or not cost.get("currency") or not cost.get("basis"):
            continue
        group = costs[(cost["currency"], cost["basis"])]
        group["amount"] += amount
        group["known_calls"] += 1
    result["cost_estimates"] = [{"currency": currency, "basis": basis, "amount": str(values["amount"]),
                                 "known_calls": values["known_calls"], "total_calls": len(calls)}
                                for (currency, basis), values in sorted(costs.items())]
    result["cost_coverage"] = sum(c["known_calls"] for c in result["cost_estimates"]) / len(calls) if calls else None
    complete_costs = all((call["usage"].get("costEstimate") or {}).get("status") == "COMPLETE_RECORDED_ESTIMATE"
                         for call in calls)
    result["cost_status"] = ("COMPLETE_ESTIMATE" if complete and result["cost_coverage"] == 1 and complete_costs else
                             "PARTIAL_ESTIMATE" if costs else "UNKNOWN")
    run = row.get("run") or {}
    result["tool_calls"] = (sum((s.get("attributes") or {}).get("stepKind") in {"tool", "capability"} for s in steps)
                            if row.get("trace") else run.get("tool_action_count"))
    wall = run.get("wall_seconds")
    latency = row.get("latency_ms") if _number(row.get("latency_ms")) else (
        wall * 1000 if _number(wall) else (row.get("trace") or {}).get("durationMs"))
    if (row.get("trace") or {}).get("status") == "running" and not _number(row.get("latency_ms")) and not _number(wall):
        latency = None
    result["latency_ms"] = latency if _number(latency) else None
    replan = row.get("replan") or [s.get("attributes") or {} for s in steps]
    stops = [a["rounds"] for a in replan if a.get("stepKind") == "loop_stop" and _number(a.get("rounds"))]
    rounds = {a["round"] for a in replan if a.get("stepKind") == "loop_round" and _number(a.get("round"))}
    result["evidence_rounds"] = max(stops) if stops else len(rounds) if rounds else None
    return result


TIMEOUT = re.compile(r"TIMEOUT|TIMED OUT", re.I)
TOOL_OK = {"SUCCESS", "TRUNCATED"}
TOOL_EMPTY = {"EMPTY", "NO_RESULTS"}
TOOL_FAILED = {"FAILED", "ERROR", "TIMEOUT"}
FILING_FALLBACKS = {"SEC_FILING_FALLBACK", "WEB_SEARCH_FALLBACK"}


def agent_process(row: dict, steps: list[dict], checks: dict, sources: list[dict]) -> dict:
    """Per-case agent process facts from the saved trace: tools, parameters, requirements, recovery, loop, models."""
    attrs = [step.get("attributes") or {} for step in steps]
    tools = [{"tool": source["action"] or source["source"], "status": source["status"],
              "error": source["reason"] or (source["status"] if source["status"] in TOOL_FAILED else ""),
              "timeout": source["status"] == "TIMEOUT" or bool(TIMEOUT.search(source["reason"] or "")),
              "unavailable": source["availability"] == "UNAVAILABLE"} for source in sources]
    evidence = [a for a in attrs if a.get("kind") == "ordinary-evidence"]
    unmet = {str(r) for a in evidence for r in a.get("unmetRequirements") or []}
    required = [str(r) for a in evidence for r in a.get("requirements") or []]
    frozen = [met for targets in (row.get("requirement_coverage") or {}).values() for met in targets.values()]
    fallback = [a.get("fallbackExecuted") for a in evidence if a.get("fallbackExecuted")]
    decisions = [a.get("decision") for a in attrs if a.get("kind") == "filing-text-decision"]
    filing_fallback = any(d in FILING_FALLBACKS for d in decisions)
    loop = {"calls": 0, "added": 0, "added_truncated": 0, "unknown": 0, "not_added": Counter(), "failed": Counter(),
            "rounds": 0, "rounds_with_new_evidence": 0, "new_evidence": 0}
    for a in row.get("replan") or attrs:
        if a.get("stepKind") != "loop_round":
            continue
        loop["rounds"] += 1
        loop["new_evidence"] += len(a.get("addedEvidenceIds") or [])
        loop["rounds_with_new_evidence"] += bool(a.get("addedEvidenceIds"))
        for item in a.get("outcomes") or []:
            outcome = str(item).split("=", 1)[-1]
            if outcome == "DECLARED":
                continue
            loop["calls"] += 1
            if outcome.startswith("FAILED"):
                loop["failed"][outcome.split(":", 1)[-1]] += 1
            elif ";ADDED:" in outcome:
                loop["added_truncated"] += 1
            elif outcome.endswith(";ADDED"):
                loop["added"] += 1
            elif ";NOT_ADDED:" in outcome:
                loop["not_added"][outcome.split(";NOT_ADDED:", 1)[1]] += 1
            else:
                # Traces before 10-14 wrote only "EXECUTED"; whether the result was kept is not recorded.
                loop["unknown"] += 1
    models = defaultdict(lambda: {"calls": 0, "failed": 0, "incomplete": 0})
    calls, _ = _invocations(row, steps)
    for call in calls:
        scope = str(call.get("scope") or call.get("role") or "unknown")
        models[scope]["calls"] += 1
        status = call.get("status") or call["usage"].get("status") or call["request"].get("status")
        if status in {"FAILED", "CANCELLED", "ERROR"}:
            models[scope]["failed"] += 1
    for a in attrs:
        if a.get("kind") == "model-completion" and a.get("completionStatus") not in {None, "COMPLETE"}:
            models[str(a.get("scope") or "unknown")]["incomplete"] += 1
        if a.get("analystCompletionStatus") not in {None, "COMPLETE"}:
            models["analyst"]["incomplete"] += 1
    return {"tools": tools,
            "parameter_checks": [v for k, v in checks.items() if (k == "parameters" or k.startswith("call:")) and isinstance(v, bool)],
            "route_check": checks.get("route") if isinstance(checks.get("route"), bool) else None,
            "requirements": {"total": len(required), "met": sum(r not in unmet for r in required)},
            "frozen_requirements": {"total": len(frozen), "met": sum(bool(m) for m in frozen)},
            "fallback": {"triggered": bool(fallback), "recovered": bool(fallback) and not unmet},
            "filing": {"decisions": decisions, "fallback": filing_fallback,
                       "recovered": (filing_fallback and "FILING_TEXT" not in unmet) if filing_fallback else None,
                       "kb_hit": "KNOWLEDGE_BASE_HIT" in decisions},
            "loop": {**loop, "not_added": dict(loop["not_added"]), "failed": dict(loop["failed"])},
            "models": {scope: dict(values) for scope, values in models.items()}}


def observe_case(row: dict, gold: dict | None = None) -> dict:
    trace = row.get("trace") or {}
    steps = trace_steps(trace)
    checks = row.get("checks") or {}
    outcome = row.get("actual_outcome") or trace.get("taskOutcome") or "UNKNOWN"
    if outcome not in OUTCOMES:
        outcome = "UNKNOWN"
    reasons, faults, sources = [], [], []
    providers = {o.get("tool"): o.get("provider") for step in steps
                 for o in (step.get("attributes") or {}).get("observations", []) if o.get("provider")}

    def fault(category, step, reason, source=None):
        faults.append({"category": category, "step_index": step.get("index"), "action": step.get("action"),
                       "reason": reason, "source": source, "attribution": "FIRST_OBSERVED_FAULT"})

    for step in steps:
        attr = step.get("attributes") or {}
        kind, action = attr.get("kind"), step.get("action") or ""
        model = attr.get("modelObservation") or (attr.get("diagnostics") or {}).get("modelObservation") or {}
        if model.get("status") in {"FAILED", "ERROR", "CANCELLED"}:
            fault("GENERATION", step, "MODEL_CALL_" + model["status"], model.get("actualModel"))
        if kind == "model-completion" and attr.get("completionStatus") not in {None, "COMPLETE"}:
            fault("GENERATION", step, "MODEL_COMPLETION:" + str(attr["completionStatus"]))
        if kind == "model-usage" and attr.get("status") in {"FAILED", "CANCELLED"}:
            fault("GENERATION", step, "MODEL_CALL_" + attr["status"])
        if kind == "routing-decision" and checks.get("route") is False:
            fault("ROUTING_OR_PARAMETERS", step, "ROUTE_MISMATCH")
        if kind == "ordinary-plan" and any(checks.get(k) is False for k in ("parameters", "targets")):
            fault("ROUTING_OR_PARAMETERS", step, "PARAMETER_OR_TARGET_MISMATCH")
        if attr.get("stepKind") in {"tool", "capability"}:
            payload = _payload(step)
            source = payload.get("provider") or attr.get("providerId") or providers.get(action) or action
            code = str(payload.get("errorCode") or attr.get("errorCode") or "")
            status = str(attr.get("outcome") or "UNKNOWN").upper()
            unavailable = status in {"FAILED", "ERROR", "TIMEOUT"} and bool(UNAVAILABLE.search(
                code + " " + str(payload.get("message") or step.get("observation") or "")))
            sources.append({"source": source, "action": action, "status": status, "reason": code,
                            "availability": "UNAVAILABLE" if unavailable else "AVAILABLE" if status == "SUCCESS" else "UNKNOWN"})
            if checks.get("call:" + action) is False:
                fault("ROUTING_OR_PARAMETERS", step, "TOOL_ARGUMENT_MISMATCH", source)
            elif status in {"FAILED", "ERROR", "TIMEOUT"}:
                fault("DATA_SOURCE_UNAVAILABLE" if unavailable else "TOOL_CALL", step, code or status, source)
        if action == "Knowledge Retrieval" and "failed" in str(step.get("thought") or "").lower():
            unavailable = bool(UNAVAILABLE.search(str(step.get("observation") or "")))
            sources.append({"source": "knowledge_retrieval", "action": action, "status": "FAILED",
                            "reason": "CONNECTION_UNAVAILABLE" if unavailable else "RETRIEVAL_FAILED",
                            "availability": "UNAVAILABLE" if unavailable else "UNKNOWN"})
            fault("DATA_SOURCE_UNAVAILABLE" if unavailable else "TOOL_CALL", step,
                  sources[-1]["reason"], "knowledge_retrieval")
        if attr.get("stepKind") == "harness_decision" and attr.get("decision") in {"DEGRADE", "BLOCK"}:
            reasons.extend(attr.get("violationCodes") or [])
        if kind == "routing-decision" and outcome == "BLOCKED":
            reasons.extend(reason for reason in attr.get("reasonCodes") or []
                           if re.search(r"UNSUPPORTED|MISMATCH|MISSING|CLARIF|GUARD|AMBIGUOUS|INVALID", reason))
        if kind == "ordinary-evidence":
            gaps = attr.get("unmetRequirements") or []
            reasons.extend("UNMET:" + str(gap) for gap in gaps)
            reasons.extend(str(o["gap"]) for o in attr.get("observations", []) if o.get("gap"))
            if gaps:
                fault("DATA_GAP", step, ",".join(map(str, gaps)))
            if attr.get("analystStatus") in {"FAILED", "ERROR"}:
                fault("GENERATION", step, "ANALYST_FAILED")
    failed_checks = [name for name, passed in checks.items() if passed is False]
    if not faults and failed_checks:
        category = "GENERATION" if any(k in failed_checks for k in ("answer", "citations", "stream")) else "UNKNOWN"
        fault(category, {}, ",".join(failed_checks))
    if gold and gold.get("status") == "FAIL" and not faults:
        kind = gold.get("failureType")
        fault("DATA_GAP" if kind == "DATA_GAP" else "GENERATION" if kind in {"DETERMINISTIC", "METHOD"} else "UNKNOWN",
              {}, "GOLD:" + str(kind))
    if row.get("error") and not faults:
        fault("UNKNOWN", {}, "RUNNER_ERROR")
    for call in row.get("model_invocations") or []:
        if not faults and call.get("status") in {"FAILED", "CANCELLED"}:
            fault("GENERATION", {}, "MODEL_CALL_" + call["status"], call.get("role"))
    if (row.get("run") or {}).get("unsafe_reasons") and not faults:
        fault("UNKNOWN", {}, "UNSAFE_TERMINAL:" + ",".join(row["run"]["unsafe_reasons"]))
    # Ordinary answers also degrade when evidence was shortened or left out of the context (allIncluded is false).
    if outcome == "DEGRADED":
        for a in (step.get("attributes") or {} for step in steps):
            if a.get("kind") != "ordinary-evidence":
                continue
            for o in a.get("observations") or []:
                if o.get("reduced"):
                    reasons.append("EVIDENCE_REDUCED:" + str(o.get("tool")))
                if o.get("included") is False:
                    reasons.append("EVIDENCE_EXCLUDED:" + str(o.get("tool")))
    # DeepResearchPipeline maps an evidence-only run to DEGRADED by design: no report was requested.
    if outcome == "DEGRADED" and (row.get("run") or {}).get("result_kind") == "EVIDENCE_ONLY":
        reasons.append("EVIDENCE_ONLY_MODE")
    if outcome in {"DEGRADED", "BLOCKED"} and not reasons:
        reasons.append("REASON_NOT_CAPTURED")
    final_evidence = next(((step.get("attributes") or {}) for step in reversed(steps)
                           if (step.get("attributes") or {}).get("kind") == "ordinary-evidence"), {})
    unavailable = {source["action"] for source in sources if source["availability"] == "UNAVAILABLE"}
    failed_evidence = {o.get("tool") for o in final_evidence.get("observations", []) if o.get("status") == "FAILED"}
    tools = unavailable & failed_evidence
    unmet = final_evidence.get("unmetRequirements") or []
    identity_failed = any(value is False and (key in {"route", "parameters", "targets", "isolation", "no_tools"}
                                             or key.startswith("call:")) for key, value in checks.items())
    environment_blocked = (outcome == "FAILED" and final_evidence.get("taskOutcome") == "FAILED"
                           and bool(unmet) and not identity_failed
                           and all(REQUIREMENT_TOOLS.get(requirement, set()) & tools for requirement in unmet))
    first_fault = faults[0] if faults else None
    failure = first_fault
    review = row.get("human_review") or {}
    if (review.get("category") == "EVALUATOR_MISJUDGMENT" and review.get("confirmed") is True
            and all(isinstance(review.get(key), str) and review[key].strip() for key in ("reviewer", "reviewed_at", "reason"))):
        failure = {"category": "EVALUATOR_MISJUDGMENT", "reason": review["reason"], "reviewer": review["reviewer"],
                   "reviewed_at": review["reviewed_at"], "attribution": "HUMAN_CONFIRMED"}
        environment_blocked = False
    # A recovered retrieval failure is observable, but does not prove that a later wrong answer was caused by it.
    return {"terminal_outcome": outcome, "degradation_reasons": sorted(set(reasons)) if outcome in {"DEGRADED", "BLOCKED"} else [],
            "first_failure": failure, "first_observed_fault": first_fault, "data_sources": sources,
            "environment_blocked": environment_blocked,
            "environment_blocked_details": {"status": "CONFIRMED_FROM_TRACE" if environment_blocked else "UNCONFIRMED",
                                             "tools": sorted(tools), "unmet_requirements": unmet,
                                             "sources": sorted({(o.get("tool"), str(o.get("target") or ""))
                                                                for o in final_evidence.get("observations", [])
                                                                if o.get("tool") in tools})},
            "resources": _resources(row, steps), "agent": agent_process(row, steps, checks, sources)}


def _rate(numerator: int, denominator: int) -> float | None:
    return numerator / denominator if denominator else None


def summarize_agent(cases: list[dict]) -> dict:
    """Agent process rates; a rate with no applicable calls or cases is absent, never 0 or 1."""
    metrics, total = {}, len(cases)
    tools = [tool for case in cases for tool in case["tools"]]
    ok = sum(t["status"] in TOOL_OK for t in tools)
    empty = sum(t["status"] in TOOL_EMPTY for t in tools)
    failed = sum(t["status"] in TOOL_FAILED for t in tools)
    unavailable = sum(t["unavailable"] for t in tools)
    values = {"tool_call_attempts": len(tools),
              "tool_call_success_rate": _rate(ok, len(tools)), "tool_call_empty_rate": _rate(empty, len(tools)),
              "tool_call_failure_rate": _rate(failed, len(tools)), "tool_call_unknown_rate": _rate(len(tools) - ok - empty - failed, len(tools)),
              "tool_timeout_rate": _rate(sum(t["timeout"] for t in tools), len(tools)),
              "tool_unavailable_rate": _rate(unavailable, len(tools)),
              # Failures with a confirmed-down source are environment, not agent behavior.
              "tool_call_success_rate_excl_unavailable": _rate(ok, len(tools) - unavailable)}
    per_tool = {}
    for name in sorted({t["tool"] for t in tools}):
        rows = [t for t in tools if t["tool"] == name]
        per_tool[name] = {"attempts": len(rows), "success_rate": _rate(sum(t["status"] in TOOL_OK for t in rows), len(rows)),
                          "failure_rate": _rate(sum(t["status"] in TOOL_FAILED for t in rows), len(rows)),
                          "timeout_rate": _rate(sum(t["timeout"] for t in rows), len(rows)),
                          "errors": dict(Counter(t["error"] for t in rows if t["error"]).most_common(5))}
        values[f"tool_success_rate:{name}"] = per_tool[name]["success_rate"]
        values[f"tool_timeout_rate:{name}"] = per_tool[name]["timeout_rate"]
    params = [v for case in cases for v in case["parameter_checks"]]
    routes = [case["route_check"] for case in cases if case["route_check"] is not None]
    requirements = [case["requirements"] for case in cases]
    frozen = [case["frozen_requirements"] for case in cases]
    triggered = [case for case in cases if case["fallback"]["triggered"]]
    filing = [case["filing"] for case in cases if case["filing"]["decisions"]]
    filing_fallback = [f for f in filing if f["fallback"]]
    loop_calls = sum(case["loop"]["calls"] for case in cases)
    # Kept/dropped is known only for executed calls that carry an ADDED/NOT_ADDED note.
    known_loop_calls = sum(case["loop"]["added"] + case["loop"]["added_truncated"] + sum(case["loop"]["not_added"].values())
                           for case in cases)
    loop_rounds = sum(case["loop"]["rounds"] for case in cases)
    not_added, loop_failed = Counter(), Counter()
    for case in cases:
        not_added.update(case["loop"]["not_added"])
        loop_failed.update(case["loop"]["failed"])
    models = defaultdict(lambda: {"calls": 0, "failed": 0, "incomplete": 0})
    for case in cases:
        for scope, row in case["models"].items():
            for key in models[scope]:
                models[scope][key] += row[key]
    model_calls = sum(row["calls"] for row in models.values())
    values.update({
        "parameter_accuracy": _rate(sum(params), len(params)),
        "route_accuracy_live": _rate(sum(routes), len(routes)),
        "requirement_satisfaction_rate": _rate(sum(r["met"] for r in requirements), sum(r["total"] for r in requirements)),
        "frozen_requirement_coverage_rate": _rate(sum(r["met"] for r in frozen), sum(r["total"] for r in frozen)),
        "fallback_trigger_rate": _rate(len(triggered), total),
        "fallback_recovery_rate": _rate(sum(case["fallback"]["recovered"] for case in triggered), len(triggered)),
        "filing_kb_hit_rate": _rate(sum(f["kb_hit"] for f in filing), len(filing)),
        "filing_fallback_rate": _rate(len(filing_fallback), len(filing)),
        "filing_fallback_recovery_rate": _rate(sum(bool(f["recovered"]) for f in filing_fallback), len(filing_fallback)),
        "deep_loop_tool_calls": loop_calls if loop_calls else None,
        "deep_loop_rounds": loop_rounds if loop_rounds else None,
        "deep_loop_added_rate": _rate(sum(case["loop"]["added"] + case["loop"]["added_truncated"] for case in cases), known_loop_calls),
        "deep_loop_truncated_rate": _rate(sum(case["loop"]["added_truncated"] for case in cases), known_loop_calls),
        "deep_loop_not_added_rate": _rate(sum(not_added.values()), known_loop_calls),
        "deep_loop_failed_rate": _rate(sum(loop_failed.values()), loop_calls),
        "deep_loop_outcome_unknown_rate": _rate(sum(case["loop"]["unknown"] for case in cases), loop_calls),
        "deep_loop_new_evidence_per_call": _rate(sum(case["loop"]["new_evidence"] for case in cases), loop_calls),
        "deep_loop_productive_round_rate": _rate(sum(case["loop"]["rounds_with_new_evidence"] for case in cases), loop_rounds),
        "model_call_failure_rate": _rate(sum(row["failed"] for row in models.values()), model_calls),
        "model_output_incomplete_rate": _rate(sum(row["incomplete"] for row in models.values()), model_calls),
    })
    for scope, row in models.items():
        values[f"model_failure_rate:{scope}"] = _rate(row["failed"], row["calls"])
        values[f"model_incomplete_rate:{scope}"] = _rate(row["incomplete"], row["calls"])
    metrics.update({key: value for key, value in values.items() if value is not None})
    return {"metrics": metrics, "tools": per_tool,
            "tool_error_types": dict(Counter(t["error"] for t in tools if t["error"]).most_common(8)),
            "deep_loop_not_added_reasons": dict(not_added.most_common()), "deep_loop_failed_reasons": dict(loop_failed.most_common()),
            "models_by_scope": {scope: dict(row) for scope, row in models.items()},
            "scope": "Rates exclude cases or calls the metric does not apply to; absent means not applicable."}


def summarize_cases(rows: list[dict], gold_cases: list[dict] | None = None) -> dict:
    gold = {row["id"]: row for row in gold_cases or []}
    observed = [(row, observe_case(row, gold.get(row["id"]))) for row in rows]
    total = len(rows)
    counts = Counter(obs["terminal_outcome"] for _, obs in observed)
    terminal = {outcome: counts[outcome] for outcome in OUTCOMES}
    metrics = {"terminal_" + key.lower() + "_rate": value / total for key, value in terminal.items()} if total else {}
    reasons = Counter(reason for _, obs in observed for reason in obs["degradation_reasons"])
    resources = [obs["resources"] for _, obs in observed]
    distributions = {}
    for name in ("observed_model_calls", "model_calls", "tool_calls", "latency_ms", "evidence_rounds"):
        values = [r[name] for r in resources if _number(r.get(name))]
        distributions[name] = {"known_cases": len(values), "total_cases": total,
                               "mean": statistics.fmean(values) if values else None, "p95": percentile(values, .95)}
        if values:
            metrics["mean_" + name] = distributions[name]["mean"]
            metrics["p95_" + name] = distributions[name]["p95"]
    calls = sum(r["observed_model_calls"] or 0 for r in resources)
    token_summary = {}
    for name in TOKEN_FIELDS:
        known = sum(r[name]["known_calls"] for r in resources)
        values = [r[name]["total"] for r in resources if r[name]["total"] is not None]
        recorded = [r[name]["known_total"] for r in resources if r[name]["known_total"] is not None]
        token_summary[name] = {"known_calls": known, "calls": calls, "coverage": known / calls if calls else None,
                               "recorded_total": sum(recorded) if recorded else None,
                               "recorded_cases": len(recorded), "total_cases": total,
                               "mean_recorded": statistics.fmean(recorded) if recorded else None,
                               "p95_recorded": percentile(recorded, .95),
                               "complete_cases": len(values), "mean": statistics.fmean(values) if values else None,
                               "p95": percentile(values, .95)}
        if calls:
            metrics[name + "_coverage"] = known / calls
        if recorded:
            metrics["mean_recorded_" + name] = token_summary[name]["mean_recorded"]
            metrics["p95_recorded_" + name] = token_summary[name]["p95_recorded"]
        if values:
            metrics["mean_" + name] = token_summary[name]["mean"]
            metrics["p95_" + name] = token_summary[name]["p95"]
    groups = defaultdict(list)
    for r in resources:
        for cost in r["cost_estimates"]:
            groups[(cost["currency"], cost["basis"])].append(cost)
    costs = [{"currency": currency, "basis": basis, "recorded_amount": str(sum(Decimal(c["amount"]) for c in costs)),
              "known_calls": sum(c["known_calls"] for c in costs), "total_calls": calls,
              "known_cases": len(costs), "mean_recorded_amount": statistics.fmean(float(c["amount"]) for c in costs),
              "p95_recorded_amount": percentile([float(c["amount"]) for c in costs], .95)}
             for (currency, basis), costs in sorted(groups.items())]
    if calls:
        metrics["cost_coverage"] = sum(c["known_calls"] for c in costs) / calls
    for cost in costs:
        suffix = re.sub(r"[^a-z0-9]+", "_", (cost["currency"] + "_" + cost["basis"]).lower())
        metrics["mean_recorded_cost_" + suffix] = cost["mean_recorded_amount"]
        metrics["p95_recorded_cost_" + suffix] = cost["p95_recorded_amount"]
    sources = defaultdict(Counter)
    for _, obs in observed:
        for source in obs["data_sources"]:
            sources[source["source"]][source["status"]] += 1
            sources[source["source"]]["availability:" + source["availability"]] += 1
    source_summary = {}
    for source, counts in sorted(sources.items()):
        statuses = {key: count for key, count in counts.items() if not key.startswith("availability:")}
        attempts = sum(statuses.values())
        failures = sum(counts[key] for key in ("FAILED", "ERROR", "TIMEOUT"))
        available, unavailable = counts["availability:AVAILABLE"], counts["availability:UNAVAILABLE"]
        source_summary[source] = {"counts": statuses, "attempts": attempts, "successes": counts["SUCCESS"],
                                  "failures": failures, "unknown": attempts - counts["SUCCESS"] - failures,
                                  "observed_success_rate": counts["SUCCESS"] / attempts,
                                  "unavailable": unavailable, "availability_known": available + unavailable,
                                  "availability_coverage": (available + unavailable) / attempts,
                                  "unavailable_rate": unavailable / (available + unavailable) if available + unavailable else None}
    agreement = None
    if gold_cases is not None:
        graded = [(row, obs, gold[row["id"]]) for row, obs in observed
                  if row["id"] in gold and gold[row["id"]].get("status") in {"PASS", "FAIL"}]
        correct = [(row, obs) for row, obs, g in graded if g["status"] == "PASS"]
        wrong = [(row, obs) for row, obs, g in graded if g["status"] == "FAIL"]
        degraded = [row["id"] for row, obs in correct if obs["terminal_outcome"] in {"DEGRADED", "BLOCKED"}]
        missed = [row["id"] for row, obs in wrong if obs["terminal_outcome"] == "COMPLETED"]
        agreement = {"diagnostic_only": True, "graded_cases": len(graded), "gold_correct_cases": len(correct),
                     "gold_incorrect_cases": len(wrong), "suspected_false_degradation_ids": degraded,
                     "suspected_false_degradation_rate": len(degraded) / len(correct) if correct else None,
                     "gold_failure_completed_ids": missed, "gold_failure_completed_rate": len(missed) / len(wrong) if wrong else None}
        metrics.update({name: agreement[name] for name in ("suspected_false_degradation_rate", "gold_failure_completed_rate")
                        if agreement[name] is not None})
    agent = summarize_agent([obs["agent"] for _, obs in observed])
    metrics.update(agent.pop("metrics"))
    return {"schema": "eval_observations_v1", "case_count": total, "metrics": metrics, "agent_process": agent,
            "terminal_counts": terminal, "degradation_reasons_top3": [{"reason": reason, "cases": count}
                                                                         for reason, count in reasons.most_common(3)],
            "failures": [{"id": row["id"], "terminal_outcome": obs["terminal_outcome"],
                          "execution_check_failed": row.get("passed") is False,
                          "gold_failed": gold.get(row["id"], {}).get("status") == "FAIL",
                          **obs["first_failure"]} for row, obs in observed if obs["first_failure"]],
            "resources": {"distributions": distributions, "tokens": token_summary, "cost_estimates": costs,
                          "call_count_semantics": "CLIENT_INVOCATIONS_EXCLUDES_SDK_RETRIES",
                          "complete_capture_cases": sum(r["model_capture_complete"] for r in resources),
                          "capture_scopes": dict(Counter(r["model_capture_scope"] for r in resources))},
            "data_sources": source_summary, "gold_terminal_agreement": agreement}
