"""Numeric-only source matching, not a financial-fact or answer-quality gate.

Equations written in the answer are evaluated. Beyond them, a number counts as DERIVED only
when it is the difference, ratio or growth of two table cells the answer itself already cites
(same table, same row or same column), or of two traced numbers in the same answer sentence;
unrelated evidence numbers are never combined. Bare table cells take the table's declared
amount unit ("金额单位：百万", "$ in Millions"). Missing source text remains NO_DATA.
"""
from __future__ import annotations

import re
from decimal import Decimal

import evolution_numeric_check as numeric

EXCLUDE = re.compile(
    r"https?://[^\s<>\"'；;，。)]+|\[(?:E\d+|\d+)\]|\b(?:FY\s*\d{2,4}|Q[1-4]|[1-4]Q\d{2,4}|10-[KQ]|E\d+)\b"
    r"|\bCIK\s*\d+\b|\b\d{10}-\d{2}-\d{6}\b"
    r"|\b[a-f\d]{32,64}\b|\b[a-f\d]{8}(?:-[a-f\d]{4}){3}-[a-f\d]{12}\b"
    r"|\b(?:sh|sz)\.\d{6}\b|\b\d{4,6}\.(?:HK|SS|SZ)\b"
    r"|\b(?:19|20)\d{2}[-/]\d{1,2}(?:[-/]\d{1,2})?(?:T[\d:.]+Z?)?"
    r"|(?:19|20)\d{2}\s*年(?:\s*\d{1,2}\s*月(?:\s*\d{1,2}\s*[日号])?)?"
    r"|\d{1,2}\s*月\s*\d{1,2}\s*[日号]|\b\d{1,2}:\d{2}(?::\d{2})?\b"
    r"|第\s*\d+\s*[项章节条点]|(?m:^[ \t#>*-]*\d+[.、)](?=\s|[^\d]))",
    re.IGNORECASE,
)
CURRENCY = {"USD": "USD", "US$": "USD", "$": "USD", "美元": "USD", "美金": "USD",
            "HKD": "HKD", "HK$": "HKD", "港元": "HKD", "港币": "HKD",
            "CNY": "CNY", "RMB": "CNY", "人民币": "CNY", "元": "CNY", "¥": "CNY", "￥": "CNY",
            "EUR": "EUR", "€": "EUR", "欧元": "EUR", "GBP": "GBP", "£": "GBP", "英镑": "GBP"}
CURRENCY_RE = "|".join(re.escape(s) for s in sorted(CURRENCY, key=len, reverse=True))
COUNTS = re.compile(r"^(股|家|人|吨|辆|台|次|个|桶)")
UNIT_SUFFIX = re.compile(r"^(?:\s*)(bps|bp|基点|个百分点|倍)", re.IGNORECASE)
TOKEN = re.compile(rf"(?<![A-Za-z0-9_.])({numeric.NUMBER}|[+\-−]?\.\d+)\s*({numeric.UNIT})?", re.IGNORECASE)


def _plain(sentence: str) -> str:
    text = sentence.replace("**", "").replace("__", "").replace("\\%", "%").replace("％", "%")
    text = re.sub(r"([+\-−])\s*(US\$|HK\$|\$|￥|¥)", r"\2\1", text)
    return EXCLUDE.sub(lambda m: " " * len(m.group()), text)


