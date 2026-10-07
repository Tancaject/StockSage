"""Independent evaluator's frozen gates. Signing keys never belong to the optimizer.

Hashes bind artifacts; the signature authenticates the evaluator that reviewed
them. Runtime isolation and human independence still require deployment controls.
"""
from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import math
import os
import sqlite3
from contextlib import closing
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
from pathlib import Path

from evolution_dataset import exact_fields, nonempty, timestamp, validate_cases, validate_gold, case_hash, REAL_ORIGIN
from evolution_rubric import STAGES
from evolution_quality import HARD_GATES, input_errors
from evolution_rubric import DIMENSIONS
from eval_common import json_hash

POLICY_SCHEMA = "fundamentals_acceptance_policy_v1"
SIGNED_SCHEMA = "fundamentals_evaluator_artifact_v1"
ROLES = ("ANALYST", "FINAL_ANSWER", "GENERATOR", "JUDGE")
GUARD_METRICS = ("numeric", "citation_support", "task_coverage", "appropriate_refusal", "appropriate_clarification", "answerable_response")
SPLITS = ("DEVELOPMENT", "VALIDATION", "HOLDOUT")


def evaluator_hash() -> str:
    names = ("evolution_acceptance.py", "evolution_compare.py", "evolution_quality.py",
             "evolution_dataset.py", "eval_common.py", "evolution_rubric.py", "ordinary_answer_quality.py",
             "evolution_experiment.py", "run_evolution_replay.py",
             "run_evolution_experiment.py", "evolution_generator.py", "evolution_candidates.py", "evolution_experiences.py",
             "evolution_reflection.py", "evolution_review.py", "evolution_numeric_check.py")
    return json_hash({name: hashlib.sha256(Path(__file__).with_name(name).read_bytes()).hexdigest() for name in names})


def require_hash(value, field: str) -> None:
    if not isinstance(value, str) or len(value) != 64 or any(char not in "0123456789abcdef" for char in value):
        raise ValueError(field + " must be a lowercase SHA-256 digest")


def money(value, field: str, *, nullable=False) -> Decimal | None:
    if value is None and nullable:
        return None
    if not isinstance(value, str):
        raise ValueError(field + " must be a nonnegative decimal string")
    try:
        result = Decimal(value)
        if not result.is_finite() or result < 0:
            raise ValueError()
    except (InvalidOperation, ValueError) as error:
        raise ValueError(field + " must be a nonnegative finite decimal string") from error
    return result


def validate_policy(policy: dict) -> None:
    exact_fields(policy, {"schema", "experimentId", "rationale", "primaryDimension", "minMeaningfulDelta",
                         "nonRegressionMargins", "minimumGroups", "bootstrapSamples", "confidenceLevel", "seed",
                         "repeats", "cohorts", "limits", "runOrder", "retryPolicy"}, "acceptance policy")
    if policy["schema"] != POLICY_SCHEMA:
        raise ValueError("Unsupported acceptance policy schema")
    nonempty(policy["experimentId"], "experimentId")
    nonempty(policy["rationale"], "policy rationale based on baseline variability")
    if policy["primaryDimension"] not in DIMENSIONS:
        raise ValueError("primaryDimension must use the frozen four-dimensional rubric")
    for name in ("minMeaningfulDelta", "confidenceLevel"):
        value = policy[name]
        if (type(value) not in (int, float) or not math.isfinite(value) or not 0 < value <= 1
                or name == "confidenceLevel" and value == 1):
            raise ValueError(name + " must be positive and at most one; confidenceLevel must be strictly below one")
    for name, low, high in (("minimumGroups", 2, 100000), ("bootstrapSamples", 100, 100000)):
        if type(policy[name]) is not int or not low <= policy[name] <= high:
            raise ValueError(name + " is outside its valid integer range")
    if type(policy["seed"]) is not int or policy["runOrder"] != "ALTERNATING" or policy["retryPolicy"] != "NO_AUTOMATIC_RETRY":
        raise ValueError("Declare a fixed random seed, alternating arms and no automatic retries")
    margins = policy["nonRegressionMargins"]
    exact_fields(margins, set(DIMENSIONS) | set(GUARD_METRICS), "nonRegressionMargins")
    for name, value in margins.items():
        if type(value) not in (int, float) or not math.isfinite(value) or not 0 <= value < 1:
            raise ValueError("Invalid non-regression margin: " + name)
    # Critical facts, coverage and refusal behavior cannot be traded for rubric gains.
    if any(margins[name] != 0 for name in GUARD_METRICS):
        raise ValueError("Numerical facts, citation support, task coverage and refusal guards require zero regression")
    exact_fields(policy["repeats"], set(SPLITS), "repeats")
    exact_fields(policy["cohorts"], set(SPLITS), "cohorts")
    assigned = set()
    for split in SPLITS:
        if type(policy["repeats"][split]) is not int or policy["repeats"][split] < 1:
            raise ValueError("Each split needs a positive repeat count")
        cohort = policy["cohorts"][split]
        exact_fields(cohort, {"caseIds", "keyGroups"}, "cohort")
        ids = cohort["caseIds"]
        if not isinstance(ids, list) or not ids or any(not isinstance(key, str) or not key for key in ids) or len(set(ids)) != len(ids):
            raise ValueError("Each cohort needs nonempty unique case IDs")
        if assigned.intersection(ids):
            raise ValueError("Policy cohorts overlap")
        assigned.update(ids)
        if not isinstance(cohort["keyGroups"], dict) or (split != "DEVELOPMENT" and not cohort["keyGroups"]):
            raise ValueError("Validation and holdout need explicitly declared key groups")
        for name, members in cohort["keyGroups"].items():
            nonempty(name, "key group")
            if (not isinstance(members, list) or not members or any(not isinstance(key, str) for key in members)
                    or len(set(members)) != len(members) or not set(members) <= set(ids)):
                raise ValueError("Key-group members must be distinct cases in their declared cohort")
    limits = policy["limits"]
    exact_fields(limits, {"maxCandidates", "maxRounds", "maxModelCalls", "maxTotalTokens",
                         "maxExperimentCostUsd", "maxMeanCandidateCostUsd", "maxCandidateP95LatencyMs"}, "limits")
    for name in ("maxCandidates", "maxRounds", "maxTotalTokens", "maxCandidateP95LatencyMs"):
        if type(limits[name]) is not int or limits[name] < 1:
            raise ValueError(name + " must be a positive integer")
    exact_fields(limits["maxModelCalls"], set(ROLES), "maxModelCalls")
    if any(type(value) is not int or value < 0 for value in limits["maxModelCalls"].values()):
        raise ValueError("Call limits must be nonnegative integers")
    for name in ("maxExperimentCostUsd", "maxMeanCandidateCostUsd"):
        money(limits[name], name, nullable=True)


