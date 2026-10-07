"""Aggregate this frozen campaign's external source reviews; make no model calls."""
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
STAGES = ("analysis", "finalAnswer")


def read(name):
    return json.loads((HERE / name).read_text(encoding="utf-8"))


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def summarize():
    plan = read("plan.json")
    assert all(digest(REPO / name) == expected for name, expected in plan["frozenFiles"].items())
    baseline = read("development-quality-summary.json")
    assert all(digest(HERE / row["reviewPath"]) == row["reviewSha256"] for row in baseline["cases"])
    assert all(digest(HERE / name) == expected for name, expected in read("generation-registration.json")["inputs"].items())
    baseline_audit = read("development-execution-audit.json")
    candidate_audit = read("candidate-dev-execution-audit.json")
    native = {row["caseId"]: row for row in candidate_audit["cases"]}
    gold = {row["caseId"]: row for row in map(json.loads, (HERE / "gold.jsonl").read_text(encoding="utf-8").splitlines())}
    review_files = ["screen-g1-source-review.json", "candidate-dev-review-original.json",
                    "candidate-dev-review-new.json", "candidate-dev-review-later.json"]
    candidates = []
    for name in review_files:
        review = read(name)
        for case in review["cases"]:
            execution = native[case["caseId"]]
            assert case["runId"] == execution["runId"]
            replay_path = REPO / execution["responsePath"]
            assert digest(replay_path) == execution["responseSha256"]
            replay = json.loads(replay_path.read_text(encoding="utf-8"))
            row = {"caseId": case["caseId"], "runId": case["runId"], "reviewPath": name,
                   "reviewSha256": digest(HERE / name), "stages": {}}
            for stage_name in STAGES:
                stage = case["stages"][stage_name]
                raw = replay.get(stage_name) or {}
                verdict = stage["verdict"]
                if verdict != "NO_DATA":
                    assert raw.get("status") == "COMPLETED" and raw.get("answer")
                    assert stage["answerSha256"] == hashlib.sha256(raw["answer"].encode()).hexdigest()
                facts = stage.get("requiredFacts")
                if facts:
                    assert len(facts) == len(gold[case["caseId"]]["facts"])
                    counts = dict(Counter(fact.get("status", fact.get("verdict")) for fact in facts))
                    assert set(counts) <= {"PASS", "FAIL", "NO_DATA"}
                else:
                    assert verdict == "NO_DATA" and not raw.get("answer")
                    counts = {"NO_DATA": len(gold[case["caseId"]]["facts"])}
                row["stages"][stage_name] = {"verdict": verdict, "requiredNumeric": counts}
            candidates.append(row)
    assert len(candidates) == len(native) == 10
    assert {row["caseId"] for row in candidates} == set(plan["developmentCases"])
    candidate_summary = {stage: {
        "plannedCases": 10,
        "verdictCounts": dict(Counter(row["stages"][stage]["verdict"] for row in candidates)),
        "requiredNumericCounts": dict(sum((Counter(row["stages"][stage]["requiredNumeric"])
                                           for row in candidates), Counter()))} for stage in STAGES}
    before = {row["caseId"]: row for row in baseline["cases"]}
    transitions = {stage: dict(Counter(before[row["caseId"]]["stages"][stage]["verdict"] + " -> "
                                      + row["stages"][stage]["verdict"] for row in candidates)) for stage in STAGES}
    generation = read("generation-source-audit.json")["summary"]
    usage = {key: baseline_audit["providerTokens"][key] + candidate_audit["providerTokens"][key]
             for key in ("inputTokens", "outputTokens", "totalTokens")}
    assert not list(HERE.glob("transfer*.execution.json"))
    artifacts = ["plan.json", "frozen-inputs.zip", "review-policy.json", "generation-registration.json",
                 "groups.json", "controls.json", "development-quality-summary.json", "development-execution-audit.json",
                 "generation-source-audit.json", "screen-g1-execution-audit.json", "candidate-development-registration.json",
                 "candidate-dev.phase.json", "candidate-dev-execution-audit.json", "focused-verification.json",
                 "live-draft-integrity.json", "generation-input-diagnostic.json", "summarize_results.py", *review_files]
    return {"schema": "fundamentals_batch_learning_validation_result_v1",
            "generatedAt": datetime.now(timezone.utc).isoformat(),
            "status": "COMPLETE_WITHOUT_TRANSFER", "scope": "FIXED_SOURCE_COMPONENT_CHAIN_AI_DIAGNOSTIC",
            "releaseEligible": False, "formalAcceptance": "NO_DATA", "residentServiceReloaded": False,
            "baseline": baseline["summary"], "candidate": candidate_summary,
            "candidateCases": candidates, "pairedDevelopmentTransitions": transitions,
            "candidateDevelopmentReusedScreenRuns": 3, "freshCandidateDevelopmentRuns": 7,
            "generator": generation, "screening": {"candidates": 1, "cases": 3, "stagePasses": 6},
            "candidateSelection": {"status": "NOT_SELECTED", "reason": "FULL_DEVELOPMENT_EXECUTION_INCOMPLETE"},
            "multipleExperienceInteractionTested": False,
            "transfer": {"status": "NOT_RUN", "datasetCases": 12, "modelCalls": 0},
            "evolutionBenefit": "LOCAL_ERROR_CORRECTED_IN_ONE_ATTEMPT; STABLE_FINAL_ANSWER_TRANSFER_BENEFIT_UNPROVEN",
            "usage": {"businessModelInvocations": baseline_audit["observedInvocationCount"] + candidate_audit["observedInvocationCount"],
                      "generatorModelInvocations": generation["observedGeneratorCalls"],
                      "businessKnownProviderTokens": usage, "generatorKnownProviderTokens": generation["usage"],
                      "allKnownProviderTokens": usage["totalTokens"] + generation["usage"]["total_tokens"],
                      "businessInvocationsWithUnknownUsage": baseline_audit["unknownUsageInvocationCount"] + candidate_audit["unknownUsageInvocationCount"],
                      "monetaryCost": None, "codexReviewerUsageIncluded": False,
                      "accountingNote": "Screening runs are already in the full candidate audit and are counted only once. Unknown usage is not zero."},
            "artifactHashes": {name: digest(HERE / name) for name in artifacts}}


if __name__ == "__main__":
    result = summarize()
    assert result["candidate"]["finalAnswer"]["verdictCounts"].get("NO_DATA", 0) > 0
    with (HERE / "result.json").open("x", encoding="utf-8", newline="\n") as output:
        output.write(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({key: result[key] for key in ("status", "candidate", "usage")}, ensure_ascii=False))
