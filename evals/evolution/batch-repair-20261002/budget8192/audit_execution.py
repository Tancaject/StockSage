"""Reuse the repair audit and add paired comparisons against the 4096 arm."""
import argparse
import importlib.util
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("parent_repair_audit", HERE.parent / "audit_execution.py")
parent = importlib.util.module_from_spec(spec)
spec.loader.exec_module(parent)
parent.HERE = HERE
parent.base.HERE = HERE


def audit():
    result = parent.audit()
    prior = parent.base.read(HERE.parent / "execution-audit.json")
    prior_cases = {row["caseId"]: row for row in prior["cases"]}
    comparisons = []
    for row in result["cases"]:
        old = prior_cases[row["caseId"]]
        if parent.base.digest(parent.base.REPO / old["responsePath"]) != old["responseSha256"]:
            result["errors"].append("BUDGET4096_RESPONSE_CHANGED:" + row["caseId"])
        stages = {}
        for name in parent.base.STAGES:
            before, after = old["stages"].get(name) or {}, row["stages"].get(name) or {}
            paired = before.get("executionStatus") == after.get("executionStatus") == "COMPLETED"
            stages[name] = {"budget4096Status": before.get("executionStatus"), "budget8192Status": after.get("executionStatus"),
                            "budget4096DurationMs": before.get("durationMs"), "budget8192DurationMs": after.get("durationMs"),
                            "completedPairDurationDeltaMs": after["durationMs"] - before["durationMs"] if paired else None,
                            "budget4096Usage": before.get("providerUsage", []), "budget8192Usage": after.get("providerUsage", [])}
        comparisons.append({"caseId": row["caseId"], "budget4096RunId": old["runId"], "budget8192RunId": row["runId"],
                            "budget4096ResponseSha256": old["responseSha256"], "budget8192ResponseSha256": row.get("responseSha256"),
                            "stages": stages})
    result["budget4096AuditSha256"] = parent.base.digest(HERE.parent / "execution-audit.json")
    result["budget8192AuditAdapterSha256"] = parent.base.digest(__file__)
    result["budget4096Comparisons"] = comparisons
    if result["errors"]:
        result["status"] = "FAIL"
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = audit()
    with args.output.open("x", encoding="utf-8", newline="\n") as output:
        output.write(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({key: result[key] for key in ("status", "plannedCaseCount", "receivedCaseCount", "passedExecutionCount", "errors", "providerTokens")}, ensure_ascii=False))
    raise SystemExit(0 if result["status"] == "PASS" else 2)