def seal(payload: dict, key: bytes) -> dict:
    if not isinstance(key, bytes) or len(key) < 32:
        raise ValueError("Evaluator signing key needs at least 32 bytes in its protected environment")
    digest = json_hash(payload)
    signature = hmac.new(key, (SIGNED_SCHEMA + "\n" + digest).encode(), hashlib.sha256).hexdigest()
    return {"schema": SIGNED_SCHEMA, "payload": payload, "payloadSha256": digest, "signature": signature}


def verified(artifact: dict, key: bytes, kind: str) -> dict:
    exact_fields(artifact, {"schema", "payload", "payloadSha256", "signature"}, "signed evaluator artifact")
    expected = seal(artifact["payload"], key)
    if (artifact["schema"] != SIGNED_SCHEMA or artifact["payloadSha256"] != expected["payloadSha256"]
            or not isinstance(artifact["signature"], str) or not hmac.compare_digest(artifact["signature"], expected["signature"])
            or artifact["payload"].get("kind") != kind):
        raise ValueError("Evaluator artifact signature, content or purpose does not match")
    return artifact["payload"]


def provider_usage(stage: dict, name: str) -> tuple[int, int] | None:
    rows = [row for row in stage.get("observations", [])
            if row.get("kind") == "model-usage" and row.get("scope") == STAGES[name][1]]
    if len(rows) != 1 or rows[0].get("usageSource") != "PROVIDER":
        return None
    values = (rows[0].get("inputTokens"), rows[0].get("outputTokens"))
    return values if all(type(value) is int and value >= 0 for value in values) else None


def validate_run_context(case: dict, experiment_id: str, mode: str) -> None:
    replay, definition = case["replay"], case["case_definition"]
    identity, context = replay.get("comparisonIdentity") or {}, replay.get("runContext") or {}
    build = identity.get("runtimeBuild") or {}
    artifact, manifest = build.get("runtimeArtifact") or {}, build.get("manifest") or {}
    if (build.get("status") != "VERIFIED_ARTIFACT" or artifact.get("status") != "KNOWN"
            or artifact.get("scope") != "CODE_SOURCE_FILE" or manifest.get("artifactFormat") != "SPRING_BOOT_JAR"
            or manifest.get("artifactSha256") != artifact.get("sha256")
            or manifest.get("schema") != "fundamentals_evolution_build_v1"
            or manifest.get("sourceState") != "WORKTREE_SNAPSHOT"
            or json_hash(build) != identity.get("runtimeBuildSha256")):
        raise ValueError("Replay lacks a verified packaged runtime/build identity")
    require_hash(manifest.get("artifactSha256"), "runtime artifact")
    require_hash(manifest.get("sourceTreeSha256"), "build source tree")
    git = manifest.get("gitSha")
    if not isinstance(git, str) or len(git) not in {40, 64} or any(char not in "0123456789abcdef" for char in git):
        raise ValueError("Replay build lacks its source Git revision")
    expected = {"schemaVersion": 1, "experimentId": experiment_id, "evaluatorVersion": evaluator_hash(), "runMode": mode,
                "runId": replay["runId"], "caseId": definition["caseId"], "repeatId": definition["repeatId"], "gitSha": git,
                "runtimeBuildSha256": identity["runtimeBuildSha256"], "bundleHash": replay["methodBundle"]["bundleSha256"],
                "modelConfigHash": identity["modelConfigSha256"], "evidenceSnapshotHash": definition["contextSha256"],
                "historySnapshotHash": identity["historySnapshotSha256"], "memorySnapshotHash": identity["memorySnapshotSha256"],
                "fixedFinalPromptHash": identity["fixedFinalPromptSha256"]}
    if context != expected:
        raise ValueError("Replay experiment, evaluator, execution identity or build provenance is missing/changed")


