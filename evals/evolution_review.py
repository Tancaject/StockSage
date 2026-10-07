"""Retain blinded opinions and independent dispute resolutions before comparison."""
from __future__ import annotations

import argparse
import copy
import json
from pathlib import Path

from evolution_acceptance import seal, verified, read_key, evaluator_hash
from evolution_compare import apply_blind_reviews, REAL_REVIEW_FIELDS, blind_material_hash
from evolution_dataset import exact_fields, nonempty, timestamp, load_jsonl
from eval_common import json_hash

PREFERENCES = {'A', 'B', 'TIE', 'UNREVIEWABLE'}


def judgment(item):
    return {'preference': item['preference'], 'options': [{
        'label': option['label'],
        'dimensions': {name: row['status'] for name, row in option['dimensions'].items()},
        **{name: option[name] for name in REAL_REVIEW_FIELDS if name in option}
    } for option in item['options']]}


def adjudication_template(manifest, baseline, candidate, packets, mapping, sources=None, gold=None):
    if not packets:
        raise ValueError('At least one complete independent blind review is required')
    reviewers, indexed = set(), []
    for packet in packets:
        apply_blind_reviews(manifest, baseline, candidate, packet, mapping, sources, gold)
        for item in packet['items']:
            for option in item['options']:
                nonempty(option['reviewer'], 'blind reviewer')
        identities = {option['reviewer'].strip() for item in packet['items'] for option in item['options']}
        if len(identities) != 1 or not next(iter(identities)) or identities & reviewers:
            raise ValueError('Each blind packet must belong to one distinct independent reviewer')
        reviewers.update(identities)
        for item in packet['items']:
            if not isinstance(item['preference'], str) or item['preference'] not in PREFERENCES:
                raise ValueError('Record A, B, TIE or UNREVIEWABLE for every trial, including losing cases')
            nonempty(item['reason'], 'blind preference reason')
            for option in item['options']:
                timestamp(option['reviewed_at'], 'blind reviewed_at')
                if item['preference'] != 'UNREVIEWABLE' and any(
                        row['status'] not in {'PASS', 'FAIL'} for row in option['dimensions'].values()):
                    raise ValueError('Unscored dimensions require an explicit UNREVIEWABLE trial')
        indexed.append({item['trialId']: item for item in packet['items']})
    rows = []
    for item in packets[0]['items']:
        opinions = [index[item['trialId']] for index in indexed]
        disputed = len({json_hash(judgment(opinion)) for opinion in opinions}) > 1
        rows.append({'trialId': item['trialId'], 'scope': item['scope'], 'status': 'DISPUTED' if disputed else 'AGREED',
                     'votes': [{'reviewer': opinion['options'][0]['reviewer'], 'packetSha256': json_hash(packet),
                                'preference': opinion['preference'], 'reason': opinion['reason']}
                               for packet, opinion in zip(packets, opinions)],
                     'resolution': {'reviewer': None, 'reviewedAt': None, 'reason': None, 'selectedPacketSha256': None}
                                   if disputed else None})
    return {'kind': 'REVIEW_ADJUDICATION_INPUT', 'manifestSha256': json_hash(manifest),
            'mappingSha256': json_hash(mapping), 'materialsSha256': blind_material_hash(packets[0]),
            'reviewPacketsSha256': [json_hash(packet) for packet in packets], 'trials': rows}


def finalize_reviews(manifest, baseline, candidate, packets, mapping, decisions, key, sources=None, gold=None):
    template = adjudication_template(manifest, baseline, candidate, packets, mapping, sources, gold)
    frozen = copy.deepcopy(decisions)
    if not isinstance(frozen.get('trials'), list) or len(frozen['trials']) != len(template['trials']):
        raise ValueError('Adjudication must retain all declared blind trials')
    for actual, expected in zip(frozen['trials'], template['trials']):
        actual['resolution'] = expected['resolution']
    if frozen != template:
        raise ValueError('Adjudication cannot alter original votes, materials, identities or trial coverage')
    by_hash = {json_hash(packet): {row['trialId']: row for row in packet['items']} for packet in packets}
    resolved = copy.deepcopy(packets[0])
    trials, unresolved = [], []
    all_reviewers = {vote['reviewer'].strip() for row in template['trials'] for vote in row['votes']}
    for target, row in zip(resolved['items'], decisions['trials']):
        chosen = template['reviewPacketsSha256'][0]
        if row['status'] == 'DISPUTED':
            resolution = row['resolution']
            exact_fields(resolution, {'reviewer', 'reviewedAt', 'reason', 'selectedPacketSha256'}, 'resolution')
            if any(value is None or value == '' for value in resolution.values()):
                unresolved.append(row['trialId'])
                trials.append({**row, 'finalPreference': None})
                continue
            nonempty(resolution['reviewer'], 'adjudicator')
            nonempty(resolution['reason'], 'adjudication reason')
            timestamp(resolution['reviewedAt'], 'adjudication reviewedAt')
            if resolution['reviewer'].strip() in all_reviewers:
                raise ValueError('A disputed review needs a distinct adjudicator, not one of its original reviewers')
            chosen = resolution['selectedPacketSha256']
            if chosen not in by_hash:
                raise ValueError('Adjudication must select a retained complete review; new scores require a new review packet')
        elif row['resolution'] is not None:
            raise ValueError('An agreed trial must not hide an extra resolution')
        selected = by_hash[chosen][row['trialId']]
        target.update(copy.deepcopy(selected))
        trials.append({**row, 'finalPreference': selected['preference']})
    if unresolved:
        return {'status': 'PENDING_ADJUDICATION', 'unresolvedTrials': unresolved, 'trials': trials}
    left, right = apply_blind_reviews(manifest, baseline, candidate, resolved, mapping, sources, gold)
    audit = {'kind': 'BLIND_REVIEW_AUDIT', 'schemaVersion': 1, 'evaluatorSha256': evaluator_hash(),
             'manifestSha256': json_hash(manifest), 'baselineReportSha256': json_hash(left),
             'candidateReportSha256': json_hash(right), 'materialsSha256': template['materialsSha256'],
             'mappingSha256': json_hash(mapping), 'reviewPacketsSha256': template['reviewPacketsSha256'],
             'adjudicationSha256': json_hash(decisions), 'trials': trials,
             'counts': {'trials': len(trials), 'disputes': sum(row['status'] == 'DISPUTED' for row in trials),
                        'ties': sum(row['finalPreference'] == 'TIE' for row in trials),
                        'unreviewable': sum(row['finalPreference'] == 'UNREVIEWABLE' for row in trials)}}
    return {'status': 'REVIEW_COMPLETE', 'baseline': left, 'candidate': right, 'audit': seal(audit, key)}


