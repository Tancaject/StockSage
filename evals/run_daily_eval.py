"""Scorecard runner: regression layers by tier, run-to-run noise, config identity and staleness.

  python evals/run_daily_eval.py                      daily tier (or: .\\init.ps1 -Mode eval)
  python evals/run_daily_eval.py --tier change        adds the answer-quality layers
  python evals/run_daily_eval.py --since <git-sha>    only the layers affected by changes since <sha>
  python evals/run_daily_eval.py --repeat 2           run ordinary and deep_evidence twice and record noise

Layers
  harness        Offline DEEP completion policy on harness_golden_set (no services, no model).
  planner        LIVE_COORDINATOR routing on agent_golden_set: the production recognizer. Needs the
                 backend and STOCKSAGE_ADMIN_TOKEN. DETERMINISTIC mode only scores the keyword fallback.
  rag            Retrieval-only metrics on golden_set via /api/docs/search; no answer generation.
  deep_evidence  DEEP evidence stage only (request-level EVIDENCE_ONLY): completion, terminal safety,
                 usable evidence and loop stop reasons. Daily runs 4 smoke cases, change/milestone runs
                 all of deep_react_cases. Needs the admin token, STOCKSAGE_EVAL_EMAIL/PASSWORD and an
                 authenticated IBKR session (market evidence would be missing otherwise).
  ordinary       Ordinary chat execution contract on ordinary_live_cases + ordinary_compare_cases.
                 Fails on cases that passed in the previous run of this layer and fail now.

Tiers: daily = harness, planner, rag, deep_evidence (smoke); change = milestone = daily + ordinary with
the full DEEP set, and both answer layers are scored by the AI judge (run_evaluation_agent.py: ordinary
five dimensions, DEEP facet coverage). Judge metrics are prefixed judge_ and never gate a layer until the
judge is calibrated on human labels. A step whose prerequisites are missing is SKIPPED, never PASS.
Identity pins (dataset hash, case counts, schema/policy versions) are reported as IDENTITY_CHANGED.

--since maps changed paths (git diff plus untracked files) to layers through CHANGE_MAP; debate/report
changes, unmapped product code and frontend changes are printed as NOT COVERED.

--repeat N stores each metric's standard deviation in results/daily/noise.json; later runs mark a
change vs the last run of the same layer as within noise when |delta| <= 2 * sd.

Output: evals/results/daily/<timestamp>/ with each report and summary.json; one line per run is
appended to evals/results/daily/history.jsonl. Exit 1 if any step FAILED or ERRORED.
"""
from __future__ import annotations

import argparse
import fnmatch
import hashlib
import json
import os
import ssl
import statistics
import subprocess
import sys
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

import requests

import deep_facet_quality as deep
from rag_summary import DEFAULT_GATES as RAG_GATES
from run_rag_eval import load_jsonl, retrieval_metrics

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
DAILY = HERE / "results" / "daily"
NOISE = DAILY / "noise.json"
ADMIN_HEADER = "X-StockSage-Admin-Token"
RAG_RETRIEVAL_METRICS = ("context_recall", "context_precision", "mrr", "ndcg")
IDENTITY_GATES = {
    "planner_schema_version", "planner_requested_cases", "planner_dataset_sha256",
    "planner_live_total_cases", "planner_live_skipped_live_only_cases", "planner_live_context_cases",
    "planner_live_intent_cases", "planner_live_context_resolution_cases",
    "planner_deterministic_total_cases", "planner_deterministic_skipped_live_only_cases",
    "harness_engine", "harness_schema", "harness_case_schema", "harness_policy_id",
    "harness_policy_version", "harness_case_count", "harness_dataset_sha256",
}
LAYERS = ("harness", "planner", "rag", "deep_evidence", "ordinary")
TIERS = {
    "daily": ("harness", "planner", "rag", "deep_evidence"),
    "change": LAYERS,
    "milestone": LAYERS,
}
DEEP_SMOKE_IDS = ("googl_doj_adtech", "nvda_export_controls", "aapl_app_store_regulation", "nvda_supplier_capacity")
STALE_DAYS = 7
# Only layers with model generation or live search are repeated; routing and retrieval are near-deterministic.
REPEATED_LAYERS = {"ordinary", "deep_evidence"}
# Judge settings validated on 10-02 (qwen3.8-max, thinking budget 4096); a different family from the deepseek
# generator. Judge metrics are diagnostic until the judge is calibrated on human labels (eval plan S5).
JUDGE_ARGS = ["--model", "qwen3.8-max", "--enable-thinking", "--thinking-budget", "4096", "--max-tokens", "8000",
              "--repeats", "1", "--workers", "2"]

