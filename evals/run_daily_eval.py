"""Scorecard runner: regression layers by tier, run-to-run noise, config identity and staleness.

  python evals/run_daily_eval.py                      daily tier (or: .\\init.ps1 -Mode eval)
  python evals/run_daily_eval.py --tier change        only the layers and cases affected by changes since HEAD
  python evals/run_daily_eval.py --since <git-sha>    the same against another revision
  python evals/run_daily_eval.py --case followup      only these ordinary/fundamentals_gold cases (repeatable)
  python evals/run_daily_eval.py --tier milestone     every layer with the full DEEP set
  python evals/run_daily_eval.py --repeat 2           run ordinary and deep_evidence twice and record noise

Layers
  harness        Offline DEEP completion policy on harness_golden_set (no services, no model).
  planner        LIVE_COORDINATOR routing on agent_golden_set: the production recognizer. Needs the
                 backend and STOCKSAGE_ADMIN_TOKEN. DETERMINISTIC mode only scores the keyword fallback.
  rag            Retrieval-only metrics on golden_set via /api/docs/search; no answer generation.
  deep_evidence  DEEP evidence stage only (request-level EVIDENCE_ONLY): completion, terminal safety,
                 usable evidence and loop stop reasons. Daily and change run 4 smoke cases, milestone runs
                 all of deep_react_cases. Needs the admin token, STOCKSAGE_EVAL_EMAIL/PASSWORD and an
                 authenticated IBKR session (market evidence would be missing otherwise).
  ordinary       Ordinary chat execution contract on ordinary_live_cases + ordinary_compare_cases.
                 Gates new regressions, required bug/happy-path cases and confirmed scope violations.
  fundamentals_gold  The VALIDATION split of the FY2025 fundamentals set through ordinary chat; answers are
                 checked against gold values and failures triaged (gold_triage.py: PROVIDER / DATA_GAP /
                 DETERMINISTIC / METHOD). HOLDOUT is never run here. Fails on newly failing cases.
  concurrency    Two accounts ask about different tickers concurrently; missing overlap/capture is
                 INCOMPLETE, missing second-account credentials is SKIPPED.

Tiers: daily = harness, planner, rag, deep_evidence (smoke), concurrency; milestone = daily + ordinary +
fundamentals_gold with the full DEEP set; change = the milestone layers affected by changes since HEAD (or --since),
with DEEP smoke cases. In change and milestone both answer layers are scored by the AI judge (run_evaluation_agent.py: ordinary
five dimensions, DEEP facet coverage). Judge metrics are prefixed judge_ and never gate a layer until the
judge is calibrated on human labels. A step whose prerequisites are missing is SKIPPED, never PASS.
Identity pins (dataset hash, case counts, schema/policy versions) are reported as IDENTITY_CHANGED.

--since maps changed paths (git diff plus untracked files) to layers through CHANGE_MAP, and the keys changed in
application.properties through PROPERTY_MAP; memory-only changes run just the ordinary conversations. Debate/report
changes, unmapped product code and frontend changes are printed as NOT COVERED.

--case runs only the named ordinary/fundamentals_gold cases. A conversation is selected and skipped as a unit (a named
turn brings its earlier turns); cases whose data source the preflight saw down are SKIPPED without a request and the
layer is INCOMPLETE. Runs of part of a layer keep per-case results but do not move metric baselines, noise or staleness.

--repeat N stores each metric's standard deviation in results/daily/noise.json; later runs mark a
change vs the last run of the same layer as within noise when |delta| <= 2 * sd and identities match.

Terminal distributions, first observed faults, resources, scope risks and numeric traceability are
reported separately. Missing capture stays unknown. Dependency-caused terminal failures are INCOMPLETE;
preflight failure alone never excuses a regression. Numeric checks and uncalibrated judges are diagnostic.
The normal run also exports persisted online traces when an admin token is available. --replay-results
re-scores saved results offline and does not append live history. --preflight-only makes no model calls.

Output: evals/results/daily/<timestamp>/ with each report and summary.json; one line per run is
appended to evals/results/daily/history.jsonl. Exit 1 for FAIL/ERROR; exit 2 for INCOMPLETE without failures.
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
import eval_observations
import eval_preflight
import numeric_traceability
import ordinary_answer_quality
from eval_risk_checks import assess_risks, run_concurrency_smoke
from rag_summary import DEFAULT_GATES as RAG_GATES
from run_deep_m0 import report_text, report_evidence_ids
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
LAYERS = ("harness", "planner", "rag", "deep_evidence", "ordinary", "fundamentals_gold", "concurrency")
FY2025 = HERE / "evolution" / "fundamentals-fy2025-expansion"
TIERS = {
    "daily": ("harness", "planner", "rag", "deep_evidence", "concurrency"),
    "change": LAYERS,  # narrowed to what changed since HEAD (or --since) in main()
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
# Conversation memory only reaches answers through earlier turns: changed_layers turns this marker into the
# ordinary layer restricted to its multi-turn cases, without fundamentals_gold or concurrency.
MULTI_TURN = ("ordinary_multi_turn",)
CHANGE_MAP = (
    # Model routing, thinking budget and shared model clients affect every answer layer
    # (application.properties is mapped per changed key through PROPERTY_MAP).
    (JAVA + "config/AgentConfig.java", ANSWER_LAYERS),
    (JAVA + "config/ThinkingModels.java", ANSWER_LAYERS),
    (JAVA + "agent/runtime/AgentRuntimeConfiguration.java", ANSWER_LAYERS),
    (JAVA + "agent/runtime/ModelInvocationAdvisor.java", ANSWER_LAYERS),
    (JAVA + "util/*", ANSWER_LAYERS),
    # Routing.
    (JAVA + "agent/intent/*", ("planner",)),
    (JAVA + "agent/Coordinator.java", ("planner", "ordinary")),
    (JAVA + "agent/RoutePlanCatalog.java", ("planner", "ordinary")),
    (JAVA + "agent/Routing*", ("planner",)),
    (JAVA + "agent/Plan*", ("planner", "ordinary")),
    (JAVA + "agent/ordinary/PlanStep.java", ("planner", "ordinary")),
    (JAVA + "agent/ModelTier.java", ("planner", "ordinary")),
    (JAVA + "config/AiConfig.java", ("planner", "rag", "ordinary")),
    (JAVA + "eval/PlannerEvalService.java", ("planner",)),
    (JAVA + "eval/PlannerEvalModels.java", ("planner",)),
    (JAVA + "agent/TickerResolutionService.java", ("planner",) + TOOLS),
    ("evals/agent_*", ("planner",)),
    ("evals/run_agent_eval.py", ("planner",)),
    # Retrieval and knowledge.
    (JAVA + "rag/*", ("rag", "ordinary")),
    (JAVA + "knowledge/*", ("rag", "ordinary")),
    (JAVA + "config/EmbeddingConfig.java", ("rag",)),
    (JAVA + "eval/RagEvalService.java", ("rag",)),
    (JAVA + "controller/RagEvalController.java", ("rag",)),
    (JAVA + "controller/DocumentController.java", ("rag",)),
    (JAVA + "eval/RagEvalModels.java", ("rag",)),
    (JAVA + "repository/VectorDocument*", ("rag",)),
    (JAVA + "repository/DocIndex*", ("rag",)),
    (JAVA + "repository/ContextualGist*", ("rag",)),
    ("evals/golden_set.jsonl", ("rag",)),
    ("evals/run_rag_eval.py", ("rag",)),
    ("evals/run_retrieval_eval.py", ("rag",)),
    ("evals/ingest_corpus.py", ("rag",)),
    # Ordinary route: analysts, planner, prompts, memory and FUNDAMENTALS methods.
    (JAVA + "agent/ordinary/Ordinary*", ("ordinary",)),
    (JAVA + "agent/analyst/*", ("ordinary",)),
    (JAVA + "agent/runtime/ModelCompletion.java", ("ordinary",)),
    (JAVA + "agent/runtime/ChatConcurrencyAdvisor.java", ("ordinary",)),
    (JAVA + "agent/ordinary/DataRequirement.java", ("ordinary",)),
    (JAVA + "agent/ordinary/ReadRequest.java", ("ordinary",)),
    (JAVA + "agent/ExecutionPlan.java", ("ordinary",)),
    (RES + "prompts/*", ("ordinary",)),
    (RES + "evolution/*", ("ordinary",)),
    (JAVA + "evolution/FundamentalsMethodRegistry.java", ("ordinary",)),
    (JAVA + "evolution/FundamentalsRuntimeIdentity.java", ("ordinary",)),
    (JAVA + "evolution/ApprovedMethodArtifact.java", ("ordinary",)),
    (JAVA + "evolution/AgentPolicyBundle.java", ("ordinary",)),
    (JAVA + "conversation/*", ("ordinary",)),
    (JAVA + "memory/*", MULTI_TURN),
    (JAVA + "harness/OrdinaryCompletionPolicy.java", ("ordinary",)),
    ("evals/ordinary_*", ("ordinary",)),
    ("evals/run_ordinary_live_eval.py", ("ordinary",)),
    ("evals/gold_triage.py", ("fundamentals_gold",)),
    ("evals/evolution_numeric_check.py", ("fundamentals_gold",)),
    ("evals/evolution/fundamentals-fy2025-expansion/*", ("fundamentals_gold",)),
    # Shared entry points and the tool layer used by both routes.
    (JAVA + "conversation/ChatService.java", TOOLS),
    (JAVA + "controller/ChatController.java", TOOLS),
    (JAVA + "conversation/ChatRequest.java", TOOLS),
    (JAVA + "agent/ordinary/ToolPrefetchService.java", TOOLS),
    (JAVA + "workbench/StockNewsService.java", TOOLS),
    (JAVA + "workbench/KLinePayloadMapper.java", TOOLS),
    (JAVA + "tool/*", TOOLS),
    (JAVA + "capability/*", TOOLS),
    (JAVA + "client/*", TOOLS),
    (JAVA + "ibkr/*", TOOLS),
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
    (JAVA + "research/deep/DeepEvidence*", ("deep_evidence",)),
    (JAVA + "research/deep/EvidenceObservationCompressor.java", ("deep_evidence",)),
    (JAVA + "research/deep/ResearchEvidenceSnapshotService.java", ("deep_evidence",)),
    (JAVA + "research/task/*", ("deep_evidence",)),
    (JAVA + "debate/ResearchDebateService.java", ("deep_evidence",)),
    (JAVA + "research/deep/DeepResearchPipeline.java", DEEP),
    (JAVA + "agent/runtime/ModelInvocationStore.java", DEEP),
    (JAVA + "research/deep/AnalysisState.java", DEEP),
    (JAVA + "agent/runtime/ModelInvocationContext.java", DEEP),
    (JAVA + "model/entity/ResearchTask*", ("deep_evidence",)),
    (JAVA + "repository/ResearchTask*", ("deep_evidence",)),
    (JAVA + "relation/*", ("deep_evidence",)),
    (JAVA + "config/ModelTokenBudgetProperties.java", DEEP),
    (JAVA + "config/ResearchHttpDeadlineConfiguration.java", DEEP),
    (JAVA + "config/AdminApiInterceptor.java", ("planner", "deep_evidence")),
    ("evals/deep_react_cases.jsonl", ("deep_evidence",)),
    ("evals/run_deep_m0.py", ("deep_evidence",)),
    # DEEP debate and report (no runnable layer yet).
    (JAVA + "debate/*", ("deep_report",)),
    (JAVA + "research/report/*", ("deep_report",)),
    # Known product code without a regression layer.
    (JAVA + "controller/*", ()),
    (JAVA + "identity/*", ()),
    (JAVA + "security/*", ()),
    (JAVA + "trace/*", ("concurrency",)),
    (JAVA + "exception/*", ()),
    (JAVA + "repository/*", ()),
    (JAVA + "model/*", ()),
    (JAVA + "*package-info.java", ()),
    (JAVA + "*Application.java", ()),
    (JAVA + "agent/runtime/AgentStep.java", ()),
    (JAVA + "research/deep/AnalysisHorizon.java", ()),
    (JAVA + "workbench/Workbench*", ()),
    (JAVA + "workbench/OfflineDemoSampleService.java", ()),
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
# Changed application.properties keys, first matching prefix wins. A key matching nothing (model routing, thinking
# budget, model clients, vector store) affects every answer layer; a comment-only edit affects none.
PROPERTY_MAP = (
    ("stocksage.chat.prompt.history-max-chars", MULTI_TURN),
    ("stocksage.memory.", MULTI_TURN),
    ("stocksage.chat.prompt.", ("ordinary",)),
    ("stocksage.chat.tool-prefetch.", TOOLS),
    ("stocksage.chat.search-ingest.", ("rag", "ordinary")),
    ("stocksage.chat.title-generation.", ()),
    ("stocksage.agent.intent.", ("planner", "ordinary")),
    ("stocksage.agent.routing.", ("planner", "ordinary")),
    ("stocksage.agent.ordinary.", ("ordinary",)),
    ("stocksage.agent.deep-react.", ("deep_evidence",)),
    ("stocksage.agent.debate.", ("deep_report",)),
    ("stocksage.research-memory.", ("ordinary",)),
    ("stocksage.rag.", ("rag", "ordinary")),
    ("stocksage.ibkr.", TOOLS),
    ("stocksage.data-service.", TOOLS),
    ("stocksage.admin.", ()),
    ("stocksage.demo.", ()),
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


def preflight_provider(tool: str, target: str) -> str | None:
    """The preflight probe that covers a tool's upstream source (data-service routes HK to AKShare, A-shares to BaoStock)."""
    target = target.upper()
    market = "HK" if target.endswith(".HK") else "CN" if target.startswith(("SH.", "SZ.", "BJ.")) else "US"
    if tool == "getIbkrHistoricalBars":
        return "ibkr"
    if tool == "getFilingText":
        return "sec"
    if tool in {"getFinancialReports", "getStructuredFinancials", "getStockKLine"}:
        return {"HK": "akshare", "CN": "baostock", "US": "sec" if tool != "getStockKLine" else None}[market]
    return None


