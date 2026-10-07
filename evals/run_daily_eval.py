"""Daily regression: the cheap evaluations, one command, compared with the previous run.

  python evals/run_daily_eval.py            (or: .\\init.ps1 -Mode eval)

Steps
  harness   Offline DEEP completion policy on harness_golden_set (no services, no model).
  planner   LIVE_COORDINATOR routing on agent_golden_set: the production recognizer
            (LLM qwen3.8-flash + embedding + pattern + n-gram). Needs the backend and
            STOCKSAGE_ADMIN_TOKEN. DETERMINISTIC mode is not used here: it scores the
            keyword fallback only, not the production router.
  rag       Retrieval-only metrics on golden_set via /api/docs/search (the production
            RagService.retrieve path). Needs the backend; no answer generation.

A step whose prerequisites are missing is SKIPPED, never PASS. Metric thresholds are the
existing gates; identity pins (dataset hash, case counts, schema/policy versions) are
reported as IDENTITY_CHANGED instead of failing, so cases can be added without editing
gate files. Daily results are regression signals, not release evidence: formal acceptance
still uses the individual runners with their pinned gates.

Output: evals/results/daily/<timestamp>/ with each report and summary.json; one line per
run is appended to evals/results/daily/history.jsonl. Exit 1 if any step FAILED.
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

import requests

from rag_summary import DEFAULT_GATES as RAG_GATES
from run_rag_eval import load_jsonl, retrieval_metrics

HERE = Path(__file__).resolve().parent
DAILY = HERE / "results" / "daily"
RAG_RETRIEVAL_METRICS = ("context_recall", "context_precision", "mrr", "ndcg")
IDENTITY_GATES = {
    "planner_schema_version", "planner_requested_cases", "planner_dataset_sha256",
    "planner_live_total_cases", "planner_live_skipped_live_only_cases", "planner_live_context_cases",
    "planner_live_intent_cases", "planner_live_context_resolution_cases",
    "planner_deterministic_total_cases", "planner_deterministic_skipped_live_only_cases",
    "harness_engine", "harness_schema", "harness_case_schema", "harness_policy_id",
    "harness_policy_version", "harness_case_count", "harness_dataset_sha256",
}


def classify(gates: list[dict]) -> dict:
    """Split gate results into metric failures (regressions) and identity changes (informational)."""
    failed = [g["metric"] for g in gates if g.get("status") == "failed" and g.get("metric") not in IDENTITY_GATES]
    identity = [g["metric"] for g in gates if g.get("status") == "failed" and g.get("metric") in IDENTITY_GATES]
    return {"failed": failed, "identity_changed": identity}


def run_python(args: list[str], timeout: int) -> subprocess.CompletedProcess:
    env = {**os.environ, "PYTHONIOENCODING": "utf-8"}
    return subprocess.run([sys.executable, *args], cwd=HERE, capture_output=True, text=True,
                          encoding="utf-8", errors="replace", timeout=timeout, env=env)


def backend_up(base_url: str) -> bool:
    try:
        with urllib.request.urlopen(f"{base_url}/api/auth/csrf", timeout=5) as response:
            return response.status == 200
    except OSError:
        return False


def step_harness(out: Path, skip_build: bool) -> dict:
    report = out / "harness.json"
    command = ["run_harness_eval.py", "--output", str(report), "--summary-output", str(out / "harness.summary.json")]
    if skip_build:
        command.append("--skip-build")
    done = run_python(command, timeout=900)
    summary_path = out / "harness.summary.json"
    if done.returncode == 2 or not summary_path.exists():
        return {"status": "ERROR", "detail": (done.stdout + done.stderr).strip()[-600:]}
    summary = json.loads(summary_path.read_text(encoding="utf-8"))
    gates = classify(summary["gates"])
    quality = summary.get("quality") or {}
    metrics = {g["metric"]: g.get("value") for g in summary["gates"]
               if isinstance(g.get("value"), (int, float)) and not isinstance(g.get("value"), bool)}
    return {"status": "FAIL" if gates["failed"] else "PASS", **gates, "metrics": metrics,
            "dataset_sha256": summary.get("dataset_sha256"), "coverage": quality.get("coverage_status")}


def step_planner(out: Path, base_url: str, token: str, baseline: Path | None) -> dict:
    report = out / "planner.json"
    command = ["run_agent_eval.py", "--mode", "LIVE_COORDINATOR", "--endpoint", f"{base_url}/api/eval/agent/planner",
               "--output", str(report), "--admin-token", token,
               # 115 cases go in one sequential HTTP request; the runner's 600 s default is too short.
               "--timeout-seconds", "1800"]
    if baseline:
        command += ["--baseline", str(baseline)]
    done = run_python(command, timeout=2100)
    if done.returncode == 3 or not report.exists():
        return {"status": "ERROR", "detail": (done.stdout + done.stderr).strip()[-600:]}
    payload = json.loads(report.read_text(encoding="utf-8"))
    planner = payload["planner"]
    # The unified report appends Harness gates (not supplied here); keep only the planner's own gates.
    gates = classify([g for g in payload["gates"] if not str(g.get("metric", "")).startswith("harness")])
    names = ("route_accuracy", "macro_f1", "required_action_recall", "forbidden_action_rate", "critical_failures",
             "fallback_rate", "llm_signal_accuracy", "intent_accuracy", "context_case_accuracy", "p95_latency_ms")
    metrics = {name: planner.get(name) for name in names if planner.get(name) is not None}
    misrouted = [{"id": r.get("id"), "expected": r.get("expectedRoute"), "actual": r.get("actualRoute")}
                 for r in planner.get("results", []) if r.get("expectedRoute") and r.get("actualRoute") != r.get("expectedRoute")]
    return {"status": "FAIL" if gates["failed"] else "PASS", **gates, "metrics": metrics,
            "dataset_sha256": planner.get("dataset_sha256"), "misrouted": misrouted}


def step_rag(out: Path, base_url: str) -> dict:
    cases = [case for case in load_jsonl(HERE / "golden_set.jsonl") if case.get("expected_evidence")]
    rows = []
    for case in cases:
        response = requests.get(f"{base_url}/api/docs/search", params={"q": case["question"], "full": "true"}, timeout=120)
        response.raise_for_status()
        # /api/docs/search returns {content, metadata}; the scorer expects metadata fields at the top level.
        contexts = [{**(item.get("metadata") or {}), "content": item.get("content", "")} for item in response.json()]
        metrics = retrieval_metrics(contexts, case["expected_evidence"])
        rows.append({"id": case["id"], "category": case.get("category"),
                     **{name: metrics[name] for name in RAG_RETRIEVAL_METRICS}})
    averages = {name: sum(row[name] for row in rows) / len(rows) for name in RAG_RETRIEVAL_METRICS}
    gates = [{"metric": name, "value": averages[name], "threshold": RAG_GATES[name]["minimum"],
              "status": "passed" if averages[name] >= RAG_GATES[name]["minimum"] else "failed"}
             for name in RAG_RETRIEVAL_METRICS]
    (out / "rag-retrieval.json").write_text(json.dumps({"cases": rows, "averages": averages, "gates": gates},
                                                       ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    worst = sorted(rows, key=lambda row: (row["context_recall"], row["mrr"]))[:5]
    failed = [g["metric"] for g in gates if g["status"] == "failed"]
    return {"status": "FAIL" if failed else "PASS", "failed": failed, "identity_changed": [],
            "metrics": averages, "case_count": len(rows), "worst_cases": [row["id"] for row in worst]}


def previous_run() -> dict | None:
    history = DAILY / "history.jsonl"
    if not history.exists():
        return None
    lines = [line for line in history.read_text(encoding="utf-8").splitlines() if line.strip()]
    return json.loads(lines[-1]) if lines else None


def deltas(current: dict, previous: dict | None) -> dict:
    if not previous:
        return {}
    result = {}
    for name, step in current.items():
        before = (previous.get("steps") or {}).get(name) or {}
        for metric, value in (step.get("metrics") or {}).items():
            old = (before.get("metrics") or {}).get(metric)
            if isinstance(value, (int, float)) and isinstance(old, (int, float)) and value != old:
                result[f"{name}.{metric}"] = round(value - old, 4)
    return result


def git_sha() -> str:
    try:
        return subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=HERE, capture_output=True,
                              text=True, timeout=10).stdout.strip()
    except (OSError, subprocess.TimeoutExpired):
        return ""


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--only", choices=["harness", "planner", "rag"], action="append",
                        help="Run only these steps (repeatable)")
    parser.add_argument("--skip-build", action="store_true", help="Reuse the compiled backend for the Harness step")
    args = parser.parse_args()
    selected = args.only or ["harness", "planner", "rag"]
    started = datetime.now(timezone.utc)
    out = DAILY / started.strftime("%Y%m%dT%H%M%SZ")
    out.mkdir(parents=True)
    previous = previous_run()
    up = backend_up(args.base_url)
    token = os.environ.get("STOCKSAGE_ADMIN_TOKEN", "")
    steps: dict[str, dict] = {}
    for name in selected:
        print(f"== {name} ...", flush=True)
        try:
            if name == "harness":
                steps[name] = step_harness(out, args.skip_build)
            elif not up:
                steps[name] = {"status": "SKIPPED", "detail": f"backend not reachable at {args.base_url}"}
            elif name == "planner" and not token:
                steps[name] = {"status": "SKIPPED", "detail": "STOCKSAGE_ADMIN_TOKEN is not set"}
            elif name == "planner":
                baseline_dir = (previous or {}).get("output")
                baseline = Path(baseline_dir) / "planner.json" if baseline_dir else None
                steps[name] = step_planner(out, args.base_url, token,
                                           baseline if baseline and baseline.exists() else None)
            else:
                steps[name] = step_rag(out, args.base_url)
        except (OSError, ValueError, KeyError, requests.RequestException, subprocess.TimeoutExpired) as error:
            steps[name] = {"status": "ERROR", "detail": f"{type(error).__name__}: {error}"[:600]}
    summary = {"schema": "daily_eval_v1", "startedAt": started.isoformat(), "gitSha": git_sha(),
               "output": str(out), "steps": steps, "deltaFromPrevious": deltas(steps, previous),
               "previousStartedAt": (previous or {}).get("startedAt")}
    (out / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    with (DAILY / "history.jsonl").open("a", encoding="utf-8") as history:
        history.write(json.dumps(summary, ensure_ascii=False) + "\n")

    print(f"\nDaily eval {summary['startedAt']} (git {summary['gitSha']}) -> {out}")
    for name, step in steps.items():
        line = f"  {name:<8} {step['status']:<8}"
        if step.get("metrics"):
            line += " " + ", ".join(f"{k}={v:.3f}" if isinstance(v, float) else f"{k}={v}"
                                    for k, v in step["metrics"].items())
        print(line)
        for key in ("failed", "identity_changed"):
            if step.get(key):
                print(f"           {key}: {', '.join(step[key])}")
        if step.get("detail"):
            print(f"           {step['detail']}")
        if step.get("misrouted"):
            print(f"           misrouted ({len(step['misrouted'])}): "
                  + ", ".join(f"{r['id']} {r['expected']}->{r['actual']}" for r in step["misrouted"][:8]))
        if step.get("worst_cases"):
            print(f"           worst retrieval cases: {', '.join(step['worst_cases'])}")
    if summary["deltaFromPrevious"]:
        print("  changed vs previous run: " + ", ".join(f"{k} {v:+}" for k, v in summary["deltaFromPrevious"].items()))
    skipped = [name for name, step in steps.items() if step["status"] == "SKIPPED"]
    if skipped:
        print(f"  NOTE: skipped steps were not evaluated: {', '.join(skipped)}")
    return 1 if any(step["status"] in {"FAIL", "ERROR"} for step in steps.values()) else 0


if __name__ == "__main__":
    raise SystemExit(main())
