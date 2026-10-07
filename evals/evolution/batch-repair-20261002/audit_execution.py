"""Reuse the batch execution audit and compare the six thinking-budget repair runs."""
from __future__ import annotations

import argparse
import importlib.util
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
BASELINE = HERE.parent / "batch-learning-20261002"
source = BASELINE / "campaign/audit_execution.py"
spec = importlib.util.spec_from_file_location("batch_repair_execution_audit", source)
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)
base.HERE = HERE
base.BUILD = base.REPO / "stocksage-backend/target/evolution-build-batch-repair-20261002"
original_stage_audit = base.audit_stage


def thinking_options_match(options: dict, settings: dict) -> bool:
    return (options.get("enableThinking") is settings["enableThinking"]
            and type(options.get("thinkingBudget")) is int
            and options["thinkingBudget"] == settings["thinkingBudget"])


def audit_stage(case: dict, name: str, settings: dict) -> dict:
    row = original_stage_audit(case, name, settings)
    if row["executionStatus"] == "NOT_EXECUTED":
        return row
    options = [item.get("clientDefaults", {}) if name == "analysis" else item for item in row["invocations"]]
    row["thinkingOptions"] = [{key: option.get(key) for key in ("enableThinking", "thinkingBudget")} for option in options]
    row["thinkingOptionsVerified"] = len(options) == 1 and thinking_options_match(options[0], settings)
    if not row["thinkingOptionsVerified"]:
        row["errors"].append("THINKING_OPTIONS_DIFFER_FROM_PLAN")
        row["status"] = "FAIL"
    return row


base.audit_stage = audit_stage


