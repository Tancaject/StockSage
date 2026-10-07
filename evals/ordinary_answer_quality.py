"""Review actual ordinary answers offline; never infer semantic quality from citations.

Export --review-template first, then apply --reviews to the same execution/replay result.
--baseline compares only matching cases and identical visible source evidence.
No network or model calls are made. Metrics cover ordinary-final-answer only.
"""
from __future__ import annotations

import argparse
import copy
import json
from datetime import datetime, timezone
from pathlib import Path

from eval_common import assess_review_format, capture_answer, json_hash, sha256, summarize_status
from eval_common import context_errors as captured_context_errors

DIMENSIONS = ("claim_support", "numeric_period_correctness", "counterevidence", "unknowns", "task_completion")
REVIEW_REQUIREMENTS = {
    "claim_support": "只检查回答已经陈述的事实及推断是否成立于本轮证据。来源矛盾、无依据事实、错误因果、错误派生结论为FAIL；均有支持为PASS。漏答本身由task_completion评价，不因漏答就认定已写事实错误。明确标为假设的一般解释不当作已证实事实；不使用模型记忆补证。",
    "numeric_period_correctness": "核查回答实际使用的数值、指标名称、单位、期间、比较基准和计算。错误值、GAAP口径或收入/费用错配为FAIL；正确为PASS。没有需要核查的数值/期间主张才为NOT_APPLICABLE；只有公司名、章节编号或题目中的年份不构成数值主张。",
    "counterevidence": "先按问题与回答结论检查证据是否有相关的重要反向经营事实或可比性因素（如增长同时利润率下降、影响比较的非经常损益）。有且回答已呈现/回应相关指标及比较因素为PASS，有而未涉及为FAIL；证据充分且确实没有这类信息才为NOT_APPLICABLE。已处理反证不是不适用。已讨论相应指标但数值或方向说错归入事实/数值，不能再当成未涉及；遗漏另一个独立的反向指标仍FAIL。缺少披露、因果未识别、预测本身不确定性归入unknowns，不能仅凭这些再次判反证失败。不要求添加范围外的负面观点。",
    "unknowns": "检查回答是否将证据缺口、因果未识别、预测不确定性明确保留。将明确未披露的指标补成确定事实、把相关性说成因果或将预测说成保证为FAIL；没有这种越界且适当说明相关缺口为PASS。用了‘预计/指引’不能豁免虚构披露。单纯算错、已有指标数值错配或漏答不自动使本维失败；必须指出具体的信息缺口或确定性越界。不要求无关免责声明。",
    "task_completion": "只检查用户要求的核心子问题、比较对象/期间、输出约束是否得到回应。给出回应或针对该子问明确说明信息缺口为PASS；忽略核心子问、答非所问为FAIL。覆盖不等于内容正确：全部子问已回答但数字错误，本维可PASS，事实/数值维度仍FAIL；声称无法回答也属于回应，其缺口声称是否真实由claim_support/unknowns评价。根据问题和回答判定，不要求额外来源支持覆盖判断；不使用NOT_APPLICABLE。",
}
STATUS_DEFINITIONS = {
    "PASS": "该维度适用，有足够材料判断且满足要求。",
    "FAIL": "有足够材料，能指出该维度的具体实质违反。",
    "NOT_APPLICABLE": "仅numeric_period_correctness无数值主张或counterevidence无相关反向信息时允许；说明检查范围和理由，不强制提供不存在的引文。",
    "NO_DATA": "该维度所需材料不足，无法判断；不是错误答案、接口失败或格式错误的同义词。",
}
EVIDENCE_RULES = {
    "scope": "AI诊断的摘录校验协议；普通人工评审使用相同语义标准、输入绑定和逐维理由，不凭reviewer文本认定人工身份。",
    "quotes": "提供的每条摘录须为输入原文连续子串，禁止改写、拼接或省略号替代。摘录仅证明定位。",
    "known": "PASS/FAIL应提供回答摘录；除task_completion外还需来源摘录。空回答的task_completion=FAIL允许无回答摘录。",
    "inapplicable_or_unknown": "NOT_APPLICABLE/NO_DATA需具体理由，摘录可为空；完整输入仍由哈希绑定。",
    "missing_source": "来源未捕获时，依赖来源的四维为NO_DATA；task_completion仍可根据完整问题/回答判断覆盖，不能凭URL或模型记忆补证。",
}
SCHEMA = "ordinary_answer_quality_v2"
SCOPE = "ordinary-final-answer"
RESULT_SCHEMAS = {"ordinary_execution_eval_v1", "ordinary_answer_replay_eval_v1"}


