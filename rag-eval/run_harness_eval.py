"""Run the DEEP completion golden set against the production Java policy."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
from pathlib import Path
from typing import Any

HERE = Path(__file__).resolve().parent
DEFAULT_CASES = HERE / "harness_golden_set.jsonl"
DEFAULT_OUTPUT = HERE / "results" / "harness_eval_result.json"
DEFAULT_BACKEND = HERE.parent / "stocksage-backend"
CASE_SCHEMA_VERSION = "harness_golden_case_v2"
RESULT_SCHEMA_VERSION = "harness_eval_v1"
MINIMUM_CASE_COUNT = 60
PHASES = {"EVIDENCE", "REPORT"}
OUTCOMES = {"PASS", "RECOVER", "DEGRADE", "BLOCK"}
CASE_FIELDS = {"id", "phase", "category", "tags", "fixture", "expected"}
EXPECTED_FIELDS = {
    "outcome",
    "violations",
    "recovery_actions",
    "allows_recommendation",
}
CASE_ID_PATTERN = re.compile(r"^[a-z0-9][a-z0-9_-]{2,95}$")


def _require_non_empty_string(value: Any, field: str, line_number: int) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(
            f"invalid harness case at line {line_number}: "
            f"{field} must be a non-empty string"
        )
    return value.strip()


def _require_string_list(value: Any, field: str, line_number: int) -> list[str]:
    if not isinstance(value, list) or any(
        not isinstance(item, str) or not item.strip() for item in value
    ):
        raise ValueError(
            f"invalid harness case at line {line_number}: "
            f"{field} must be a list of non-empty strings"
        )
    normalized = [item.strip() for item in value]
    if len(normalized) != len(set(normalized)):
        raise ValueError(
            f"invalid harness case at line {line_number}: "
            f"{field} must not contain duplicates"
        )
    return normalized


def _semantic_fingerprint(row: dict[str, Any]) -> str:
    semantic_input = {
        "phase": row["phase"],
        "fixture": row["fixture"],
    }
    canonical = json.dumps(
        semantic_input,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def load_cases(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    seen_ids: set[str] = set()
    seen_fingerprints: dict[str, str] = {}
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        try:
            row = json.loads(line)
        except json.JSONDecodeError as error:
            raise ValueError(
                f"invalid harness case JSON at line {line_number}: {error.msg}"
            ) from error
        if not isinstance(row, dict):
            raise ValueError(
                f"invalid harness case at line {line_number}: case must be an object"
            )
        if set(row) != CASE_FIELDS:
            missing = sorted(CASE_FIELDS - set(row))
            unknown = sorted(set(row) - CASE_FIELDS)
            raise ValueError(
                f"invalid harness case at line {line_number}: "
                f"missing fields={missing}, unknown fields={unknown}"
            )

        case_id = _require_non_empty_string(row["id"], "id", line_number)
        if not CASE_ID_PATTERN.fullmatch(case_id):
            raise ValueError(
                f"invalid harness case at line {line_number}: "
                "id must match ^[a-z0-9][a-z0-9_-]{2,95}$"
            )
        if case_id in seen_ids:
            raise ValueError(f"duplicate harness case id: {case_id}")
        seen_ids.add(case_id)

        phase = _require_non_empty_string(row["phase"], "phase", line_number)
        if phase not in PHASES:
            raise ValueError(
                f"invalid harness case at line {line_number}: "
                f"phase must be one of {sorted(PHASES)}"
            )
        row["phase"] = phase
        row["id"] = case_id
        row["category"] = _require_non_empty_string(
            row["category"], "category", line_number
        )
        row["tags"] = _require_string_list(row["tags"], "tags", line_number)
        if not row["tags"]:
            raise ValueError(
                f"invalid harness case at line {line_number}: "
                "tags must not be empty"
            )
        if not isinstance(row["fixture"], dict) or not row["fixture"]:
            raise ValueError(
                f"invalid harness case at line {line_number}: "
                "fixture must be a non-empty object"
            )

        expected = row["expected"]
        if not isinstance(expected, dict) or set(expected) != EXPECTED_FIELDS:
            missing = (
                sorted(EXPECTED_FIELDS - set(expected))
                if isinstance(expected, dict)
                else sorted(EXPECTED_FIELDS)
            )
            unknown = (
                sorted(set(expected) - EXPECTED_FIELDS)
                if isinstance(expected, dict)
                else []
            )
            raise ValueError(
                f"invalid harness case at line {line_number}: "
                f"expected missing fields={missing}, unknown fields={unknown}"
            )
        outcome = _require_non_empty_string(
            expected["outcome"], "expected.outcome", line_number
        )
        if outcome not in OUTCOMES:
            raise ValueError(
                f"invalid harness case at line {line_number}: "
                f"expected.outcome must be one of {sorted(OUTCOMES)}"
            )
        expected["outcome"] = outcome
        expected["violations"] = _require_string_list(
            expected["violations"], "expected.violations", line_number
        )
        expected["recovery_actions"] = _require_string_list(
            expected["recovery_actions"],
            "expected.recovery_actions",
            line_number,
        )
        if not isinstance(expected["allows_recommendation"], bool):
            raise ValueError(
                f"invalid harness case at line {line_number}: "
                "expected.allows_recommendation must be a boolean"
            )
        if expected["allows_recommendation"] != (outcome == "PASS"):
            raise ValueError(
                f"invalid harness case at line {line_number}: "
                "expected.allows_recommendation must agree with expected.outcome"
            )

        fingerprint = _semantic_fingerprint(row)
        duplicate_id = seen_fingerprints.get(fingerprint)
        if duplicate_id is not None:
            raise ValueError(
                "duplicate harness semantic fixture: "
                f"{case_id} duplicates {duplicate_id}"
            )
        seen_fingerprints[fingerprint] = case_id
        rows.append(row)
    if len(rows) < MINIMUM_CASE_COUNT:
        raise ValueError(
            "harness golden set must contain at least "
            f"{MINIMUM_CASE_COUNT} unique cases; found {len(rows)}"
        )
    return rows


def build_maven_command(
    backend: Path,
    cases: Path,
    output: Path,
    platform: str | None = None,
) -> list[str]:
    windows = (platform or os.name) == "nt"
    wrapper = backend / ("mvnw.cmd" if windows else "mvnw")
    return [
        str(wrapper),
        "-q",
        "-Dtest=HarnessGoldenSetTest",
        f"-Dharness.cases={cases.resolve()}",
        f"-Dharness.output={output.resolve()}",
        "test",
    ]


def run_production_policy(
    backend: Path,
    cases: Path,
    output: Path,
    timeout_seconds: int = 180,
) -> tuple[dict[str, Any], int]:
    loaded_cases = load_cases(cases)
    backend = backend.resolve()
    output = output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.unlink(missing_ok=True)
    command = build_maven_command(backend, cases, output)
    completed = subprocess.run(
        command,
        cwd=backend,
        check=False,
        timeout=timeout_seconds,
    )
    if not output.is_file():
        raise RuntimeError(
            "Java Harness evaluator did not produce a result; "
            f"Maven exit code was {completed.returncode}"
        )
    result = json.loads(output.read_text(encoding="utf-8"))
    if result.get("engine") != "java-production-policy":
        raise ValueError("Harness result was not produced by the Java production policy")
    if result.get("schema_version") != RESULT_SCHEMA_VERSION:
        raise ValueError(
            f"Harness result schema must be {RESULT_SCHEMA_VERSION}"
        )
    if result.get("case_schema_version") != CASE_SCHEMA_VERSION:
        raise ValueError(
            f"Harness case schema must be {CASE_SCHEMA_VERSION}"
        )
    if int(result.get("case_count", -1)) != len(loaded_cases):
        raise ValueError(
            "Harness result case_count does not match the validated golden set"
        )
    if completed.returncode != 0 and result.get("status") == "pass":
        result["status"] = "error"
        result["runner_error"] = f"Maven exited with code {completed.returncode}"
        output.write_text(
            json.dumps(result, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
    return result, completed.returncode


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--cases", type=Path, default=DEFAULT_CASES)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--backend", type=Path, default=DEFAULT_BACKEND)
    parser.add_argument("--timeout-seconds", type=int, default=180)
    parser.add_argument("--fail-on-gate", action="store_true")
    args = parser.parse_args()
    result, runner_exit_code = run_production_policy(
        args.backend,
        args.cases,
        args.output,
        max(1, args.timeout_seconds),
    )
    print(json.dumps({
        "status": result["status"],
        "engine": result["engine"],
        "case_schema_version": result["case_schema_version"],
        "policy_id": result["policy_id"],
        "policy_version": result["policy_version"],
        "case_count": result["case_count"],
        "decision_contract_exact_match_rate": result[
            "decision_contract_exact_match_rate"
        ],
        "violation_exact_match_rate": result["violation_exact_match_rate"],
        "recovery_exact_match_rate": result["recovery_exact_match_rate"],
        "unsafe_pass_count": result["unsafe_pass_count"],
        "coverage.status": (result.get("coverage") or {}).get("status"),
        "dataset_sha256": result["dataset_sha256"],
    }, ensure_ascii=False))
    if runner_exit_code != 0:
        return runner_exit_code
    return 1 if args.fail_on_gate and result["status"] != "pass" else 0


if __name__ == "__main__":
    raise SystemExit(main())
