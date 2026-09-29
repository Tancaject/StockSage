"""Prepare grouped, paired offline comparisons and blinded human review materials.

Real reviews require independent source cases and gold. V2 decisions additionally
require signed pre-search E07 gates and complete resource accounting. V1 and
synthetic results cannot authorize a release.
"""
from __future__ import annotations

import argparse
import copy
import json
import math
import random
from pathlib import Path

from evolution_quality import (SCHEMA as QUALITY_SCHEMA, REAL_SCHEMA, STAGES, apply_reviews,
                               result_cases, review_template, source_material)
from evolution_dataset import REAL_ORIGIN, load_jsonl
from ordinary_answer_quality import DIMENSIONS, json_hash, sha256

SCHEMA = "fundamentals_evolution_comparison_manifest_v1"
V2_SCHEMA = "fundamentals_evolution_comparison_manifest_v2"
COMMON_IDENTITIES = {"modelConfigSha256", "fixedFinalPromptSha256", "memorySnapshotSha256"}
V2_IDENTITIES = COMMON_IDENTITIES | {"runtimeBuildSha256"}


def validate_manifest(manifest: dict) -> dict[str, dict]:
    fields = {"schema", "baselineBundleId", "candidateBundleId", "baselineBundleSha256", "candidateBundleSha256",
              "fixedContractSha256", "expectedComparisonIdentity", "minimumGroups", "bootstrapSamples", "maxRunsPerArm",
              "seed", "confidenceLevel", "primaryDimension", "nonRegressionDimensions", "cases"}
    v2 = isinstance(manifest, dict) and manifest.get("schema") == V2_SCHEMA
    if v2:
        fields |= {"acceptanceGateSha256", "evaluationSplit", "selectionSha256"}
    if not isinstance(manifest, dict) or set(manifest) != fields or manifest.get("schema") not in {SCHEMA, V2_SCHEMA}:
        raise ValueError("Expected a supported fundamentals evolution comparison manifest")
    if v2:
        require_hash(manifest["acceptanceGateSha256"], "acceptanceGateSha256")
        if manifest["evaluationSplit"] not in {"DEVELOPMENT", "VALIDATION", "HOLDOUT", "SHADOW"}:
            raise ValueError("Comparison requires a frozen development, validation, holdout or approved shadow cohort")
        if manifest["evaluationSplit"] == "HOLDOUT":
            require_hash(manifest["selectionSha256"], "selectionSha256")
        elif manifest["selectionSha256"] is not None:
            raise ValueError("Development and validation precede final candidate selection")
    for key in ("baselineBundleId", "candidateBundleId"):
        if not isinstance(manifest.get(key), str) or not manifest[key]:
            raise ValueError("Comparison manifest is missing " + key)
    if manifest["baselineBundleId"] == manifest["candidateBundleId"]:
        raise ValueError("Baseline and candidate must select distinct bundle IDs")
    for key in ("baselineBundleSha256", "candidateBundleSha256", "fixedContractSha256"):
        require_hash(manifest.get(key), key)
    identities = manifest.get("expectedComparisonIdentity") or {}
    if set(identities) != (V2_IDENTITIES if v2 else COMMON_IDENTITIES):
        raise ValueError("expectedComparisonIdentity must declare every common comparison identity")
    for key, value in identities.items():
        require_hash(value, key)
    for key, low, high in (("minimumGroups", 2, 100000), ("bootstrapSamples", 100, 100000),
                           ("maxRunsPerArm", 1, 100000)):
        if type(manifest.get(key)) is not int or not low <= manifest[key] <= high:
            raise ValueError(f"{key} must be an integer in [{low}, {high}]")
    if type(manifest.get("seed")) is not int or type(manifest.get("confidenceLevel")) not in (int, float) or not 0 < manifest["confidenceLevel"] < 1:
        raise ValueError("Manifest requires an integer seed and confidenceLevel between zero and one")
    if manifest.get("primaryDimension") not in DIMENSIONS:
        raise ValueError("Manifest primaryDimension must use the existing quality rubric")
    constraints = manifest.get("nonRegressionDimensions")
    if not isinstance(constraints, list) or len(set(constraints)) != len(constraints) or any(name not in DIMENSIONS for name in constraints):
        raise ValueError("nonRegressionDimensions must be a unique list of rubric dimensions")
    indexed = {}
    for case in manifest.get("cases", []):
        if not isinstance(case, dict) or set(case) != {"caseId", "issuerId", "reportFamilyId", "caseSha256",
                "expectedEvidenceSnapshotSha256", "expectedHistorySnapshotSha256", "repeatIds"}:
            raise ValueError("Comparison case contains missing or unknown fields")
        key = case.get("caseId")
        if not isinstance(key, str) or not key or key in indexed:
            raise ValueError("Manifest cases require nonempty unique caseId values")
        for field in ("issuerId", "reportFamilyId"):
            if not isinstance(case.get(field), str) or not case[field]:
                raise ValueError("Manifest case is missing " + field)
        for field in ("caseSha256", "expectedEvidenceSnapshotSha256", "expectedHistorySnapshotSha256"):
            require_hash(case.get(field), field)
        repeats = case.get("repeatIds")
        if not isinstance(repeats, list) or not repeats or len(set(repeats)) != len(repeats) or any(type(value) is not int or value < 1 for value in repeats):
            raise ValueError("Manifest repeatIds must be distinct positive integers")
        indexed[key] = case
    if not indexed or sum(len(case["repeatIds"]) for case in indexed.values()) > manifest["maxRunsPerArm"]:
        raise ValueError("Manifest cases must fit the predeclared per-arm invocation budget")
    return indexed


