"""批量驱动 StockSage 的 EDGAR 入库接口，按主题 + 行业拓宽知识库语料。

后端 POST /api/docs/edgar/ingest?ticker=&type=&count= 会拉取并解析 SEC 财报、
做父子分块、向量化写入 Milvus。入库服务按 source-hash 去重，因此本脚本可安全重跑。

用法：
    # 默认灌入全部 13 家新公司（各 10-K x2 + 10-Q x2）
    python ingest_corpus.py --base-url http://localhost:8080

    # 只灌某几家
    python ingest_corpus.py --tickers MU,WDC

    # 自定义份数
    python ingest_corpus.py --kcount 3 --qcount 1

前置条件：backend(:8080) + data-service + Milvus 已启动；会消耗 DashScope embedding 额度。
约束：只接美股 SEC filer；A 股光模块/存储龙头（旭创/新易盛/天孚/兆易/江波龙等）不在此链路。
"""

import argparse
import os
import sys
import time
from datetime import datetime

import requests


# 默认拓宽清单：全部为美股 SEC filer。标签用于后续写金标用例时对照。
# --- AI 算力供应链主题（光互连 + 存力）---
# --- 跨行业板块补齐（GICS 异质性）---
NEW_COMPANIES = [
    # 光模块 / 光通信
    ("AAOI", "Optical - transceiver pure-play"),
    ("FN", "Optical - module contract mfg"),
    ("COHR", "Optical - components/datacom"),
    ("LITE", "Optical - components"),
    # 存储 / 内存
    ("MU", "Storage - DRAM/NAND/HBM"),
    ("WDC", "Storage - HDD/NAND"),
    ("STX", "Storage - HDD"),
    # GICS 板块补齐
    ("CAT", "Industrials"),
    ("PG", "Consumer Staples - household"),
    ("KO", "Consumer Staples - beverages"),
    ("NEE", "Utilities"),
    ("PLD", "Real Estate REIT"),
    ("LIN", "Materials"),
]


def ingest_one(base_url, ticker, filing_type, count, timeout, headers):
    """调用一次入库接口，返回 (ok, summary_dict)。"""
    url = f"{base_url.rstrip('/')}/api/docs/edgar/ingest"
    params = {"ticker": ticker, "type": filing_type, "count": count}
    started = time.perf_counter()
    response = requests.post(url, params=params, headers=headers, timeout=timeout)
    elapsed = time.perf_counter() - started
    response.raise_for_status()
    body = response.json()
    body["_elapsed_seconds"] = round(elapsed, 1)
    ok = not body.get("error")
    return ok, body


def print_result(ticker, label, filing_type, ok, body):
    if not ok:
        print(f"  [FAIL] {ticker} {filing_type} ({label}): {body.get('message', 'unknown error')}")
        return 0
    total = body.get("total_chunks", 0)
    processed = body.get("filings_processed", 0)
    elapsed = body.get("_elapsed_seconds", 0)
    print(f"  [OK]   {ticker} {filing_type} ({label}): "
          f"filings={processed}, chunks={total}, {elapsed}s")
    for d in body.get("details", []):
        status = d.get("status")
        if status == "ingested":
            print(f"         - {d.get('filing_date')}  chunks={d.get('chunks')}  {d.get('source_id')}")
        else:
            print(f"         - {d.get('filing_date')}  FAILED: {d.get('message')}")
    return total


def parse_args():
    parser = argparse.ArgumentParser(description="Broaden StockSage RAG corpus via EDGAR ingestion.")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--tickers", default=None,
                        help="逗号分隔，只灌这些 ticker；缺省灌入全部 13 家新公司")
    parser.add_argument("--kcount", type=int, default=2, help="每家 10-K 份数（默认 2）")
    parser.add_argument("--qcount", type=int, default=2, help="每家 10-Q 份数（默认 2）")
    parser.add_argument("--timeout", type=int, default=600, help="单次请求超时秒数")
    parser.add_argument("--sleep", type=float, default=2.0, help="请求间隔秒数（对 SEC 友好）")
    parser.add_argument("--admin-header", default=os.environ.get("STOCKSAGE_ADMIN_HEADER_NAME", "X-StockSage-Admin-Token"),
                        help="admin 鉴权头名（默认 X-StockSage-Admin-Token）")
    parser.add_argument("--admin-token", default=os.environ.get("STOCKSAGE_ADMIN_TOKEN", "local-admin"),
                        help="admin token；缺省取环境变量 STOCKSAGE_ADMIN_TOKEN，再缺省为 local-admin")
    return parser.parse_args()


def main():
    args = parse_args()

    companies = NEW_COMPANIES
    if args.tickers:
        wanted = {t.strip().upper() for t in args.tickers.split(",") if t.strip()}
        known = dict(NEW_COMPANIES)
        companies = [(t, known.get(t, "?")) for t in wanted]

    jobs = []
    for ticker, label in companies:
        if args.kcount > 0:
            jobs.append((ticker, label, "10-K", args.kcount))
        if args.qcount > 0:
            jobs.append((ticker, label, "10-Q", args.qcount))

    headers = {args.admin_header: args.admin_token}

    print(f"=== EDGAR corpus broaden | {datetime.now().isoformat(timespec='seconds')} ===")
    print(f"base-url={args.base_url}  companies={len(companies)}  jobs={len(jobs)}  "
          f"(10-K x{args.kcount} + 10-Q x{args.qcount} each)")
    print(f"auth header={args.admin_header} (token len={len(args.admin_token)})")
    print("")

    grand_total = 0
    failures = 0
    for ticker, label, filing_type, count in jobs:
        try:
            ok, body = ingest_one(args.base_url, ticker, filing_type, count, args.timeout, headers)
        except requests.exceptions.RequestException as exc:
            print(f"  [ERR]  {ticker} {filing_type} ({label}): request failed: {exc}")
            failures += 1
            continue
        grand_total += print_result(ticker, label, filing_type, ok, body)
        if not ok:
            failures += 1
        time.sleep(args.sleep)

    print("")
    print(f"=== Done. total_chunks_ingested={grand_total}, failed_jobs={failures}/{len(jobs)} ===")
    if failures:
        print("有失败任务，检查 backend 日志（常见原因：data-service 未起、EDGAR 限流、ticker 无对应 CIK、")
        print("或该公司某 filing_type 份数不足）。")
        sys.exit(1)


if __name__ == "__main__":
    main()
