"""Verify saved Max DEVELOPMENT baseline outputs; never invokes a provider."""
import hashlib
import json
import statistics
import sys
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[3]
sys.path.insert(0, str(REPO / "rag-eval"))
from run_evolution_replay import run_cases
from evolution_acceptance import provider_usage, validate_run_context
from evolution_compare import model_identity
from evolution_quality import input_errors, stage_errors

EXPECTED_BUILD = "f6d9e017c82a9bfd621dddd2b9e59c19037437fff7a79175ec827d742932b1f5"
EXPECTED_EXPERIMENT = "common-fundamentals-max-offline-001-20261001"
read = lambda path: json.loads(path.read_text(encoding="utf-8"))
sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    output = HERE / "baseline-execution-audit.json"
    if output.exists():
        raise FileExistsError("Preserve the existing audit; this one-time summarizer refuses overwrite.")
    errors, cases, shards, sources = [], [], [], {}
    run_ids, case_ids, identities = [], [], []

    def check(condition, message):
        if not condition:
            errors.append(message)

    build_path = REPO / "stocksage-backend/target/evolution-build-max-20261001/build.json"
    build = read(build_path)
    check(build["artifactSha256"] == sha(build_path.with_name("stocksage-backend.jar")) == EXPECTED_BUILD,
          "PRODUCTION_ARTIFACT_HASH_MISMATCH")
    sources[str(build_path)] = sha(build_path)
    driver_path = REPO / "stocksage-backend/target/evolution-max-20261001-driver/driver-dependencies.json"
    driver = read(driver_path)
    check(driver["productionArtifactSha256"] == EXPECTED_BUILD, "DRIVER_BUILD_BINDING_CHANGED")
    for item in [driver["driverSource"], *driver["driverClasses"], *driver["dependencies"]]:
        check(sha(Path(item["path"])) == item["sha256"], "DRIVER_DEPENDENCY_CHANGED:" + item["path"])
    sources[str(driver_path)] = sha(driver_path)
    sources["audit_baseline.py"] = sha(Path(__file__))
    for shard in (1, 2):
        raw = HERE / f"raw-baseline-{shard}"
        execution_path = HERE / f"execution-development-baseline-{shard}.json"
        execution = read(execution_path)
        runtime = read(raw / "runtime.json")
        check(sha(execution_path) == sha(raw / "execution.json"), f"shard{shard}:EXECUTION_COPY_CHANGED")
        check(execution["context"]["experimentId"] == EXPECTED_EXPERIMENT
              and execution["context"]["runMode"] == "DEVELOPMENT", f"shard{shard}:EXPERIMENT_CONTEXT_CHANGED")
        check(runtime["runtimeArtifact"]["sha256"] == EXPECTED_BUILD
              and runtime["buildAttestation"] == "VERIFIED_ARTIFACT", f"shard{shard}:RUNTIME_BUILD_MISMATCH")
        check(runtime["stageTimeoutSeconds"] == 180 and runtime["retryAttempts"] == 1
              and runtime["httpTransport"]["readTimeout"] == "PT3M", f"shard{shard}:TIMEOUT_OR_RETRY_CHANGED")
        check(runtime["servingBundleId"] == "baseline-v1", f"shard{shard}:SERVING_METHOD_CHANGED")
        check("/target/evolution-max-20261001-driver/classes/" in runtime["driver"]["codeSource"],
              f"shard{shard}:DRIVER_ORIGIN_CHANGED")
        origins = runtime.get("productionCodeSources", {})
        check(len(origins) == 14 and all("/evolution-build-max-20261001/stocksage-backend.jar/" in value
                                       for value in origins.values()), f"shard{shard}:PRODUCTION_CODE_SOURCE_CHANGED")
        response_path = raw / "responses.jsonl"
        rows = [json.loads(line) for line in response_path.read_text(encoding="utf-8").splitlines()] if response_path.exists() else []
        by_run = {row["request"]["runId"]: row["response"] for row in rows}
        planned = {run["runId"]: run for run in execution["runs"]}
        check(len(rows) == len(by_run) == len(planned) == 3 and set(by_run) == set(planned),
              f"shard{shard}:MISSING_EXTRA_OR_DUPLICATE_RESPONSE")
        check([row["request"]["runId"] for row in rows] == list(planned), f"shard{shard}:RUN_ORDER_MISMATCH")
        for row in rows:
            request = row["request"]
            registered = planned.get(request["runId"], {})
            check(request == {key: registered.get(key) for key in ("caseId", "bundleId", "runId")},
                  f"{request['runId']}:REQUEST_REGISTRATION_MISMATCH")
            check(read(raw / (request["runId"] + ".request.json")) == request
                  and read(raw / (request["runId"] + ".response.json")) == row["response"],
                  f"{request['runId']}:INDIVIDUAL_RECORD_MISMATCH")
        report = run_cases(execution, "baseline-v1", sha(execution_path),
                           lambda request: by_run[request["runId"]], continue_on_failure=True)
        check(report["status"] == "PASS", f"shard{shard}:REPLAY_VALIDATION_{report['status']}")
        missing = [{"runId": run_id, "requestSaved": (raw / (run_id + ".request.json")).exists(),
                    "providerUsage": "UNKNOWN"} for run_id in planned if run_id not in by_run]
        shards.append({"shard": shard, "executionStatus": report["status"], "registeredRuns": len(planned),
                       "savedResponses": len(rows), "missingResponses": missing,
                       "failureRecords": [read(path) for path in sorted(raw.glob("*.failure.json"))],
                       "providerErrors": [json.loads(line) for line in (raw / "provider-error.jsonl").read_text(encoding="utf-8").splitlines()]
                       if (raw / "provider-error.jsonl").exists() else []})
        for case in report["cases"]:
            replay = case["replay"]
            run_id = replay["runId"]
            run_ids.append(run_id)
            case_ids.append(replay["caseId"])
            try:
                validate_run_context(case, EXPECTED_EXPERIMENT, "DEVELOPMENT")
            except ValueError as error:
                errors.append(f"{run_id}:RUN_CONTEXT_INVALID:{error}")
            errors.extend(f"{run_id}:{error}" for error in input_errors(case))
            identity = replay["comparisonIdentity"]
            configuration_json = identity.get("modelConfigurationJson", "")
            check(hashlib.sha256(configuration_json.encode()).hexdigest() == identity.get("modelConfigSha256")
                  and json.loads(configuration_json) == identity.get("modelConfiguration"), f"{run_id}:MODEL_CONFIG_HASH_MISMATCH")
            identities.append({key: identity[key] for key in
                               ("runtimeBuildSha256", "modelConfigSha256", "fixedFinalPromptSha256", "memorySnapshotSha256")})
            check(identity["runtimeBuild"]["manifest"] == build, f"{run_id}:BUILD_MANIFEST_CHANGED")
            item = {"runId": run_id, "caseId": replay["caseId"], "status": replay["status"], "errorCode": replay.get("errorCode"), "stages": {}}
            for name in ("analysis", "finalAnswer"):
                stage = replay.get(name) or {}
                errors.extend(f"{run_id}:{name}:{error}" for error in stage_errors(case, name))
                usage_rows = [row for row in stage.get("observations", []) if row.get("kind") == "model-usage"]
                usage, model = provider_usage(stage, name), model_identity(stage, name)
                check(usage is not None and len(usage_rows) == 1
                      and usage_rows[0].get("usageSemantics") == "INVOCATION_SNAPSHOT", f"{run_id}:{name}:USAGE_UNKNOWN_OR_DUPLICATE")
                check(model is not None and model["actualModel"] == "qwen3.8-max"
                      and model["request"]["modelName"] == "qwen3.8-max"
                      and model["timeoutSeconds"] == 180, f"{run_id}:{name}:MODEL_OR_TIMEOUT_MISMATCH")
                options = (model["request"].get("clientDefaults", {}) if name == "analysis" else model["request"]) if model else {}
                requested_max = options.get("maxTokens" if name == "analysis" else "maxOutputTokens")
                check(options.get("temperature") == 0.7 and requested_max == 4096, f"{run_id}:{name}:MODEL_OPTIONS_CHANGED")
                if usage:
                    check(usage_rows[0].get("totalTokens") == sum(usage), f"{run_id}:{name}:TOKEN_TOTAL_MISMATCH")
                messages = stage.get("messages")
                if messages:
                    digest = hashlib.sha256(json.dumps(messages, ensure_ascii=False, separators=(",", ":")).encode()).hexdigest()
                    check(digest == stage.get("promptSha256"), f"{run_id}:{name}:PROMPT_HASH_MISMATCH")
                item["stages"][name] = {"status": stage.get("status", "NOT_RECORDED"), "errorCode": stage.get("errorCode"),
                    "actualModel": model["actualModel"] if model else None, "modelIdentity": model,
                    "durationMs": stage.get("durationMs"), "answerCharacters": len(stage.get("answer") or ""),
                    "providerUsageStatus": "KNOWN" if usage is not None else "UNKNOWN",
                    "providerUsageRecords": len(usage_rows), "inputTokens": usage[0] if usage else None,
                    "outputTokens": usage[1] if usage else None, "totalTokens": sum(usage) if usage else None,
                    "requestedMaxOutputTokens": requested_max, "outputExceedsRequestedMax": usage[1] > requested_max if usage and requested_max else None,
                    "finishReason": None, "finishReasonStatus": "NOT_CAPTURED_BY_CURRENT_PRODUCER"}
            cases.append(item)
        for path in [execution_path, *sorted(raw.glob("*.json")), *sorted(raw.glob("*.jsonl"))]:
            sources[str(path.relative_to(HERE))] = sha(path)
    check(len(run_ids) == len(set(run_ids)) == len(case_ids) == len(set(case_ids)) == 6, "BASELINE_CASE_COVERAGE_MISMATCH")
    check(bool(identities) and all(value == identities[0] for value in identities), "CROSS_CASE_COMPARISON_IDENTITY_CHANGED")
    stages = {}
    for name in ("analysis", "finalAnswer"):
        recorded = [case["stages"][name] for case in cases]
        known = [stage for stage in recorded if stage["providerUsageStatus"] == "KNOWN"]
        durations = [stage["durationMs"] for stage in recorded if isinstance(stage["durationMs"], int)]
        stages[name] = {"completed": sum(stage["status"] == "COMPLETED" for stage in recorded),
            "knownUsageCalls": len(known), "usageComplete": len(known) == 6,
            "knownProviderTokens": {key: sum(stage[key] for stage in known) for key in ("inputTokens", "outputTokens", "totalTokens")},
            "medianDurationMs": statistics.median(durations) if durations else None, "maxDurationMs": max(durations) if durations else None,
            "outputExceedsRequestedMaxCount": sum(stage["outputExceedsRequestedMax"] is True for stage in recorded),
            "finishReasonStatus": "NOT_CAPTURED_BY_CURRENT_PRODUCER"}
    result = {"schema": "max_baseline_execution_audit_v1", "experimentId": EXPECTED_EXPERIMENT,
        "auditedAt": datetime.now(timezone.utc).isoformat(), "status": "PASS" if not errors else "FAIL_OR_INCOMPLETE",
        "answerQuality": "NOT_REVIEWED_BY_THIS_AUDIT", "providerCallsByAuditor": 0, "activationAuthorized": False,
        "runtimeArtifactSha256": EXPECTED_BUILD, "registeredCaseCount": 6,
        "errors": errors, "shards": shards, "stages": stages, "cases": cases, "sourcePathBase": str(HERE), "sourceSha256": sources,
        "cost": {"status": "UNPRICED", "failedOrMissingUsage": "UNKNOWN_NOT_ZERO"},
        "limitations": ["Finish reasons were not recorded; completion status and token counts cannot establish absence of truncation.",
                        "This audit verifies frozen component replay execution, not semantic quality or formal acceptance."]}
    with output.open("x", encoding="utf-8") as stream:
        json.dump(result, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    print(json.dumps({"status": result["status"], "stages": stages, "errors": errors}, ensure_ascii=False))
    return 0 if not errors else 1


if __name__ == "__main__":
    raise SystemExit(main())