def freeze_baseline(report: dict, sources: list[dict], gold: list[dict], policy: dict,
                    review: dict, evidence_artifacts: dict[str, bytes], key: bytes) -> dict:
    from evolution_compare import revalidate_reviews, model_identity, V2_IDENTITIES
    validate_policy(policy)
    source_index = validate_cases(sources)
    validate_gold(sources, gold)
    expected_cases = {case for cohort in policy["cohorts"].values() for case in cohort["caseIds"]}
    if set(source_index) != expected_cases or any(source_index[case]["split"] != split
            for split, cohort in policy["cohorts"].items() for case in cohort["caseIds"]):
        raise ValueError("Policy cohorts must bind the exact independently split source dataset")
    if any(case["origin"] != REAL_ORIGIN for case in sources):
        raise ValueError("Synthetic sources cannot authorize an optimization baseline")
    reviewed = revalidate_reviews(report, sources, gold)
    planned = {f"{case}@{repeat}" for split in ("DEVELOPMENT", "VALIDATION")
               for case in policy["cohorts"][split]["caseIds"] for repeat in range(1, policy["repeats"][split] + 1)}
    if report.get("status") != "PASS" or {case["id"] for case in report["cases"]} != planned or report["sample_count"] != len(planned):
        raise ValueError("E07 needs every predeclared development/validation baseline run to finish")
    identities, bundles, observed_models = [], [], []
    for case in reviewed["cases"]:
        replay = case["replay"]
        validate_run_context(case, policy["experimentId"], "BASELINE")
        identities.append({name: replay.get("comparisonIdentity", {}).get(name) for name in V2_IDENTITIES})
        bundles.append(replay.get("methodBundle"))
        observed_models.append({name: model_identity(replay[name], name) for name in STAGES})
        for name in STAGES:
            assessment = case["quality_review"]["stages"][name]
            if (assessment["errors"] or assessment["status"] not in {"PASS", "FAIL"}
                    or any(assessment["hard_gates"][gate]["status"] != "PASS" for gate in HARD_GATES)
                    or model_identity(replay[name], name) is None or provider_usage(replay[name], name) is None):
                raise ValueError("E07 lacks reviewed labels, hard-gate evidence, actual model identity or provider token usage")
    if (any(value != identities[0] for value in identities) or any(value != bundles[0] for value in bundles)
            or any(value != observed_models[0] for value in observed_models)):
        raise ValueError("E07 baseline mixes method or execution configurations")
    for name, digest in identities[0].items():
        require_hash(digest, name)
    exact_fields(review, {"reviewer", "reviewedAt", "evidenceLevel", "baselineReportSha256", "sourceCasesSha256",
                          "goldSha256", "policySha256", "evaluatorSha256", "checks", "findings"}, "baseline review")
    nonempty(review["reviewer"], "baseline reviewer")
    timestamp(review["reviewedAt"], "baseline reviewedAt")
    if review["evidenceLevel"] not in {"UNIT_TEST", "SNAPSHOT_LIVE_MODEL", "END_TO_END_REPLAY", "AUTHORIZED_LIVE"}:
        raise ValueError("Baseline review requires an explicit evidence level")
    for name, value in (("baselineReportSha256", json_hash(report)), ("sourceCasesSha256", json_hash(sources)),
                        ("goldSha256", json_hash(gold)), ("policySha256", json_hash(policy)), ("evaluatorSha256", evaluator_hash())):
        if review[name] != value:
            raise ValueError("Baseline review changed: " + name)
    exact_fields(review["checks"], {"negativeControls", "isolation", "reviewCalibration", "samplingPlan"}, "baseline checks")
    for name, check in review["checks"].items():
        exact_fields(check, {"status", "artifactSha256"}, "baseline check")
        data = evidence_artifacts.get(check["artifactSha256"])
        if check["status"] != "PASS" or not isinstance(data, bytes) or hashlib.sha256(data).hexdigest() != check["artifactSha256"]:
            raise ValueError("Baseline check evidence is absent, changed or not passed: " + name)
    if not isinstance(review["findings"], list) or not review["findings"]:
        raise ValueError("Record baseline limitations, failure attribution and reviewer disagreement in findings")
    for item in review["findings"]:
        nonempty(item, "baseline finding")
    payload = {"kind": "BASELINE_ACCEPTANCE", "experimentId": policy["experimentId"], "policy": policy,
               "status": "MECHANISM_READY" if review["evidenceLevel"] == "UNIT_TEST" else "ACCEPTED",
               "issuedAt": datetime.now(timezone.utc).isoformat(), "review": review,
               "baselineBundle": bundles[0], "expectedComparisonIdentity": identities[0],
               "caseHashes": {case_id: case_hash(case) for case_id, case in source_index.items()},
               "observedModelIdentities": observed_models[0],
               "holdoutKeys": sorted({item for case in sources if case["split"] == "HOLDOUT" for item in
                                      ["family:" + json_hash([case["issuerId"], case["reportFamilyId"]]),
                                       "group:" + json_hash(case["leakageGroup"]),
                                       *("raw:" + row["rawSha256"] for row in case["evidence"]),
                                       *("source:" + json_hash(row["sourceRef"]) for row in case["evidence"])]})}
    return seal(payload, key)


def require_gate(artifact: dict, key: bytes, *, allow_mechanism=False) -> dict:
    gate = verified(artifact, key, "BASELINE_ACCEPTANCE")
    validate_policy(gate["policy"])
    if gate["review"]["policySha256"] != json_hash(gate["policy"]) or gate["experimentId"] != gate["policy"]["experimentId"]:
        raise ValueError("Signed baseline review and policy identity disagree")
    if gate["status"] != "ACCEPTED" and not (allow_mechanism and gate["status"] == "MECHANISM_READY"):
        raise ValueError("Only a real independently accepted E07 baseline authorizes optimization")
    if gate["review"]["evaluatorSha256"] != evaluator_hash():
        raise ValueError("Evaluator changed since E07; repeat independent acceptance with the new evaluator")
    return gate


def validate_comparison_gate(manifest: dict, artifact: dict, key: bytes) -> dict:
    from evolution_compare import validate_manifest
    validate_manifest(manifest)
    gate = require_gate(artifact, key)
    policy = gate["policy"]
    if manifest.get("acceptanceGateSha256") != artifact["payloadSha256"]:
        raise ValueError("Comparison manifest does not match its pre-search acceptance gate")
    for field in ("minimumGroups", "bootstrapSamples", "confidenceLevel", "seed", "primaryDimension"):
        if manifest[field] != policy[field]:
            raise ValueError("Comparison changes a frozen E07 parameter: " + field)
    if (set(manifest["nonRegressionDimensions"]) != set(DIMENSIONS)
            or manifest["expectedComparisonIdentity"] != gate["expectedComparisonIdentity"]
            or manifest["baselineBundleId"] != gate["baselineBundle"]["bundleId"]
            or manifest["baselineBundleSha256"] != gate["baselineBundle"]["bundleSha256"]
            or manifest["fixedContractSha256"] != gate["baselineBundle"]["fixedContractSha256"]):
        raise ValueError("Comparison must retain the frozen rubric, baseline and execution identities")
    split = manifest["evaluationSplit"]
    cohort = policy["cohorts"][split]
    if {row["caseId"] for row in manifest["cases"]} != set(cohort["caseIds"]):
        raise ValueError("Comparison does not cover the frozen cohort")
    for row in manifest["cases"]:
        if row["caseSha256"] != gate["caseHashes"][row["caseId"]] or row["repeatIds"] != list(range(1, policy["repeats"][split] + 1)):
            raise ValueError("Comparison changes frozen cases or repetitions")
    planned_calls = 2 * sum(len(row["repeatIds"]) for row in manifest["cases"])
    if any(planned_calls > policy["limits"]["maxModelCalls"][role] for role in ("ANALYST", "FINAL_ANSWER")):
        raise ValueError("Paired comparison alone exceeds the frozen role call budget")
    return gate