def _numbers(text: str) -> tuple[list[dict], int]:
    rows, excluded = [], 0
    sentences = [sentence for sentence in re.split(r"(?<=[。！？；;!?])|\r?\n", text) if sentence.strip()]
    for index, sentence in enumerate(sentences):
        plain = _plain(sentence)
        excluded += sum(len(re.findall(r"\d+(?:\.\d+)?", m.group())) for m in EXCLUDE.finditer(sentence))
        for match in TOKEN.finditer(plain):
            raw, unit = match.group(1), (match.group(2) or "").lower()
            tail = plain[match.end():].lstrip()
            extra = UNIT_SUFFIX.match(tail) if not unit else None
            if extra:
                unit = extra.group(1).lower()
            before = re.search(rf"({CURRENCY_RE})\s*$", plain[:match.start()], re.IGNORECASE)
            after = re.match(rf"({CURRENCY_RE})(?![A-Za-z])", tail, re.IGNORECASE)
            currency = next((CURRENCY.get(m.group(1).upper(), CURRENCY.get(m.group(1)))
                             for m in (before, after) if m), None)
            count = COUNTS.match(tail)
            scale = numeric.MONEY_UNITS.get(unit, Decimal(1))
            if count:
                kind, scale = "count:" + count.group(1), scale * (1000000 if unit else 1)
            elif unit in {"%", "个百分点", "bp", "bps", "基点"}:
                kind = "percent" if unit == "%" else "percentage_point"
                scale = Decimal("0.01") if unit in {"bp", "bps", "基点"} else Decimal(1)
            elif unit == "倍":
                kind, scale = "ratio", Decimal(1)
            elif unit in numeric.MONEY_UNITS or currency:
                kind = "amount"
                if not unit:
                    scale = Decimal("0.000001")
            else:
                kind, scale = "bare", Decimal(1)
            unknown = bool(not unit and not currency and re.match(r"(?:[A-Z]{1,5}\b|[eE][+\-]?\d|[兆千])", tail))
            in_equation = any(e.start(1) <= match.start() < e.end(1) for e in numeric.EQUATION.finditer(plain))
            unknown = unknown or not in_equation and bool(re.match(r"[-–～~]\s*\d", tail) or
                                      re.search(r"\d\s*[-–～~]\s*$", plain[:match.start()]) or
                                      raw.startswith("-") and re.search(r"\d$", plain[:match.start()]))
            rows.append({"raw": raw, "unit": unit, "value": numeric.number(raw), "scale": scale,
                         "kind": kind, "currency": currency, "unknown": unknown,
                         "text": match.group().strip() + (extra.group().strip() if extra else ""),
                         "sentence": sentence, "index": index, "plain": plain, "start": match.start(), "end": match.end()})
    return rows, excluded


def _same(answer: dict, source: dict) -> bool:
    if answer["unknown"] or source["unknown"] or answer["kind"] != source["kind"]:
        return False
    if answer["currency"] and source["currency"] and answer["currency"] != source["currency"]:
        return False
    # A rounded answer can match a more precise source, not acquire precision from a rounded source.
    return abs(answer["value"] * answer["scale"] - source["value"] * source["scale"]) <= (
        numeric.half_step(answer["raw"]) * answer["scale"] + Decimal("1e-9"))


def _calculation(row: dict, sources: list[dict]) -> tuple[str, list[str], str] | None:
    for equation in numeric.EQUATION.finditer(row["plain"]):
        is_result = equation.start(3) == row["start"]
        is_operand = equation.start(1) <= row["start"] < equation.end(1)
        if not is_result and not is_operand:
            continue
        chain, _, result, percent = equation.groups()
        value = numeric.evaluate(chain)
        if value is None:
            return ("INDETERMINATE", [], "INVALID_EXPLICIT_CALCULATION") if is_result else None
        inputs, _ = _numbers(chain)
        matched = []
        for item in inputs:
            # The conventional percentage scale is the only implicit numeric constant accepted here.
            before = chain[:item["start"]].rstrip()
            if percent and item["value"] == 100 and before.endswith(("*", "×")):
                continue
            found = next((source for source in sources if not source["unknown"]
                          and abs(item["value"] - source["value"]) <= numeric.half_step(item["raw"])), None)
            if found is None:
                return ("UNMATCHED", [], "EXPLICIT_OPERAND_NOT_FOUND") if is_result else None
            matched.append(found)
        units = {(item["kind"], item["scale"], item["currency"]) for item in matched}
        if len(units) != 1:
            return ("INDETERMINATE", [], "EXPLICIT_OPERAND_UNITS_DIFFER") if is_result else None
        if percent:
            if re.search(r"[/÷]", chain):
                if not re.search(r"[*×]\s*100\b", chain):
                    value *= 100
            elif matched and matched[0]["kind"] != "percent":
                return ("INDETERMINATE", [], "PERCENT_CALCULATION_UNITS_UNKNOWN") if is_result else None
        if is_operand:
            source = next(item for item in matched if abs(item["value"] - row["value"]) <= numeric.half_step(row["raw"]))
            return "DIRECT", [source["sentence"]], "EXPLICIT_OPERAND_NUMERIC_MATCH"
        if row["kind"] not in {"bare", "percent", "ratio"} and matched and (
                row["kind"] != matched[0]["kind"] or row["scale"] != matched[0]["scale"] or
                row["currency"] and matched[0]["currency"] and row["currency"] != matched[0]["currency"]):
            return "INDETERMINATE", [], "RESULT_UNIT_DIFFERS_FROM_OPERANDS"
        if matched[0]["kind"] != "bare" and re.search(r"[*×]", re.sub(r"[*×]\s*100\b", "", chain)):
            return "INDETERMINATE", [], "UNSUPPORTED_DIMENSIONAL_CALCULATION"
        if re.search(r"[/÷]", chain) and row["kind"] not in {"bare", "percent", "ratio"}:
            return "INDETERMINATE", [], "DIVISION_RESULT_UNIT_MISMATCH"
        if abs(value - numeric.number(result)) > numeric.half_step(result) + Decimal("1e-9"):
            return "UNMATCHED", [], "EXPLICIT_CALCULATION_MISMATCH"
        return "CALCULATED", list(dict.fromkeys(item["sentence"] for item in matched)), "EXPLICIT_CALCULATION"
    return None