def unavailable_sources(preflight: dict | None) -> set[str]:
    """Probes that saw their source down; HTTP 5xx from the local data-service may be our own code, so it is not counted."""
    providers = (preflight or {}).get("providers") or {}
    return {name for name, row in providers.items()
            if row.get("status") == "UNAVAILABLE" and not str(row.get("reason", "")).startswith("HTTP_5")}


def environment_confirmed(details: dict, preflight: dict | None) -> bool:
    """A trace-observed dependency block exempts a case only if this run's probe saw the same source down.

    HTTP 5xx from the local data-service is not accepted: it is as likely our own code as the upstream.
    """
    down = unavailable_sources(preflight)
    service_down = "data_service" in down
    sources = [preflight_provider(tool, target) for tool, target in details.get("sources") or []]
    return bool(sources) and all(source and (source in down or service_down and source != "ibkr") for source in sources)


def case_sources(row: dict) -> set[str]:
    """Sources a case cannot be answered without: its expected tool calls, and the market source of a HK/A-share target."""
    sources = {preflight_provider(call.get("tool", ""), str((call.get("args") or [""])[0])) for call in row.get("calls") or []}
    target = str(row.get("targets_single") or "")
    if preflight_provider("getFinancialReports", target) in {"akshare", "baostock"}:
        sources.add(preflight_provider("getFinancialReports", target))
    return {source for source in sources if source}


