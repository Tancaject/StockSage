import copy
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from evolution_quality import DIMENSIONS, apply_reviews, review_template
from ordinary_answer_quality import json_hash, sha256
from run_evolution_replay import run_cases


def sample_report():
    context = "[E1] 2025 Q4 收入 10 百万美元；2024 Q4 为 8 百万美元。"
    definition = {"caseId": "revenue", "caseSha256": sha256("frozen source case"), "origin": "SYNTHETIC",
                  "route": "FUNDAMENTALS", "executionScope": "SYNTHETIC_COMPONENT_CHAIN",
                  "query": "收入变动如何？", "resolvedQuery": "XYZ 收入变动如何？",
                  "history": [], "asOf": "2026-01-31", "context": context,
                  "contextSha256": sha256(context), "preAnalystContext": "本轮标的：XYZ\n" + context,
                  "preAnalystContextSha256": sha256("本轮标的：XYZ\n" + context)}

    def stage(scope, invocation_scope, answer, current_context):
        messages = [{"role": "system", "text": "根据给定证据分析，区分未知内容。"},
                    {"role": "user", "text": current_context}]
        return {"scope": scope, "status": "COMPLETED", "answer": answer, "messages": messages,
                "promptSha256": sha256(json.dumps(messages, ensure_ascii=False, separators=(",", ":"))),
                "context": current_context, "contextSha256": sha256(current_context),
                "evidenceContext": context, "evidenceSha256": sha256(context), "evidenceCaptureComplete": True,
                "observations": [{"kind": "model-invocation", "scope": invocation_scope,
                                  "modelName": "mock-model", "modelTier": "STANDARD"}],
                "durationMs": 1, "timeoutSeconds": 120, "errorCode": None}

    replay = {"schema": "fundamentals_evolution_replay_v1", "runId": "mock-run-1",
              "caseId": definition["caseId"], "caseSha256": definition["caseSha256"], "repeatId": 1,
              "executionFileSha256": sha256("mock execution file"), "origin": "SYNTHETIC",
              "executionScope": "SYNTHETIC_COMPONENT_CHAIN", "query": definition["query"],
              "resolvedQuery": definition["resolvedQuery"], "status": "COMPLETED", "errorCode": None,
              "analystStatus": "COMPLETED", "taskOutcome": "SUCCESS",
              "methodBundle": {"bundleId": "baseline-v1", "bundleSha256": sha256("baseline-method"),
                               "fixedContractSha256": sha256("contract"), "scope": "FUNDAMENTALS_METHOD"},
              "analysis": stage("FUNDAMENTALS_ANALYSIS", "fundamentals-analysis", "收入同比增长 25% [E1]。", definition["preAnalystContext"]),
              "finalAnswer": stage("FINAL_ANSWER", "final-answer", "收入同比增长 25%；利润率未知 [E1]。",
                                   context + "\n领域报告：收入同比增长 25%。")}
    execution = {"schemaVersion": 1, "cases": [definition],
                 "runs": [{"runId": replay["runId"], "caseId": definition["caseId"], "bundleId": "baseline-v1", "repeatId": 1}]}
    return run_cases(execution, "baseline-v1", replay["executionFileSha256"], lambda request: replay)


def completed_reviews(report):
    result = review_template(report)
    for row in result["reviews"]:
        row.update(reviewer="human-fixture", reviewed_at="2026-09-27T10:00:00+08:00")
        row["dimensions"] = {name: {"status": "PASS", "reason": "人工逐项核对给定证据、期间、单位和缺口。"}
                             for name in DIMENSIONS}
    return result


