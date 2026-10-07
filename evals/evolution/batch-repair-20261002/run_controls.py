"""Review explicitly authored negative controls, never replay or relabel business runs."""
from concurrent.futures import ThreadPoolExecutor
import copy
import hashlib
import json
from pathlib import Path
import sys

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parents[1]))
from eval_common import json_hash, sha256
from evolution_ai_review import DEFAULT_CONFIG, PROTOCOL, diagnostic_inputs, judge_stage
from evolution_dataset import load_jsonl
from run_ragas_eval import resolve_api_key


def main():
    old = HERE.parent / "batch-learning-20261002"
    specification = HERE / "evaluator-negative-controls.json"
    spec = json.loads(specification.read_text(encoding="utf-8"))
    sources, gold = (load_jsonl(old / name) for name in ("cases.jsonl", "gold.jsonl"))
    items = {item["id"]: item for path in old.glob("raw-development-*/baseline-v1.report.json")
             for item in diagnostic_inputs(json.loads(path.read_text(encoding="utf-8")), sources, gold)
             if item["stage_name"] == "finalAnswer"}
    prepared = []
    for control in spec["controls"]:
        authored = json.loads((HERE / (control["controlId"] + ".authored-answer.json")).read_text(encoding="utf-8"))
        source = HERE.parents[2] / control["sourceRawResponsePath"]
        assert hashlib.sha256(source.read_bytes()).hexdigest() == authored["sourceResponseSha256"]
        item = copy.deepcopy(items[authored["caseId"] + "@1"])
        assert not item["errors"] and sha256(item["answer"]) == authored["sourceAnswerSha256"]
        change = control["replace"]
        assert item["answer"].count(change["old"]) == change["count"] == 1
        assert item["answer"].replace(change["old"], change["new"], 1) == authored["answer"]
        assert sha256(authored["answer"]) == authored["answerSha256"]
        source_binding = copy.deepcopy(item["binding"])
        item.update(id=control["controlId"], answer=authored["answer"])
        item["stage"]["answer"] = authored["answer"]
        item["binding"].update(id=item["id"], answerSha256=authored["answerSha256"],
            caseSha256=json_hash({"control": control, "sourceBinding": source_binding}))
        prepared.append((item, control, source_binding))
    output = HERE / "judge-negative-controls.json"
    with output.open("x", encoding="utf-8") as target:
        json.dump({"status": "RUNNING", "artifactType": "AUTHORED_EVALUATOR_CONTROL"}, target)
    key = resolve_api_key(None)
    def review(entry):
        item, control, source_binding = entry
        result = judge_stage(item, DEFAULT_CONFIG, key)
        result.update(artifactType="AUTHORED_EVALUATOR_CONTROL", source_binding=source_binding,
                      expected_dimensions=control["expectedDimensions"])
        return result
    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(review, prepared))
    report = {"schema": "authored_native_judge_controls_v1", "artifactType": "AUTHORED_EVALUATOR_CONTROL",
              "humanReviewStatus": "AI_ONLY", "releaseEligible": False, "status": "COMPLETED",
              "specSha256": hashlib.sha256(specification.read_bytes()).hexdigest(),
              "runnerSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              "adapterSha256": hashlib.sha256((HERE.parents[1] / "evolution_ai_review.py").read_bytes()).hexdigest(),
              "protocolSha256": sha256(PROTOCOL), "config": DEFAULT_CONFIG, "reviews": results}
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for row in results:
        print(json.dumps({key: row[key] for key in ("id", "status", "technical_status", "errors")}), flush=True)


if __name__ == "__main__":
    main()