# Changed path (glob, relative to the repo root; "*" also matches "/") -> affected layers. Every matching
# entry applies. An empty tuple marks known product code that needs no regression layer (auth, admin,
# tracing); product code that matches nothing is reported as unmapped. "deep_report" is not a runnable
# layer yet; it is reported so that debate/report changes are not mistaken for covered changes.
JAVA = "stocksage-backend/src/main/java/com/stocksage/"
RES = "stocksage-backend/src/main/resources/"
ANSWER_LAYERS = ("planner", "rag", "ordinary", "deep_evidence", "deep_report")
TOOLS = ("ordinary", "deep_evidence")
DEEP = ("deep_evidence", "deep_report")
CHANGE_MAP = (
    # Model routing, thinking budget and shared model clients affect every answer layer.
    (RES + "application.properties", ANSWER_LAYERS),
    (JAVA + "config/AgentConfig.java", ANSWER_LAYERS),
    (JAVA + "config/ThinkingModels.java", ANSWER_LAYERS),
    (JAVA + "agent/AgentRuntimeConfiguration.java", ANSWER_LAYERS),
    (JAVA + "agent/ModelInvocationAdvisor.java", ANSWER_LAYERS),
    (JAVA + "util/*", ANSWER_LAYERS),
    # Routing.
    (JAVA + "agent/intent/*", ("planner",)),
    (JAVA + "agent/Coordinator.java", ("planner", "ordinary")),
    (JAVA + "agent/RoutePlanCatalog.java", ("planner", "ordinary")),
    (JAVA + "agent/Routing*", ("planner",)),
    (JAVA + "agent/Plan*", ("planner", "ordinary")),
    (JAVA + "agent/ModelTier.java", ("planner", "ordinary")),
    (JAVA + "config/AiConfig.java", ("planner", "rag", "ordinary")),
    (JAVA + "service/PlannerEvalService.java", ("planner",)),
    (JAVA + "model/dto/PlannerEval*", ("planner",)),
    (JAVA + "model/dto/RouteEvalMetrics.java", ("planner",)),
    (JAVA + "service/TickerResolutionService.java", ("planner",) + TOOLS),
    ("evals/agent_*", ("planner",)),
    ("evals/run_agent_eval.py", ("planner",)),
    # Retrieval and knowledge.
    (JAVA + "rag/*", ("rag", "ordinary")),
    (JAVA + "knowledge/*", ("rag", "ordinary")),
    (JAVA + "config/EmbeddingConfig.java", ("rag",)),
    (JAVA + "service/RagEvalService.java", ("rag",)),
    (JAVA + "controller/RagEvalController.java", ("rag",)),
    (JAVA + "controller/DocumentController.java", ("rag",)),
    (JAVA + "model/dto/RagEval*", ("rag",)),
    (JAVA + "repository/VectorDocument*", ("rag",)),
    (JAVA + "repository/DocIndex*", ("rag",)),
    (JAVA + "repository/ContextualGist*", ("rag",)),
    ("evals/golden_set.jsonl", ("rag",)),
    ("evals/run_rag_eval.py", ("rag",)),
    ("evals/run_retrieval_eval.py", ("rag",)),
    ("evals/ingest_corpus.py", ("rag",)),
    # Ordinary route: analysts, planner, prompts, memory and FUNDAMENTALS methods.
    (JAVA + "agent/Ordinary*", ("ordinary",)),
    (JAVA + "agent/Fundamentals*", ("ordinary",)),
    (JAVA + "agent/MarketAgent.java", ("ordinary",)),
    (JAVA + "agent/NewsAgent.java", ("ordinary",)),
    (JAVA + "agent/ModelCompletion.java", ("ordinary",)),
    (JAVA + "agent/ChatConcurrencyAdvisor.java", ("ordinary",)),
    (JAVA + "agent/DataRequirement.java", ("ordinary",)),
    (JAVA + "agent/ReadRequest.java", ("ordinary",)),
    (JAVA + "agent/ExecutionPlan.java", ("ordinary",)),
    (RES + "prompts/*", ("ordinary",)),
    (RES + "evolution/*", ("ordinary",)),
    (JAVA + "evolution/FundamentalsMethodRegistry.java", ("ordinary",)),
    (JAVA + "evolution/FundamentalsRuntimeIdentity.java", ("ordinary",)),
    (JAVA + "evolution/ApprovedMethodArtifact.java", ("ordinary",)),
    (JAVA + "evolution/AgentPolicyBundle.java", ("ordinary",)),
    (JAVA + "conversation/*", ("ordinary",)),
    (JAVA + "memory/*", ("ordinary",)),
    (JAVA + "service/OrdinaryEvidence.java", ("ordinary",)),
    (JAVA + "harness/OrdinaryCompletionPolicy.java", ("ordinary",)),
    ("evals/ordinary_*", ("ordinary",)),
    ("evals/run_ordinary_live_eval.py", ("ordinary",)),
    # Shared entry points and the tool layer used by both routes.
    (JAVA + "conversation/ChatService.java", TOOLS),
    (JAVA + "controller/ChatController.java", TOOLS),
    (JAVA + "model/dto/ChatRequest.java", TOOLS),
    (JAVA + "service/ToolPrefetchService.java", TOOLS),
    (JAVA + "service/StockNewsService.java", TOOLS),
    (JAVA + "service/KLinePayloadMapper.java", TOOLS),
    (JAVA + "tool/*", TOOLS),
    (JAVA + "capability/*", TOOLS),
    (JAVA + "client/*", TOOLS),
    (JAVA + "ibkr/*", TOOLS),
    (JAVA + "cache/*", TOOLS),
    (JAVA + "mcp/*", TOOLS),
    (JAVA + "skill/*", TOOLS),
    (JAVA + "config/AsyncConfig.java", TOOLS),
    (RES + "capabilities/*", TOOLS),
    (RES + "skills/*", TOOLS),
    ("stocksage-data-service/*", TOOLS),
    # Evidence models feed both routes and the debate.
    (JAVA + "evidence/*", TOOLS + ("deep_report",)),
    # DEEP evidence stage, task infrastructure and completion policy.
    (JAVA + "harness/*", ("harness", "deep_evidence")),
    ("evals/harness*", ("harness",)),
    ("evals/run_harness_eval.py", ("harness",)),
    (JAVA + "research/DeepEvidence*", ("deep_evidence",)),
    (JAVA + "research/EvidenceObservationCompressor.java", ("deep_evidence",)),
    (JAVA + "research/Research*", ("deep_evidence",)),
    (JAVA + "research/DeepResearchPipeline.java", DEEP),
    (JAVA + "research/ModelInvocationStore.java", DEEP),
    (JAVA + "agent/DeepEvidence*", ("deep_evidence",)),
    (JAVA + "model/dto/AnalysisState.java", DEEP),
    (JAVA + "model/dto/ModelInvocationContext.java", DEEP),
    (JAVA + "model/entity/ResearchTask*", ("deep_evidence",)),
    (JAVA + "repository/ResearchTask*", ("deep_evidence",)),
    (JAVA + "service/CompanyRelationService.java", ("deep_evidence",)),
    (JAVA + "service/RelationExtraction*", ("deep_evidence",)),
    (JAVA + "config/ModelTokenBudgetProperties.java", DEEP),
    (JAVA + "config/ResearchHttpDeadlineConfiguration.java", DEEP),
    (JAVA + "config/AdminApiInterceptor.java", ("planner", "deep_evidence")),
    ("evals/deep_react_cases.jsonl", ("deep_evidence",)),
    ("evals/run_deep_m0.py", ("deep_evidence",)),
    # DEEP debate and report (no runnable layer yet).
    (JAVA + "research/ResearchDebateService.java", ("deep_report",)),
    (JAVA + "research/ReportMarkdownRenderer.java", ("deep_report",)),
    (JAVA + "research/InvestmentReport*", ("deep_report",)),
    (JAVA + "agent/BullResearcher.java", ("deep_report",)),
    (JAVA + "agent/BearResearcher.java", ("deep_report",)),
    (JAVA + "agent/ResearchManager.java", ("deep_report",)),
    (JAVA + "agent/Debate*", ("deep_report",)),
    (JAVA + "model/dto/DebateModels.java", ("deep_report",)),
    (JAVA + "model/dto/InvestmentReport.java", ("deep_report",)),
    # Known product code without a regression layer.
    (JAVA + "controller/*", ()),
    (JAVA + "identity/*", ()),
    (JAVA + "security/*", ()),
    (JAVA + "trace/*", ()),
    (JAVA + "exception/*", ()),
    (JAVA + "repository/*", ()),
    (JAVA + "model/*", ()),
    (JAVA + "*package-info.java", ()),
    (JAVA + "*Application.java", ()),
    (JAVA + "agent/AgentStep.java", ()),
    (JAVA + "service/AgentAdminService.java", ()),
    (JAVA + "service/UserService.java", ()),
    (JAVA + "service/Workbench*", ()),
    (JAVA + "service/OfflineDemoSampleService.java", ()),
    (JAVA + "evolution/EvolutionFailurePool.java", ()),
    (JAVA + "evolution/EvolutionReplayService.java", ()),
    (JAVA + "config/SecurityConfig.java", ()),
    (JAVA + "config/SpaCsrfTokenRequestHandler.java", ()),
    (JAVA + "config/WebConfig.java", ()),
    (JAVA + "config/Phoenix*", ()),
    (JAVA + "config/ResearchTaskMetricsConfig.java", ()),
    (JAVA + "config/RuntimeArtifactIdentity.java", ()),
    (JAVA + "config/ModelPricingProperties.java", ()),
    (RES + "db/*", ()),
    ("stocksage-backend/src/main/java/db/*", ()),
    (RES + "demo/*", ()),
    (RES + "application-local.properties.example", ()),
)


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


