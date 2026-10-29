"""Gold-answer check and automatic failure triage for ordinary FUNDAMENTALS answers.

For each answered case the gold facts and derived values are looked up in the final answer with the
deterministic numeric check. A case that misses any of them, or writes a self-contradictory conversion or
calculation, is a failure, and is triaged by where it broke:

  PROVIDER       the run produced no usable analysis (outcome FAILED/BLOCKED, analyst not COMPLETED, empty answer)
  DATA_GAP       a gold source fact is not in the evidence the analyst actually received (analystContext)
  DETERMINISTIC  evidence complete; the answer writes a wrong unit conversion or stated arithmetic (a mismatch that
                 the displayed operands' rounding explains is not counted)
  METHOD         evidence complete; the answer omits or miscomputes a required value

DATA_GAP lists the missing source fields: the fix is in data retrieval or presentation, not in the method.
Only METHOD failures are learning material for method evolution. Finding a number in the text does not
prove it is attached to the right period; this check does not replace the reviewed claim extraction.

  python evals/gold_triage.py --report <run_ordinary_live_eval output> --gold <gold.jsonl> --output triage.json
"""
from __future__ import annotations

import argparse
import itertools
import json
import re
from decimal import Decimal
from pathlib import Path

import evolution_numeric_check as numeric

HERE = Path(__file__).resolve().parent
FY2025 = HERE / "evolution" / "fundamentals-fy2025-expansion"


def gold_facts(gold: dict) -> tuple[list[dict], list[dict]]:
    """(values the answer must state, source values the evidence must contain) as numeric-check facts.

    Required: gold facts plus derivation outputs. Source: derivation inputs (such as debt line items) plus
    facts that no derivation produces; ratios and sums are computed, so they are not expected in evidence."""
    pick = lambda f: {"field": f["field"], "value": f["value"], "unit": f["unit"]}
    derivations = gold.get("derivations", [])
    required = {f["field"]: pick(f) for f in gold["facts"] + [d["output"] for d in derivations]}
    produced = {d["output"]["field"] for d in derivations}
    source = {f["field"]: pick(f) for f in [i for d in derivations for i in d["inputs"]] + gold["facts"]
              if f["field"] not in produced}
    return list(required.values()), list(source.values())


def in_evidence(evidence: str, fact: dict) -> bool:
    """Evidence tables print amounts without units: millions in the compact table, sometimes thousands in
    the original balance sheet, so a million-unit fact also matches its value in thousands."""
    candidates = [fact]
    if fact["unit"] == "million":
        candidates.append({**fact, "value": str(Decimal(fact["value"]) * 1000)})
    return any(numeric.check_answer(evidence, [item])["found"] for item in candidates)


def within_policy(answer: str, fact: dict, tolerance: Decimal) -> bool:
    """The gold's own numericPolicy tolerance for percentages and ratios: 29.50% written for 29.4947 passes at 0.01,
    while the display-precision rule (half of the last digit) would reject it."""
    if fact["unit"] not in {"percent", "percentagePoint", "ratio"}:
        return False
    target = Decimal(fact["value"])
    for row in numeric.tokens(answer):
        value = row["value"] / 100 if fact["unit"] == "ratio" and row["unit"] == "%" else row["value"]
        unit_ok = row["unit"] in ({"%", "个百分点"} if fact["unit"] != "ratio" else {"", "倍", "%"})
        # Same as the display-precision rule: a bare integer (a list number such as "3.") is not a ratio.
        unit_ok = unit_ok and not (fact["unit"] == "ratio" and row["unit"] == "" and "." not in row["raw"])
        if unit_ok and abs(value - target) <= tolerance:
            return True
    return False


