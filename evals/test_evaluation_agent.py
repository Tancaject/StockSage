import copy
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from ordinary_answer_quality import DIMENSIONS, apply_reviews
from run_evaluation_agent import judge_case, main, pilot_inputs, run_tool, saved_inputs, validate_verdict
from test_ordinary_answer_quality import sample_report


class EvaluationAgentTest(unittest.TestCase):
    def setUp(self):
        self.item = saved_inputs(sample_report())[0]
        self.config = {"model": "judge-test", "max_tokens": 1000, "max_calls": 2}
        self.verdict = {"dimensions": {name: {"status": "PASS", "reason": "来源支持",
            "answer_quotes": [self.item["answer"]], "evidence_quotes": [self.item["evidence"]]} for name in DIMENSIONS}}

    def test_actual_tool_loop_keeps_evidence_model_usage_and_diagnostic_boundary(self):
        bodies = []
        def transport(config, body, key):
            bodies.append(copy.deepcopy(body))
            message = {"role": "assistant", "content": json.dumps(self.verdict)}
            if len(bodies) == 1:
                message = {"role": "assistant", "content": None, "reasoning_content": "先计算同比变化，再核对来源。", "tool_calls": [
                    {"id": "calc", "type": "function", "function": {"name": "calculate", "arguments": json.dumps(
                        {"operation": "percent_change", "left": "110", "right": "100"})}}]}
            return {"model": "judge-test-observed", "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15},
                    "choices": [{"finish_reason": "tool_calls" if len(bodies) == 1 else "stop", "message": message}]}
        result = judge_case(self.item, self.config, "核查证据", "private-key", 1, transport)
        self.assertEqual(result["status"], "PASS")
        self.assertEqual(result["technical_status"], "VALID")
        self.assertEqual(result["binding"], self.item["binding"])
        self.assertEqual(result["usage"]["total_tokens"], 30)
        self.assertEqual(result["calls"][1]["observed_model"], "judge-test-observed")
        self.assertEqual(result["tool_trace"][0]["output"]["result"], "10.0")
        self.assertNotIn("private-key", json.dumps(result))
        self.assertEqual(bodies[1]["messages"][3]["role"], "tool")
        self.assertEqual(bodies[1]["messages"][2]["reasoning_content"], "先计算同比变化，再核对来源。")
        self.assertEqual(json.loads(bodies[0]["messages"][1]["content"])["evidence"], self.item["evidence"])
        self.assertEqual([tool["function"]["name"] for tool in bodies[0]["tools"]], ["calculate"])
        self.assertNotIn("tools", bodies[1])
        self.assertEqual(bodies[1]["response_format"], {"type": "json_object"})
        self.assertEqual(bodies[0]["response_format"], {"type": "json_object"})
        with self.assertRaises(ValueError):
            apply_reviews(sample_report(), {"schema": "ordinary_judge_diagnostic_v2", "reviews": [result]})

    def test_cli_thinking_options_match_actual_request_and_report(self):
        response = {"model": "judge-test", "choices": [{"finish_reason": "stop",
                    "message": {"role": "assistant", "content": json.dumps(self.verdict)}}]}
        with tempfile.TemporaryDirectory() as temp:
            source = Path(temp) / "input.json"
            source.write_text(json.dumps(sample_report()), encoding="utf-8")
            for enabled, budget in ((False, None), (False, 4096), (True, 4096), (True, 0)):
                with self.subTest(enabled=enabled, budget=budget):
                    output = Path(temp) / f"review-{enabled}-{budget}.json"
                    argv = ["run_evaluation_agent", "--input", str(source), "--output", str(output),
                            "--model", "judge-test", "--repeats", "1", "--workers", "1", "--max-calls", "1"]
                    if enabled:
                        argv.append("--enable-thinking")
                    if budget is not None:
                        argv.extend(["--thinking-budget", str(budget)])
                    bodies = []
                    def capture(request, timeout):
                        bodies.append(json.loads(request.data))
                        return io.StringIO(json.dumps(response))
                    with patch("sys.argv", argv), patch("run_evaluation_agent.resolve_api_key", return_value="test-key"), \
                            patch("run_evaluation_agent.urllib.request.build_opener") as opener, patch("builtins.print"):
                        opener.return_value.open.side_effect = capture
                        self.assertEqual(main(), 0)
                    config = json.loads(output.read_text(encoding="utf-8"))["judge"]["config"]
                    self.assertEqual(len(bodies), 1)
                    self.assertIs(bodies[0]["enable_thinking"], enabled)
                    self.assertIs(config["enable_thinking"], enabled)
                    if budget is None:
                        self.assertNotIn("thinking_budget", bodies[0])
                        self.assertNotIn("thinking_budget", config)
                    else:
                        self.assertEqual(bodies[0]["thinking_budget"], budget)
                        self.assertEqual(config["thinking_budget"], budget)
            with patch("sys.argv", ["run_evaluation_agent", "--input", str(source), "--output", str(Path(temp) / "invalid.json"),
                                    "--thinking-budget", "-1"]), patch("sys.stderr", new_callable=io.StringIO), \
                    patch("run_evaluation_agent.resolve_api_key") as key_resolver:
                with self.assertRaises(SystemExit) as error:
                    main()
                self.assertEqual(error.exception.code, 2)
                key_resolver.assert_not_called()

    def test_bindings_quotes_tools_and_missing_evidence_cannot_silently_pass(self):
        report = sample_report()
        report["cases"][0]["answer"] = "changed"
        called = []
        row = judge_case(saved_inputs(report)[0], self.config, "", "", 1, lambda *args: called.append(args))
        self.assertEqual(row["status"], "NO_DATA")
        self.assertEqual(row["technical_status"], "INVALID")
        self.assertTrue(all(d["errors"] for d in row["dimensions"].values()))
        self.assertFalse(called)
        self.verdict["dimensions"]["claim_support"]["evidence_quotes"] = ["不存在的来源"]
        checked = validate_verdict(self.item, self.verdict)
        self.assertEqual(checked["technical_status"], "PARTIAL")
        self.assertFalse(checked["errors"])
        self.assertEqual(sum(not d["errors"] for d in checked["dimensions"].values()), 4)
        self.assertEqual(checked["dimensions"]["claim_support"]["reported_status"], "PASS")
        self.verdict["dimensions"]["unknowns"]["reported_status"] = "FAIL"
        self.assertEqual(validate_verdict(self.item, self.verdict)["dimensions"]["unknowns"]["status"], "PASS")
        with self.assertRaises(ValueError):
            run_tool("shell", {"command": "dir"}, self.item)
        with self.assertRaises(ValueError):
            run_tool("calculate", {"operation": "divide", "left": "NaN", "right": "2"}, self.item)

    def test_pilot_labels_never_enter_judge_material_and_source_groups_cannot_leak(self):
        case = {"id": "a", "group_id": "report", "split": "DEV", "question": "问题", "answer": "答案", "evidence": "来源",
                "expected_dimensions": "SECRET_LABEL", "rationale": "SECRET_REASON"}
        inputs = pilot_inputs([case], "DEV")
        self.assertNotIn("SECRET", json.dumps(inputs))
        with self.assertRaises(ValueError):
            pilot_inputs([case, {**case, "id": "b", "split": "VALIDATION"}], "DEV")

    def test_provider_and_truncation_failures_remain_visible(self):
        def unavailable(*args):
            raise TimeoutError("provider timeout")
        row = judge_case(self.item, self.config, "", "", 1, unavailable)
        self.assertEqual(row["status"], "NO_DATA")
        self.assertEqual(row["technical_status"], "INVALID")
        self.assertEqual(len(row["calls"]), 1)
        self.assertIsNone(row["usage"]["total_tokens"])
        row = judge_case(self.item, self.config, "", "", 1, lambda *args: {
            "model": "judge-test", "choices": [{"finish_reason": "length"}]})
        self.assertEqual(row["status"], "NO_DATA")
        self.assertTrue(row["errors"])

    def test_na_and_missing_source_are_semantic_states_not_format_failures(self):
        self.verdict["dimensions"]["counterevidence"] = {
            "status": "NOT_APPLICABLE", "reason": "证据中没有与收入结论相反的指标", "answer_quotes": [], "evidence_quotes": []}
        self.assertEqual(validate_verdict(self.item, self.verdict)["technical_status"], "VALID")
        empty = {**self.item, "evidence": ""}
        for name in DIMENSIONS[:-1]:
            self.verdict["dimensions"][name] = {"status": "NO_DATA", "reason": "未捕获来源", "answer_quotes": [], "evidence_quotes": []}
        self.verdict["dimensions"]["task_completion"]["evidence_quotes"] = []
        checked = validate_verdict(empty, self.verdict)
        self.assertEqual(checked["technical_status"], "VALID")
        self.assertEqual(checked["dimensions"]["task_completion"]["status"], "PASS")
        self.assertEqual(checked["status"], "NO_DATA")

    def test_bound_empty_answer_is_a_completion_failure_not_a_capture_failure(self):
        item = saved_inputs(sample_report(answer=""))[0]
        self.assertFalse(item["errors"])
        verdict = {"dimensions": {name: {"status": "NO_DATA", "reason": "没有回答可核查", "answer_quotes": [], "evidence_quotes": []} for name in DIMENSIONS}}
        verdict["dimensions"]["task_completion"].update(status="FAIL", reason="没有回应问题")
        checked = validate_verdict(item, verdict)
        self.assertEqual(checked["technical_status"], "VALID")
        self.assertEqual(checked["status"], "FAIL")
        verdict["dimensions"]["task_completion"]["status"] = "PASS"
        self.assertEqual(validate_verdict(item, verdict)["technical_status"], "PARTIAL")


if __name__ == "__main__":
    unittest.main()
