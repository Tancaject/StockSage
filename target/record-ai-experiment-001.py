"""Index one frozen AI diagnostic batch after all evidence exists; leave it OPEN."""
import hashlib
import json
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "rag-eval/evolution/common-fundamentals-2025-v1"
AI = DATA / "ai-experiment-001"
OLD = ROOT / "stocksage-backend/target/evolution-drills/ordinary-2281939637392614900/iteration-input.json"
OUTPUT = ROOT / "docs/evolution/iterations/iteration-001-ai-diagnostic-20260930"
MANIFEST = ROOT / "target/ai-experiment-001-iteration-input.json"


def reference(path):
    path = path.resolve()
    return {"path": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}


def main():
    manifest = json.loads(OLD.read_bytes())
    assert len(manifest["proofs"]) == 17
    for digest, location in manifest["proofs"].items():
        assert reference(OLD.parent / location)["sha256"] == digest, location
    for role, entry in manifest["artifacts"].items():
        assert reference(OLD.parent / entry["path"])["sha256"] == entry["sha256"], role
    assert set(manifest["artifacts"]) == {"offlineDrill"}
    if OUTPUT.exists() or MANIFEST.exists():
        raise FileExistsError("Use the existing immutable record; do not overwrite this batch.")

    manifest["artifacts"].update({
        "sourceCases": reference(DATA / "cases.jsonl"),
        "baselineReport": reference(DATA / "results/20260930-packaged-qwen37-baseline/baseline-report.json"),
        "candidate": reference(AI / "candidate.json"),
    })
    gold = DATA / "acceptance-preparation/gold-draft.jsonl"
    proof_paths = [gold] + [AI / name for name in (
        "plan.json", "bundle.json", "experience.json", "source-artifacts.json",
        "driver-dependencies.json", "candidate-report-development.json",
        "candidate-report-validation.json", "identity-verification.json", "diagnostic-assessment.json",
        "proposal.json", "review-instructions.md", "review-development.json", "review-validation.json",
        "review-mapping-development.json", "review-mapping-validation.json",
    )]
    if (AI / "review-adjudications.json").exists():
        proof_paths.append(AI / "review-adjudications.json")
    proof_paths += [AI / f"raw-{split}" / name
                    for split in ("development", "validation")
                    for name in ("execution.json", "runtime.json", "responses.jsonl")]
    added = {str(path): reference(path) for path in proof_paths}
    for entry in added.values():
        manifest["proofs"][entry["sha256"]] = entry["path"]
    manifest.update(
        iterationId="iteration-001", actor="Codex", recordedAt=datetime.now(timezone.utc).isoformat(),
        closure=None,
        unresolved=[
            {"issueType": "MISSING_DATA",
             "description": "已绑定公开授权源数据和 gold 草稿；gold 尚未独立确认，正式验收证据仍不完整。",
             "evidenceRefs": [added[str(gold)]["sha256"]]},
            {"issueType": "EVALUATION_FAILURE",
             "description": "候选报告与人工智能诊断仅作研究证据，不能替代正式 E07、独立比较及后续验收；本批次不授权激活。",
             "evidenceRefs": [added[str(AI / name)]["sha256"] for name in
                              ("identity-verification.json", "diagnostic-assessment.json")]},
        ],
        nextBatch={"trigger": "MANUAL", "scope":
                   "当前候选保持冻结；是否开展新的固定批次由用户决定。先补齐独立 gold 与正式验收证据，不因本轮诊断改写候选，不自动继续或激活。"},
    )
    # Every requested file has been bound before creating the manifest or index.
    with MANIFEST.open("x", encoding="utf-8", newline="\n") as stream:
        stream.write(json.dumps(manifest, ensure_ascii=False, sort_keys=True, indent=2) + "\n")
    subprocess.run([sys.executable, "-B", str(ROOT / "rag-eval/evolution_iteration.py"),
                    "--manifest", str(MANIFEST), "--output", str(OUTPUT)], cwd=ROOT, check=True)
    record = json.loads((OUTPUT / "artifact-index.json").read_bytes())
    assert record["status"] == "OPEN" and record["activationAuthorized"] is False
    assert {role for role in record["artifacts"] if not role.startswith("proof:")} == {
        "sourceCases", "baselineReport", "candidate", "offlineDrill"}
    print(json.dumps({"status": record["status"], "activationAuthorized": False,
                      "proofCount": len(manifest["proofs"]), "output": str(OUTPUT)}))


if __name__ == "__main__":
    main()