UNIT_DECLARATION = re.compile(r"金额单位[:：]\s*(百万|亿|千|万)|单位[:：]\s*(百万|亿|千)|\$\s*in\s*(Millions|Thousands|Billions)",
                              re.IGNORECASE)
DECLARED_SCALE = {"百万": Decimal(1), "亿": Decimal(100), "千": Decimal("0.001"), "万": Decimal("0.01"),
                  "millions": Decimal(1), "thousands": Decimal("0.001"), "billions": Decimal(1000)}
# 这些行的数值不是金额，表头声明的金额单位不适用。
NON_AMOUNT_ROW = re.compile(r"每股|股数|EPS|per share|shares|率|比率|倍|%|ratio|margin", re.IGNORECASE)
SECTION_START = re.compile(r"^\[E\d+\]|\[\[STOCKSAGE_EVIDENCE_BEGIN\]\]")


def _tables(evidence: str) -> list[list[dict]]:
    """Table cells grouped by table; each cell carries every unit interpretation it supports."""
    tables, current, declared = [], [], None
    for line in evidence.splitlines():
        if SECTION_START.search(line):
            declared = None
        unit = UNIT_DECLARATION.search(line)
        if unit:
            declared = DECLARED_SCALE[next(group for group in unit.groups() if group).lower()]
        cells = [cell.strip() for cell in line.split("|")]
        if len(cells) < 3:
            if current:
                tables.append(current)
                current = []
            continue
        row = len({cell["row"] for cell in current})
        for column, text in enumerate(cells[1:], start=1):
            tokens = [token for token in _numbers(text)[0] if not token["unknown"]]
            if len(tokens) != 1:
                continue
            token = tokens[0]
            readings = [{"kind": token["kind"], "base": token["value"] * token["scale"], "currency": token["currency"]}]
            plain_amount = token["kind"] == "bare" or token["kind"] == "amount" and not token["unit"]
            if declared is not None and plain_amount and not NON_AMOUNT_ROW.search(cells[0]):
                readings.append({"kind": "amount", "base": token["value"] * declared, "currency": token["currency"]})
            current.append({"row": row, "column": column, "line": line.strip(), "readings": readings})
    if current:
        tables.append(current)
    return tables


def _base(row: dict) -> Decimal:
    return row["value"] * row["scale"]


def _compatible(answer: dict, kind: str, currency: str | None) -> bool:
    if answer["currency"] and currency and answer["currency"] != currency:
        return False
    return answer["kind"] == kind or kind == "bare" and answer["kind"] == "ratio"


