"""Bind human reviews to both stages of an isolated fundamentals replay.

Export --review-template, fill the existing four-dimensional human rubric, then
apply --reviews to the unchanged result. Synthetic runs are mechanism evidence;
their answer_quality remains NO_DATA even when the human review passes.
No model, network, search, or publication calls are made.
"""
from __future__ import annotations

import argparse
import copy
import json
import re
from decimal import Decimal
from pathlib import Path

from evolution_dataset import (REAL_ORIGIN, REAL_SCOPE, execution_payload, fact_key, load_jsonl,
                               validate_facts, validate_gold)
from ordinary_answer_quality import DIMENSIONS, assess_review, capture_answer, json_hash, material, sha256, summarize_status

SCHEMA = "fundamentals_evolution_quality_v1"
REAL_SCHEMA = "fundamentals_evolution_quality_v2"
RESULT_SCHEMA = "fundamentals_evolution_replay_eval_v1"
STAGES = {"analysis": ("FUNDAMENTALS_ANALYSIS", "fundamentals-analysis"),
          "finalAnswer": ("FINAL_ANSWER", "final-answer")}
HASH = re.compile(r"[0-9a-f]{64}\Z")
HARD_GATES = ("authorization", "tenant_isolation", "security_identity", "as_of", "budget", "output_contract")


def result_cases(report: dict) -> dict[str, dict]:
    if report.get("schema") != RESULT_SCHEMA:
        raise ValueError("Expected fundamentals_evolution_replay_eval_v1; regenerate the replay report")
    count = report.get("sample_count")
    if type(count) is not int or count < 0 or not isinstance(report.get("cases"), list):
        raise ValueError("Replay report requires a nonnegative sample_count and cases list")
    if report.get("sample_unit") != "INVOCATION":
        raise ValueError("Replay sample_unit must be INVOCATION; repeated calls are not independent source cases")
    file_hash = report.get("execution_file_sha256")
    if not isinstance(file_hash, str) or not HASH.fullmatch(file_hash):
        raise ValueError("Replay report requires the frozen execution_file_sha256")
    cases = {}
    for case in report["cases"]:
        key = case.get("id")
        if not isinstance(key, str) or not key or key in cases:
            raise ValueError("Replay report has a missing or duplicate case id")
        if (case.get("replay") or {}).get("executionFileSha256") != file_hash:
            raise ValueError("Replay source differs from execution_file_sha256; regenerate the report")
        cases[key] = case
    if len(cases) > count:
        raise ValueError("Replay report contains more cases than sample_count")
    if len(cases) == count and json_hash([case.get("case_definition") for case in cases.values()]) != report.get("dataset_sha256"):
        raise ValueError("Replay definitions do not match dataset_sha256; regenerate the report")
    if len(cases) == count and len({(case.get("case_definition") or {}).get("caseId") for case in cases.values()}) != report.get("unique_case_count"):
        raise ValueError("Replay unique_case_count does not match its frozen source cases")
    return cases


def input_errors(case: dict) -> list[str]:
    definition, replay = case.get("case_definition") or {}, case.get("replay") or {}
    request = case.get("request") or {}
    bundle = replay.get("methodBundle") or {}
    errors = []
    if replay.get("schema") != "fundamentals_evolution_replay_v1" or not replay.get("runId"):
        errors.append("REPLAY_IDENTITY_MISSING_OR_UNSUPPORTED")
    if (case["id"] != f"{definition.get('caseId')}@{definition.get('repeatId')}"
            or replay.get("caseId") != definition.get("caseId")
            or any(replay.get(key) != definition.get(key) for key in
                   ("caseSha256", "query", "resolvedQuery", "origin", "repeatId"))):
        errors.append("FROZEN_INPUT_BINDING_MISMATCH")
    if (request.get("caseId") != definition.get("caseId") or request.get("bundleId") != definition.get("bundleId")
            or request.get("runId") != replay.get("runId")):
        errors.append("REPLAY_REQUEST_BINDING_MISMATCH")
    if ((definition.get("origin"), definition.get("executionScope")) not in
            {("SYNTHETIC", "SYNTHETIC_COMPONENT_CHAIN"), (REAL_ORIGIN, REAL_SCOPE)}
            or replay.get("executionScope") != definition.get("executionScope")):
        errors.append("UNSUPPORTED_EVIDENCE_SCOPE")
    for text_key, hash_key in (("context", "contextSha256"), ("preAnalystContext", "preAnalystContextSha256")):
        context = definition.get(text_key)
        if not isinstance(context, str) or sha256(context) != definition.get(hash_key):
            errors.append("FROZEN_INPUT_HASH_MISMATCH:" + text_key)
    if (not definition.get("bundleId") or bundle.get("bundleId") != definition["bundleId"]
            or bundle.get("scope") != "FUNDAMENTALS_METHOD"
            or any(not isinstance(bundle.get(key), str) or not HASH.fullmatch(bundle[key])
                   for key in ("bundleSha256", "fixedContractSha256"))):
        errors.append("METHOD_BUNDLE_BINDING_MISSING_OR_CHANGED")
    if any(not isinstance(replay.get(key), str) or not HASH.fullmatch(replay[key])
           for key in ("caseSha256", "executionFileSha256")):
        errors.append("EXECUTION_INPUT_HASH_MISSING")
    return errors


