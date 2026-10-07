"""Deterministic numeric checks on an answer's text; no reviewer extraction or model call.

check_answer() reports, for each gold fact, whether the answer states that value (any of
百万/亿/万亿/million/billion forms, percent for ratios, judged by the displayed precision),
plus two kinds of self-contradiction the answer writes explicitly:
  - unit conversions such as "0.3亿美元，即约300百万美元" whose two sides differ;
  - stated arithmetic such as "978 / 1,127 = 86.79%" whose result is wrong at the shown precision.
Finding a value in the text does not prove it is attached to the right period; the reviewed
claim extraction in evolution_quality keeps that check.
"""
from __future__ import annotations

import argparse
import json
import re
from decimal import Decimal
from pathlib import Path

# Magnitude words → multiplier into the gold's "million" base.
MONEY_UNITS = {'万亿': Decimal(1000000), '千亿': Decimal(100000), '亿': Decimal(100), '千万': Decimal(10),
               '百万': Decimal(1), '万': Decimal('0.01'), 'billion': Decimal(1000), 'bn': Decimal(1000),
               'million': Decimal(1), 'mn': Decimal(1), 'b': Decimal(1000), 'm': Decimal(1)}
NUMBER = r'[+\-−]?\d{1,3}(?:,\d{3})+(?:\.\d+)?|[+\-−]?\d+(?:\.\d+)?'
UNIT = r'万亿|千亿|亿|千万|百万|万|billion|bn|million|mn|%|个百分点|倍|(?-i:B|M)(?![A-Za-z])'
TOKEN = re.compile(rf'(?<![\w.])({NUMBER})\s*({UNIT})?', re.IGNORECASE)
CONVERSION = re.compile(rf'({NUMBER})\s*({UNIT})[^。；;\n\d]{{0,12}}?(?:即|约|合|相当于|=)\s*约?\s*({NUMBER})\s*({UNIT})', re.IGNORECASE)
OPERAND = rf'[\(（]?\s*(?:{NUMBER})\s*[\)）]?'
# 结果后若紧跟运算符（如 "A − (B+C) = A − D = E" 的中间项），它是下一个算式的开头，不是结果。
EQUATION = re.compile(rf'({OPERAND}(?:\s*[+\-−×*/÷]\s*{OPERAND})+)\s*([=≈])\s*({NUMBER})\s*(%)?(?![\d,.%]|[ \t]*[+\-−×*/÷][ \t]*[\d(（])')
OPERATORS = {'×': '*', '÷': '/', '−': '-', '（': '(', '）': ')'}


def number(text: str) -> Decimal:
    return Decimal(text.replace(',', '').replace('−', '-'))


def half_step(text: str) -> Decimal:
    """Half of the last displayed digit: 86.79 → 0.005, 1,127 → 0.5."""
    digits = text.split('.')[1] if '.' in text else ''
    return Decimal(5) / Decimal(10) ** (len(digits) + 1)


def normalize(answer: str) -> str:
    """去掉 Markdown 加粗和货币符号（"−$4,467M" 的负号与数字之间隔着 $），统一全角与 LaTeX 转义的百分号。"""
    text = re.sub(r'\[E\d+\]', '', answer or '')  # 引用标记里的数字会打断金额换算的匹配
    return text.replace('**', '').replace('__', '').replace('US$', '').replace('$', '').replace('\\%', '%').replace('％', '%')


def tokens(answer: str) -> list[dict]:
    plain = normalize(answer)
    rows = []
    for match in TOKEN.finditer(plain):
        raw, unit = match.group(1), (match.group(2) or '').lower()
        rows.append({'raw': raw, 'unit': unit, 'value': number(raw), 'step': half_step(raw)})
    return rows


def matches(fact: dict, row: dict) -> bool:
    unit, target = fact['unit'], Decimal(fact['value'])
    if unit == 'million':
        if row['unit'] in {'%', '个百分点', '倍'}:
            return False
        scale = MONEY_UNITS.get(row['unit'], Decimal(1))
        return abs(row['value'] * scale - target) <= row['step'] * scale + Decimal('1e-9')
    if unit in {'percent', 'percentagePoint'}:
        return row['unit'] in {'%', '个百分点'} and abs(row['value'] - target) <= row['step'] + Decimal('1e-9')
    if unit == 'ratio':
        # 整数（如编号 "3."）的舍入步长是 0.5，会误配比率；比率必须带小数或显式单位。
        if row['unit'] == '' and '.' not in row['raw']:
            return False
        if row['unit'] == '%':
            return abs(row['value'] / 100 - target) <= row['step'] / 100 + Decimal('1e-9')
        return row['unit'] in {'', '倍'} and abs(row['value'] - target) <= row['step'] + Decimal('1e-9')
    return row['unit'] == '' and abs(row['value'] - target) <= row['step'] + Decimal('1e-9')