def rounding_explained(issue: str) -> bool:
    """An ARITHMETIC_MISMATCH such as "8,972 ÷ 65,179 = 13.76%" (computed 13.7652) is not an error when the answer
    computed from unrounded values and printed rounded operands: the shown result lies within the range the
    operands' display precision allows. Required values are still checked against gold separately."""
    if not issue.startswith("ARITHMETIC_MISMATCH"):
        return False
    text = issue.split('"')[1]
    chain, shown = re.split(r"\s*[=≈]\s*", text)[-2:]
    percent = shown.endswith("%")
    shown = shown.rstrip("%")
    spans = list(re.finditer(r"\d[\d,]*(?:\.\d+)?", chain))
    if not spans or len(spans) > 8:
        return False
    values = []
    for signs in itertools.product((-1, 1), repeat=len(spans)):
        expression, last = "", 0
        for sign, span in zip(signs, spans):
            raw = span.group(0)
            # 不带千分位的三位以内整数（× 2、/ 4、× 100）当作精确常数，不按显示精度放宽。
            exact = re.fullmatch(r"\d{1,3}", raw) is not None
            bound = numeric.number(raw) + (0 if exact else sign * numeric.half_step(raw))
            expression += chain[last:span.start()] + str(bound)
            last = span.end()
        value = numeric.evaluate(expression + chain[last:])
        if value is not None:
            values.append(value * 100 if percent else value)
    target, step = numeric.number(shown), numeric.half_step(shown)
    return bool(values) and min(values) - step <= target <= max(values) + step


def triage(gold: dict, answer: str, evidence: str, outcome: str, analyst_status: str) -> dict:
    required, source = gold_facts(gold)
    check = numeric.check_answer(answer or "", required)
    tolerance = Decimal(str((gold.get("numericPolicy") or {}).get("absoluteTolerance", "0")))
    by_field = {fact["field"]: fact for fact in required}
    missing = sorted(field for field, status in check["facts"].items()
                     if status == "NOT_FOUND" and not within_policy(answer or "", by_field[field], tolerance))
    check["found"] = check["total"] - len(missing)
    check["issues"] = [issue for issue in check["issues"] if not rounding_explained(issue)]
    row = {"found": check["found"], "total": check["total"], "missingInAnswer": missing, "issues": check["issues"]}
    if not missing and not check["issues"]:
        return {**row, "status": "PASS", "failureType": None}
    if outcome in {"FAILED", "BLOCKED"} or analyst_status != "COMPLETED" or not (answer or "").strip():
        return {**row, "status": "FAIL", "failureType": "PROVIDER"}
    absent = sorted(fact["field"] for fact in source if not in_evidence(evidence or "", fact))
    if absent:
        return {**row, "status": "FAIL", "failureType": "DATA_GAP", "missingInEvidence": absent}
    return {**row, "status": "FAIL", "failureType": "DETERMINISTIC" if check["issues"] else "METHOD"}


def evidence_step(trace: dict) -> dict:
    steps = trace.get("steps") or []
    if isinstance(steps, str):
        steps = json.loads(steps)
    return next(((s.get("attributes") or {}) for s in steps
                 if (s.get("attributes") or {}).get("kind") == "ordinary-evidence"), {})


def summarize(rows: list[dict]) -> dict:
    graded = [row for row in rows if row.get("status") in {"PASS", "FAIL"}]
    counts = {kind: sum(row.get("failureType") == kind for row in graded)
              for kind in ("PROVIDER", "DATA_GAP", "DETERMINISTIC", "METHOD")}
    return {"cases": len(rows), "graded": len(graded),
            "pass_rate": sum(row["status"] == "PASS" for row in graded) / len(graded) if graded else 0.0,
            "gold_fact_rate": (sum(row["found"] for row in graded) / sum(row["total"] for row in graded)
                               if graded and sum(row["total"] for row in graded) else 0.0),
            "failure_types": counts}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--report", type=Path, required=True, help="run_ordinary_live_eval.py output")
    parser.add_argument("--gold", type=Path, default=FY2025 / "gold.jsonl")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    gold = {row["caseId"]: row for row in map(json.loads, args.gold.read_text(encoding="utf-8").splitlines()) if row}
    rows = []
    for case in json.loads(args.report.read_text(encoding="utf-8"))["cases"]:
        if case["id"] not in gold:
            rows.append({"id": case["id"], "status": "NO_GOLD"})
            continue
        trace = case.get("trace") or {}
        evidence = evidence_step(trace)
        rows.append({"id": case["id"], "traceId": trace.get("traceId"), "outcome": case.get("actual_outcome"),
                     **triage(gold[case["id"]], case.get("answer", ""), evidence.get("analystContext") or "",
                              case.get("actual_outcome") or "", evidence.get("analystStatus") or "")})
    result = {"schema": "gold_triage_v1", "gold": str(args.gold), "report": str(args.report),
              "summary": summarize(rows), "cases": rows}
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result["summary"], ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