def select_cases(rows: list[dict], scope: str, case_ids: set[str] | None,
                 preflight: dict | None) -> tuple[set[str], dict[str, str]]:
    """Ids to run, and ids skipped because this run's probe saw a source they need down.

    A conversation is selected and skipped as a unit: a named turn brings its earlier turns, and one turn's
    missing source skips the whole conversation. Scope "multi_turn" keeps only conversations.
    """
    groups: dict[str, list[dict]] = {}
    for row in rows:
        groups.setdefault(row.get("conversation") or row["id"], []).append(row)
    down = unavailable_sources(preflight)
    if "data_service" in down:
        down |= {"akshare", "baostock", "sec"}
    selected, skipped = set(), {}
    for turns in groups.values():
        if scope == "multi_turn" and not turns[0].get("conversation"):
            continue
        if case_ids is not None:
            named = [index for index, row in enumerate(turns) if row["id"] in case_ids]
            if not named:
                continue
            turns = turns[:named[-1] + 1]
        missing = sorted(set().union(*(case_sources(row) for row in turns)) & down)
        if missing:
            skipped.update({row["id"]: "PREFLIGHT_UNAVAILABLE:" + ",".join(missing) for row in turns})
        else:
            selected.update(row["id"] for row in turns)
    return selected, skipped


def full_scope(step: dict) -> bool:
    """Only a run of every case of a layer may move its metric baseline, noise or staleness."""
    return step.get("case_scope", "all") == "all" and not step.get("skipped_cases")


def case_diagnostics(rows: list[dict], gold: list[dict] | None = None, preflight: dict | None = None) -> dict:
    observations = eval_observations.summarize_cases(rows, gold)
    details, risks, numeric = {}, [], []
    risk_checked, risk_no_data = 0, 0
    gold_by_id = {row["id"]: row for row in gold or []}
    blocked, suspected, undetermined = [], [], []
    for row in rows:
        trace = row.get("trace") or {}
        definition = row.get("case_definition") or row
        answer = row.get("answer") or report_text(row.get("report") or {}, row.get("evidence_items"))
        evidence = "\n\n".join(str((step.get("attributes") or {}).get("evidenceContext") or "")
                               for step in eval_observations.trace_steps(trace)
                               if (step.get("attributes") or {}).get("kind") == "answer-context"
                               and (step.get("attributes") or {}).get("evidenceCaptureComplete") is True)
        snapshot = row.get("evidence_snapshot") or {}
        if snapshot:
            evidence = "\n\n".join(str(snapshot.get(key) or "") for key in ("marketReport", "fundamentalsReport", "newsReport"))
        traceability = numeric_traceability.check(answer, evidence)
        risk = assess_risks(definition, trace, answer, snapshot or None,
                            report_evidence_ids(row.get("report") or {}, row.get("evidence_items")))
        risk_checked += risk.get("checked_count", 0)
        risk_no_data += risk.get("no_data_count", 0)
        observed = eval_observations.observe_case(row, gold_by_id.get(row["id"]))
        details[row["id"]] = {**observed, "numeric_traceability": traceability, "risk_checks": risk}
        risks.extend({"id": row["id"], **item} for item in risk.get("violations", []))
        if isinstance(traceability.get("traceable_rate"), (float, int)):
            numeric.append(traceability["traceable_rate"])
        # Only the terminal unmet requirements can establish a dependency-caused block, and only a
        # matching probe failure in this run confirms it; otherwise it is reported, not exempted.
        if observed.get("environment_blocked"):
            confirmed = environment_confirmed(observed["environment_blocked_details"], preflight)
            (blocked if confirmed else suspected).append(row["id"])
        # A check that could not be decided (e.g. the source returned nothing to inspect) is unknown, not a failure.
        checks = row.get("checks") or {}
        if row.get("checks_no_data") and not any(value is False for value in checks.values()):
            undetermined.append(row["id"])
    metrics = {**observations["metrics"], "risk_violation_count": len(risks),
               "numeric_traceability_scored_cases": len(numeric),
               "risk_checked_count": risk_checked, "risk_no_data_count": risk_no_data}
    if numeric:
        metrics["numeric_traceability_mean"] = statistics.fmean(numeric)
    return {"metrics": metrics, "observations": observations, "case_diagnostics": details,
            "risk_violations": risks, "environment_blocked": sorted(blocked),
            "environment_suspected": sorted(suspected), "undetermined_checks": sorted(undetermined)}


