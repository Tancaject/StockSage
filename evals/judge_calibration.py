"""Calibrate AI diagnostic reviews against frozen pilot labels; never authorize release.

Ordinary five-dimension reports use calibrate/compare_reports; DEEP facet reports use calibrate_facets.
"""
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


def planned_attempts(report: dict, cases: list[dict]) -> list[tuple[str, int]]:
    """Older reports repeat every case; sampled rejudging records the exact attempt plan."""
    repeats = report.get("repeats")
    if type(repeats) is not int or repeats < 1:
        raise ValueError("Diagnostic repeats must be a positive integer")
    ids = {row["id"] for row in cases}
    plan = report.get("planned_attempts")
    if plan is None:
        return [(row["id"], repeat) for row in cases for repeat in range(1, repeats + 1)]
    if not isinstance(plan, list) or not plan:
        raise ValueError("planned_attempts must be a nonempty list")
    result = []
    for row in plan:
        if (not isinstance(row, dict) or row.get("id") not in ids or type(row.get("repeat")) is not int
                or not 1 <= row["repeat"] <= repeats):
            raise ValueError("Unknown or out-of-range planned review attempt")
        result.append((row["id"], row["repeat"]))
    if len(set(result)) != len(result) or any((key, 1) not in result for key in ids):
        raise ValueError("Review plans must be unique and include the first attempt for every case")
    return result


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
                or review.get("status") not in {"AI_CROSS_CHECKED", "HUMAN_CONFIRMED"}
                or review.get("rubric_sha256") != rubric["sha256"]):
            raise ValueError("Pilot labels are unreviewed, disputed or use another rubric: " + key)
        reviewers = review.get("reviewers")
        if (not isinstance(reviewers, list) or any(not isinstance(name, str) or not name.strip() for name in reviewers)
                or len({name.strip() for name in reviewers}) < (1 if review["status"] == "HUMAN_CONFIRMED" else 2)):
            raise ValueError("Labels need one human reviewer or two distinct AI review roles: " + key)
        if review["status"] == "HUMAN_CONFIRMED" and (
                case.get("label_origin") != "HUMAN" or not str(review.get("reviewed_at") or "").strip()):
            raise ValueError("Human-confirmed labels need HUMAN origin and reviewed_at: " + key)
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
    attempts = set(planned_attempts(report, selected))
    if not isinstance(report.get("reviews"), list):
        raise ValueError("Diagnostic reviews must be a list")
    indexed = {}
    for review in report["reviews"]:
        if not isinstance(review, dict):
            raise ValueError("Diagnostic review must be an object")
        key, repeat = review.get("id"), review.get("repeat")
        if (not isinstance(key, str) or type(repeat) is not int
                or (key, repeat) not in attempts or (key, repeat) in indexed):
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
        "technical_invalid_rate": (total - len(valid)) / total if total else None,
        "no_data_rate": sum(row["actual"] == "NO_DATA" for row in valid) / total if total else None,
        "not_applicable_rate": sum(row["actual"] == "NOT_APPLICABLE" for row in valid) / total if total else None,
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
    cases = {case["id"]: case for case in selected}
    observations = [{"id": key, "repeat": repeat, **_assessment(cases[key], indexed.get((key, repeat)))}
                    for key, repeat in planned_attempts(report, selected)]
    consistent, eligible = 0, 0
    for case in selected:
        rows = [row for row in observations if row["id"] == case["id"]]
        if len(rows) > 1 and all(row["valid"] for row in rows):
            eligible += 1
            consistent += len({tuple(row["dimensions"][name]["actual"] for name in DIMENSIONS) for row in rows}) == 1
    return {"schema": "judge_calibration_v2", "diagnostic_only": True, "release_eligible": False,
            "dataset_sha256": report["dataset_sha256"], "rubric": ordinary_rubric(),
            "planned_attempts": [{"id": key, "repeat": repeat} for key, repeat in planned_attempts(report, selected)],
            "human_calibrated_sample_count": sum(case["label_review"]["status"] == "HUMAN_CONFIRMED" for case in selected),
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


def _weighted_kappa(pairs: list[tuple[int, int]], k: int = 3) -> float | None:
    """Quadratic weighted kappa for ordinal 0..k-1 scores."""
    n = len(pairs)
    observed = [[sum(e == i and a == j for e, a in pairs) for j in range(k)] for i in range(k)]
    rows = [sum(observed[i]) for i in range(k)]
    cols = [sum(observed[i][j] for i in range(k)) for j in range(k)]
    weight = [[(i - j) ** 2 / (k - 1) ** 2 for j in range(k)] for i in range(k)]
    disagreement = sum(weight[i][j] * observed[i][j] for i in range(k) for j in range(k))
    expected = sum(weight[i][j] * rows[i] * cols[j] / n for i in range(k) for j in range(k)) if n else 0
    return 1 - disagreement / expected if expected else None


def calibrate_facets(dataset: list[dict], report: dict) -> dict:
    """Agreement of DEEP facet scores with labelled rows. Invalid judgments count against coverage, not agreement."""
    from deep_facet_quality import assess_facets, deep_rubric, input_hash, summarize
    if report.get("schema") not in {"deep_facet_judge_v1", "deep_facet_judge_v2"} or report.get("input_kind") != "pilot":
        raise ValueError("Expected a deep facet judge report on a labelled pilot")
    binary = report["schema"] == "deep_facet_judge_v2"
    if binary and report.get("rubric") != deep_rubric():
        raise ValueError("DEEP binary rubric differs; preserve old reports and review labels under v2")
    if report.get("dataset_sha256") != json_hash(dataset):
        raise ValueError("Judge report does not match the full labelled dataset")
    selected = [row for row in dataset if row.get("split") == report.get("split")]
    if not selected:
        raise ValueError("The selected split has no dataset rows")
    for row in selected:
        labels = row.get("expected_facets")
        if (not isinstance(labels, dict) or set(labels) != {facet["id"] for facet in row["facets"]}
                or any(type(label.get("score")) is not int or label["score"] not in (0, 1, 2) for label in labels.values())):
            raise ValueError("Each pilot row needs a 0/1/2 label for every facet: " + str(row.get("id")))
        if binary:
            if row.get("rubric_sha256") != deep_rubric()["sha256"]:
                raise ValueError("DEEP v2 pilot labels must bind the binary rubric: " + row["id"])
            review = row.get("label_review") or {}
            human = review.get("status") == "HUMAN_CONFIRMED"
            reviewers = review.get("reviewers")
            if (review.get("status") not in {"HUMAN_CONFIRMED", "AI_CROSS_CHECKED"}
                    or review.get("rubric_sha256") != row["rubric_sha256"]
                    or not isinstance(reviewers, list)
                    or any(not isinstance(name, str) or not name.strip() for name in reviewers)
                    or len(set(reviewers)) < (1 if human else 2)
                    or (human and (row.get("label_origin") != "HUMAN" or not review.get("reviewed_at")))):
                raise ValueError("DEEP v2 labels require a completed human or AI cross-review: " + row["id"])
            for label in labels.values():
                relevant, direct = label.get("relevant_evidence"), label.get("direct_support")
                if (type(relevant) is not bool or type(direct) is not bool or (direct and not relevant)
                        or label["score"] != int(relevant) + int(direct)
                        or not isinstance(label.get("reason"), str) or not label["reason"].strip()):
                    raise ValueError("DEEP v2 labels need consistent binary judgments and score: " + row["id"])
    attempts = planned_attempts(report, selected)
    reviews = {}
    for review in report.get("reviews", []):
        key = (review["id"], review["repeat"])
        if key not in attempts or key in reviews:
            raise ValueError("Unknown, duplicate or out-of-range facet review attempt")
        reviews[key] = review
    cases = {row["id"]: row for row in selected}
    pairs, invalid, disagreements, checked_reviews = [], 0, [], []
    for key, repeat in attempts:
        row = cases[key]
        review = reviews.get((key, repeat)) or {}
        errors = list(review.get("errors") or [])
        if review.get("input_sha256") != input_hash(row):
            errors.append("INPUT_BINDING_MISMATCH")
        checked = assess_facets(row, review.get("facets") or {}, errors, binary=binary)
        checked_reviews.append({"id": key, "repeat": repeat, "input_sha256": input_hash(row), **checked})
        for facet, label in row["expected_facets"].items():
            actual = checked["facets"][facet]["score"]
            if actual is None:
                invalid += 1
                continue
            pairs.append((label["score"], actual))
            if actual != label["score"]:
                disagreements.append({"id": key, "repeat": repeat, "facet": facet, "expected": label["score"],
                                      "actual": actual, "judge_reason": checked["facets"][facet].get("reason")})
    total = len(pairs) + invalid
    uncovered = [pair for pair in pairs if pair[0] == 0]
    covered = [pair for pair in pairs if pair[0] >= 1]
    return {"schema": "deep_facet_calibration_v2" if binary else "deep_facet_calibration_v1",
            "diagnostic_only": True, "release_eligible": False, "rubric": report.get("rubric"),
            "summary": summarize(checked_reviews), "cost": _cost(report.get("reviews", [])),
            "split": report["split"], "repeats": report["repeats"],
            "judge": report.get("judge"), "sample_count": len(selected),
            "label_origins": sorted({str(row.get("label_origin", "UNSPECIFIED")) for row in selected}),
            "facet_judgments": total, "technical_invalid_rate": invalid / total if total else None,
            "exact_agreement": sum(e == a for e, a in pairs) / len(pairs) if pairs else None,
            "within_one_agreement": sum(abs(e - a) <= 1 for e, a in pairs) / len(pairs) if pairs else None,
            "quadratic_weighted_kappa": _weighted_kappa(pairs),
            # False coverage is the facet analogue of a false pass: the judge credits evidence that is not there.
            "false_coverage_rate": sum(a >= 1 for _, a in uncovered) / len(uncovered) if uncovered else None,
            "false_coverage_denominator": len(uncovered),
            "missed_coverage_rate": sum(a == 0 for _, a in covered) / len(covered) if covered else None,
            "missed_coverage_denominator": len(covered),
            "confusion": {str(e): {str(a): sum(pair == (e, a) for pair in pairs) for a in range(3)} for e in range(3)},
            "disagreements": disagreements}


def compare_reports(dataset: list[dict], baseline: dict, candidate: dict) -> dict:
    before, after = calibrate(dataset, baseline), calibrate(dataset, candidate)
    for field in ("dataset_sha256", "rubric", "split", "repeats", "planned_attempts"):
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
        if report.get("schema") in {"deep_facet_judge_v1", "deep_facet_judge_v2"}:
            result = calibrate_facets(dataset, report)
        else:
            result = compare_reports(dataset, json.loads(Path(args.baseline).read_text(encoding="utf-8-sig")), report) if args.baseline else calibrate(dataset, report)
        output = Path(args.output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    except (OSError, ValueError, TypeError) as exc:
        parser.error(str(exc))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
