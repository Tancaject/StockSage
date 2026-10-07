"""Freeze the source-checked batch draft into native cases and AI-only gold."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
from decimal import Decimal, localcontext
import hashlib
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT.parents[1]))

from eval_common import json_hash, sha256
from evolution_dataset import (REAL_EVIDENCE_FIELDS, REAL_ORIGIN, REAL_SCOPE, case_hash,
                               derived_value, validate_cases, validate_facts, validate_real_expectations)
from evolution_rubric import DIMENSIONS

POLICY = {"absoluteTolerance": "0.01", "decimalPlaces": 4, "rounding": "HALF_EVEN"}
OPERATIONS = {"ADD": "SUM", "SUM": "SUM", "SUBTRACT": "DIFFERENCE", "DIFFERENCE": "DIFFERENCE",
              "DIVIDE": "RATIO", "RATIO": "RATIO", "PERCENT_CHANGE": "PERCENT_CHANGE", "PERCENT_OF": "PERCENT_OF",
              "SUBTRACT_SUM": "DIFFERENCE"}


def artifact(root: Path, relative: str) -> tuple[Path, str]:
    path = (root / relative).resolve()
    if not path.is_relative_to(root.resolve()):
        raise ValueError("Source artifact must stay inside draft directory: " + relative)
    return path, hashlib.sha256(path.read_bytes()).hexdigest()


def gold_for(draft: dict, case: dict, frozen_at: str) -> tuple[dict, list[dict]]:
    validate_facts(draft["facts"], {row["evidenceId"] for row in case["evidence"]})
    facts = {fact["field"]: dict(fact) for fact in draft["facts"]}
    if len(facts) != len(draft["facts"]):
        raise ValueError("Draft fact fields must be unique for formula references: " + draft["caseId"])
    derivations, audit = {}, []
    for derivation in draft["derivations"]:
        field = derivation["output"]
        operation = OPERATIONS.get(derivation["operation"])
        if operation is None or field in facts:
            raise ValueError("Unknown operation or duplicate output: " + str(derivation))
        if not isinstance(derivation["inputs"], list) or len(derivation["inputs"]) < 2:
            raise ValueError("Each draft formula requires at least two field references")
        inputs = [dict(facts[name]) for name in derivation["inputs"]]
        output = {key: derivation[key] for key in ("evidenceId", "period", "unit", "currency")}
        output.update(field=field, value="0")
        original_inputs = [dict(row) for row in inputs]
        if len(inputs) > 2:
            if derivation["operation"] not in {"ADD", "SUM", "SUBTRACT_SUM"}:
                raise ValueError("Only sums and SUBTRACT_SUM accept more than two inputs")
            if any(row["unit"] != inputs[0]["unit"] or row["currency"] != inputs[0]["currency"] for row in inputs):
                raise ValueError("Compound formula requires identical units and currencies")
            with localcontext() as context:
                context.prec = 80
                total = sum(Decimal(row["value"]) for row in inputs[1:])
            inputs = [inputs[0], {"evidenceId": output["evidenceId"], "field": field + "SourceSum",
                                  "value": str(total), "unit": inputs[0]["unit"], "currency": inputs[0]["currency"],
                                  "period": output["period"]}]
        native = {"output": output, "operation": operation, "inputs": inputs}
        output["value"] = derived_value(native, POLICY)
        expected = Decimal(derivation["value"])
        if not expected.is_finite() or abs(expected - Decimal(output["value"])) > Decimal("0.0001"):
            raise ValueError("Draft computed value disagrees with Decimal formula: " + draft["caseId"] + "/" + field)
        facts[field] = output
        derivations[field] = native
        audit.append({"output": dict(output), "draftOperation": derivation["operation"], "inputs": original_inputs,
                      "nativeDerivation": native})
    required = draft["requiredFactFields"]
    if not isinstance(required, list) or not required or len(set(required)) != len(required):
        raise ValueError("requiredFactFields must be a nonempty unique list: " + draft["caseId"])
    gold = {"schemaVersion": 2, "caseId": case["caseId"], "caseSha256": case_hash(case),
            "humanReviewStatus": "UNREVIEWED", "reviewType": "AI_SOURCE_CHECKED",
            "reviewer": "AI-source-check-not-human", "reviewedAt": frozen_at,
            "reviewNotes": "AI source-checked offline trial only; no human acceptance. Amounts may be converted exactly to gold units; "
                           "ratios and percentages use two decimal places. All numeric checks use absolute tolerance 0.01; "
                           "gold calculations use four decimal places and HALF_EVEN. Only requiredFactFields are mandatory answer facts. "
                           + " ".join(draft.get("reviewNotes", [])),
            "dimensions": {name: {"status": "NO_DATA", "reason": "Source expectations, not an answer rating"} for name in DIMENSIONS},
            "facts": [facts[name] for name in required], "numericPolicy": dict(POLICY),
            "derivations": [derivations[name] for name in required if name in derivations],
            "requiredPoints": [{"id": row["id"].lower(), "text": row["description"]} for row in draft["requiredPoints"]],
            "expectedResponse": "ANSWER"}
    validate_facts(gold["facts"], {row["evidenceId"] for row in case["evidence"]})
    validate_real_expectations(gold, case)
    return gold, audit


def prepare(draft_path: Path, output_dir: Path) -> dict:
    outputs = [output_dir / name for name in ("cases.jsonl", "gold.jsonl", "source-manifest.json")]
    if any(path.exists() for path in outputs):
        raise ValueError("Refusing to replace frozen output; use a new --output-dir")
    draft_bytes = draft_path.read_bytes()
    draft = json.loads(draft_bytes.decode("utf-8-sig"))
    if draft.get("schema") != "batch_learning_dataset_draft_v1" or draft.get("reviewAuthority") != "AI_OFFLINE_TRIAL_NOT_HUMAN_GOLD":
        raise ValueError("Expected the source-checked AI offline batch draft")
    frozen_at = datetime.now(timezone.utc).isoformat()
    manifest = {"schema": "batch_learning_source_manifest_v1", "draft_sha256": hashlib.sha256(draft_bytes).hexdigest(),
                "draftStatus": draft["status"], "frozenAt": frozen_at, "reviewAuthority": draft["reviewAuthority"],
                "numericPolicy": dict(POLICY), "sources": [], "derivationAudit": {}}
    cases = []
    for row in draft["cases"]:
        if row["issuerId"] not in draft["plannedSplits"][row["split"]] or row["issuerId"] in draft["excludedPriorIssuers"]:
            raise ValueError("Case issuer does not belong to the predeclared fresh split: " + row["caseId"])
        evidence = []
        for index, source in enumerate(row["sources"], 1):
            _, download_hash = artifact(draft_path.parent, source["downloadPath"])
            extraction, extraction_hash = artifact(draft_path.parent, source["extractionPath"])
            if download_hash != source["downloadSha256"]:
                raise ValueError("Downloaded source changed: " + source["downloadPath"])
            extracted = extraction.read_text(encoding="utf-8")
            if source["rawText"] not in {extracted, extracted.rstrip("\r\n")}:
                raise ValueError("Raw excerpt differs from extraction artifact: " + source["extractionPath"])
            if datetime.fromisoformat(source["retrievedAt"].replace("Z", "+00:00")) > datetime.fromisoformat(frozen_at):
                raise ValueError("Frozen timestamp precedes source retrieval")
            item = {key: source[key] for key in REAL_EVIDENCE_FIELDS - {"evidenceId", "target", "rawSha256", "visibleSha256"}}
            item.update(evidenceId="E" + str(index), target=row["ticker"], rawSha256=sha256(source["rawText"]),
                        visibleSha256=sha256(source["visibleText"]))
            evidence.append(item)
            manifest["sources"].append({"caseId": row["caseId"], "evidenceId": item["evidenceId"],
                                        **{key: source[key] for key in ("sourceRef", "sourceVersion", "downloadPath", "extractionPath", "retrievedAt")},
                                        "downloadSha256": download_hash, "extractionSha256": extraction_hash,
                                        "rawSha256": item["rawSha256"], "visibleSha256": item["visibleSha256"]})
        cases.append({"schemaVersion": 2, "origin": REAL_ORIGIN, "executionScope": REAL_SCOPE,
                      **{key: row[key] for key in ("caseId", "ticker", "issuerId", "split", "reportFamilyId", "leakageGroup", "query")},
                      "resolvedQuery": row["query"], "history": [], "asOf": frozen_at[:10],
                      "requestAttributes": {"reportPeriod": "quarterly", "reportCount": 2},
                      "timeSensitivity": "HISTORICAL", "initialTaskOutcome": "COMPLETED", "evidence": evidence,
                      "provenance": {"authorizationRef": "user-request-20261002-batch-learning", "authorizedBy": "user",
                                     "capturedAt": frozen_at, "asOfInstant": frozen_at,
                                     "reviewedBy": "AI-source-check-not-human", "reviewedAt": frozen_at,
                                     "sourceManifestSha256": "0" * 64}})
    validate_cases(cases)
    # Resolve all formulas before freezing the manifest. Intermediate arithmetic
    # remains auditable without becoming a required fact in the user's answer.
    for row, case in zip(draft["cases"], cases):
        _, audit = gold_for(row, case, frozen_at)
        manifest["derivationAudit"][row["caseId"]] = audit
    manifest_text = json.dumps(manifest, ensure_ascii=False, indent=2) + "\n"
    manifest_hash = sha256(manifest_text)
    for case in cases:
        case["provenance"]["sourceManifestSha256"] = manifest_hash
    gold = [gold_for(row, case, frozen_at)[0] for row, case in zip(draft["cases"], cases)]
    validate_cases(cases)
    output_dir.mkdir(parents=True, exist_ok=True)
    for path, text in zip(outputs, ("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in cases),
                                   "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in gold), manifest_text)):
        with path.open("x", encoding="utf-8", newline="\n") as target:
            target.write(text)
    return {"status": "FROZEN_AI_DIAGNOSTIC", "case_count": len(cases), "cases_sha256": json_hash(cases),
            "gold_sha256": json_hash(gold), "source_manifest_sha256": manifest_hash, "output_dir": str(output_dir)}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, default=ROOT / "dataset-draft.json")
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    try:
        print(json.dumps(prepare(args.input, args.output_dir), ensure_ascii=False))
        return 0
    except (ValueError, KeyError, TypeError, ArithmeticError, OSError) as error:
        parser.exit(2, "Dataset freeze failed: " + str(error) + "\n")


if __name__ == "__main__":
    raise SystemExit(main())