def ibkr_authenticated(gateway: str) -> bool:
    try:
        request = urllib.request.Request(gateway + "/v1/api/iserver/auth/status", data=b"", method="POST",
                                         headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(request, timeout=8, context=ssl._create_unverified_context()) as response:
            return '"authenticated":true' in response.read().decode("utf-8", "replace")
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


def step_planner(out: Path, base_url: str, token: str, baseline: Path | None, tag: str = "") -> dict:
    report = out / f"planner{tag}.json"
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


def step_rag(out: Path, base_url: str, tag: str = "") -> dict:
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
    (out / f"rag-retrieval{tag}.json").write_text(json.dumps({"cases": rows, "averages": averages, "gates": gates},
                                                             ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    worst = sorted(rows, key=lambda row: (row["context_recall"], row["mrr"]))[:5]
    failed = [g["metric"] for g in gates if g["status"] == "failed"]
    return {"status": "FAIL" if failed else "PASS", "failed": failed, "identity_changed": [],
            "metrics": averages, "case_count": len(rows), "worst_cases": [row["id"] for row in worst]}


def judge(rubric: str, report: Path, output: Path, case_count: int) -> dict:
    """AI judge on a layer's saved results. judge_* metrics are diagnostic and never gate the layer."""
    done = run_python(["run_evaluation_agent.py", "--rubric", rubric, "--input", str(report),
                       "--output", str(output), *JUDGE_ARGS], timeout=240 * case_count + 300)
    if not output.exists():
        return {"judge_error": (done.stdout + done.stderr).strip()[-300:]}
    reviews = json.loads(output.read_text(encoding="utf-8"))["reviews"]
    valid = [row for row in reviews if row.get("technical_status") == "VALID"]
    metrics = {"judge_invalid_rate": 1 - len(valid) / len(reviews) if reviews else 1.0}
    if rubric == "deep_facets":
        summary = deep.summarize(reviews)
        metrics.update(judge_invalid_rate=summary["technical_invalid_rate"],
                       judge_facet_score_mean=summary["facet_score_mean"],
                       judge_direct_coverage_rate=summary["direct_coverage_rate"],
                       judge_uncovered_rate=summary["uncovered_rate"])
        flagged = sorted({row["id"] for row in reviews for f in (row.get("facets") or {}).values() if f["score"] == 0})
    else:
        metrics["judge_answer_pass_rate"] = sum(row["status"] == "PASS" for row in valid) / len(valid) if valid else None
        flagged = sorted(row["id"] for row in valid if row["status"] == "FAIL")
    return {"judge_metrics": {key: value for key, value in metrics.items() if value is not None},
            "judge_flagged": flagged, "judge_output": str(output)}


def step_deep_evidence(out: Path, base_url: str, gateway: str, smoke: bool, judged: bool, tag: str = "") -> dict:
    cases = [json.loads(line) for line in (HERE / "deep_react_cases.jsonl").read_text(encoding="utf-8").splitlines()
             if line.strip()]
    if smoke:
        cases = [case for case in cases if case["id"] in DEEP_SMOKE_IDS]
    case_file = out / f"deep-evidence-cases{tag}.jsonl"
    case_file.write_text("".join(json.dumps(case, ensure_ascii=False) + "\n" for case in cases), encoding="utf-8")
    report = out / f"deep-evidence{tag}.json"
    done = run_python(["run_deep_m0.py", "--arm", "SCORECARD", "--cases", str(case_file), "--output", str(report),
                       "--base-url", base_url, "--evidence-only", "--ibkr-gateway", gateway],
                      timeout=300 * len(cases) + 300)
    if not report.exists():
        return {"status": "ERROR", "detail": (done.stdout + done.stderr).strip()[-600:]}
    rows = json.loads(report.read_text(encoding="utf-8"))["cases"]
    completed, safe, rejected, evidence, seconds, per_case = 0, 0, [], [], [], {}
    for row in rows:
        run = row.get("run") or {}
        ok = run.get("result_kind") == "EVIDENCE_ONLY" and not row.get("error")
        completed += ok
        safe += ok and not run.get("unsafe_reasons")
        stops = [step.get("stopReason") or "" for step in row.get("replan") or [] if step.get("stepKind") == "loop_stop"]
        rejected += [row["id"] for stop in stops if stop.startswith("REJECTED")]
        usable = (row.get("evidence_digest") or "").count("citable: true")
        evidence.append(usable)
        if run.get("wall_seconds"):
            seconds.append(run["wall_seconds"])
        per_case[row["id"]] = {"evidence": usable, "stop": stops[-1] if stops else "", "ok": ok}
    total = len(cases)
    metrics = {"completion_rate": completed / total, "safe_terminal_rate": safe / total,
               "loop_rejected": len(rejected), "mean_usable_evidence": statistics.fmean(evidence) if evidence else 0.0,
               "mean_wall_seconds": statistics.fmean(seconds) if seconds else 0.0}
    failed = [name for name, bad in (("completion_rate", metrics["completion_rate"] < 1),
                                     ("safe_terminal_rate", metrics["safe_terminal_rate"] < 1),
                                     ("loop_rejected", metrics["loop_rejected"] > 0)) if bad]
    verdict = judge("deep_facets", report, out / f"deep-facet-judge{tag}.json", total) if judged else {}
    metrics.update(verdict.pop("judge_metrics", {}))
    return {"status": "FAIL" if failed else "PASS", "failed": failed, "identity_changed": [], "metrics": metrics,
            "case_count": total, "smoke": smoke, "cases": per_case, **verdict}


def step_ordinary(out: Path, base_url: str, previous_step: dict | None, judged: bool, tag: str = "") -> dict:
    case_file = out / f"ordinary-cases{tag}.jsonl"
    # The scorecard measures the production configuration. cmp-* cases need multi-target fan-out, which is
    # off by default (10-12), so they would all route DIRECT and fail; they belong to multi-target experiments.
    lines = [line for name in ("ordinary_live_cases.jsonl", "ordinary_compare_cases.jsonl")
             for line in (HERE / name).read_text(encoding="utf-8").splitlines()
             if line.strip() and not json.loads(line)["id"].startswith("cmp-")]
    case_file.write_text("\n".join(lines) + "\n", encoding="utf-8")
    report = out / f"ordinary{tag}.json"
    done = run_python(["run_ordinary_live_eval.py", "--base-url", base_url, "--cases", str(case_file),
                       "--output", str(report), "--label", "scorecard"], timeout=3600)
    if not report.exists():
        return {"status": "ERROR", "detail": (done.stdout + done.stderr).strip()[-600:]}
    payload = json.loads(report.read_text(encoding="utf-8"))
    if payload.get("status") == "BLOCKED":
        return {"status": "ERROR", "detail": str(payload.get("error"))[:600]}
    cases = {case["id"]: bool(case.get("passed")) for case in payload["cases"]}
    latencies = sorted(case.get("latency_ms") or 0 for case in payload["cases"])
    previous_cases = (previous_step or {}).get("cases") or {}
    # Execution checks have no stable all-pass baseline (unsupported-bar is a known failure), so the gate
    # is per case: a case that passed in the previous run of this layer must not fail now.
    newly_failing = sorted(case_id for case_id, passed in cases.items() if not passed and previous_cases.get(case_id))
    metrics = {"execution_pass_rate": payload.get("execution_contract_pass_rate") or 0.0,
               "p95_latency_ms": latencies[int(0.95 * (len(latencies) - 1))] if latencies else 0}
    verdict = judge("ordinary", report, out / f"ordinary-judge{tag}.json", len(cases)) if judged else {}
    metrics.update(verdict.pop("judge_metrics", {}))
    return {"status": "FAIL" if newly_failing else "PASS", "failed": ["newly_failing_cases"] if newly_failing else [],
            "identity_changed": [], "metrics": metrics, "case_count": len(cases), "cases": cases,
            "newly_failing": newly_failing, "known_failing": sorted(c for c, p in cases.items() if not p and c not in newly_failing),
            "dataset_sha256": payload.get("dataset_sha256"), **verdict}


def history() -> list[dict]:
    path = DAILY / "history.jsonl"
    if not path.exists():
        return []
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def last_step(runs: list[dict], name: str) -> dict | None:
    """Most recent evaluated (PASS/FAIL) result of a layer, for per-case comparison and baselines."""
    for run in reversed(runs):
        step = (run.get("steps") or {}).get(name)
        if step and step.get("status") in {"PASS", "FAIL"}:
            return {**step, "output": run.get("output"), "startedAt": run.get("startedAt")}
    return None


def staleness(runs: list[dict], now: datetime) -> dict:
    result = {}
    for name in LAYERS:
        for run in reversed(runs):
            if ((run.get("steps") or {}).get(name) or {}).get("status") in {"PASS", "FAIL"}:
                result[name] = round((now - datetime.fromisoformat(run["startedAt"])).total_seconds() / 86400, 1)
                break
        else:
            result[name] = None
    return result


def deltas(current: dict, runs: list[dict]) -> dict:
    """Metric changes vs the most recent evaluated run of the same layer (runs may cover other layers)."""
    result = {}
    for name, step in current.items():
        before = last_step(runs, name) or {}
        for metric, value in (step.get("metrics") or {}).items():
            old = (before.get("metrics") or {}).get(metric)
            if isinstance(value, (int, float)) and isinstance(old, (int, float)) and value != old:
                result[f"{name}.{metric}"] = round(value - old, 4)
    return result


def aggregate(results: list[dict]) -> dict:
    """Combine repeated runs: mean metrics, per-metric sd, the worst status and the first run's details."""
    order = {"ERROR": 3, "FAIL": 2, "SKIPPED": 1, "PASS": 0}
    combined = {**results[0], "status": max((r["status"] for r in results), key=lambda s: order.get(s, 0))}
    runs = [r.get("metrics") or {} for r in results if r.get("metrics")]
    if len(runs) >= 2:
        names = [m for m in runs[0] if all(isinstance(run.get(m), (int, float)) for run in runs)]
        combined["metrics"] = {m: statistics.fmean(run[m] for run in runs) for m in names}
        combined["noise_sd"] = {m: statistics.stdev(run[m] for run in runs) for m in names}
        combined["repeats"] = runs
    return combined


def changed_layers(since: str) -> tuple[set[str], list[str], list[str]]:
    """Layers affected by changes between <since> and the working tree, plus unmapped and uncovered paths."""
    git = ["git", "-c", "core.quotepath=off"]
    diff = subprocess.run(git + ["diff", "--name-only", since], cwd=ROOT, capture_output=True, text=True,
                          encoding="utf-8", check=True)
    untracked = subprocess.run(git + ["ls-files", "--others", "--exclude-standard"], cwd=ROOT,
                               capture_output=True, text=True, encoding="utf-8", check=True)
    paths = sorted({p for p in (diff.stdout + untracked.stdout).splitlines() if p.strip()})
    layers, unmapped = set(), []
    for path in paths:
        matched = [mapped for pattern, mapped in CHANGE_MAP if fnmatch.fnmatchcase(path, pattern)]
        layers.update(layer for mapped in matched for layer in mapped)
        # Only product code must be mapped; eval tooling and experiment data may change freely.
        if not matched and path.startswith(("stocksage-backend/src/main/", "stocksage-data-service/")):
            unmapped.append(path)
    return layers, unmapped, paths


def model_config(base_url: str, token: str) -> dict | None:
    try:
        response = requests.get(f"{base_url}/api/admin/agent/model-config", headers={ADMIN_HEADER: token}, timeout=15)
        response.raise_for_status()
        return response.json()
    except requests.RequestException:
        return None


def git_sha() -> str:
    try:
        return subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=HERE, capture_output=True,
                              text=True, timeout=10).stdout.strip()
    except (OSError, subprocess.TimeoutExpired):
        return ""


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--tier", choices=sorted(TIERS), default="daily")
    parser.add_argument("--only", choices=LAYERS, action="append", help="Run only these layers (repeatable)")
    parser.add_argument("--since", help="Run only the layers affected by changes since this git revision")
    parser.add_argument("--repeat", type=int, default=1, help="Run live layers N times and record noise")
    parser.add_argument("--ibkr-gateway", default="https://localhost:5000")
    parser.add_argument("--skip-build", action="store_true", help="Reuse the compiled backend for the Harness step")
    parser.add_argument("--no-judge", action="store_true", help="Skip the AI judge in the change/milestone tiers")
    args = parser.parse_args()
    if args.repeat < 1:
        parser.error("--repeat must be positive")

    tier = "change" if args.since and args.tier == "daily" else args.tier
    selected = list(args.only or TIERS[tier])
    uncovered: list[str] = []
    if args.since:
        affected, unmapped, paths = changed_layers(args.since)
        print(f"== {len(paths)} changed paths since {args.since}; affected layers: {', '.join(sorted(affected)) or 'none'}")
        if "deep_report" in affected:
            uncovered.append("deep_report (debate/report quality is not a runnable layer yet)")
        if unmapped:
            uncovered.append("unmapped paths: " + ", ".join(unmapped[:12]) + (" ..." if len(unmapped) > 12 else ""))
        if any(path.startswith("stocksage-frontend/") for path in paths):
            uncovered.append("frontend changes (check the rendered page in a browser)")
        selected = [layer for layer in selected if layer in affected]

    started = datetime.now(timezone.utc)
    out = DAILY / started.strftime("%Y%m%dT%H%M%SZ")
    out.mkdir(parents=True)
    runs = history()
    up = backend_up(args.base_url)
    token = os.environ.get("STOCKSAGE_ADMIN_TOKEN", "")
    eval_user = bool(os.environ.get("STOCKSAGE_EVAL_EMAIL") and os.environ.get("STOCKSAGE_EVAL_PASSWORD"))
    config = model_config(args.base_url, token) if up and token else None
    config_sha = hashlib.sha256(json.dumps(config, sort_keys=True).encode()).hexdigest()[:12] if config else None

    steps: dict[str, dict] = {}
    for name in selected:
        print(f"== {name} ...", flush=True)
        try:
            if name == "harness":
                steps[name] = step_harness(out, args.skip_build)
                continue
            if not up:
                steps[name] = {"status": "SKIPPED", "detail": f"backend not reachable at {args.base_url}"}
                continue
            if name in {"planner", "deep_evidence"} and not token:
                steps[name] = {"status": "SKIPPED", "detail": "STOCKSAGE_ADMIN_TOKEN is not set"}
                continue
            if name in {"deep_evidence", "ordinary"} and not eval_user:
                steps[name] = {"status": "SKIPPED", "detail": "STOCKSAGE_EVAL_EMAIL / STOCKSAGE_EVAL_PASSWORD not set"}
                continue
            if name == "deep_evidence" and args.ibkr_gateway and not ibkr_authenticated(args.ibkr_gateway):
                steps[name] = {"status": "SKIPPED", "detail": "IBKR session not authenticated; market evidence would be missing"}
                continue
            results = []
            repeats = args.repeat if name in REPEATED_LAYERS else 1
            for repeat in range(repeats):
                tag = f"-r{repeat + 1}" if repeats > 1 else ""
                if name == "planner":
                    base = (last_step(runs, "planner") or {}).get("output")
                    candidates = [Path(base) / f for f in ("planner.json", "planner-r1.json")] if base else []
                    baseline = next((path for path in candidates if path.exists()), None)
                    results.append(step_planner(out, args.base_url, token, baseline if repeat == 0 else None, tag))
                elif name == "rag":
                    results.append(step_rag(out, args.base_url, tag))
                elif name == "deep_evidence":
                    results.append(step_deep_evidence(out, args.base_url, args.ibkr_gateway, smoke=(tier == "daily"),
                                                      judged=(tier != "daily" and not args.no_judge), tag=tag))
                else:
                    results.append(step_ordinary(out, args.base_url, last_step(runs, "ordinary"),
                                                 judged=(tier != "daily" and not args.no_judge), tag=tag))
            steps[name] = aggregate(results)
        except (OSError, ValueError, KeyError, requests.RequestException, subprocess.TimeoutExpired) as error:
            steps[name] = {"status": "ERROR", "detail": f"{type(error).__name__}: {error}"[:600]}

    noise = json.loads(NOISE.read_text(encoding="utf-8")) if NOISE.exists() else {}
    for name, step in steps.items():
        if step.get("noise_sd"):
            noise[name] = {"sd": step["noise_sd"], "repeats": args.repeat, "measuredAt": started.isoformat(),
                           "configSha": config_sha, "gitSha": git_sha()}
    if any(step.get("noise_sd") for step in steps.values()):
        NOISE.write_text(json.dumps(noise, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    summary = {"schema": "daily_eval_v2", "startedAt": started.isoformat(), "gitSha": git_sha(), "tier": tier,
               "since": args.since, "repeat": args.repeat, "configSha": config_sha, "config": config,
               "output": str(out), "steps": steps, "uncovered": uncovered,
               "deltaFromPrevious": deltas(steps, runs),
               "deltaBaselines": {name: (last_step(runs, name) or {}).get("startedAt") for name in steps}}
    summary["staleDays"] = staleness(runs + [summary], datetime.now(timezone.utc))
    (out / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    with (DAILY / "history.jsonl").open("a", encoding="utf-8") as stream:
        stream.write(json.dumps(summary, ensure_ascii=False) + "\n")

    print(f"\nScorecard {summary['startedAt']} tier={tier} (git {summary['gitSha']}, config {config_sha or 'unknown'}) -> {out}")
    if config:
        routing = config.get("modelRouting") or {}
        print(f"  models: fast={routing.get('fast')} standard={routing.get('standard')} strong={routing.get('strong')}"
              f" thinkingBudget={routing.get('thinkingBudget')} maxOutputTokens={routing.get('maxOutputTokens')}")
    for name, step in steps.items():
        line = f"  {name:<14} {step['status']:<8}"
        if step.get("metrics"):
            line += " " + ", ".join(f"{k}={v:.3f}" if isinstance(v, float) else f"{k}={v}"
                                    for k, v in step["metrics"].items())
        print(line)
        for key in ("failed", "identity_changed", "newly_failing", "known_failing", "judge_flagged"):
            if step.get(key):
                print(f"                 {key}: {', '.join(step[key])}")
        if step.get("judge_error"):
            print(f"                 judge_error: {step['judge_error']}")
        if step.get("noise_sd"):
            print("                 noise sd: " + ", ".join(f"{k}={v:.4f}" for k, v in step["noise_sd"].items()))
        if step.get("detail"):
            print(f"                 {step['detail']}")
        if step.get("misrouted"):
            print(f"                 misrouted ({len(step['misrouted'])}): "
                  + ", ".join(f"{r['id']} {r['expected']}->{r['actual']}" for r in step["misrouted"][:8]))
        if step.get("worst_cases"):
            print(f"                 worst retrieval cases: {', '.join(step['worst_cases'])}")
    if summary["deltaFromPrevious"]:
        parts = []
        for key, value in summary["deltaFromPrevious"].items():
            layer, metric = key.split(".", 1)
            sd = ((noise.get(layer) or {}).get("sd") or {}).get(metric)
            parts.append(f"{key} {value:+}" + (" (within noise)" if sd is not None and abs(value) <= 2 * sd else ""))
        print("  changed vs the last run of each layer: " + ", ".join(parts))
    for item in uncovered:
        print(f"  NOT COVERED: {item}")
    stale = [f"{name} ({days}d)" if days is not None else f"{name} (never)"
             for name, days in summary["staleDays"].items() if days is None or days > STALE_DAYS]
    if stale:
        print(f"  STALE (> {STALE_DAYS} days without an evaluated run): {', '.join(stale)}")
    if any(key.startswith("judge_") for step in steps.values() for key in step.get("metrics") or {}):
        print("  NOTE: judge_* metrics come from an AI judge not yet calibrated on human labels (diagnostic only)")
    skipped = [name for name, step in steps.items() if step["status"] == "SKIPPED"]
    if skipped:
        print(f"  NOTE: skipped steps were not evaluated: {', '.join(skipped)}")
    return 1 if any(step["status"] in {"FAIL", "ERROR"} for step in steps.values()) else 0


if __name__ == "__main__":
    raise SystemExit(main())