def stage_case(case: dict, stage_name: str) -> dict:
    """Adapt transport fields only; semantic rating stays in ordinary_answer_quality."""
    replay = case.get("replay") or {}
    stage = replay.get(stage_name) or {}
    scope, invocation_scope = STAGES[stage_name]
    definition = {"id": case["id"], "question": replay.get("query", ""), "scope": scope,
                  "frozen_input": case.get("case_definition"), "request": case.get("request"),
                  "replaySha256": json_hash(replay)}
    context = {"kind": "answer-context", "schemaVersion": 1, "scope": "ordinary-final-answer",
               "hasImages": False, "promptFingerprintScope": "TEXT_ONLY",
               **{key: stage.get(key) for key in ("messages", "promptSha256", "context", "contextSha256",
                                                 "evidenceContext", "evidenceSha256", "evidenceCaptureComplete")}}
    # The shared assessor has a final-answer slot. Keep this remapping private;
    # exported stage scope and observations retain their actual execution role.
    observations = [{**row, "scope": "final-answer"} for row in stage.get("observations", [])
                    if row.get("scope") == invocation_scope]
    answer = stage.get("answer") or ""
    return {"id": case["id"], **capture_answer(definition, answer),
            "events": [{"type": "answer", "content": answer}],
            "trace": {"steps": [{"attributes": context}] + [{"attributes": row} for row in observations]}}


def stage_errors(case: dict, stage_name: str) -> list[str]:
    stage = (case.get("replay") or {}).get(stage_name)
    if stage is None:
        return ["STAGE_NOT_EXECUTED"]
    errors = []
    if stage.get("scope") != STAGES[stage_name][0]:
        errors.append("STAGE_SCOPE_MISMATCH")
    if stage_name == "analysis":
        if stage.get("evidenceContext") != (case.get("case_definition") or {}).get("context"):
            errors.append("VISIBLE_EVIDENCE_INPUT_MISMATCH")
        if stage.get("context") != (case.get("case_definition") or {}).get("preAnalystContext"):
            errors.append("ANALYST_INPUT_MISMATCH")
    # The final assembler can sanitize source markers. Assess what was actually
    # visible instead of reimplementing its renderer or equating raw and visible text.
    context, evidence = stage.get("context"), stage.get("evidenceContext")
    if (not isinstance(context, str) or not isinstance(evidence, str) or not evidence.strip()
            or evidence not in context or not any(row.get("role") == "user" and context in row.get("text", "")
                                                 for row in stage.get("messages", []))):
        errors.append("CONTEXT_OR_EVIDENCE_NOT_VISIBLE_IN_PROMPT")
    return errors


def source_material(report: dict, source_cases: list[dict] | None, gold: list[dict] | None) -> dict:
    """Labels enter the evaluator only; bind them back to exact allowlisted execution inputs."""
    cases = result_cases(report)
    origins = {(case.get("case_definition") or {}).get("origin") for case in cases.values()}
    if len(origins) > 1:
        raise ValueError("Review real and synthetic runs in separate reports")
    if REAL_ORIGIN not in origins:
        return {}
    if source_cases is None or gold is None:
        raise ValueError("Real review requires --source-cases and --gold from the independent evaluation store")
    labels = validate_gold(source_cases, gold)
    inputs = {row["caseId"]: execution_payload(row) for row in source_cases}
    for case in cases.values():
        definition = {key: value for key, value in case["case_definition"].items() if key not in {"bundleId", "repeatId"}}
        if inputs.get(definition["caseId"]) != definition:
            raise ValueError("Reviewed source differs from frozen execution input: " + case["id"])
    return labels


