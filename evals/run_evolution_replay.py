"""Run registered FUNDAMENTALS replay cases; PASS describes execution, never semantic improvement."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from datetime import datetime, timezone
from pathlib import Path
import urllib.error
import urllib.request

from eval_common import json_hash


def run_cases(execution: dict, bundle_id: str, execution_file_sha256: str, invoke,
              comparison_manifest: dict | None = None, *, validate_only=False, continue_on_failure=False) -> dict:
    cases = execution.get("cases", [])
    if execution.get("schemaVersion") not in {1, 2} or not cases or len({c["caseId"] for c in cases}) != len(cases):
        raise ValueError("Execution file requires schemaVersion 1/2 and unique nonempty cases")
    if execution["schemaVersion"] == 2:
        from evolution_dataset import validate_execution_context
        validate_execution_context(execution.get("context"))
    elif execution.get("context") is not None:
        raise ValueError("Execution context requires schemaVersion 2")
    for case in cases:
        if case.get("route") != "FUNDAMENTALS" or not all(case.get(k) for k in
                ("caseId", "caseSha256", "query", "resolvedQuery", "origin")):
            raise ValueError("Every case must contain the frozen FUNDAMENTALS input identity")
    indexed = {case["caseId"]: case for case in cases}
    runs = execution.get("runs", [])
    if not runs or len({r["runId"] for r in runs}) != len(runs):
        raise ValueError("Execution file requires unique registered run IDs")
    runs = [run for run in runs if run.get("bundleId") == bundle_id]
    if not runs:
        raise ValueError("Execution file contains no registered runs for the selected bundle")
    definitions = []
    for run in runs:
        if (run.get("caseId") not in indexed or run.get("bundleId") != bundle_id
                or type(run.get("repeatId")) is not int or run["repeatId"] < 1):
            raise ValueError("Run must reference a registered case, selected bundle and positive repeat ID")
        definitions.append({**indexed[run["caseId"]], "bundleId": bundle_id, "repeatId": run["repeatId"]})
    if len({(r["caseId"], r["repeatId"]) for r in runs}) != len(runs):
        raise ValueError("Repeated invocations need distinct repeat IDs")
    if comparison_manifest is not None:
        from evolution_compare import validate_manifest
        declared = validate_manifest(comparison_manifest)
        if bundle_id not in (comparison_manifest["baselineBundleId"], comparison_manifest["candidateBundleId"]):
            raise ValueError("Selected bundle is outside the predeclared comparison")
        expected = {(case_id, repeat) for case_id, case in declared.items() for repeat in case["repeatIds"]}
        if {(r["caseId"], r["repeatId"]) for r in runs} != expected or any(
                definition["caseSha256"] != declared[definition["caseId"]]["caseSha256"] for definition in definitions):
            raise ValueError("Registered runs differ from the predeclared comparison cases or repeats")
    report = {"schema": "fundamentals_evolution_replay_eval_v1", "sample_count": len(runs),
              "sample_unit": "INVOCATION", "unique_case_count": len({r["caseId"] for r in runs}),
              "dataset_sha256": json_hash(definitions), "execution_file_sha256": execution_file_sha256,
              "status": "BLOCKED", "answer_quality": "NO_DATA", "cases": [],
              "started_at": datetime.now(timezone.utc).isoformat()}
    if comparison_manifest is not None:
        report["comparison_manifest_sha256"] = json_hash(comparison_manifest)
    if validate_only:
        return report
    for definition, run in zip(definitions, runs):
        request = {"caseId": definition["caseId"], "bundleId": bundle_id, "runId": run["runId"]}
        try:
            result = invoke(request)
            if not isinstance(result, dict):
                raise ValueError("Replay endpoint returned a non-object response")
            checks = {
                "schema": result.get("schema") == "fundamentals_evolution_replay_v1",
                "identity": all(result.get(k) == request[k] for k in ("runId", "caseId")),
                "repeat": result.get("repeatId") == run["repeatId"],
                "source": result.get("caseSha256") == definition["caseSha256"]
                          and result.get("executionFileSha256") == execution_file_sha256,
                "questions": result.get("query") == definition["query"]
                             and result.get("resolvedQuery") == definition["resolvedQuery"],
                "origin": result.get("origin") == definition["origin"]
                          and result.get("executionScope") == definition.get("executionScope"),
                "bundle": (result.get("methodBundle") or {}).get("bundleId") == bundle_id,
                "execution": result.get("status") == "COMPLETED"
                             and all((result.get(stage) or {}).get("status") == "COMPLETED"
                                     for stage in ("analysis", "finalAnswer")),
            }
            if execution["schemaVersion"] == 2:
                context = result.get("runContext") or {}
                checks["run_context"] = (all(context.get(key) == value for key, value in execution["context"].items())
                                          and all(context.get(key) == run[key] for key in ("runId", "caseId", "repeatId")))
            report["cases"].append({"id": f"{definition['caseId']}@{run['repeatId']}", "case_definition": definition,
                                    "request": request, "replay": result, "checks": checks,
                                    "passed": all(checks.values())})
            if not all(checks.values()):
                report["status"] = "FAIL"
                if not continue_on_failure:
                    break
        except (OSError, ValueError, KeyError, TypeError) as error:
            # A lost response can still have incurred model usage; there is no automatic retry.
            report["blocked"] = {"case_id": definition["caseId"], "run_id": request["runId"],
                                 "error_type": type(error).__name__,
                                 "usage_status": "UNKNOWN" if getattr(error, "submitted", True) else "NOT_CALLED"}
            if hasattr(error, "exit_code"):
                report["blocked"].update(reason=error.reason, exit_code=error.exit_code)
            if isinstance(error, urllib.error.HTTPError):
                report["blocked"]["http_status"] = error.code
            break
    report["completed_count"] = len(report["cases"])
    report["completed_at"] = datetime.now(timezone.utc).isoformat()
    if len(report["cases"]) == len(runs) and all(row["passed"] for row in report["cases"]):
        report["status"] = "PASS"
    return report


def run_paired_cases(execution: dict, execution_file_sha256: str, invoke, comparison_manifest: dict,
                     acceptance_gate: dict, evaluator_key: bytes, *, holdout_ledger: Path | None = None,
                     selection: dict | None = None, started_at: str | None = None) -> tuple[dict, dict]:
    from evolution_acceptance import validate_comparison_gate, holdout_reservation
    from evolution_experiment import BudgetStop
    gate = validate_comparison_gate(comparison_manifest, acceptance_gate, evaluator_key)
    if execution.get("schemaVersion") != 2 or execution.get("context") != {
            "experimentId": gate["experimentId"], "evaluatorVersion": gate["review"]["evaluatorSha256"],
            "runMode": comparison_manifest["evaluationSplit"]}:
        raise ValueError("Paired execution requires the registered E07 experiment/evaluator/split context")
    bundles = [comparison_manifest[arm + "BundleId"] for arm in ("baseline", "candidate")]
    for bundle in bundles:
        run_cases(execution, bundle, execution_file_sha256, invoke, comparison_manifest, validate_only=True)
    reservation = None
    if comparison_manifest["evaluationSplit"] == "HOLDOUT":
        if holdout_ledger is None:
            raise ValueError("Holdout execution requires the independent evaluator's persistent --holdout-ledger")
        reservation = holdout_reservation(comparison_manifest, acceptance_gate, evaluator_key, holdout_ledger, reserve=True, selection=selection)
    registered = {(row["caseId"], row["repeatId"], row["bundleId"]): row for row in execution["runs"]}
    outcomes, order = {}, []
    started = started_at or datetime.now(timezone.utc).isoformat()
    pairs = [(case["caseId"], repeat) for case in comparison_manifest["cases"] for repeat in case["repeatIds"]]
    stopped = False
    for index, (case_id, repeat) in enumerate(pairs):
        for bundle in bundles if index % 2 == 0 else reversed(bundles):
            run = registered[(case_id, repeat, bundle)]
            request = {key: run[key] for key in ("caseId", "bundleId", "runId")}
            order.append(run["runId"])
            try:
                result = invoke(request)
                if not isinstance(result, dict):
                    raise ValueError("Replay endpoint returned a non-object response")
                outcomes[run["runId"]] = result
            except (OSError, ValueError, KeyError, TypeError) as error:
                outcomes[run["runId"]] = error
                if getattr(error, 'submitted', True) is False:
                    order.pop()
                stopped = True
                break
        if stopped:
            break  # Unknown remote usage prevents further submissions; never reissue the ambiguous call.
    completed = datetime.now(timezone.utc).isoformat()

    def recorded(request):
        value = outcomes.get(request["runId"])
        if value is None:
            raise BudgetStop("PAIRED_EXECUTION_STOPPED_BEFORE_THIS_RUN")
        if isinstance(value, Exception):
            raise value
        return value

    reports = []
    for bundle in bundles:
        report = run_cases(execution, bundle, execution_file_sha256, recorded, comparison_manifest, continue_on_failure=True)
        report.update(started_at=started, completed_at=completed, run_order="ALTERNATING", invocation_order=order)
        if reservation is not None:
            report["holdout_reservation_sha256"] = json_hash(reservation)
        reports.append(report)
    return tuple(reports)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--bundle-id", default="baseline-v1")
    parser.add_argument("--endpoint", default="http://localhost:8080/api/eval/evolution/replay")
    parser.add_argument("--timeout-seconds", type=int, default=300)
    parser.add_argument("--max-runs", type=int, default=12)
    parser.add_argument("--comparison-manifest", type=Path,
                        help="Freeze and bind the independent comparison plan before any model call")
    parser.add_argument("--paired", action="store_true", help="Run both v2 arms in alternating order; --output is a new directory")
    parser.add_argument("--acceptance-gate", type=Path)
    parser.add_argument("--holdout-ledger", type=Path)
    parser.add_argument("--selection", type=Path)
    parser.add_argument("--experiment-ledger", type=Path, help="Shared evaluator-owned SQLite resource ledger; required for paired runs")
    parser.add_argument("--resource-reservations", type=Path, help="Frozen per-role token/cost reservations and rate card")
    parser.add_argument("--stop-file", type=Path, help="Stop before the next new submission when this file exists")
    args = parser.parse_args()
    if args.output.exists():
        parser.error("Output already exists; use a new artifact path to preserve previous runs")
    if args.timeout_seconds < 1 or args.max_runs < 1:
        parser.error("Timeout and maximum invocation count must be positive")
    if args.paired and (not args.comparison_manifest or not args.acceptance_gate or not args.experiment_ledger or not args.resource_reservations):
        parser.error("--paired requires --comparison-manifest, --acceptance-gate, --experiment-ledger and --resource-reservations")
    try:
        raw = args.input.read_bytes()
        execution = json.loads(raw.decode("utf-8"))
        if len([run for run in execution.get("runs", []) if args.paired or run.get("bundleId") == args.bundle_id]) > args.max_runs:
            raise ValueError("Execution file exceeds --max-runs; declare a larger limit before running")
        token = os.environ.get("STOCKSAGE_ADMIN_TOKEN", "")

        def invoke(body):
            if not token:
                raise ValueError("MISSING_ADMIN_TOKEN")
            request = urllib.request.Request(args.endpoint,
                    data=json.dumps(body, ensure_ascii=False).encode("utf-8"), method="POST",
                    headers={"Content-Type": "application/json",
                             os.environ.get("STOCKSAGE_ADMIN_HEADER_NAME", "X-StockSage-Admin-Token"): token})
            with urllib.request.urlopen(request, timeout=args.timeout_seconds) as response:
                result = json.load(response)
                if not isinstance(result, dict):
                    raise ValueError("Replay endpoint returned a non-object response")
                return result

        comparison = json.loads(args.comparison_manifest.read_text(encoding="utf-8")) if args.comparison_manifest else None
        if args.paired:
            from evolution_acceptance import read_key, validate_comparison_gate
            from evolution_experiment import ExperimentLedger, replay_usage
            gate = json.loads(args.acceptance_gate.read_text(encoding="utf-8"))
            selection = json.loads(args.selection.read_text(encoding="utf-8")) if args.selection else None
            evaluator_key = read_key()
            validate_comparison_gate(comparison, gate, evaluator_key)
            if not token:
                parser.exit(3, "MISSING_ADMIN_TOKEN: configure the dedicated evaluation instance token before submitting replay calls.\n")
            ledger = ExperimentLedger(args.experiment_ledger, gate, evaluator_key,
                                      json.loads(args.resource_reservations.read_text(encoding="utf-8")))
            plan_identity = {"executionFileSha256": hashlib.sha256(raw).hexdigest(), "comparisonManifestSha256": json_hash(comparison)}
            started_at = ledger.bind_plan(plan_identity)
            args.output.mkdir(parents=True, exist_ok=False)
            with (args.output / "invocations.jsonl").open("x", encoding="utf-8") as journal:
                def record(value):
                    journal.write(json.dumps(value, ensure_ascii=False) + "\n")
                    journal.flush()
                    os.fsync(journal.fileno())

                def tracked_invoke(request):
                    record({"request": request, "status": "SUBMITTED", "at": datetime.now(timezone.utc).isoformat()})
                    try:
                        response = invoke(request)
                    except (OSError, ValueError, KeyError, TypeError) as error:
                        record({"request": request, "status": "UNKNOWN", "error_type": type(error).__name__,
                                "at": datetime.now(timezone.utc).isoformat()})
                        raise
                    record({"request": request, "status": response.get("status"), "response": response,
                            "at": datetime.now(timezone.utc).isoformat()})
                    return response

                def budgeted_invoke(request):
                    sent = False
                    def submit(_):
                        nonlocal sent
                        sent = True
                        return tracked_invoke(request)
                    response = ledger.call(request['runId'], {**plan_identity, 'request': request}, ('ANALYST', 'FINAL_ANSWER'),
                                           submit, replay_usage, stop_requested=lambda: args.stop_file is not None and args.stop_file.exists())
                    if not sent:
                        record({'request': request, 'status': 'RESTORED', 'at': datetime.now(timezone.utc).isoformat()})
                    return response

                baseline, candidate = run_paired_cases(execution, hashlib.sha256(raw).hexdigest(), budgeted_invoke, comparison,
                                                       gate, evaluator_key, holdout_ledger=args.holdout_ledger, selection=selection, started_at=started_at)
            for name, result in (("baseline", baseline), ("candidate", candidate)):
                with (args.output / (name + ".json")).open("x", encoding="utf-8") as output:
                    output.write(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
            resources = ledger.export_review(args.output / 'resource-review')
            complete = resources['status'] == 'READY' and all(report['status'] == 'PASS' for report in (baseline, candidate))
            code = 0 if complete else max([3] + [report.get('blocked', {}).get('exit_code', 3) for report in (baseline, candidate)])
            reason = resources['reason'] or next((report.get('blocked', {}).get('reason') for report in (baseline, candidate)
                                                  if report.get('blocked', {}).get('reason')), None)
            print(json.dumps({"status": "PASS" if complete else "BUDGET_EXHAUSTED" if code == 4 else "INCOMPLETE",
                              "reason": reason, "resource_status": resources['status'],
                              "output": str(args.output)}))
            return code
        report = run_cases(execution, args.bundle_id, hashlib.sha256(raw).hexdigest(), invoke, comparison)
        if not token:
            report["blocked"] = {"reason": "MISSING_ADMIN_TOKEN",
                                 "next_step": "Configure STOCKSAGE_ADMIN_TOKEN and start the evolution-eval service.",
                                 "usage_status": "NOT_CALLED"}
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with args.output.open("x", encoding="utf-8") as output:
            output.write(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(2, f"Invalid replay input or output: {type(error).__name__}: {error}\n")
    print(json.dumps({"status": report["status"], "completed": report["completed_count"],
                      "output": str(args.output)}))
    return 0 if report["status"] == "PASS" else 3 if report["status"] == "BLOCKED" else 5


if __name__ == "__main__":
    raise SystemExit(main())