def audit() -> dict:
    result = base.audit("repair-", "DEVELOPMENT", "baseline-v1")
    settings = base.read(HERE / "plan.json")["businessSettings"]
    previous_path = BASELINE / "campaign/baseline-execution-audit.json"
    previous = base.read(previous_path)
    baseline_cases_path = BASELINE / "cases.jsonl"
    baseline_case_check = next(check for check in previous["frozenFileChecks"]
                               if (base.REPO / check["path"]).resolve() == baseline_cases_path.resolve())
    if base.digest(baseline_cases_path) != baseline_case_check["expectedSha256"]:
        result["errors"].append("BASELINE_SOURCE_CASES_CHANGED")
    old_by_case = {row["caseId"]: row for row in previous["cases"] if "supersededBy" not in row}
    old_inputs = {row["caseId"]: base.execution_payload(row) for row in base.load_jsonl(BASELINE / "cases.jsonl")}
    new_inputs = {row["caseId"]: base.execution_payload(row) for row in base.load_jsonl(HERE / "cases.jsonl")}
    comparisons, controls = [], []
    for row in result["cases"]:
        runtime_path = HERE / ("raw-" + row["shard"]) / "runtime.json"
        if runtime_path.exists():
            runtime = base.read(runtime_path)
            defaults = runtime.get("clients", {}).get("fundamentals", {})
            row["runtimeThinkingOptions"] = {key: defaults.get(key) for key in ("enableThinking", "thinkingBudget")}
            if not thinking_options_match(defaults, settings):
                row["errors"].append("RUNTIME_THINKING_OPTIONS_DIFFER_FROM_PLAN")
        old = old_by_case[row["caseId"]]
        old_response_hash = base.digest(base.REPO / old["responsePath"])
        if old_response_hash != old["responseSha256"]:
            row["errors"].append("BASELINE_RESPONSE_CHANGED")
        same_input = new_inputs[row["caseId"]] == old_inputs[row["caseId"]]
        if not same_input:
            row["errors"].append("SOURCE_INPUT_DIFFERS_FROM_BASELINE")
        if row["errors"]:
            row["status"] = "FAIL"
        old_replay = base.read(base.REPO / old["responsePath"])
        new_replay = base.read(base.REPO / row["responsePath"]) if row.get("responsePath") else {}
        comparison = {"caseId": row["caseId"], "baselineRunId": old["runId"], "repairRunId": row["runId"],
                      "baselineResponseSha256": old_response_hash,
                      "repairResponseSha256": row.get("responseSha256"), "sourceCaseMatchesBaseline": same_input,
                      "group": "FORMER_TIMEOUT" if old["stages"]["analysis"]["errorCode"] == "TIMEOUT" else "SUCCESSFUL_CONTROL",
                      "baselineExecutionStatus": old["status"], "repairExecutionStatus": row["status"], "stages": {}}
        for name in base.STAGES:
            before, after = old["stages"].get(name) or {}, row["stages"].get(name) or {}
            paired = before.get("executionStatus") == after.get("executionStatus") == "COMPLETED"
            comparison["stages"][name] = {
                "baselineExecutionStatus": before.get("executionStatus", "NOT_EXECUTED"),
                "repairExecutionStatus": after.get("executionStatus", "NOT_EXECUTED"),
                "baselineDurationMs": before.get("durationMs"), "repairDurationMs": after.get("durationMs"),
                "baselineDurationCensoredByTimeout": before.get("errorCode") == "TIMEOUT",
                "completedPairDurationDeltaMs": after["durationMs"] - before["durationMs"] if paired else None,
                "baselineProviderUsage": before.get("providerUsage", []), "repairProviderUsage": after.get("providerUsage", [])}
            if comparison["group"] == "SUCCESSFUL_CONTROL" and paired:
                controls.append({"caseId": row["caseId"], "stage": name,
                                 "baselineDurationMs": before["durationMs"], "repairDurationMs": after["durationMs"],
                                 **{key: {"baseline": before[key], "repair": after[key]} for key in ("inputTokens", "outputTokens", "totalTokens")}})
        if new_replay:
            same_prompt = old_replay["analysis"]["promptSha256"] == new_replay["analysis"]["promptSha256"]
            comparison["analysisPromptMatchesBaseline"] = same_prompt
            if not same_prompt:
                row["errors"].append("ANALYSIS_PROMPT_DIFFERS_FROM_BASELINE")
                row["status"] = comparison["repairExecutionStatus"] = "FAIL"
        comparisons.append(comparison)
    result["passedExecutionCount"] = sum(row["status"] == "PASS" for row in result["cases"])
    result["failedExecutionCount"] = sum(row["status"] == "FAIL" for row in result["cases"])
    result["status"] = ("FAIL" if result["errors"] or result["failedExecutionCount"] else
                        "INCOMPLETE" if result["missingResponseCount"] else "PASS")
    result["auditAdapterSha256"] = base.digest(__file__)
    result["baselineAuditSha256"] = base.digest(previous_path)
    result["requestObservationScope"] = "NATIVE_CLIENT_DEFAULTS_AND_FINAL_REQUEST_OPTIONS; HTTP serialization is covered by StandardThinkingBudgetHttpTest"
    result["reasoningTokenBreakdown"] = "NO_DATA: native business response records retain aggregate provider usage only"
    result["comparisonScope"] = "HISTORICAL_DIAGNOSTIC_NOT_RANDOMIZED; failed baseline durations are censored and missing usage remains unknown"
    result["comparisons"] = comparisons
    result["successfulControlPairs"] = controls
    result["successfulControlPairTotals"] = {
        "stageCount": len(controls),
        "baselineDurationMs": sum(row["baselineDurationMs"] for row in controls),
        "repairDurationMs": sum(row["repairDurationMs"] for row in controls),
        **{key: {arm: sum(row[key][arm] for row in controls)
                 if all(type(row[key][arm]) is int for row in controls) else None for arm in ("baseline", "repair")}
           for key in ("inputTokens", "outputTokens", "totalTokens")}}
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = audit()
    with args.output.open("x", encoding="utf-8", newline="\n") as output:
        output.write(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({key: result[key] for key in ("status", "plannedCaseCount", "receivedCaseCount", "passedExecutionCount", "errors", "providerTokens")}, ensure_ascii=False))
    raise SystemExit(0 if result["status"] == "PASS" else 2)