def verify_review_audit(artifact, manifest, baseline, candidate, key):
    audit = verified(artifact, key, 'BLIND_REVIEW_AUDIT')
    if (audit.get('schemaVersion') != 1 or audit.get('evaluatorSha256') != evaluator_hash()
            or audit.get('manifestSha256') != json_hash(manifest)
            or audit.get('baselineReportSha256') != json_hash(baseline)
            or audit.get('candidateReportSha256') != json_hash(candidate)):
        raise ValueError('Blind review audit differs from the exact reviewed reports, manifest or current evaluator')
    expected = 2 * sum(len(case['repeatIds']) for case in manifest['cases'])
    trials = audit.get('trials', [])
    if (len(trials) != expected or len({row['trialId'] for row in trials}) != expected
            or any(row.get('finalPreference') not in PREFERENCES for row in trials)):
        raise ValueError('Blind review audit has incomplete trial coverage or unresolved decisions')
    if audit.get('counts') != {'trials': len(trials), 'disputes': sum(row['status'] == 'DISPUTED' for row in trials),
                               'ties': sum(row['finalPreference'] == 'TIE' for row in trials),
                               'unreviewable': sum(row['finalPreference'] == 'UNREVIEWABLE' for row in trials)}:
        raise ValueError('Blind review summary differs from its retained decisions')
    return audit


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mode', required=True, choices=['template', 'finalize'])
    for name in ('manifest', 'baseline', 'candidate', 'mapping', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--reviews', type=Path, nargs='+', required=True)
    for name in ('source-cases', 'gold', 'adjudication'):
        parser.add_argument('--' + name, type=Path)
    args = parser.parse_args()
    if args.output.exists():
        parser.error('Output must be a new directory; preserve all original review artifacts')
    if bool(args.source_cases) != bool(args.gold) or args.mode == 'finalize' and not args.adjudication:
        parser.error('Supply source-cases and gold together; finalize also requires adjudication')
    try:
        from run_evolution_experiment import write_once
        read = lambda path: json.loads(path.read_bytes().decode('utf-8'))
        manifest, baseline, candidate, mapping = [read(path) for path in (args.manifest, args.baseline, args.candidate, args.mapping)]
        packets = [read(path) for path in args.reviews]
        sources = load_jsonl(args.source_cases) if args.source_cases else None
        gold = load_jsonl(args.gold) if args.gold else None
        if args.mode == 'template':
            result = {'status': 'ADJUDICATION_TEMPLATE'}
            output = {'adjudication.json': adjudication_template(manifest, baseline, candidate, packets, mapping, sources, gold)}
        else:
            decisions = read(args.adjudication)
            result = finalize_reviews(manifest, baseline, candidate, packets, mapping, decisions, read_key(), sources, gold)
            output = {'adjudication.json': decisions, 'result.json': {
                name: value for name, value in result.items() if name not in {'baseline', 'candidate', 'audit'}}}
            if result['status'] == 'REVIEW_COMPLETE':
                output.update({'baseline-reviewed.json': result['baseline'], 'candidate-reviewed.json': result['candidate'],
                               'review-audit.json': result['audit']})
        args.output.mkdir(parents=True, exist_ok=False)
        # Preserve every original opinion, including ties, disagreements and losing arms.
        output.update({f'reviews/{json_hash(packet)}.json': packet for packet in packets})
        for name, value in output.items():
            write_once(args.output / name, value)
        print(json.dumps({'status': result['status'], 'output': str(args.output)}))
        return 3 if result['status'] == 'PENDING_ADJUDICATION' else 0
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(2, f'Independent review failed: {error}. Check the original blind packets and resolution identities.\n')


if __name__ == '__main__':
    raise SystemExit(main())
