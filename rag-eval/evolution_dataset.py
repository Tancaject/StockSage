"""Validate frozen evolution evidence and separate gold; never infer semantic quality."""
from __future__ import annotations

import argparse
import json
import re
from datetime import date, datetime
from decimal import Decimal, localcontext, ROUND_HALF_EVEN
from pathlib import Path
from uuid import uuid4

from ordinary_answer_quality import DIMENSIONS, json_hash, sha256


CASE_FIELDS = {"schemaVersion", "caseId", "origin", "split", "issuerId", "reportFamilyId",
               "leakageGroup", "query", "resolvedQuery", "history", "asOf", "evidence",
               "executionScope", "ticker", "requestAttributes", "timeSensitivity", "initialTaskOutcome"}
EVIDENCE_FIELDS = {"evidenceId", "target", "sourceRef", "sourceVersion", "publishedAt",
                   "availableAt", "rawText", "visibleText", "rawSha256", "visibleSha256"}
GOLD_FIELDS = {"schemaVersion", "caseId", "caseSha256", "humanReviewStatus", "dimensions",
               "facts", "reviewNotes"}
FACT_FIELDS = {"evidenceId", "field", "value", "unit", "currency", "period"}
SPLITS = {"SMOKE", "DEVELOPMENT", "VALIDATION", "HOLDOUT"}
IDENTIFIER = re.compile(r"[a-z0-9][a-z0-9-]{0,79}\Z")
NUMBER = re.compile(r"[+-]?(?:\d+(?:\.\d*)?|\.\d+)\Z")
REAL_ORIGIN = "PUBLIC_AUTHORIZED"
REAL_SCOPE = "FROZEN_COMPONENT_CHAIN"
PROVENANCE_FIELDS = {"authorizationRef", "authorizedBy", "capturedAt", "asOfInstant", "reviewedBy", "reviewedAt", "sourceManifestSha256"}
REAL_EVIDENCE_FIELDS = EVIDENCE_FIELDS | {"periodStart", "periodEnd", "unit", "currency", "retrievedAt"}
REAL_GOLD_FIELDS = GOLD_FIELDS | {"reviewer", "reviewedAt", "numericPolicy", "derivations", "requiredPoints", "expectedResponse"}


def exact_fields(value: object, fields: set[str], location: str) -> None:
    if not isinstance(value, dict) or set(value) != fields:
        raise ValueError(f"{location}: fields must be exactly {sorted(fields)}")


def nonempty(value: object, location: str) -> None:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{location}: expected nonempty text")


def identifier(value: object, location: str) -> None:
    if not isinstance(value, str) or not IDENTIFIER.fullmatch(value):
        raise ValueError(f"{location}: invalid identifier")


def timestamp(value: object, location: str) -> None:
    nonempty(value, location)
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        if parsed.tzinfo is None:
            raise ValueError("timezone missing")
    except ValueError as error:
        raise ValueError(f"{location}: expected ISO datetime with timezone") from error


