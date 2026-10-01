"""Observe a fixed ordinary FUNDAMENTALS batch; never authorize scope expansion.

Raw execution exports and reviews stay in restricted storage. The emitted report
omits questions, answers and user account IDs; withdrawal records its local operator.
"""
from __future__ import annotations

import argparse
import getpass
import json
import math
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path

from evolution_acceptance import require_hash
from evolution_compare import percentile
from evolution_dataset import exact_fields, nonempty
from evolution_deployment import _package, _time
from evolution_quality import HARD_GATES
from evolution_release import publisher_key, verify_publisher
from ordinary_answer_quality import apply_reviews, attributes, material, unique_cases
from eval_common import json_hash

SCHEMA = 'fundamentals_serving_observation_v1'
REVIEW_SCHEMA = 'fundamentals_serving_review_v1'
ERROR_CATEGORIES = {'NONE', 'METHOD', 'DATA', 'CAPABILITY', 'EXECUTOR', 'EVALUATOR', 'UNKNOWN'}


def binding(case):
    return {'caseId': case['id'], 'runId': (case.get('trace') or {}).get('traceId'),
            'traceSha256': json_hash(case.get('trace')), 'answerSha256': material(case)['binding']['answerSha256']}


def review_template(report):
    unique_cases(report)
    return {'schema': REVIEW_SCHEMA, 'reportSha256': json_hash(report), 'reviews': [
        {'binding': binding(case), 'reviewer': '', 'reviewedAt': '', 'errorCategory': 'UNKNOWN',
         'appropriateRefusal': {'status': 'NO_DATA', 'reason': ''},
         'hardGates': {name: {'status': 'NO_DATA', 'reason': ''} for name in HARD_GATES}}
        for case in report['cases']]}


def _reviews(report, reviews):
    if reviews is None:
        return {}
    exact_fields(reviews, {'schema', 'reportSha256', 'reviews'}, 'serving reviews')
    if reviews['schema'] != REVIEW_SCHEMA or reviews['reportSha256'] != json_hash(report):
        raise ValueError('Serving reviews belong to a different execution export')
    cases, result = unique_cases(report), {}
    for row in reviews['reviews']:
        exact_fields(row, {'binding', 'reviewer', 'reviewedAt', 'errorCategory', 'appropriateRefusal', 'hardGates'}, 'serving review')
        key = row['binding']['caseId']
        if key not in cases or key in result or row['binding'] != binding(cases[key]):
            raise ValueError('Serving review has an unknown, duplicate or changed run binding')
        nonempty(row['reviewer'], 'serving reviewer')
        if _time(row['reviewedAt']) > datetime.now(timezone.utc):
            raise ValueError('Serving review time cannot be in the future')
        if row['errorCategory'] not in ERROR_CATEGORIES:
            raise ValueError('Unknown serving error category')
        exact_fields(row['hardGates'], set(HARD_GATES), 'serving hard gates')
        for check in [row['appropriateRefusal'], *row['hardGates'].values()]:
            exact_fields(check, {'status', 'reason'}, 'serving check')
            if check['status'] not in {'PASS', 'FAIL', 'NOT_APPLICABLE', 'NO_DATA'}:
                raise ValueError('Unknown serving check status')
            if check['status'] != 'NO_DATA':
                nonempty(check['reason'], 'serving check rationale')
        result[key] = row
    return result


def _usage(rows):
    known = [value for value, _ in rows if value.get('usageSource') == 'PROVIDER'
             and all(type(value.get(name)) is int and value[name] >= 0 for name in ('inputTokens', 'outputTokens'))]
    complete = sum(value.get('usageSource') == 'PROVIDER' and completed
                   and all(type(value.get(name)) is int and value[name] >= 0 for name in ('inputTokens', 'outputTokens'))
                   for value, completed in rows)
    return {'requests': len(rows), 'observedCount': len(known), 'completeCount': complete,
            'unknownOrPartialCount': len(rows) - complete,
            'observedInputTokens': sum(value['inputTokens'] for value in known) if known else None,
            'observedOutputTokens': sum(value['outputTokens'] for value in known) if known else None,
            'totalTokens': sum(value['inputTokens'] + value['outputTokens'] for value in known)
            if rows and complete == len(rows) else None}