def ordinary_rubric() -> dict:
    rubric = {"schema": "ordinary_answer_rubric_v2", "scope": SCOPE,
              "dimensions": dict(REVIEW_REQUIREMENTS), "statuses": dict(STATUS_DEFINITIONS),
              "evidence_rules": dict(EVIDENCE_RULES),
              "attribution": "各维独立按其定义判断。同一缺陷确实同时违反多个定义时可跨维失败，但每项须独立说明依据；不能自动复制失败，也不能规定所有错误只归一维。只汇总各维状态，不将重复原因累加为惩罚总分。"}
    return {**rubric, "sha256": json_hash({**rubric, "dimensionKeys": DIMENSIONS})}


def attributes(case: dict) -> list[dict]:
    steps = case.get("trace", {}).get("steps", [])
    if isinstance(steps, str):
        steps = json.loads(steps)
    return [step.get("attributes") or {} for step in steps]


def context_errors(context: dict) -> list[str]:
    return captured_context_errors(context, expected_scope=SCOPE)


def material(case: dict) -> dict:
    """Recompute bindings from the actual exported stream and trace, not review labels."""
    attrs = attributes(case)
    contexts = [item for item in attrs if item.get("kind") == "answer-context"]
    context = contexts[0] if len(contexts) == 1 else {}
    invocations = [item for item in attrs if item.get("kind") == "model-invocation"
                   and item.get("scope") == "final-answer"]
    usage = [item for item in attrs if item.get("kind") == "model-usage"
             and item.get("scope") == "final-answer"]
    invocation = invocations[0] if len(invocations) == 1 else {}
    answer = "".join(event.get("content", "") for event in case.get("events", [])
                     if event.get("type") == "answer")
    definition = case.get("case_definition") or {}
    errors = []
    if not definition or definition.get("id") != case.get("id") or case.get("case_sha256") != json_hash(definition):
        errors.append("CASE_BINDING_MISSING_OR_CHANGED")
    if answer != case.get("answer") or sha256(answer) != case.get("answer_sha256"):
        errors.append("ANSWER_BINDING_MISSING_OR_CHANGED")
    errors.extend(context_errors(context))
    if not invocation.get("modelName") or not invocation.get("modelTier"):
        errors.append("MODEL_INVOCATION_MISSING")
    binding = {"id": case.get("id"), "scope": SCOPE, "rubricSha256": ordinary_rubric()["sha256"],
               "caseSha256": json_hash(definition), "answerSha256": sha256(answer),
               **{key: context.get(key) for key in
                  ("promptSha256", "contextSha256", "evidenceSha256")},
               "modelName": invocation.get("modelName"), "modelTier": invocation.get("modelTier"),
               "invocationSha256": json_hash(invocations)}
    return {"binding": binding, "question": definition.get("question", ""), "answer": answer,
            "answer_context": context, "model_invocations": invocations,
            "model_usage": usage[-1] if usage else {"usageSource": "NO_DATA"},
            "usage_scope": "final-answer", "errors": errors}


def review_template(report: dict) -> dict:
    if report.get("schema") not in RESULT_SCHEMAS:
        raise ValueError("Expected an ordinary execution or answer-replay result")
    unique_cases(report)
    return {"schema": SCHEMA, "scope": SCOPE, "rubric": ordinary_rubric(),
            "dataset_sha256": report.get("dataset_sha256"),
            "reviews": [{**material(case), "reviewer": "", "reviewed_at": "",
                         "dimensions": {name: {"status": "NO_DATA", "reason": ""} for name in DIMENSIONS}}
                        for case in report.get("cases", [])]}


def assess_review(case: dict, review: dict | None) -> dict:
    checked = assess_review_format(material(case), review, DIMENSIONS)
    if checked["errors"]:
        return checked
    for name, row in checked["dimensions"].items():
        if row["status"] == "NOT_APPLICABLE" and name not in {"numeric_period_correctness", "counterevidence"}:
            checked["errors"].append("NOT_APPLICABLE_FORBIDDEN:" + name)
            row["status"] = "NO_DATA"
    checked["status"] = summarize_status([row["status"] for row in checked["dimensions"].values()])
    return checked