def validate_case(case: dict) -> None:
    real = isinstance(case, dict) and case.get("origin") == REAL_ORIGIN
    exact_fields(case, CASE_FIELDS | ({"provenance"} if real else set()), "case")
    if type(case["schemaVersion"]) is not int or case["schemaVersion"] != (2 if real else 1):
        raise ValueError("case: unsupported schemaVersion")
    identifier(case["caseId"], "caseId")
    if case["origin"] not in ("SYNTHETIC", REAL_ORIGIN) or not isinstance(case["split"], str) or case["split"] not in SPLITS:
        raise ValueError("case: unsupported origin or split")
    if not real and (case["executionScope"] != "SYNTHETIC_COMPONENT_CHAIN" or case["ticker"] != case["issuerId"]
            or case["requestAttributes"] != {} or case["timeSensitivity"] != "HISTORICAL"
            or case["initialTaskOutcome"] != "DEGRADED"):
        raise ValueError("case: expected the declared synthetic replay profile")
    if real:
        if (case["executionScope"] != REAL_SCOPE or case["timeSensitivity"] not in
                {"NONE", "REAL_TIME", "RECENT", "HISTORICAL", "UNSPECIFIED"}
                or case["initialTaskOutcome"] not in {"COMPLETED", "DEGRADED"}):
            raise ValueError("case: unsupported authorized replay profile")
        nonempty(case["ticker"], "ticker")
        request_attributes_text(case["requestAttributes"])
        provenance = case["provenance"]
        exact_fields(provenance, PROVENANCE_FIELDS, "provenance")
        for field in ("authorizationRef", "authorizedBy", "reviewedBy"):
            nonempty(provenance[field], "provenance." + field)
        for field in ("capturedAt", "asOfInstant", "reviewedAt"):
            timestamp(provenance[field], "provenance." + field)
        if not isinstance(provenance["sourceManifestSha256"], str) or not re.fullmatch(r"[0-9a-f]{64}", provenance["sourceManifestSha256"]):
            raise ValueError("provenance.sourceManifestSha256 must bind the imported source manifest")
        if datetime.fromisoformat(provenance["asOfInstant"].replace("Z", "+00:00")).date().isoformat() != case["asOf"]:
            raise ValueError("asOf must match the recorded research instant's local date")
    for field in ("issuerId", "reportFamilyId", "leakageGroup", "query", "resolvedQuery"):
        nonempty(case[field], field)
    if not isinstance(case["history"], list):
        raise ValueError("history: expected a list of messages")
    for message in case["history"]:
        exact_fields(message, {"role", "text"}, "history message")
        if message["role"] not in ("user", "assistant"):
            raise ValueError("history: only user and assistant roles are allowed")
        nonempty(message["text"], "history message text")
    if not isinstance(case["asOf"], str) or not re.fullmatch(r"\d{4}-\d{2}-\d{2}", case["asOf"]):
        raise ValueError("asOf: expected ISO local date YYYY-MM-DD")
    date.fromisoformat(case["asOf"])
    if not isinstance(case["evidence"], list) or not case["evidence"]:
        raise ValueError("evidence: expected a nonempty list")
    ids = set()
    for evidence in case["evidence"]:
        exact_fields(evidence, REAL_EVIDENCE_FIELDS if real else EVIDENCE_FIELDS, "evidence")
        evidence_id = evidence["evidenceId"]
        if not isinstance(evidence_id, str) or not re.fullmatch(r"E[1-9][0-9]*", evidence_id) or evidence_id in ids:
            raise ValueError("evidenceId: invalid or duplicate ID")
        ids.add(evidence_id)
        for field in ("target", "sourceRef", "sourceVersion", "rawText", "visibleText"):
            nonempty(evidence[field], field)
        for field in ("publishedAt", "availableAt"):
            timestamp(evidence[field], field)
        if real:
            timestamp(evidence["retrievedAt"], "retrievedAt")
            for field in ("unit", "currency", "periodStart", "periodEnd"):
                if evidence[field] is not None:
                    nonempty(evidence[field], field)
                    if field.startswith("period"):
                        date.fromisoformat(evidence[field])
            if (evidence["periodStart"] is not None and evidence["periodEnd"] is not None
                    and evidence["periodStart"] > evidence["periodEnd"]):
                raise ValueError("periodStart must not follow periodEnd")
            instant = lambda value: datetime.fromisoformat(value.replace("Z", "+00:00"))
            if not (instant(evidence["publishedAt"]) <= instant(evidence["availableAt"])
                    <= instant(case["provenance"]["asOfInstant"])):
                raise ValueError("evidence availability crosses the frozen research time boundary")
            if instant(evidence["retrievedAt"]) > instant(case["provenance"]["capturedAt"]):
                raise ValueError("snapshot capture precedes evidence retrieval")
        for field in ("raw", "visible"):
            if evidence[field + "Sha256"] != sha256(evidence[field + "Text"]):
                raise ValueError(f"{evidence_id}: {field} evidence hash mismatch")
        # Only a contiguous excerpt is exported; hidden text must never reach the model.
        if evidence["visibleText"] not in evidence["rawText"]:
            raise ValueError(f"{evidence_id}: visible evidence is not an excerpt of raw evidence")


def validate_cases(cases: list[dict]) -> dict[str, dict]:
    if not isinstance(cases, list) or not cases:
        raise ValueError("dataset: expected a nonempty case list")
    indexed, owners = {}, {}
    for case in cases:
        validate_case(case)
        if case["caseId"] in indexed:
            raise ValueError("dataset: duplicate caseId " + case["caseId"])
        indexed[case["caseId"]] = case
        groups = [("group", case["leakageGroup"]),
                  ("report", case["issuerId"], case["reportFamilyId"])]
        for evidence in case["evidence"]:
            groups.extend([("raw", evidence["rawSha256"]),
                           ("visible", evidence["visibleSha256"]),
                           ("source", evidence["sourceRef"])])
        for group in groups:
            previous = owners.setdefault(group, case["split"])
            if previous != case["split"]:
                raise ValueError("dataset: cross-split leakage for " + str(group))
    return indexed


