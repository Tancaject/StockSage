"""Audit a pre-registered subset phase using the campaign's unchanged execution checks."""
import argparse
import importlib.util
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("batch_validation_phase_execution", HERE / "audit_execution.py")
execution_audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(execution_audit)
base = execution_audit.base


def audit(manifest_path):
    manifest = base.read(manifest_path)
    split, prefix, bundle = (manifest[key] for key in ("split", "prefix", "bundleId"))
    errors = []
    if manifest["schema"] != "fundamentals_registered_subset_phase_v1":
        errors.append("PHASE_MANIFEST_SCHEMA_MISMATCH")
    if manifest["planSha256"] != base.digest(HERE / "plan.json"):
        errors.append("PHASE_PLAN_HASH_MISMATCH")
    if manifest["auditPhaseSha256"] != base.digest(__file__):
        errors.append("PHASE_AUDIT_SCRIPT_HASH_MISMATCH")

    expected = [(row["caseId"], row["runId"]) for row in manifest["expectedRuns"]]
    expected_cases = {case_id for case_id, _ in expected}
    split_cases = {row["caseId"] for row in base.load_jsonl(HERE / "cases.jsonl") if row["split"] == split}
    if (not expected or len(expected_cases) != len(expected)
            or len({run_id for _, run_id in expected}) != len(expected)
            or not expected_cases <= split_cases):
        errors.append("PHASE_EXPECTED_RUNS_INVALID")

    registered_paths = [base.REPO / row["path"] for row in manifest["executions"]]
    discovered_paths = sorted(HERE.glob(prefix + "*.execution.json"))
    if (len(set(path.resolve() for path in registered_paths)) != len(registered_paths)
            or {path.resolve() for path in registered_paths} != {path.resolve() for path in discovered_paths}):
        errors.append("PHASE_EXECUTION_FILE_COVERAGE_MISMATCH")
    registered_runs, execution_checks = [], []
    for identity, path in zip(manifest["executions"], registered_paths):
        actual_sha = base.digest(path) if path.exists() else None
        execution_checks.append({"path": identity["path"], "expectedSha256": identity["sha256"],
                                 "actualSha256": actual_sha})
        if actual_sha != identity["sha256"]:
            errors.append("PHASE_EXECUTION_HASH_MISMATCH:" + identity["path"])
        if path.exists():
            for run in base.read(path)["runs"]:
                registered_runs.append((run["caseId"], run["runId"]))
                if run["bundleId"] != bundle:
                    errors.append("PHASE_REGISTERED_BUNDLE_MISMATCH:" + run["runId"])
    if len(registered_runs) != len(expected) or set(registered_runs) != set(expected):
        errors.append("PHASE_REGISTERED_RUN_COVERAGE_MISMATCH")

    result = execution_audit.audit(prefix, split, bundle)
    actual = [(row["caseId"], row["runId"]) for row in result["cases"]]
    if len(actual) != len(expected) or set(actual) != set(expected):
        errors.append("PHASE_AUDITED_RUN_COVERAGE_MISMATCH")

    # The original audit requires the whole split. Only this single check is
    # replaced, after the manifest proves exactly which registered runs belong.
    original_coverage_error = "REGISTERED_CASE_COVERAGE_MISMATCH"
    if not errors:
        result["errors"] = [error for error in result["errors"] if error != original_coverage_error]
    result["errors"].extend(errors)
    result["scope"] = "SUBSET_REGISTERED_PHASE_EXECUTION"
    result["phaseId"] = manifest["phaseId"]
    result["phaseManifestPath"] = str(manifest_path)
    result["phaseManifestSha256"] = base.digest(manifest_path)
    result["auditPhaseSha256"] = base.digest(__file__)
    result["phaseExecutionChecks"] = execution_checks
    result["phaseExpectedRuns"] = manifest["expectedRuns"]
    result["plannedCaseCount"] = len(expected)
    result["plannedStageCount"] = len(expected) * len(base.STAGES)
    reported_planned_stages = {(row["runId"], stage_name)
                               for row in result["cases"] if (row["caseId"], row["runId"]) in expected
                               for stage_name, stage in row["stages"].items() if stage["totalTokens"] is not None}
    result["unreportedPlannedStageCount"] = result["plannedStageCount"] - len(reported_planned_stages)
    result["status"] = ("FAIL" if result["errors"] or result["failedExecutionCount"] else
                        "INCOMPLETE" if result["missingResponseCount"] else "PASS")
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--phase-manifest", type=Path, required=True,
                        help="Pre-registered expected runs, execution hashes, plan hash and this script's hash")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = audit(args.phase_manifest)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x", encoding="utf-8", newline="\n") as target:
        target.write(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({key: result[key] for key in
                     ("status", "scope", "plannedCaseCount", "receivedCaseCount", "passedExecutionCount",
                      "plannedStageCount", "unreportedPlannedStageCount", "errors", "providerTokens")},
                     ensure_ascii=False))
    raise SystemExit(0 if result["status"] == "PASS" else 2)
