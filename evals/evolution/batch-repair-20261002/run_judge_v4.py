"""Run the frozen native-judge calibration on saved answers, never business replay."""
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import sys

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(ROOT / "evals"))
from eval_common import json_hash, sha256
from evolution_ai_review import DEFAULT_CONFIG, PROTOCOL, diagnostic_inputs, judge_stage
from evolution_dataset import load_jsonl
from evolution_rubric import rubric_hash
from run_ragas_eval import resolve_api_key


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main():
    plan_path = HERE / "judge-v4-high-plan.json"
    plan = read(plan_path)
    if plan["status"] != "FROZEN":
        raise ValueError("Freeze the v4 adapter, shared checker and sample identities before evaluation")
    for entry in plan["inputs"]:
        if digest(ROOT / entry["path"]) != entry["sha256"]:
            raise ValueError("Frozen input changed: " + entry["path"])
    if (plan["judgeConfig"] != DEFAULT_CONFIG or plan["protocolSha256"] != sha256(PROTOCOL)
            or plan["rubricSha256"] != rubric_hash()):
        raise ValueError("Active judge settings differ from the registered calibration")
    samples = plan["samples"]
    if (len(samples) != 16 or len({row["sampleId"] for row in samples}) != 16
            or len(samples) * DEFAULT_CONFIG["max_calls"] > plan["maxProviderCalls"]
            or not 1 <= plan["maxConcurrentStageReviews"] <= 6):
        raise ValueError("Calibration must retain 16 registered stages within its call and concurrency limits")
    prepared, cached = [], {}
    for sample in samples:
        key = (sample["report"], sample["sourceCases"], sample["gold"])
        if key not in cached:
            cached[key] = diagnostic_inputs(read(ROOT / key[0]), load_jsonl(ROOT / key[1]), load_jsonl(ROOT / key[2]))
        matches = [item for item in cached[key]
                   if item["id"] == sample["caseId"] + "@1" and item["stage_name"] == sample["stage"]]
        if len(matches) != 1 or matches[0]["errors"] or matches[0]["binding"]["answerSha256"] != sample["answerSha256"]:
            raise ValueError("Registered answer is missing, incomplete or changed: " + sample["sampleId"])
        prepared.append((sample, matches[0]))
    output = ROOT / plan["output"]
    report = {"schema": "native_judge_v4_calibration_v1", "status": "RUNNING", "releaseEligible": False,
              "humanReviewed": False, "generatedAt": datetime.now(timezone.utc).isoformat(),
              "executionMode": "NEW_PROVIDER_CALLS_ON_SAVED_ANSWERS", "planSha256": digest(plan_path),
              "adapterSha256": digest(ROOT / "evals/evolution_ai_review.py"),
              "qualitySha256": digest(ROOT / "evals/evolution_quality.py"),
              "config": DEFAULT_CONFIG, "protocolSha256": sha256(PROTOCOL), "rubricSha256": rubric_hash(),
              "expectedStageCount": 16, "reviews": []}
    api_key = resolve_api_key(None)
    with output.open("x", encoding="utf-8") as stream:
        stream.write(json.dumps(report, ensure_ascii=False, indent=2) + "\n")

    def save():
        temporary = output.with_suffix(output.suffix + ".tmp")
        temporary.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        temporary.replace(output)

    with ThreadPoolExecutor(max_workers=plan["maxConcurrentStageReviews"]) as pool:
        futures = {pool.submit(judge_stage, item, DEFAULT_CONFIG, api_key): sample for sample, item in prepared}
        for future in as_completed(futures):
            sample = futures[future]
            result = future.result()
            # Labels stay outside judge_stage's payload and are attached only after its response.
            result.update(sampleId=sample["sampleId"], answerSet=sample["answerSet"],
                          independentStatus=sample["independentStatus"], independentReference=sample["independentReference"])
            report["reviews"].append(result)
            report["reviews"].sort(key=lambda row: row["sampleId"])
            rows = report["reviews"]
            decisive = lambda row: row["technical_status"] == "VALID" and row["status"] in {"PASS", "FAIL"}
            report["summary"] = {"planned": 16, "completed": len(rows),
                "providerCalls": sum(len(row["calls"]) for row in rows),
                "technicalValid": sum(row["technical_status"] == "VALID" for row in rows),
                "correctDecisions": sum(decisive(row) and row["status"] == row["independentStatus"] for row in rows),
                "falsePasses": sum(decisive(row) and row["status"] == "PASS" and row["independentStatus"] == "FAIL" for row in rows),
                "falseFails": sum(decisive(row) and row["status"] == "FAIL" and row["independentStatus"] == "PASS" for row in rows),
                "noDecision": 16 - sum(decisive(row) for row in rows)}
            save()
            print(json.dumps({key: result[key] for key in ("sampleId", "status", "technical_status", "errors")}, ensure_ascii=False), flush=True)
    report["status"] = "COMPLETED" if all(row["technical_status"] == "VALID" for row in report["reviews"]) else "INCOMPLETE"
    save()
    print(json.dumps({"status": report["status"], "summary": report["summary"], "output": str(output)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
