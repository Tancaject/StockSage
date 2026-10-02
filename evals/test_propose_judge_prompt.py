import copy
import json
from pathlib import Path
import tempfile
import unittest

from eval_common import json_hash, sha256
from propose_judge_prompt import prepare_proposal, propose
from run_evaluation_agent import PROTOCOL, TOOLS
from test_judge_calibration import diagnostic, pilot, set_status


def material():
    dataset = pilot()
    dataset.append({**copy.deepcopy(dataset[0]), "id": "VALIDATION_SENTINEL_ID", "group_id": "reserved-source",
                    "split": "VALIDATION", "question": "VALIDATION_SENTINEL_QUESTION",
                    "evidence": "VALIDATION_SENTINEL_EVIDENCE", "answer": "VALIDATION_SENTINEL_ANSWER"})
    baseline = diagnostic(dataset, repeats=1)
    baseline["status"] = "COMPLETED"
    baseline["judge"].update(config={"endpoint": "https://example.test/v1/chat/completions", "model": "judge",
        "temperature": 0, "enable_thinking": False, "timeout_seconds": 180, "max_tokens": 2400, "max_calls": 4},
        guidance="Read the frozen evidence and assess each dimension.", tools_sha256=json_hash(TOOLS))
    baseline["judge"]["prompt_sha256"] = sha256(PROTOCOL + "\n" + baseline["judge"]["guidance"])
    baseline["reviews"][1]["tool_trace"] = [{"name": "calculate", "arguments": {"operation": "percent_change", "left": "110", "right": "100"}, "output": {"result": "10"}}]
    set_status(baseline["reviews"][1], "numeric_period_correctness", "PASS")
    return dataset, baseline


class ProposeJudgePromptTest(unittest.TestCase):
    def test_one_dev_batch_no_validation_or_activation_and_bound_artifacts(self):
        dataset, baseline = material()
        config, body, batch = prepare_proposal(dataset, baseline)
        self.assertNotIn("VALIDATION_SENTINEL", json.dumps(body))
        self.assertEqual(batch["case_count"], 1)
        self.assertEqual(config, baseline["judge"]["config"])
        payload = json.loads(body["messages"][1]["content"])
        self.assertEqual(payload["dev_error_batch"][0]["expected_dimensions"], dataset[1]["expected_dimensions"])
        self.assertEqual(payload["fixed_max_calls"], baseline["judge"]["config"]["max_calls"])
        self.assertEqual(payload["dev_error_batch"][0]["baseline_reviews"][0]["tool_trace"], baseline["reviews"][1]["tool_trace"])
        self.assertEqual(payload["dev_error_batch"][0]["case_sha256"], json_hash(dataset[1]))
        self.assertEqual(payload["dev_preservation_examples"][0]["id"], "correct")
        self.assertEqual(len(payload["fixed_rubric"]["dimensions"]), 5)
        self.assertNotIn("read_evidence", json.dumps(payload["fixed_tools"]))
        calls = []
        def transport(config, body, key):
            calls.append(body)
            return {"model": "observed-judge", "usage": {"total_tokens": 25}, "choices": [{"finish_reason": "stop",
                    "message": {"role": "assistant", "content": json.dumps({"guidance": "Read once; compare periods and calculate relevant changes.",
                                                                                 "rationale": "Retain the rubric and correct judgments."})}}]}
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / "candidate"
            record = propose(dataset, baseline, output, "PRIVATE_CREDENTIAL_SENTINEL", transport)
            self.assertEqual(len(calls), 1)
            self.assertEqual(record["status"], "PROPOSED")
            self.assertEqual(record["schema"], "judge_prompt_proposal_v2")
            self.assertFalse(record["release_eligible"])
            self.assertEqual(record["candidate_prompt_sha256"], sha256(PROTOCOL + "\n" + (output / "candidate-prompt.txt").read_text(encoding="utf-8")))
            saved = (output / "proposal.json").read_text(encoding="utf-8")
            self.assertNotIn("PRIVATE_CREDENTIAL_SENTINEL", saved)
            self.assertNotIn("VALIDATION_SENTINEL", saved)

    def test_no_transport_when_clean_missing_attempts_or_wrong_split(self):
        dataset, baseline = material()
        variants = []
        clean = copy.deepcopy(baseline)
        set_status(clean["reviews"][1], "numeric_period_correctness", "FAIL")
        variants.append(clean)
        missing = copy.deepcopy(baseline)
        missing["reviews"].pop()
        variants.append(missing)
        wrong_split = copy.deepcopy(baseline)
        wrong_split["split"] = "VALIDATION"
        variants.append(wrong_split)
        calls = []
        with tempfile.TemporaryDirectory() as temp:
            for index, report in enumerate(variants):
                output = Path(temp) / str(index)
                with self.assertRaises(ValueError):
                    propose(dataset, report, output, "key", lambda *args: calls.append(args))
                self.assertFalse(output.exists())
        self.assertFalse(calls)

    def test_bad_response_kept_without_candidate_or_retry(self):
        dataset, baseline = material()
        calls = []
        def transport(*args):
            calls.append(args)
            return {"model": "judge", "choices": [{"finish_reason": "length", "message": {"role": "assistant", "content": "truncated"}}]}
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / "candidate"
            with self.assertRaises(ValueError):
                propose(dataset, baseline, output, "key", transport)
            self.assertEqual(len(calls), 1)
            self.assertFalse((output / "candidate-prompt.txt").exists())
            saved = json.loads((output / "proposal.json").read_text(encoding="utf-8"))
            self.assertEqual(saved["status"], "FAILED")
            self.assertEqual(saved["response"]["choices"][0]["finish_reason"], "length")


if __name__ == "__main__":
    unittest.main()
