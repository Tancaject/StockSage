"""Run the DEEP completion golden set against the production Java policy."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any

from harness_eval_summary import evaluate_harness

HERE = Path(__file__).resolve().parent
DEFAULT_CASES = HERE / "harness_golden_set.jsonl"
DEFAULT_OUTPUT = HERE / "results" / "harness_eval_result.json"
DEFAULT_BACKEND = HERE.parent / "stocksage-backend"
JAVA_DRIVER = HERE / "harness" / "HarnessPolicyEval.java"
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
    platform: str | None = None,
) -> list[str]:
    windows = (platform or os.name) == "nt"
    wrapper = backend / ("mvnw.cmd" if windows else "mvnw")
    return [
        str(wrapper),
        "-q",
        "compile",
        "dependency:build-classpath",
        "-DincludeScope=runtime",
        f"-Dmdep.outputFile={backend / 'target' / 'harness-eval-classpath.txt'}",
    ]


def java_command(backend: Path, cases: Path, output: Path) -> list[str]:
    java_home = os.environ.get("JAVA_HOME")
    java = (
        str(Path(java_home) / "bin" / ("java.exe" if os.name == "nt" else "java"))
        if java_home else shutil.which("java")
    )
    if java is None:
        raise RuntimeError("找不到 Java；请安装 JDK 17 或更新版本，并配置 JAVA_HOME 或 PATH。")
    classpath_file = backend / "target" / "harness-eval-classpath.txt"
    if not classpath_file.is_file():
        raise RuntimeError(f"缺少运行依赖清单 {classpath_file}；请不带 --skip-build 重新运行。")
    classpath = os.pathsep.join([
        str(backend / "target" / "classes"),
        classpath_file.read_text(encoding="utf-8").strip(),
    ])
    # Maven's dependency path can exceed the Windows command-line limit.
    arguments = ["--class-path", classpath, str(JAVA_DRIVER), str(cases), str(output)]
    argument_file = output.parent / "launch.args"
    argument_file.write_text("\n".join(
        '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'
        for value in arguments
    ) + "\n", encoding="utf-8")
    return [java, f"@{argument_file}"]


def run_production_policy(
    backend: Path,
    cases: Path,
    output: Path,
    timeout_seconds: int = 180,
    skip_build: bool = False,
) -> tuple[dict[str, Any], int]:
    loaded_cases = load_cases(cases)
    backend = backend.resolve()
    output = output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    if not skip_build:
        build = subprocess.run(
            build_maven_command(backend), cwd=backend, check=False,
            timeout=timeout_seconds, stdout=sys.stderr, stderr=sys.stderr,
        )
        if build.returncode != 0:
            raise RuntimeError(
                f"后端编译或运行依赖生成失败，Maven 退出码 {build.returncode}；"
                "请先修复上方编译错误，再运行评测。"
            )
    completed = subprocess.run(
        java_command(backend, cases.resolve(), output),
        cwd=backend,
        check=False,
        timeout=timeout_seconds,
        stdout=sys.stderr,
        stderr=sys.stderr,
    )
    if completed.returncode != 0:
        raise RuntimeError(
            f"Harness Java 驱动编译或执行失败，退出码 {completed.returncode}；"
            "请查看上方 Java 错误，修复后重新运行。"
        )
    if not output.is_file():
        raise RuntimeError(
            "Java Harness evaluator did not produce a result; "
            f"expected output at {output}"
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
    return result, completed.returncode


def main() -> int:
    parser = argparse.ArgumentParser(
        description="编译并调用生产 DEEP 完成策略，评测固定用例；不启动服务或调用模型。"
    )
    parser.add_argument("--cases", type=Path, default=DEFAULT_CASES)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--backend", type=Path, default=DEFAULT_BACKEND)
    parser.add_argument("--summary-output", type=Path,
                        help="门禁汇总路径；默认写入结果文件旁的 .summary.json")
    parser.add_argument("--skip-build", action="store_true",
                        help="使用已编译的后端及 target/harness-eval-classpath.txt；仍重新编译并执行 Java 驱动")
    parser.add_argument("--timeout-seconds", type=int, default=180)
    parser.add_argument("--fail-on-gate", action="store_true",
                        help="现有 Harness 门禁未通过时返回退出码 1；执行错误始终返回 2")
    args = parser.parse_args()
    summary_output = args.summary_output or args.output.with_suffix(".summary.json")
    if args.output.resolve() == args.cases.resolve():
        parser.error("--output 必须与用例路径不同")
    if summary_output.resolve() in {args.output.resolve(), args.cases.resolve()}:
        parser.error("--summary-output 必须与用例和评测结果路径不同")
    try:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix=".harness-eval-", dir=args.output.parent) as directory:
            current_output = Path(directory) / "result.json"
            result, runner_exit_code = run_production_policy(
                args.backend, args.cases, current_output,
                max(1, args.timeout_seconds), args.skip_build,
            )
            gate_results, quality = evaluate_harness(result)
            summary = {
                "schema": "harness_eval_summary_v1",
                "status": "pass" if all(gate["status"] == "passed" for gate in gate_results) else "fail",
                "result_path": str(args.output.resolve()),
                "dataset_sha256": result["dataset_sha256"],
                "gates": gate_results,
                "quality": quality,
            }
            current_summary = Path(directory) / "summary.json"
            current_summary.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n",
                                       encoding="utf-8")
            summary_output.parent.mkdir(parents=True, exist_ok=True)
            current_output.replace(args.output)
            current_summary.replace(summary_output)
    except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired) as error:
        print(json.dumps({"status": "error", "error": str(error)}, ensure_ascii=False))
        return 2
    print(json.dumps({
        "status": result["status"],
        "gate_status": summary["status"],
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
    return 1 if args.fail_on_gate and summary["status"] != "pass" else 0


if __name__ == "__main__":
    raise SystemExit(main())