def comparison_report_hash(report: dict) -> str:
    """Bind all reported decisions/metrics without a circular reference to their signature."""
    return json_hash({name: value for name, value in report.items() if name not in {'releaseEvidence', 'signedReleaseEvidence'}})


def verified_comparison(report: dict, key: bytes) -> dict:
    evidence = verified(report['signedReleaseEvidence'], key, 'RELEASE_EVIDENCE')
    if (report.get('schema') != 'fundamentals_evolution_comparison_v2'
            or evidence.get('evaluatorSha256') != evaluator_hash()
            or evidence.get('comparisonReportSha256') != comparison_report_hash(report)
            or evidence.get('comparisonStatus') != report.get('status')
            or {name: value for name, value in evidence.items() if name != 'kind'} != report.get('releaseEvidence')):
        raise ValueError('Comparison decisions/metrics changed or lack the current evaluator report binding')
    return evidence


def select_candidate(validation_result: dict, key: bytes, reviewer: str, reason: str) -> dict:
    evidence = verified_comparison(validation_result, key)
    if (evidence["evaluationSplit"] != "VALIDATION" or evidence["comparisonStatus"] != "IMPROVED"
            or evidence["evaluatorSha256"] != evaluator_hash()
            or {name: value for name, value in evidence.items() if name != "kind"} != validation_result["releaseEvidence"]):
        raise ValueError("Select one candidate from unchanged, independently accepted validation evidence")
    nonempty(reviewer, "selection reviewer")
    nonempty(reason, "selection reason")
    return seal({"kind": "CANDIDATE_SELECTION", "gateSha256": evidence["acceptanceGateSha256"],
                 "candidateBundleSha256": evidence["candidateBundleSha256"],
                 "validationEvidenceSha256": validation_result["signedReleaseEvidence"]["payloadSha256"],
                 "selectedAt": datetime.now(timezone.utc).isoformat(), "reviewer": reviewer, "reason": reason}, key)


def holdout_reservation(manifest: dict, gate_artifact: dict, key: bytes, path: Path, *, reserve=False, selection=None) -> dict:
    gate = validate_comparison_gate(manifest, gate_artifact, key)
    if manifest["evaluationSplit"] != "HOLDOUT":
        raise ValueError("Only a HOLDOUT plan can reserve final-acceptance sources")
    if selection is None:
        raise ValueError("Holdout requires the independent evaluator's selected-candidate artifact")
    chosen = verified(selection, key, "CANDIDATE_SELECTION")
    if (manifest["selectionSha256"] != selection["payloadSha256"] or chosen["gateSha256"] != gate_artifact["payloadSha256"]
            or chosen["candidateBundleSha256"] != manifest["candidateBundleSha256"]):
        raise ValueError("Holdout candidate differs from the frozen validation selection")
    timestamp(chosen["selectedAt"], "selectedAt")
    # Persistent ownership is the dataset/cohort, not the experiment ID that a new search can replace.
    ownership_keys = gate["holdoutKeys"]
    identity = json_hash(ownership_keys)
    record = {"holdoutSha256": identity, "manifestSha256": json_hash(manifest),
              "candidateBundleSha256": manifest["candidateBundleSha256"]}
    if not reserve and not path.is_file():
        raise ValueError("Holdout was not reserved before model execution")
    if reserve:
        path.parent.mkdir(parents=True, exist_ok=True)
    with closing(sqlite3.connect(path)) as db:
        if reserve:
            with db:
                db.execute("CREATE TABLE IF NOT EXISTS holdout (id TEXT PRIMARY KEY, manifest TEXT NOT NULL, candidate TEXT NOT NULL)")
                db.execute("BEGIN IMMEDIATE")
                for owner in ownership_keys:
                    row = db.execute("SELECT manifest,candidate FROM holdout WHERE id=?", (owner,)).fetchone()
                    if row is not None and row != (record["manifestSha256"], record["candidateBundleSha256"]):
                        raise ValueError("Holdout source/group is already consumed; use a new independent holdout")
                    db.execute("INSERT OR IGNORE INTO holdout VALUES (?, ?, ?)", (owner, record["manifestSha256"], record["candidateBundleSha256"]))
        for owner in ownership_keys:
            row = db.execute("SELECT manifest,candidate FROM holdout WHERE id=?", (owner,)).fetchone()
            if row != (record["manifestSha256"], record["candidateBundleSha256"]):
                raise ValueError("Holdout is absent or already consumed by a different candidate/plan; use a new independent holdout")
    return record


