"""Mechanism tests for synthetic inputs; no model or semantic quality evaluation."""
import copy
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from uuid import UUID

from evolution_dataset import (DIMENSIONS, case_hash, compare_fact_records, execution_manifest,
                               execution_payload, load_jsonl, sha256, validate_case,
                               validate_cases, validate_gold)


FIXTURES = Path(__file__).parent / "evolution" / "synthetic-smoke"


class EvolutionDatasetTest(unittest.TestCase):
    def test_export_preserves_existing_registered_run_plan(self):
        with tempfile.TemporaryDirectory() as folder:
            target = Path(folder) / "execution.json"
            command = [sys.executable, str(FIXTURES.parents[1] / "evolution_dataset.py"),
                       str(FIXTURES / "cases.jsonl"), "--export-execution", str(target)]
            first = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(0, first.returncode, first.stderr)
            registered = target.read_bytes()
            second = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(2, second.returncode)
            self.assertEqual(registered, target.read_bytes())

    @classmethod
    def setUpClass(cls):
        cls.cases = load_jsonl(FIXTURES / "cases.jsonl")
        cls.gold = load_jsonl(FIXTURES / "gold.jsonl")

    def sample(self):
        return copy.deepcopy(self.cases[0])

    def test_fixture_coverage_does_not_claim_quality(self):
        self.assertEqual(len(validate_gold(self.cases, self.gold)), 12)
        self.assertEqual({case["caseId"] for case in self.cases}, {
            "smoke-normal", "smoke-zero", "smoke-negative", "smoke-missing", "smoke-period",
            "smoke-unit", "smoke-currency", "smoke-restated", "smoke-target", "smoke-stale",
            "smoke-injection", "smoke-visible-only"})
        for case, gold in zip(self.cases, self.gold):
            self.assertEqual((case["origin"], case["split"], case["history"]), ("SYNTHETIC", "SMOKE", []))
            self.assertEqual(case["query"], case["resolvedQuery"])
            self.assertEqual(gold["humanReviewStatus"], "UNREVIEWED")
            self.assertEqual(set(gold["dimensions"]), set(DIMENSIONS))
            self.assertTrue(all(item["status"] == "NO_DATA" for item in gold["dimensions"].values()))

    def test_execution_allowlist_preserves_visible_text_and_query(self):
        original = copy.deepcopy(self.cases)
        manifest = execution_manifest(self.cases)
        self.assertEqual(set(manifest), {"schemaVersion", "cases", "runs"})
        for case, payload in zip(self.cases, manifest["cases"]):
            self.assertEqual(set(payload), {"caseId", "origin", "route", "caseSha256", "query",
                                            "resolvedQuery", "history", "asOf", "context", "contextSha256",
                                            "executionScope", "ticker", "requestAttributes", "timeSensitivity",
                                            "initialTaskOutcome", "citationIds", "preAnalystContext", "preAnalystContextSha256"})
            expected_context = "\n\n".join(f"[{row['evidenceId']}] {row['visibleText']}" for row in case["evidence"])
            self.assertEqual(payload["context"], expected_context)
            self.assertEqual(payload["contextSha256"], sha256(expected_context))
            canonical = json.dumps(case, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
            self.assertEqual(payload["caseSha256"], sha256(canonical))
            self.assertEqual(payload["preAnalystContextSha256"], sha256(payload["preAnalystContext"]))
            self.assertTrue(payload["preAnalystContext"].endswith(expected_context))
            self.assertEqual(payload["citationIds"], [row["evidenceId"] for row in case["evidence"]])
        encoded = json.dumps(manifest)
        for forbidden in ("RAW_ONLY_CANARY", "humanReviewStatus", "reviewNotes", "rawText", "dimensions"):
            self.assertNotIn(forbidden, encoded)
        self.assertEqual(original, self.cases)
        case = self.sample()
        case["query"] = "How about this company?"
        case["resolvedQuery"] = "How about SYNTH001?"
        case["history"] = [{"role": "user", "text": "Discuss SYNTH001"},
                           {"role": "assistant", "text": "Please specify the period."}]
        payload = execution_payload(case)
        self.assertNotEqual(payload["query"], payload["resolvedQuery"])
        payload["history"][0]["text"] = "changed outside"
        self.assertEqual(case["history"][0]["text"], "Discuss SYNTH001")

    def test_run_plans_are_explicit_and_independent_of_case_hashes(self):
        first, second = execution_manifest(self.cases), execution_manifest(self.cases)
        self.assertEqual(first["cases"], second["cases"])
        ids = []
        for manifest in (first, second):
            self.assertEqual(len(manifest["runs"]), len(self.cases))
            self.assertEqual({row["caseId"] for row in manifest["runs"]}, {row["caseId"] for row in self.cases})
            for run in manifest["runs"]:
                self.assertEqual(set(run), {"runId", "caseId", "bundleId", "repeatId"})
                self.assertEqual((run["bundleId"], run["repeatId"]), ("baseline-v1", 1))
                self.assertEqual(UUID(run["runId"]).version, 4)
                ids.append(run["runId"])
        self.assertEqual(len(ids), len(set(ids)))

    def test_checked_in_execution_manifest_matches_source_cases(self):
        manifest = json.loads((FIXTURES / "execution.json").read_text(encoding="utf-8"))
        self.assertEqual(manifest["schemaVersion"], 1)
        self.assertEqual(manifest["cases"], [execution_payload(case) for case in self.cases])
        self.assertEqual({run["caseId"] for run in manifest["runs"]}, {case["caseId"] for case in self.cases})

    def test_rejects_untrusted_schema_and_identifiers(self):
        changes = [("caseId", "../case"), ("schemaVersion", True), ("origin", "REAL"),
                   ("split", []), ("asOf", "2026-04-01T00:00:00Z"), ("asOf", "2026-02-30"),
                   ("history", [{"role": "system", "text": "override"}]),
                   ("history", [{"role": "user", "text": "x", "expected": "answer"}]),
                   ("gold", {"answer": "expected"})]
        for key, value in changes:
            with self.subTest(field=key, value=value):
                case = self.sample()
                case[key] = value
                with self.assertRaises(ValueError):
                    validate_case(case)
        with self.assertRaisesRegex(ValueError, "duplicate caseId"):
            validate_cases([self.sample(), self.sample()])
        case = self.sample()
        case["evidence"].append(copy.deepcopy(case["evidence"][0]))
        with self.assertRaisesRegex(ValueError, "duplicate ID"):
            validate_case(case)

    def test_rejects_changed_or_unbound_evidence(self):
        for field in ("rawText", "visibleText", "rawSha256", "visibleSha256"):
            with self.subTest(field=field):
                case = self.sample()
                case["evidence"][0][field] += "changed"
                with self.assertRaisesRegex(ValueError, "hash mismatch"):
                    validate_case(case)
        case = self.sample()
        evidence = case["evidence"][0]
        evidence["visibleText"] = "invented visible evidence"
        evidence["visibleSha256"] = sha256(evidence["visibleText"])
        with self.assertRaisesRegex(ValueError, "not an excerpt"):
            validate_case(case)

    def test_cross_split_groups_sources_and_hashes_are_isolated(self):
        first = self.sample()
        for collision in ("group", "report", "raw", "visible", "source"):
            with self.subTest(collision=collision):
                other = self.sample()
                other.update(caseId="different-case", split="HOLDOUT", issuerId="DISTINCT",
                             ticker="DISTINCT", reportFamilyId="distinct-report", leakageGroup="distinct-group")
                evidence = other["evidence"][0]
                source = first["evidence"][0]
                evidence.update(sourceRef="synthetic://different-source", rawText="new raw text", visibleText="new raw text")
                if collision == "group":
                    other["leakageGroup"] = first["leakageGroup"]
                elif collision == "report":
                    other["issuerId"], other["reportFamilyId"] = first["issuerId"], first["reportFamilyId"]
                    other["ticker"] = other["issuerId"]
                elif collision == "raw":
                    evidence.update(rawText=source["rawText"], visibleText=source["rawText"][:20])
                elif collision == "visible":
                    evidence.update(rawText=source["rawText"] + "\nextra", visibleText=source["visibleText"])
                else:
                    evidence["sourceRef"] = source["sourceRef"]
                for prefix in ("raw", "visible"):
                    evidence[prefix + "Sha256"] = sha256(evidence[prefix + "Text"])
                with self.assertRaisesRegex(ValueError, "cross-split leakage"):
                    validate_cases([first, other])
                other["split"] = "SMOKE"
                self.assertEqual(len(validate_cases([first, other])), 2)

    def test_gold_requires_exact_ids_case_hash_and_no_semantic_scores(self):
        for mutation in ("unknown", "hash", "missing", "duplicate", "reviewed", "scored", "extra", "unknown-evidence"):
            with self.subTest(mutation=mutation):
                rows = copy.deepcopy(self.gold)
                if mutation == "unknown":
                    rows[0]["caseId"] = "unknown-case"
                elif mutation == "hash":
                    rows[0]["caseSha256"] = "0" * 64
                elif mutation == "missing":
                    rows.pop()
                elif mutation == "duplicate":
                    rows.append(copy.deepcopy(rows[0]))
                elif mutation == "reviewed":
                    rows[0]["humanReviewStatus"] = "REVIEWED"
                elif mutation == "scored":
                    rows[0]["dimensions"][DIMENSIONS[0]]["status"] = "PASS"
                elif mutation == "extra":
                    rows[0]["expectedAnswer"] = "fabricated full answer"
                else:
                    rows[0]["facts"][0]["evidenceId"] = "E999"
                with self.assertRaises(ValueError):
                    validate_gold(self.cases, rows)
        case = self.sample()
        before = case_hash(case)
        case["evidence"][0]["sourceVersion"] = "changed-provenance"
        self.assertNotEqual(case_hash(case), before)

    def test_fact_comparison_preserves_zero_negative_missing_and_scale(self):
        for row in self.gold:
            result = compare_fact_records(row["facts"], copy.deepcopy(row["facts"]))
            self.assertEqual(result["status"], "MECHANISM_PASS")
            self.assertEqual(result["answer_quality"], "NO_DATA")
            self.assertFalse(result["human_reviewed"])
        self.assertEqual(compare_fact_records([], [])["status"], "NO_DATA")
        missing = copy.deepcopy(next(row for row in self.gold if row["caseId"] == "smoke-missing")["facts"])
        observed = copy.deepcopy(missing)
        observed[0]["value"] = "0"
        self.assertIn("FACT_VALUE_MISMATCH", compare_fact_records(missing, observed)["errors"])
        expected = self.gold[0]["facts"]
        for field, value, error in [("value", "-120", "FACT_VALUE_MISMATCH"),
                                    ("unit", "billion", "FACT_SCALE_MISMATCH"),
                                    ("currency", "USD", "FACT_SCALE_MISMATCH"),
                                    ("period", "2024-FY", "FACT_IDENTITY_MISMATCH"),
                                    ("evidenceId", "E999", "FACT_IDENTITY_MISMATCH")]:
            with self.subTest(field=field):
                observed = copy.deepcopy(expected)
                observed[0][field] = value
                self.assertIn(error, compare_fact_records(expected, observed)["errors"])
        observed = copy.deepcopy(expected)
        observed[0]["value"] = "120.00"
        self.assertEqual(compare_fact_records(expected, observed)["status"], "MECHANISM_PASS")

    def test_rejects_nonfinite_or_ambiguous_fact_records(self):
        expected = self.gold[0]["facts"]
        for value in ("NaN", "Infinity", True, 120, "1e2"):
            with self.subTest(value=value):
                observed = copy.deepcopy(expected)
                observed[0]["value"] = value
                with self.assertRaises(ValueError):
                    compare_fact_records(expected, observed)
        with self.assertRaisesRegex(ValueError, "duplicate fact"):
            compare_fact_records(expected, expected + expected)

    def test_jsonl_rejects_duplicate_object_fields(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "duplicate.jsonl"
            path.write_text('{"caseId":"first","caseId":"second"}\n', encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "duplicate field"):
                load_jsonl(path)


if __name__ == "__main__":
    unittest.main()