def assess_diagnostic_dimensions(item: dict, ratings: dict, global_errors: list[str]) -> dict:
    """Validate each dimension independently; only capture/transport errors affect all."""
    dimensions = {}
    for name in DIMENSIONS:
        row = ratings.get(name) if isinstance(ratings, dict) else None
        errors = list(global_errors)
        raw = row if isinstance(row, dict) else {}
        status = raw.get("reported_status", raw.get("status"))
        reason = raw.get("reason", "")
        if not isinstance(status, str) or status not in STATUS_DEFINITIONS:
            errors.append("STATUS_INVALID")
            status = None
        if not isinstance(reason, str) or not reason.strip():
            errors.append("REASON_MISSING")
        if status == "NOT_APPLICABLE" and name not in {"numeric_period_correctness", "counterevidence"}:
            errors.append("NOT_APPLICABLE_FORBIDDEN")
        if not item.get("evidence", "").strip() and name != "task_completion" and status != "NO_DATA":
            errors.append("SOURCE_UNAVAILABLE")
        if not item.get("question", "").strip():
            errors.append("QUESTION_UNAVAILABLE")
        quotes = {}
        for field, text in (("answer_quotes", item.get("answer", "")), ("evidence_quotes", item.get("evidence", ""))):
            values = raw.get(field, [])
            required = status in {"PASS", "FAIL"} and (field == "answer_quotes" or name != "task_completion")
            if field == "answer_quotes" and name == "task_completion" and status == "FAIL" and not text.strip():
                required = False
            if not isinstance(values, list) or any(not isinstance(q, str) or not q.strip() or q not in text for q in values):
                errors.append("EXCERPT_INVALID:" + field)
            elif required and not values:
                errors.append("EXCERPT_MISSING:" + field)
            quotes[field] = values
        dimensions[name] = {"status": "NO_DATA" if errors else status, "reported_status": status,
                            "reason": reason, **quotes, "errors": errors}
    valid_count = sum(not row["errors"] for row in dimensions.values())
    return {"status": summarize_status([row["status"] for row in dimensions.values()]),
            "technical_status": "VALID" if valid_count == len(DIMENSIONS) else "PARTIAL" if valid_count else "INVALID",
            "errors": list(global_errors), "dimensions": dimensions}


def unique_cases(report: dict) -> dict[str, dict]:
    cases = report.get("cases", [])
    result = {case["id"]: case for case in cases}
    if len(cases) != len(result) or any(not key for key in result):
        raise ValueError("Result cases must have nonempty unique ids")
    return result


def apply_reviews(report: dict, reviews: dict) -> dict:
    if report.get("schema") not in RESULT_SCHEMAS:
        raise ValueError("Expected an ordinary execution or answer-replay result")
    if reviews.get("schema") != SCHEMA or reviews.get("scope") != SCOPE:
        raise ValueError("Unsupported review schema or scope")
    if reviews.get("dataset_sha256") != report.get("dataset_sha256"):
        raise ValueError("Reviews belong to a different dataset")
    if reviews.get("rubric") != ordinary_rubric():
        raise ValueError("Ordinary answer review rubric is missing or changed; export a new template and review its criteria")
    output = copy.deepcopy(report)
    cases = unique_cases(output)
    definitions = [case.get("case_definition") for case in cases.values()]
    if len(cases) == output.get("sample_count") and all(isinstance(row, dict) and row for row in definitions):
        if json_hash(definitions) != output.get("dataset_sha256"):
            raise ValueError("Complete result cases do not match dataset_sha256; regenerate the result")
    indexed = {}
    for review in reviews.get("reviews", []):
        key = (review.get("binding") or {}).get("id")
        if key not in cases or key in indexed:
            raise ValueError("Unknown or duplicate review case: " + str(key))
        indexed[key] = review
    for key, case in cases.items():
        case["quality_review"] = assess_review(case, indexed.get(key))
    status = summarize_status([case["quality_review"]["status"] for case in cases.values()])
    complete = bool(cases) and len(cases) == output.get("sample_count")
    if not complete and status != "FAIL":
        status = "NO_DATA"
    output["answer_quality"] = status
    gate = "FAIL" if status == "FAIL" or output.get("status") == "FAIL" else (
        "PASS" if status == "PASS" and complete and output.get("status") == "PASS"
        and all(case.get("passed") is True for case in cases.values()) else "NO_DATA")
    dimensions = {}
    for name in DIMENSIONS:
        values = [case["quality_review"]["dimensions"][name]["status"] for case in cases.values()]
        values.extend(["NO_DATA"] * max(0, output.get("sample_count", 0) - len(cases)))
        rated = [value for value in values if value in {"PASS", "FAIL"}]
        dimensions[name] = {"rated_count": len(rated), "pass_count": rated.count("PASS"),
                            "not_applicable_count": values.count("NOT_APPLICABLE"),
                            "missing_count": values.count("NO_DATA"),
                            "pass_rate": rated.count("PASS") / len(rated) if rated else None}
    reviewed_count = sum(not case["quality_review"]["errors"] and all(
        row["status"] != "NO_DATA" for row in case["quality_review"]["dimensions"].values()) for case in cases.values())
    output["quality_evaluation"] = {"schema": SCHEMA, "scope": SCOPE, "rubric": ordinary_rubric(), "status": gate,
                                    "generated_at": datetime.now(timezone.utc).isoformat(),
                                    "sample_count": output.get("sample_count"), "completed_count": len(cases),
                                    "reviewed_count": reviewed_count,
                                    "review_coverage": reviewed_count / output["sample_count"] if output.get("sample_count") else None,
                                    "rated_count": sum(case["quality_review"]["status"] in {"PASS", "FAIL"}
                                                       and not case["quality_review"]["errors"] for case in cases.values()),
                                    "dimensions": dimensions}
    return output


