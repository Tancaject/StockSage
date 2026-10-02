import copy
import unittest

from eval_common import json_hash, summarize_status
from judge_calibration import calibrate, compare_reports
from ordinary_answer_quality import DIMENSIONS, ordinary_rubric


def pilot():
    rows = []
    rubric_hash = ordinary_rubric()["sha256"]
    for key, value in (("correct", "10"), ("wrong", "20")):
        labels = {name: {"status": "PASS", "reason": "Question, source and arithmetic checked"} for name in DIMENSIONS}
        labels["counterevidence"]["status"] = "NOT_APPLICABLE"
        if key == "wrong":
            for name in ("claim_support", "numeric_period_correctness"):
                labels[name] = {"status": "FAIL", "reason": "100 to 110 is 10%, whereas the answer states 20%"}
        rows.append({"id": key, "group_id": "public-report", "split": "DEV",
                     "question": "What was revenue growth?", "answer": "Revenue grew " + value + "%.",
                     "evidence": "Revenue increased from 100 to 110.", "label_origin": "AI_SOURCE_CHECKED",
                     "source_url": "https://example.test/report", "source_title": "Arithmetic fixture",
                     "rubric_sha256": rubric_hash, "label_review": {"status": "AI_CROSS_CHECKED",
                         "rubric_sha256": rubric_hash, "reviewers": ["AI arithmetic fixture", "AI rubric fixture"]},
                     "expected_dimensions": labels})
    return rows


def diagnostic(dataset, repeats=2):
    reviews = []
    for case in dataset:
        if case["split"] != "DEV":
            continue
        for repeat in range(1, repeats + 1):
            dimensions = {name: {**label, "answer_quotes": [case["answer"]] if case["answer"] else [],
                                "evidence_quotes": [case["evidence"]] if case["evidence"] else []}
                          for name, label in case["expected_dimensions"].items()}
            reviews.append({"id": case["id"], "repeat": repeat,
                            "input_sha256": json_hash({key: case[key] for key in ("question", "answer", "evidence")}),
                            "dimensions": dimensions, "errors": [], "technical_status": "VALID",
                            "status": summarize_status([row["status"] for row in dimensions.values()]),
                            "usage": {"prompt_tokens": 100, "completion_tokens": 30, "total_tokens": 130},
                            "calls": [{"observed_model": "judge"}], "tool_trace": [], "latency_ms": 200})
    return {"schema": "ordinary_judge_diagnostic_v2", "origin": "AI", "diagnostic_only": True,
            "release_eligible": False, "input_kind": "pilot", "dataset_sha256": json_hash(dataset),
            "rubric": ordinary_rubric(), "judge": {"config": {"model": "judge"}, "prompt_sha256": "prompt",
                                                   "tools_sha256": "tools", "runner_sha256": "runner"},
            "split": "DEV", "repeats": repeats, "reviews": reviews, "status": "COMPLETED"}


def set_status(review, name, status):
    review["dimensions"][name]["status"] = status
    if "reported_status" in review["dimensions"][name]:
        review["dimensions"][name]["reported_status"] = status
    review["status"] = summarize_status([row["status"] for row in review["dimensions"].values()])


