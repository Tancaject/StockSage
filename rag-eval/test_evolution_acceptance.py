"""Fictional model/evidence fixtures exercise decisions, never claim real E07 acceptance."""
import copy
import json
import io
import os
import tempfile
import unittest
from contextlib import redirect_stdout
from datetime import datetime, timezone, timedelta
from pathlib import Path
from unittest.mock import patch

from evolution_acceptance import (freeze_baseline, require_gate, seal, verified, evaluator_hash, validate_policy,
                                  holdout_reservation, seal_resource_audit, select_candidate, GUARD_METRICS, ROLES)
from evolution_compare import compare_reports, blind_material, REAL_REVIEW_FIELDS
from evolution_dataset import case_hash
from evolution_quality import review_template, apply_reviews, STAGES
from ordinary_answer_quality import DIMENSIONS, json_hash, sha256
from run_evolution_replay import run_paired_cases
from run_evolution_replay import main as replay_main
from test_evolution_real import source_case, gold_row, real_report, filled_reviews
from test_evolution_compare import experiment

KEY = b"unit-test-only-not-a-deployment-secret"


def reviewed_comparison(manifest, baseline, candidate, sources, gold, **kwargs):
    """Repackage fictional pre-filled reviews through the actual blind-audit signer."""
    from evolution_review import adjudication_template, finalize_reviews
    packet, mapping = blind_material(manifest, baseline, candidate, sources, gold)
    reports = {'baseline': baseline, 'candidate': candidate}
    for item, trial in zip(packet['items'], mapping['mapping']):
        for option in item['options']:
            case = next(row for row in reports[trial['labels'][option['label']]]['cases'] if row['id'] == trial['id'])
            review = case['quality_review']['stages'][trial['stage']]
            option.update({field: copy.deepcopy(review[field]) for field in
                           ('reviewer', 'reviewed_at', 'dimensions', *REAL_REVIEW_FIELDS)})
        statuses = [[row['status'] for row in option['dimensions'].values()] for option in item['options']]
        scores = [values.count('PASS') for values in statuses]
        item.update(preference='UNREVIEWABLE' if any('NO_DATA' in values for values in statuses) else
                    'TIE' if scores[0] == scores[1] else 'A' if scores[0] > scores[1] else 'B',
                    reason='Fictional rubric comparison, not a real independent opinion')
    decisions = adjudication_template(manifest, baseline, candidate, [packet], mapping, sources, gold)
    final = finalize_reviews(manifest, baseline, candidate, [packet], mapping, decisions, KEY, sources, gold)
    return compare_reports(manifest, final['baseline'], final['candidate'], sources, gold,
                           review_audit=final['audit'], **kwargs)