def review_template(report: dict, source_cases: list[dict] | None = None, gold: list[dict] | None = None) -> dict:
    labels = source_material(report, source_cases, gold)
    rows = []
    for case in result_cases(report).values():
        for name, (scope, _) in STAGES.items():
            snapshot = material(stage_case(case, name))
            stage = (case.get("replay") or {}).get(name) or {}
            snapshot["answer_context"]["scope"] = scope
            snapshot.pop("model_invocations")
            snapshot.pop("model_usage")
            snapshot["observations"] = stage.get("observations", [])
            snapshot["usage_scope"] = scope
            row = {**snapshot, "scope": scope, "evidence_kind": "PUBLIC_AUTHORIZED_REVIEW" if labels else "SYNTHETIC_MECHANISM_ONLY",
                         "errors": input_errors(case) + stage_errors(case, name) + snapshot["errors"],
                         "reviewer": "", "reviewed_at": "",
                         "dimensions": {dimension: {"status": "NO_DATA", "reason": ""} for dimension in DIMENSIONS}}
            if labels:
                label = labels[case["case_definition"]["caseId"]]
                row.update(gold_sha256=json_hash(label), expectations=label,
                           hard_gates={name: {"status": "NO_DATA", "reason": ""} for name in HARD_GATES},
                           extraction_complete={"status": "NO_DATA", "reason": ""}, claims=[],
                           points={point["id"]: {"status": "NO_DATA", "reason": "", "answerExcerpt": ""}
                                   for point in label["requiredPoints"]}, response_kind="UNREVIEWED")
            rows.append(row)
    return {"schema": REAL_SCHEMA if labels else SCHEMA, "dataset_sha256": report.get("dataset_sha256"), "reviews": rows}