def regression_result(rows: list[dict], passed: dict, previous_step: dict | None, diagnostics: dict) -> dict:
    previous = (previous_step or {}).get("cases") or {}
    excluded = set(diagnostics["environment_blocked"]) | set(diagnostics.get("undetermined_checks") or [])
    newly = sorted(key for key, ok in passed.items() if not ok and previous.get(key) and key not in excluded)
    required = {row["id"] for row in rows if any((row.get("case_definition") or {}).get(key)
                                                for key in ("regression", "must_pass"))}
    required_failed = sorted(key for key in required if not passed.get(key) and key not in excluded)
    failures = (["newly_failing_cases"] if newly else []) + (["required_regression_cases"] if required_failed else [])
    if diagnostics["risk_violations"]:
        failures.append("risk_redline")
    status = "FAIL" if failures else "INCOMPLETE" if excluded else "PASS"
    return {"status": status, "failed": failures, "newly_failing": newly,
            "known_failing": sorted(key for key, ok in passed.items() if not ok and key not in newly and key not in excluded),
            "required_failed": required_failed, "regression_cases": {key: passed.get(key) for key in sorted(required)},
            "cases": passed, "environment_blocked": diagnostics["environment_blocked"],
            "undetermined_checks": diagnostics.get("undetermined_checks") or [], "gate_scope": "NO_REGRESSION_AND_REQUIRED_CASES"}


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


def judge(rubric: str, report: Path, output: Path, case_count: int, excluded_ids: set[str] | None = None) -> dict:
    """AI judge on a layer's saved results. judge_* metrics are diagnostic and never gate the layer."""
    done = run_python(["run_evaluation_agent.py", "--rubric", rubric, "--input", str(report),
                       "--output", str(output), *JUDGE_ARGS], timeout=240 * case_count + 300)
    if not output.exists():
        return {"judge_error": (done.stdout + done.stderr).strip()[-300:]}
    payload = json.loads(output.read_text(encoding="utf-8"))
    eligible = [row for row in payload["reviews"] if row["id"] not in (excluded_ids or set())]
    reviews = [row for row in eligible if row.get("repeat", 1) == 1]
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
        summary = ordinary_answer_quality.summarize_diagnostics(reviews)
        metrics["judge_answer_pass_rate"] = sum(row["status"] == "PASS" for row in valid) / len(valid) if valid else None
        flagged = sorted(row["id"] for row in valid if row["status"] == "FAIL")
    repeated = (deep.summarize if rubric == "deep_facets" else ordinary_answer_quality.summarize_diagnostics)(eligible)
    for key in ("repeat_consistency_rate", "repeat_exact_agreement", "repeat_pairs", "repeat_eligible_case_count"):
        if repeated.get(key) is not None:
            metrics["judge_" + key] = repeated[key]
    for dimension, values in summary.get("dimensions", {}).items():
        for key in ("no_data_rate", "not_applicable_rate", "technical_invalid_rate"):
            if values.get(key) is not None:
                metrics[f"judge_{dimension}_{key}"] = values[key]
    return {"judge_metrics": {key: value for key, value in metrics.items() if value is not None},
            "judge_flagged": flagged, "judge_output": str(output), "judge_diagnostics": summary,
            "judge_schema": payload.get("schema"), "judge_rubric": payload.get("rubric"),
            "judge_cost": payload.get("cost"), "judge_rejudging": payload.get("rejudging"),
            "judge_repeat_diagnostics": repeated}


def step_deep_evidence(out: Path, base_url: str, gateway: str, smoke: bool, judged: bool, tag: str = "",
                       include_challenges: bool = False, preflight: dict | None = None) -> dict:
    cases = [json.loads(line) for line in (HERE / "deep_react_cases.jsonl").read_text(encoding="utf-8").splitlines()
             if line.strip()]
    if not include_challenges:
        cases = [case for case in cases if case.get("suite") != "challenge"]
    if smoke:
        cases = [case for case in cases if case["id"] in DEEP_SMOKE_IDS]
    case_file = out / f"deep-evidence-cases{tag}.jsonl"
    case_file.write_text("".join(json.dumps(case, ensure_ascii=False) + "\n" for case in cases), encoding="utf-8")
    report = out / f"deep-evidence{tag}.json"
    command = ["run_deep_m0.py", "--arm", "SCORECARD", "--cases", str(case_file), "--output", str(report),
               "--base-url", base_url, "--evidence-only", "--ibkr-gateway", gateway]
    if include_challenges:
        command.append("--include-challenges")
    done = run_python(command,
                      timeout=300 * len(cases) + 300)
    if not report.exists():
        return {"status": "ERROR", "detail": (done.stdout + done.stderr).strip()[-600:]}
    all_rows = json.loads(report.read_text(encoding="utf-8"))["cases"]
    challenge_ids = {case["id"] for case in cases if case.get("gate_eligible") is False}
    rows = [row for row in all_rows if row["id"] not in challenge_ids]
    diagnostics = case_diagnostics(rows, preflight=preflight)
    challenge_diagnostics = case_diagnostics([row for row in all_rows if row["id"] in challenge_ids]) if challenge_ids else None
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
    total = len(cases) - len(challenge_ids)
    metrics = {"completion_rate": completed / total, "safe_terminal_rate": safe / total,
               "loop_rejected": len(rejected), "mean_usable_evidence": statistics.fmean(evidence) if evidence else 0.0,
               "mean_wall_seconds": statistics.fmean(seconds) if seconds else 0.0}
    failed = [name for name, bad in (("completion_rate", metrics["completion_rate"] < 1),
                                     ("safe_terminal_rate", metrics["safe_terminal_rate"] < 1),
                                     ("loop_rejected", metrics["loop_rejected"] > 0)) if bad]
    environmental = set(diagnostics["environment_blocked"])
    gate_rows = [row for row in rows if row["id"] not in environmental]
    if environmental:
        failed = [name for name in failed if name not in {"completion_rate", "safe_terminal_rate"}]
        if any(not per_case[row["id"]]["ok"] for row in gate_rows):
            failed.append("completion_rate")
        if any((row.get("run") or {}).get("unsafe_reasons") for row in gate_rows):
            failed.append("safe_terminal_rate")
    if diagnostics["risk_violations"]:
        failed.append("risk_redline")
    metrics.update(diagnostics.pop("metrics"))
    verdict = judge("deep_facets", report, out / f"deep-facet-judge{tag}.json", len(cases), challenge_ids) if judged else {}
    metrics.update(verdict.pop("judge_metrics", {}))
    return {"status": "FAIL" if failed else "INCOMPLETE" if environmental else "PASS", "failed": failed, "identity_changed": [], "metrics": metrics,
            "case_count": total, "smoke": smoke, "cases": per_case, "challenge_ids": sorted(challenge_ids),
            "challenge_diagnostics": challenge_diagnostics,
            "dataset_sha256": hashlib.sha256(case_file.read_bytes()).hexdigest(),
            **diagnostics, **verdict}