def seal_resource_audit(body: dict, evidence_artifacts: dict[str, bytes], key: bytes) -> dict:
    if body.get("kind") != "RESOURCE_AUDIT":
        raise ValueError("Expected a resource audit")
    try:
        ledger = json.loads(evidence_artifacts.get(body['ledgerSha256'], b''))
    except (ValueError, UnicodeDecodeError):
        ledger = None
    history_proofs = set()
    if isinstance(ledger, dict) and ledger.get('schema') == 'fundamentals_resource_ledger_v1':
        from evolution_experiment import action_history_proofs, search_history_proofs
        history_proofs = action_history_proofs(ledger) | search_history_proofs(ledger)
    for digest in [body["ledgerSha256"], *(entry["proofSha256"] for entry in body["entries"]), *sorted(history_proofs)]:
        data = evidence_artifacts.get(digest)
        if not isinstance(data, bytes):
            raise ValueError("Resource audit is missing the ledger or an invocation proof")
        if hashlib.sha256(data).hexdigest() != digest:
            try:
                canonical = json_hash(json.loads(data))
            except (ValueError, UnicodeDecodeError):
                canonical = None
            if canonical != digest:
                raise ValueError("Resource audit proof changed")
    if isinstance(ledger, dict) and ledger.get('schema') == 'fundamentals_resource_ledger_v1':
        from evolution_experiment import resource_entries
        if (ledger['experimentId'] != body['experimentId'] or ledger['acceptanceGateSha256'] != body['gateSha256']
                or json_hash(body['entries']) != json_hash(resource_entries(ledger))):
            raise ValueError('Resource audit cannot omit, reorder or change the recorded experiment invocations')
    return seal(body, key)


def compare_resources(audit: dict, gate_artifact: dict, key: bytes, reports: tuple[dict, dict]) -> dict:
    """The evaluator attests ledger completeness; observed replay usage is checked independently."""
    body = verified(audit, key, "RESOURCE_AUDIT")
    exact_fields(body, {"kind", "experimentId", "gateSha256", "ledgerSha256", "complete", "reviewer", "reviewedAt",
                        "candidateCount", "searchRounds", "entries", "runOutcomes"}, "resource audit")
    if body["gateSha256"] != gate_artifact["payloadSha256"] or body["experimentId"] != gate_artifact["payload"]["experimentId"]:
        raise ValueError("Resource audit belongs to another experiment or frozen gate")
    nonempty(body["reviewer"], "resource reviewer")
    timestamp(body["reviewedAt"], "resource reviewedAt")
    require_hash(body["ledgerSha256"], "ledgerSha256")
    limits = gate_artifact["payload"]["policy"]["limits"]
    missing, failures, calls, tokens, cost = [], [], dict.fromkeys(ROLES, 0), 0, Decimal(0)
    if body["complete"] is not True:
        missing.append("EXPERIMENT_LEDGER_INCOMPLETE")
    for field, limit in (("candidateCount", "maxCandidates"), ("searchRounds", "maxRounds")):
        if type(body[field]) is not int or body[field] < 1:
            raise ValueError("Resource audit needs positive " + field)
        if body[field] > limits[limit]:
            failures.append(limit + "_EXCEEDED")
    entries, ids = {}, set()
    if not isinstance(body["entries"], list) or not isinstance(body["runOutcomes"], dict):
        raise ValueError("Resource audit requires invocation entries and run outcomes")
    for entry in body["entries"]:
        exact_fields(entry, {"invocationId", "runId", "role", "status", "inputTokens", "outputTokens", "costUsd", "proofSha256"}, "usage entry")
        nonempty(entry["invocationId"], "invocationId")
        nonempty(entry["runId"], "runId")
        require_hash(entry["proofSha256"], "proofSha256")
        if entry["invocationId"] in ids or entry["role"] not in ROLES or entry["status"] not in {"COMPLETED", "FAILED", "UNKNOWN"}:
            raise ValueError("Usage entries require distinct IDs and known roles/statuses")
        ids.add(entry["invocationId"])
        calls[entry["role"]] += 1
        entries.setdefault((entry["runId"], entry["role"]), []).append(entry)
        if entry["status"] == "UNKNOWN":
            missing.append("UNKNOWN_INVOCATION_USAGE")
        for field in ("inputTokens", "outputTokens"):
            value = entry[field]
            if value is None:
                missing.append("UNKNOWN_TOKEN_USAGE")
            elif type(value) is not int or value < 0:
                raise ValueError("Token usage must be a nonnegative integer or null")
            else:
                tokens += value
        charge = money(entry["costUsd"], "costUsd", nullable=True)
        if charge is None:
            missing.append("UNKNOWN_INVOCATION_COST")
        else:
            cost += charge
    for role, count in calls.items():
        if count > limits["maxModelCalls"][role]:
            failures.append(role + "_CALL_LIMIT_EXCEEDED")
    if tokens > limits["maxTotalTokens"]:
        failures.append("TOKEN_LIMIT_EXCEEDED")
    cost_limit = money(limits["maxExperimentCostUsd"], "maxExperimentCostUsd", nullable=True)
    if cost_limit is None:
        missing.append("EXPERIMENT_COST_LIMIT_NOT_FROZEN")
    elif cost > cost_limit:
        failures.append("EXPERIMENT_COST_LIMIT_EXCEEDED")
    means, latencies, outcomes = [], [], {"baseline": {}, "candidate": {}}
    for arm, report in zip(("baseline", "candidate"), reports):
        for case in report["cases"]:
            replay = case["replay"]
            run = replay["runId"]
            outcome = body["runOutcomes"].get(run)
            if outcome not in {"COMPLETED", "METHOD_FAILURE", "INFRASTRUCTURE_FAILURE"}:
                missing.append("RUN_ATTRIBUTION_MISSING:" + run)
            if outcome == "METHOD_FAILURE" and arm == "candidate":
                failures.append("CANDIDATE_EXECUTION_FAILURE:" + run)
            if outcome == "INFRASTRUCTURE_FAILURE":
                missing.append("INFRASTRUCTURE_FAILURE:" + run)
            if replay.get("status") == "COMPLETED" and outcome != "COMPLETED":
                missing.append("RUN_ATTRIBUTION_DISAGREES:" + run)
            outcomes[arm][run] = outcome
            run_cost, duration, run_known = Decimal(0), 0, True
            for name, role in (("analysis", "ANALYST"), ("finalAnswer", "FINAL_ANSWER")):
                stage = replay.get(name)
                if stage is None:
                    run_known = False
                    continue
                rows = entries.get((run, role), [])
                if len(rows) != 1 or rows[0]["proofSha256"] != json_hash(stage):
                    missing.append("STAGE_LEDGER_BINDING_MISMATCH:" + run + ":" + name)
                    run_known = False
                    continue
                if rows[0]["status"] != stage.get("status"):
                    missing.append("STAGE_LEDGER_STATUS_MISMATCH:" + run + ":" + name)
                usage = provider_usage(stage, name)
                if usage is None or usage != (rows[0]["inputTokens"], rows[0]["outputTokens"]):
                    missing.append("STAGE_PROVIDER_USAGE_UNAVAILABLE_OR_CHANGED:" + run + ":" + name)
                charge = money(rows[0]["costUsd"], "costUsd", nullable=True)
                if charge is None:
                    run_known = False
                else:
                    run_cost += charge
                elapsed = stage.get("durationMs")
                if type(elapsed) is not int or elapsed < 0:
                    missing.append("STAGE_LATENCY_UNAVAILABLE:" + run + ":" + name)
                    run_known = False
                else:
                    duration += elapsed
            if arm == "candidate" and run_known:
                means.append(run_cost)
                latencies.append(duration)
    from evolution_compare import percentile
    mean_cost = sum(means, Decimal(0)) / len(means) if means else None
    p95 = percentile(sorted(latencies), .95) if latencies else None
    mean_limit = money(limits["maxMeanCandidateCostUsd"], "maxMeanCandidateCostUsd", nullable=True)
    if mean_limit is None or mean_cost is None:
        missing.append("CANDIDATE_COST_UNAVAILABLE")
    elif mean_cost > mean_limit:
        failures.append("CANDIDATE_MEAN_COST_LIMIT_EXCEEDED")
    if p95 is None:
        missing.append("CANDIDATE_LATENCY_UNAVAILABLE")
    elif p95 > limits["maxCandidateP95LatencyMs"]:
        failures.append("CANDIDATE_LATENCY_LIMIT_EXCEEDED")
    return {"failures": sorted(set(failures)), "missing": sorted(set(missing)), "calls": calls,
            "known_tokens": tokens, "known_cost_usd": str(cost), "cost_complete": "UNKNOWN_INVOCATION_COST" not in missing,
            "candidate_mean_cost_usd": str(mean_cost) if mean_cost is not None else None,
            "candidate_component_p95_ms": p95, "run_outcomes": outcomes,
            "candidate_failures": sum(value == "METHOD_FAILURE" for value in outcomes["candidate"].values()),
            "planned_candidate_runs": reports[1]["sample_count"]}


