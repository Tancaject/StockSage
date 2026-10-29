"""DEEP 补证对照（M0）：按 deep_react_cases.jsonl 逐题提交 DEEP 研究，保存补证步骤与报告详情。

同一构建分别以补证关闭（A）和开启（B）运行；两组用不同评测账号，避免报告复用互相污染。
--evidence-only 时每题另存完整证据快照（evidence_snapshot），供 run_evaluation_agent.py --rubric deep_facets 评审。
评分见 deep_m0_score.py。凭据从环境变量读取，不写入输出。
"""
from __future__ import annotations

import argparse
import json
import os
import ssl
import time
import urllib.request
from datetime import datetime
from pathlib import Path

from eval_http import StockSageClient, LiveEvalError
from eval_observations import observe_case, summarize_cases
from eval_risk_checks import assess_risks
from numeric_traceability import check as numeric_check
from run_harness_live_eval import CaseReconciliationPending, load_cases, parse_steps, run_case

HERE = Path(__file__).resolve().parent


def replan_steps(trace: dict) -> list[dict]:
    """Trace 中补证（单步或取证循环）步骤的公开属性。"""
    rows = []
    for step in parse_steps(trace):
        attributes = step.get("attributes") or {}
        if attributes.get("stepKind") in {"replan_decision", "replan_execution", "loop_round", "loop_resume", "loop_stop"}:
            rows.append(attributes)
    return rows


def wait_for_ibkr(gateway: str) -> None:
    """DEEP 的美股行情来自 IBKR；会话失效时基础取证必然缺行情，题目会作废，所以提交前等待认证。"""
    context = ssl._create_unverified_context()
    announced = False
    while True:
        try:
            request = urllib.request.Request(gateway + "/v1/api/iserver/auth/status", data=b"", method="POST",
                                             headers={"User-Agent": "Mozilla/5.0"})
            body = urllib.request.urlopen(request, timeout=8, context=context).read().decode("utf-8", "replace")
            if '"authenticated":true' in body:
                if announced:
                    # 等待期间后端保活会连续 401 并打开熔断器（冷却 120 秒）；冷却结束前提交的题会因缺行情作废。
                    print(json.dumps({"ibkr": "authenticated, waiting for backend circuit breaker cooldown"}), flush=True)
                    time.sleep(130)
                return
        except Exception:
            pass
        if not announced:
            print(json.dumps({"waiting": "IBKR not authenticated"}), flush=True)
            announced = True
        time.sleep(15)


def report_payload(detail: dict) -> dict:
    report = detail.get("report") or {}
    keep = ["recommendation", "analystSummary", "rationale", "riskFactors", "bullFactors", "bearFactors",
            "bullCase", "bearCase", "unknowns", "dataFreshness", "decisionAudit", "reusedFromCache", "qualityStatus"]
    return {
        "report": {key: report.get(key) for key in keep},
        "evidence_items": detail.get("evidenceItems") or report.get("evidenceItems") or [],
    }


def report_text(report: dict, evidence_items: list[dict] | None = None) -> str:
    def strings(value):
        if isinstance(value, str):
            return [value]
        children = value.values() if isinstance(value, dict) else value if isinstance(value, list) else []
        return [text for child in children for text in strings(child)]
    texts = [text for key in ("analystSummary", "rationale", "riskFactors", "bullFactors", "bearFactors",
                              "bullCase", "bearCase", "unknowns", "dataFreshness")
             for text in strings(report.get(key))]
    # These table cells are generated claims, not source evidence for numeric verification.
    items = evidence_items if evidence_items is not None else report.get("evidenceItems") or []
    texts.extend(text for item in items for key in ("dimension", "evidence", "implication")
                 for text in strings(item.get(key)))
    return "\n".join(texts)


