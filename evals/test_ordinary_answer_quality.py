import copy
import json
import unittest
from unittest.mock import patch

from ordinary_answer_quality import DIMENSIONS, REVIEW_REQUIREMENTS, analyst_inputs, apply_reviews, compare_reports, material, review_template
from eval_common import capture_answer, json_hash, sha256


def sample_report(answer="收入增长10% [E1]", instruction="只使用证据", evidence="[E1] 收入同比增长10%", model="model-a"):
    definition = {"id": "revenue", "question": "本期收入如何？", "route": "FUNDAMENTALS"}
    messages = [{"role": "system", "text": instruction}, {"role": "user", "text": evidence}]
    context = {"kind": "answer-context", "schemaVersion": 1, "scope": "ordinary-final-answer",
               "promptFingerprintScope": "TEXT_ONLY", "hasImages": False, "evidenceCaptureComplete": True,
               "context": evidence, "contextSha256": sha256(evidence),
               "evidenceContext": evidence, "evidenceSha256": sha256(evidence), "messages": messages,
               "promptSha256": sha256(json.dumps(messages, ensure_ascii=False, separators=(",", ":")))}
    invocation = {"kind": "model-invocation", "scope": "final-answer", "modelName": model,
                  "modelTier": "STANDARD", "temperature": 0.0, "maxOutputTokens": 1000}
    case = {"id": "revenue", "passed": True, **capture_answer(definition, answer),
            "events": [{"type": "answer", "content": answer}],
            "trace": {"steps": [{"attributes": context}, {"attributes": invocation}]}}
    return {"schema": "ordinary_execution_eval_v1", "dataset_sha256": json_hash([definition]),
            "sample_count": 1, "status": "PASS", "cases": [case]}


def completed_reviews(report, status="PASS"):
    reviews = review_template(report)
    for row in reviews["reviews"]:
        row["reviewer"] = "human-reviewer"
        row["reviewed_at"] = "2026-09-25T09:00:00+08:00"
        row["dimensions"] = {name: {"status": status, "reason": "人工逐项检查实际上下文与回答"} for name in DIMENSIONS}
    return reviews


def sample_analyst_report():
    report = sample_report()
    case = report["cases"][0]
    context = case["trace"]["steps"][0]["attributes"]
    before, draft, after = "😀 [E1] facts\n## Analyst\n", "## Analyst\n草稿😀\n\n", "RAG memory profile"
    text = before + draft + after
    context.update(context=text, contextSha256=sha256(text))
    context["messages"][1]["text"] = text
    context["messages"].append({"role": "user", "text": "current question"})
    context["promptSha256"] = sha256(json.dumps(context["messages"], ensure_ascii=False, separators=(",", ":")))
    context["analystSpan"] = {"schemaVersion": 1, "messageIndex": 1, "start": len(before),
                              "end": len(before + draft), "offsetUnit": "UNICODE_CODE_POINT",
                              "removedTextSha256": sha256(draft)}
    case["trace"]["steps"].append({"attributes": {"kind": "ordinary-evidence", "analystStatus": "COMPLETED"}})
    return report