def require_hash(value, name: str) -> None:
    if not isinstance(value, str) or len(value) != 64 or any(char not in "0123456789abcdef" for char in value):
        raise ValueError(name + " must be a SHA-256 digest")


def revalidate_reviews(report: dict, source_cases=None, gold=None) -> dict:
    reviews = [row for case in report.get("cases", [])
               for row in (case.get("quality_review") or {}).get("stages", {}).values()]
    real = any(case["case_definition"].get("origin") == REAL_ORIGIN for case in report.get("cases", []))
    return apply_reviews(report, {"schema": REAL_SCHEMA if real else QUALITY_SCHEMA,
                                  "dataset_sha256": report.get("dataset_sha256"), "reviews": reviews}, source_cases, gold)


def model_identity(stage: dict, name: str) -> dict | None:
    scope = STAGES[name][1]
    observations = stage.get("observations") or []
    calls = [row for row in observations if row.get("kind") == "model-invocation" and row.get("scope") == scope]
    usage = [row for row in observations if row.get("kind") == "model-usage" and row.get("scope") == scope]
    key = "actualModel" if name == "analysis" else "providerModelName"
    actual = {row[key] for row in usage if isinstance(row.get(key), str) and row[key]}
    timeout = stage.get("timeoutSeconds")
    if (len(calls) != 1 or len(actual) != 1 or not calls[0].get("modelName") or not calls[0].get("modelTier")
            or type(timeout) is not int or timeout < 1):
        return None
    invocation = {key: value for key, value in calls[0].items() if key != "actualSystemPromptSha256"}
    if name == "analysis" and not isinstance(invocation.get("clientDefaults"), dict):
        return None
    if name == "analysis":
        invocation["clientDefaults"] = {key: value for key, value in invocation["clientDefaults"].items()
                                        if key != "systemPromptHash"}
    if name == "finalAnswer" and ("temperature" not in invocation or "maxOutputTokens" not in invocation):
        return None
    return {"request": invocation, "actualModel": next(iter(actual)), "timeoutSeconds": stage.get("timeoutSeconds")}


