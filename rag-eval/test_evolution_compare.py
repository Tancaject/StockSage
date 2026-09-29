import copy
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from evolution_compare import apply_blind_reviews, blind_material, compare_reports
from evolution_quality import STAGES, apply_reviews
from ordinary_answer_quality import DIMENSIONS, json_hash, sha256
from test_evolution_quality import completed_reviews, sample_report


def experiment(group_count=3, repeats=2):
    identity = {key: sha256(key) for key in ("modelConfigSha256", "fixedFinalPromptSha256", "memorySnapshotSha256")}
    manifest = {"schema": "fundamentals_evolution_comparison_manifest_v1", "baselineBundleId": "baseline-v1",
                "candidateBundleId": "candidate-v1", "baselineBundleSha256": sha256("baseline-method"),
                "candidateBundleSha256": sha256("candidate-method"), "fixedContractSha256": sha256("contract"),
                "minimumGroups": 3, "bootstrapSamples": 200, "confidenceLevel": 0.95, "seed": 123,
                "primaryDimension": "claim_support", "nonRegressionDimensions": list(DIMENSIONS[1:]),
                "maxRunsPerArm": group_count * repeats, "expectedComparisonIdentity": identity, "cases": []}
    reports = {arm: {**sample_report(), "cases": [], "sample_count": group_count * repeats,
                     "unique_case_count": group_count} for arm in ("baseline", "candidate")}
    for index in range(group_count):
        case_id = f"case-{index}"
        digest = sha256(case_id)
        source = sample_report()["cases"][0]["case_definition"]
        evidence_hash, history_hash = source["contextSha256"], json_hash(source["history"])
        manifest["cases"].append({"caseId": case_id, "caseSha256": digest, "issuerId": f"issuer-{index}",
                                  "reportFamilyId": "annual-2025", "repeatIds": list(range(1, repeats + 1)),
                                  "expectedEvidenceSnapshotSha256": evidence_hash,
                                  "expectedHistorySnapshotSha256": history_hash})
        for repeat in range(1, repeats + 1):
            for arm, report in reports.items():
                row = copy.deepcopy(sample_report()["cases"][0])
                row["id"] = f"{case_id}@{repeat}"
                row["case_definition"].update(caseId=case_id, caseSha256=digest, repeatId=repeat,
                                              bundleId=manifest[arm + "BundleId"])
                row["request"].update(caseId=case_id, runId=f"{arm}-{case_id}-{repeat}", bundleId=manifest[arm + "BundleId"])
                replay = row["replay"]
                replay.update(caseId=case_id, caseSha256=digest, repeatId=repeat, runId=row["request"]["runId"])
                replay["methodBundle"].update(bundleId=manifest[arm + "BundleId"], bundleSha256=manifest[arm + "BundleSha256"])
                replay["comparisonIdentity"] = {**identity, "evidenceSnapshotSha256": evidence_hash, "historySnapshotSha256": history_hash}
                for stage in STAGES:
                    call = replay[stage]["observations"][0]
                    if stage == "analysis":
                        call["clientDefaults"] = {"model": "mock-model", "temperature": 0, "maxTokens": 1000}
                        call["actualSystemPromptSha256"] = sha256(arm + " method prompt")
                    else:
                        call.update(temperature=0, maxOutputTokens=1000)
                    replay[stage]["observations"].append({"kind": "model-usage", "scope": STAGES[stage][1],
                                                         "actualModel" if stage == "analysis" else "providerModelName": "mock-model-rev1"})
                configuration = {"provider": {"scope": "API_CONSTRUCTION", "protocol": "OPENAI_COMPATIBLE",
                                               "base": {"host": "mock.invalid"}, "unknownProviderRevision": True},
                                 "analysis": replay["analysis"]["observations"][0]["clientDefaults"],
                                 "finalAnswer": [replay["finalAnswer"]["observations"][0]],
                                 "promptMaxChars": 24000, "analysisTimeoutSeconds": 120}
                identity["modelConfigSha256"] = json_hash(configuration)
                replay["comparisonIdentity"].update(modelConfigSha256=identity["modelConfigSha256"],
                                                    modelConfigurationJson=json.dumps(configuration, ensure_ascii=False, sort_keys=True, separators=(",", ":")),
                                                    modelConfiguration=configuration, modelConfigurationComplete=True)
                report["cases"].append(row)
    for arm, report in reports.items():
        report["dataset_sha256"] = json_hash([row["case_definition"] for row in report["cases"]])
        report["comparison_manifest_sha256"] = json_hash(manifest)
        reviews = completed_reviews(report)
        if arm == "baseline":
            for row in reviews["reviews"]:
                row["dimensions"]["claim_support"] = {"status": "FAIL", "reason": "合成负向对照，仅验证机制。"}
        reports[arm] = apply_reviews(report, reviews)
    return manifest, reports["baseline"], reports["candidate"]