def conversion_issues(plain: str) -> list[str]:
    issues = []
    for match in CONVERSION.finditer(plain):
        left, left_unit, right, right_unit = match.groups()
        left_unit, right_unit = left_unit.lower(), right_unit.lower()
        if left_unit not in MONEY_UNITS or right_unit not in MONEY_UNITS or left_unit == right_unit:
            continue
        a, b = number(left) * MONEY_UNITS[left_unit], number(right) * MONEY_UNITS[right_unit]
        tolerance = half_step(left) * MONEY_UNITS[left_unit] + half_step(right) * MONEY_UNITS[right_unit]
        if abs(a - b) > tolerance:
            issues.append(f'UNIT_CONVERSION_MISMATCH: "{match.group(0).strip()}" ({a} vs {b} million)')
    return issues


def evaluate(expression: str) -> Decimal | None:
    """Evaluate a +−×÷ chain of displayed numbers with normal precedence; None if malformed."""
    for symbol, python in OPERATORS.items():
        expression = expression.replace(symbol, python)
    expression = expression.strip()
    # 句中括号可能只包住算式的一端，如 "（978 / 1,127 = 86.79%）"；去掉两端不成对的括号。
    while expression.startswith('(') and expression.count('(') > expression.count(')'):
        expression = expression[1:].strip()
    while expression.endswith(')') and expression.count(')') > expression.count('('):
        expression = expression[:-1].strip()
    if expression.count('(') != expression.count(')'):
        return None
    source = re.sub(r'\d[\d,]*(?:\.\d+)?', lambda m: f"Decimal('{m.group(0).replace(',', '')}')", expression)
    try:
        return eval(source, {'__builtins__': {}, 'Decimal': Decimal})  # 输入已由正则限定为数字、四则运算符和括号
    except (ArithmeticError, SyntaxError, TypeError):
        return None


def arithmetic_issues(plain: str) -> list[str]:
    issues = []
    for match in EQUATION.finditer(plain):
        chain, relation, result, percent = match.groups()
        value = evaluate(chain)
        if value is None:
            continue
        if percent:
            value *= 100
        shown = number(result)
        # "≈" 表示作者自己声明的近似，允许 1% 相对误差；"=" 只允许显示精度内的舍入。
        tolerance = half_step(result) + (abs(value) / 100 if relation == '≈' else 0) + Decimal('1e-9')
        if abs(value - shown) > tolerance:
            issues.append(f'ARITHMETIC_MISMATCH: "{match.group(0).strip()}" (computed {value.quantize(Decimal("0.0001"))})')
    return issues


def check_answer(answer: str, facts: list[dict]) -> dict:
    """facts: [{field, value, unit}] with unit million / ratio / percent / percentagePoint."""
    plain = normalize(answer)
    rows = tokens(plain)
    results = {fact['field']: 'FOUND' if any(matches(fact, row) for row in rows) else 'NOT_FOUND' for fact in facts}
    issues = conversion_issues(plain) + arithmetic_issues(plain)
    return {'schema': 'deterministic_numeric_check_v1', 'facts': results,
            'found': sum(value == 'FOUND' for value in results.values()), 'total': len(results), 'issues': issues}


def facts_from_answer_key(key: dict) -> list[dict]:
    """Adapter for answer keys with requiredFacts/derived maps (common-fundamentals and production drafts)."""
    unit = key.get('amountUnit', 'million')
    facts = [{'field': name, 'value': value, 'unit': unit} for name, value in key.get('requiredFacts', {}).items()]
    for name, value in key.get('derived', {}).items():
        facts.append({'field': name, 'value': value,
                      'unit': 'percent' if name.lower().endswith(('pct', 'percent')) else 'ratio' if 'ratio' in name.lower() else unit})
    return facts


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--answer', type=Path, required=True, help='UTF-8 answer text')
    parser.add_argument('--answer-key', type=Path, required=True, help='JSON with requiredFacts/derived, or a gold row with facts')
    args = parser.parse_args()
    key = json.loads(args.answer_key.read_text(encoding='utf-8'))
    facts = key['facts'] if 'facts' in key else facts_from_answer_key(key)
    print(json.dumps(check_answer(args.answer.read_text(encoding='utf-8'), facts), ensure_ascii=False, indent=2))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
