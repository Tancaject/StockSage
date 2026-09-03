"""为 StockSage 补充 LLM-judge 的 RAGAS 指标（faithfulness 等）。

本脚本不重新调用后端、不重跑检索，而是离线读取 run_rag_eval.py 产出的结果文件，
复用其中已采集的 question / answer / reference_answer / retrieved_contexts，
用 DashScope（百炼 OpenAI 兼容接口）作为判官模型计算回答级指标：

  - faithfulness                          回答是否被检索上下文支撑（反幻觉，仅需 LLM）
  - factual_correctness                   回答与参考答案的事实一致性（仅需 LLM）
  - context_recall (LLM judge)            参考答案所需证据是否被召回（仅需 LLM）
  - llm_context_precision_with_reference  召回上下文相对参考答案的相关性（仅需 LLM）
  - answer_relevancy (可选, --with-relevancy)  回答是否切题（需 embedding）

设计原则：
  * 非侵入——只新增本脚本，不改动 run_rag_eval.py / eval_summary.py。
  * 密钥单一来源——判官 key 优先读环境变量 DASHSCOPE_API_KEY，缺失时回落到后端的
    application-local.properties（spring.ai.dashscope.api-key），运行时即时读取、不落盘。
  * 不覆盖——RAGAS 指标统一加 ragas_ 前缀写回，保留原有启发式 context_recall/precision。

用法（无需手动设 key，脚本会自动从 application-local.properties 读）：
  python .\rag-eval\run_ragas_eval.py --dry-run        # 先验证数据装配，不花 token
  python .\rag-eval\run_ragas_eval.py --limit 5        # 小样本试跑
  python .\rag-eval\run_ragas_eval.py                  # 全量（默认取 results 最新一份）
"""

import argparse
import hashlib
import importlib.metadata
import json
import math
import os
import warnings
from datetime import datetime
from pathlib import Path

from eval_utils import (
    DEFAULT_PHOENIX_ENDPOINT,
    DEFAULT_PHOENIX_PROJECT,
    average_numeric_metrics,
    setup_phoenix,
)


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_RESULTS_DIR = ROOT / "rag-eval" / "results"
DEFAULT_LOCAL_PROPS = (
    ROOT / "stocksage-backend" / "src" / "main" / "resources" / "application-local.properties"
)
DASHSCOPE_API_KEY_PROPERTY = "spring.ai.dashscope.api-key"
DASHSCOPE_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"

# RAGAS LLM-judge 指标的验收门槛（与 RAG_EVALUATION.md 一致）。
RAGAS_GATES = {
    "ragas_faithfulness": 0.85,
    "ragas_factual_correctness": 0.80,
}


def latest_result_file():
    """默认选取 results 目录下最新的全量评测文件（排除已生成的 *_ragas.json）。"""
    candidates = sorted(
        path for path in DEFAULT_RESULTS_DIR.glob("rag_eval_*.json")
        if "_ragas" not in path.name
    )
    if not candidates:
        raise SystemExit(
            f"未找到结果文件：{DEFAULT_RESULTS_DIR}\\rag_eval_*.json，请先运行 run_rag_eval.py。"
        )
    return candidates[-1]


def load_result(path):
    with Path(path).open("r", encoding="utf-8") as handle:
        return json.load(handle)


def build_samples(cases, max_context_chars):
    """把结果文件中的 case 转成 RAGAS SingleTurnSample，并记录被跳过的用例。"""
    from ragas import SingleTurnSample

    samples = []
    scored_cases = []
    skipped = []
    for case in cases:
        contexts = [
            str(ctx.get("content", "")).strip()[:max_context_chars]
            for ctx in (case.get("retrieved_contexts") or [])
        ]
        contexts = [ctx for ctx in contexts if ctx]
        answer = str(case.get("answer") or "").strip()
        reference = str(case.get("reference_answer") or "").strip()
        question = str(case.get("question") or "").strip()

        # faithfulness 需要 response + retrieved_contexts；缺任一则无法评判，记录并跳过。
        if not answer or not contexts:
            skipped.append({"id": case.get("id", ""), "reason": "missing answer or contexts"})
            continue

        samples.append(SingleTurnSample(
            user_input=question,
            response=answer,
            retrieved_contexts=contexts,
            reference=reference,
        ))
        scored_cases.append(case)
    return samples, scored_cases, skipped


