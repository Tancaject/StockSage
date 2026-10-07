"""Triage the backend evolution failure pool and export METHOD failures.

Failures enter the pool from answer feedback, DEEP report rejections and unfinished ordinary
FUNDAMENTALS answers. Only failures triaged as METHOD become learning material; DETERMINISTIC
ones go to code fixes, DATA_GAP/PROVIDER/NOT_A_FAILURE are kept for the record only.

  python evolution_failure_pool.py list [--status NEW]
  python evolution_failure_pool.py show --id 12
  python evolution_failure_pool.py triage --id 12 --type METHOD --note "quarter compared with half-year cumulative"
  python evolution_failure_pool.py export --output evolution/production-failures-YYYYMMDD

export writes one draft per METHOD failure: the exact analyst query/context and answer from the
trace, plus an empty answer-key stub. A draft is not a registered case: a reviewer must add source
provenance and gold before it enters evolution_dataset.py and the replay/reflection workflow.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import urllib.request
from pathlib import Path

BASE = 'http://localhost:8080/api/admin/evolution/failures'
TYPES = ('METHOD', 'DETERMINISTIC', 'DATA_GAP', 'PROVIDER', 'NOT_A_FAILURE')


def admin_headers() -> dict:
    token = os.environ.get('STOCKSAGE_ADMIN_TOKEN', '')
    if not token:
        raise ValueError('Set STOCKSAGE_ADMIN_TOKEN')
    return {os.environ.get('STOCKSAGE_ADMIN_HEADER_NAME', 'X-StockSage-Admin-Token'): token, 'Accept': 'application/json'}


def request_json(method: str, url: str, *, headers: dict, body: dict | None = None):
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode('utf-8')
    request = urllib.request.Request(url, data=data, method=method,
                                     headers={**headers, **({'Content-Type': 'application/json'} if data else {})})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def ordinary_evidence(trace: dict) -> dict:
    steps = json.loads(trace.get('steps') or '[]')
    for step in steps:
        attributes = step.get('attributes') or {}
        if attributes.get('kind') == 'ordinary-evidence':
            return attributes
    return {}


def draft(detail: dict) -> tuple[dict, dict]:
    failure, trace = detail['failure'], detail.get('trace') or {}
    evidence = ordinary_evidence(trace)
    context = evidence.get('analystContext')
    case_id = f"prod-failure-{failure['id']}"
    case = {'schema': 'production_failure_case_draft_v1', 'caseId': case_id, 'failureId': failure['id'],
            'source': failure['source'], 'route': failure['route'], 'traceId': failure.get('traceId'),
            'methodBundleId': failure.get('methodBundleId'), 'taskOutcome': failure.get('taskOutcome'),
            'query': failure.get('userQuery'), 'analystQuery': evidence.get('analystQuery'),
            'analystContext': context,
            'analystContextSha256': hashlib.sha256(context.encode('utf-8')).hexdigest() if context else None,
            'answer': detail.get('answer'), 'userNote': failure.get('note'), 'triageNote': failure.get('triageNote'),
            'methodSelection': evidence.get('methodSelection'),
            'replayable': bool(context), 'reviewStatus': 'DRAFT_NEEDS_PROVENANCE_AND_GOLD'}
    key = {'schema': 'production_failure_answer_key_draft_v1', 'caseId': case_id, 'humanReviewStatus': 'UNREVIEWED',
           'requiredFacts': {}, 'derived': {}, 'requiredPoints': [], 'failureToReproduce': failure.get('triageNote')}
    return case, key


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--base-url', default=BASE)
    sub = parser.add_subparsers(dest='command', required=True)
    listing = sub.add_parser('list')
    listing.add_argument('--status', choices=['NEW', 'TRIAGED'])
    listing.add_argument('--type', choices=TYPES)
    sub.add_parser('show').add_argument('--id', type=int, required=True)
    triage = sub.add_parser('triage')
    triage.add_argument('--id', type=int, required=True)
    triage.add_argument('--type', choices=TYPES, required=True)
    triage.add_argument('--note', required=True)
    export = sub.add_parser('export')
    export.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    headers = admin_headers()
    try:
        if args.command == 'list':
            query = '&'.join(f'{name}={value}' for name, value in (('status', args.status), ('failureType', args.type)) if value)
            rows = request_json('GET', f'{args.base_url}?limit=200{"&" + query if query else ""}', headers=headers)
            for row in rows:
                print(f"#{row['id']} {row['status']:<8} {row.get('failureType') or '-':<13} {row['source']:<16} {row['route']:<12} "
                      f"{(row.get('userQuery') or '')[:60]}")
        elif args.command == 'show':
            print(json.dumps(request_json('GET', f'{args.base_url}/{args.id}', headers=headers), ensure_ascii=False, indent=2))
        elif args.command == 'triage':
            row = request_json('POST', f'{args.base_url}/{args.id}/triage', headers=headers,
                               body={'failureType': args.type, 'note': args.note})
            print(f"#{row['id']} -> {row['failureType']}")
        else:
            if args.output.exists():
                parser.error('Use a new output directory')
            rows = request_json('GET', f'{args.base_url}?failureType=METHOD&limit=200', headers=headers)
            args.output.mkdir(parents=True)
            cases, keys = [], []
            for row in rows:
                case, key = draft(request_json('GET', f"{args.base_url}/{row['id']}", headers=headers))
                cases.append(case)
                keys.append(key)
            for name, items in (('case-drafts.jsonl', cases), ('answer-key-drafts.jsonl', keys)):
                with (args.output / name).open('x', encoding='utf-8') as handle:
                    for item in items:
                        handle.write(json.dumps(item, ensure_ascii=False) + '\n')
            print(json.dumps({'exported': len(cases), 'replayable': sum(case['replayable'] for case in cases),
                              'output': str(args.output)}, ensure_ascii=False))
        return 0
    except (OSError, ValueError, KeyError) as error:
        parser.exit(2, f'Failure pool request failed: {error}\n')


if __name__ == '__main__':
    raise SystemExit(main())
