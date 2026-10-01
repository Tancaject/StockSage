"""Replay frozen analyst input pairs through the backend's ordinary no-tool answer path.

Uses the current deployment, not the source run's historical model configuration.
Writes two independently reviewable final-answer reports; never retries model calls.
"""
from __future__ import annotations

import argparse
import copy
import json
import os
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

from eval_common import capture_answer, json_hash, sha256
from ordinary_answer_quality import material


def prompt_hash(messages: list[dict]) -> str:
    return sha256(json.dumps(messages, ensure_ascii=False, separators=(",", ":")))


def validate_pair(pair: dict) -> None:
    definition, binding = pair["case_definition"], pair["source_binding"]
    baseline, candidate, span = pair["baseline"], pair["without_analyst"], pair["analystSpan"]
    if (not pair.get("id") or pair["id"] != definition.get("id") or pair["id"] != binding.get("id")
            or json_hash(definition) != binding.get("caseSha256")
            or sha256(pair["evidenceContext"]) != pair["evidenceSha256"]
            or pair["evidenceSha256"] != binding.get("evidenceSha256")
            or json_hash(pair["source_model_invocations"]) != binding.get("invocationSha256")):
        raise ValueError("Source binding changed: " + str(pair.get("id")))
    for branch in (baseline, candidate):
        rows = branch["messages"]
        if (not rows or any(set(row) != {"role", "text"} or row["role"] not in {"system", "user", "assistant"}
                            or not isinstance(row["text"], str) for row in rows)
                or rows[-1]["role"] != "user"
                or prompt_hash(rows) != branch["promptSha256"]
                or sha256(branch["context"]) != branch["contextSha256"]):
            raise ValueError("Replay prompt binding changed: " + pair["id"])
    index, start, end = (span.get(key) for key in ("messageIndex", "start", "end"))
    text = baseline["context"]
    if (span.get("schemaVersion") != 1 or span.get("offsetUnit") != "UNICODE_CODE_POINT"
            or any(type(value) is not int for value in (index, start, end))
            or not 0 <= index < len(baseline["messages"])
            or baseline["messages"][index] != {"role": "user", "text": text}
            or not 0 <= start < end <= len(text)
            or sha256(text[start:end]) != span.get("removedTextSha256")
            or sha256(text[start:end]) != pair["removedDraftSha256"]
            or baseline["promptSha256"] != binding.get("promptSha256")
            or baseline["contextSha256"] != binding.get("contextSha256")):
        raise ValueError("Analyst span binding changed: " + pair["id"])
    expected = copy.deepcopy(baseline["messages"])
    expected[index]["text"] = text[:start] + text[end:]
    if candidate["messages"] != expected or candidate["context"] != expected[index]["text"]:
        raise ValueError("Candidate changes more than the analyst draft: " + pair["id"])
    if binding.get("modelTier") not in {"FAST", "STANDARD", "STRONG"}:
        raise ValueError("Unsupported source model tier: " + pair["id"])


def replay_case(pair: dict, variant: str, response: dict) -> dict:
    branch = pair[variant]
    context = {"kind": "answer-context", "schemaVersion": 1, "scope": "ordinary-final-answer",
               "promptFingerprintScope": "TEXT_ONLY", "hasImages": False, "evidenceCaptureComplete": True,
               **branch, "evidenceContext": pair["evidenceContext"], "evidenceSha256": pair["evidenceSha256"]}
    observations = response.get("observations", [])
    answer = response.get("answer", "")
    checks = {"response_schema": response.get("schema") == "ordinary_answer_replay_v1",
              "replay_identity": bool(response.get("replayId")),
              "prompt_binding": response.get("promptSha256") == branch["promptSha256"],
              "generation_completed": response.get("status") == "COMPLETED" and bool(answer.strip())}
    case = {"id": pair["id"], "variant": variant, "checks": checks,
            **capture_answer(pair["case_definition"], answer),
            "events": [{"type": "answer", "content": answer}],
            "trace_origin": "REPLAY_RESPONSE_NOT_PERSISTED_TRACE",
            "trace": {"steps": [{"attributes": context}] + [{"attributes": row} for row in observations]},
            "replay": response, "source_binding": pair["source_binding"]}
    checks["answer_attribution"] = not material(case)["errors"]
    case["passed"] = all(checks.values())
    return case