def resolve_api_key(local_props=None):
    """解析 DashScope key。优先环境变量；否则回落到后端的 application-local.properties。

    密钥在脚本运行时即时读取，不打印、不写入其他文件，沿用项目「密钥只存一份」的约定。
    """
    env_key = os.environ.get("DASHSCOPE_API_KEY")
    if env_key:
        return env_key

    path = Path(local_props) if local_props else DEFAULT_LOCAL_PROPS
    if path.exists():
        for raw in path.read_text(encoding="utf-8").splitlines():
            line = raw.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            name, _, value = line.partition("=")
            if name.strip() == DASHSCOPE_API_KEY_PROPERTY:
                value = value.strip()
                if value:
                    return value

    raise SystemExit(
        "未找到 DashScope key：\n"
        "  1) 环境变量 DASHSCOPE_API_KEY 未设置；\n"
        f"  2) 也没能从 {path} 读到 {DASHSCOPE_API_KEY_PROPERTY}。\n"
        "请确认 application-local.properties 存在该项，或用 --local-props 指定其它路径。"
    )


def build_judge_llm(model, api_key, timeout=300):
    from langchain_openai import ChatOpenAI
    from ragas.llms import LangchainLLMWrapper

    chat = ChatOpenAI(
        model=model,
        api_key=api_key,
        base_url=DASHSCOPE_BASE_URL,
        temperature=0.0,
        timeout=timeout,
        max_retries=2,
    )
    return LangchainLLMWrapper(chat)


def build_judge_embeddings(model, api_key):
    from langchain_openai import OpenAIEmbeddings
    from ragas.embeddings import LangchainEmbeddingsWrapper

    embeddings = OpenAIEmbeddings(
        model=model,
        api_key=api_key,
        base_url=DASHSCOPE_BASE_URL,
        check_embedding_ctx_length=False,
    )
    return LangchainEmbeddingsWrapper(embeddings)


def build_metrics(with_relevancy):
    """实例化 RAGAS 指标。classic 导入在 0.4.x 仍可用，仅有 Deprecation 提示，这里静音。"""
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", DeprecationWarning)
        from ragas.metrics import (
            Faithfulness,
            FactualCorrectness,
            LLMContextRecall,
            LLMContextPrecisionWithReference,
            ResponseRelevancy,
        )
    metrics = [
        Faithfulness(),
        LLMContextRecall(),
        LLMContextPrecisionWithReference(),
        FactualCorrectness(mode="f1"),
    ]
    if with_relevancy:
        metrics.append(ResponseRelevancy())
    return metrics


def to_clean_float(value):
    """把 NaN / 非数值转成 None，便于写 JSON 和求均值。"""
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    if math.isnan(number) or math.isinf(number):
        return None
    return number


def collect_scores(eval_result, metrics):
    """从 RAGAS 评测结果里按 metric.name 抽取每条用例的分数。"""
    frame = eval_result.to_pandas()
    metric_names = [metric.name for metric in metrics]
    rows = []
    for _, record in frame.iterrows():
        scores = {}
        for name in metric_names:
            if name in record:
                scores[f"ragas_{name}"] = to_clean_float(record[name])
        rows.append(scores)
    return rows, metric_names


def merge_and_average(result, scored_cases, score_rows):
    """把 ragas_ 指标合并进对应 case.metrics，并按全部数值指标重算 averages。"""
    by_id = {id(case): scores for case, scores in zip(scored_cases, score_rows)}
    for case in result.get("cases", []):
        scores = by_id.get(id(case))
        if scores:
            case.setdefault("metrics", {}).update(scores)

    averages = average_numeric_metrics(result.get("cases", []))
    result["averages"] = averages
    return averages


def fmt_metric(value):
    return "--" if value is None else f"{value:.3f}"


def emit_summary_span(tracer, averages, output, metric_names):
    if tracer is None:
        return
    with tracer.start_as_current_span("ragas.llm_judge.summary") as span:
        span.set_attribute("openinference.span.kind", "EVALUATOR")
        for name in metric_names:
            value = averages.get(f"ragas_{name}")
            if value is not None:
                span.set_attribute(f"eval.average_ragas_{name}", float(value))
        span.set_attribute("eval.output_path", str(output))