def guard_value(check: dict, metric: str) -> float | None:
    if metric in {"appropriate_refusal", "appropriate_clarification", "answerable_response"}:
        response = check["metrics"]["response"]
        applies = response["expected"] == {"appropriate_refusal": "REFUSE", "appropriate_clarification": "CLARIFY", "answerable_response": "ANSWER"}[metric]
        return int(response["expected"] == response["observed"]) if applies else None
    counts = check["metrics"][metric]
    return counts["passed"] / counts["total"] if counts["total"] else None


def decide_comparison(result: dict, manifest: dict, baseline: dict, candidate: dict,
                       sources: list[dict], gold: list[dict], gate_artifact: dict, resource_audit: dict,
                       key: bytes, holdout_ledger: Path | None = None, selection: dict | None = None,
                       review_audit: dict | None = None) -> dict:
    from evolution_compare import grouped_summary, revalidate_reviews, model_identity
    gate = validate_comparison_gate(manifest, gate_artifact, key)
    policy, split = gate["policy"], manifest["evaluationSplit"]
    source_split = split
    failures, missing = [], []
    review_summary = None
    if review_audit is None:
        missing.append("INDEPENDENT_BLIND_REVIEW_AUDIT_MISSING")
    else:
        from evolution_review import verify_review_audit
        review_summary = verify_review_audit(review_audit, manifest, baseline, candidate, key)['counts']
        if review_summary['unreviewable']:
            missing.append("BLIND_TRIALS_UNREVIEWABLE")
    if (json_hash(sources) != gate["review"]["sourceCasesSha256"] or json_hash(gold) != gate["review"]["goldSha256"]):
        raise ValueError("Independent source dataset or labels changed after E07")
    source_index = validate_cases(sources)
    cohort = policy["cohorts"][source_split]
    declared = {row["caseId"]: row for row in manifest["cases"]}
    if set(declared) != set(cohort["caseIds"]):
        raise ValueError("Comparison does not cover the frozen cohort")
    for case_id, row in declared.items():
        source = source_index[case_id]
        if (source["split"] != source_split or row["issuerId"] != source["issuerId"] or row["reportFamilyId"] != source["reportFamilyId"]
                or row["repeatIds"] != list(range(1, policy["repeats"][source_split] + 1))):
            raise ValueError("Comparison grouping, split or repetitions differ from independent sources/policy")
    for report in (baseline, candidate):
        started = report.get("started_at")
        if not isinstance(started, str):
            missing.append("PRE_SEARCH_GATE_TIME_NOT_PROVEN")
        else:
            timestamp(started, "replay started_at")
            if datetime.fromisoformat(started.replace("Z", "+00:00")) <= datetime.fromisoformat(gate["issuedAt"]):
                missing.append("REPLAY_PREDATES_FROZEN_GATE")
        if report.get("run_order") != "ALTERNATING":
            missing.append("PAIRED_RUN_ORDER_NOT_PROVEN")
    if split == "HOLDOUT":
        if holdout_ledger is None or selection is None:
            missing.append("HOLDOUT_RESERVATION_MISSING")
        else:
            reservation = holdout_reservation(manifest, gate_artifact, key, holdout_ledger, selection=selection)
            if any(report.get("holdout_reservation_sha256") != json_hash(reservation) for report in (baseline, candidate)):
                missing.append("HOLDOUT_RESERVATION_BINDING_MISSING")
            selected_at = datetime.fromisoformat(selection["payload"]["selectedAt"].replace("Z", "+00:00"))
            if any(not report.get("started_at") or datetime.fromisoformat(report["started_at"].replace("Z", "+00:00")) <= selected_at
                   for report in (baseline, candidate)):
                missing.append("HOLDOUT_RUN_PREDATES_CANDIDATE_SELECTION")
    resources = compare_resources(resource_audit, gate_artifact, key, (baseline, candidate))
    failures.extend(resources["failures"])
    # 完成率单独把关：候选两阶段完成数少于基线（超时、截断等），直接判不通过，不混成 INCONCLUSIVE。
    completion = {arm: sum(case["replay"].get("status") == "COMPLETED" for case in report["cases"])
                  for arm, report in (("baseline", baseline), ("candidate", candidate))}
    if completion["candidate"] < completion["baseline"]:
        failures.append("COMPLETION_RATE_REGRESSED")
    missing.extend(resources["missing"])
    missing.extend(reason for reason in result["reasons"] if reason != "RELEASE_DECISION_GATES_NOT_FROZEN")
    lookup = [{case["id"]: case for case in report["cases"]} for report in (baseline, candidate)]
    expected_order = []
    for index, (case_id, repeat) in enumerate((case["caseId"], repeat) for case in manifest["cases"] for repeat in case["repeatIds"]):
        pair = [rows.get(f"{case_id}@{repeat}") for rows in lookup]
        if all(pair):
            expected_order.extend(case["replay"]["runId"] for case in (pair if index % 2 == 0 else reversed(pair)))
    observed_order = list(dict.fromkeys(entry["runId"] for entry in resource_audit["payload"]["entries"]
                                        if entry["runId"] in expected_order))
    if observed_order != expected_order or any(report.get("invocation_order") != expected_order for report in (baseline, candidate)):
        missing.append("ACTUAL_PAIRED_RUN_ORDER_MISMATCH")
    for report in (baseline, candidate):
        for case in report["cases"]:
            try:
                validate_run_context(case, gate["experimentId"], split)
            except ValueError:
                missing.append("RUN_BUILD_OR_EXPERIMENT_CONTEXT_MISSING_OR_CHANGED")
            for name in STAGES:
                if model_identity(case["replay"].get(name) or {}, name) != gate["observedModelIdentities"][name]:
                    missing.append("ACTUAL_MODEL_CHANGED_SINCE_E07:" + name)
    audited = [revalidate_reviews(report, sources, gold) for report in (baseline, candidate)]
    reviews = [{case["id"]: case["quality_review"]["stages"] for case in report["cases"]} for report in audited]
    extended = []
    for pair in result["pairs"]:
        delta = {name: dict(pair["deltas"][name]) for name in STAGES}
        for name in STAGES:
            left, right = (rows[pair["id"]][name]["claim_checks"] for rows in reviews)
            for failure in right["failures"]:
                if failure.startswith("HARD_GATE:") or failure in {"ILLEGAL_CITATION", "EMPTY_ANSWER"}:
                    failures.append("CANDIDATE_HARD_GATE:" + failure)
            for metric in GUARD_METRICS:
                values = [guard_value(check, metric) for check in (left, right)]
                if (values[0] is None) != (values[1] is None):
                    missing.append("METRIC_APPLICABILITY_CHANGED:" + metric)
                delta[name][metric] = values[1] - values[0] if None not in values else None
            a, b = (check["metrics"]["numeric"] for check in (left, right))
            if b["total"] - b["passed"] > a["total"] - a["passed"]:
                failures.append("CRITICAL_NUMERIC_ERRORS_INCREASED:" + pair["id"] + ":" + name)
        extended.append({**pair, "deltas": delta})
    failure_scored = set()
    paired_ids = {pair["id"] for pair in extended}
    for case_id, case in lookup[1].items():
        run = case["replay"]["runId"]
        if (case_id in paired_ids or case_id not in lookup[0] or input_errors(case)
                or resources["run_outcomes"]["candidate"].get(run) != "METHOD_FAILURE"
                or any(row["errors"] for row in reviews[0][case_id].values())):
            continue
        deltas = {}
        for stage in STAGES:
            left, right = (rows[case_id][stage] for rows in reviews)
            completed = ((case["replay"].get(stage) or {}).get("status") == "COMPLETED" and not right["errors"])
            deltas[stage] = {}
            for dimension in DIMENSIONS:
                a = left["dimensions"][dimension]["status"]
                b = right["dimensions"][dimension]["status"] if completed else "FAIL"
                deltas[stage][dimension] = int(b == "PASS") - int(a == "PASS") if a in {"PASS", "FAIL"} and b in {"PASS", "FAIL"} else None
            for metric in GUARD_METRICS:
                a = guard_value(left["claim_checks"], metric)
                b = guard_value(right["claim_checks"], metric) if completed else 0
                deltas[stage][metric] = b - a if a is not None and b is not None else None
        extended.append({"id": case_id, "caseId": case["case_definition"]["caseId"], "deltas": deltas,
                         "failure_scoring": "FAILED_OR_UNREVIEWABLE_STAGE_AS_ZERO", "failure_attribution": "METHOD_FAILURE"})
        failure_scored.add(case_id)
    metrics = grouped_summary(manifest, extended, declared, (*DIMENSIONS, *GUARD_METRICS))
    groups = {name: grouped_summary(manifest, [pair for pair in extended if pair["caseId"] in members], declared,
                                    (*DIMENSIONS, *GUARD_METRICS)) for name, members in cohort["keyGroups"].items()}
    for group_name, values in (("ALL", metrics), *groups.items()):
        for stage in STAGES:
            for dimension, margin in policy["nonRegressionMargins"].items():
                metric = values[stage][dimension]
                if metric["case_count"] == 0 and dimension in GUARD_METRICS:
                    continue  # No applicable claims/refusal cases is N/A, never an invented perfect score.
                mean, interval = metric["mean_paired_delta"], metric["bootstrap_interval"]
                label = group_name + ":" + stage + ":" + dimension
                if interval is None:
                    missing.append("GROUP_SAMPLE_INSUFFICIENT:" + label)
                elif mean < -margin:
                    failures.append("NON_REGRESSION_FAILED:" + label)
                elif interval[0] < -margin:
                    missing.append("NON_REGRESSION_UNCERTAIN:" + label)
    # 声明了 failure-repro 组时，主判定只看失败复现题是否改善；其余组（如 regression）仍受上面的非退化约束。
    primary_cohort = "failure-repro" if "failure-repro" in groups else "ALL"
    primary_metrics = groups.get("failure-repro", metrics)
    stage_decisions = {}
    for stage in STAGES:
        metric = primary_metrics[stage][policy["primaryDimension"]]
        interval, mean = metric["bootstrap_interval"], metric["mean_paired_delta"]
        if interval is None or interval[0] <= 0 < interval[1]:
            decision = "INCONCLUSIVE"
        elif mean >= policy["minMeaningfulDelta"] and interval[0] > 0:
            decision = "IMPROVED"
        elif interval[1] < policy["minMeaningfulDelta"]:
            decision = "NO_IMPROVEMENT"
        else:
            decision = "INCONCLUSIVE"
        stage_decisions[stage] = decision
    status = "NO_IMPROVEMENT" if failures else "INCONCLUSIVE" if missing else stage_decisions["finalAnswer"]
    eligible = status == "IMPROVED" and split == "HOLDOUT"
    evidence = {**result["releaseEvidence"], "schema": "fundamentals_evolution_release_evidence_v2",
                "eligible": eligible, "authorization": "PENDING_HUMAN_APPROVAL" if eligible else "NOT_AUTHORIZED",
                "reason": "INDEPENDENT_HOLDOUT_ACCEPTED" if eligible else "FINAL_HOLDOUT_ACCEPTANCE_REQUIRED",
                "candidateBundleSha256": manifest["candidateBundleSha256"], "fixedContractSha256": manifest["fixedContractSha256"],
                "acceptanceGateSha256": gate_artifact["payloadSha256"], "resourceAuditSha256": resource_audit["payloadSha256"],
                "sourceCasesSha256": json_hash(sources), "goldSha256": json_hash(gold), "evaluatorSha256": evaluator_hash(),
                "expectedComparisonIdentity": manifest["expectedComparisonIdentity"], "evaluationSplit": split,
                "comparisonStatus": status, "selectionSha256": manifest["selectionSha256"],
                "reviewAuditSha256": review_audit['payloadSha256'] if review_audit else None}
    comparison = {**result, "schema": "fundamentals_evolution_comparison_v2", "status": status,
            "stage_decisions": stage_decisions, "primary_cohort": primary_cohort,
            "completion": {**completion, "planned": len(baseline["cases"])},
            "metrics": metrics, "key_groups": groups,
            "resources": resources, "review_summary": review_summary,
            "failures": sorted(set(failures)), "reasons": sorted(set(missing)),
            "pairs": extended, "paired_count": len(extended), "failure_scored_count": len(failure_scored),
            "excluded": [row for row in result["excluded"] if row["id"] not in failure_scored]}
    evidence['comparisonReportSha256'] = comparison_report_hash(comparison)
    return {**comparison, "releaseEvidence": evidence, "signedReleaseEvidence": seal({"kind": "RELEASE_EVIDENCE", **evidence}, key)}