def ordinary_lines() -> list[str]:
    # The scorecard measures the production configuration. cmp-* cases need multi-target fan-out, which is
    # off by default (10-12), so they would all route DIRECT and fail; they belong to multi-target experiments.
    return [line for name in ("ordinary_live_cases.jsonl", "ordinary_compare_cases.jsonl")
            for line in (HERE / name).read_text(encoding="utf-8").splitlines()
            if line.strip() and not json.loads(line)["id"].startswith("cmp-")]


def gold_rows() -> list[dict]:
    cases = [json.loads(line) for line in (FY2025 / "cases.jsonl").read_text(encoding="utf-8").splitlines() if line.strip()]
    # Every outcome is allowed: this layer scores the answer against gold, the execution contract is the ordinary layer's.
    return [{"id": case["caseId"], "question": case["query"], "route": "FUNDAMENTALS",
             "targets_single": case["ticker"],
             "outcomes": ["COMPLETED", "DEGRADED", "FAILED", "BLOCKED"]} for case in cases if case["split"] == "VALIDATION"]


def step_ordinary(out: Path, base_url: str, previous_step: dict | None, judged: bool, tag: str = "",
                  preflight: dict | None = None, scope: str = "all", case_ids: set[str] | None = None) -> dict:
    case_file = out / f"ordinary-cases{tag}.jsonl"
    lines = ordinary_lines()
    chosen, skipped = select_cases([json.loads(line) for line in lines], scope, case_ids, preflight)
    if not chosen:
        return {"status": "SKIPPED", "detail": f"no ordinary case to run (scope {scope})",
                "case_scope": scope, "skipped_cases": skipped}
    # Original lines keep the dataset hash of a full run unchanged.
    case_file.write_text("\n".join(line for line in lines if json.loads(line)["id"] in chosen) + "\n", encoding="utf-8")
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
    diagnostics = case_diagnostics(payload["cases"], preflight=preflight)
    gate = regression_result(payload["cases"], cases, previous_step, diagnostics)
    if skipped and gate["status"] == "PASS":
        gate["status"] = "INCOMPLETE"
    metrics = {"execution_pass_rate": payload.get("execution_contract_pass_rate") or 0.0,
               "p95_latency_ms": latencies[int(0.95 * (len(latencies) - 1))] if latencies else 0}
    verdict = judge("ordinary", report, out / f"ordinary-judge{tag}.json", len(cases)) if judged else {}
    metrics.update(diagnostics.pop("metrics"))
    metrics.update(verdict.pop("judge_metrics", {}))
    return {**gate, "identity_changed": [], "metrics": metrics, "case_count": len(cases),
            "case_scope": scope, "skipped_cases": skipped,
            "dataset_sha256": payload.get("dataset_sha256"), **diagnostics, **verdict}


def step_fundamentals_gold(out: Path, base_url: str, previous_step: dict | None, preflight: dict | None = None,
                           scope: str = "all", case_ids: set[str] | None = None) -> dict:
    rows = gold_rows()
    chosen, skipped = select_cases(rows, scope, case_ids, preflight)
    if not chosen:
        return {"status": "SKIPPED", "detail": f"no fundamentals gold case to run (scope {scope})",
                "case_scope": scope, "skipped_cases": skipped}
    rows = [row for row in rows if row["id"] in chosen]
    case_file = out / "fundamentals-gold-cases.jsonl"
    case_file.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8")
    report, triage_file = out / "fundamentals-gold.json", out / "fundamentals-gold-triage.json"
    done = run_python(["run_ordinary_live_eval.py", "--base-url", base_url, "--cases", str(case_file),
                       "--output", str(report), "--timeout-seconds", "400", "--label", "scorecard"],
                      timeout=400 * len(rows) + 300)
    if not report.exists() or json.loads(report.read_text(encoding="utf-8")).get("status") == "BLOCKED":
        return {"status": "ERROR", "detail": (done.stdout + done.stderr).strip()[-600:]}
    run_python(["gold_triage.py", "--report", str(report), "--gold", str(FY2025 / "gold.jsonl"),
                "--output", str(triage_file)], timeout=120)
    triage = json.loads(triage_file.read_text(encoding="utf-8"))
    summary = triage["summary"]
    passed = {row["id"]: row.get("status") == "PASS" for row in triage["cases"]}
    payload = json.loads(report.read_text(encoding="utf-8"))
    diagnostics = case_diagnostics(payload["cases"], triage["cases"], preflight)
    gate = regression_result(payload["cases"], passed, previous_step, diagnostics)
    if skipped and gate["status"] == "PASS":
        gate["status"] = "INCOMPLETE"
    metrics = {"gold_pass_rate": summary["pass_rate"], "gold_fact_rate": summary["gold_fact_rate"],
               **{"fail_" + kind.lower(): count for kind, count in summary["failure_types"].items()}}
    metrics.update(diagnostics.pop("metrics"))
    return {**gate,
            "identity_changed": [], "metrics": metrics, "case_count": len(rows), "cases": passed,
            "case_scope": scope, "skipped_cases": skipped,
            "dataset_sha256": hashlib.sha256(case_file.read_bytes()).hexdigest(),
            "triage": {row["id"]: {key: row.get(key) for key in ("failureType", "missingInAnswer", "missingInEvidence", "issues")}
                       for row in triage["cases"] if row.get("status") == "FAIL"},
            "triage_output": str(triage_file), **diagnostics}