def fixture():
    _, prototype, _ = experiment(group_count=1, repeats=1)
    prototype = prototype["cases"][0]["replay"]
    sources, gold = [], []
    for split in ("DEVELOPMENT", "VALIDATION", "HOLDOUT"):
        for number in range(3):
            case = source_case()
            key = split.lower() + "-" + str(number)
            case.update(caseId=key, split=split, issuerId=key, ticker=key, leakageGroup=key)
            evidence = case["evidence"][0]
            text = evidence["visibleText"] + " Unit fixture: " + key
            evidence.update(target=key, sourceRef="https://example.invalid/" + key,
                            rawText=text, visibleText=text, rawSha256=sha256(text), visibleSha256=sha256(text))
            sources.append(case)
            gold.append(gold_row(case))
    policy = {"schema": "fundamentals_acceptance_policy_v1", "experimentId": "unit-test-experiment",
              "rationale": "Fictional test thresholds; not calibrated business values", "primaryDimension": "claim_support",
              "minMeaningfulDelta": .1, "nonRegressionMargins": dict.fromkeys((*DIMENSIONS, *GUARD_METRICS), 0),
              "minimumGroups": 3, "bootstrapSamples": 100, "confidenceLevel": .95, "seed": 23,
              "repeats": dict.fromkeys(("DEVELOPMENT", "VALIDATION", "HOLDOUT"), 1),
              "cohorts": {split: {"caseIds": [case["caseId"] for case in sources if case["split"] == split],
                                   "keyGroups": {"declared-test-group": [case["caseId"] for case in sources if case["split"] == split]}}
                          for split in ("DEVELOPMENT", "VALIDATION", "HOLDOUT")},
              "limits": {"maxCandidates": 4, "maxRounds": 2, "maxModelCalls": dict.fromkeys(ROLES, 100),
                         "maxTotalTokens": 100000, "maxExperimentCostUsd": "20", "maxMeanCandidateCostUsd": "1",
                         "maxCandidateP95LatencyMs": 1000}, "runOrder": "ALTERNATING", "retryPolicy": "NO_AUTOMATIC_RETRY"}

    def report_for(splits, arm):
        selected = [case for case in sources if case["split"] in splits]
        report = real_report(selected[0])
        report["cases"] = []
        for case in selected:
            row = copy.deepcopy(real_report(case)["cases"][0])
            bundle = "baseline-v1" if arm == "baseline" else "candidate-v1"
            row["case_definition"]["bundleId"] = bundle
            row["request"].update(bundleId=bundle, runId=arm + "-" + case["caseId"])
            replay = row["replay"]
            replay["runId"] = row["request"]["runId"]
            replay["methodBundle"].update(bundleId=bundle, bundleSha256=sha256(arm + "-method"))
            replay["comparisonIdentity"] = copy.deepcopy(prototype["comparisonIdentity"])
            replay["comparisonIdentity"].update(evidenceSnapshotSha256=row["case_definition"]["contextSha256"],
                                                 historySnapshotSha256=json_hash(case["history"]))
            for name in STAGES:
                replay[name]["observations"] = copy.deepcopy(prototype[name]["observations"])
                replay[name]["observations"][-1].update(usageSource="PROVIDER", inputTokens=100, outputTokens=20)
            attach_runtime(row, next(iter(splits)) if len(splits) == 1 else "BASELINE")
            report["cases"].append(row)
        report.update(sample_count=len(selected), unique_case_count=len(selected), completed_count=len(selected),
                      dataset_sha256=json_hash([row["case_definition"] for row in report["cases"]]))
        return rate(report, sources, gold, baseline=(arm == "baseline"))

    baseline = report_for({"DEVELOPMENT", "VALIDATION"}, "baseline")
    proof = b"UNIT TEST negative controls/isolation/calibration/sampling evidence"
    digest = sha256(proof.decode())
    review = {"reviewer": "test-independent-evaluator", "reviewedAt": "2026-09-28T00:00:00Z",
              "evidenceLevel": "END_TO_END_REPLAY", "baselineReportSha256": json_hash(baseline),
              "sourceCasesSha256": json_hash(sources), "goldSha256": json_hash(gold), "policySha256": json_hash(policy),
              "evaluatorSha256": evaluator_hash(), "checks": {name: {"status": "PASS", "artifactSha256": digest}
              for name in ("negativeControls", "isolation", "reviewCalibration", "samplingPlan")},
              "findings": ["Test baseline has a deliberately failing claim-support rating"]}
    gate = freeze_baseline(baseline, sources, gold, policy, review, {digest: proof}, KEY)
    return sources, gold, policy, baseline, review, {digest: proof}, gate, report_for


def rate(report, sources, gold, *, baseline=False):
    template = review_template(report, sources, gold)
    indexed, labels = {case["caseId"]: case for case in sources}, {row["caseId"]: row for row in gold}
    for row in template["reviews"]:
        case_id = row["binding"]["id"].rsplit("@", 1)[0]
        reference = filled_reviews(real_report(indexed[case_id]), indexed[case_id], labels[case_id])["reviews"][0]
        for field in ("reviewer", "reviewed_at", "dimensions", "hard_gates", "extraction_complete", "claims", "points", "response_kind"):
            row[field] = copy.deepcopy(reference[field])
        if baseline:
            row["dimensions"]["claim_support"] = {"status": "FAIL", "reason": "Fictional negative control"}
    return apply_reviews(report, template, sources, gold)


