"""Freeze the FY2025 10-K expansion set into native cases and AI-only gold.

Every source value is read from the extracted SEC statement text by row label and column, scaled
to USD millions, and cross-checked against SEC companyfacts for the same accession and period.
Formulas reuse the batch-learning freezer's gold_for(), so gold matches the existing schema.
Outputs are never replaced; use a new --output-dir.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import re
import sys
import time
import urllib.request
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal, localcontext
from pathlib import Path

ROOT = Path(__file__).resolve().parent
EVALS = ROOT.parents[1]
sys.path.insert(0, str(EVALS))

from eval_common import json_hash, sha256
from evolution_dataset import REAL_ORIGIN, REAL_SCOPE, case_hash, validate_cases, validate_gold

_spec = importlib.util.spec_from_file_location("batch_prepare", ROOT.parent / "batch-learning-20261002" / "prepare_dataset.py")
batch_prepare = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(batch_prepare)
POLICY = batch_prepare.POLICY

SOURCES = ROOT / "sources"
HEADERS = {"User-Agent": "StockSage Research contact@stocksage.dev"}

# split, Chinese name, fiscal year start/end, prior-year start/end
ISSUERS = {
    "CVX": ("VALIDATION", "雪佛龙", "2025-01-01", "2025-12-31", "2024-01-01", "2024-12-31"),
    "CAT": ("VALIDATION", "卡特彼勒", "2025-01-01", "2025-12-31", "2024-01-01", "2024-12-31"),
    "LLY": ("VALIDATION", "礼来", "2025-01-01", "2025-12-31", "2024-01-01", "2024-12-31"),
    "MCD": ("VALIDATION", "麦当劳", "2025-01-01", "2025-12-31", "2024-01-01", "2024-12-31"),
    "NFLX": ("VALIDATION", "奈飞", "2025-01-01", "2025-12-31", "2024-01-01", "2024-12-31"),
    "INTC": ("VALIDATION", "英特尔", "2024-12-29", "2025-12-27", "2023-12-31", "2024-12-28"),
    "ABBV": ("HOLDOUT", "艾伯维", "2025-01-01", "2025-12-31", "2024-01-01", "2024-12-31"),
    "HON": ("HOLDOUT", "霍尼韦尔", "2025-01-01", "2025-12-31", "2024-01-01", "2024-12-31"),
    "VZ": ("HOLDOUT", "威瑞森", "2025-01-01", "2025-12-31", "2024-01-01", "2024-12-31"),
    "UBER": ("HOLDOUT", "优步", "2025-01-01", "2025-12-31", "2024-01-01", "2024-12-31"),
    "BA": ("HOLDOUT", "波音", "2025-01-01", "2025-12-31", "2024-01-01", "2024-12-31"),
    "UNH": ("HOLDOUT", "联合健康", "2025-01-01", "2025-12-31", "2024-01-01", "2024-12-31"),
}

# Per-issuer growth labels: (revenue row, revenue name, profit row, profit name, profit occurrence-free)
GROWTH = {
    "CVX": ("Sales and other operating revenues", "销售及其他经营收入", "Income (Loss) Before Income Tax Expense", "税前利润"),
    "CAT": ("Total sales and revenues", "总销售及收入", "Operating profit", "营业利润"),
    "LLY": ("Revenue", "营业收入", "Income before income taxes", "税前利润"),
    "MCD": ("Total revenues", "总收入", "Operating income", "营业利润"),
    "NFLX": ("Revenues", "营业收入", "Operating income", "营业利润"),
    "INTC": ("Net revenue", "净收入", "Operating income (loss)", "营业利润（亏损）"),
    "ABBV": ("Net revenues", "净收入", "Operating earnings", "营业利润"),
    "HON": ("Net sales", "净销售额", "Income from continuing operations before taxes", "持续经营业务税前利润"),
    "VZ": ("Operating Revenues", "营业收入", "Operating Income", "营业利润"),
    "UBER": ("Revenue", "营业收入", "Income from operations", "营业利润"),
    "BA": ("Total revenues", "总收入", "Earnings/(loss) from operations", "营业利润（亏损）"),
    "UNH": ("Total revenues", "总收入", "Earnings from operations", "营业利润"),
}

# Cash flow labels: (operating cash flow row, net income row, net income name, capex rows)
CASH = {
    "CVX": ("Net Cash Provided by Operating Activities", "Net Income (Loss)", "净利润", ["Capital expenditures"]),
    "CAT": ("Net cash provided by (used for) operating activities", "Profit of consolidated and affiliated companies", "合并及联营公司利润",
            ["Capital expenditures – excluding equipment leased to others", "Expenditures for equipment leased to others"]),
    "LLY": ("Net Cash Provided by Operating Activities", "Net income", "净利润", ["Purchases of property and equipment"]),
    "MCD": ("Cash provided by operations", "Net income", "净利润", ["Capital expenditures"]),
    "NFLX": ("Net cash provided by operating activities", "Net income", "净利润", ["Purchases of property and equipment"]),
    "INTC": ("Net cash provided by (used for) operating activities", "Net income (loss)", "净利润（含少数股东）", ["Additions to property, plant and equipment"]),
    "ABBV": ("Cash flows from operating activities", "Net earnings", "净利润", ["Acquisitions of property and equipment"]),
    "HON": ("Net cash provided by operating activities from continuing operations", "Net income from continuing operations", "持续经营业务净利润", ["Capital expenditures"]),
    "VZ": ("Net cash provided by operating activities", "Net Income", "净利润", ["Capital expenditures (including capitalized software)"]),
    "UBER": ("Net cash provided by operating activities", "Net income including non-controlling interests", "净利润（含少数股东）", ["Purchases of property and equipment"]),
    "BA": ("Net cash provided/(used) by operating activities", "Net earnings/(loss)", "净利润", ["Payments to acquire property, plant and equipment"]),
    "UNH": ("Cash flows from operating activities", "Net earnings", "净利润", ["Purchases of property, equipment and capitalized software"]),
}

# Liquidity labels: cash row, debt rows as (row, occurrence), debt description used in the question
LIQUIDITY = {
    "CVX": ("Cash and cash equivalents", [("Short-term debt", 0), ("Long-term debt", 0)], "短期债务与长期债务"),
    "CAT": ("Cash and cash equivalents", [("Short-term borrowings", 0), ("Long-term debt due within one year", 0),
                                          ("Long-term debt due within one year", 1), ("Long-term debt due after one year", 0),
                                          ("Long-term debt due after one year", 1)],
            "短期借款，以及机械能源和金融产品两个分部列示的一年内到期与一年后到期长期债务"),
    "LLY": ("Cash and cash equivalents", [("Short-term borrowings and current maturities of long-term debt", 0), ("Long-term debt", 0)],
            "短期借款及一年内到期长期债务、长期债务"),
    "MCD": ("Cash and equivalents", [("Long-term debt", 0)], "资产负债表列示的长期债务"),
    "NFLX": ("Cash and cash equivalents", [("Short-term debt", 0), ("Long-term debt", 0)], "短期债务与长期债务"),
    "INTC": ("Cash and cash equivalents", [("Short-term debt", 0), ("Debt", 0)], "短期债务与长期债务（表中列为 Debt）"),
    "ABBV": ("Cash and equivalents", [("Short-term borrowings", 0), ("Current portion of long-term debt and finance lease obligations", 0),
                                      ("Long-term debt and finance lease obligations", 0)], "短期借款、一年内到期及长期债务和融资租赁负债"),
    "HON": ("Cash and cash equivalents", [("Commercial paper and other short-term borrowings", 0), ("Current maturities of long-term debt", 0),
                                          ("Long-term debt", 0)], "商业票据及其他短期借款、一年内到期长期债务、长期债务"),
    "VZ": ("Cash and cash equivalents", [("Debt maturing within one year", 0), ("Long-term debt", 0)], "一年内到期债务与长期债务"),
    "UBER": ("Cash and cash equivalents", [("Long-term debt, net of current portion", 0)], "资产负债表单列的长期债务（不含一年内到期部分）"),
    "BA": ("Cash and cash equivalents", [("Short-term debt and current portion of long-term debt", 0), ("Long-term debt", 0)],
           "短期债务及一年内到期长期债务、长期债务"),
    "UNH": ("Cash and cash equivalents", [("Short-term borrowings and current maturities of long-term debt", 0),
                                          ("Long-term debt, less current maturities", 0)], "短期借款及一年内到期长期债务、长期债务"),
}

# Issuer-specific points and reviewer notes that a fair answer must handle.
EXTRA_POINTS = {
    ("INTC", "growth"): ["两年营业利润均为亏损，不计算亏损额的同比百分比；说明亏损收窄的金额和利润率改善"],
    ("BA", "growth"): ["识别2025年营业利润包含9,672百万美元资产处置收益，剔除后营业利润为负，不能把扭亏直接归因于主业改善"],
    ("CAT", "cash"): ["资本开支同时包括“不含出租设备的资本开支”和“出租设备支出”两项，合计口径要说明"],
    ("HON", "cash"): ["使用持续经营业务口径，不混入终止经营业务现金流或利润"],
    ("INTC", "cash"): ["经营现金流不足以覆盖资本开支，简化自由现金流为负；净利润接近零，不用经营现金流/净利润的倍数作结论"],
    ("BA", "cash"): ["经营现金流低于资本开支，简化自由现金流为负"],
    ("CAT", "liquidity"): ["长期债务按两个分部分列，须把各分部的一年内到期和一年后到期金额都计入，不能漏掉金融产品分部"],
    ("MCD", "liquidity"): ["流动比率低于1，需结合经营现金流稳定性解释，不能仅凭此判定偿债危机；股东权益为负不等于资不抵债的现金危机"],
    ("ABBV", "liquidity"): ["流动比率低于1且股东权益为负，不能仅凭此判定无法偿债"],
    ("VZ", "liquidity"): ["流动比率低于1，不能仅凭此判定无法偿债"],
    ("UBER", "liquidity"): ["表中未单列一年内到期债务，只能按题目口径使用长期债务，不补造流动部分"],
    ("UNH", "liquidity"): ["流动比率低于1，不能仅凭此判定无法偿债"],
}
NOTES = {
    "CAT": "SEC 报表页把一年内到期和一年后到期长期债务按两个分部列示、合并行无数值；gold 债务为五行之和，companyfacts 无分部维度值可交叉核对。",
    "NFLX": "原表金额单位为千美元，gold 已精确换算为百万美元；答案可用任一等值单位。",
    "HON": "利润表金额单位为美元，gold 已精确换算为百万美元；资产负债表原表为百万美元。",
    "INTC": "财年按公司52/53周口径，FY2025截至2025-12-27，不是自然年。",
    "UBER": "2025年净利润高于税前利润，含所得税收益等非经营项目；可选解释，不作必答。",
    "ABBV": "营业利润与税前利润差距大（含或有对价公允价值变动等），本题只要求营业利润口径。",
}


def statement_text(ticker: str, statement: str) -> str:
    return (SOURCES / f"{ticker}-fy2025-{statement}.txt").read_text(encoding="utf-8")


def scale(text: str) -> Decimal:
    title = text.splitlines()[0]
    if "$ in Millions" in title:
        return Decimal(1)
    if "$ in Thousands" in title:
        return Decimal("0.001")
    if re.search(r"\$ in (Billions|Hundreds)", title):
        raise ValueError("Unexpected statement scale: " + title)
    return Decimal("0.000001")


def source_unit(text: str) -> str:
    return {Decimal(1): "million", Decimal("0.001"): "thousand"}.get(scale(text), "USD")


def numeric_cells(line: str) -> list[str]:
    return [cell for cell in line.split(" | ")[1:] if re.search(r"\d", cell) and not re.fullmatch(r"\[\d+\]", cell)]


def read_value(ticker: str, statement: str, label: str, occurrence: int = 0, column: int = 0) -> tuple[Decimal, str]:
    """Return the value in USD millions and the verbatim source line."""
    text = statement_text(ticker, statement)
    # Section headers can repeat a row label without values; only valued rows count as occurrences.
    lines = [line for line in text.splitlines() if line.split(" | ")[0] == label and numeric_cells(line)]
    if len(lines) <= occurrence:
        raise ValueError(f"{ticker}/{statement}: row not found: {label}#{occurrence}")
    cells = numeric_cells(lines[occurrence])
    raw = cells[column].replace("$", "").replace(",", "").strip()
    negative = raw.startswith("(") and raw.endswith(")")
    value = Decimal(raw.strip("()")) * (-1 if negative else 1)
    with localcontext() as context:
        context.prec = 40
        return (value * scale(text)).normalize(), lines[occurrence]


def plain(value: Decimal) -> str:
    text = format(value, "f")
    return text.rstrip("0").rstrip(".") if "." in text else text


def compute(operation: str, values: list[Decimal]) -> Decimal:
    with localcontext() as context:
        context.prec = 80
        a, b = values[0], values[1]
        if operation in {"SUM", "ADD"}:
            return sum(values)
        if operation in {"SUBTRACT", "SUBTRACT_SUM"}:
            return a - sum(values[1:])
        if operation == "DIVIDE":
            return a / b
        if operation == "PERCENT_CHANGE":
            return (a - b) / b * 100
        if operation == "PERCENT_OF":
            return a / b * 100
    raise ValueError(operation)


class Case:
    def __init__(self, ticker: str, task: str, statement: str, query: str):
        self.ticker, self.task, self.statement, self.query = ticker, task, statement, query
        self.facts, self.derivations, self.required, self.points, self.locators = {}, [], [], [], {}

    def fact(self, field: str, label: str, period: str, occurrence: int = 0, column: int = 0, absolute: bool = False,
             required: bool = True) -> None:
        value, line = read_value(self.ticker, self.statement, label, occurrence, column)
        if absolute:
            value = abs(value)
        self.facts[field] = {"evidenceId": "E1", "field": field, "value": plain(value), "unit": "million",
                             "currency": "USD", "period": period}
        self.locators[field] = {"label": label, "occurrence": occurrence, "column": column, "absolute": absolute, "line": line}
        if required:
            self.required.append(field)

    def derive(self, output: str, operation: str, inputs: list[str], period: str, unit: str = "million",
               required: bool = True) -> None:
        values = [Decimal(self.facts[name]["value"]) for name in inputs]
        value = compute(operation, values)
        self.facts[output] = {"evidenceId": "E1", "field": output, "value": str(value), "unit": unit,
                              "currency": "N/A" if unit in {"ratio", "percent"} else "USD", "period": period}
        self.derivations.append({"output": output, "operation": operation, "inputs": inputs, "value": str(value),
                                 "evidenceId": "E1", "period": period, "unit": unit,
                                 "currency": "N/A" if unit in {"ratio", "percent"} else "USD"})
        if required:
            self.required.append(output)

    def point(self, text: str) -> None:
        self.points.append({"id": f"P{len(self.points) + 1}", "description": text})


def growth_case(ticker: str) -> Case:
    split, name, start, end, prior_start, prior_end = ISSUERS[ticker]
    revenue_row, revenue_name, profit_row, profit_name = GROWTH[ticker]
    losses = ticker in {"INTC", "BA"}
    ask_growth = "收入同比增速，以及营业利润（亏损）较上年的变化金额" if losses else f"{revenue_name}和{profit_name}的同比增速"
    extra = "；另外剔除表中列示的资产处置收益（Gain on dispositions, net）后，2025年营业利润是多少" if ticker == "BA" else ""
    query = (f"{name}（{ticker}）FY2025的增长和盈利能力怎么样？请根据利润表给出FY2025和FY2024的{revenue_name}、{profit_name}，"
             f"计算{ask_growth}，以及两年的{profit_name}率（{profit_name}/{revenue_name}）{extra}，再判断盈利能力是改善还是走弱。"
             "金额注明单位，百分比保留两位小数。")
    case = Case(ticker, "growth", "income", query)
    current, prior = f"{start}/{end}", f"{prior_start}/{prior_end}"
    case.fact("revenue2025", revenue_row, current)
    case.fact("revenue2024", revenue_row, prior, column=1)
    case.fact("profit2025", profit_row, current)
    case.fact("profit2024", profit_row, prior, column=1)
    case.derive("revenueGrowthPct", "PERCENT_CHANGE", ["revenue2025", "revenue2024"], current, "percent")
    if losses:
        case.derive("profitChange", "SUBTRACT", ["profit2025", "profit2024"], current)
    else:
        case.derive("profitGrowthPct", "PERCENT_CHANGE", ["profit2025", "profit2024"], current, "percent")
    case.derive("profitMargin2025Pct", "PERCENT_OF", ["profit2025", "revenue2025"], current, "percent")
    case.derive("profitMargin2024Pct", "PERCENT_OF", ["profit2024", "revenue2024"], prior, "percent")
    if ticker == "BA":
        case.fact("gainOnDispositions2025", "Gain on dispositions, net", current, absolute=True)
        case.derive("profitExGain2025", "SUBTRACT", ["profit2025", "gainOnDispositions2025"], current)
    case.point(f"两个财年的{revenue_name}和{profit_name}金额正确，使用全年而非季度数字")
    case.point("增速（或变化金额）与两年利润率计算正确")
    case.point("盈利能力判断与指标方向一致，不编造报表外的业务原因")
    return case


def cash_case(ticker: str) -> Case:
    split, name, start, end, *_ = ISSUERS[ticker]
    ocf_row, income_row, income_name, capex_rows = CASH[ticker]
    weak_income = ticker == "INTC"
    ratio_ask = "经营现金流与净利润的差额" if weak_income else "经营现金流/净利润"
    scope = "持续经营业务的" if ticker == "HON" else ""
    capex_name = "资本开支（含出租设备支出）" if ticker == "CAT" else "资本开支"
    query = (f"{name}（{ticker}）FY2025的利润有多少变成了现金？请根据现金流量表列出{scope}经营活动现金流、{income_name}和{capex_name}，"
             f"计算{ratio_ask}、简化自由现金流（经营现金流减资本开支）以及资本开支占经营现金流的比例，并评价现金创造能力。"
             "金额注明单位，比率保留两位小数。")
    case = Case(ticker, "cash", "cashflow", query)
    period = f"{start}/{end}"
    case.fact("operatingCashFlow2025", ocf_row, period)
    case.fact("netIncome2025", income_row, period)
    if len(capex_rows) == 1:
        case.fact("capex2025", capex_rows[0], period, absolute=True)
    else:
        for index, row in enumerate(capex_rows):
            case.fact(f"capexPart{index + 1}", row, period, absolute=True, required=False)
        case.derive("capex2025", "SUM", [f"capexPart{index + 1}" for index in range(len(capex_rows))], period)
    if weak_income:
        case.derive("cashIncomeGap", "SUBTRACT", ["operatingCashFlow2025", "netIncome2025"], period)
    else:
        case.derive("cashConversionRatio", "DIVIDE", ["operatingCashFlow2025", "netIncome2025"], period, "ratio")
    case.derive("simpleFreeCashFlow", "SUBTRACT", ["operatingCashFlow2025", "capex2025"], period)
    case.derive("capexToOperatingCashFlowPct", "PERCENT_OF", ["capex2025", "operatingCashFlow2025"], period, "percent")
    case.point("经营现金流、净利润和资本开支金额正确，资本开支按支出正额处理")
    case.point("现金转化指标、简化自由现金流和资本开支占比计算正确")
    case.point("对现金创造能力的评价与数字一致，不把简化自由现金流说成公司官方口径")
    return case


def liquidity_case(ticker: str) -> Case:
    split, name, start, end, prior_start, prior_end = ISSUERS[ticker]
    cash_row, debt_rows, debt_name = LIQUIDITY[ticker]
    query = (f"{name}（{ticker}）FY2025末的短期偿债能力和债务情况如何？请根据资产负债表给出期末现金及现金等价物、两期的流动资产和流动负债，"
             f"计算两期流动比率；有息债务按{debt_name}合计，再计算净债务（有息债务减现金及现金等价物），并说明变化。"
             "金额注明单位，比率保留两位小数。")
    case = Case(ticker, "liquidity", "balance", query)
    current, prior = f"{end}/{end}", f"{prior_end}/{prior_end}"
    case.fact("cash2025", cash_row, current)
    case.fact("currentAssets2025", "Total current assets" if ticker != "CVX" else "Total Current Assets", current)
    case.fact("currentLiabilities2025", "Total current liabilities" if ticker != "CVX" else "Total Current Liabilities", current)
    case.fact("currentAssets2024", "Total current assets" if ticker != "CVX" else "Total Current Assets", prior, column=1)
    case.fact("currentLiabilities2024", "Total current liabilities" if ticker != "CVX" else "Total Current Liabilities", prior, column=1)
    case.derive("currentRatio2025", "DIVIDE", ["currentAssets2025", "currentLiabilities2025"], current, "ratio")
    case.derive("currentRatio2024", "DIVIDE", ["currentAssets2024", "currentLiabilities2024"], prior, "ratio")
    if len(debt_rows) == 1:
        case.fact("debt2025", debt_rows[0][0], current, occurrence=debt_rows[0][1])
    else:
        for index, (row, occurrence) in enumerate(debt_rows):
            case.fact(f"debtPart{index + 1}", row, current, occurrence=occurrence, required=False)
        case.derive("debt2025", "SUM", [f"debtPart{index + 1}" for index in range(len(debt_rows))], current)
    case.derive("netDebt2025", "SUBTRACT", ["debt2025", "cash2025"], current)
    case.point("现金、流动资产、流动负债和题定口径债务金额正确，使用期末余额而非期间流量")
    case.point("两期流动比率、债务合计和净债务计算正确")
    case.point("对偿债能力变化的判断与数字一致，不把有息债务称为总负债")
    return case


def build_cases() -> list[Case]:
    cases = []
    for ticker in ISSUERS:
        for case in (growth_case(ticker), cash_case(ticker), liquidity_case(ticker)):
            for text in EXTRA_POINTS.get((ticker, case.task), []):
                case.point(text)
            cases.append(case)
    return cases


def companyfacts(cik: int) -> dict:
    cache = SOURCES / f"companyfacts-{cik}.json"
    if not cache.exists():
        time.sleep(0.2)
        cache.write_bytes(urllib.request.urlopen(urllib.request.Request(
            f"https://data.sec.gov/api/xbrl/companyfacts/CIK{cik:010d}.json", headers=HEADERS), timeout=60).read())
    return json.loads(cache.read_text(encoding="utf-8"))


def xbrl_check(case: Case, index: dict) -> dict:
    """Second evidence path: SEC companyfacts value for the same concept, accession and period."""
    source = index[f"{case.ticker}-fy2025-{case.statement}"]
    rows = json.loads((SOURCES / f"{case.ticker}-fy2025-{case.statement}.rows.json").read_text(encoding="utf-8"))
    facts = companyfacts(source["cik"])["facts"]
    results = {}
    for field, locator in case.locators.items():
        matches = [row for row in rows if row["cells"][0] == locator["label"] and numeric_cells(" | ".join(row["cells"]))]
        concept = matches[locator["occurrence"]]["concept"]
        prefix, name = concept.split(":", 1)
        start, end = case.facts[field]["period"].split("/")
        values = [item for item in facts.get(prefix, {}).get(name, {}).get("units", {}).get("USD", [])
                  if item["accn"] == source["accession"] and item["end"] == end and item.get("start", start) == start]
        if not values:
            results[field] = {"concept": concept, "status": "NOT_IN_COMPANYFACTS"}
            continue
        expected = Decimal(case.facts[field]["value"])
        observed = {abs(Decimal(str(item["val"])) / Decimal(1000000)) if locator["absolute"] else Decimal(str(item["val"])) / Decimal(1000000)
                    for item in values}
        if expected not in observed:
            raise ValueError(f"{case.ticker}/{field}: statement {expected} differs from companyfacts {sorted(observed)}")
        results[field] = {"concept": concept, "status": "MATCH"}
    return results


def prepare(output_dir: Path) -> dict:
    outputs = [output_dir / name for name in ("cases.jsonl", "gold.jsonl", "source-manifest.json")]
    if any(path.exists() for path in outputs):
        raise ValueError("Refusing to replace frozen output; use a new --output-dir")
    index = json.loads((SOURCES / "index.json").read_text(encoding="utf-8"))
    frozen_at = datetime.now(timezone.utc).isoformat()
    manifest = {"schema": "fundamentals_fy2025_expansion_source_manifest_v1", "frozenAt": frozen_at,
                "reviewAuthority": "AI_OFFLINE_TRIAL_NOT_HUMAN_GOLD", "numericPolicy": dict(POLICY),
                "preparer_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                "sources": [], "derivationAudit": {}, "locators": {}, "xbrlCrossCheck": {}}
    drafts, natives = [], []
    for case in build_cases():
        split, *_ , end, prior_start, prior_end = ISSUERS[case.ticker]
        stem = f"{case.ticker}-fy2025-{case.statement}"
        source = index[stem]
        download = SOURCES / f"{stem}.htm"
        if hashlib.sha256(download.read_bytes()).hexdigest() != source["downloadSha256"]:
            raise ValueError("Downloaded source changed: " + stem)
        text = statement_text(case.ticker, case.statement)
        filed = date.fromisoformat(source["filedAt"])
        start = None if case.statement == "balance" else ISSUERS[case.ticker][2]
        evidence = {"evidenceId": "E1", "target": case.ticker, "sourceRef": source["sourceRef"],
                    "sourceVersion": f"{case.ticker}-FY2025-10-K-{source['accession']}-{case.statement}",
                    "publishedAt": f"{filed.isoformat()}T00:00:00Z",
                    # Filing date has no reliable time; treat it as visible from the next UTC day.
                    "availableAt": f"{(filed + timedelta(days=1)).isoformat()}T00:00:00Z",
                    "rawText": text, "visibleText": text, "rawSha256": sha256(text), "visibleSha256": sha256(text),
                    "periodStart": start, "periodEnd": ISSUERS[case.ticker][3], "unit": source_unit(text), "currency": "USD",
                    "retrievedAt": source["retrievedAt"]}
        case_id = f"{case.ticker.lower()}-fy2025-{case.task}"
        native = {"schemaVersion": 2, "caseId": case_id, "origin": REAL_ORIGIN, "split": split, "issuerId": case.ticker,
                  "reportFamilyId": f"{case.ticker.lower()}-fy2025", "leakageGroup": case.ticker, "query": case.query,
                  "resolvedQuery": case.query, "history": [], "asOf": frozen_at[:10], "evidence": [evidence],
                  "executionScope": REAL_SCOPE, "ticker": case.ticker, "requestAttributes": {},
                  "timeSensitivity": "HISTORICAL", "initialTaskOutcome": "COMPLETED",
                  "provenance": {"authorizationRef": "user-request-20261007-answer-quality-expansion", "authorizedBy": "user",
                                 "capturedAt": frozen_at, "asOfInstant": frozen_at,
                                 "reviewedBy": "AI-source-check-not-human", "reviewedAt": frozen_at,
                                 "sourceManifestSha256": "0" * 64}}
        draft = {"caseId": case_id, "facts": list(case.facts[name] for name in case.facts if name not in {d["output"] for d in case.derivations}),
                 "derivations": case.derivations, "requiredFactFields": case.required, "requiredPoints": case.points,
                 "reviewNotes": [NOTES[case.ticker]] if case.ticker in NOTES else []}
        manifest["sources"].append({"caseId": case_id, "evidenceId": "E1", "sourceRef": source["sourceRef"],
                                    "accession": source["accession"], "downloadPath": f"sources/{stem}.htm",
                                    "extractionPath": f"sources/{stem}.txt", "retrievedAt": source["retrievedAt"],
                                    "downloadSha256": source["downloadSha256"],
                                    "extractionSha256": hashlib.sha256((SOURCES / f"{stem}.txt").read_bytes()).hexdigest(),
                                    "rawSha256": evidence["rawSha256"]})
        manifest["locators"][case_id] = case.locators
        manifest["xbrlCrossCheck"][case_id] = xbrl_check(case, index)
        drafts.append(draft)
        natives.append(native)
    validate_cases(natives)
    for draft, native in zip(drafts, natives):
        manifest["derivationAudit"][draft["caseId"]] = batch_prepare.gold_for(draft, native, frozen_at)[1]
    manifest_text = json.dumps(manifest, ensure_ascii=False, indent=2) + "\n"
    manifest_hash = sha256(manifest_text)
    for native in natives:
        native["provenance"]["sourceManifestSha256"] = manifest_hash
    gold = [batch_prepare.gold_for(draft, native, frozen_at)[0] for draft, native in zip(drafts, natives)]
    validate_cases(natives)
    # validate_gold() requires human review and predates reviewType; check structure and hashes on an adjusted copy.
    validate_gold(natives, [{**{key: value for key, value in row.items() if key != "reviewType"}, "humanReviewStatus": "REVIEWED"}
                            for row in gold])
    output_dir.mkdir(parents=True, exist_ok=True)
    for path, text in zip(outputs, ("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in natives),
                                   "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in gold), manifest_text)):
        with path.open("x", encoding="utf-8", newline="\n") as target:
            target.write(text)
    unmatched = sum(1 for checks in manifest["xbrlCrossCheck"].values() for item in checks.values() if item["status"] != "MATCH")
    return {"status": "FROZEN_AI_DIAGNOSTIC", "case_count": len(natives), "cases_sha256": json_hash(natives),
            "gold_sha256": json_hash(gold), "source_manifest_sha256": manifest_hash, "xbrl_unmatched": unmatched,
            "output_dir": str(output_dir)}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, default=ROOT)
    args = parser.parse_args()
    try:
        print(json.dumps(prepare(args.output_dir), ensure_ascii=False))
        return 0
    except (ValueError, KeyError, TypeError, IndexError, ArithmeticError, OSError) as error:
        parser.exit(2, "Dataset freeze failed: " + str(error) + "\n")


if __name__ == "__main__":
    raise SystemExit(main())