def compare_reports(baseline: dict, candidate: dict) -> dict:
    before, after = unique_cases(baseline), unique_cases(candidate)
    paired, unpaired = [], []
    for key in sorted(before.keys() | after.keys()):
        if key not in before or key not in after:
            unpaired.append({"id": key, "reason": "CASE_MISSING"})
            continue
        if before[key].get("passed") is not True or after[key].get("passed") is not True:
            unpaired.append({"id": key, "reason": "EXECUTION_NOT_PASSED"})
            continue
        left, right = material(before[key]), material(after[key])
        if left["errors"] or right["errors"]:
            unpaired.append({"id": key, "reason": "CONTEXT_OR_BINDING_UNAVAILABLE"})
            continue
        if any(left["binding"][field] != right["binding"][field] for field in ("caseSha256", "evidenceSha256")):
            unpaired.append({"id": key, "reason": "CASE_OR_EVIDENCE_CHANGED"})
            continue
        left_review = assess_review(before[key], before[key].get("quality_review"))
        right_review = assess_review(after[key], after[key].get("quality_review"))
        if left_review["errors"] or right_review["errors"]:
            unpaired.append({"id": key, "reason": "BOUND_REVIEW_MISSING"})
            continue
        deltas = {}
        for name in DIMENSIONS:
            a = left_review["dimensions"][name]["status"]
            b = right_review["dimensions"][name]["status"]
            deltas[name] = int(b == "PASS") - int(a == "PASS") if a in {"PASS", "FAIL"} and b in {"PASS", "FAIL"} else None
        paired.append({"id": key, "baseline": left["binding"], "candidate": right["binding"],
                       "baseline_model_invocations": left["model_invocations"],
                       "candidate_model_invocations": right["model_invocations"],
                       "baseline_model_usage": left["model_usage"], "candidate_model_usage": right["model_usage"],
                       "dimension_deltas": deltas})
    routes = {key: str((before.get(key) or after[key]).get("case_definition", {}).get("route") or "UNKNOWN")
              for key in before.keys() | after.keys()}
    strata = {}
    for route in sorted(set(routes.values())):
        strata[route] = pair_summary([row for row in paired if routes[row["id"]] == route],
                                    [row for row in unpaired if routes[row["id"]] == route])
    return {"schema": "ordinary_answer_comparison_v2", "scope": SCOPE, "usage_scope": "final-answer", "rubric": ordinary_rubric(),
            **pair_summary(paired, unpaired), "stratification": "baseline_expected_route_else_candidate",
            "coverage_scope": "exported_case_union",
            "route_strata": strata, "pairs": paired, "unpaired": unpaired}


def pair_summary(paired: list[dict], unpaired: list[dict]) -> dict:
    """Keep absent/unrateable pairs visible; PAIRED describes evidence, not superiority."""
    summary = {}
    for name in DIMENSIONS:
        values = [row["dimension_deltas"][name] for row in paired if row["dimension_deltas"][name] is not None]
        summary[name] = {"rated_pair_count": len(values), "pass_rate_delta": sum(values) / len(values) if values else None}
    total = len(paired) + len(unpaired)
    return {"status": "PAIRED" if any(row["rated_pair_count"] for row in summary.values()) else "NO_DATA",
            "case_count": total, "paired_count": len(paired), "unpaired_count": len(unpaired),
            "pair_coverage": len(paired) / total if total else None,
            "unpaired_reasons": {reason: sum(row["reason"] == reason for row in unpaired)
                                 for reason in sorted({row["reason"] for row in unpaired})},
            "dimensions": summary}