def read_key() -> bytes:
    value = os.environ.get("STOCKSAGE_EVOLUTION_EVALUATOR_KEY", "")
    if len(value.encode()) < 32:
        raise ValueError("Set STOCKSAGE_EVOLUTION_EVALUATOR_KEY only in the independent evaluator environment (at least 32 bytes)")
    return value.encode()


def main() -> int:
    from evolution_dataset import load_jsonl
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    for name in ("baseline", "resource-audit", "select-result"):
        mode.add_argument("--" + name, type=Path)
    for name in ("source-cases", "gold", "policy", "review", "evidence-index"):
        parser.add_argument("--" + name, type=Path)
    parser.add_argument("--reviewer")
    parser.add_argument("--reason")
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if args.baseline and not all((args.source_cases, args.gold, args.policy, args.review)):
        parser.error("Baseline acceptance requires --baseline, --source-cases, --gold, --policy and --review")
    if not args.select_result and not args.evidence_index:
        parser.error("Baseline/resource acceptance requires --evidence-index with the original audit proofs")
    try:
        read = lambda path: json.loads(path.read_text(encoding="utf-8"))
        index = read(args.evidence_index) if args.evidence_index else {}
        evidence = {digest: (args.evidence_index.parent / file).read_bytes() for digest, file in index.items()}
        artifact = (select_candidate(read(args.select_result), read_key(), args.reviewer, args.reason) if args.select_result
                    else seal_resource_audit(read(args.resource_audit), evidence, read_key()) if args.resource_audit
                    else freeze_baseline(read(args.baseline), load_jsonl(args.source_cases), load_jsonl(args.gold),
                                         read(args.policy), read(args.review), evidence, read_key()))
        with args.output.open("x", encoding="utf-8") as target:
            target.write(json.dumps(artifact, ensure_ascii=False, indent=2) + "\n")
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(2, f"E07 acceptance rejected: {error}\n")
    print(json.dumps({"status": artifact["payload"].get("status", artifact["payload"]["kind"] + "_SIGNED"), "artifact_sha256": artifact["payloadSha256"]}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