def attach_runtime(row, mode):
    replay, definition = row["replay"], row["case_definition"]
    identity = replay["comparisonIdentity"]
    build = {"status": "VERIFIED_ARTIFACT", "runtimeArtifact": {"status": "KNOWN", "scope": "CODE_SOURCE_FILE",
             "sha256": "c" * 64, "dependencyScope": "ONLY_BYTES_IN_SOURCE_FILE", "javaRuntime": "UNIT_TEST"},
             "manifest": {"schema": "fundamentals_evolution_build_v1", "gitSha": "a" * 40,
             "sourceTreeSha256": "b" * 64, "sourceState": "WORKTREE_SNAPSHOT", "artifactSha256": "c" * 64,
             "artifactFormat": "SPRING_BOOT_JAR", "builtAt": "2026-09-28T00:00:00Z"}}
    identity.update(runtimeBuild=build, runtimeBuildSha256=json_hash(build))
    replay["runContext"] = {"schemaVersion": 1, "experimentId": "unit-test-experiment", "evaluatorVersion": evaluator_hash(),
             "runMode": mode, "runId": replay["runId"], "caseId": definition["caseId"], "repeatId": definition["repeatId"],
             "gitSha": "a" * 40, "runtimeBuildSha256": identity["runtimeBuildSha256"],
             "bundleHash": replay["methodBundle"]["bundleSha256"], "modelConfigHash": identity["modelConfigSha256"],
             "evidenceSnapshotHash": definition["contextSha256"], "historySnapshotHash": identity["historySnapshotSha256"],
             "memorySnapshotHash": identity["memorySnapshotSha256"], "fixedFinalPromptHash": identity["fixedFinalPromptSha256"]}


def execution_context(report):
    return {key: report["cases"][0]["replay"]["runContext"][key] for key in ("experimentId", "evaluatorVersion", "runMode")}


def comparison(split="VALIDATION"):
    sources, gold, policy, _, _, _, gate, report_for = fixture()
    baseline, candidate = report_for({split}, "baseline"), report_for({split}, "candidate")
    manifest = {"schema": "fundamentals_evolution_comparison_manifest_v2", "baselineBundleId": "baseline-v1",
                "candidateBundleId": "candidate-v1", "baselineBundleSha256": sha256("baseline-method"),
                "candidateBundleSha256": sha256("candidate-method"), "fixedContractSha256": sha256("contract"),
                "expectedComparisonIdentity": gate["payload"]["expectedComparisonIdentity"],
                **{key: policy[key] for key in ("minimumGroups", "bootstrapSamples", "confidenceLevel", "seed", "primaryDimension")},
                "nonRegressionDimensions": list(DIMENSIONS), "maxRunsPerArm": 3,
                "acceptanceGateSha256": gate["payloadSha256"], "evaluationSplit": split, "selectionSha256": None, "cases": []}
    if split == "HOLDOUT":
        manifest["selectionSha256"] = selected_fixture(gate, manifest)["payloadSha256"]
    order = []
    for index, case in enumerate(baseline["cases"]):
        definition = case["case_definition"]
        source = next(row for row in sources if row["caseId"] == definition["caseId"])
        manifest["cases"].append({"caseId": source["caseId"], "issuerId": source["issuerId"], "reportFamilyId": source["reportFamilyId"],
                                 "caseSha256": case_hash(source), "repeatIds": [1], "expectedEvidenceSnapshotSha256": definition["contextSha256"],
                                 "expectedHistorySnapshotSha256": json_hash(source["history"])})
        pair = [case["replay"]["runId"], candidate["cases"][index]["replay"]["runId"]]
        order.extend(pair if index % 2 == 0 else reversed(pair))
    for report in (baseline, candidate):
        report.update(comparison_manifest_sha256=json_hash(manifest), run_order="ALTERNATING", invocation_order=order,
                      started_at=(datetime.now(timezone.utc) + timedelta(seconds=1)).isoformat())
    return sources, gold, manifest, gate, baseline, candidate, resource_audit(gate, baseline, candidate)