def request_attributes_text(attributes: dict) -> str:
    """ReadRequest attributes are scalar strings, integers and booleans; preserve source order."""
    if not isinstance(attributes, dict) or any(not isinstance(key, str) or not key for key in attributes):
        raise ValueError("requestAttributes must contain named scalar values")
    def render(value):
        if type(value) is bool:
            return "true" if value else "false"
        if isinstance(value, str) or type(value) is int and -(2 ** 63) <= value < 2 ** 63:
            return str(value)
        raise ValueError("requestAttributes must use the existing ReadRequest scalar contract")
    return "{" + ", ".join(key + "=" + render(value) for key, value in attributes.items()) + "}"


def execution_payload(case: dict) -> dict:
    """Allowlist execution inputs; gold and raw-only excerpts have no execution field."""
    validate_case(case)
    real = case["origin"] == REAL_ORIGIN
    metadata = [{key: row[key] for key in sorted(REAL_EVIDENCE_FIELDS - {"rawText", "visibleText"})}
                for row in case["evidence"]] if real else []
    context = "\n\n".join(f"[{row['evidenceId']}] " + (
                json.dumps(metadata[index], ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n" if real else "")
                + row["visibleText"] for index, row in enumerate(case["evidence"]))
    # Java verifies this frozen snapshot with ToolPrefetchService.ordinaryEvidenceContext.
    pre_analyst_context = (f"本轮标的：{case['ticker']}\n请求参数：{request_attributes_text(case['requestAttributes'])}"
                           f"\n请求时间要求：{case['timeSensitivity']}"
                           f"\n证据验收：{case['initialTaskOutcome']}。仅引用下列可用证据，使用 [E1] 等编号并保留来源；数据缺口必须明确说明。\n"
                           + context)
    payload = {"caseId": case["caseId"], "origin": case["origin"], "route": "FUNDAMENTALS",
            "caseSha256": case_hash(case), "query": case["query"], "resolvedQuery": case["resolvedQuery"],
            "history": [dict(message) for message in case["history"]], "asOf": case["asOf"],
            "context": context, "contextSha256": sha256(context),
            "executionScope": case["executionScope"], "ticker": case["ticker"], "requestAttributes": dict(case["requestAttributes"]),
            "timeSensitivity": case["timeSensitivity"], "initialTaskOutcome": case["initialTaskOutcome"],
            "citationIds": [row["evidenceId"] for row in case["evidence"]],
            "preAnalystContext": pre_analyst_context, "preAnalystContextSha256": sha256(pre_analyst_context)}
    if real:
        payload.update(snapshotProvenance=dict(case["provenance"]), evidenceMetadata=[
            {**meta, "visibleText": row["visibleText"]} for meta, row in zip(metadata, case["evidence"])])
    return payload


def case_hash(case: dict) -> str:
    """SHA-256 of compact, sorted-key, ensure_ascii=False UTF-8 JSON, without a newline."""
    return json_hash(case)


def execution_manifest(cases: list[dict], context: dict | None = None) -> dict:
    validate_cases(cases)
    if context is not None:
        validate_execution_context(context)
    result = {"schemaVersion": 2 if context is not None else 1, "cases": [execution_payload(case) for case in cases],
            "runs": [{"runId": str(uuid4()), "caseId": case["caseId"], "bundleId": "baseline-v1", "repeatId": 1}
                     for case in cases]}
    if context is not None:
        result["context"] = context
    return result


def validate_execution_context(context: dict) -> None:
    exact_fields(context, {"experimentId", "evaluatorVersion", "runMode"}, "execution context")
    identifier(context["experimentId"], "experimentId")
    if not isinstance(context["evaluatorVersion"], str) or not re.fullmatch(r"[a-f0-9]{64}", context["evaluatorVersion"]):
        raise ValueError("evaluatorVersion must be the frozen evaluator source fingerprint")
    if context["runMode"] not in {"BASELINE", "DEVELOPMENT", "VALIDATION", "HOLDOUT", "SHADOW"}:
        raise ValueError("Unsupported evaluation runMode")


def validate_facts(facts: object, evidence_ids: set[str] | None = None) -> dict[tuple, dict]:
    if not isinstance(facts, list):
        raise ValueError("facts: expected a list")
    indexed = {}
    for fact in facts:
        exact_fields(fact, FACT_FIELDS, "fact")
        for field in FACT_FIELDS - {"value"}:
            nonempty(fact[field], "fact." + field)
        value = fact["value"]
        if value is not None and (not isinstance(value, str) or not NUMBER.fullmatch(value)):
            raise ValueError("fact.value: expected finite decimal text or null for missing")
        if evidence_ids is not None and fact["evidenceId"] not in evidence_ids:
            raise ValueError("fact: unknown evidenceId")
        key = (fact["evidenceId"], fact["field"], fact["period"])
        if key in indexed:
            raise ValueError("facts: duplicate fact identity")
        indexed[key] = fact
    return indexed


def validate_gold(cases: list[dict], rows: list[dict]) -> dict[str, dict]:
    indexed = validate_cases(cases)
    if not isinstance(rows, list):
        raise ValueError("gold: expected a list")
    gold = {}
    for row in rows:
        real = isinstance(row, dict) and row.get("schemaVersion") == 2
        exact_fields(row, REAL_GOLD_FIELDS if real else GOLD_FIELDS, "gold")
        identifier(row["caseId"], "gold.caseId")
        case = indexed.get(row["caseId"])
        if case is None or row["caseId"] in gold:
            raise ValueError("gold: unknown or duplicate caseId")
        if type(row["schemaVersion"]) is not int or row["schemaVersion"] != case["schemaVersion"]:
            raise ValueError("gold: unsupported schemaVersion")
        if row["caseSha256"] != case_hash(case):
            raise ValueError("gold: case hash mismatch")
        if not real and row["humanReviewStatus"] != "UNREVIEWED":
            raise ValueError("gold: synthetic mechanism fixtures must remain UNREVIEWED")
        if real:
            if row["humanReviewStatus"] != "REVIEWED":
                raise ValueError("gold: real source facts require independent review")
            nonempty(row["reviewer"], "gold.reviewer")
            timestamp(row["reviewedAt"], "gold.reviewedAt")
        exact_fields(row["dimensions"], set(DIMENSIONS), "gold.dimensions")
        for dimension in DIMENSIONS:
            item = row["dimensions"][dimension]
            exact_fields(item, {"status", "reason"}, "gold.dimension")
            if item["status"] != "NO_DATA" or not isinstance(item["reason"], str):
                raise ValueError("gold defines source expectations, not ratings of an answer not yet produced")
        nonempty(row["reviewNotes"], "gold.reviewNotes")
        validate_facts(row["facts"], {item["evidenceId"] for item in case["evidence"]})
        if real:
            validate_real_expectations(row, case)
        gold[row["caseId"]] = row
    if set(gold) != set(indexed):
        raise ValueError("gold: case coverage mismatch")
    return gold


def fact_key(fact: dict) -> tuple:
    return fact["evidenceId"], fact["field"], fact["period"]


def derived_value(derivation: dict, policy: dict) -> str:
    """A fixed formula vocabulary, not evaluator-supplied Python or model arithmetic."""
    values = [Decimal(fact["value"]) for fact in derivation["inputs"]]
    with localcontext() as context:
        context.prec = max(80, sum(len(value.as_tuple().digits) for value in values) + policy["decimalPlaces"] + 8)
        a, b = values
        operation = derivation["operation"]
        if operation == "DIFFERENCE":
            value = a - b
        elif operation == "SUM":
            value = a + b
        else:
            if b == 0:
                raise ValueError("gold derivation divides by zero; label the missing result explicitly")
            value = (a - b) / b * 100 if operation == "PERCENT_CHANGE" else a / b
            if operation == "PERCENT_OF":
                value *= 100
        return str(value.quantize(Decimal(1).scaleb(-policy["decimalPlaces"]), rounding=ROUND_HALF_EVEN))


def validate_real_expectations(gold: dict, case: dict) -> None:
    policy = gold["numericPolicy"]
    exact_fields(policy, {"absoluteTolerance", "decimalPlaces", "rounding"}, "gold.numericPolicy")
    tolerance = policy["absoluteTolerance"]
    if (not isinstance(tolerance, str) or not NUMBER.fullmatch(tolerance) or Decimal(tolerance) < 0
            or type(policy["decimalPlaces"]) is not int or not 0 <= policy["decimalPlaces"] <= 18
            or policy["rounding"] != "HALF_EVEN"):
        raise ValueError("gold numeric policy requires nonnegative decimal tolerance and HALF_EVEN precision in [0,18]")
    if gold["expectedResponse"] not in {"ANSWER", "REFUSE", "CLARIFY"}:
        raise ValueError("gold.expectedResponse must declare ANSWER, REFUSE or CLARIFY")
    if not isinstance(gold["requiredPoints"], list) or not gold["requiredPoints"]:
        raise ValueError("gold requires independently labeled task points, including reasons for refusal/clarification")
    ids = set()
    for point in gold["requiredPoints"]:
        exact_fields(point, {"id", "text"}, "gold.requiredPoint")
        identifier(point["id"], "gold.requiredPoint.id")
        nonempty(point["text"], "gold.requiredPoint.text")
        if point["id"] in ids:
            raise ValueError("gold: duplicate required point")
        ids.add(point["id"])
    if not isinstance(gold["derivations"], list):
        raise ValueError("gold.derivations must be a list")
    expected = validate_facts(gold["facts"])
    seen = set()
    for row in gold["derivations"]:
        exact_fields(row, {"output", "operation", "inputs"}, "gold.derivation")
        validate_facts([row["output"]])
        validate_facts(row["inputs"], {item["evidenceId"] for item in case["evidence"]})
        key = fact_key(row["output"])
        if (key in seen or expected.get(key) != row["output"] or len(row["inputs"]) != 2
                or row["operation"] not in {"SUM", "DIFFERENCE", "RATIO", "PERCENT_CHANGE", "PERCENT_OF"}
                or any(fact["value"] is None for fact in row["inputs"])):
            raise ValueError("gold derivation needs one declared output and two known source inputs")
        if any(row["inputs"][0][field] != row["inputs"][1][field] for field in ("unit", "currency")):
            raise ValueError("gold derivation inputs require matching units/currencies; no implicit conversion")
        if (row["output"]["value"] is None or
                Decimal(row["output"]["value"]) != Decimal(derived_value(row, policy))):
            raise ValueError("gold derived value does not match its frozen formula and rounding policy")
        seen.add(key)


def compare_fact_records(expected: list[dict], observed: list[dict]) -> dict:
    """Compare independently extracted records, never infer them from free-form model prose."""
    expected_rows, observed_rows = validate_facts(expected), validate_facts(observed)
    errors = []
    if expected_rows.keys() != observed_rows.keys():
        errors.append("FACT_IDENTITY_MISMATCH")
    for key in expected_rows.keys() & observed_rows.keys():
        left, right = expected_rows[key], observed_rows[key]
        if any(left[field] != right[field] for field in ("unit", "currency")):
            errors.append("FACT_SCALE_MISMATCH")
        a, b = left["value"], right["value"]
        if (a is None) != (b is None) or (a is not None and b is not None and Decimal(a) != Decimal(b)):
            errors.append("FACT_VALUE_MISMATCH")
    status = "MECHANISM_FAIL" if errors else ("MECHANISM_PASS" if expected_rows else "NO_DATA")
    return {"status": status,
            "errors": sorted(set(errors)), "answer_quality": "NO_DATA", "human_reviewed": False}


def _unique_object(pairs: list[tuple]) -> dict:
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("JSON: duplicate field " + key)
        result[key] = value
    return result


def load_jsonl(path: Path) -> list[dict]:
    return [json.loads(line, object_pairs_hook=_unique_object)
            for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("cases", type=Path)
    parser.add_argument("--gold", type=Path)
    parser.add_argument("--export-execution", type=Path, help="Export allowlisted execution inputs without gold")
    parser.add_argument("--experiment-id")
    parser.add_argument("--run-mode", choices=["BASELINE", "DEVELOPMENT", "VALIDATION", "HOLDOUT", "SHADOW"])
    args = parser.parse_args()
    try:
        cases = load_jsonl(args.cases)
        validate_cases(cases)
        if args.gold:
            validate_gold(cases, load_jsonl(args.gold))
        if args.export_execution:
            if bool(args.experiment_id) != bool(args.run_mode):
                raise ValueError("--experiment-id and --run-mode must be supplied together")
            context = None
            if args.experiment_id:
                from evolution_acceptance import evaluator_hash
                context = {"experimentId": args.experiment_id, "runMode": args.run_mode, "evaluatorVersion": evaluator_hash()}
            with args.export_execution.open("x", encoding="utf-8") as target:
                target.write(json.dumps(execution_manifest(cases, context), ensure_ascii=False, indent=2) + "\n")
    except (OSError, ValueError, TypeError) as error:
        print(json.dumps({"status": "INVALID", "error": str(error)}, ensure_ascii=False))
        return 2
    real = all(case["origin"] == REAL_ORIGIN for case in cases)
    print(json.dumps({"status": "SOURCE_VALIDATED" if real else "MECHANISM_READY", "case_count": len(cases),
                      "dataset_sha256": json_hash(cases), "answer_quality": "NO_DATA",
                      "human_reviewed": bool(real and args.gold), "research_effect": "UNVERIFIED"}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
