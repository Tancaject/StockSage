"""Replay the frozen 2x2 diagnostic; reuse the registered runner and calibration."""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import statistics
import sys

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(ROOT / "evals"))
from eval_common import json_hash, sha256
from judge_calibration import calibrate, compare_reports
from ordinary_answer_quality import DIMENSIONS, ordinary_rubric
from run_evaluation_agent import PROTOCOL, TOOLS, judge_case, pilot_inputs
from run_ragas_eval import resolve_api_key


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def save(path, value):
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def inputs():
    plan = read(HERE / "plan.json")
    for name, digest in plan["frozen_files"].items():
        assert hashlib.sha256((ROOT / name).read_bytes()).hexdigest() == digest, name
    dataset = [json.loads(line) for line in (ROOT / plan["dataset"]).read_text(encoding="utf-8").splitlines() if line.strip()]
    assert json_hash(dataset) == plan["dataset_sha256"]
    assert ordinary_rubric()["sha256"] == plan["rubric_sha256"]
    assert len(plan["schedule"]) == len(dataset) * plan["repeats"] * len(plan["arms"])
    keys = {(j["arm"], j["id"], j["repeat"]) for j in plan["schedule"]}
    assert len(keys) == len(plan["schedule"])
    return plan, dataset


def run():
    plan, dataset = inputs()
    items = {item["id"]: item for item in pilot_inputs(dataset, "VALIDATION")}
    reports, guidance = {}, {}
    for arm, spec in plan["arms"].items():
        assert not (HERE / (arm + ".json")).exists(), "Preserve prior results"
        guidance[arm] = (HERE / spec["prompt"]).read_text(encoding="utf-8")
        reports[arm] = {"schema": "ordinary_judge_diagnostic_v2", "origin": "AI", "diagnostic_only": True,
            "release_eligible": False, "input_kind": "pilot", "dataset_sha256": json_hash(dataset),
            "human_review_status": "UNREVIEWED", "rubric": ordinary_rubric(), "split": "VALIDATION",
            "split_usage": "PREVIOUSLY_CONSUMED_DIAGNOSTIC_REPLAY", "repeats": plan["repeats"],
            "generated_at": datetime.now(timezone.utc).isoformat(), "plan_sha256": json_hash(plan),
            "judge": {"config": spec["config"], "prompt_sha256": sha256(PROTOCOL + "\n" + guidance[arm]),
                "guidance": guidance[arm], "tools_sha256": json_hash(TOOLS),
                "runner_sha256": sha256((ROOT / "evals/run_evaluation_agent.py").read_text(encoding="utf-8"))},
            "reviews": [], "status": "RUNNING"}
        calibrate(dataset, reports[arm])
    key = resolve_api_key()
    for arm, report in reports.items():
        save(HERE / (arm + ".json"), report)
    with ThreadPoolExecutor(max_workers=plan["workers"]) as pool:
        futures = {pool.submit(judge_case, items[job["id"]], plan["arms"][job["arm"]]["config"],
                    guidance[job["arm"]], key, job["repeat"]): job for job in plan["schedule"]}
        for index, future in enumerate(as_completed(futures), 1):
            job, result = futures[future], future.result()
            report = reports[job["arm"]]
            report["reviews"].append(result)
            report["reviews"].sort(key=lambda row: (row["id"], row["repeat"]))
            save(HERE / (job["arm"] + ".json"), report)
            print(json.dumps({"completed": index, "total": len(futures), **job,
                              "technical_status": result["technical_status"], "errors": result["errors"]}), flush=True)
    for arm, report in reports.items():
        report["status"] = "COMPLETED" if all(r["technical_status"] == "VALID" for r in report["reviews"]) else "INCOMPLETE"
        save(HERE / (arm + ".json"), report)


