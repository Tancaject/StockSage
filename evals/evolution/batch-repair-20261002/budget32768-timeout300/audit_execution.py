"""Reuse the repair audit without changing its execution or quality boundaries."""
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

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = parent.audit()
    result["experimentAuditAdapterSha256"] = parent.base.digest(__file__)
    result["phasedExecutionPlan"] = parent.base.read(HERE / "plan.json")["phases"]
    with args.output.open("x", encoding="utf-8", newline="\n") as output:
        output.write(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({key: result[key] for key in ("status", "plannedCaseCount", "receivedCaseCount", "passedExecutionCount", "errors", "providerTokens")}, ensure_ascii=False))
    raise SystemExit(0 if result["status"] == "PASS" else 2)