class JudgeCalibrationTest(unittest.TestCase):
    def test_crosschecked_labels_bindings_cost_and_historical_rejection(self):
        dataset = pilot()
        report = diagnostic(dataset)
        result = calibrate(dataset, report)
        self.assertEqual(result["schema"], "judge_calibration_v2")
        self.assertEqual(result["dimension_total"]["end_to_end_label_agreement_count"], 20)
        self.assertEqual(result["overall"]["semantic_agreement_rate"], 1)
        self.assertEqual(result["repeatability"]["consistency_rate"], 1)
        self.assertEqual(result["cost"]["total_tokens"], 520)
        self.assertFalse(result["release_eligible"])
        for field, value in (("dataset_sha256", "changed"), ("rubric", {}), ("repeats", True)):
            changed = copy.deepcopy(report)
            changed[field] = value
            with self.assertRaises(ValueError):
                calibrate(dataset, changed)
        for field, value in (("status", "DISPUTED"), ("rubric_sha256", "old"), ("reviewers", ["same", "same"])):
            changed = copy.deepcopy(dataset)
            changed[0]["label_review"][field] = value
            with self.assertRaises(ValueError):
                calibrate(changed, diagnostic(changed))
        changed = copy.deepcopy(dataset)
        changed[0]["expected_dimensions"]["task_completion"]["status"] = "NOT_APPLICABLE"
        with self.assertRaisesRegex(ValueError, "NOT_APPLICABLE"):
            calibrate(changed, diagnostic(changed))
        report["schema"] = "ordinary_judge_diagnostic_v1"
        with self.assertRaisesRegex(ValueError, "preserve v1"):
            calibrate(dataset, report)

    def test_technical_coverage_abstention_and_semantic_denominators_are_separate(self):
        dataset = pilot()
        report = diagnostic(dataset)
        set_status(report["reviews"][0], "numeric_period_correctness", "FAIL")
        set_status(report["reviews"][2], "numeric_period_correctness", "PASS")
        set_status(report["reviews"][3], "numeric_period_correctness", "NO_DATA")
        metrics = calibrate(dataset, report)["dimensions"]["numeric_period_correctness"]
        self.assertEqual(metrics["technical_valid_rate"], 1)
        self.assertEqual(metrics["known_judgment_coverage"], 0.75)
        self.assertEqual(metrics["semantic_agreement_rate"], 1 / 3)
        self.assertEqual(metrics["end_to_end_label_agreement_rate"], 0.25)
        self.assertEqual(metrics["valid_abstention_on_known_count"], 1)
        self.assertEqual((metrics["false_pass_count"], metrics["false_pass_denominator"]), (1, 1))
        self.assertEqual((metrics["false_fail_count"], metrics["false_fail_denominator"]), (1, 2))

    def test_runner_local_quote_error_preserves_others_global_binding_does_not(self):
        from run_evaluation_agent import validate_verdict
        dataset = pilot()
        report = diagnostic(dataset)
        item = {**dataset[0], "errors": [], "binding": None}
        verdict = {"dimensions": copy.deepcopy(report["reviews"][0]["dimensions"])}
        verdict["dimensions"]["unknowns"]["evidence_quotes"] = ["not in the frozen evidence"]
        checked = validate_verdict(item, verdict)
        self.assertEqual(checked["technical_status"], "PARTIAL")
        self.assertEqual(checked["errors"], [])
        report["reviews"][0].update(checked)
        result = calibrate(dataset, report)
        self.assertEqual(result["dimension_total"]["technical_valid_count"], 19)
        self.assertEqual(result["observations"][0]["dimensions"]["unknowns"]["reported_status"], "PASS")
        self.assertTrue(result["observations"][0]["dimensions"]["claim_support"]["valid"])
        item["errors"] = ["INPUT_BINDING_MISMATCH"]
        report["reviews"][0].update(validate_verdict(item, verdict))
        self.assertEqual(calibrate(dataset, report)["dimension_total"]["technical_valid_count"], 15)
        report["reviews"].pop()
        result = calibrate(dataset, report)
        self.assertEqual(result["attempt_count"], 4)
        self.assertEqual(result["recorded_attempt_count"], 3)
        self.assertEqual(result["dimension_total"]["technical_valid_count"], 10)
        report["reviews"].append(copy.deepcopy(report["reviews"][0]))
        with self.assertRaisesRegex(ValueError, "duplicate"):
            calibrate(dataset, report)

    def test_selection_uses_common_pairs_blocks_abstention_and_new_false_pass(self):
        dataset = pilot()
        baseline = diagnostic(dataset, repeats=1)
        set_status(baseline["reviews"][1], "numeric_period_correctness", "PASS")
        good = diagnostic(dataset, repeats=1)
        comparison = compare_reports(dataset, baseline, good)
        self.assertTrue(comparison["candidate_selection_eligible"])
        self.assertEqual(comparison["corrected_count"], 1)
        abstaining = copy.deepcopy(good)
        set_status(abstaining["reviews"][1], "numeric_period_correctness", "NO_DATA")
        result = compare_reports(dataset, baseline, abstaining)
        self.assertEqual(result["coverage_lost_count"], 1)
        self.assertFalse(result["candidate_selection_eligible"])
        set_status(baseline["reviews"][1], "unknowns", "FAIL")
        regressed = copy.deepcopy(good)
        set_status(regressed["reviews"][1], "claim_support", "PASS")
        result = compare_reports(dataset, baseline, regressed)
        self.assertEqual(result["common_semantic_pairs"]["net_agreement_gain"], 1)
        self.assertEqual(result["new_false_pass_count"], 1)
        self.assertFalse(result["candidate_selection_eligible"])
        baseline = diagnostic(dataset, repeats=1)
        set_status(baseline["reviews"][0], "numeric_period_correctness", "NO_DATA")
        set_status(baseline["reviews"][1], "numeric_period_correctness", "PASS")
        gained = copy.deepcopy(good)
        set_status(gained["reviews"][0], "numeric_period_correctness", "FAIL")
        result = compare_reports(dataset, baseline, gained)
        self.assertEqual(result["coverage_gained_count"], 1)
        self.assertEqual(result["common_semantic_pairs"]["count"], 9)
        self.assertTrue(result["candidate_selection_eligible"])

    def test_valid_no_data_is_distinct_from_failure_validation_never_selects(self):
        dataset = pilot()
        dataset[0].update(answer="The supplied material does not state revenue growth.", evidence="")
        for name in DIMENSIONS:
            if name != "task_completion":
                dataset[0]["expected_dimensions"][name] = {"status": "NO_DATA", "reason": "Source capture is absent"}
        report = diagnostic(dataset)
        result = calibrate(dataset, report)
        self.assertEqual(result["dimension_total"]["technical_valid_count"], 20)
        self.assertEqual(result["dimension_total"]["valid_correct_no_data_count"], 8)
        self.assertEqual(result["dimension_total"]["semantic_comparison_count"], 12)
        self.assertEqual(result["repeatability"]["eligible_case_count"], 2)
        for case in dataset:
            case["split"] = "VALIDATION"
        report.update(split="VALIDATION", dataset_sha256=json_hash(dataset))
        compared = compare_reports(dataset, report, report)
        self.assertEqual(compared["purpose"], "VALIDATION_OBSERVATION")
        self.assertFalse(compared["candidate_selection_eligible"])

    def test_missing_usage_does_not_turn_known_subtotal_into_complete_cost(self):
        dataset = pilot()
        report = diagnostic(dataset)
        report["reviews"][0]["usage"] = None
        cost = calibrate(dataset, report)["cost"]
        self.assertFalse(cost["usage_complete"])
        self.assertIsNone(cost["total_tokens"])
        self.assertEqual(cost["known_total_tokens"], 390)
        self.assertEqual(cost["total_tokens_reported_attempts"], 3)

    def test_comparison_rejects_changed_configuration_repeats_and_observed_model(self):
        dataset = pilot()
        baseline = diagnostic(dataset)
        for field, value in (("config", {"model": "different"}), ("tools_sha256", "different"), ("runner_sha256", "different")):
            changed = copy.deepcopy(baseline)
            changed["judge"][field] = value
            with self.assertRaisesRegex(ValueError, field):
                compare_reports(dataset, baseline, changed)
        changed = copy.deepcopy(baseline)
        changed["reviews"][0]["calls"][0]["observed_model"] = "actual-different-model"
        with self.assertRaisesRegex(ValueError, "observed model"):
            compare_reports(dataset, baseline, changed)
        changed = copy.deepcopy(baseline)
        for review in changed["reviews"]:
            review["calls"] = []
        with self.assertRaisesRegex(ValueError, "observed model"):
            compare_reports(dataset, baseline, changed)
        with self.assertRaisesRegex(ValueError, "repeats"):
            compare_reports(dataset, baseline, diagnostic(dataset, repeats=1))


if __name__ == "__main__":
    unittest.main()