def rereview(report):
    return apply_reviews(report, completed_reviews(report))


class EvolutionCompareTest(unittest.TestCase):
    def test_synthetic_improvement_is_only_grouped_mechanism_evidence(self):
        manifest, baseline, candidate = experiment()
        result = compare_reports(manifest, baseline, candidate)
        metric = result["metrics"]["finalAnswer"]["claim_support"]
        self.assertEqual(result["paired_count"], 6)
        self.assertEqual(metric["repeat_count"], 6)
        self.assertEqual(metric["group_count"], 3)
        self.assertEqual(metric["mean_paired_delta"], 1)
        self.assertEqual(metric["bootstrap_interval"], [1, 1])
        self.assertEqual(result["status"], "INCONCLUSIVE")
        self.assertFalse(result["releaseEvidence"]["eligible"])
        self.assertEqual(result["releaseEvidence"]["authorization"], "NOT_AUTHORIZED")
        self.assertEqual(result, compare_reports(manifest, baseline, candidate))

    def test_repeated_calls_do_not_satisfy_minimum_independent_groups(self):
        manifest, baseline, candidate = experiment(group_count=2, repeats=4)
        result = compare_reports(manifest, baseline, candidate)
        self.assertEqual(result["paired_count"], 8)
        self.assertIn("INSUFFICIENT_INDEPENDENT_GROUPS", result["reasons"])
        self.assertIsNone(result["metrics"]["analysis"]["claim_support"]["bootstrap_interval"])

    def test_missing_or_stale_reviews_and_missing_invocations_are_not_comparable(self):
        for change in ("missing-review", "stale-answer", "missing-call"):
            with self.subTest(change=change):
                manifest, baseline, candidate = experiment()
                if change == "missing-review":
                    candidate["cases"][0].pop("quality_review")
                elif change == "stale-answer":
                    candidate["cases"][0]["replay"]["analysis"]["answer"] = "改变后的报告"
                else:
                    candidate["cases"].pop()
                    candidate["status"] = "BLOCKED"
                result = compare_reports(manifest, baseline, candidate)
                self.assertLess(result["paired_count"], result["planned_invocation_pairs"])
                self.assertTrue(result["excluded"])
                self.assertEqual(result["status"], "INCONCLUSIVE")

    def test_same_source_models_provider_and_fixed_contract_are_required(self):
        for change in ("case", "method", "fixed-contract", "provider", "fixed-final", "history", "actual-model", "client-defaults", "timeout"):
            with self.subTest(change=change):
                manifest, baseline, candidate = experiment()
                replay = candidate["cases"][0]["replay"]
                if change == "case":
                    replay["caseSha256"] = sha256("other-source")
                elif change == "method":
                    replay["methodBundle"]["bundleSha256"] = sha256("other-method")
                elif change == "fixed-contract":
                    replay["methodBundle"]["fixedContractSha256"] = sha256("other-contract")
                elif change == "provider":
                    replay["comparisonIdentity"]["modelConfiguration"]["provider"]["base"]["host"] = "other.invalid"
                elif change in ("fixed-final", "history"):
                    key = {"fixed-final": "fixedFinalPromptSha256", "history": "historySnapshotSha256"}[change]
                    replay["comparisonIdentity"][key] = sha256("other-identity")
                elif change == "actual-model":
                    replay["analysis"]["observations"][1]["actualModel"] = "other-model"
                elif change == "client-defaults":
                    replay["analysis"]["observations"][0]["clientDefaults"]["temperature"] = 0.9
                else:
                    replay["analysis"]["timeoutSeconds"] = 999
                result = compare_reports(manifest, baseline, rereview(candidate))
                self.assertTrue(any(row["id"] == "case-0@1" for row in result["excluded"]))

    def test_missing_actual_model_and_predeclaration_are_visible(self):
        manifest, baseline, candidate = experiment()
        candidate.pop("comparison_manifest_sha256")
        candidate["cases"][0]["replay"]["analysis"]["observations"].pop()
        result = compare_reports(manifest, baseline, rereview(candidate))
        self.assertIn("candidate:PREDECLARED_MANIFEST_BINDING_MISSING_OR_CHANGED", result["reasons"])
        self.assertIn("analysis:MODEL_OR_BUDGET_UNAVAILABLE_OR_CHANGED", result["excluded"][0]["reasons"])

    def test_java_exponent_encoding_preserves_the_exact_configuration_hash(self):
        manifest, baseline, candidate = experiment()
        for report in (baseline, candidate):
            for row in report["cases"]:
                identity = row["replay"]["comparisonIdentity"]
                identity["modelConfiguration"]["analysis"]["temperature"] = 0.0001
                row["replay"]["analysis"]["observations"][0]["clientDefaults"]["temperature"] = 0.0001
                text = json.dumps(identity["modelConfiguration"], ensure_ascii=False, sort_keys=True,
                                  separators=(",", ":")).replace(":0.0001", ":1.0E-4")
                identity.update(modelConfigurationJson=text, modelConfigSha256=sha256(text))
                manifest["expectedComparisonIdentity"]["modelConfigSha256"] = sha256(text)
        for report in (baseline, candidate):
            report["comparison_manifest_sha256"] = json_hash(manifest)
        result = compare_reports(manifest, rereview(baseline), rereview(candidate))
        self.assertEqual(6, result["paired_count"])
        self.assertEqual([], result["excluded"])

    def test_missing_manifest_identity_and_over_budget_plan_are_rejected(self):
        manifest, baseline, candidate = experiment()
        del manifest["expectedComparisonIdentity"]["modelConfigSha256"]
        with self.assertRaisesRegex(ValueError, "comparison identity"):
            compare_reports(manifest, baseline, candidate)
        manifest, baseline, candidate = experiment()
        manifest["maxRunsPerArm"] = 1
        with self.assertRaisesRegex(ValueError, "budget"):
            compare_reports(manifest, baseline, candidate)

    def test_blinded_materials_hide_arm_identity_and_round_trip_human_reviews(self):
        manifest, baseline, candidate = experiment(group_count=1, repeats=1)
        packet, mapping = blind_material(manifest, baseline, candidate)
        raw = json.dumps(packet)
        self.assertNotIn("baseline", raw)
        self.assertNotIn("candidate", raw)
        self.assertNotIn("bundle", raw)
        self.assertEqual(len(packet["items"]), 2)
        for row in mapping["mapping"]:
            self.assertEqual(set(row["labels"].values()), {"baseline", "candidate"})
        for item in packet["items"]:
            for option in item["options"]:
                option.update(reviewer="independent-human-fixture", reviewed_at="2026-09-27T11:00:00+08:00",
                              dimensions={name: {"status": "PASS", "reason": "机制测试中逐项复核。"} for name in DIMENSIONS})
        reviewed = apply_blind_reviews(manifest, baseline, candidate, packet, mapping)
        for report in reviewed:
            self.assertEqual(report["quality_evaluation"]["mechanism_status"], "MECHANISM_PASS")
            self.assertEqual(report["answer_quality"], "NO_DATA")
        packet["items"][0]["options"][0]["answer"] = "另一答案"
        with self.assertRaisesRegex(ValueError, "changed"):
            apply_blind_reviews(manifest, baseline, candidate, packet, mapping)

    def test_cli_writes_read_only_release_evidence_and_does_not_overwrite(self):
        manifest, baseline, candidate = experiment(group_count=1, repeats=1)
        script = Path(__file__).with_name("evolution_compare.py")
        with tempfile.TemporaryDirectory() as folder:
            paths = {name: Path(folder) / (name + ".json") for name in ("manifest", "baseline", "candidate", "output")}
            for name, value in (("manifest", manifest), ("baseline", baseline), ("candidate", candidate)):
                paths[name].write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")
            command = [sys.executable, str(script)]
            for name in paths:
                command.extend(["--" + name, str(paths[name])])
            result = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(result.returncode, 2, result.stderr)
            self.assertEqual(json.loads(result.stdout)["status"], "INCONCLUSIVE")
            output = paths["output"].read_bytes()
            result = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(result.returncode, 2)
            self.assertIn("already exists", result.stderr)
            self.assertEqual(output, paths["output"].read_bytes())


if __name__ == "__main__":
    unittest.main()