def run_pairs(inputs: dict, invoke) -> dict[str, dict]:
    if inputs.get("schema") != "ordinary_analyst_inputs_v1":
        raise ValueError("Expected ordinary_analyst_inputs_v1")
    pairs = inputs["pairs"]
    if len({pair["id"] for pair in pairs}) != len(pairs):
        raise ValueError("Duplicate replay case ids")
    for pair in pairs:
        validate_pair(pair)  # Validate the entire artifact before spending any model call.
    reports = {variant: {"schema": "ordinary_answer_replay_eval_v1", "evidence_kind": "LIVE_REPLAY",
                        "execution_scope": "FINAL_ANSWER_ONLY", "variant": variant,
                        "configuration_scope": "OBSERVED_FINAL_ANSWER_REQUEST",
                        "generated_at": datetime.now(timezone.utc).isoformat(),
                        "dataset_sha256": json_hash([pair["case_definition"] for pair in pairs]),
                        "sample_count": len(pairs), "answer_quality": "NO_DATA", "cases": [],
                        "source_unavailable": inputs.get("unavailable", [])}
               for variant in ("baseline", "without_analyst")}
    for ordinal, pair in enumerate(pairs):
        order = ("baseline", "without_analyst") if ordinal % 2 == 0 else ("without_analyst", "baseline")
        completed = {}
        for variant in order:
            try:
                response = invoke({"messages": pair[variant]["messages"],
                                   "modelTier": pair["source_binding"]["modelTier"]})
                case = replay_case(pair, variant, response)
            except (OSError, ValueError, KeyError, TypeError) as error:
                # Do not retry: a lost HTTP response does not prove the provider did not run.
                for report in reports.values():
                    report["blocked"] = {"case_id": pair["id"], "variant": variant,
                                         "error_type": type(error).__name__}
                    if isinstance(error, urllib.error.HTTPError):
                        report["blocked"]["http_status"] = error.code
                break
            case["execution_order"] = list(order)
            reports[variant]["cases"].append(case)
            completed[variant] = case
            if not case["passed"]:
                break
        if len(completed) != 2 or not all(case["passed"] for case in completed.values()):
            break
        configurations = [material(completed[variant])["model_invocations"] for variant in order]
        matched = configurations[0] == configurations[1]
        for case in completed.values():
            case["checks"]["paired_request_configuration"] = matched
            case["passed"] = case["passed"] and matched
        if not matched:
            break
    for report in reports.values():
        report["completed_count"] = len(report["cases"])
        report["status"] = ("FAIL" if any(not case["passed"] for case in report["cases"]) else
                            "BLOCKED" if len(report["cases"]) != len(pairs) or report.get("blocked") else
                            "PASS" if pairs else "NO_DATA")
    return reports


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--endpoint", default="http://localhost:8080/api/eval/agent/answer")
    parser.add_argument("--timeout-seconds", type=int, default=180)
    args = parser.parse_args()
    token = os.environ.get("STOCKSAGE_ADMIN_TOKEN", "")
    if not token or args.timeout_seconds < 1:
        parser.error("Configure STOCKSAGE_ADMIN_TOKEN and a positive HTTP timeout")

    def invoke(body):
        request = urllib.request.Request(args.endpoint, data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
                                         headers={"Content-Type": "application/json",
                                                  os.environ.get("STOCKSAGE_ADMIN_HEADER_NAME", "X-StockSage-Admin-Token"): token},
                                         method="POST")
        with urllib.request.urlopen(request, timeout=args.timeout_seconds) as response:
            return json.load(response)

    try:
        reports = run_pairs(json.loads(args.input.read_text(encoding="utf-8")), invoke)
        args.output_dir.mkdir(parents=True, exist_ok=True)
        for variant, report in reports.items():
            (args.output_dir / (variant + ".json")).write_text(
                json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(2, str(error) + "\n")
    print(json.dumps({variant: report["status"] for variant, report in reports.items()}))
    return 0 if all(report["status"] == "PASS" for report in reports.values()) else 2


if __name__ == "__main__":
    raise SystemExit(main())
