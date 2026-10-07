"""Audit this campaign's defaults, registered executions and usage without model calls."""
import argparse
import importlib.util
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
source = HERE.parent / "batch-repair-20261002/audit_execution.py"
spec = importlib.util.spec_from_file_location("batch_learning_validation_audit", source)
parent = importlib.util.module_from_spec(spec)
spec.loader.exec_module(parent)
base = parent.base
base.HERE = HERE
base.BUILD = base.REPO / "stocksage-backend/target/evolution-build-batch-learning-validation-20261002"


def runtime_errors(runtime, settings, scope):
    dependencies = base.read(HERE / "runtime-dependencies.json")
    defaults = runtime.get("clients", {}).get("fundamentals", {})
    errors = []
    if (runtime.get("buildAttestation") != "VERIFIED_ARTIFACT"
            or runtime.get("runtimeArtifact", {}).get("sha256") != dependencies["build"]["artifactSha256"]
            or runtime.get("scope") != scope or runtime.get("servingBundleId") != "baseline-v1"):
        errors.append("RUNTIME_BUILD_SCOPE_OR_SERVING_BUNDLE_MISMATCH")
    if (runtime.get("stageTimeoutSeconds") != settings["stageTimeoutSeconds"]
            or runtime.get("retryAttempts") != settings["retryAttempts"]
            or runtime.get("httpTransport", {}).get("readTimeout") != "PT" + str(settings["httpReadTimeoutSeconds"] // 60) + "M"):
        errors.append("RUNTIME_TIMEOUT_OR_RETRY_OPTIONS_DIFFER_FROM_PLAN")
    if (defaults.get("model") != settings["model"] or defaults.get("temperature") != settings["temperature"]
            or defaults.get("maxTokens") != settings["maxTokens"]
            or not parent.thinking_options_match(defaults, settings)):
        errors.append("RUNTIME_FUNDAMENTALS_OPTIONS_DIFFER_FROM_PLAN")
    return errors


def audit_preflight(runtime_path):
    runtime = base.read(runtime_path)
    settings = base.read(HERE / "plan.json")["businessSettings"]
    errors = runtime_errors(runtime, settings, "NO_MODEL_PREFLIGHT")
    return {"schema": "fundamentals_batch_defaults_preflight_audit_v1", "status": "FAIL" if errors else "PASS",
            "scope": "NO_MODEL_PREFLIGHT", "releaseEligible": False, "businessQuality": "NOT_ASSESSED",
            "errors": errors, "runtimePath": str(runtime_path), "runtimeSha256": base.digest(runtime_path),
            "planSha256": base.digest(HERE / "plan.json"), "auditAdapterSha256": base.digest(__file__),
            "observedFundamentalsDefaults": runtime.get("clients", {}).get("fundamentals", {}),
            "observedHttpTransport": runtime.get("httpTransport"),
            "finalAnswerOptions": "Verified from actual per-stage invocations by the execution audit."}


def audit(prefix, split, bundle, lineage_path=None):
    # The parent installs its thinking-budget check into the shared stage audit;
    # its six-case historical comparison is deliberately not called here.
    result = base.audit(prefix, split, bundle, lineage_path)
    settings = base.read(HERE / "plan.json")["businessSettings"]
    for row in result["cases"]:
        runtime_path = HERE / ("raw-" + row["shard"]) / "runtime.json"
        if runtime_path.exists():
            runtime = base.read(runtime_path)
            row["errors"].extend(runtime_errors(runtime, settings, "LIVE_MODEL_FROZEN_COMPONENT_CHAIN"))
            row["runtimeThinkingOptions"] = {key: runtime.get("clients", {}).get("fundamentals", {}).get(key)
                                             for key in ("enableThinking", "thinkingBudget")}
        if row.get("responsePath"):
            replay = base.read(base.REPO / row["responsePath"])
            row["analystStatus"], row["taskOutcome"] = replay.get("analystStatus"), replay.get("taskOutcome")
            if row["analystStatus"] == "TRUNCATED":
                row["errors"].append("ANALYSIS_DRAFT_TRUNCATED")
        if row["errors"]:
            row["status"] = "FAIL"
    primary = [row for row in result["cases"] if "supersededBy" not in row]
    result["passedExecutionCount"] = sum(row["status"] == "PASS" for row in primary)
    result["failedExecutionCount"] = sum(row["status"] == "FAIL" for row in primary)
    result["status"] = ("FAIL" if result["errors"] or result["failedExecutionCount"] else
                        "INCOMPLETE" if any(row["status"] == "NO_DATA" for row in primary) else "PASS")
    result["auditAdapterSha256"] = base.digest(__file__)
    result["thinkingAuditSourceSha256"] = base.digest(source)
    result["requestObservationScope"] = "NATIVE_CLIENT_DEFAULTS_AND_FINAL_REQUEST_OPTIONS"
    result["reasoningTokenBreakdown"] = "NO_DATA: native business records retain aggregate provider usage only"
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prefix", default="development-")
    parser.add_argument("--split", choices=("DEVELOPMENT", "VALIDATION"), default="DEVELOPMENT")
    parser.add_argument("--bundle", default="baseline-v1")
    parser.add_argument("--lineage", type=Path)
    parser.add_argument("--preflight-runtime", type=Path, help="Audit saved no-model startup options against plan.json")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = (audit_preflight(args.preflight_runtime) if args.preflight_runtime else
              audit(args.prefix, args.split, args.bundle, args.lineage))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x", encoding="utf-8", newline="\n") as output:
        output.write(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({key: result[key] for key in
                     ("status", "plannedCaseCount", "receivedCaseCount", "passedExecutionCount", "errors", "providerTokens")
                     if key in result}, ensure_ascii=False))
    raise SystemExit(0 if result["status"] == "PASS" else 2)