def summarize():
    plan, dataset = inputs()
    cases = {c["id"]: c for c in dataset}
    reports = {arm: read(HERE / (arm + ".json")) for arm in plan["arms"]}
    output = {"schema": "ordinary_judge_2x2_diagnostic_v1", "diagnostic_only": True,
              "release_eligible": False, "plan_sha256": json_hash(plan), "arms": {}, "comparisons": {}}
    calibrated = {}
    for arm, report in reports.items():
        assert len(report["reviews"]) == len(dataset) * plan["repeats"]
        cal = calibrated[arm] = calibrate(dataset, report)
        save(HERE / (arm + "-calibration.json"), cal)
        correct, corrupt, retained, detected, false_rejected = 0, 0, 0, 0, 0
        outcomes, by_type = {}, {}
        for obs in cal["observations"]:
            case = cases[obs["id"]]
            good = case["variant"].startswith("CORRECT_")
            keep = all(d["valid"] and d["actual"] in {"PASS", "NOT_APPLICABLE"} for d in obs["dimensions"].values())
            if good:
                correct += 1
                retained += keep
                false_rejected += any(d["valid"] and d["actual"] == "FAIL" for d in obs["dimensions"].values())
                hit = None
            else:
                corrupt += 1
                target = obs["dimensions"][plan["target_dimensions"][case["variant"]]]
                hit = target["valid"] and target["actual"] == "FAIL"
                detected += hit
                counts = by_type.setdefault(case["variant"], {"detected": 0, "total": 0})
                counts["detected"] += hit
                counts["total"] += 1
            outcomes[obs["id"], obs["repeat"]] = {"retained": keep, "target_detected": hit}
        anchors = {c["group_id"]: c["id"] for c in dataset if c["variant"] == "CORRECT_FACTUAL"}
        paired = sum(outcomes[anchors[cases[key]["group_id"]], repeat]["retained"] and row["target_detected"]
                     for (key, repeat), row in outcomes.items() if row["target_detected"] is not None)
        calls = [call for r in report["reviews"] for call in r["calls"]]
        messages = [(call.get("response", {}).get("choices") or [{}])[0].get("message", {}) for call in calls]
        rt = [(call.get("usage") or {}).get("completion_tokens_details", {}).get("reasoning_tokens") for call in calls]
        local = Counter(e for r in report["reviews"] for d in r["dimensions"].values() for e in d["errors"])
        output["arms"][arm] = {"report_sha256": json_hash(report), "dimension_total": cal["dimension_total"],
            "dimensions": cal["dimensions"], "repeatability": cal["repeatability"], "cost": cal["cost"],
            "correct_retained": retained, "correct_total": correct, "correct_false_rejected": false_rejected,
            "correct_inconclusive": correct-retained-false_rejected,
            "target_detected": detected, "corrupt_total": corrupt, "paired_separation": paired, "by_error_type": by_type,
            "local_error_counts": dict(local), "global_error_counts": dict(Counter(e for r in report["reviews"] for e in r["errors"])),
            "observed_models": sorted({call.get("observed_model") for call in calls if call.get("observed_model")}),
            "reasoning_content_calls": sum(bool(m.get("reasoning_content")) for m in messages),
            "reasoning_tokens_reported_calls": sum(type(v) is int for v in rt),
            "known_reasoning_tokens": sum(v for v in rt if type(v) is int),
            "median_attempt_latency_ms": statistics.median(r["latency_ms"] for r in report["reviews"]),
            "calculator_calls": sum(len(r["tool_trace"]) for r in report["reviews"])}
        assert correct == 16 and corrupt == 24
    for name, left, right in plan["contrasts"]:
        before, after = calibrated[left], calibrated[right]
        pairs = [(a["dimensions"][d], b["dimensions"][d]) for a, b in zip(before["observations"], after["observations"]) for d in DIMENSIONS]
        assert all(a["expected"] == b["expected"] for a, b in pairs)
        common = [(a,b) for a,b in pairs if a["valid"] and b["valid"] and a["actual"] != "NO_DATA" and b["actual"] != "NO_DATA"]
        output["comparisons"][name] = {"left": left, "right": right, "common_count": len(common),
            "corrected": sum(a["actual"] != a["expected"] and b["actual"] == b["expected"] for a,b in common),
            "regressed": sum(a["actual"] == a["expected"] and b["actual"] != b["expected"] for a,b in common),
            "new_false_pass": sum(a["expected"] == a["actual"] == "FAIL" and b["actual"] in {"PASS","NOT_APPLICABLE"} for a,b in common),
            "technical_coverage_gained": sum(not a["valid"] and b["valid"] for a,b in pairs),
            "technical_coverage_lost": sum(a["valid"] and not b["valid"] for a,b in pairs)}
        if name.startswith("prompt_"):
            comparison = compare_reports(dataset, reports[left], reports[right])
            assert comparison["corrected_count"] == output["comparisons"][name]["corrected"]
            assert comparison["regressed_count"] == output["comparisons"][name]["regressed"]
            save(HERE / (name + "-comparison.json"), comparison)
    shared = [(i, d) for i in range(len(dataset) * plan["repeats"]) for d in DIMENSIONS
              if all(cal["observations"][i]["dimensions"][d]["valid"] and
                     cal["observations"][i]["dimensions"][d]["actual"] != "NO_DATA"
                     for cal in calibrated.values())]
    agreement = {arm: sum(cal["observations"][i]["dimensions"][d]["actual"] ==
                          cal["observations"][i]["dimensions"][d]["expected"] for i,d in shared)
                 for arm, cal in calibrated.items()}
    output["common_four_arm_semantics"] = {"count": len(shared), "agreement_counts": agreement,
        "interaction_agreement_count": agreement["D_prompt_on"] - agreement["C_base_on"] -
                                       agreement["B_prompt_off"] + agreement["A_base_off"]}
    save(HERE / "summary.json", output)
    print(json.dumps({arm: {k: v for k,v in metrics.items() if k in {"correct_retained", "correct_false_rejected", "target_detected", "paired_separation", "local_error_counts", "reasoning_content_calls", "calculator_calls"}}
                      for arm, metrics in output["arms"].items()}, ensure_ascii=False))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("check", "run", "summarize"))
    mode = parser.parse_args().mode
    if mode == "run":
        run()
    elif mode == "summarize":
        summarize()
    else:
        plan, dataset = inputs()
        print(json.dumps({"status": "PREFLIGHT_OK", "cases": len(dataset), "attempts": len(plan["schedule"])}))
