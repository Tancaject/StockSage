"""Checks shared by evaluators; each evaluator owns its scopes and rating criteria."""
from __future__ import annotations

import hashlib
import json
from datetime import datetime


def sha256(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def json_hash(value) -> str:
    return sha256(json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")))


def capture_answer(case: dict, answer: str) -> dict:
    return {"case_definition": case, "case_sha256": json_hash(case),
            "answer": answer, "answer_sha256": sha256(answer)}


def context_errors(context: dict, *, expected_scope: str) -> list[str]:
    """Verify captured text and hashes against the caller's actual execution scope."""
    errors = []
    if not context or context.get("schemaVersion") != 1 or context.get("scope") != expected_scope:
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


def summarize_status(values: list[str]) -> str:
    if "FAIL" in values:
        return "FAIL"
    if not values or "NO_DATA" in values or not any(value == "PASS" for value in values):
        return "NO_DATA"
    return "PASS"


def assess_review_format(snapshot: dict, review: dict | None, dimensions: tuple[str, ...]) -> dict:
    """Check review provenance, binding and rating format, without rating the answer."""
    result = {"binding": snapshot["binding"], "status": "NO_DATA", "errors": list(snapshot["errors"]),
              "dimensions": {name: {"status": "NO_DATA", "reason": ""} for name in dimensions}}
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
    ratings = review.get("dimensions") or {}
    for name in dimensions:
        row = ratings.get(name) or {}
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
