"""Review actual ordinary answers offline; never infer semantic quality from citations.

Export --review-template first, then apply --reviews to the same execution/replay result.
--baseline compares only matching cases and identical visible source evidence.
No network or model calls are made. Metrics cover ordinary-final-answer only.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path

DIMENSIONS = ("claim_support", "numeric_period_correctness", "counterevidence", "unknowns")
SCHEMA = "ordinary_answer_quality_v1"
SCOPE = "ordinary-final-answer"
RESULT_SCHEMAS = {"ordinary_execution_eval_v1", "ordinary_answer_replay_eval_v1"}


def sha256(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def json_hash(value) -> str:
    return sha256(json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")))


def capture_answer(case: dict, answer: str) -> dict:
    return {"case_definition": case, "case_sha256": json_hash(case),
            "answer": answer, "answer_sha256": sha256(answer)}


def attributes(case: dict) -> list[dict]:
    steps = case.get("trace", {}).get("steps", [])
    if isinstance(steps, str):
        steps = json.loads(steps)
    return [step.get("attributes") or {} for step in steps]


def context_errors(context: dict) -> list[str]:
    """Check the actual text-only prompt/evidence capture shared by quality review and rollback."""
    errors = []
    if not context or context.get("schemaVersion") != 1 or context.get("scope") != SCOPE:
        errors.append("ANSWER_CONTEXT_MISSING_OR_UNSUPPORTED")
    else:
        if context.get("evidenceCaptureComplete") is not True:
            errors.append("SOURCE_EVIDENCE_CAPTURE_INCOMPLETE")
        for text_key, hash_key in (("context", "contextSha256"), ("evidenceContext", "evidenceSha256")):
            if not isinstance(context.get(text_key), str) or sha256(context[text_key]) != context.get(hash_key):
                errors.append(hash_key + "_MISMATCH")
        messages = context.get("messages")
        if not isinstance(messages, list) or not messages or any(
                not isinstance(row, dict) or not isinstance(row.get("role"), str)
                or not isinstance(row.get("text"), str) for row in messages):
            errors.append("PROMPT_MESSAGES_MISSING")
        else:
            prompt = [{"role": row["role"], "text": row["text"]} for row in messages]
            canonical = json.dumps(prompt, ensure_ascii=False, separators=(",", ":"))
            if sha256(canonical) != context.get("promptSha256"):
                errors.append("PROMPT_HASH_MISMATCH")
        if context.get("hasImages") is not False or context.get("promptFingerprintScope") != "TEXT_ONLY":
            errors.append("FULL_MULTIMODAL_CONTEXT_UNAVAILABLE")
    return errors


def material(case: dict) -> dict:
    """Recompute bindings from the actual exported stream and trace, not review labels."""
    attrs = attributes(case)
    contexts = [item for item in attrs if item.get("kind") == "answer-context"]
    context = contexts[0] if len(contexts) == 1 else {}
    invocations = [item for item in attrs if item.get("kind") == "model-invocation"
                   and item.get("scope") == "final-answer"]
    usage = [item for item in attrs if item.get("kind") == "model-usage"
             and item.get("scope") == "final-answer"]
    invocation = invocations[0] if len(invocations) == 1 else {}
    answer = "".join(event.get("content", "") for event in case.get("events", [])
                     if event.get("type") == "answer")
    definition = case.get("case_definition") or {}
    errors = []
    if not definition or definition.get("id") != case.get("id") or case.get("case_sha256") != json_hash(definition):
        errors.append("CASE_BINDING_MISSING_OR_CHANGED")
    if not answer.strip() or answer != case.get("answer") or sha256(answer) != case.get("answer_sha256"):
        errors.append("ANSWER_BINDING_MISSING_OR_CHANGED")
    errors.extend(context_errors(context))
    if not invocation.get("modelName") or not invocation.get("modelTier"):
        errors.append("MODEL_INVOCATION_MISSING")
    binding = {"id": case.get("id"), "caseSha256": json_hash(definition), "answerSha256": sha256(answer),
               **{key: context.get(key) for key in
                  ("promptSha256", "contextSha256", "evidenceSha256")},
               "modelName": invocation.get("modelName"), "modelTier": invocation.get("modelTier"),
               "invocationSha256": json_hash(invocations)}
    return {"binding": binding, "question": definition.get("question", ""), "answer": answer,
            "answer_context": context, "model_invocations": invocations,
            "model_usage": usage[-1] if usage else {"usageSource": "NO_DATA"},
            "usage_scope": "final-answer", "errors": errors}


def review_template(report: dict) -> dict:
    if report.get("schema") not in RESULT_SCHEMAS:
        raise ValueError("Expected an ordinary execution or answer-replay result")
    unique_cases(report)
    return {"schema": SCHEMA, "scope": SCOPE, "dataset_sha256": report.get("dataset_sha256"),
            "reviews": [{**material(case), "reviewer": "", "reviewed_at": "",
                         "dimensions": {name: {"status": "NO_DATA", "reason": ""} for name in DIMENSIONS}}
                        for case in report.get("cases", [])]}


def summarize_status(values: list[str]) -> str:
    if "FAIL" in values:
        return "FAIL"
    if not values or "NO_DATA" in values or not any(value == "PASS" for value in values):
        return "NO_DATA"
    return "PASS"


def assess_review(case: dict, review: dict | None) -> dict:
    snapshot = material(case)
    result = {"binding": snapshot["binding"], "status": "NO_DATA", "errors": snapshot["errors"],
              "dimensions": {name: {"status": "NO_DATA", "reason": ""} for name in DIMENSIONS}}
    if snapshot["errors"] or review is None:
        if review is None:
            result["errors"].append("REVIEW_MISSING")
        return result
    if review.get("binding") != snapshot["binding"]:
        result["status"] = "FAIL"
        result["errors"].append("REVIEW_BINDING_MISMATCH")
        return result
    if not isinstance(review.get("reviewer"), str) or not review["reviewer"].strip() or not isinstance(review.get("reviewed_at"), str) or not review["reviewed_at"].strip():
        result["errors"].append("REVIEW_PROVENANCE_MISSING")
        return result
    try:
        reviewed_at = datetime.fromisoformat(review["reviewed_at"].replace("Z", "+00:00"))
        if reviewed_at.tzinfo is None:
            raise ValueError("Review time must include a timezone")
    except (ValueError, TypeError):
        result["errors"].append("REVIEW_TIME_INVALID")
        return result
    result.update(reviewer=review["reviewer"], reviewed_at=review["reviewed_at"])
    dimensions = review.get("dimensions") or {}
    for name in DIMENSIONS:
        row = dimensions.get(name) or {}
        status, reason = row.get("status", "NO_DATA"), str(row.get("reason") or "").strip()
        if status not in {"PASS", "FAIL", "NOT_APPLICABLE", "NO_DATA"}:
            result["errors"].append("INVALID_DIMENSION:" + name)
            status = "FAIL"
        elif status != "NO_DATA" and not reason:
            result["errors"].append("REVIEW_REASON_MISSING:" + name)
            status = "FAIL" if status == "FAIL" else "NO_DATA"
        result["dimensions"][name] = {"status": status, "reason": reason}
    result["status"] = summarize_status([row["status"] for row in result["dimensions"].values()])
    return result


def unique_cases(report: dict) -> dict[str, dict]:
    cases = report.get("cases", [])
    result = {case["id"]: case for case in cases}
    if len(cases) != len(result) or any(not key for key in result):
        raise ValueError("Result cases must have nonempty unique ids")
    return result


def apply_reviews(report: dict, reviews: dict) -> dict:
    if report.get("schema") not in RESULT_SCHEMAS:
        raise ValueError("Expected an ordinary execution or answer-replay result")
    if reviews.get("schema") != SCHEMA or reviews.get("scope") != SCOPE:
        raise ValueError("Unsupported review schema or scope")
    if reviews.get("dataset_sha256") != report.get("dataset_sha256"):
        raise ValueError("Reviews belong to a different dataset")
    output = copy.deepcopy(report)
    cases = unique_cases(output)
    definitions = [case.get("case_definition") for case in cases.values()]
    if len(cases) == output.get("sample_count") and all(isinstance(row, dict) and row for row in definitions):
        if json_hash(definitions) != output.get("dataset_sha256"):
            raise ValueError("Complete result cases do not match dataset_sha256; regenerate the result")
    indexed = {}
    for review in reviews.get("reviews", []):
        key = (review.get("binding") or {}).get("id")
        if key not in cases or key in indexed:
            raise ValueError("Unknown or duplicate review case: " + str(key))
        indexed[key] = review
    for key, case in cases.items():
        case["quality_review"] = assess_review(case, indexed.get(key))
    status = summarize_status([case["quality_review"]["status"] for case in cases.values()])
    complete = bool(cases) and len(cases) == output.get("sample_count")
    if not complete and status != "FAIL":
        status = "NO_DATA"
    output["answer_quality"] = status
    gate = "FAIL" if status == "FAIL" or output.get("status") == "FAIL" else (
        "PASS" if status == "PASS" and complete and output.get("status") == "PASS"
        and all(case.get("passed") is True for case in cases.values()) else "NO_DATA")
    dimensions = {}
    for name in DIMENSIONS:
        values = [case["quality_review"]["dimensions"][name]["status"] for case in cases.values()]
        values.extend(["NO_DATA"] * max(0, output.get("sample_count", 0) - len(cases)))
        rated = [value for value in values if value in {"PASS", "FAIL"}]
        dimensions[name] = {"rated_count": len(rated), "pass_count": rated.count("PASS"),
                            "not_applicable_count": values.count("NOT_APPLICABLE"),
                            "missing_count": values.count("NO_DATA"),
                            "pass_rate": rated.count("PASS") / len(rated) if rated else None}
    reviewed_count = sum(not case["quality_review"]["errors"] and all(
        row["status"] != "NO_DATA" for row in case["quality_review"]["dimensions"].values()) for case in cases.values())
    output["quality_evaluation"] = {"schema": SCHEMA, "scope": SCOPE, "status": gate,
                                    "generated_at": datetime.now(timezone.utc).isoformat(),
                                    "sample_count": output.get("sample_count"), "completed_count": len(cases),
                                    "reviewed_count": reviewed_count,
                                    "review_coverage": reviewed_count / output["sample_count"] if output.get("sample_count") else None,
                                    "rated_count": sum(case["quality_review"]["status"] in {"PASS", "FAIL"}
                                                       and not case["quality_review"]["errors"] for case in cases.values()),
                                    "dimensions": dimensions}
    return output


def compare_reports(baseline: dict, candidate: dict) -> dict:
    before, after = unique_cases(baseline), unique_cases(candidate)
    paired, unpaired = [], []
    for key in sorted(before.keys() | after.keys()):
        if key not in before or key not in after:
            unpaired.append({"id": key, "reason": "CASE_MISSING"})
            continue
        if before[key].get("passed") is not True or after[key].get("passed") is not True:
            unpaired.append({"id": key, "reason": "EXECUTION_NOT_PASSED"})
            continue
        left, right = material(before[key]), material(after[key])
        if left["errors"] or right["errors"]:
            unpaired.append({"id": key, "reason": "CONTEXT_OR_BINDING_UNAVAILABLE"})
            continue
        if any(left["binding"][field] != right["binding"][field] for field in ("caseSha256", "evidenceSha256")):
            unpaired.append({"id": key, "reason": "CASE_OR_EVIDENCE_CHANGED"})
            continue
        left_review = assess_review(before[key], before[key].get("quality_review"))
        right_review = assess_review(after[key], after[key].get("quality_review"))
        if left_review["errors"] or right_review["errors"]:
            unpaired.append({"id": key, "reason": "BOUND_REVIEW_MISSING"})
            continue
        deltas = {}
        for name in DIMENSIONS:
            a = left_review["dimensions"][name]["status"]
            b = right_review["dimensions"][name]["status"]
            deltas[name] = int(b == "PASS") - int(a == "PASS") if a in {"PASS", "FAIL"} and b in {"PASS", "FAIL"} else None
        paired.append({"id": key, "baseline": left["binding"], "candidate": right["binding"],
                       "baseline_model_invocations": left["model_invocations"],
                       "candidate_model_invocations": right["model_invocations"],
                       "baseline_model_usage": left["model_usage"], "candidate_model_usage": right["model_usage"],
                       "dimension_deltas": deltas})
    routes = {key: str((before.get(key) or after[key]).get("case_definition", {}).get("route") or "UNKNOWN")
              for key in before.keys() | after.keys()}
    strata = {}
    for route in sorted(set(routes.values())):
        strata[route] = pair_summary([row for row in paired if routes[row["id"]] == route],
                                    [row for row in unpaired if routes[row["id"]] == route])
    return {"schema": "ordinary_answer_comparison_v1", "scope": SCOPE, "usage_scope": "final-answer",
            **pair_summary(paired, unpaired), "stratification": "baseline_expected_route_else_candidate",
            "coverage_scope": "exported_case_union",
            "route_strata": strata, "pairs": paired, "unpaired": unpaired}


def pair_summary(paired: list[dict], unpaired: list[dict]) -> dict:
    """Keep absent/unrateable pairs visible; PAIRED describes evidence, not superiority."""
    summary = {}
    for name in DIMENSIONS:
        values = [row["dimension_deltas"][name] for row in paired if row["dimension_deltas"][name] is not None]
        summary[name] = {"rated_pair_count": len(values), "pass_rate_delta": sum(values) / len(values) if values else None}
    total = len(paired) + len(unpaired)
    return {"status": "PAIRED" if any(row["rated_pair_count"] for row in summary.values()) else "NO_DATA",
            "case_count": total, "paired_count": len(paired), "unpaired_count": len(unpaired),
            "pair_coverage": len(paired) / total if total else None,
            "unpaired_reasons": {reason: sum(row["reason"] == reason for row in unpaired)
                                 for reason in sorted({row["reason"] for row in unpaired})},
            "dimensions": summary}


def analyst_inputs(report: dict) -> dict:
    """Export inputs only; never reuse the observed answer/review as a candidate result."""
    if report.get("schema") != "ordinary_execution_eval_v1":
        raise ValueError("Expected an ordinary_execution_eval_v1 live result")
    pairs, unavailable = [], []
    for key, case in unique_cases(report).items():
        observed = material(case)
        context = observed["answer_context"]
        span = context.get("analystSpan") or {}
        ordinary = [row for row in attributes(case) if row.get("kind") == "ordinary-evidence"]
        if observed["errors"] or case.get("passed") is not True:
            unavailable.append({"id": key, "reason": "EXECUTION_OR_BINDING_UNAVAILABLE"})
            continue
        if len(ordinary) != 1 or ordinary[0].get("analystStatus") != "COMPLETED" or not span:
            unavailable.append({"id": key, "reason": "COMPLETE_ANALYST_SPAN_UNAVAILABLE"})
            continue
        messages = context["messages"]
        index, start, end = (span.get(field) for field in ("messageIndex", "start", "end"))
        if (span.get("schemaVersion") != 1 or span.get("offsetUnit") != "UNICODE_CODE_POINT"
                or any(type(value) is not int for value in (index, start, end))
                or not 0 <= index < len(messages)
                or messages[index]["role"] != "user"
                or messages[index]["text"] != context["context"]
                or not 0 <= start < end <= len(context["context"])
                or span.get("removedTextSha256") != sha256(context["context"][start:end])):
            unavailable.append({"id": key, "reason": "INVALID_ANALYST_SPAN"})
            continue
        candidate = copy.deepcopy(messages)
        candidate[index]["text"] = context["context"][:start] + context["context"][end:]
        def input_payload(rows):
            canonical = [{"role": row["role"], "text": row["text"]} for row in rows]
            return {"messages": canonical,
                    "context": rows[index]["text"], "contextSha256": sha256(rows[index]["text"]),
                    "promptSha256": sha256(json.dumps(canonical, ensure_ascii=False, separators=(",", ":")))}
        pairs.append({"id": key, "source_binding": observed["binding"],
                      "case_definition": case["case_definition"],
                      "source_model_invocations": observed["model_invocations"],
                      "evidenceContext": context["evidenceContext"], "evidenceSha256": context["evidenceSha256"],
                      "removedDraftSha256": sha256(context["context"][start:end]),
                      "analystSpan": span,
                      "baseline": input_payload(messages), "without_analyst": input_payload(candidate)})
    return {"schema": "ordinary_analyst_inputs_v1", "scope": "frozen-final-answer-inputs",
            "status": "INPUTS_READY" if pairs else "NO_DATA", "pairs": pairs, "unavailable": unavailable}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--review-template", type=Path)
    mode.add_argument("--reviews", type=Path)
    mode.add_argument("--analyst-inputs", type=Path)
    parser.add_argument("--baseline", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.reviews and not args.output:
        parser.error("--reviews requires --output")
    if args.baseline and not args.reviews:
        parser.error("--baseline requires --reviews")
    try:
        report = json.loads(args.input.read_text(encoding="utf-8"))
        if args.review_template:
            output, path = review_template(report), args.review_template
        elif args.analyst_inputs:
            output, path = analyst_inputs(report), args.analyst_inputs
        else:
            output = apply_reviews(report, json.loads(args.reviews.read_text(encoding="utf-8")))
            if args.baseline:
                output["paired_comparison"] = compare_reports(json.loads(args.baseline.read_text(encoding="utf-8")), output)
            path = args.output
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    except (ValueError, KeyError, TypeError, OSError) as error:
        parser.exit(2, str(error) + "\n")
    status = ("REVIEW_TEMPLATE" if args.review_template else output["status"] if args.analyst_inputs
              else output["quality_evaluation"]["status"])
    print(json.dumps({"status": status, "output": str(path)}))
    return 0 if args.review_template or status in {"PASS", "INPUTS_READY"} else 2


if __name__ == "__main__":
    raise SystemExit(main())