def pair_errors(manifest: dict, declared: dict, left: dict, right: dict) -> list[str]:
    errors = []
    definitions = []
    for arm, case in (("baseline", left), ("candidate", right)):
        definition, replay = case.get("case_definition") or {}, case.get("replay") or {}
        definitions.append({key: value for key, value in definition.items() if key != "bundleId"})
        bundle = replay.get("methodBundle") or {}
        if (definition.get("caseId") != declared["caseId"] or replay.get("caseId") != declared["caseId"]
                or replay.get("caseSha256") != declared["caseSha256"] or definition.get("caseSha256") != declared["caseSha256"]
                or definition.get("repeatId") not in declared["repeatIds"]):
            errors.append(arm + ":FROZEN_CASE_MISMATCH")
        if (bundle.get("bundleId") != manifest[arm + "BundleId"] or bundle.get("bundleSha256") != manifest[arm + "BundleSha256"]
                or bundle.get("fixedContractSha256") != manifest["fixedContractSha256"]):
            errors.append(arm + ":METHOD_BUNDLE_MISMATCH")
        identity = replay.get("comparisonIdentity") or {}
        expected = {**manifest["expectedComparisonIdentity"],
                    "evidenceSnapshotSha256": declared["expectedEvidenceSnapshotSha256"],
                    "historySnapshotSha256": declared["expectedHistorySnapshotSha256"]}
        if any(identity.get(key) != value for key, value in expected.items()):
            errors.append(arm + ":COMPARISON_IDENTITY_MISSING_OR_CHANGED")
        configuration = identity.get("modelConfiguration") or {}
        serialized = identity.get("modelConfigurationJson")
        try:
            exact_configuration = isinstance(serialized, str) and json.loads(serialized) == configuration
        except (ValueError, TypeError):
            exact_configuration = False
        if (identity.get("modelConfigurationComplete") is not True
                or (configuration.get("provider") or {}).get("scope") != "API_CONSTRUCTION"
                or not exact_configuration or sha256(serialized or "") != identity.get("modelConfigSha256")):
            errors.append(arm + ":PROVIDER_OR_MODEL_SNAPSHOT_UNAVAILABLE_OR_CHANGED")
        if (identity.get("evidenceSnapshotSha256") != definition.get("contextSha256")
                or identity.get("historySnapshotSha256") != json_hash(definition.get("history"))):
            errors.append(arm + ":SOURCE_SNAPSHOT_HASH_MISMATCH")
        actual_analysis = model_identity(replay.get("analysis") or {}, "analysis")
        actual_final = [row for row in (replay.get("finalAnswer") or {}).get("observations", [])
                        if row.get("kind") == "model-invocation"]
        if (actual_analysis is None or configuration.get("analysis") != actual_analysis["request"].get("clientDefaults")
                or configuration.get("analysisTimeoutSeconds") != actual_analysis["timeoutSeconds"]
                or configuration.get("finalAnswer") != actual_final
                or type(configuration.get("promptMaxChars")) is not int or configuration["promptMaxChars"] < 1):
            errors.append(arm + ":ACTUAL_CONFIGURATION_SNAPSHOT_MISMATCH")
        if case.get("passed") is not True or replay.get("status") != "COMPLETED":
            errors.append(arm + ":EXECUTION_NOT_COMPLETE")
    if definitions[0] != definitions[1]:
        errors.append("FROZEN_INPUTS_DIFFER")
    if left["replay"].get("runId") == right["replay"].get("runId"):
        errors.append("INDEPENDENT_RUN_ID_REQUIRED")
    for name in STAGES:
        a, b = left["replay"].get(name) or {}, right["replay"].get(name) or {}
        models = (model_identity(a, name), model_identity(b, name))
        if models[0] is None or models[1] is None or models[0] != models[1]:
            errors.append(name + ":MODEL_OR_BUDGET_UNAVAILABLE_OR_CHANGED")
        if a.get("evidenceSha256") != b.get("evidenceSha256"):
            errors.append(name + ":VISIBLE_EVIDENCE_CHANGED")
    return errors


def percentile(values: list[float], fraction: float) -> float:
    position = (len(values) - 1) * fraction
    lower = math.floor(position)
    upper = math.ceil(position)
    return values[lower] + (values[upper] - values[lower]) * (position - lower)