def assess_claims(stage: dict, review: dict, gold: dict, definition: dict) -> dict:
    """Human extraction is explicit; numerical checks never trust the generator's rating."""
    failures, missing = [], []
    answer, evidence = stage.get("answer") or "", stage.get("evidenceContext") or ""
    metrics = {"numeric": {"passed": 0, "total": len(gold["facts"])},
               "citation_support": {"passed": 0, "total": 0},
               "task_coverage": {"passed": 0, "total": len(gold["requiredPoints"])},
               "response": {"expected": gold["expectedResponse"], "observed": review.get("response_kind", "UNREVIEWED")}}
    if review.get("gold_sha256") != json_hash(gold):
        return {"status": "NO_DATA", "failures": [], "errors": ["GOLD_BINDING_MISSING_OR_CHANGED"], "metrics": metrics}

    def rating(row, name):
        if not isinstance(row, dict) or row.get("status") not in {"PASS", "FAIL"} or not str(row.get("reason") or "").strip():
            missing.append("REVIEW_INCOMPLETE:" + name)
            return "NO_DATA"
        if row["status"] == "FAIL":
            failures.append(name)
        return row["status"]

    gates = review.get("hard_gates") or {}
    if not isinstance(gates, dict):
        raise ValueError("hard_gates must be an object keyed by the frozen gate names")
    if set(gates) != set(HARD_GATES):
        missing.append("HARD_GATE_COVERAGE_MISMATCH")
    for name in HARD_GATES:
        rating(gates.get(name), "HARD_GATE:" + name)
    complete = rating(review.get("extraction_complete"), "CLAIM_EXTRACTION") == "PASS"
    if not answer.strip():
        failures.append("EMPTY_ANSWER")
    sources = {row["evidenceId"]: row["visibleText"] for row in definition["evidenceMetadata"]}
    allowed = set(sources)
    if any(value not in allowed for value in re.findall(r"\[(E\d+)\]", answer)):
        failures.append("ILLEGAL_CITATION")
    claims = review.get("claims")
    if not isinstance(claims, list):
        claims = []
        missing.append("CLAIMS_MISSING")
    expected = {fact_key(fact): fact for fact in gold["facts"]}
    matched, seen = set(), set()
    for claim in claims:
        if not isinstance(claim, dict) or set(claim) != {"fact", "answerExcerpt", "evidenceExcerpt", "support"}:
            missing.append("CLAIM_EXTRACTION_INVALID")
            continue
        fact = claim["fact"]
        try:
            validate_facts([fact])
        except (ValueError, TypeError):
            missing.append("CLAIM_FACT_INVALID")
            continue
        key = fact_key(fact)
        if key in seen:
            missing.append("DUPLICATE_EXTRACTED_CLAIM")
            continue
        seen.add(key)
        metrics["citation_support"]["total"] += 1
        excerpt, support_text = claim["answerExcerpt"], claim["evidenceExcerpt"]
        supported = rating(claim["support"], "UNSUPPORTED_CLAIM:" + str(key))
        if (not isinstance(excerpt, str) or not excerpt.strip() or excerpt not in answer
                or not isinstance(support_text, str) or not support_text.strip() or support_text not in evidence
                or support_text not in sources.get(fact["evidenceId"], "")):
            missing.append("CLAIM_EXCERPT_BINDING_MISMATCH:" + str(key))
            continue
        if fact["evidenceId"] not in allowed or f"[{fact['evidenceId']}]" not in answer:
            failures.append("CLAIM_CITATION_MISSING:" + str(key))
            continue
        if supported == "PASS":
            metrics["citation_support"]["passed"] += 1
        target = expected.get(key)
        if target is not None:
            actual, wanted = fact["value"], target["value"]
            same_value = (actual is None and wanted is None) or (actual is not None and wanted is not None
                         and abs(Decimal(actual) - Decimal(wanted)) <= Decimal(gold["numericPolicy"]["absoluteTolerance"]))
            if any(fact[field] != target[field] for field in ("unit", "currency")) or not same_value:
                failures.append("NUMERIC_FACT_MISMATCH:" + str(key))
            elif supported == "PASS":
                matched.add(key)
    if complete:
        for key in expected.keys() - seen:
            failures.append("REQUIRED_FACT_MISSING_OR_PERIOD_CHANGED:" + str(key))
    metrics["numeric"]["passed"] = len(matched)
    points = review.get("points") or {}
    if not isinstance(points, dict):
        raise ValueError("points must be an object keyed by independent gold point IDs")
    if set(points) != {point["id"] for point in gold["requiredPoints"]}:
        missing.append("TASK_POINT_COVERAGE_MISMATCH")
    for point in gold["requiredPoints"]:
        row = points.get(point["id"])
        if rating(row, "TASK_POINT:" + point["id"]) == "PASS":
            excerpt = row.get("answerExcerpt")
            if not isinstance(excerpt, str) or not excerpt.strip() or excerpt not in answer:
                missing.append("TASK_POINT_EXCERPT_MISMATCH:" + point["id"])
            else:
                metrics["task_coverage"]["passed"] += 1
    response = review.get("response_kind")
    if response not in {"ANSWER", "REFUSE", "CLARIFY"}:
        missing.append("RESPONSE_KIND_UNREVIEWED")
    elif response != gold["expectedResponse"]:
        failures.append("UNNECESSARY_REFUSAL" if response == "REFUSE" and gold["expectedResponse"] == "ANSWER" else "RESPONSE_KIND_MISMATCH")
    return {"status": "FAIL" if failures else "NO_DATA" if missing else "PASS",
            "failures": sorted(set(failures)), "errors": sorted(set(missing)), "metrics": metrics}