def selected_fixture(gate, manifest):
    return seal({"kind": "CANDIDATE_SELECTION", "gateSha256": gate["payloadSha256"],
                 "candidateBundleSha256": manifest["candidateBundleSha256"], "validationEvidenceSha256": sha256("test validation evidence"),
                 "selectedAt": gate["payload"]["issuedAt"], "reviewer": "unit test reviewer", "reason": "Unit contract fixture"}, KEY)


def resource_audit(gate, baseline, candidate):
    rows = {case["replay"]["runId"]: case for report in (baseline, candidate) for case in report["cases"]}
    entries, proofs = [], {}
    for run in baseline["invocation_order"]:
        for name, role in (("analysis", "ANALYST"), ("finalAnswer", "FINAL_ANSWER")):
            stage = rows[run]["replay"].get(name)
            if stage is None:
                continue
            digest = json_hash(stage)
            proofs[digest] = json.dumps(stage).encode()
            entries.append({"invocationId": run + ":" + role, "runId": run, "role": role,
                            "status": "COMPLETED", "inputTokens": 100, "outputTokens": 20, "costUsd": "0.01", "proofSha256": digest})
    ledger = b"UNIT TEST complete ledger"
    digest = sha256(ledger.decode())
    proofs[digest] = ledger
    body = {"kind": "RESOURCE_AUDIT", "experimentId": gate["payload"]["experimentId"], "gateSha256": gate["payloadSha256"],
            "ledgerSha256": digest, "complete": True, "reviewer": "test-evaluator", "reviewedAt": "2026-09-28T00:00:00Z",
            "candidateCount": 1, "searchRounds": 1, "entries": entries, "runOutcomes": {run: "COMPLETED" for run in rows}}
    return seal_resource_audit(body, proofs, KEY)