def grouped_summary(manifest: dict, pairs: list[dict], declared: dict[str, dict], metric_names=DIMENSIONS) -> dict:
    metrics = {}
    for stage in STAGES:
        metrics[stage] = {}
        for dimension in metric_names:
            per_case = {}
            for pair in pairs:
                value = pair["deltas"][stage][dimension]
                if value is not None:
                    per_case.setdefault(pair["caseId"], []).append(value)
            groups = {}
            for key, repeats in per_case.items():
                case = declared[key]
                groups.setdefault((case["issuerId"], case["reportFamilyId"]), []).append(sum(repeats) / len(repeats))
            means = [sum(values) / len(values) for values in groups.values()]
            interval = None
            if len(means) >= manifest["minimumGroups"]:
                rng = random.Random(manifest["seed"])
                samples = sorted(sum(rng.choices(means, k=len(means))) / len(means) for _ in range(manifest["bootstrapSamples"]))
                tail = (1 - manifest["confidenceLevel"]) / 2
                interval = [percentile(samples, tail), percentile(samples, 1 - tail)]
            metrics[stage][dimension] = {"group_count": len(means), "case_count": len(per_case),
                                          "repeat_count": sum(len(values) for values in per_case.values()),
                                          "mean_paired_delta": sum(means) / len(means) if means else None,
                                          "bootstrap_interval": interval}
    return metrics


def compare_reports(manifest: dict, baseline: dict, candidate: dict, source_cases=None, gold=None,
                    *, acceptance_gate=None, resource_audit=None, evaluator_key=None, holdout_ledger=None, selection=None,
                    review_audit=None, shadow_authorization=None, approved_artifact=None, publisher_key=None) -> dict:
    declared = validate_manifest(manifest)
    before, after = result_cases(baseline), result_cases(candidate)
    expected = {f"{case['caseId']}@{repeat}" for case in declared.values() for repeat in case["repeatIds"]}
    real = bool(before) and all(case["case_definition"].get("origin") == REAL_ORIGIN for case in before.values())
    kind = "PUBLIC_AUTHORIZED_REVIEW" if real else "SYNTHETIC_MECHANISM_ONLY"
    reasons = {"RELEASE_DECISION_GATES_NOT_FROZEN" if real else "SYNTHETIC_ONLY_REAL_SCOPE_NOT_IMPLEMENTED"}
    for arm, report, rows in (("baseline", baseline, before), ("candidate", candidate, after)):
        if set(rows) != expected or report.get("sample_count") != len(expected):
            reasons.add(arm + ":PLANNED_CASES_MISSING_OR_EXTRA")
        if report.get("sample_count", 0) > manifest["maxRunsPerArm"]:
            reasons.add(arm + ":INVOCATION_BUDGET_EXCEEDED")
        if report.get("comparison_manifest_sha256") != json_hash(manifest):
            reasons.add(arm + ":PREDECLARED_MANIFEST_BINDING_MISSING_OR_CHANGED")
        if report.get("status") != "PASS":
            reasons.add(arm + ":EXECUTION_NOT_COMPLETE")
    audited = [revalidate_reviews(report, source_cases, gold) for report in (baseline, candidate)]
    review_index = [{case["id"]: case["quality_review"]["stages"] for case in report["cases"]} for report in audited]
    pairs, excluded = [], []
    for key in sorted(expected):
        if key not in before or key not in after:
            excluded.append({"id": key, "reasons": ["PAIR_MISSING"]})
            continue
        case_id = key.rsplit("@", 1)[0]
        errors = pair_errors(manifest, declared[case_id], before[key], after[key])
        deltas = {}
        for stage in STAGES:
            left, right = (reviews[key][stage] for reviews in review_index)
            if left["errors"] or right["errors"]:
                errors.append(stage + ":BOUND_HUMAN_REVIEW_UNAVAILABLE")
            deltas[stage] = {}
            for dimension in DIMENSIONS:
                a, b = (row["dimensions"][dimension]["status"] for row in (left, right))
                if "NO_DATA" in (a, b):
                    errors.append(stage + ":DIMENSION_UNREVIEWED:" + dimension)
                deltas[stage][dimension] = int(b == "PASS") - int(a == "PASS") if a in {"PASS", "FAIL"} and b in {"PASS", "FAIL"} else None
        if errors:
            excluded.append({"id": key, "reasons": sorted(set(errors))})
        else:
            pair = {"id": key, "caseId": case_id, "deltas": deltas}
            if real:
                pair["claim_checks"] = {arm: {stage: reviews[key][stage]["claim_checks"] for stage in STAGES}
                                         for arm, reviews in zip(("baseline", "candidate"), review_index)}
            pairs.append(pair)
    if excluded:
        reasons.add("PAIRS_UNAVAILABLE_OR_INCOMPATIBLE")
    metrics = grouped_summary(manifest, pairs, declared)
    required = {manifest["primaryDimension"], *manifest["nonRegressionDimensions"]}
    if any(metrics[stage][dimension]["group_count"] < manifest["minimumGroups"] for stage in STAGES for dimension in required):
        reasons.add("INSUFFICIENT_INDEPENDENT_GROUPS")
    identity = {"manifest_sha256": json_hash(manifest), "baseline_report_sha256": json_hash(baseline),
                "candidate_report_sha256": json_hash(candidate)}
    result = {"schema": "fundamentals_evolution_comparison_v1", "status": "INCONCLUSIVE",
            "evidence_kind": kind, **identity, "reasons": sorted(reasons),
            "planned_invocation_pairs": len(expected), "paired_count": len(pairs), "excluded": excluded,
            "aggregation": "REPEAT_MEAN_THEN_CASE_MEAN_WITHIN_ISSUER_REPORT_FAMILY",
            "bootstrap": {"unit": "ISSUER_REPORT_FAMILY", "samples": manifest["bootstrapSamples"],
                          "confidence_level": manifest["confidenceLevel"], "seed": manifest["seed"],
                          "interval_method": "PERCENTILE_LINEAR_INTERPOLATION", "scope": "FROZEN_COMPONENT_CHAIN" if real else "MECHANISM_ONLY"},
            "metrics": metrics, "pairs": pairs,
            "releaseEvidence": {"schema": "fundamentals_evolution_release_evidence_v1", **identity,
                                "eligible": False, "authorization": "NOT_AUTHORIZED",
                                "reason": "REAL_BASELINE_AND_INDEPENDENT_ACCEPTANCE_PENDING"}}
    if manifest["schema"] == V2_SCHEMA:
        if not real:
            return result
        if acceptance_gate is None or resource_audit is None or evaluator_key is None:
            result["reasons"].append("SIGNED_E07_OR_RESOURCE_AUDIT_MISSING")
            return result
        from evolution_acceptance import decide_comparison
        return decide_comparison(result, manifest, baseline, candidate, source_cases, gold, acceptance_gate,
                                  resource_audit, evaluator_key, holdout_ledger, selection, review_audit,
                                  shadow_authorization, approved_artifact, publisher_key)
    return result