def history() -> list[dict]:
    path = DAILY / "history.jsonl"
    if not path.exists():
        return []
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def last_step(runs: list[dict], name: str) -> dict | None:
    """Most recent evaluated (PASS/FAIL) full run of a layer as the baseline, with per-case results of every run."""
    latest, cases = None, {}
    for run in reversed(runs):
        step = (run.get("steps") or {}).get(name)
        if step and step.get("status") in {"PASS", "FAIL", "INCOMPLETE"}:
            if latest is None and full_scope(step):
                latest = {**step, "output": run.get("output"), "startedAt": run.get("startedAt")}
            excluded = set(step.get("environment_blocked") or []) | set(step.get("undetermined_checks") or [])
            # Only per-case pass maps carry over; the concurrency layer stores its raw request records as a list.
            step_cases = step.get("cases") if isinstance(step.get("cases"), dict) else {}
            for key, result in step_cases.items():
                if key not in cases and key not in excluded:
                    cases[key] = result
    return {**(latest or {}), "cases": cases} if latest or cases else None


def staleness(runs: list[dict], now: datetime) -> dict:
    result = {}
    for name in LAYERS:
        for run in reversed(runs):
            step = (run.get("steps") or {}).get(name) or {}
            if step.get("status") in {"PASS", "FAIL"} and full_scope(step):
                result[name] = round((now - datetime.fromisoformat(run["startedAt"])).total_seconds() / 86400, 1)
                break
        else:
            result[name] = None
    return result


def deltas(current: dict, runs: list[dict]) -> dict:
    """Metric changes vs the most recent evaluated run of the same layer (runs may cover other layers)."""
    result = {}
    for name, step in current.items():
        if not full_scope(step):
            continue
        before = last_step(runs, name) or {}
        for metric, value in (step.get("metrics") or {}).items():
            old = (before.get("metrics") or {}).get(metric)
            if isinstance(value, (int, float)) and isinstance(old, (int, float)) and value != old:
                result[f"{name}.{metric}"] = round(value - old, 4)
    return result


def aggregate(results: list[dict]) -> dict:
    """Combine repeated runs: mean metrics, per-metric sd, the worst status and the first run's details."""
    order = {"ERROR": 4, "FAIL": 3, "INCOMPLETE": 2, "NO_DATA": 2, "SKIPPED": 1, "PASS": 0}
    combined = {**results[0], "status": max((r["status"] for r in results), key=lambda s: order.get(s, 0))}
    runs = [r.get("metrics") or {} for r in results if r.get("metrics")]
    if len(runs) >= 2:
        names = [m for m in runs[0] if all(isinstance(run.get(m), (int, float)) for run in runs)]
        combined["metrics"] = {m: statistics.fmean(run[m] for run in runs) for m in names}
        combined["noise_sd"] = {m: statistics.stdev(run[m] for run in runs) for m in names}
        combined["repeats"] = runs
    # Per-case stability across repeats: pass^k (every trial passes) is what a user relying on one answer
    # experiences; pass@k (any trial passes) shows capability. Added after noise so they are not "noise" metrics.
    trials = [r.get("cases") for r in results if isinstance(r.get("cases"), dict)]
    if len(trials) >= 2:
        def passed(value):
            return value.get("ok") if isinstance(value, dict) else value
        ids = sorted(set.intersection(*(set(t) for t in trials)))
        outcomes = {case: [bool(passed(t[case])) for t in trials] for case in ids}
        k = len(trials)
        if ids:
            combined.setdefault("metrics", {}).update({
                f"pass^{k}_rate": sum(all(v) for v in outcomes.values()) / len(ids),
                f"pass@{k}_rate": sum(any(v) for v in outcomes.values()) / len(ids),
                "per_trial_pass_rate": sum(sum(v) for v in outcomes.values()) / (len(ids) * k)})
            combined["flaky_cases"] = sorted(case for case, v in outcomes.items() if any(v) and not all(v))
    return combined


