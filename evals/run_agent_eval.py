"""Run the typed planner evaluator and emit agent_eval_v1.

Only Python's standard library is used so the runner works in the normal
project harness. Live LLM modes still require a running backend and provider.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path

from agent_eval_summary import build_report, load_json


HERE = Path(__file__).resolve().parent


def load_cases(path: Path) -> list[dict]:
    return [
        json.loads(line)
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]


def cases_sha256(cases: list[dict]) -> str:
    """Hash semantic JSONL content independent of whitespace and line endings."""
    canonical = "\n".join(
        json.dumps(case, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        for case in cases
    )
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def invoke(
    endpoint: str,
    mode: str,
    cases: list[dict],
    admin_token: str | None,
    timeout_seconds: float = 600.0,
) -> dict:
    request = urllib.request.Request(
        endpoint,
        data=json.dumps({"mode": mode, "cases": cases}).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    if admin_token:
        request.add_header("X-StockSage-Admin-Token", admin_token)
    with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
        return json.loads(response.read().decode("utf-8"))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--endpoint",
        default="http://localhost:8080/api/eval/agent/planner",
    )
    parser.add_argument(
        "--mode",
        choices=("DETERMINISTIC", "LIVE_COORDINATOR"),
        default="DETERMINISTIC",
    )
    parser.add_argument("--cases", type=Path, default=HERE / "agent_golden_set.jsonl")
    parser.add_argument(
        "--gates", type=Path, default=HERE / "agent_eval_gates.json",
        help="Planner-only evaluation standards",
    )
    parser.add_argument(
        "--harness-gates", type=Path, default=HERE / "harness_eval_gates.json",
        help="Independent offline/live Harness evaluation standards",
    )
    parser.add_argument("--baseline", type=Path)
    parser.add_argument("--rag", type=Path)
    parser.add_argument("--trace", type=Path)
    parser.add_argument("--harness", type=Path)
    parser.add_argument(
        "--harness-live",
        type=Path,
        help="Optional harness_live_eval_v1 result from a real DEEP HTTP run",
    )
    parser.add_argument(
        "--dialog",
        type=Path,
        help="Optional EchoMind-style end-to-end dialog/LLM-as-Judge report",
    )
    parser.add_argument("--output", type=Path, default=HERE / "agent_eval_result.json")
    parser.add_argument("--admin-token")
    parser.add_argument(
        "--timeout-seconds",
        type=float,
        default=600.0,
        help="HTTP timeout for the sequential planner batch (default: 600 seconds)",
    )
    args = parser.parse_args()
    if args.timeout_seconds <= 0:
        parser.error("--timeout-seconds must be greater than zero")

    try:
        cases = load_cases(args.cases)
        planner = invoke(
            args.endpoint,
            args.mode,
            cases,
            args.admin_token,
            args.timeout_seconds,
        )
        planner["datasetSha256"] = cases_sha256(cases)
        report = build_report(
            planner,
            load_json(args.gates),
            load_json(args.baseline) if args.baseline else None,
            load_json(args.rag) if args.rag else None,
            load_json(args.trace) if args.trace else None,
            load_json(args.dialog) if args.dialog else None,
            load_json(args.harness) if args.harness else None,
            load_json(args.harness_live) if args.harness_live else None,
            harness_gates=load_json(args.harness_gates),
        )
        args.output.write_text(
            json.dumps(report, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        planner_status = report["planner"]["gate_status"]
        print(
            f"{report['schema_version']} planner={planner_status} "
            f"unified={report['status']} -> {args.output}"
        )
        return 0 if planner_status == "passed" else 2
    except (OSError, ValueError, urllib.error.URLError) as error:
        print(f"agent eval could not run: {type(error).__name__}", file=sys.stderr)
        return 3


if __name__ == "__main__":
    raise SystemExit(main())