def apply_reviews(report: dict, reviews: dict, source_cases: list[dict] | None = None, gold: list[dict] | None = None) -> dict:
    cases = result_cases(report)
    labels = source_material(report, source_cases, gold)
    schema = REAL_SCHEMA if labels else SCHEMA
    kind = "PUBLIC_AUTHORIZED_REVIEW" if labels else "SYNTHETIC_MECHANISM_ONLY"
    if not isinstance(reviews, dict) or not isinstance(reviews.get("reviews"), list):
        raise ValueError("Reviews must contain a stage-review list")
    if reviews.get("schema") != schema or reviews.get("dataset_sha256") != report.get("dataset_sha256"):
        raise ValueError("Reviews belong to a different schema or dataset; export a new review template")
    indexed = {}
    for review in reviews.get("reviews", []):
        if not isinstance(review, dict) or not isinstance(review.get("binding"), dict):
            raise ValueError("Each stage review requires a binding object")
        key = ((review.get("binding") or {}).get("id"), review.get("scope"))
        if key[0] not in cases or key[1] not in {value[0] for value in STAGES.values()} or key in indexed:
            raise ValueError("Unknown or duplicate stage review: " + str(key))
        indexed[key] = review
    output = copy.deepcopy(report)
    statuses = []
    for case in output["cases"]:
        replay = case.get("replay") or {}
        errors = input_errors(case)
        execution_failed = (case.get("passed") is False or replay.get("status") == "FAILED"
                            or replay.get("analystStatus") == "FAILED" or bool(replay.get("errorCode"))
                            or any(value is False for value in (case.get("checks") or {}).values()))
        stage_reviews = {}
        for name, (scope, _) in STAGES.items():
            stage = replay.get(name) or {}
            review = indexed.get((case["id"], scope)) or {}
            assessment = assess_review(stage_case(case, name), review or None)
            assessment.update(scope=scope, evidence_kind=kind)
            assessment["errors"].extend(errors + stage_errors(case, name))
            failed = stage.get("status") == "FAILED" or bool(stage.get("errorCode"))
            if failed:
                assessment["errors"].append("STAGE_EXECUTION_FAILED")
            if failed or assessment["status"] == "FAIL":
                assessment["status"] = "FAIL"
            elif assessment["errors"] or stage.get("status") != "COMPLETED":
                assessment["status"] = "NO_DATA"
            if labels:
                checked = assess_claims(stage, review, labels[case["case_definition"]["caseId"]], case["case_definition"])
                assessment.update({key: copy.deepcopy(review.get(key)) for key in
                                   ("gold_sha256", "hard_gates", "extraction_complete", "claims", "points", "response_kind")})
                assessment["claim_checks"] = checked
                assessment["errors"].extend(checked["errors"])
                assessment["status"] = summarize_status([assessment["status"], checked["status"]])
            stage_reviews[name] = assessment
        status = summarize_status([row["status"] for row in stage_reviews.values()])
        if execution_failed:
            status = "FAIL"
        elif replay.get("status") != "COMPLETED" and status != "FAIL":
            status = "NO_DATA"
        case["quality_review"] = {"stages": stage_reviews, "review_status": status,
                                  "answer_quality": status if labels else "NO_DATA", "errors": errors}
        statuses.append(status)
    status = summarize_status(statuses)
    complete = bool(cases) and len(cases) == report["sample_count"] and report.get("status") == "PASS"
    if report.get("status") == "FAIL":
        status = "FAIL"
    elif not complete and status != "FAIL":
        status = "NO_DATA"
    output["answer_quality"] = status if labels else "NO_DATA"
    output["quality_evaluation"] = {
        "schema": schema, "evidence_kind": kind, "status": status if labels else "FAIL" if status == "FAIL" else "NO_DATA",
        "mechanism_status": {"PASS": "MECHANISM_PASS", "FAIL": "MECHANISM_FAIL", "NO_DATA": "NO_DATA"}[status],
        "sample_count": report["sample_count"], "sample_unit": "INVOCATION", "unique_case_count": report.get("unique_case_count"),
        "completed_count": len(cases),
        "reviewed_stage_count": sum(row["status"] in {"PASS", "FAIL"} and not row["errors"]
                                    for case in output["cases"] for row in case["quality_review"]["stages"].values())}
    return output


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--review-template", type=Path)
    mode.add_argument("--reviews", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--source-cases", type=Path, help="Independent raw/visible source JSONL; never sent to the Agent")
    parser.add_argument("--gold", type=Path, help="Independent reviewed expectations JSONL")
    args = parser.parse_args()
    if args.reviews and not args.output:
        parser.error("--reviews requires --output")
    if bool(args.source_cases) != bool(args.gold):
        parser.error("--source-cases and --gold must be supplied together")
    try:
        report = json.loads(args.input.read_text(encoding="utf-8"))
        sources = load_jsonl(args.source_cases) if args.source_cases else None
        gold = load_jsonl(args.gold) if args.gold else None
        if args.review_template:
            output, path = review_template(report, sources, gold), args.review_template
        else:
            output = apply_reviews(report, json.loads(args.reviews.read_text(encoding="utf-8")), sources, gold)
            path = args.output
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("x", encoding="utf-8") as target:
            target.write(json.dumps(output, ensure_ascii=False, indent=2) + "\n")
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(2, f"Evolution quality review failed: {error}\n")
    status = ("REVIEW_TEMPLATE" if args.review_template else output["quality_evaluation"]["status"]
              if output["quality_evaluation"]["evidence_kind"] == "PUBLIC_AUTHORIZED_REVIEW"
              else output["quality_evaluation"]["mechanism_status"])
    print(json.dumps({"status": status, "answer_quality": output.get("answer_quality", "NO_DATA")}))
    return 0 if status in {"REVIEW_TEMPLATE", "MECHANISM_PASS", "PASS"} else 2


if __name__ == "__main__":
    raise SystemExit(main())