def _latency(values):
    observed = sorted(value for value in values if type(value) in (int, float) and math.isfinite(value) and value >= 0)
    return {'observedCount': len(observed), 'missingCount': len(values) - len(observed),
            'p95Ms': percentile(observed, .95) if observed else None, 'method': 'LINEAR_INTERPOLATION'}


def observe(report, activation_artifact, approved_artifact, publisher, quality_reviews=None, reviews=None):
    _, pack = _package(approved_artifact, publisher)
    activation = verify_publisher(activation_artifact, publisher)
    if (activation.get('kind') != 'METHOD_ACTIVATION' or activation.get('schemaVersion') != 1
            or activation.get('mode') != 'SERVING'
            or activation.get('approvedArtifactSha256') != approved_artifact['payloadSha256']
            or activation.get('packageSha256') != json_hash(pack)
            or activation.get('scope') != pack['scope']
            or activation.get('expectedComparisonIdentity') != pack['expectedComparisonIdentity']):
        raise ValueError('Observation requires the matching signed SERVING authorization and approved package')
    require_hash(activation.get('rollbackAcceptanceSha256'), 'rollback acceptance')
    if report.get('schema') != 'ordinary_execution_eval_v1':
        raise ValueError('Observe actual ordinary execution exports, not isolated answer replays')
    cases = unique_cases(report)
    count = report.get('sample_count')
    if type(count) is not int or count < len(cases):
        raise ValueError('Observation sample_count cannot omit exported executions')
    if len(cases) == count and json_hash([case.get('case_definition') for case in cases.values()]) != report.get('dataset_sha256'):
        raise ValueError('Complete observation cases differ from the frozen dataset')
    if any((case.get('case_definition') or {}).get('route') != 'FUNDAMENTALS' for case in cases.values()):
        raise ValueError('Observation batch must contain only ordinary FUNDAMENTALS cases')
    indexed_reviews = _reviews(report, reviews)
    quality = unique_cases(apply_reviews(report, quality_reviews)) if quality_reviews is not None else {}
    groups, runs, seen = defaultdict(list), [], set()
    candidate = pack['bundle']
    expected = {candidate['bundleId']: candidate['contentSha256'], pack['rollback']['bundleId']: pack['rollback']['bundleSha256']}
    for key, case in cases.items():
        trace = case.get('trace') or {}
        run_id = trace.get('traceId')
        if run_id and run_id in seen:
            raise ValueError('Repeated trace IDs are not new observations')
        if run_id:
            seen.add(run_id)
        attrs = attributes(case)
        methods = [row for row in attrs if row.get('kind') == 'ordinary-evidence']
        if len(methods) > 1:
            raise ValueError('Ambiguous ordinary method attribution')
        method = methods[0] if methods else {}
        bundle, selection = method.get('methodBundle') or {}, method.get('methodSelection') or {}
        bundle_id, bundle_hash = bundle.get('bundleId'), bundle.get('bundleSha256')
        failures, unknown = [], []
        if not run_id or not bundle_id or not bundle_hash:
            unknown.append('METHOD_ATTRIBUTION_MISSING')
        elif expected.get(bundle_id) != bundle_hash:
            failures.append('UNAPPROVED_BUNDLE')
        if bundle_id == candidate['bundleId']:
            if (selection.get('authorizationSha256') != activation_artifact['payloadSha256']
                    or selection.get('activationId') != activation['activationId'] or selection.get('mode') != 'SERVING'
                    or selection.get('reason') != 'APPROVED_SCOPE' or trace.get('userId') not in activation['internalAccountIds']
                    or selection.get('comparisonIdentity') != activation['expectedComparisonIdentity']
                    or method.get('route') != 'FUNDAMENTALS'):
                failures.append('CANDIDATE_SCOPE_OR_CONDITIONS_MISMATCH')
            try:
                if not _time(activation['approvedAt']) <= _time(selection.get('pinnedAt')) < _time(activation['expiresAt']):
                    failures.append('CANDIDATE_OUTSIDE_AUTHORIZATION_TIME')
            except (ValueError, TypeError):
                failures.append('CANDIDATE_PIN_TIME_MISSING')
        completed = method.get('analystStatus') == 'COMPLETED'
        if completed:
            invocation = method.get('analystInvocation') or {}
            if (invocation.get('methodBundle') != bundle or invocation.get('kind') != 'model-invocation'
                    or invocation.get('scope') != 'fundamentals-analysis'):
                failures.append('ACTUAL_ANALYST_METHOD_MISMATCH')
            try:
                require_hash(invocation.get('actualSystemPromptSha256'), 'analyst system prompt')
                require_hash(invocation.get('actualUserPromptSha256'), 'analyst user prompt')
            except ValueError:
                failures.append('ACTUAL_ANALYST_PROMPT_HASH_MISSING')
        snapshot = material(case)
        unknown.extend(snapshot['errors'])
        quality_row = (quality.get(key) or {}).get('quality_review') or {}
        quality_status = quality_row.get('status', 'NO_DATA')
        if quality_status == 'FAIL':
            failures.append('FINAL_ANSWER_QUALITY_FAILED')
        review = indexed_reviews.get(key) or {}
        refusal = (review.get('appropriateRefusal') or {}).get('status', 'NO_DATA')
        gates = review.get('hardGates') or {}
        failures.extend('HARD_GATE:' + name for name, check in gates.items() if check['status'] == 'FAIL')
        if refusal == 'FAIL':
            failures.append('INAPPROPRIATE_REFUSAL')
        if quality_status == 'NO_DATA' or quality_row.get('errors') or not review or refusal == 'NO_DATA' or any(
                check['status'] == 'NO_DATA' for check in gates.values()):
            unknown.append('REVIEW_INCOMPLETE')
        if trace.get('status') != 'success' or not completed or case.get('passed') is not True:
            unknown.append('EXECUTION_NOT_SUCCESSFUL')
        row = {**binding(case), 'bundleId': bundle_id, 'bundleSha256': bundle_hash,
               'selectionReason': selection.get('reason', 'UNKNOWN'), 'status': trace.get('status', 'UNKNOWN'),
               'taskOutcome': trace.get('taskOutcome', 'UNKNOWN'), 'analystStatus': method.get('analystStatus', 'UNKNOWN'),
               'quality': quality_status, 'appropriateRefusal': refusal, 'errorCategory': review.get('errorCategory', 'UNKNOWN'),
               'hardFailures': failures, 'unknowns': sorted(set(unknown))}
        runs.append(row)
        groups[(bundle_id, bundle_hash)].append((row, trace, method, snapshot))
    versions = []
    for (bundle_id, bundle_hash), items in groups.items():
        rows = [row for row, _, _, _ in items]
        statuses = Counter(row['status'] for row in rows)
        versions.append({'bundleId': bundle_id, 'bundleSha256': bundle_hash, 'count': len(rows),
            'shareOfExportedRuns': len(rows) / len(cases), 'statuses': dict(statuses),
            'errorRate': statuses['error'] / len(rows), 'cancelledRate': statuses['cancelled'] / len(rows),
            'unsuccessfulRate': sum(row['status'] != 'success' or row['analystStatus'] != 'COMPLETED' for row in rows) / len(rows),
            'selectionReasons': dict(Counter(row['selectionReason'] for row in rows)),
            'quality': dict(Counter(row['quality'] for row in rows)),
            'appropriateRefusal': dict(Counter(row['appropriateRefusal'] for row in rows)),
            'errorCategories': dict(Counter(row['errorCategory'] for row in rows)),
            'requestLatency': _latency([trace.get('durationMs') for _, trace, _, _ in items]),
            'analystLatency': _latency([method.get('analystDurationMs') for _, _, method, _ in items]),
            'analystUsage': _usage([(method.get('analystUsage') or {}, row['analystStatus'] == 'COMPLETED')
                                     for row, _, method, _ in items]),
            'finalAnswerUsage': _usage([(snapshot['model_usage'], row['status'] == 'success' and len(snapshot['model_invocations']) == 1)
                                      for row, _, _, snapshot in items])})
    hard_failure = any(row['hardFailures'] for row in runs)
    incomplete = not runs or len(cases) != count or any(row['unknowns'] for row in runs)
    decision = 'STOP_EXPANSION_AND_WITHDRAW' if hard_failure else ('HOLD_SCOPE_UNCERTAIN' if incomplete else 'MAINTAIN_REVIEWED_SCOPE')
    return {'schema': SCHEMA, 'reportSha256': json_hash(report), 'activationSha256': activation_artifact['payloadSha256'],
            'qualityReviewsSha256': json_hash(quality_reviews) if quality_reviews is not None else None,
            'operationalReviewsSha256': json_hash(reviews) if reviews is not None else None,
            'generatedAt': datetime.now(timezone.utc).isoformat(), 'scope': 'EXPORTED_BATCH_ONLY',
            'sampleCount': count, 'exportedCount': len(cases), 'missingCount': count - len(cases),
            'candidateBundleId': candidate['bundleId'], 'decision': decision, 'expansionAuthorized': False,
            'versions': versions, 'runs': runs}


