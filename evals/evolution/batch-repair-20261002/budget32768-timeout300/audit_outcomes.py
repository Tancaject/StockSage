"""Append native task outcomes to the preserved execution audit without rescoring."""
import hashlib
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[3]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


if __name__ == "__main__":
    audit_path = HERE / "execution-audit.json"
    audit = json.loads(audit_path.read_text(encoding="utf-8"))
    rows = []
    for case in audit["cases"]:
        path = REPO / case["responsePath"]
        assert digest(path) == case["responseSha256"], path
        response = json.loads(path.read_text(encoding="utf-8"))
        assert response["caseId"] == case["caseId"] and response["runId"] == case["runId"]
        durations = {stage: response[stage]["durationMs"] for stage in ("analysis", "finalAnswer")}
        rows.append({"caseId": case["caseId"], "runId": case["runId"],
                     "responsePath": case["responsePath"], "responseSha256": digest(path),
                     "responseStatus": response["status"], "analystStatus": response["analystStatus"],
                     "taskOutcome": response["taskOutcome"], "durationMs": durations,
                     "sumAnalysisAndFinalDurationMs": sum(durations.values()),
                     "analysisUtf16Length": len(response["analysis"]["answer"].encode("utf-16-le")) // 2})
    totals = [row["sumAnalysisAndFinalDurationMs"] for row in rows]
    result = {"scope": "NATIVE_TASK_OUTCOMES_NOT_SEMANTIC_SCORING", "executionAuditSha256": digest(audit_path),
              "caseCount": len(rows), "truncatedAnalystCount": sum(row["analystStatus"] == "TRUNCATED" for row in rows),
              "degradedTaskCount": sum(row["taskOutcome"] == "DEGRADED" for row in rows),
              "sumAnalysisAndFinalDurationMs": {"min": min(totals), "max": max(totals), "sum": sum(totals)},
              "durationScope": "Sum of two stage durations, excluding orchestration; 300 seconds is per stage, not per question.",
              "degradationCause": "ToolPrefetchService.appendAnalystDraft keeps at most 4500 characters of the analysis draft for the final-answer context; complete raw analysis remains recorded.",
              "qualityBoundary": "Execution completion does not establish semantic correctness or absence of task degradation.",
              "releaseEligible": False, "cases": rows}
    with (HERE / "execution-outcomes.json").open("x", encoding="utf-8", newline="\n") as stream:
        stream.write(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({key: result[key] for key in ("caseCount", "truncatedAnalystCount", "degradedTaskCount", "sumAnalysisAndFinalDurationMs")}, ensure_ascii=False))
