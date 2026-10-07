"""Audit saved batch executions and actual provider usage without making calls."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import sys

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE.parents[1])]
from experiment import BUILD, REPO, digest, read
from eval_common import json_hash, sha256
from evolution_acceptance import provider_usage
from evolution_compare import model_identity
from evolution_dataset import execution_payload, load_jsonl
from evolution_quality import input_errors, stage_errors, stage_material
from evolution_rubric import STAGES
from run_evolution_replay import run_cases


def audit_stage(case: dict, name: str, settings: dict) -> dict:
    stage = case["replay"].get(name) or {}
    if not stage:
        return {"status": "NO_DATA", "errors": ["STAGE_NOT_EXECUTED"], "executionStatus": "NOT_EXECUTED",
                "errorCode": None, "answerSha256": None, "modelIdentity": None, "invocationCount": 0,
                "invocations": [], "providerUsage": [], "durationMs": None,
                "inputTokens": None, "outputTokens": None, "totalTokens": None}
    errors = stage_errors(case, name) + stage_material(case, name)["errors"]
    invocations = [row for row in stage.get("observations", []) if row.get("kind") == "model-invocation"]
    usage_rows = [row for row in stage.get("observations", []) if row.get("kind") == "model-usage"]
    identity, usage = model_identity(stage, name), provider_usage(stage, name)
    if len(invocations) != 1 or invocations[0].get("scope") != STAGES[name][1]:
        errors.append("EXPECTED_ONE_NATIVE_STAGE_INVOCATION")
    if identity is None:
        errors.append("MODEL_IDENTITY_UNAVAILABLE")
    else:
        invocation = identity["request"]
        options = invocation.get("clientDefaults", {}) if name == "analysis" else invocation
        max_key = "maxTokens" if name == "analysis" else "maxOutputTokens"
        if (identity["actualModel"] != settings["model"] or invocation.get("modelName") != settings["model"]
                or invocation.get("modelTier") != "STANDARD" or options.get("temperature") != settings["temperature"]
                or options.get(max_key) != settings["maxTokens"] or identity["timeoutSeconds"] != settings["stageTimeoutSeconds"]):
            errors.append("MODEL_OR_OPTIONS_DIFFER_FROM_PLAN")
    if usage is None or len(usage_rows) != 1:
        errors.append("PROVIDER_USAGE_INCOMPLETE")
    elif (type(usage_rows[0].get("totalTokens")) is not int or usage_rows[0]["totalTokens"] != sum(usage)
          or usage_rows[0].get("usageSemantics") != "INVOCATION_SNAPSHOT"):
        errors.append("PROVIDER_USAGE_TOTAL_OR_SEMANTICS_INVALID")
    duration = stage.get("durationMs")
    if type(duration) is not int or duration < 0:
        errors.append("STAGE_DURATION_UNAVAILABLE")
    if stage.get("status") != "COMPLETED" or stage.get("errorCode") or not stage.get("answer", "").strip():
        errors.append("STAGE_FAILED_OR_ANSWER_MISSING")
    return {"status": "FAIL" if errors else "PASS", "errors": sorted(set(errors)),
            "executionStatus": stage.get("status"), "errorCode": stage.get("errorCode"),
            "answerSha256": sha256(stage.get("answer") or ""), "modelIdentity": identity,
            "invocationCount": len(invocations), "invocations": invocations, "providerUsage": usage_rows, "durationMs": duration,
            "inputTokens": usage[0] if usage else None, "outputTokens": usage[1] if usage else None,
            "totalTokens": sum(usage) if usage else None}


def audit(prefix: str, split: str, bundle: str, lineage_path: Path | None = None) -> dict:
    plan, dependencies = read(HERE / "plan.json"), read(HERE / "runtime-dependencies.json")
    plan_sha = digest(HERE / "plan.json")
    errors, identities, cases, artifacts, runtime_checks = [], [], [], [], []
    frozen = [{"path": path, "expectedSha256": expected, "actualSha256": digest(REPO / path)}
              for path, expected in plan["frozenFiles"].items()]
    if any(row["actualSha256"] != row["expectedSha256"] for row in frozen):
        errors.append("FROZEN_FILE_CHANGED")
    runtime_files = [{"path": str(BUILD / "stocksage-backend.jar"), "sha256": dependencies["build"]["artifactSha256"]},
                     dependencies["driverSource"], *dependencies["driverClasses"], *dependencies["dependencies"]]
    for row in runtime_files:
        runtime_checks.append({**row, "actualSha256": digest(row["path"])})
    if any(row["actualSha256"] != row["sha256"] for row in runtime_checks):
        errors.append("RUNTIME_FILE_CHANGED")
    expected_cases = {row["caseId"]: execution_payload(row) for row in load_jsonl(HERE / "cases.jsonl") if row["split"] == split}
    for execution_path in sorted(HERE.glob(prefix + "*.execution.json")):
        name = execution_path.name.removesuffix(".execution.json")
        registration_path = HERE / (name + ".registration.json")
        registration, execution = read(registration_path), read(execution_path)
        execution_sha = digest(execution_path)
        shard_errors = []
        if registration["executionSha256"] != execution_sha or registration["planSha256"] != plan_sha:
            shard_errors.append("REGISTRATION_HASH_MISMATCH")
        if execution.get("context") != {"experimentId": plan["experimentId"], "evaluatorVersion": plan["evaluatorSha256"], "runMode": split}:
            shard_errors.append("EXECUTION_CONTEXT_MISMATCH")
        if registration["modelCalls"] != len(execution["runs"]) * len(STAGES):
            shard_errors.append("REGISTERED_CALL_COUNT_MISMATCH")
        if any(expected_cases.get(row["caseId"]) != row for row in execution["cases"]):
            shard_errors.append("EXECUTION_SOURCE_CASE_MISMATCH")
        output = HERE / ("raw-" + name)
        saved_execution, runtime_path = output / "execution.json", output / "runtime.json"
        responses, response_requests = {}, {}
        if saved_execution.exists() and digest(saved_execution) != execution_sha:
            shard_errors.append("CAPTURED_EXECUTION_MISMATCH")
        if runtime_path.exists():
            runtime = read(runtime_path)
            if (runtime.get("buildAttestation") != "VERIFIED_ARTIFACT"
                    or (runtime.get("runtimeArtifact") or {}).get("sha256") != dependencies["build"]["artifactSha256"]
                    or runtime.get("retryAttempts") != plan["businessSettings"]["retryAttempts"]
                    or (runtime.get("httpTransport") or {}).get("readTimeout") != "PT" + str(plan["businessSettings"]["httpReadTimeoutSeconds"] // 60) + "M"
                    or runtime.get("scope") != "LIVE_MODEL_FROZEN_COMPONENT_CHAIN"):
                shard_errors.append("RUNTIME_ATTESTATION_OR_HTTP_OPTIONS_MISMATCH")
            artifacts.append({"path": str(runtime_path.relative_to(REPO)), "sha256": digest(runtime_path)})
        response_log = output / "responses.jsonl"
        if response_log.exists():
            for line in response_log.read_text(encoding="utf-8").splitlines():
                if not line.strip():
                    continue
                row = json.loads(line)
                run_id = row["request"]["runId"]
                if run_id in responses:
                    shard_errors.append("DUPLICATE_RESPONSE:" + run_id)
                responses[run_id], response_requests[run_id] = row["response"], row["request"]
            artifacts.append({"path": str(response_log.relative_to(REPO)), "sha256": digest(response_log)})
        expected_runs = {run["runId"] for run in execution["runs"]}
        if set(responses) - expected_runs:
            shard_errors.append("UNREGISTERED_RESPONSE")
        for run in execution["runs"]:
            if run["bundleId"] != bundle:
                continue
            request = {key: run[key] for key in ("runId", "caseId", "bundleId")}
            row = {**run, "shard": name, "status": "NO_DATA", "errors": list(shard_errors),
                   "responsePresent": run["runId"] in responses, "stages": {}}
            identities.append((run["runId"], run["caseId"], run["repeatId"]))
            request_path = output / (run["runId"] + ".request.json")
            failure_path = output / (run["runId"] + ".failure.json")
            reservation_path = HERE / "attempts" / (run["runId"] + ".json")
            row["requestPresent"], row["failurePresent"] = request_path.exists(), failure_path.exists()
            if request_path.exists() and read(request_path) != request:
                row["errors"].append("CAPTURED_REQUEST_MISMATCH")
            if reservation_path.exists():
                reservation = read(reservation_path)
                if (reservation.get("run") != run or reservation.get("executionSha256") != execution_sha
                        or Path(reservation.get("output", "")).resolve() != output.resolve()):
                    row["errors"].append("ATTEMPT_RESERVATION_MISMATCH")
            elif row["requestPresent"] or row["responsePresent"]:
                row["errors"].append("ATTEMPT_RESERVATION_MISSING")
            if row["responsePresent"]:
                if not saved_execution.exists() or not runtime_path.exists() or not request_path.exists():
                    row["errors"].append("EXECUTION_CAPTURE_INCOMPLETE")
                replay = responses[run["runId"]]
                response_path = output / (run["runId"] + ".response.json")
                if (response_requests[run["runId"]] != request or not response_path.exists()
                        or read(response_path) != replay):
                    row["errors"].append("RAW_RESPONSE_BINDING_MISMATCH")
                report = run_cases({**execution, "runs": [run]}, bundle, execution_sha, lambda request: replay)
                case = report["cases"][0]
                row["errors"].extend(input_errors(case))
                if report["status"] != "PASS":
                    row["errors"].append("NATIVE_REPLAY_EXECUTION_FAILED")
                identity = replay.get("comparisonIdentity") or {}
                build = identity.get("runtimeBuild") or {}
                if (build.get("status") != "VERIFIED_ARTIFACT" or build.get("manifest") != dependencies["build"]
                        or json_hash(build) != identity.get("runtimeBuildSha256")
                        or (build.get("runtimeArtifact") or {}).get("sha256") != dependencies["build"]["artifactSha256"]):
                    row["errors"].append("REPLAY_BUILD_IDENTITY_MISMATCH")
                row["stages"] = {stage: audit_stage(case, stage, plan["businessSettings"]) for stage in STAGES}
                row["status"] = "PASS" if not row["errors"] and all(s["status"] == "PASS" for s in row["stages"].values()) else "FAIL"
                row["responsePath"] = str(response_path.relative_to(REPO))
                row["responseSha256"] = digest(response_path) if response_path.exists() else None
            elif failure_path.exists():
                row["status"] = "FAIL"
                row["failure"] = read(failure_path)
            if row["errors"]:
                row["status"] = "FAIL"
            cases.append(row)
    if len({run for run, _, _ in identities}) != len(identities):
        errors.append("DUPLICATE_REGISTERED_RUN")
    lineage = read(lineage_path) if lineage_path else {"replacements": []}
    indexed, seen_old, seen_new = {row["runId"]: row for row in cases}, set(), set()
    for replacement in lineage["replacements"]:
        old_id, new_id = replacement["previousRunId"], replacement["newRunId"]
        old, new = indexed.get(old_id), indexed.get(new_id)
        if old is None and new is None:
            continue
        if (old is None or new is None or old_id in seen_old or new_id in seen_new
                or old["caseId"] != replacement["caseId"] or new["caseId"] != replacement["caseId"]
                or old["repeatId"] != new["repeatId"] or old["bundleId"] != new["bundleId"]
                or old["requestPresent"] or old["responsePresent"] or old["failurePresent"] or old["errors"]):
            errors.append("INVALID_UNSUBMITTED_LINEAGE:" + old_id)
            continue
        seen_old.add(old_id)
        seen_new.add(new_id)
        old["supersededBy"] = new_id
        new["firstSubmissionAfterReservation"] = old_id
    primary = [row for row in cases if "supersededBy" not in row]
    if len({(row["caseId"], row["repeatId"]) for row in primary}) != len(primary):
        errors.append("DUPLICATE_CASE_SUBMISSION_WITHOUT_VALID_LINEAGE")
    if {row["caseId"] for row in primary} != set(expected_cases) or len(primary) != plan["caseCounts"][split]:
        errors.append("REGISTERED_CASE_COVERAGE_MISMATCH")
    stages = [stage for row in cases for stage in row["stages"].values()]
    known_usage = [stage for stage in stages if stage["totalTokens"] is not None]
    expected_stage_count = plan["caseCounts"][split] * len(STAGES)
    return {"schema": "fundamentals_batch_execution_audit_v1", "scope": "EXECUTION_AND_USAGE_ONLY",
            "generatedAt": datetime.now(timezone.utc).isoformat(), "releaseEligible": False, "businessQuality": "NOT_ASSESSED",
            "planSha256": plan_sha, "auditScriptSha256": digest(__file__), "split": split, "bundleId": bundle,
            "lineage": lineage, "lineageSha256": digest(lineage_path) if lineage_path else None,
            "status": "FAIL" if errors or any(row["status"] == "FAIL" for row in primary) else "INCOMPLETE" if any(row["status"] == "NO_DATA" for row in primary) else "PASS",
            "errors": errors, "plannedCaseCount": plan["caseCounts"][split], "registeredAttemptCount": len(cases),
            "primaryCaseCount": len(primary), "supersededUnsubmittedReservationCount": len(seen_old),
            "submittedRunCount": sum(row["requestPresent"] for row in cases),
            "receivedCaseCount": sum(row["responsePresent"] for row in primary),
            "passedExecutionCount": sum(row["status"] == "PASS" for row in primary),
            "failedExecutionCount": sum(row["status"] == "FAIL" for row in primary),
            "missingResponseCount": sum(not row["responsePresent"] for row in primary),
            "notStartedCaseCount": sum(not row["requestPresent"] for row in primary),
            "plannedStageCount": expected_stage_count, "observedInvocationCount": sum(stage["invocationCount"] for stage in stages),
            "providerUsageStageCount": len(known_usage), "unreportedPlannedStageCount": expected_stage_count - len(known_usage),
            "unknownUsageInvocationCount": sum(stage["invocationCount"] for stage in stages if stage["totalTokens"] is None),
            "providerTokens": {key: sum(stage[key] for stage in known_usage) for key in ("inputTokens", "outputTokens", "totalTokens")},
            "sumRecordedStageDurationMs": sum(stage["durationMs"] for stage in stages if type(stage["durationMs"]) is int),
            "frozenFileChecks": frozen, "runtimeFileChecks": runtime_checks, "captureArtifacts": artifacts, "cases": cases}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prefix", default="development-")
    parser.add_argument("--split", choices=("DEVELOPMENT", "VALIDATION"), default="DEVELOPMENT")
    parser.add_argument("--bundle", default="baseline-v1")
    parser.add_argument("--lineage", type=Path, help="Explicit replacement mapping for reservations that never submitted a request")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = audit(args.prefix, args.split, args.bundle, args.lineage)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x", encoding="utf-8", newline="\n") as target:
        target.write(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({key: result[key] for key in ("status", "plannedCaseCount", "receivedCaseCount", "passedExecutionCount", "errors", "providerTokens")}, ensure_ascii=False))
    return 0 if result["status"] == "PASS" else 2


if __name__ == "__main__":
    raise SystemExit(main())
