"""DEEP 补证对照（M0）评分。

packet：自动统计补证触发、新增证据及其被报告采用的比例，并生成盲评包：A/B 两组随机标为 X/Y；
传入 --c（取证循环，M5）时三组随机标为 X/Y/Z。
score：读入盲评打分（每个必需维度 0=缺失，1=提到但无可用证据，2=由证据支撑），还原组别后汇总。
"""
from __future__ import annotations

import argparse
import json
import random
from pathlib import Path


def load(path: Path) -> dict[str, dict]:
    return {row["id"]: row for row in json.loads(path.read_text(encoding="utf-8"))["cases"]}


def replan_summary(row: dict) -> dict:
    added = []
    decisions = []
    for step in row.get("replan") or []:
        if step.get("stepKind") in ("replan_decision", "loop_stop"):
            decisions.append({key: step.get(key) for key in ("decision", "replanAction", "status", "stopReason",
                                                              "reasonCode", "rounds", "toolCalls")
                              if step.get(key) is not None})
        added += [str(item) for item in step.get("addedEvidenceIds") or []]
    cited = {str(item) for evidence in row.get("evidence_items") or [] for item in evidence.get("sourceEvidenceIds") or []}
    used = sorted(set(added) & cited)
    return {"decisions": decisions, "added_evidence_ids": sorted(set(added)), "used_added_ids": used,
            "utilization": (len(used) / len(set(added))) if added else None}


def render_report(row: dict) -> str:
    if row.get("error"):
        return f"（本组运行失败：{row['error']}）"
    if "evidence_digest" in row:
        # 只跑证据阶段：按证据本身打分（2=有证据直接覆盖，1=只部分或间接覆盖，0=没有覆盖）。
        return "证据摘要：" + chr(10) + (row.get("evidence_digest") or "（无证据）")
    report = row.get("report") or {}
    lines = [f"结论：{report.get('recommendation')}", f"摘要：{report.get('analystSummary')}"]
    for key, title in (("rationale", "理由"), ("bullFactors", "看多"), ("bearFactors", "看空"),
                       ("riskFactors", "风险"), ("unknowns", "未知/缺口")):
        for item in report.get(key) or []:
            lines.append(f"- {title}：{item}")
    lines.append("证据表：")
    for evidence in row.get("evidence_items") or []:
        lines.append(f"  - [{evidence.get('dimension')}] {evidence.get('evidence')}（来源：{evidence.get('source')}；"
                     f"证据ID：{', '.join(evidence.get('sourceEvidenceIds') or []) or '无'}）")
    return "\n".join(lines)


def packet(args: argparse.Namespace) -> None:
    arms = {"A": load(args.a), "B": load(args.b)}
    if args.c:
        arms["C"] = load(args.c)
    rng = random.Random(args.seed)
    mapping, auto, parts = {}, {}, []
    for case_id, row_a in arms["A"].items():
        rows = {arm: data.get(case_id, {}) for arm, data in arms.items()}
        auto[case_id] = {arm: replan_summary(row) for arm, row in rows.items()}
        auto[case_id]["wall_seconds"] = {arm: (row.get("run") or {}).get("wall_seconds") for arm, row in rows.items()}
        auto[case_id]["result_kind"] = {arm: (row.get("run") or {}).get("result_kind") for arm, row in rows.items()}
        order = list(rows)
        rng.shuffle(order)
        labels = ["X", "Y", "Z"][:len(order)]
        mapping[case_id] = dict(zip(labels, order))
        facets = "\n".join(f"- {f['id']}：{f['description']}" for f in row_a.get("required_facets", []))
        reports = "\n\n".join(f"### 报告 {label}\n{render_report(rows[arm])}" for label, arm in zip(labels, order))
        parts.append(f"## {case_id}（{row_a.get('ticker')}，{row_a.get('category')}）\n\n必需维度：\n{facets}\n\n{reports}\n")
    args.out_dir.mkdir(parents=True, exist_ok=True)
    (args.out_dir / "review_packet.md").write_text("\n".join(parts), encoding="utf-8")
    (args.out_dir / "blind_mapping.json").write_text(json.dumps(mapping, indent=2), encoding="utf-8")
    (args.out_dir / "auto_metrics.json").write_text(json.dumps(auto, ensure_ascii=False, indent=2), encoding="utf-8")
    summary = {"cases": len(auto)}
    for arm in [arm for arm in arms if arm != "A"]:
        used = [item[arm]["utilization"] for item in auto.values() if item[arm]["utilization"] is not None]
        summary[f"{arm}_added_evidence_cases"] = sum(1 for item in auto.values() if item[arm]["added_evidence_ids"])
        summary[f"{arm}_mean_utilization"] = (sum(used) / len(used)) if used else None
    print(json.dumps(summary, ensure_ascii=False))


def score(args: argparse.Namespace) -> None:
    mapping = json.loads((args.out_dir / "blind_mapping.json").read_text(encoding="utf-8"))
    reviews = json.loads(args.reviews.read_text(encoding="utf-8"))  # {case_id: {"X": {facet: 0-2}, "Y": {...}}}
    totals = {arm: [] for labels in mapping.values() for arm in labels.values()}
    per_case = {}
    for case_id, labels in reviews.items():
        arms = {mapping[case_id][label]: scores for label, scores in labels.items()}
        per_case[case_id] = {arm: sum(scores.values()) for arm, scores in arms.items()}
        for arm, scores in arms.items():
            totals[arm] += list(scores.values())
    summary = {arm: {"facet_score_mean": sum(values) / len(values) if values else None,
                     "evidence_backed_rate": sum(1 for v in values if v == 2) / len(values) if values else None}
               for arm, values in totals.items()}
    summary["per_case"] = per_case
    (args.out_dir / "facet_scores.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="mode", required=True)
    p = sub.add_parser("packet")
    p.add_argument("--a", type=Path, required=True)
    p.add_argument("--b", type=Path, required=True)
    p.add_argument("--c", type=Path, help="可选：取证循环组（M5）")
    p.add_argument("--out-dir", type=Path, required=True)
    p.add_argument("--seed", type=int, default=20261012)
    s = sub.add_parser("score")
    s.add_argument("--reviews", type=Path, required=True)
    s.add_argument("--out-dir", type=Path, required=True)
    args = parser.parse_args()
    packet(args) if args.mode == "packet" else score(args)


if __name__ == "__main__":
    main()
