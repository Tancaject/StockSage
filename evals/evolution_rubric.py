"""Criteria for the two stages of a fundamentals-method evolution experiment."""
from __future__ import annotations

from eval_common import json_hash

SCHEMA = "fundamentals_evolution_rubric_v1"
DIMENSIONS = ("claim_support", "numeric_period_correctness", "counterevidence", "unknowns")
STAGES = {"analysis": ("FUNDAMENTALS_ANALYSIS", "fundamentals-analysis"),
          "finalAnswer": ("FINAL_ANSWER", "final-answer")}

# Dimension keys remain stable, but these requirements belong to this experiment,
# not to the ordinary-answer evaluator or to the model's own assessment.
STAGE_REQUIREMENTS = {
    "analysis": {
        "claim_support": "核查基本面分析稿的事实、额外主张及推断是否由该阶段实际可见证据支持；可接受有边界的一般机制，不将其当作公司已证实事实。",
        "numeric_period_correctness": "核查分析稿所用科目、金额、单位、期间、比较基数及派生计算，包括自行增加的合计；按本题 gold 的数值口径和用户问题范围判定。",
        "counterevidence": "核查分析稿是否保留实际证据中影响结论的相反信号及方法不适用条件；没有相关反向证据时不强造风险，也不要求未问的同行比较。",
        "unknowns": "核查分析方法是否把证据缺口与相应结论的强度对应起来；缺数据不能补成原因或保证，可计算且证据充分的内容也不应被误称未知。",
    },
    "finalAnswer": {
        "claim_support": "核查最终回答是否有实际可见证据支持，包括整合时新增的事实和因果；不能仅因分析稿曾这样表述就判为有据，也不能把分析中的可能性写成确定事实。",
        "numeric_period_correctness": "核查最终呈现的金额、科目、单位、期间与公式是否正确，是否在汇总时遗漏、误归类或改写；以最终答案和本题 gold 复核，不沿用分析稿分数。",
        "counterevidence": "核查最终判断是否保留与用户问题有关、足以改变结论的反向证据；允许压缩分析稿，但不能只留下有利部分，不要求照搬所有段落。",
        "unknowns": "核查最终答案是否保留实际影响判断的证据局限，避免把账面关系扩大为经营原因、未来结果或偿付保证；不以机械复述限制词或额外免责声明作为通过条件。",
    },
}


def rubric_hash() -> str:
    return json_hash({"schema": SCHEMA, "dimensions": DIMENSIONS, "stages": STAGES,
                      "requirements": STAGE_REQUIREMENTS})


def stage_rubric(stage_name: str) -> dict:
    scope, invocation_scope = STAGES[stage_name]
    return {"schema": SCHEMA, "sha256": rubric_hash(), "scope": scope,
            "invocationScope": invocation_scope, "dimensions": dict(STAGE_REQUIREMENTS[stage_name])}