class EvolutionAcceptanceTest(unittest.TestCase):
    def test_development_comparison_can_filter_search_but_cannot_select_or_release(self):
        sources, gold, manifest, gate, baseline, candidate, audit = comparison('DEVELOPMENT')
        result = reviewed_comparison(manifest, baseline, candidate, sources, gold, acceptance_gate=gate,
                                 resource_audit=audit, evaluator_key=KEY)
        self.assertEqual(result['status'], 'IMPROVED')
        self.assertFalse(result['releaseEvidence']['eligible'])
        with self.assertRaises(ValueError):
            select_candidate(result, KEY, 'test-reviewer', 'premature selection')

    def test_baseline_rejects_unattested_build_and_changed_execution_provenance(self):
        from evolution_acceptance import validate_run_context
        _, _, policy, baseline, _, _, _, _ = fixture()
        for field, value in [('experimentId', 'another-experiment'), ('evaluatorVersion', 'f' * 64),
                             ('runMode', 'HOLDOUT'), ('runId', 'another-run'), ('gitSha', 'd' * 40)]:
            row = copy.deepcopy(baseline['cases'][0])
            row['replay']['runContext'][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                validate_run_context(row, policy['experimentId'], 'BASELINE')
        for scope in ('CLASS_TREE', 'CODE_SOURCE_FILE'):
            row = copy.deepcopy(baseline['cases'][0])
            identity = row['replay']['comparisonIdentity']
            identity['runtimeBuild']['runtimeArtifact'].update(scope=scope, sha256='d' * 64)
            identity['runtimeBuildSha256'] = json_hash(identity['runtimeBuild'])
            row['replay']['runContext']['runtimeBuildSha256'] = identity['runtimeBuildSha256']
            with self.subTest(scope=scope), self.assertRaises(ValueError):
                validate_run_context(row, policy['experimentId'], 'BASELINE')

    def test_baseline_gate_binds_review_policy_code_and_evidence_level(self):
        sources, gold, policy, baseline, review, proofs, gate, _ = fixture()
        self.assertEqual(require_gate(gate, KEY)["status"], "ACCEPTED")
        changed = copy.deepcopy(gate)
        changed["payload"]["policy"]["minMeaningfulDelta"] = .01
        with self.assertRaisesRegex(ValueError, "signature"):
            require_gate(changed, KEY)
        review["evidenceLevel"] = "UNIT_TEST"
        mechanism = freeze_baseline(baseline, sources, gold, policy, review, proofs, KEY)
        with self.assertRaisesRegex(ValueError, "real independently"):
            require_gate(mechanism, KEY)
        with self.assertRaises(ValueError):
            freeze_baseline(baseline, sources, gold, policy, review, {}, KEY)

    def test_primary_improvement_is_separate_from_holdout_release_eligibility(self):
        sources, gold, manifest, gate, baseline, candidate, audit = comparison()
        result = reviewed_comparison(manifest, baseline, candidate, sources, gold, acceptance_gate=gate, resource_audit=audit, evaluator_key=KEY)
        self.assertEqual(result["status"], "IMPROVED", result)
        self.assertFalse(result["releaseEvidence"]["eligible"])
        self.assertEqual(result["stage_decisions"], {"analysis": "IMPROVED", "finalAnswer": "IMPROVED"})
        self.assertEqual(result["metrics"]["finalAnswer"]["appropriate_refusal"]["case_count"], 0)
        verified(result["signedReleaseEvidence"], KEY, "RELEASE_EVIDENCE")

    def test_holdout_is_consumed_by_source_groups_across_new_experiment_ids(self):
        sources, gold, manifest, gate, baseline, candidate, audit = comparison("HOLDOUT")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "holdout.sqlite"
            selected = selected_fixture(gate, manifest)
            reservation = holdout_reservation(manifest, gate, KEY, path, reserve=True, selection=selected)
            for report in (baseline, candidate):
                report["holdout_reservation_sha256"] = json_hash(reservation)
            result = reviewed_comparison(manifest, baseline, candidate, sources, gold, acceptance_gate=gate, resource_audit=audit,
                                     evaluator_key=KEY, holdout_ledger=path, selection=selected)
            self.assertEqual(result["status"], "IMPROVED", result)
            self.assertTrue(result["releaseEvidence"]["eligible"])
            self.assertEqual(result["releaseEvidence"]["authorization"], "PENDING_HUMAN_APPROVAL")
            other_gate = copy.deepcopy(gate["payload"])
            other_gate["policy"]["experimentId"] = other_gate["experimentId"] = "new-experiment"
            other_gate["review"]["policySha256"] = json_hash(other_gate["policy"])
            other_gate = seal(other_gate, KEY)
            other_manifest = {**manifest, "acceptanceGateSha256": other_gate["payloadSha256"]}
            other_selected = selected_fixture(other_gate, other_manifest)
            other_manifest["selectionSha256"] = other_selected["payloadSha256"]
            with self.assertRaisesRegex(ValueError, "consumed"):
                holdout_reservation(other_manifest, other_gate, KEY, path, reserve=True, selection=other_selected)

    def test_cost_latency_unknown_usage_and_generator_cost_cannot_be_hidden(self):
        for change, expected in (("unknown", "INCONCLUSIVE"), ("latency", "NO_IMPROVEMENT"),
                                  ("generator-cost", "NO_IMPROVEMENT"), ("incomplete", "INCONCLUSIVE")):
            with self.subTest(change=change):
                sources, gold, manifest, gate, baseline, candidate, audit = comparison()
                body = copy.deepcopy(audit["payload"])
                if change == "unknown":
                    body["entries"][0]["costUsd"] = None
                elif change == "incomplete":
                    body["complete"] = False
                elif change == "generator-cost":
                    body["entries"].append({**body["entries"][0], "invocationId": "generator-one", "runId": "generator-one",
                                            "role": "GENERATOR", "costUsd": "25"})
                else:
                    for case in candidate["cases"]:
                        case["replay"]["finalAnswer"]["durationMs"] = 5000
                    candidate = rate(candidate, sources, gold)
                    body = resource_audit(gate, baseline, candidate)["payload"]
                result = reviewed_comparison(manifest, baseline, candidate, sources, gold, acceptance_gate=gate,
                                         resource_audit=seal(body, KEY), evaluator_key=KEY)
                self.assertEqual(result["status"], expected, result)
                self.assertFalse(result["releaseEvidence"]["eligible"])

    def test_component_only_improvement_and_posthoc_policy_changes_do_not_release(self):
        sources, gold, manifest, gate, baseline, candidate, audit = comparison()
        for case in candidate["cases"][:2]:
            case["quality_review"]["stages"]["finalAnswer"]["dimensions"]["claim_support"].update(status="FAIL", reason="Unit uncertainty control")
        uncertain = reviewed_comparison(manifest, baseline, candidate, sources, gold, acceptance_gate=gate, resource_audit=audit, evaluator_key=KEY)
        self.assertGreater(uncertain["metrics"]["finalAnswer"]["claim_support"]["mean_paired_delta"], .1)
        self.assertEqual(uncertain["status"], "INCONCLUSIVE")
        for case in candidate["cases"]:
            case["quality_review"]["stages"]["finalAnswer"]["dimensions"]["claim_support"].update(status="FAIL", reason="Unit control")
        result = reviewed_comparison(manifest, baseline, candidate, sources, gold, acceptance_gate=gate, resource_audit=audit, evaluator_key=KEY)
        self.assertEqual(result["stage_decisions"]["analysis"], "IMPROVED")
        self.assertEqual(result["status"], "NO_IMPROVEMENT")
        manifest["minimumGroups"] = 2
        with self.assertRaisesRegex(ValueError, "frozen"):
            reviewed_comparison(manifest, baseline, candidate, sources, gold, acceptance_gate=gate, resource_audit=audit, evaluator_key=KEY)

    def test_paired_runner_alternates_and_rejects_a_changed_gate_before_calls(self):
        _, _, manifest, gate, baseline, candidate, _ = comparison()
        reports = (baseline, candidate)
        runs = [{**case["request"], "repeatId": 1} for report in reports for case in report["cases"]]
        execution = {"schemaVersion": 2, "context": execution_context(baseline), "cases": [{key: value for key, value in case["case_definition"].items()
                                                     if key not in {"bundleId", "repeatId"}} for case in baseline["cases"]], "runs": runs}
        replies = {case["request"]["runId"]: case["replay"] for report in reports for case in report["cases"]}
        calls = []
        def invoke(request):
            calls.append(request["runId"])
            return replies[request["runId"]]
        output = run_paired_cases(execution, baseline["execution_file_sha256"], invoke, manifest, gate, KEY)
        self.assertEqual(calls, baseline["invocation_order"])
        self.assertTrue(all(report["status"] == "PASS" for report in output))
        changed = copy.deepcopy(gate)
        changed["payload"]["policy"]["limits"]["maxTotalTokens"] += 1
        with self.assertRaises(ValueError):
            run_paired_cases(execution, baseline["execution_file_sha256"], invoke, manifest, changed, KEY)
        self.assertEqual(len(calls), 6)

    def test_candidate_failure_stays_in_metric_denominators_and_cost_audit(self):
        sources, gold, manifest, gate, baseline, candidate, _ = comparison()
        case = candidate["cases"][0]
        case["replay"].update(status="FAILED", errorCode="FINAL_ANSWER_FAILED")
        case["replay"]["finalAnswer"].update(status="FAILED", errorCode="TIMEOUT")
        case["passed"] = False
        candidate["status"] = "FAIL"
        candidate = rate(candidate, sources, gold)
        body = resource_audit(gate, baseline, candidate)["payload"]
        body["runOutcomes"][case["replay"]["runId"]] = "METHOD_FAILURE"
        for entry in body["entries"]:
            if entry["runId"] == case["replay"]["runId"] and entry["role"] == "FINAL_ANSWER":
                entry["status"] = "FAILED"
        result = reviewed_comparison(manifest, baseline, candidate, sources, gold, acceptance_gate=gate,
                                 resource_audit=seal(body, KEY), evaluator_key=KEY)
        self.assertEqual(result["status"], "NO_IMPROVEMENT", result)
        self.assertEqual(result["failure_scored_count"], 1)
        self.assertEqual(result["metrics"]["finalAnswer"]["claim_support"]["case_count"], 3)
        self.assertEqual(result["resources"]["calls"]["FINAL_ANSWER"], 6)
        self.assertEqual(result["resources"]["candidate_failures"], 1)

    def test_unknown_submission_stops_pairing_without_reissuing_calls(self):
        _, _, manifest, gate, baseline, candidate, _ = comparison()
        cases = [{key: value for key, value in row["case_definition"].items() if key not in {"bundleId", "repeatId"}}
                 for row in baseline["cases"]]
        runs = [{**row["request"], "repeatId": 1} for report in (baseline, candidate) for row in report["cases"]]
        calls = []
        def invoke(request):
            calls.append(request["runId"])
            raise OSError("unit-test ambiguous provider response")
        reports = run_paired_cases({"schemaVersion": 2, "context": execution_context(baseline), "cases": cases, "runs": runs},
                                   baseline["execution_file_sha256"], invoke, manifest, gate, KEY)
        self.assertEqual(len(calls), 1)
        self.assertTrue(all(report["status"] == "BLOCKED" for report in reports))

    def test_key_group_uncertainty_and_actual_model_change_prevent_acceptance(self):
        for change in ("group-size", "model-revision", "reordered-calls"):
            with self.subTest(change=change):
                sources, gold, manifest, gate, baseline, candidate, audit = comparison()
                if change == "group-size":
                    body = copy.deepcopy(gate["payload"])
                    group = body["policy"]["cohorts"]["VALIDATION"]["keyGroups"]
                    group["declared-test-group"] = group["declared-test-group"][:2]
                    body["review"]["policySha256"] = json_hash(body["policy"])
                    gate = seal(body, KEY)
                    manifest["acceptanceGateSha256"] = gate["payloadSha256"]
                    for report in (baseline, candidate):
                        report["comparison_manifest_sha256"] = json_hash(manifest)
                    audit = resource_audit(gate, baseline, candidate)
                elif change == "model-revision":
                    for case in candidate["cases"]:
                        case["replay"]["analysis"]["observations"][-1]["actualModel"] = "other-revision"
                    candidate = rate(candidate, sources, gold)
                    audit = resource_audit(gate, baseline, candidate)
                else:
                    candidate["invocation_order"] = list(reversed(candidate["invocation_order"]))
                result = reviewed_comparison(manifest, baseline, candidate, sources, gold, acceptance_gate=gate,
                                         resource_audit=audit, evaluator_key=KEY)
                self.assertEqual(result["status"], "INCONCLUSIVE", result)

    def test_selection_uses_signed_validation_and_missing_selection_stops_holdout(self):
        sources, gold, manifest, gate, baseline, candidate, audit = comparison()
        result = reviewed_comparison(manifest, baseline, candidate, sources, gold, acceptance_gate=gate, resource_audit=audit, evaluator_key=KEY)
        selected = select_candidate(result, KEY, "unit evaluator", "Unit selection only")
        self.assertEqual(verified(selected, KEY, "CANDIDATE_SELECTION")["candidateBundleSha256"], manifest["candidateBundleSha256"])
        result["releaseEvidence"]["comparisonStatus"] = "NO_IMPROVEMENT"
        with self.assertRaises(ValueError):
            select_candidate(result, KEY, "unit evaluator", "Changed evidence")
        _, _, holdout, gate, _, _, _ = comparison("HOLDOUT")
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "selected-candidate"):
                holdout_reservation(holdout, gate, KEY, Path(directory) / "holdout.sqlite", reserve=True)

    def test_paired_cli_preserves_each_submission_and_response_without_network(self):
        from test_evolution_experiment import reservations
        _, _, manifest, gate, baseline, candidate, _ = comparison()
        definitions = [{key: value for key, value in row["case_definition"].items() if key not in {"bundleId", "repeatId"}}
                       for row in baseline["cases"]]
        runs = [{**row["request"], "repeatId": 1} for report in (baseline, candidate) for row in report["cases"]]
        execution = {"schemaVersion": 2, "context": execution_context(baseline), "cases": definitions, "runs": runs}
        raw = json.dumps(execution).encode()
        replies = {row["request"]["runId"]: {**row["replay"], "executionFileSha256": sha256(raw.decode())}
                   for report in (baseline, candidate) for row in report["cases"]}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "execution.json").write_bytes(raw)
            for name, value in (("comparison.json", manifest), ("gate.json", gate)):
                (root / name).write_text(json.dumps(value), encoding="utf-8")
            (root / 'resources.json').write_text(json.dumps(reservations()), encoding='utf-8')
            args = ["run_evolution_replay.py", "--input", str(root / "execution.json"), "--output", str(root / "paired"),
                    "--paired", "--comparison-manifest", str(root / "comparison.json"), "--acceptance-gate", str(root / "gate.json"), "--max-runs", "6",
                    '--experiment-ledger', str(root / 'ledger.sqlite'), '--resource-reservations', str(root / 'resources.json')]
            def response(request, **_):
                return io.BytesIO(json.dumps(replies[json.loads(request.data)["runId"]]).encode())
            with patch.dict(os.environ, {"STOCKSAGE_ADMIN_TOKEN": "unit-test", "STOCKSAGE_EVOLUTION_EVALUATOR_KEY": KEY.decode()}), \
                    patch("sys.argv", args), patch("urllib.request.urlopen", side_effect=response) as mocked, redirect_stdout(io.StringIO()) as output:
                self.assertEqual(replay_main(), 0)
            self.assertEqual(mocked.call_count, 6)
            self.assertEqual(json.loads(output.getvalue())["status"], "PASS")
            journal = [json.loads(line) for line in (root / "paired/invocations.jsonl").read_text(encoding="utf-8").splitlines()]
            self.assertEqual([row["status"] for row in journal], ["SUBMITTED", "COMPLETED"] * 6)
            self.assertEqual(json.loads((root / "paired/candidate.json").read_text(encoding="utf-8"))["status"], "PASS")
            budget = json.loads((root / 'paired/resource-review/ledger.json').read_text(encoding='utf-8'))
            self.assertEqual(budget['callsReserved'], {'ANALYST': 6, 'FINAL_ANSWER': 6, 'GENERATOR': 0, 'JUDGE': 0})
            self.assertEqual(budget['tokensObserved'], 1440)
            args[args.index('--output') + 1] = str(root / 'resumed')
            with patch.dict(os.environ, {'STOCKSAGE_ADMIN_TOKEN': 'unit-test', 'STOCKSAGE_EVOLUTION_EVALUATOR_KEY': KEY.decode()}), \
                    patch('sys.argv', args), patch('urllib.request.urlopen', side_effect=AssertionError('duplicate model call')), redirect_stdout(io.StringIO()):
                self.assertEqual(replay_main(), 0)
            resumed = json.loads((root / 'resumed/candidate.json').read_text(encoding='utf-8'))
            original = json.loads((root / 'paired/candidate.json').read_text(encoding='utf-8'))
            self.assertEqual(resumed['started_at'], original['started_at'])
            self.assertEqual(resumed['cases'], original['cases'])
            oversized = reservations()
            for row in oversized['roles'].values():
                row.update(maxTokens=100000, maxCostUsd='0.2')
            (root / 'resources.json').write_text(json.dumps(oversized), encoding='utf-8')
            args[args.index('--output') + 1] = str(root / 'over-budget')
            args[args.index('--experiment-ledger') + 1] = str(root / 'budget-check.sqlite')
            with patch.dict(os.environ, {'STOCKSAGE_ADMIN_TOKEN': 'unit-test', 'STOCKSAGE_EVOLUTION_EVALUATOR_KEY': KEY.decode()}), \
                    patch('sys.argv', args), patch('urllib.request.urlopen', side_effect=AssertionError('over-budget submission')), redirect_stdout(io.StringIO()) as output:
                self.assertEqual(replay_main(), 4)
            self.assertEqual(json.loads(output.getvalue())['status'], 'BUDGET_EXHAUSTED')
            stopped = json.loads((root / 'over-budget/baseline.json').read_text(encoding='utf-8'))
            self.assertEqual(stopped['blocked']['usage_status'], 'NOT_CALLED')
            self.assertEqual(stopped['invocation_order'], [])


if __name__ == "__main__":
    unittest.main()