def _derivations(a: dict, b: dict, series: bool = False) -> list[tuple[str, Decimal, str | None]]:
    """Difference, ratio and growth of two same-kind readings; signs are compared by magnitude.

    A series (one item over periods) yields only change and growth: a ratio of two periods of the same
    item is not a reported figure, and those near-1 values would match almost any small ratio.
    """
    if a["kind"] != b["kind"] or a["currency"] and b["currency"] and a["currency"] != b["currency"]:
        return []
    x, y, currency = a["base"], b["base"], a["currency"] or b["currency"]
    if a["kind"] == "percent":
        return [("percentage_point", x - y, None)]
    if a["kind"] not in {"amount", "bare"}:
        return []
    values = [(a["kind"], x - y, currency)]
    for top, bottom in ((x, y), (y, x)):
        if bottom:
            values.append(("percent", (top / bottom - 1) * 100, None))
            if not series:
                values += [("bare", top / bottom, None), ("percent", top / bottom * 100, None)]
    return values


def _derived_match(answer: dict, pairs, tolerance) -> list[str] | None:
    target = abs(_base(answer))
    for left, right, excerpts, series in pairs:
        for kind, value, currency in _derivations(left, right, series):
            if _compatible(answer, kind, currency) and abs(abs(value) - target) <= tolerance(answer, left, right):
                return excerpts
    return None


def _cell_match(answer: dict, reading: dict) -> bool:
    return (_compatible(answer, reading["kind"], reading["currency"]) and answer["kind"] != "ratio"
            and abs(_base(answer) - reading["base"]) <= numeric.half_step(answer["raw"]) * answer["scale"] + Decimal("1e-9"))


def check(answer: str, evidence: str) -> dict:
    """Return occurrence-level diagnostics; no evidence or no applicable values has a null rate.

    Bare table cells use only their own table's declared amount unit, never a header elsewhere.
    Source matching establishes only a numeric occurrence, never its company, period or meaning.
    """
    candidates, excluded = _numbers(answer)
    sources, _ = _numbers(evidence)
    tables = _tables(evidence)
    rows, kept = [], []
    for candidate in candidates:
        constant = any(equation.group(4) and equation.start(1) <= candidate["start"] < equation.end(1)
                       and candidate["value"] == 100 and candidate["plain"][:candidate["start"]].rstrip().endswith(("*", "×"))
                       for equation in numeric.EQUATION.finditer(candidate["plain"]))
        if constant:
            excluded += 1
            continue
        if not evidence.strip():
            status, excerpts, reason = "INDETERMINATE", [], "EVIDENCE_NOT_CAPTURED"
        elif candidate["unknown"]:
            status, excerpts, reason = "INDETERMINATE", [], "UNSUPPORTED_UNIT_OR_NOTATION"
        else:
            calculation = _calculation(candidate, sources)
            found = next((source for source in sources if _same(candidate, source)), None)
            cell = next((cell for table in tables for cell in table
                         if any(_cell_match(candidate, reading) for reading in cell["readings"])), None)
            if calculation:
                status, excerpts, reason = calculation
            elif found:
                status, excerpts, reason = "DIRECT", [found["sentence"]], "NUMERIC_MATCH_ONLY"
            elif cell:
                status, excerpts, reason = "DIRECT", [cell["line"]], "DECLARED_TABLE_UNIT_MATCH"
            elif any(source["value"] == candidate["value"] and source["kind"] != candidate["kind"]
                     and "bare" in {source["kind"], candidate["kind"]} for source in sources):
                status, excerpts, reason = "INDETERMINATE", [], "LOCAL_UNIT_MISSING"
            else:
                status, excerpts, reason = "UNMATCHED", [], "NO_NUMERIC_SOURCE_MATCH"
        rows.append({"text": candidate["text"], "sentence": candidate["sentence"], "status": status,
                     "source_excerpts": excerpts, "reason": reason})
        kept.append(candidate)
    _derive(kept, rows, tables)
    counts = {"applicable": len(rows), **{name: sum(row["status"] == name.upper() for row in rows)
                                         for name in ("direct", "calculated", "derived", "unmatched", "indeterminate")}}
    determinable = counts["applicable"] - counts["indeterminate"]
    status = "NOT_APPLICABLE" if not rows else "NO_DATA" if not evidence.strip() else "SCORED"
    return {"schema": "numeric_traceability_v2", "diagnostic_only": True, "status": status, "counts": counts,
            # INDETERMINATE numbers (unit or notation unknown) are reported, not counted as untraceable.
            "traceable_rate": (counts["direct"] + counts["calculated"] + counts["derived"]) / determinable
            if determinable and evidence.strip() else None, "numbers": rows, "excluded_count": excluded,
            "scope": "numeric_occurrences_only; company, period, field and financial basis are not verified"}