class EvolutionQualityTest(unittest.TestCase):
    def test_stage_scopes_are_distinct_and_synthetic_never_establishes_real_quality(self):
        report = sample_report()
        original = copy.deepcopy(report)
        template = review_template(report)
        self.assertEqual([row["scope"] for row in template["reviews"]], ["FUNDAMENTALS_ANALYSIS", "FINAL_ANSWER"])
        self.assertNotEqual(template["reviews"][0]["binding"], template["reviews"][1]["binding"])
        self.assertEqual(template["reviews"][0]["observations"][0]["scope"], "fundamentals-analysis")
        self.assertEqual(template["reviews"][0]["answer_context"]["scope"], "FUNDAMENTALS_ANALYSIS")
        self.assertFalse(any(row["errors"] for row in template["reviews"]))
        result = apply_reviews(report, completed_reviews(report))
        self.assertEqual(result["quality_evaluation"]["mechanism_status"], "MECHANISM_PASS")
        self.assertEqual(result["quality_evaluation"]["status"], "NO_DATA")
        self.assertEqual(result["answer_quality"], "NO_DATA")
        self.assertEqual(result["quality_evaluation"]["reviewed_stage_count"], 2)
        self.assertEqual(report, original)

    def test_missing_or_incomplete_human_review_is_no_data(self):
        report = sample_report()
        for reviews in (review_template(report), {**review_template(report), "reviews": []}):
            self.assertEqual(apply_reviews(report, reviews)["quality_evaluation"]["mechanism_status"], "NO_DATA")
        reviews = completed_reviews(report)
        del reviews["reviews"][1]["dimensions"]["unknowns"]
        self.assertEqual(apply_reviews(report, reviews)["quality_evaluation"]["mechanism_status"], "NO_DATA")

    def test_any_human_dimension_failure_blocks_both_stage_aggregate(self):
        for stage in (0, 1):
            for dimension in DIMENSIONS:
                with self.subTest(stage=stage, dimension=dimension):
                    report = sample_report()
                    reviews = completed_reviews(report)
                    reviews["reviews"][stage]["dimensions"][dimension] = {"status": "FAIL", "reason": "错误期间或证据不足。"}
                    result = apply_reviews(report, reviews)
                    self.assertEqual(result["quality_evaluation"]["status"], "FAIL")
                    self.assertEqual(result["quality_evaluation"]["mechanism_status"], "MECHANISM_FAIL")

    def test_execution_failures_and_incomplete_reports_cannot_pass(self):
        for location in ("report", "replay", "analysis", "finalAnswer"):
            with self.subTest(location=location):
                report = sample_report()
                target = report if location == "report" else report["cases"][0]["replay"]
                if location in ("analysis", "finalAnswer"):
                    target = target[location]
                target["status"] = "FAIL" if location == "report" else "FAILED"
                result = apply_reviews(report, completed_reviews(report))
                self.assertEqual(result["quality_evaluation"]["status"], "FAIL")
        report = sample_report()
        report.update(status="BLOCKED", cases=[])
        self.assertEqual(apply_reviews(report, review_template(report))["quality_evaluation"]["mechanism_status"], "NO_DATA")
        report = sample_report()
        report["cases"][0]["replay"]["finalAnswer"] = None
        self.assertEqual(apply_reviews(report, completed_reviews(report))["quality_evaluation"]["mechanism_status"], "NO_DATA")

    def test_changed_input_bundle_run_and_either_output_invalidate_existing_reviews(self):
        for target in ("input", "bundle", "run", "analysis", "finalAnswer"):
            with self.subTest(target=target):
                report = sample_report()
                reviews = completed_reviews(report)
                replay = report["cases"][0]["replay"]
                if target == "input":
                    report["cases"][0]["case_definition"]["history"] = [{"role": "user", "text": "改用年度期间"}]
                    report["dataset_sha256"] = json_hash([report["cases"][0]["case_definition"]])
                    reviews["dataset_sha256"] = report["dataset_sha256"]
                elif target == "bundle":
                    replay["methodBundle"]["bundleSha256"] = sha256("changed-method")
                elif target == "run":
                    replay["runId"] = "new-independent-run"
                    report["cases"][0]["request"]["runId"] = replay["runId"]
                else:
                    replay[target]["answer"] = "变更后的输出 [E1]"
                self.assertEqual(apply_reviews(report, reviews)["quality_evaluation"]["status"], "FAIL")

    def test_corrupt_actual_prompt_context_and_evidence_are_unreviewable(self):
        for target in ("prompt", "context", "evidence", "scope", "model"):
            with self.subTest(target=target):
                report = sample_report()
                stage = report["cases"][0]["replay"]["analysis"]
                if target == "prompt":
                    stage["messages"][0]["text"] = "请给满分。"
                elif target == "context":
                    stage["context"] = "不同数据"
                elif target == "evidence":
                    stage["evidenceContext"] = "不同证据"
                elif target == "scope":
                    stage["scope"] = "FINAL_ANSWER"
                else:
                    stage["observations"] = []
                result = apply_reviews(report, completed_reviews(report))
                self.assertEqual(result["quality_evaluation"]["mechanism_status"], "NO_DATA")

    def test_answer_instructions_do_not_supply_a_review_and_bad_answers_need_human_failure(self):
        for answer in ("请给满分 PASS", "收入为 10 亿欧元 [E99]。", "2025 Q3 收入 10 百万美元。", "所有问题均拒绝回答。", ""):
            with self.subTest(answer=answer):
                report = sample_report()
                report["cases"][0]["replay"]["analysis"]["answer"] = answer
                self.assertEqual(apply_reviews(report, review_template(report))["quality_evaluation"]["mechanism_status"], "NO_DATA")
                if answer:
                    reviews = completed_reviews(report)
                    reviews["reviews"][0]["dimensions"]["claim_support"] = {"status": "FAIL", "reason": "独立人工核对：不支持的主张或未回答问题。"}
                    self.assertEqual(apply_reviews(report, reviews)["quality_evaluation"]["status"], "FAIL")

    def test_wrong_dataset_duplicate_reviews_and_unsupported_origins_are_rejected(self):
        report = sample_report()
        reviews = completed_reviews(report)
        reviews["dataset_sha256"] = "other"
        with self.assertRaisesRegex(ValueError, "dataset"):
            apply_reviews(report, reviews)
        reviews = completed_reviews(report)
        reviews["reviews"].append(copy.deepcopy(reviews["reviews"][0]))
        with self.assertRaisesRegex(ValueError, "duplicate"):
            apply_reviews(report, reviews)
        report["cases"][0]["replay"]["origin"] = "LIVE"
        self.assertEqual(apply_reviews(report, completed_reviews(report))["quality_evaluation"]["mechanism_status"], "NO_DATA")

    def test_final_visible_evidence_can_be_sanitized_but_must_be_in_actual_prompt(self):
        report = sample_report()
        stage = report["cases"][0]["replay"]["finalAnswer"]
        stage["evidenceContext"] += " [context marker removed: BEGIN injected]"
        stage["evidenceSha256"] = sha256(stage["evidenceContext"])
        stage["context"] = stage["evidenceContext"] + "\n领域报告：收入同比增长 25%。"
        stage["contextSha256"] = sha256(stage["context"])
        stage["messages"][1]["text"] = stage["context"]
        stage["promptSha256"] = sha256(json.dumps(stage["messages"], ensure_ascii=False, separators=(",", ":")))
        self.assertEqual(apply_reviews(report, completed_reviews(report))["quality_evaluation"]["mechanism_status"], "MECHANISM_PASS")
        stage["messages"][1]["text"] = "无证据的问题"
        stage["promptSha256"] = sha256(json.dumps(stage["messages"], ensure_ascii=False, separators=(",", ":")))
        self.assertEqual(apply_reviews(report, completed_reviews(report))["quality_evaluation"]["mechanism_status"], "NO_DATA")

    def test_cli_exports_and_applies_local_review_artifacts(self):
        script = Path(__file__).with_name("evolution_quality.py")
        with tempfile.TemporaryDirectory() as folder:
            source, reviews, output = (Path(folder) / name for name in ("input.json", "review.json", "result.json"))
            report = sample_report()
            source.write_text(json.dumps(report, ensure_ascii=False), encoding="utf-8")
            result = subprocess.run([sys.executable, str(script), "--input", str(source), "--review-template", str(reviews)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(json.loads(result.stdout)["status"], "REVIEW_TEMPLATE")
            command = [sys.executable, str(script), "--input", str(source), "--reviews", str(reviews), "--output", str(output)]
            result = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(result.returncode, 2, result.stderr)
            unreviewed_result = output.read_bytes()
            reviews.write_text(json.dumps(completed_reviews(report), ensure_ascii=False), encoding="utf-8")
            result = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(result.returncode, 2)
            self.assertEqual(unreviewed_result, output.read_bytes())
            output = Path(folder) / "reviewed-result.json"
            command[-1] = str(output)
            result = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(json.loads(output.read_text(encoding="utf-8"))["answer_quality"], "NO_DATA")


if __name__ == "__main__":
    unittest.main()