def parse_args():
    parser = argparse.ArgumentParser(
        description="为已有的 StockSage RAG 评测结果补充 LLM-judge 的 RAGAS 指标。"
    )
    parser.add_argument("--input", default=None, help="rag_eval_<ts>.json，默认取 results 最新一份")
    parser.add_argument("--output", default=None, help="输出路径，默认 <input>_ragas.json")
    parser.add_argument("--judge-model", default="qwen3.6-plus", help="判官模型（百炼兼容接口）")
    parser.add_argument("--embedding-model", default="text-embedding-v4", help="answer_relevancy 用的 embedding 模型")
    parser.add_argument("--with-relevancy", action="store_true", help="额外计算 answer_relevancy（需 embedding 端点）")
    parser.add_argument("--limit", type=int, default=None, help="只评测前 N 条用例，便于试跑控成本")
    parser.add_argument("--max-context-chars", type=int, default=4000, help="每条上下文截断长度")
    parser.add_argument("--max-workers", type=int, default=4, help="RAGAS 并发，过高易触发限流")
    parser.add_argument("--timeout", type=int, default=600,
                        help="单次调用 + RAGAS job 超时(秒)；factual_correctness 是多步指标，默认 RunConfig 仅 180s 会掐断")
    parser.add_argument("--local-props", default=None,
                        help="application-local.properties 路径（默认自动定位），从中读取 DashScope key")
    parser.add_argument("--no-phoenix", action="store_true")
    parser.add_argument("--phoenix-endpoint", default=DEFAULT_PHOENIX_ENDPOINT)
    parser.add_argument("--phoenix-project", default=DEFAULT_PHOENIX_PROJECT)
    parser.add_argument("--dry-run", action="store_true", help="只装配数据并打印样例，不调用判官模型")
    return parser.parse_args()


def main():
    args = parse_args()
    input_path = Path(args.input) if args.input else latest_result_file()
    result = load_result(input_path)
    cases = result.get("cases", [])
    if args.limit is not None:
        cases = cases[:args.limit]
    print(f"读取结果文件: {input_path}  (用例数={len(cases)})")

    samples, scored_cases, skipped = build_samples(cases, args.max_context_chars)
    print(f"可评测用例: {len(samples)}  跳过(缺 answer/contexts): {len(skipped)}")
    if skipped:
        print("  跳过的用例:", ", ".join(item["id"] for item in skipped))

    if args.dry_run:
        if samples:
            sample = samples[0]
            print("\n[dry-run] 首条样例装配预览：")
            print("  user_input:", sample.user_input[:120])
            print("  response  :", sample.response[:120])
            print("  reference :", (sample.reference or "")[:120])
            print("  contexts  :", len(sample.retrieved_contexts), "段")
        print("\n[dry-run] 数据装配 OK，未调用判官模型。去掉 --dry-run 即可正式评测。")
        return

    if not samples:
        raise SystemExit("没有可评测的用例（answer/contexts 均缺失）。")

    from ragas import EvaluationDataset, RunConfig, evaluate

    api_key = resolve_api_key(args.local_props)
    judge_llm = build_judge_llm(args.judge_model, api_key, args.timeout)
    judge_embeddings = build_judge_embeddings(args.embedding_model, api_key) if args.with_relevancy else None
    metrics = build_metrics(args.with_relevancy)

    print(f"判官模型: {args.judge_model}  指标: {[m.name for m in metrics]}")
    dataset = EvaluationDataset(samples=samples)
    # RunConfig 的 job 超时默认仅 180s，会掐断 factual_correctness 这类多步指标；显式拉大。
    eval_result = evaluate(
        dataset=dataset,
        metrics=metrics,
        llm=judge_llm,
        embeddings=judge_embeddings,
        run_config=RunConfig(max_workers=args.max_workers, timeout=args.timeout),
        show_progress=True,
    )

    score_rows, metric_names = collect_scores(eval_result, metrics)
    averages = merge_and_average(result, scored_cases, score_rows)

    output = Path(args.output) if args.output else (
        input_path.with_name(input_path.stem + "_ragas.json")
    )
    output.parent.mkdir(parents=True, exist_ok=True)
    result["ragas"] = {
        "evidence_kind": "SAMPLED" if args.limit is not None else "GOLDEN_SET",
        "evaluator_version": f"ragas-{importlib.metadata.version('ragas')}",
        "dataset_sha256": hashlib.sha256(input_path.read_bytes()).hexdigest(),
        "judge_model": args.judge_model,
        "embedding_model": args.embedding_model if args.with_relevancy else None,
        "metrics": [f"ragas_{name}" for name in metric_names],
        "source_result": str(input_path),
        "created_at": datetime.now().isoformat(timespec="seconds"),
        "skipped": skipped,
    }
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")

    print("\nRAGAS 平均分：")
    for name in metric_names:
        print(f"  ragas_{name}: {fmt_metric(averages.get(f'ragas_{name}'))}")

    print("\n门槛检查：")
    for metric, minimum in RAGAS_GATES.items():
        value = averages.get(metric)
        if value is None:
            print(f"  {metric}: -- (未计算)")
        else:
            print(f"  {metric}: {value:.3f}  门槛>={minimum}  {'PASS' if value >= minimum else 'FAIL'}")

    print(f"\n已写入: {output}")

    tracer, provider = setup_phoenix(args, "stocksage-ragas-eval")
    emit_summary_span(tracer, averages, output, metric_names)
    if provider is not None:
        provider.force_flush()
        print(f"已上报 Phoenix 项目: {args.phoenix_project}")


if __name__ == "__main__":
    main()