TRACED = {"DIRECT", "CALCULATED", "DERIVED"}
NEARBY = 2
OPEN = {("UNMATCHED", "NO_NUMERIC_SOURCE_MATCH"), ("INDETERMINATE", "LOCAL_UNIT_MISSING")}


def _tolerance(answer: dict, left: dict, right: dict) -> Decimal:
    """One displayed step of the answer, plus the rounding carried by rounded operands."""
    target = abs(_base(answer))
    absolute = left["error"] + right["error"]
    relative = target * sum(side["error"] / abs(side["base"]) for side in (left, right) if side["base"])
    return 2 * numeric.half_step(answer["raw"]) * answer["scale"] + max(absolute, relative) + Decimal("1e-9")


def _derive(candidates: list[dict], rows: list[dict], tables: list[list[dict]]) -> None:
    """Upgrade open rows whose value derives from cited table cells or traced numbers in the same sentence."""
    traced = [candidate for candidate, row in zip(candidates, rows) if row["status"] in TRACED]
    # Same row (one item over periods): one cited cell anywhere suffices. Same column (two items): both
    # cells must be cited within NEARBY answer sentences of the derived number; a column spans many items,
    # and pairs of cells cited far apart would match almost any small ratio.
    table_pairs = []
    for table in tables:
        cited = [{candidate["index"] for candidate in traced
                  if any(_cell_match(candidate, reading) for reading in cell["readings"])} for cell in table]
        for i, a in enumerate(table):
            for j in range(i + 1, len(table)):
                b = table[j]
                series = a["row"] == b["row"]
                if not (series and (cited[i] or cited[j]) or a["column"] == b["column"] and cited[i] and cited[j]):
                    continue
                excerpts = list(dict.fromkeys((a["line"], b["line"])))
                near = None if series else (cited[i], cited[j])
                table_pairs += [(dict(left, error=Decimal(0)), dict(right, error=Decimal(0)), excerpts, series, near)
                                for left in a["readings"] for right in b["readings"]]
    for candidate, row in zip(candidates, rows):
        if (row["status"], row["reason"]) in OPEN:
            usable = [pair[:4] for pair in table_pairs if pair[4] is None or all(
                any(abs(index - candidate["index"]) <= NEARBY for index in indices) for indices in pair[4])]
            excerpts = _derived_match(candidate, usable, _tolerance)
            if excerpts:
                row.update(status="DERIVED", source_excerpts=excerpts, reason="DERIVED_FROM_CITED_TABLE_CELLS")
    for _ in range(3):
        changed = False
        for candidate, row in zip(candidates, rows):
            if (row["status"], row["reason"]) not in OPEN:
                continue
            same = [{"kind": other["kind"], "base": _base(other), "currency": other["currency"],
                     "error": numeric.half_step(other["raw"]) * other["scale"]}
                    for other, other_row in zip(candidates, rows)
                    if other is not candidate and other["sentence"] == candidate["sentence"] and other_row["status"] in TRACED]
            pairs = [(a, b, [candidate["sentence"]], False) for i, a in enumerate(same) for b in same[i + 1:]]
            if _derived_match(candidate, pairs, _tolerance):
                row.update(status="DERIVED", source_excerpts=[candidate["sentence"]], reason="DERIVED_FROM_TRACED_ANSWER_VALUES")
                changed = True
        if not changed:
            break