class OrdinaryAnswerQualityTest(unittest.TestCase):
    def test_analyst_input_export_removes_only_bound_span_and_never_inherits_results(self):
        report = sample_analyst_report()
        case = report["cases"][0]
        context = case["trace"]["steps"][0]["attributes"]
        before, draft, after = "😀 [E1] facts\n## Analyst\n", "## Analyst\n草稿😀\n\n", "RAG memory profile"
        original = copy.deepcopy(report)
        result = analyst_inputs(report)
        pair = result["pairs"][0]
        self.assertEqual(result["status"], "INPUTS_READY")
        self.assertEqual(pair["baseline"]["messages"], context["messages"])
        expected = copy.deepcopy(context["messages"])
        expected[1]["text"] = before + after
        self.assertEqual(pair["without_analyst"]["messages"], expected)
        self.assertEqual(pair["without_analyst"]["contextSha256"], sha256(before + after))
        self.assertEqual(pair["evidenceSha256"], context["evidenceSha256"])
        self.assertEqual(pair["removedDraftSha256"], sha256(draft))
        self.assertNotIn("answer", pair)
        self.assertNotIn("model_usage", pair)
        self.assertNotIn("quality_review", pair)
        self.assertEqual(report, original)
        for field, value in (("start", -1), ("end", 99999), ("messageIndex", 2),
                             ("start", True), ("removedTextSha256", "changed"), ("schemaVersion", 2)):
            changed = copy.deepcopy(report)
            changed["cases"][0]["trace"]["steps"][0]["attributes"]["analystSpan"][field] = value
            self.assertEqual(analyst_inputs(changed)["status"], "NO_DATA")
        for status in ("TRUNCATED", "EMPTY", "FAILED", "CAPACITY_REJECTED"):
            case["trace"]["steps"][-1]["attributes"]["analystStatus"] = status
            self.assertEqual(analyst_inputs(report)["status"], "NO_DATA")

    def test_human_review_required_and_bound_to_actual_material(self):
        report = sample_report()
        template = review_template(report)
        self.assertEqual(template["reviews"][0]["answer_context"]["evidenceContext"], "[E1] 收入同比增长10%")
        self.assertEqual(apply_reviews(report, template)["answer_quality"], "NO_DATA")
        rated = apply_reviews(report, completed_reviews(report))
        self.assertEqual(rated["answer_quality"], "PASS")
        self.assertEqual(rated["quality_evaluation"]["rated_count"], 1)
        self.assertEqual(material(report["cases"][0])["model_usage"], {"usageSource": "NO_DATA"})

    def test_ordinary_requirements_are_visible_and_old_review_bindings_are_rejected(self):
        report = sample_report()
        reviews = completed_reviews(report)
        self.assertEqual(reviews["rubric"]["dimensions"], REVIEW_REQUIREMENTS)
        self.assertEqual(reviews["reviews"][0]["binding"]["rubricSha256"], reviews["rubric"]["sha256"])
        with patch.dict(REVIEW_REQUIREMENTS, {"unknowns": "Changed ordinary answer criterion"}):
            with self.assertRaisesRegex(ValueError, "rubric.*changed"):
                apply_reviews(report, reviews)
        reviews["reviews"][0]["binding"].pop("rubricSha256")
        result = apply_reviews(report, reviews)
        self.assertIn("REVIEW_BINDING_MISMATCH", result["cases"][0]["quality_review"]["errors"])
        self.assertEqual(compare_reports(result, result)["paired_count"], 0)

    def test_missing_dimension_does_not_pass_and_failure_takes_precedence(self):
        report = sample_report()
        reviews = completed_reviews(report)
        del reviews["reviews"][0]["dimensions"]["unknowns"]
        result = apply_reviews(report, reviews)
        self.assertEqual(result["answer_quality"], "NO_DATA")
        self.assertEqual(result["quality_evaluation"]["dimensions"]["unknowns"]["missing_count"], 1)
        reviews["reviews"][0]["dimensions"]["claim_support"]["status"] = "FAIL"
        self.assertEqual(apply_reviews(report, reviews)["quality_evaluation"]["status"], "FAIL")

    def test_changed_answer_context_or_prompt_cannot_reuse_review(self):
        report = sample_report()
        reviews = completed_reviews(report)
        for target in ("answer", "context", "messages"):
            changed = copy.deepcopy(report)
            if target == "answer":
                changed["cases"][0]["events"][0]["content"] = "伪造回答 [E1]"
            elif target == "context":
                changed["cases"][0]["trace"]["steps"][0]["attributes"]["context"] = "改变证据"
            else:
                changed["cases"][0]["trace"]["steps"][0]["attributes"]["messages"][0]["text"] = "新指令"
            self.assertEqual(apply_reviews(changed, reviews)["quality_evaluation"]["status"], "NO_DATA")
        updated = sample_report(answer="收入下降 [E1]")
        self.assertEqual(apply_reviews(updated, reviews)["quality_evaluation"]["status"], "FAIL")

    def test_incomplete_evidence_images_and_missing_model_are_no_data(self):
        report = sample_report()
        reviews = completed_reviews(report)
        for key, value in (("hasImages", True), ("evidenceCaptureComplete", False)):
            changed = copy.deepcopy(report)
            changed["cases"][0]["trace"]["steps"][0]["attributes"][key] = value
            self.assertEqual(apply_reviews(changed, reviews)["answer_quality"], "NO_DATA")
        report["cases"][0]["trace"]["steps"].pop()
        self.assertEqual(apply_reviews(report, reviews)["answer_quality"], "NO_DATA")

    def test_na_requires_reason_and_all_na_is_not_a_pass(self):
        report = sample_report()
        reviews = completed_reviews(report, "NOT_APPLICABLE")
        self.assertEqual(apply_reviews(report, reviews)["answer_quality"], "NO_DATA")
        reviews["reviews"][0]["dimensions"]["claim_support"]["status"] = "PASS"
        self.assertEqual(apply_reviews(report, reviews)["answer_quality"], "PASS")
        reviews["reviews"][0]["dimensions"]["unknowns"]["reason"] = ""
        self.assertEqual(apply_reviews(report, reviews)["answer_quality"], "NO_DATA")

    def test_failed_or_incomplete_execution_cannot_pass_combined_gate(self):
        report = sample_report()
        reviews = completed_reviews(report)
        report["status"] = "FAIL"
        self.assertEqual(apply_reviews(report, reviews)["quality_evaluation"]["status"], "FAIL")
        report["status"] = "PASS"
        report["sample_count"] = 2
        self.assertEqual(apply_reviews(report, reviews)["answer_quality"], "NO_DATA")
        report["sample_count"] = 1
        report["dataset_sha256"] = json_hash([report["cases"][0]["case_definition"], {"id": "omitted"}])
        with self.assertRaisesRegex(ValueError, "dataset_sha256"):
            apply_reviews(report, completed_reviews(report))

    def test_pairing_requires_same_case_and_evidence_and_keeps_model_attribution(self):
        old, new = sample_report(), sample_report(instruction="新的综合要求", model="model-b")
        a = apply_reviews(old, completed_reviews(old, "FAIL"))
        b = apply_reviews(new, completed_reviews(new))
        pair = compare_reports(a, b)
        self.assertEqual(pair["paired_count"], 1)
        self.assertEqual(pair["dimensions"]["claim_support"]["pass_rate_delta"], 1.0)
        self.assertEqual(pair["pairs"][0]["candidate_model_invocations"][0]["modelName"], "model-b")
        changed = sample_report(evidence="[E1] 不同的源证据")
        changed = apply_reviews(changed, completed_reviews(changed))
        self.assertEqual(compare_reports(a, changed)["unpaired"][0]["reason"], "CASE_OR_EVIDENCE_CHANGED")
        b["cases"][0]["quality_review"]["binding"]["answerSha256"] = "stale"
        self.assertEqual(compare_reports(a, b)["status"], "NO_DATA")

    def test_duplicate_wrong_dataset_and_invalid_review_provenance_are_rejected(self):
        report = sample_report()
        reviews = completed_reviews(report)
        reviews["reviews"].append(copy.deepcopy(reviews["reviews"][0]))
        with self.assertRaises(ValueError):
            apply_reviews(report, reviews)
        reviews = completed_reviews(report)
        reviews["dataset_sha256"] = "other"
        with self.assertRaises(ValueError):
            apply_reviews(report, reviews)
        reviews = completed_reviews(report)
        reviews["reviews"][0]["reviewed_at"] = "yesterday"
        self.assertEqual(apply_reviews(report, reviews)["answer_quality"], "NO_DATA")

    def test_route_strata_expose_opposite_effects_and_unpaired_coverage(self):
        def rated_case(key, route, status, evidence="same evidence"):
            report = sample_report(evidence=evidence)
            case = report["cases"][0]
            definition = {**case["case_definition"], "id": key, "route": route}
            case.update({"id": key, **capture_answer(definition, case["answer"])})
            report["dataset_sha256"] = json_hash([definition])
            return apply_reviews(report, completed_reviews(report, status))["cases"][0]

        before = {"cases": [rated_case("financial", "FUNDAMENTALS", "FAIL"),
                            rated_case("news", "NEWS", "PASS"),
                            rated_case("changed", "NEWS", "PASS"),
                            rated_case("absent", "MARKET", "PASS")]}
        after = {"cases": [rated_case("financial", "FUNDAMENTALS", "PASS"),
                           rated_case("news", "NEWS", "FAIL"),
                           rated_case("changed", "NEWS", "PASS", "different evidence")]}
        result = compare_reports(before, after)
        self.assertEqual(result["dimensions"]["claim_support"]["pass_rate_delta"], 0)
        self.assertEqual(result["pair_coverage"], 0.5)
        strata = result["route_strata"]
        self.assertEqual(strata["FUNDAMENTALS"]["dimensions"]["claim_support"]["pass_rate_delta"], 1)
        self.assertEqual(strata["NEWS"]["dimensions"]["claim_support"]["pass_rate_delta"], -1)
        self.assertEqual(strata["NEWS"]["pair_coverage"], 0.5)
        self.assertEqual(strata["NEWS"]["unpaired_reasons"], {"CASE_OR_EVIDENCE_CHANGED": 1})
        self.assertEqual(strata["MARKET"]["status"], "NO_DATA")
        self.assertIsNone(strata["MARKET"]["dimensions"]["claim_support"]["pass_rate_delta"])
        self.assertEqual(strata["MARKET"]["unpaired_reasons"], {"CASE_MISSING": 1})
        self.assertEqual(sum(row["case_count"] for row in strata.values()), result["case_count"])
        self.assertIsNone(compare_reports({"cases": []}, {"cases": []})["pair_coverage"])


if __name__ == "__main__":
    unittest.main()