def blind_material(manifest: dict, baseline: dict, candidate: dict, source_cases=None, gold=None) -> tuple[dict, dict]:
    declared = validate_manifest(manifest)
    reports = {"baseline": result_cases(baseline), "candidate": result_cases(candidate)}
    labels = source_material(baseline, source_cases, gold)
    source_material(candidate, source_cases, gold)
    templates = {arm: {(row["binding"]["id"], row["scope"]): row for row in
                       review_template(report, source_cases, gold)["reviews"]}
                 for arm, report in (("baseline", baseline), ("candidate", candidate))}
    items, mapping = [], []
    rng = random.SystemRandom()
    for case in declared.values():
        for repeat in case["repeatIds"]:
            key = f"{case['caseId']}@{repeat}"
            if any(key not in rows for rows in reports.values()):
                raise ValueError("Cannot blind an incomplete pair: " + key)
            for stage, (scope, _) in STAGES.items():
                arms = ["baseline", "candidate"]
                rng.shuffle(arms)
                trial = f"trial-{len(items) + 1:04d}"
                options = []
                for label, arm in zip(("A", "B"), arms):
                    source = reports[arm][key]
                    observed = source["replay"].get(stage)
                    observed = observed or {"status": "NOT_COMPLETED", "errorCode": source['replay'].get('errorCode')}
                    options.append({"label": label, "answer": observed.get("answer") or "", "visible_evidence": observed.get("evidenceContext"),
                                    "execution_status": observed.get("status"), "error_code": observed.get("errorCode"),
                                    "reviewer": "", "reviewed_at": "",
                                    "dimensions": {name: {"status": "NO_DATA", "reason": ""} for name in DIMENSIONS}})
                    if labels:
                        template = templates[arm][(key, scope)]
                        options[-1].update({field: template[field] for field in
                                            ("gold_sha256", "expectations", *REAL_REVIEW_FIELDS)})
                source = reports["baseline"][key]["case_definition"]
                items.append({"trialId": trial, "scope": scope, "question": source["query"], "asOf": source["asOf"],
                              "preference": None, "reason": "", "options": options})
                mapping.append({"trialId": trial, "stage": stage, "id": key, "labels": dict(zip(("A", "B"), arms))})
    packet = {"schema": "fundamentals_evolution_blind_review_v1", "evidence_kind": "PUBLIC_AUTHORIZED_REVIEW" if labels else "SYNTHETIC_MECHANISM_ONLY", "items": items}
    key = {"schema": "fundamentals_evolution_blind_mapping_v1", "manifest_sha256": json_hash(manifest),
           "baseline_report_sha256": json_hash(baseline), "candidate_report_sha256": json_hash(candidate),
           "mapping": mapping}
    packet["mapping_sha256"] = json_hash(key)
    key["materials_sha256"] = blind_material_hash(packet)
    return packet, key


