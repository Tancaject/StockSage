"""Check actual Java offline drill exports with the production rollback run validator.

This records mechanism evidence only. It cannot emit signed rollback acceptance,
attest fault injection, authorize serving, or make model-quality claims.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from evolution_acceptance import evaluator_hash
from evolution_deployment import verify_drill_runs
from ordinary_answer_quality import json_hash
from run_evolution_experiment import write_once


def check(path):
    fixture = json.loads(path.read_bytes())
    if fixture.get('kind') != 'OFFLINE_ORDINARY_DRILL_EXPORT' or fixture.get('realAcceptance') is not False:
        raise ValueError('Expected explicitly fictional ordinary drill exports')
    approved, drill = fixture['approvedPayload'], fixture['drillPayload']
    pack = approved['package']
    if (pack.get('evaluatorSha256') != evaluator_hash() or approved.get('packageSha256') != json_hash(pack)
            or drill.get('approvedArtifactSha256') != json_hash(approved)
            or drill.get('packageSha256') != json_hash(pack)):
        raise ValueError('Drill producer used different package/evaluator bytes; regenerate the Java export')
    runs, proofs = {}, {}
    for phase, row in fixture['runs'].items():
        trace_file = (path.parent / row['traceFile']).resolve()
        if trace_file.parent != path.parent.resolve():
            raise ValueError('Drill traces must be adjacent to the producer manifest')
        proofs[row['traceSha256']] = trace_file.read_bytes()
        runs[phase] = {key: row[key] for key in ('runId', 'traceSha256', 'caseSha256')}
    review = {'runs': runs, 'withdrawnAt': fixture['withdrawnAt'], 'reviewedAt': fixture['reviewedAt']}
    verify_drill_runs(drill, pack, json_hash(drill), review, proofs)
    return {'kind': 'OFFLINE_ORDINARY_DRILL_VERIFICATION', 'schemaVersion': 1,
            'status': 'VERIFIED_ORDINARY_RUN_CHAIN', 'realAcceptance': False,
            'producerManifestSha256': json_hash(fixture), 'evaluatorSha256': evaluator_hash(),
            'runs': runs, 'withdrawnAt': fixture['withdrawnAt'],
            'independentReview': 'NOT_PERFORMED', 'faultInjectionAcceptance': 'NOT_PERFORMED',
            'qualityEffect': 'UNVERIFIED', 'activationAuthorized': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        result = check(args.input)
        write_once(args.output, result)
        print(json.dumps({'status': result['status'], 'output': str(args.output)}))
        return 0
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(2, f'Offline drill rejected: {error}. Keep the original traces and regenerate the matching producer batch.\n')


if __name__ == '__main__':
    raise SystemExit(main())