def withdraw(observation, controls):
    """Explicit operator action; never clear switches or rewrite historical artifacts."""
    if observation['decision'] != 'STOP_EXPANSION_AND_WITHDRAW':
        return None
    from evolution_dataset import identifier
    identifier(observation['candidateBundleId'], 'withdrawn bundle ID')
    if not controls.is_dir():
        raise ValueError('Withdrawal control directory is missing; restore operator storage and retry withdrawal')
    names = ['PROHIBIT_ACTIVATION', 'REVOKE.' + observation['candidateBundleId']]
    existing = {path.name for path in controls.iterdir()}
    action = {'actor': getpass.getuser(), 'startedAt': datetime.now(timezone.utc).isoformat(),
              'from': {name: 'PRESENT' if name in existing else 'ABSENT' for name in names},
              'reason': observation['decision'],
              'evidenceRefs': {field: observation[field] for field in
                               ('reportSha256', 'activationSha256', 'qualityReviewsSha256', 'operationalReviewsSha256')
                               if observation.get(field) is not None}}
    for name in names:
        # Existing switches must survive; touching a marker never reactivates a method.
        with (controls / name).open('a', encoding='utf-8'):
            pass
    return {**action, 'at': datetime.now(timezone.utc).isoformat(),
            'to': dict.fromkeys(names, 'PRESENT'), 'markers': names,
            'status': 'WITHDRAWAL_MARKERS_WRITTEN', 'runtimeWithdrawal': 'UNVERIFIED'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('report', 'approved-artifact', 'activation', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    for name in ('quality-reviews', 'reviews', 'control-directory'):
        parser.add_argument('--' + name, type=Path)
    parser.add_argument('--review-template', action='store_true')
    parser.add_argument('--withdraw-on-failure', action='store_true')
    args = parser.parse_args()
    if args.output.exists() or args.withdraw_on_failure and (args.control_directory is None or args.review_template):
        parser.error('Use a new output file; withdrawal requires --control-directory and cannot generate a review template')
    try:
        from run_evolution_experiment import write_once
        read = lambda path: json.loads(path.read_bytes()) if path else None
        report = read(args.report)
        result = observe(report, read(args.activation), read(args.approved_artifact), publisher_key(),
                         read(args.quality_reviews), read(args.reviews))
        if args.review_template:
            result = review_template(report)
        elif args.withdraw_on_failure:
            action = withdraw(result, args.control_directory)
            result['operatorMarkers'] = action['markers'] if action else []
            result['operatorAction'] = action
        write_once(args.output, result)
        print(json.dumps({'schema': result['schema'], 'decision': result.get('decision'), 'output': str(args.output)}))
        return {'STOP_EXPANSION_AND_WITHDRAW': 3, 'HOLD_SCOPE_UNCERTAIN': 4}.get(result.get('decision'), 0)
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(2, f'Observation rejected: {error}. Preserve exports/reviews and check the signed activation and operator controls.\n')


if __name__ == '__main__':
    raise SystemExit(main())
