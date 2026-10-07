"""Calibrate AI diagnostic reviews against frozen pilot labels; never authorize release."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from eval_common import json_hash, summarize_status
from ordinary_answer_quality import DIMENSIONS, assess_diagnostic_dimensions, ordinary_rubric

KNOWN = {"PASS", "FAIL", "NOT_APPLICABLE"}
STATUSES = KNOWN | {"NO_DATA"}
COMPARISON_POLICY = {
    "schema": "judge_comparison_policy_v2",
    "requirements": ["net_agreement_gain_on_common_valid_known_pairs", "no_common_new_false_pass",
                     "no_known_label_item_coverage_loss", "preserve_correct_no_data",
                     "no_new_unsupported_certainty"],
    "release_eligible": False,
}


def _validate(dataset: list[dict], report: dict) -> tuple[list[dict], dict]:
    if (not isinstance(report, dict) or report.get("schema") != "ordinary_judge_diagnostic_v2"
            or report.get("origin") != "AI" or report.get("diagnostic_only") is not True
            or report.get("release_eligible") is not False or report.get("input_kind") != "pilot"):
        raise ValueError("Expected an AI-only pilot diagnostic v2 report; preserve v1 artifacts, review labels under the current rubric and rerun into a new output")
    if not isinstance(dataset, list) or not dataset:
        raise ValueError("Pilot dataset must be a nonempty list")
    rubric = ordinary_rubric()
    ids, groups = set(), {}
    for case in dataset:
        if not isinstance(case, dict):
            raise ValueError("Pilot rows must be objects")
        key, group, split = case.get("id"), case.get("group_id"), case.get("split")
        if not isinstance(key, str) or not key or key in ids:
            raise ValueError("Pilot ids must be nonempty and unique")
        ids.add(key)
        if not isinstance(group, str) or not group or not isinstance(split, str) or split not in {"DEV", "VALIDATION"}:
            raise ValueError("Each pilot row needs group_id and DEV or VALIDATION split")
        if group in groups and groups[group] != split:
            raise ValueError("A source group cannot occur in both dataset splits")
        groups[group] = split
        if any(not isinstance(case.get(name), str) for name in ("question", "answer", "evidence")):
            raise ValueError("Pilot question, answer and evidence must be strings")
        review = case.get("label_review")
        if (case.get("rubric_sha256") != rubric["sha256"] or not isinstance(review, dict)
                or review.get("status") != "AI_CROSS_CHECKED" or review.get("rubric_sha256") != rubric["sha256"]):
            raise ValueError("Pilot labels are unreviewed, disputed or use another rubric: " + key)
        reviewers = review.get("reviewers")
        if (not isinstance(reviewers, list) or any(not isinstance(name, str) or not name.strip() for name in reviewers)
                or len({name.strip() for name in reviewers}) < 2):
            raise ValueError("Pilot labels need two distinct AI review roles: " + key)
        labels = case.get("expected_dimensions")
        if not isinstance(labels, dict) or set(labels) != set(DIMENSIONS):
            raise ValueError("Pilot labels must contain all current ordinary-answer dimensions")
        for name, label in labels.items():
            if (not isinstance(label, dict) or not isinstance(label.get("status"), str) or label["status"] not in STATUSES
                    or not isinstance(label.get("reason"), str) or not label["reason"].strip()):
                raise ValueError("Each pilot label needs a supported status and a reason")
            if label["status"] == "NOT_APPLICABLE" and name not in {"numeric_period_correctness", "counterevidence"}:
                raise ValueError("NOT_APPLICABLE is forbidden for pilot label " + name)
    if report.get("dataset_sha256") != json_hash(dataset):
        raise ValueError("Diagnostic report does not match the full frozen dataset")
    if report.get("rubric") != ordinary_rubric():
        raise ValueError("Diagnostic rubric is missing or changed; rerun under current criteria")
    if not isinstance(report.get("split"), str) or report["split"] not in {"DEV", "VALIDATION"}:
        raise ValueError("Diagnostic split must be DEV or VALIDATION")
    if type(report.get("repeats")) is not int or report["repeats"] < 1:
        raise ValueError("Diagnostic repeats must be a positive integer")
    selected = [case for case in dataset if case["split"] == report["split"]]
    if not selected:
        raise ValueError("The selected split has no dataset rows")
    selected_ids = {case["id"] for case in selected}
    if not isinstance(report.get("reviews"), list):
        raise ValueError("Diagnostic reviews must be a list")
    indexed = {}
    for review in report["reviews"]:
        if not isinstance(review, dict):
            raise ValueError("Diagnostic review must be an object")
        key, repeat = review.get("id"), review.get("repeat")
        if (not isinstance(key, str) or key not in selected_ids or type(repeat) is not int
                or not 1 <= repeat <= report["repeats"] or (key, repeat) in indexed):
            raise ValueError("Unknown, duplicate or out-of-range diagnostic attempt")
        indexed[key, repeat] = review
    return selected, indexed


def _assessment(case: dict, review: dict | None) -> dict:
    errors, ratings = [], {}
    if review is None:
        errors.append("REVIEW_MISSING")
    else:
        if review.get("input_sha256") != json_hash({key: case[key] for key in ("question", "answer", "evidence")}):
            errors.append("INPUT_BINDING_MISMATCH")
        if not isinstance(review.get("errors"), list) or any(not isinstance(error, str) for error in review["errors"]):
            errors.append("GLOBAL_ERRORS_MALFORMED")
        else:
            errors.extend(review["errors"])
        ratings = review.get("dimensions")
        if isinstance(ratings, dict) and set(ratings) - set(DIMENSIONS):
            errors.append("UNREGISTERED_DIMENSIONS")
    checked = assess_diagnostic_dimensions(case, ratings, errors)
    dimensions = {name: {"expected": case["expected_dimensions"][name]["status"],
                         "actual": row["status"], "reported_status": row["reported_status"],
                         "errors": row["errors"], "valid": not row["errors"]}
                  for name, row in checked["dimensions"].items()}
    return {"dimensions": dimensions, "expected": summarize_status([
        case["expected_dimensions"][name]["status"] for name in DIMENSIONS]),
        "actual": checked["status"], "errors": checked["errors"], "technical_status": checked["technical_status"],
        "valid": checked["technical_status"] == "VALID"}


def _metrics(rows: list[dict]) -> dict:
    """Semantic rates require valid known labels/judgments; false passes use expected FAIL.

    Overall rows require every dimension valid; dimension rows stand independently.
    """
    total = len(rows)
    valid = [row for row in rows if row["valid"]]
    known_labels = sum(row["expected"] in KNOWN for row in rows)
    semantic = [row for row in valid if row["expected"] in KNOWN and row["actual"] in KNOWN]
    passed = [row for row in semantic if row["expected"] in {"PASS", "NOT_APPLICABLE"}]
    failed = [row for row in semantic if row["expected"] == "FAIL"]
    exact = sum(row["expected"] == row["actual"] for row in valid)
    semantic_exact = sum(row["expected"] == row["actual"] for row in semantic)
    correct_no_data = sum(row["expected"] == row["actual"] == "NO_DATA" for row in valid)
    false_pass = sum(row["actual"] in {"PASS", "NOT_APPLICABLE"} for row in failed)
    false_fail = sum(row["actual"] == "FAIL" for row in passed)
    return {
        "count": total, "known_label_count": known_labels, "expected_no_data_count": total - known_labels,
        "technical_valid_count": len(valid), "technical_invalid_count": total - len(valid),
        "technical_valid_rate": len(valid) / total if total else None,
        "known_judgment_count": len(semantic), "known_judgment_coverage": len(semantic) / known_labels if known_labels else None,
        "semantic_comparison_count": len(semantic), "semantic_agreement_count": semantic_exact,
        "semantic_error_count": len(semantic) - semantic_exact,
        "semantic_agreement_rate": semantic_exact / len(semantic) if semantic else None,
        "valid_no_data_count": sum(row["actual"] == "NO_DATA" for row in valid),
        "valid_abstention_on_known_count": sum(row["expected"] in KNOWN and row["actual"] == "NO_DATA" for row in valid),
        "valid_correct_no_data_count": correct_no_data,
        "correct_no_data_rate": correct_no_data / (total - known_labels) if total > known_labels else None,
        "end_to_end_label_agreement_count": exact, "end_to_end_label_agreement_rate": exact / total if total else None,
        "false_pass_count": false_pass, "false_pass_denominator": len(failed),
        "false_pass_rate": false_pass / len(failed) if failed else None,
        "false_fail_count": false_fail, "false_fail_denominator": len(passed),
        "false_fail_rate": false_fail / len(passed) if passed else None,
        "unsupported_certainty_count": sum(row["expected"] == "NO_DATA" and row["actual"] in KNOWN for row in valid),
        "confusion_valid": {expected: {actual: sum(row["expected"] == expected and row["actual"] == actual for row in valid)
                                 for actual in sorted(STATUSES)} for expected in sorted(STATUSES)},
    }


def _cost(reviews: list[dict]) -> dict:
    totals = {}
    for name in ("prompt_tokens", "completion_tokens", "total_tokens"):
        values = [(row.get("usage") or {}).get(name) for row in reviews if isinstance(row.get("usage"), dict)]
        values = [value for value in values if type(value) is int and value >= 0]
        totals[name] = sum(values) if reviews and len(values) == len(reviews) else None
        totals["known_" + name] = sum(values) if values else None
        totals[name + "_reported_attempts"] = len(values)
    latency = [row.get("latency_ms") for row in reviews]
    latency = [value for value in latency if type(value) in (int, float) and value >= 0]
    return {**totals, "usage_complete": bool(reviews) and all(totals[name] is not None for name in
                                                             ("prompt_tokens", "completion_tokens", "total_tokens")),
            "usage_scope": "recorded_attempts",
            "call_count": sum(len(row["calls"]) for row in reviews if isinstance(row.get("calls"), list)),
            "call_count_reported_attempts": sum(isinstance(row.get("calls"), list) for row in reviews),
            "summed_attempt_latency_ms": sum(latency) if latency else None,
            "latency_reported_attempts": len(latency), "monetary_cost": None, "pricing_status": "UNCONFIGURED"}


def calibrate(dataset: list[dict], report: dict) -> dict:
    selected, indexed = _validate(dataset, report)
    observations = [{"id": case["id"], "repeat": repeat, **_assessment(case, indexed.get((case["id"], repeat)))}
                    for case in selected for repeat in range(1, report["repeats"] + 1)]
    consistent, eligible = 0, 0
    for case in selected:
        rows = [row for row in observations if row["id"] == case["id"]]
        if report["repeats"] > 1 and all(row["valid"] for row in rows):
            eligible += 1
            consistent += len({tuple(row["dimensions"][name]["actual"] for name in DIMENSIONS) for row in rows}) == 1
    return {"schema": "judge_calibration_v2", "diagnostic_only": True, "release_eligible": False,
            "dataset_sha256": report["dataset_sha256"], "rubric": ordinary_rubric(),
            "judge": report.get("judge"), "split": report["split"], "repeats": report["repeats"],
            "sample_count": len(selected), "attempt_count": len(observations), "recorded_attempt_count": len(indexed),
            "label_origins": sorted({str(case.get("label_origin", "UNSPECIFIED")) for case in selected}),
            "metric_denominators": {"technical_valid_rate": "count", "known_judgment_coverage": "known_label_count",
                                    "semantic_agreement_rate": "semantic_comparison_count",
                                    "correct_no_data_rate": "expected_no_data_count",
                                    "end_to_end_label_agreement_rate": "count",
                                    "false_pass_rate": "false_pass_denominator", "false_fail_rate": "false_fail_denominator"},
            "metric_definitions": {
                "scope": "overall counts complete technically valid reviews; dimension_total counts dimensions independently",
                "semantic_comparison": "technically valid, with both expected and actual in PASS/FAIL/NOT_APPLICABLE",
                "false_pass_denominator": "expected FAIL among semantic comparisons",
                "false_fail_denominator": "expected PASS or NOT_APPLICABLE among semantic comparisons",
                "no_data": "valid NO_DATA judgments are counted separately from technical failures and semantic comparisons"},
            "overall": _metrics(observations),
            "dimensions": {name: _metrics([row["dimensions"][name] for row in observations]) for name in DIMENSIONS},
            "dimension_total": _metrics([row["dimensions"][name] for row in observations for name in DIMENSIONS]),
            "repeatability": {"eligible_case_count": eligible, "consistent_case_count": consistent,
                              "excluded_case_count": len(selected) - eligible,
                              "consistency_rate": consistent / eligible if eligible else None,
                              "requires": "at least two technically valid repeats; legitimate NO_DATA judgments are included"},
            "cost": _cost(report["reviews"]), "observations": observations}


def compare_reports(dataset: list[dict], baseline: dict, candidate: dict) -> dict:
    before, after = calibrate(dataset, baseline), calibrate(dataset, candidate)
    for field in ("dataset_sha256", "rubric", "split", "repeats"):
        if before[field] != after[field]:
            raise ValueError("Judge comparisons require identical " + field)
    for field in ("config", "tools_sha256", "runner_sha256"):
        left = (before.get("judge") or {}).get(field)
        right = (after.get("judge") or {}).get(field)
        if not left or left != right:
            raise ValueError("Prompt comparisons require identical nonempty judge " + field)
    models = [{call["observed_model"] for row in report["reviews"]
               for call in (row.get("calls") if isinstance(row.get("calls"), list) else [])
               if isinstance(call, dict) and isinstance(call.get("observed_model"), str) and call["observed_model"]}
              for report in (baseline, candidate)]
    if not all(models) or models[0] != models[1]:
        raise ValueError("Prompt comparisons require identical nonempty observed model sets")
    transitions = []
    for left, right in zip(before["observations"], after["observations"]):
        for name in DIMENSIONS:
            a, b = left["dimensions"][name], right["dimensions"][name]
            old_known, new_known = a["valid"] and a["actual"] in KNOWN, b["valid"] and b["actual"] in KNOWN
            common = a["expected"] in KNOWN and old_known and new_known
            old_correct = a["valid"] and a["actual"] == a["expected"]
            new_correct = b["valid"] and b["actual"] == b["expected"]
            transitions.append({"id": left["id"], "repeat": left["repeat"], "dimension": name,
                                "expected": a["expected"], "baseline": a["actual"], "candidate": b["actual"],
                                "baseline_valid": a["valid"], "candidate_valid": b["valid"], "common_semantic_pair": common,
                                "corrected": common and not old_correct and new_correct,
                                "regressed": common and old_correct and not new_correct,
                                "new_false_pass": common and a["expected"] == "FAIL" and a["actual"] == "FAIL" and b["actual"] in {"PASS", "NOT_APPLICABLE"},
                                "coverage_gained": a["expected"] in KNOWN and not old_known and new_known,
                                "coverage_lost": a["expected"] in KNOWN and old_known and not new_known,
                                "correct_no_data_gained": a["expected"] == "NO_DATA" and not old_correct and new_correct,
                                "correct_no_data_lost": a["expected"] == "NO_DATA" and old_correct and not new_correct,
                                "new_unsupported_certainty": a["expected"] == "NO_DATA" and not old_known and new_known})
    counts = {name + "_count": sum(row[name] for row in transitions)
              for name in ("corrected", "regressed", "new_false_pass", "coverage_gained", "coverage_lost",
                           "correct_no_data_gained", "correct_no_data_lost", "new_unsupported_certainty")}
    common = [row for row in transitions if row["common_semantic_pair"]]
    baseline_agreement = sum(row["baseline"] == row["expected"] for row in common)
    candidate_agreement = sum(row["candidate"] == row["expected"] for row in common)
    gain = candidate_agreement - baseline_agreement
    eligible = gain > 0 and not any(counts[name] for name in (
        "new_false_pass_count", "coverage_lost_count", "correct_no_data_lost_count", "new_unsupported_certainty_count"))
    return {"schema": "judge_comparison_v2", "diagnostic_only": True, "release_eligible": False,
            "split": after["split"], "purpose": "DEV_SELECTION" if after["split"] == "DEV" else "VALIDATION_OBSERVATION",
            "policy": COMPARISON_POLICY, "policy_sha256": json_hash(COMPARISON_POLICY),
            "criterion_met": eligible, "candidate_selection_eligible": eligible and after["split"] == "DEV",
            "common_semantic_pairs": {"count": len(common), "baseline_agreement_count": baseline_agreement,
                                      "candidate_agreement_count": candidate_agreement, "net_agreement_gain": gain,
                                      "baseline_agreement_rate": baseline_agreement / len(common) if common else None,
                                      "candidate_agreement_rate": candidate_agreement / len(common) if common else None},
            **counts, "baseline": before, "candidate": after,
            "transitions": transitions}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", required=True)
    parser.add_argument("--report", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--baseline")
    args = parser.parse_args()
    try:
        dataset = [json.loads(line) for line in Path(args.dataset).read_text(encoding="utf-8-sig").splitlines() if line.strip()]
        report = json.loads(Path(args.report).read_text(encoding="utf-8-sig"))
        result = compare_reports(dataset, json.loads(Path(args.baseline).read_text(encoding="utf-8-sig")), report) if args.baseline else calibrate(dataset, report)
        output = Path(args.output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    except (OSError, ValueError, TypeError) as exc:
        parser.error(str(exc))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