def analyst_inputs(report: dict) -> dict:
    """Export inputs only; never reuse the observed answer/review as a candidate result."""
    if report.get("schema") != "ordinary_execution_eval_v1":
        raise ValueError("Expected an ordinary_execution_eval_v1 live result")
    pairs, unavailable = [], []
    for key, case in unique_cases(report).items():
        observed = material(case)
        context = observed["answer_context"]
        span = context.get("analystSpan") or {}
        ordinary = [row for row in attributes(case) if row.get("kind") == "ordinary-evidence"]
        if observed["errors"] or case.get("passed") is not True:
            unavailable.append({"id": key, "reason": "EXECUTION_OR_BINDING_UNAVAILABLE"})
            continue
        if len(ordinary) != 1 or ordinary[0].get("analystStatus") != "COMPLETED" or not span:
            unavailable.append({"id": key, "reason": "COMPLETE_ANALYST_SPAN_UNAVAILABLE"})
            continue
        messages = context["messages"]
        index, start, end = (span.get(field) for field in ("messageIndex", "start", "end"))
        if (span.get("schemaVersion") != 1 or span.get("offsetUnit") != "UNICODE_CODE_POINT"
                or any(type(value) is not int for value in (index, start, end))
                or not 0 <= index < len(messages)
                or messages[index]["role"] != "user"
                or messages[index]["text"] != context["context"]
                or not 0 <= start < end <= len(context["context"])
                or span.get("removedTextSha256") != sha256(context["context"][start:end])):
            unavailable.append({"id": key, "reason": "INVALID_ANALYST_SPAN"})
            continue
        candidate = copy.deepcopy(messages)
        candidate[index]["text"] = context["context"][:start] + context["context"][end:]
        def input_payload(rows):
            canonical = [{"role": row["role"], "text": row["text"]} for row in rows]
            return {"messages": canonical,
                    "context": rows[index]["text"], "contextSha256": sha256(rows[index]["text"]),
                    "promptSha256": sha256(json.dumps(canonical, ensure_ascii=False, separators=(",", ":")))}
        pairs.append({"id": key, "source_binding": observed["binding"],
                      "case_definition": case["case_definition"],
                      "source_model_invocations": observed["model_invocations"],
                      "evidenceContext": context["evidenceContext"], "evidenceSha256": context["evidenceSha256"],
                      "removedDraftSha256": sha256(context["context"][start:end]),
                      "analystSpan": span,
                      "baseline": input_payload(messages), "without_analyst": input_payload(candidate)})
    return {"schema": "ordinary_analyst_inputs_v1", "scope": "frozen-final-answer-inputs",
            "status": "INPUTS_READY" if pairs else "NO_DATA", "pairs": pairs, "unavailable": unavailable}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--review-template", type=Path)
    mode.add_argument("--reviews", type=Path)
    mode.add_argument("--analyst-inputs", type=Path)
    parser.add_argument("--baseline", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.reviews and not args.output:
        parser.error("--reviews requires --output")
    if args.baseline and not args.reviews:
        parser.error("--baseline requires --reviews")
    try:
        report = json.loads(args.input.read_text(encoding="utf-8"))
        if args.review_template:
            output, path = review_template(report), args.review_template
        elif args.analyst_inputs:
            output, path = analyst_inputs(report), args.analyst_inputs
        else:
            output = apply_reviews(report, json.loads(args.reviews.read_text(encoding="utf-8")))
            if args.baseline:
                output["paired_comparison"] = compare_reports(json.loads(args.baseline.read_text(encoding="utf-8")), output)
            path = args.output
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    except (ValueError, KeyError, TypeError, OSError) as error:
        parser.exit(2, str(error) + "\n")
    status = ("REVIEW_TEMPLATE" if args.review_template else output["status"] if args.analyst_inputs
              else output["quality_evaluation"]["status"])
    print(json.dumps({"status": status, "output": str(path)}))
    return 0 if args.review_template or status in {"PASS", "INPUTS_READY"} else 2


if __name__ == "__main__":
    raise SystemExit(main())