def replay_results(paths: list[Path], out: Path) -> dict:
    """Re-score saved outputs without model calls or changing the live regression history."""
    reports = []
    for path in paths:
        payload = json.loads(path.read_text(encoding="utf-8"))
        rows = payload.get("cases")
        if not isinstance(rows, list) or any(not isinstance(row, dict) or "id" not in row for row in rows):
            raise ValueError(f"Expected a saved ordinary/DEEP cases report: {path}")
        if len({row["id"] for row in rows}) != len(rows):
            raise ValueError(f"Duplicate case IDs in {path}")
        if not any("trace" in row or "run" in row for row in rows):
            raise ValueError(f"No captured trace or run in {path}")
        gold_path = path.with_name(path.stem + "-triage.json")
        gold = json.loads(gold_path.read_text(encoding="utf-8"))["cases"] if gold_path.exists() else None
        reports.append({"input": str(path.resolve()), "input_sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                        "gold_source": str(gold_path.resolve()) if gold else None,
                        "gold_source_sha256": hashlib.sha256(gold_path.read_bytes()).hexdigest() if gold else None,
                        "case_count": len(rows), **case_diagnostics(rows, gold)})
    result = {"schema": "eval_observation_replay_v1", "status": "DIAGNOSTIC", "model_calls": 0,
              "gitSha": git_sha(), "reports": reports,
              "evaluator_sha256": {name: hashlib.sha256((HERE / name).read_bytes()).hexdigest() for name in (
                  "run_daily_eval.py", "eval_observations.py", "eval_risk_checks.py", "numeric_traceability.py",
                  "run_deep_m0.py", "evolution_numeric_check.py")},
              "scope": "Saved outputs only; historical gold labels retained. No live coverage or calibration claim."}
    (out / "summary.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return result


def step_concurrency(out: Path, base_url: str) -> dict:
    report = run_concurrency_smoke(base_url)
    path = out / "concurrency.json"
    path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    # Raw request records (events, traces) stay in concurrency.json; the scorecard step and history keep a summary.
    summary = {key: value for key, value in report.items() if key != "cases"}
    summary["requests"] = [{key: case.get(key) for key in ("ticker", "question", "expected_user_id")}
                           for case in report.get("cases", [])]
    return {**summary, "output": str(path),
            "status": "INCOMPLETE" if report["status"] == "NO_DATA" else report["status"]}


def online_report(out: Path, base_url: str) -> dict:
    output = out / "online-trace-review.json"
    try:
        done = run_python(["online_trace_review.py", "--base-url", base_url, "--output", str(output)], timeout=180)
    except (OSError, subprocess.TimeoutExpired) as error:
        return {"status": "ERROR", "detail": f"Trace export did not complete: {type(error).__name__}"}
    if done.returncode or not output.exists():
        return {"status": "ERROR", "detail": (done.stdout + done.stderr).strip()[-600:]}
    payload = json.loads(output.read_text(encoding="utf-8"))
    return {"status": "DIAGNOSTIC", "output": str(output), "sampling": payload["sampling"],
            "changes": [row for row in payload["changes"] if row.get("review_change") or row.get("kind")]}


def changed_properties(since: str, path: str) -> list[str]:
    """Keys added, removed or edited in a .properties file between <since> and the working tree."""
    diff = subprocess.run(["git", "-c", "core.quotepath=off", "diff", "-U0", since, "--", path], cwd=ROOT,
                          capture_output=True, text=True, encoding="utf-8", check=True)
    keys = set()
    for line in diff.stdout.splitlines():
        text = line[1:].strip()
        if line.startswith(("+", "-")) and not line.startswith(("+++", "---")) and "=" in text \
                and not text.startswith(("#", "!")):
            keys.add(text.split("=", 1)[0].strip())
    return sorted(keys)


def changed_layers(since: str) -> tuple[set[str], str | None, list[str], list[str]]:
    """Layers affected by changes between <since> and the working tree, the ordinary case scope, plus unmapped
    and uncovered paths."""
    git = ["git", "-c", "core.quotepath=off"]
    diff = subprocess.run(git + ["diff", "--name-only", since], cwd=ROOT, capture_output=True, text=True,
                          encoding="utf-8", check=True)
    untracked = subprocess.run(git + ["ls-files", "--others", "--exclude-standard"], cwd=ROOT,
                               capture_output=True, text=True, encoding="utf-8", check=True)
    paths = sorted({p for p in (diff.stdout + untracked.stdout).splitlines() if p.strip()})
    layers, unmapped = set(), []
    for path in paths:
        if path == RES + "application.properties":
            matched = [next((mapped for prefix, mapped in PROPERTY_MAP if key.startswith(prefix)), ANSWER_LAYERS)
                       for key in changed_properties(since, path)] or [()]
        else:
            matched = [mapped for pattern, mapped in CHANGE_MAP if fnmatch.fnmatchcase(path, pattern)]
        layers.update(layer for mapped in matched for layer in mapped)
        # Only product code must be mapped; eval tooling and experiment data may change freely.
        if not matched and path.startswith(("stocksage-backend/src/main/", "stocksage-data-service/")):
            unmapped.append(path)
    # Everything that can change a single-turn ordinary answer can change a fundamentals gold answer; a
    # memory-only change runs just the ordinary conversations.
    scope = "all" if "ordinary" in layers else "multi_turn" if MULTI_TURN[0] in layers else None
    layers.discard(MULTI_TURN[0])
    if scope:
        layers.add("ordinary")
    if scope == "all":
        layers.update(("fundamentals_gold", "concurrency"))
    return layers, scope, unmapped, paths


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
    parser.add_argument("--case", action="append",
                        help="Run only these ordinary/fundamentals_gold case ids (repeatable); earlier turns are included")
    parser.add_argument("--repeat", type=int, default=1, help="Run live layers N times and record noise")
    parser.add_argument("--ibkr-gateway", default="https://localhost:5000")
    parser.add_argument("--skip-build", action="store_true", help="Reuse the compiled backend for the Harness step")
    parser.add_argument("--no-judge", action="store_true", help="Skip the AI judge in the change/milestone tiers")
    parser.add_argument("--rejudge-count", type=int, default=0, help="Extra sampled judge calls per quality layer (default: zero)")
    parser.add_argument("--include-challenges", action="store_true", help="Include DEEP challenge cases as diagnostics in change/milestone")
    parser.add_argument("--output-dir", type=Path, help="New output directory; must not already exist")
    parser.add_argument("--replay-results", type=Path, action="append", help="Re-score saved ordinary/DEEP outputs offline; repeatable, no live history write")
    parser.add_argument("--preflight-only", action="store_true", help="Only probe dependencies; no model generation or live eval")
    parser.add_argument("--data-service-url", default=os.environ.get("DATA_SERVICE_URL", "http://localhost:8001"))
    parser.add_argument("--ollama-url", default=os.environ.get("STOCKSAGE_EMBEDDING_OLLAMA_BASE_URL")
                        or os.environ.get("OLLAMA_BASE_URL", "http://localhost:11434"))
    parser.add_argument("--embedding-model", default=os.environ.get("OLLAMA_EMBEDDING_MODEL", "bge-m3"))
    parser.add_argument("--probe-search", action="store_true", help="Allow the preflight search request to consume provider credit")
    parser.add_argument("--online-review", action="store_true",
                        help="Export 14 days of persisted traces for the weekly review (default: change/milestone tiers without --only)")
    args = parser.parse_args()
    if args.repeat < 1:
        parser.error("--repeat must be positive")
    if args.rejudge_count < 0:
        parser.error("--rejudge-count must be nonnegative")
    if args.include_challenges and args.tier == "daily" and not args.since:
        parser.error("--include-challenges requires --tier change or milestone")
    if args.rejudge_count:
        JUDGE_ARGS.extend(["--rejudge-count", str(args.rejudge_count)])
    case_ids = set(args.case) if args.case else None
    if case_ids:
        pools = {"ordinary": {json.loads(line)["id"] for line in ordinary_lines()},
                 "fundamentals_gold": {row["id"] for row in gold_rows()}}
        unknown = sorted(case_ids - set().union(*pools.values()))
        if unknown:
            parser.error("unknown case ids: " + ", ".join(unknown))

    started = datetime.now(timezone.utc)
    out = args.output_dir or DAILY / started.strftime("%Y%m%dT%H%M%SZ")
    out.mkdir(parents=True)
    if args.replay_results:
        result = replay_results(args.replay_results, out)
        print(json.dumps({"status": result["status"], "model_calls": 0, "output": str(out / "summary.json")}, ensure_ascii=False))
        return 0
    if args.preflight_only:
        result = eval_preflight.run(data_url=args.data_service_url, ollama_url=args.ollama_url,
                                   embedding_model=args.embedding_model, ibkr_gateway=args.ibkr_gateway,
                                   probe_search=args.probe_search)
        (out / "preflight.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({"output": str(out / "preflight.json"), "providers": result["providers"]}, ensure_ascii=False))
        return 0

    tier = "change" if args.since and args.tier == "daily" else args.tier
    # change compares the working tree with the last commit unless told otherwise; only milestone runs everything.
    since = None if case_ids else args.since or ("HEAD" if tier == "change" and not args.only else None)
    selected = list(args.only or TIERS[tier])
    uncovered: list[str] = []
    scope = "all"
    if case_ids:
        selected, scope = [name for name, ids in pools.items() if ids & case_ids], "cases"
    elif since:
        affected, ordinary_scope, unmapped, paths = changed_layers(since)
        print(f"== {len(paths)} changed paths since {since}; affected layers: {', '.join(sorted(affected)) or 'none'}"
              + (" (ordinary: multi-turn cases only)" if ordinary_scope == "multi_turn" else ""))
        if "deep_report" in affected:
            uncovered.append("deep_report (debate/report quality is not a runnable layer yet)")
        if unmapped:
            uncovered.append("unmapped paths: " + ", ".join(unmapped[:12]) + (" ..." if len(unmapped) > 12 else ""))
        if any(path.startswith("stocksage-frontend/") for path in paths):
            uncovered.append("frontend changes (check the rendered page in a browser)")
        selected = [layer for layer in selected if layer in affected]
        scope = ordinary_scope or "all"

    runs = history()
    up = backend_up(args.base_url)
    token = os.environ.get("STOCKSAGE_ADMIN_TOKEN", "")
    eval_user = bool(os.environ.get("STOCKSAGE_EVAL_EMAIL") and os.environ.get("STOCKSAGE_EVAL_PASSWORD"))
    config = model_config(args.base_url, token) if up and token else None
    config_sha = hashlib.sha256(json.dumps(config, sort_keys=True).encode()).hexdigest()[:12] if config else None

    preflight = None
    if any(name != "harness" for name in selected):
        print("== dependency probes (no model generation; endpoint scope recorded) ...", flush=True)
        preflight = eval_preflight.run(data_url=args.data_service_url, ollama_url=args.ollama_url,
                                      embedding_model=args.embedding_model, ibkr_gateway=args.ibkr_gateway,
                                      probe_search=args.probe_search)
        (out / "preflight.json").write_text(json.dumps(preflight, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

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
            if name in {"deep_evidence", "ordinary", "fundamentals_gold", "concurrency"} and not eval_user:
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
                elif name == "fundamentals_gold":
                    results.append(step_fundamentals_gold(out, args.base_url, last_step(runs, "fundamentals_gold"), preflight,
                                                          scope, case_ids))
                elif name == "deep_evidence":
                    results.append(step_deep_evidence(out, args.base_url, args.ibkr_gateway, smoke=(tier != "milestone"),
                                                      judged=(tier != "daily" and not args.no_judge), tag=tag,
                                                      include_challenges=args.include_challenges, preflight=preflight))
                elif name == "concurrency":
                    results.append(step_concurrency(out, args.base_url))
                else:
                    results.append(step_ordinary(out, args.base_url, last_step(runs, "ordinary"),
                                                 judged=(tier != "daily" and not args.no_judge), tag=tag,
                                                 preflight=preflight, scope=scope, case_ids=case_ids))
            steps[name] = aggregate(results)
        except (OSError, ValueError, KeyError, requests.RequestException, subprocess.TimeoutExpired) as error:
            steps[name] = {"status": "ERROR", "detail": f"{type(error).__name__}: {error}"[:600]}

    noise = json.loads(NOISE.read_text(encoding="utf-8")) if NOISE.exists() else {}
    for name, step in steps.items():
        if step.get("noise_sd") and full_scope(step):
            noise[name] = {"sd": step["noise_sd"], "repeats": args.repeat, "measuredAt": started.isoformat(),
                           "configSha": config_sha, "gitSha": git_sha(), "dataset_sha256": step.get("dataset_sha256"),
                           "judge_rubric": step.get("judge_rubric")}
    if any(step.get("noise_sd") and full_scope(step) for step in steps.values()):
        DAILY.mkdir(parents=True, exist_ok=True)
        NOISE.write_text(json.dumps(noise, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    # The export reads 14 days of full traces; it belongs to change/milestone runs, not every quick layer run.
    wanted = args.online_review or tier in {"change", "milestone"} and not args.only and not args.case
    weekly = (online_report(out, args.base_url) if wanted and up and token else
              {"status": "SKIPPED", "detail": "Backend and admin token required" if wanted else "Not requested for this run"})
    summary = {"schema": "daily_eval_v3", "startedAt": started.isoformat(), "gitSha": git_sha(), "tier": tier,
               "since": since, "caseIds": sorted(case_ids) if case_ids else None,
               "repeat": args.repeat, "configSha": config_sha, "config": config,
               "output": str(out), "steps": steps, "uncovered": uncovered, "online_trace_review": weekly,
               "preflight": str(out / "preflight.json") if preflight else None,
               "deltaFromPrevious": deltas(steps, runs),
               "deltaBaselines": {name: (last_step(runs, name) or {}).get("startedAt") for name in steps}}
    summary["staleDays"] = staleness(runs + [summary], datetime.now(timezone.utc))
    (out / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    DAILY.mkdir(parents=True, exist_ok=True)
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
        for key in ("failed", "identity_changed", "newly_failing", "known_failing", "flaky_cases", "judge_flagged"):
            if step.get(key):
                print(f"                 {key}: {', '.join(step[key])}")
        if step.get("case_scope", "all") != "all":
            print(f"                 case scope: {step['case_scope']} (baseline, noise and staleness unchanged)")
        if step.get("skipped_cases"):
            print("                 skipped, source down: "
                  + ", ".join(f"{case_id} {reason}" for case_id, reason in step["skipped_cases"].items()))
        for case_id, row in (step.get("triage") or {}).items():
            print(f"                 {case_id}: {row['failureType']} answer-missing={row.get('missingInAnswer')}"
                  + (f" evidence-missing={row['missingInEvidence']}" if row.get("missingInEvidence") else "")
                  + (f" issues={row['issues']}" if row.get("issues") else ""))
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
            reference = noise.get(layer) or {}
            comparable = (config_sha is not None and reference.get("configSha") == config_sha
                          and reference.get("dataset_sha256") is not None
                          and reference["dataset_sha256"] == steps[layer].get("dataset_sha256")
                          and reference.get("judge_rubric") == steps[layer].get("judge_rubric"))
            sd = (reference.get("sd") or {}).get(metric) if comparable else None
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
    print(f"  online trace review: {weekly['status']} -> {weekly.get('output') or weekly.get('detail')}")
    if any(step["status"] in {"FAIL", "ERROR"} for step in steps.values()):
        return 1
    return 2 if any(step["status"] == "INCOMPLETE" for step in steps.values()) else 0


if __name__ == "__main__":
    raise SystemExit(main())
