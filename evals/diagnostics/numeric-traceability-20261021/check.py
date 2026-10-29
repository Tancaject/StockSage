"""Offline numeric diagnostics check; run from repository root, no service/model calls."""
from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "evals"))

from numeric_traceability import check


def self_check():
    empty = check("收入为 12 亿美元。", "")
    assert empty["status"] == "NO_DATA" and empty["traceable_rate"] is None
    assert empty["counts"]["indeterminate"] == 1
    labels = check("1. 2025年10月20日，FY2024 Q3 见 [E12]，0700.HK。\n第2项：2026-10-21 12:30，https://example.com/123", "")
    assert labels["status"] == "NOT_APPLICABLE", labels
    identifiers = check("CIK 0000093410，10-K accession 0000093410-26-000078；证据编号 E12。", "")
    assert identifiers["status"] == "NOT_APPLICABLE", identifiers
    direct = check("营收 1.25 亿美元，毛利率 12.5%。", "Revenue: USD 125 million. Margin: 12.50%.")
    assert direct["counts"]["direct"] == 2, direct
    assert check("市值 10 亿美元。", "市值 10 亿港元。")["counts"]["unmatched"] == 1
    assert check("利润率增加 10 个百分点。", "利润率 10%。")["counts"]["unmatched"] == 1
    mixed = check("收入 30 亿美元，利润 9 亿美元。", "收入 30 亿美元。")
    assert mixed["traceable_rate"] == 0.5 and mixed["counts"]["unmatched"] == 1, mixed
    calculated = check("占比为 20 / 100 = 20%。", "分部收入 20 亿美元；总收入 100 亿美元。")
    assert calculated["counts"]["calculated"] == 1, calculated
    explicit = check("占比 20 / 100 * 100 = 20%。", "分部收入 20 亿美元；总收入 100 亿美元。")
    assert explicit["counts"]["calculated"] == 1, explicit
    assert check("利润率 20%。", "收入 100 亿美元，成本 80 亿美元。")["counts"]["unmatched"] == 1
    bad = check("占比 20 / 100 = 30%。", "分部收入 20 亿美元；总收入 100 亿美元。")
    assert bad["counts"]["calculated"] == 0 and bad["counts"]["unmatched"] >= 1, bad
    missing = check("占比 20 / 100 = 20%。", "总收入 100 亿美元。")
    assert missing["counts"]["calculated"] == 0, missing
    zero = check("比率 20 / 0 = 1。", "金额分别为 20、0。")
    assert zero["counts"]["calculated"] == 0 and zero["counts"]["indeterminate"] >= 1, zero
    mismatch = check("差额 100 - 80 = 20 亿美元。", "收入 100 亿美元；成本 80 百万美元。")
    assert mismatch["counts"]["calculated"] == 0, mismatch
    count_units = check("人数 10 + 20 = 30 股。", "两部门分别为 10 人、20 人。")
    assert count_units["counts"]["calculated"] == 0, count_units
    product_units = check("总收入 10 * 20 = 200 亿美元。", "两部门分别收入 10 亿美元、20 亿美元。")
    assert product_units["counts"]["calculated"] == 0, product_units
    negative = check("净利润 −$5M。", "净利润 −5 百万美元。")
    assert negative["counts"]["direct"] == 1, negative
    unknown = check("产能 10 ZW。", "产能 10。")
    assert unknown["counts"]["indeterminate"] == 1, unknown
    partial = check("增速 .5%。", "增速 0.50%。")
    assert partial["counts"]["direct"] == 1, partial
    ranges = check("增速 10-12%。", "增速 -12%。")
    assert ranges["counts"]["direct"] == 0 and ranges["counts"]["indeterminate"] == 2, ranges
    original = "收入 **12.50 亿美元** [E2]。"
    evidence = "财报显示收入为 1,250 million USD。"
    result = check(original, evidence)
    assert result["counts"]["direct"] == 1, result
    assert all(row["sentence"] in original and all(s in evidence for s in row["source_excerpts"])
               for row in result["numbers"])
    json.dumps(result)


def historical():
    inputs = [ROOT / "evals/results/daily/20261020T040915Z/ordinary.json",
              ROOT / "evals/results/daily/20261020T040915Z/fundamentals-gold.json",
              ROOT / "evals/results/deep-m5/supplier_c.json"]
    rows, sources = [], []
    for path in inputs:
        payload = json.loads(path.read_text(encoding="utf-8"))
        sources.append({"path": str(path.relative_to(ROOT)), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
        for case in payload["cases"]:
            if "answer" in case:
                steps = case.get("trace", {}).get("steps") or []
                if isinstance(steps, str):
                    steps = json.loads(steps)
                contexts = [step.get("attributes") or {} for step in steps]
                context = next((item for item in contexts if item.get("kind") == "answer-context"), {})
                evidence = context.get("evidenceContext") or ""
                answer = case["answer"]
                scope = "ordinary-final-answer"
            else:
                answer = json.dumps(case.get("report") or {}, ensure_ascii=False)
                # These historical reports captured model-written summaries, not the source snapshot.
                evidence = ""
                scope = "deep-report"
            rows.append({"source": str(path.relative_to(ROOT)), "id": case["id"], "scope": scope,
                         "evidence_chars": len(evidence), "diagnostic": check(answer, evidence)})
    return {"schema": "numeric_traceability_offline_check_v1", "self_check": "PASS", "sources": sources,
            "helper_sha256": hashlib.sha256((ROOT / "evals/numeric_traceability.py").read_bytes()).hexdigest(),
            "cases": rows}


if __name__ == "__main__":
    self_check()
    report = historical()
    output = Path(__file__).with_name("results.json")
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"self_check": "PASS", "historical_cases": len(report["cases"]), "output": str(output)}, ensure_ascii=False))