def report_evidence_ids(report: dict, evidence_items: list[dict] | None = None) -> list[str] | None:
    if not report and evidence_items is None:
        return None
    items = evidence_items if evidence_items is not None else report.get("evidenceItems") or []
    return sorted({evidence_id for item in items for evidence_id in item.get("sourceEvidenceIds") or []})


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--arm", required=True, help="对照组标签，例如 A_OFF、B_REPLAN")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--cases", type=Path, default=HERE / "deep_react_cases.jsonl")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--email-env", default="STOCKSAGE_EVAL_EMAIL")
    parser.add_argument("--password-env", default="STOCKSAGE_EVAL_PASSWORD")
    parser.add_argument("--ibkr-gateway", default="https://localhost:5000", help="为空时不检查 IBKR 会话")
    parser.add_argument("--evidence-only", action="store_true",
                        help="请求级只跑证据阶段（需 STOCKSAGE_ADMIN_TOKEN），后端无需切换全局开关")
    parser.add_argument("--include-challenges", action="store_true", help="包含仅供诊断的挑战题")
    parser.add_argument("--full-capture", action="store_true",
                        help="完整 DEEP 并保存每次模型调用的原始输入输出（需 STOCKSAGE_ADMIN_TOKEN）")
    args = parser.parse_args()

    cases = load_cases(args.cases)
    if not args.include_challenges:
        cases = [case for case in cases if case.get("gate_eligible") is not False]
    if args.evidence_only or args.full_capture:
        for case in cases:
            case["evaluation_mode"] = "EVIDENCE_ONLY" if args.evidence_only else "FULL_CAPTURE"
    done = {}
    if args.output.exists():
        done = {row["id"]: row for row in json.loads(args.output.read_text(encoding="utf-8"))["cases"]}
    admin_token = os.environ.get("STOCKSAGE_ADMIN_TOKEN", "")
    if (args.evidence_only or args.full_capture) and not admin_token:
        raise SystemExit("--evidence-only / --full-capture 需要 STOCKSAGE_ADMIN_TOKEN")
    client = StockSageClient(args.base_url, extra_headers={"X-StockSage-Admin-Token": admin_token} if admin_token else None)
    client.login(os.environ[args.email_env], os.environ[args.password_env])
    results = []
    for case in cases:
        if case["id"] in done:
            results.append(done[case["id"]])
            continue
        if args.ibkr_gateway:
            wait_for_ibkr(args.ibkr_gateway)
        row = {"id": case["id"], "ticker": case["ticker"], "category": case.get("category"),
               "required_facets": case.get("required_facets", []), "gate_eligible": case.get("gate_eligible", True)}
        try:
            run = run_case(client, case)
            row["run"] = {key: run.get(key) for key in (
                "status", "task_id", "trace_id", "conversation_id", "task_status", "result_kind",
                "result_report_version_id", "wall_seconds", "tool_action_count", "unsafe_reasons")}
            _, trace = client.json_request("GET", "/api/trace/" + str(run["trace_id"]))
            row["trace"] = trace
            row["replan"] = replan_steps(trace)
            # 每轮的新证据数、续停来源、推测执行结果和评分对比，由后端写在 Trace 步骤属性里。
            steps = trace.get("steps") if isinstance(trace, dict) else None
            if isinstance(steps, str):
                steps = json.loads(steps)
            attributes = [step.get("attributes") or {} for step in steps or [] if isinstance(step, dict)]
            row["debate_rounds"] = [item for item in attributes if item.get("kind") == "debate-round"]
            row["scoring_comparison"] = next(
                (item for item in attributes if item.get("kind") == "scoring-comparison"), None)
            if admin_token:
                try:
                    _, row["model_invocations"] = client.json_request(
                        "GET", f"/api/admin/agent/research-tasks/{run['task_id']}/model-invocations")
                    row["model_invocations_complete"] = True
                    row["model_capture_scope"] = "DEEP_TASK_LEDGER_EXCLUDES_ROUTING"
                except LiveEvalError:
                    row["model_invocations_error"] = "Model invocation ledger could not be read; resource totals are unknown."
            if run.get("result_kind") == "EVIDENCE_ONLY":
                # 只跑证据阶段：取该研究写回会话的证据摘要，供盲评按必需维度打分。
                _, messages = client.json_request("GET", f"/api/chat/conversations/{run['conversation_id']}/messages")
                digests = [m.get("content") for m in messages or []
                           if m.get("role") == "assistant" and m.get("traceId") == run["trace_id"]]
                row["evidence_digest"] = digests[-1] if digests else ""
            if admin_token:
                # 用同一任务最终证据作追溯来源，报告中的模型归纳不能给自己作证。
                try:
                    _, snapshot = client.json_request(
                        "GET", f"/api/admin/agent/research-tasks/{run['task_id']}/evidence-snapshot")
                    row["evidence_snapshot"] = {key: snapshot.get(key) for key in (
                        "query", "ticker", "fundamentalsReport", "marketReport", "newsReport", "evidenceLedger")}
                except LiveEvalError:
                    if args.evidence_only:
                        raise
                    row["evidence_snapshot_error"] = "Final evidence snapshot could not be read; numeric traceability is NO_DATA."
            report_id = run.get("result_report_version_id")
            if report_id:
                _, detail = client.json_request("GET", f"/api/reports/investment/{report_id}")
                row.update(report_payload(detail))
        except CaseReconciliationPending as pending:
            row["error"] = f"reconciliation pending: {pending}"[:500]
        except Exception as error:  # 单题失败不中断整组，保留错误供复核
            row["error"] = f"{type(error).__name__}: {error}"[:500]
        row["observations"] = observe_case(row)
        snapshot = row.get("evidence_snapshot") or {}
        answer_text = report_text(row.get("report") or {}, row.get("evidence_items"))
        source_text = "\n".join(snapshot.get(key) or "" for key in ("fundamentalsReport", "marketReport", "newsReport"))
        row["numeric_traceability"] = numeric_check(answer_text, source_text)
        row["risk_checks"] = assess_risks(case, row.get("trace") or {},
                                          answer_text,
                                          row.get("evidence_snapshot"),
                                          report_evidence_ids(row.get("report") or {}, row.get("evidence_items")))
        results.append(row)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps({"schema": "deep_m0_run_v1", "arm": args.arm,
                                           "generated_at": datetime.now().astimezone().isoformat(),
                                           "cases": results, "observations_summary": summarize_cases(results)},
                                          ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps({"id": case["id"], "status": row.get("run", {}).get("status"),
                          "result_kind": row.get("run", {}).get("result_kind"), "error": row.get("error")},
                         ensure_ascii=False), flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