REAL_REVIEW_FIELDS = ("hard_gates", "extraction_complete", "claims", "points", "response_kind")


def blind_material_hash(packet: dict) -> str:
    material = copy.deepcopy(packet)
    for item in material.get("items", []):
        item.pop("preference", None)
        item.pop("reason", None)
        for option in item.get("options", []):
            for field in ("reviewer", "reviewed_at", "dimensions", *REAL_REVIEW_FIELDS):
                option.pop(field, None)
    return json_hash(material)


def apply_blind_reviews(manifest: dict, baseline: dict, candidate: dict, packet: dict, mapping: dict,
                        source_cases=None, gold=None) -> tuple[dict, dict]:
    if (mapping.get("schema") != "fundamentals_evolution_blind_mapping_v1"
            or mapping.get("manifest_sha256") != json_hash(manifest)
            or mapping.get("baseline_report_sha256") != json_hash(baseline)
            or mapping.get("candidate_report_sha256") != json_hash(candidate)
            or packet.get("mapping_sha256") != json_hash({key: value for key, value in mapping.items() if key != "materials_sha256"})
            or mapping.get("materials_sha256") != blind_material_hash(packet)):
        raise ValueError("Blinded materials, mapping or source reports changed; regenerate the review packet")
    templates = {arm: review_template(report, source_cases, gold) for arm, report in (("baseline", baseline), ("candidate", candidate))}
    indexes = {arm: {(row["binding"]["id"], row["scope"]): row for row in template["reviews"]} for arm, template in templates.items()}
    items = {item["trialId"]: item for item in packet["items"]}
    if len(items) != len(packet["items"]) or set(items) != {row["trialId"] for row in mapping["mapping"]}:
        raise ValueError("Blinded review trial coverage changed")
    seen = set()
    for trial in mapping["mapping"]:
        scope = STAGES[trial["stage"]][0]
        item = items[trial["trialId"]]
        if item["scope"] != scope or set(trial["labels"].values()) != {"baseline", "candidate"}:
            raise ValueError("Blinded mapping must contain both distinct arms in the original stage")
        for option in item["options"]:
            arm = trial["labels"][option["label"]]
            key = (arm, trial["id"], scope)
            if key in seen:
                raise ValueError("Duplicate blinded stage review")
            seen.add(key)
            target = indexes[arm][(trial["id"], scope)]
            if option["answer"] != target["answer"] or option["visible_evidence"] != target["answer_context"]["evidenceContext"]:
                raise ValueError("Blinded mapping no longer identifies its original answer and evidence")
            target.update({field: option[field] for field in ("reviewer", "reviewed_at", "dimensions")})
            if target.get("evidence_kind") == "PUBLIC_AUTHORIZED_REVIEW":
                target.update({field: option.get(field) for field in REAL_REVIEW_FIELDS})
    return (apply_reviews(baseline, templates["baseline"], source_cases, gold),
            apply_reviews(candidate, templates["candidate"], source_cases, gold))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("manifest", "baseline", "candidate"):
        parser.add_argument("--" + name, type=Path, required=True)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--output", type=Path)
    mode.add_argument("--blind-dir", type=Path)
    parser.add_argument("--blind-reviews", type=Path)
    parser.add_argument("--mapping", type=Path)
    parser.add_argument("--source-cases", type=Path)
    parser.add_argument("--gold", type=Path)
    parser.add_argument("--acceptance-gate", type=Path)
    parser.add_argument("--resource-audit", type=Path)
    parser.add_argument("--review-audit", type=Path)
    parser.add_argument("--shadow-authorization", type=Path)
    parser.add_argument("--approved-artifact", type=Path)
    parser.add_argument("--holdout-ledger", type=Path)
    parser.add_argument("--selection", type=Path)
    args = parser.parse_args()
    if bool(args.blind_reviews) != bool(args.mapping) or args.blind_reviews and not args.output:
        parser.error("--blind-reviews and --mapping must be supplied together with --output")
    if bool(args.source_cases) != bool(args.gold):
        parser.error("--source-cases and --gold must be supplied together")
    try:
        manifest, baseline, candidate = (json.loads(path.read_text(encoding="utf-8")) for path in (args.manifest, args.baseline, args.candidate))
        sources = load_jsonl(args.source_cases) if args.source_cases else None
        gold = load_jsonl(args.gold) if args.gold else None
        gate = json.loads(args.acceptance_gate.read_text(encoding="utf-8")) if args.acceptance_gate else None
        audit = json.loads(args.resource_audit.read_text(encoding="utf-8")) if args.resource_audit else None
        review_audit = json.loads(args.review_audit.read_text(encoding="utf-8")) if args.review_audit else None
        shadow = json.loads(args.shadow_authorization.read_text(encoding="utf-8")) if args.shadow_authorization else None
        approved = json.loads(args.approved_artifact.read_text(encoding="utf-8")) if args.approved_artifact else None
        publisher = None
        if manifest.get('evaluationSplit') == 'SHADOW':
            from evolution_release import publisher_key
            publisher = publisher_key()
        selection = json.loads(args.selection.read_text(encoding="utf-8")) if args.selection else None
        key = None
        if gate is not None or audit is not None:
            from evolution_acceptance import read_key
            key = read_key()
        if args.blind_dir:
            packet, mapping = blind_material(manifest, baseline, candidate, sources, gold)
            outputs = {args.blind_dir / "blinded.json": packet, args.blind_dir / "mapping-private.json": mapping}
        else:
            if args.blind_reviews:
                packet, mapping = (json.loads(path.read_text(encoding="utf-8")) for path in (args.blind_reviews, args.mapping))
                baseline, candidate = apply_blind_reviews(manifest, baseline, candidate, packet, mapping, sources, gold)
            outputs = {args.output: compare_reports(manifest, baseline, candidate, sources, gold,
                        acceptance_gate=gate, resource_audit=audit, evaluator_key=key, holdout_ledger=args.holdout_ledger,
                        selection=selection, review_audit=review_audit, shadow_authorization=shadow,
                        approved_artifact=approved, publisher_key=publisher)}
        if any(path.exists() for path in outputs):
            raise ValueError("Output already exists; choose a new path to preserve frozen evidence")
        for path, value in outputs.items():
            path.parent.mkdir(parents=True, exist_ok=True)
            with path.open("x", encoding="utf-8") as target:
                target.write(json.dumps(value, ensure_ascii=False, indent=2) + "\n")
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(2, f"Evolution comparison failed: {error}\n")
    status = "BLIND_REVIEW_TEMPLATE" if args.blind_dir else outputs[args.output]["status"]
    authorization = "NOT_AUTHORIZED" if args.blind_dir else outputs[args.output]["releaseEvidence"]["authorization"]
    print(json.dumps({"status": status, "release_authorization": authorization}))
    return 0 if args.blind_dir or status in {"IMPROVED", "NO_IMPROVEMENT"} else 2


if __name__ == "__main__":
    raise SystemExit(main())
