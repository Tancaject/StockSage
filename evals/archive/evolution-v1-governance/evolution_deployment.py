"""Publisher drill authorization, independent rollback acceptance and scoped activation records."""
from __future__ import annotations

import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path

from evolution_acceptance import evaluator_hash, read_key, require_hash, seal, verified
from evolution_dataset import exact_fields, nonempty, timestamp
from evolution_release import publisher_key, sign_publisher, verify_publisher
from evolution_shadow import check_proofs
from eval_common import json_hash, context_errors

AUTH_CHECKS = {'internalAccounts', 'preproductionIsolation', 'runtimeConditions', 'applicability', 'retention'}
ROLLBACK_CHECKS = {'corruptPackage', 'hashMismatch', 'experienceWithdrawal', 'modelDrift',
                   'evaluatorFailure', 'loaderUnavailable', 'stopGeneration', 'stopShadow',
                   'prohibitActivation', 'returnStable', 'inflightVersion', 'noCandidateCache', 'preserveHistory'}
ACTIVATION_CHECKS = {'internalAccounts', 'runtimeConditions', 'applicability', 'monitoring', 'retention'}
RUN_PHASES = {'candidate-before', 'candidate-inflight', 'candidate-denied', 'baseline-after'}


def _now():
    return datetime.now(timezone.utc)


def _time(value):
    timestamp(value, 'deployment timestamp')
    return datetime.fromisoformat(value.replace('Z', '+00:00'))


def _accounts(values):
    if not isinstance(values, list) or not values or len(set(values)) != len(values):
        raise ValueError('Deployment requires a nonempty, distinct internal-account allowlist')
    for value in values:
        nonempty(value, 'internal account ID')
        if value != value.strip() or len(value) > 32 or value == '*':
            raise ValueError('Account IDs must be exact internal identities, never wildcard scope')
    return set(values)


def _package(artifact, publisher):
    approved = verify_publisher(artifact, publisher)
    pack = approved.get('package') or {}
    if (approved.get('kind') != 'APPROVED_METHOD_PACKAGE' or approved.get('schemaVersion') != 1
            or approved.get('status') != 'APPROVED_PENDING_VALIDATION'
            or approved.get('packageSha256') != json_hash(pack)
            or pack.get('kind') != 'METHOD_RELEASE_PACKAGE' or pack.get('schemaVersion') != 1
            or pack.get('evaluatorSha256') != evaluator_hash()):
        raise ValueError('Deployment requires an unchanged publisher-approved package and current evaluator')
    return approved, pack


def _shadow(artifact, approved_artifact, evaluator, publisher):
    approved, pack = _package(approved_artifact, publisher)
    shadow = verified(artifact, evaluator, 'SHADOW_ACCEPTANCE')
    if (shadow.get('schemaVersion') != 1 or shadow.get('status') != 'ACCEPTED_FOR_ROLLBACK_DRILL'
            or shadow.get('approvedArtifactSha256') != approved_artifact['payloadSha256']
            or shadow.get('packageSha256') != approved['packageSha256']
            or shadow.get('gateSha256') != pack['acceptanceGateSha256']
            or shadow.get('evaluatorSha256') != evaluator_hash()
            or shadow.get('candidateBundleSha256') != pack['bundle']['contentSha256']):
        raise ValueError('Independent shadow acceptance differs from the approved package or evaluator')
    return shadow, approved, pack


def _review(review, checks, proofs):
    nonempty(review['approver'], 'deployment approver')
    nonempty(review['reason'], 'deployment reason')
    if not _time(review['approvedAt']) <= _now() < _time(review['expiresAt']):
        raise ValueError('Deployment authorization must be effective and unexpired')
    _accounts(review['internalAccountIds'])
    check_proofs(review['checks'], checks, proofs)


def _controls(directory, bundle_id):
    # Enumerate once: missing/inaccessible storage is an error, not an absent stop marker.
    markers = {path.name for path in directory.iterdir()}
    if markers & {'PROHIBIT_ACTIVATION', 'STABLE_ONLY', 'REVOKE.' + bundle_id}:
        raise ValueError('Operator control prohibits authorizing this method; retain the existing artifacts and history')


def authorize_drill(approved_artifact, shadow_artifact, review, proofs, evaluator, publisher, controls):
    shadow, approved, pack = _shadow(shadow_artifact, approved_artifact, evaluator, publisher)
    exact_fields(review, {'approvedArtifactSha256', 'shadowAcceptanceSha256', 'activationId', 'approver',
                         'approvedAt', 'expiresAt', 'reason', 'internalAccountIds', 'checks'}, 'drill authorization')
    _review(review, AUTH_CHECKS, proofs)
    nonempty(review['activationId'], 'drill activationId')
    if (review['approvedArtifactSha256'] != approved_artifact['payloadSha256']
            or review['shadowAcceptanceSha256'] != shadow_artifact['payloadSha256']
            or not _accounts(review['internalAccountIds']) <= _accounts(shadow['internalAccountIds'])
            or _time(review['expiresAt']) > _time(shadow['retentionUntil'])):
        raise ValueError('Drill changed the reviewed package, internal-account scope or shadow retention boundary')
    _controls(controls, pack['bundle']['bundleId'])
    return sign_publisher({'kind': 'METHOD_ACTIVATION', 'schemaVersion': 1, 'mode': 'DRILL', **review,
                           'packageSha256': approved['packageSha256'], 'approvalSha256': approved['approvalSha256'],
                           'rollbackAcceptanceSha256': None, 'scope': pack['scope'],
                           'expectedComparisonIdentity': pack['expectedComparisonIdentity']}, publisher)


def _drill(artifact, approved_artifact, publisher):
    approved, pack = _package(approved_artifact, publisher)
    body = verify_publisher(artifact, publisher)
    if (body.get('kind') != 'METHOD_ACTIVATION' or body.get('schemaVersion') != 1 or body.get('mode') != 'DRILL'
            or body.get('approvedArtifactSha256') != approved_artifact['payloadSha256']
            or body.get('packageSha256') != approved['packageSha256'] or body.get('approvalSha256') != approved['approvalSha256']
            or body.get('scope') != pack['scope'] or body.get('expectedComparisonIdentity') != pack['expectedComparisonIdentity']
            or body.get('rollbackAcceptanceSha256') is not None):
        raise ValueError('Rollback drill authorization differs from the approved package and scope')
    return body, approved, pack


def _trace(proof_hash, proofs):
    require_hash(proof_hash, 'drill trace hash')
    raw = proofs.get(proof_hash)
    if not isinstance(raw, bytes) or hashlib.sha256(raw).hexdigest() != proof_hash:
        raise ValueError('Original drill Trace export is missing or changed')
    trace = json.loads(raw)
    steps = json.loads(trace['steps']) if isinstance(trace.get('steps'), str) else trace.get('steps')
    rows = [step.get('attributes') or {} for step in steps]
    method = [row for row in rows if row.get('kind') == 'ordinary-evidence']
    context = [row for row in rows if row.get('kind') == 'answer-context']
    calls = [row for row in rows if row.get('kind') == 'model-invocation' and row.get('scope') == 'final-answer']
    if len(method) != 1 or len(context) != 1 or len(calls) != 1 or trace.get('status') != 'success':
        raise ValueError('Drill needs complete ordinary Trace exports and a fresh final-answer invocation')
    if (context_errors(context[0], expected_scope="ordinary-final-answer")
            or not calls[0].get('modelName') or not calls[0].get('modelTier')):
        raise ValueError('Drill needs intact actual prompt/evidence capture and final model identity')
    if method[0].get('route') != 'FUNDAMENTALS' or method[0].get('analystStatus') != 'COMPLETED':
        raise ValueError('Drill must exercise the actual ordinary fundamentals analyst')
    invocation = method[0].get('analystInvocation') or {}
    if (invocation.get('kind') != 'model-invocation' or invocation.get('scope') != 'fundamentals-analysis'
            or invocation.get('methodBundle') != method[0].get('methodBundle')):
        raise ValueError('Drill must retain the actual analyst invocation and its pinned method identity')
    require_hash(invocation.get('actualSystemPromptSha256'), 'actual analyst system prompt')
    require_hash(invocation.get('actualUserPromptSha256'), 'actual analyst user prompt')
    return trace, method[0], context[0]


def accept_drill(approved_artifact, drill_artifact, review, proofs, evaluator, publisher):
    drill, _, pack = _drill(drill_artifact, approved_artifact, publisher)
    exact_fields(review, {'drillAuthorizationSha256', 'reviewer', 'reviewedAt', 'withdrawnAt', 'runs', 'checks'}, 'rollback review')
    nonempty(review['reviewer'], 'rollback reviewer')
    if (review['reviewer'] == drill['approver'] or review['drillAuthorizationSha256'] != drill_artifact['payloadSha256']
            or not _time(drill['approvedAt']) <= _time(review['reviewedAt']) <= _now() < _time(drill['expiresAt'])):
        raise ValueError('Rollback needs a separate reviewer, unchanged drill and timely acceptance')
    check_proofs(review['checks'], ROLLBACK_CHECKS, proofs)
    verify_drill_runs(drill, pack, drill_artifact['payloadSha256'], review, proofs)
    return seal({'kind': 'ROLLBACK_ACCEPTANCE', 'schemaVersion': 1, 'status': 'ACCEPTED_FOR_SCOPED_ACTIVATION', **review,
                 'approvedArtifactSha256': approved_artifact['payloadSha256'], 'evaluatorSha256': evaluator_hash(),
                 'internalAccountIds': drill['internalAccountIds'], 'scope': pack['scope'],
                 'expectedComparisonIdentity': pack['expectedComparisonIdentity']}, evaluator)


def verify_drill_runs(drill, pack, authorization_hash, review, proofs):
    """Shared raw-run checks; this alone grants no acceptance or activation authority."""
    exact_fields(review['runs'], RUN_PHASES, 'rollback runs')
    withdrawal = _time(review['withdrawnAt'])
    if not _time(drill['approvedAt']) <= withdrawal < _time(review['reviewedAt']):
        raise ValueError('Withdrawal must be inside the authorized drill before independent acceptance')
    run_ids, cases = set(), set()
    for phase, row in review['runs'].items():
        exact_fields(row, {'traceSha256', 'runId', 'caseSha256'}, 'rollback run')
        require_hash(row['caseSha256'], 'fixed drill case')
        trace, method, context = _trace(row['traceSha256'], proofs)
        selected = method.get('methodSelection') or {}
        started, completed = _time(selected.get('pinnedAt')), _time(method.get('analystCompletedAt'))
        bundle = method.get('methodBundle') or {}
        candidate = phase in {'candidate-before', 'candidate-inflight'}
        expected_bundle = pack['bundle'] if candidate else pack['rollback']
        expected_hash = expected_bundle['contentSha256' if candidate else 'bundleSha256']
        if (trace.get('traceId') != row['runId'] or row['runId'] in run_ids
                or trace.get('userId') not in drill['internalAccountIds']
                or selected.get('authorizationSha256') != authorization_hash
                or selected.get('mode') != 'DRILL' or selected.get('caseSha256') != row['caseSha256']
                or selected.get('comparisonIdentity') != pack['expectedComparisonIdentity']
                or selected.get('reason') != ('APPROVED_SCOPE' if candidate else 'BUNDLE_WITHDRAWN')
                or bundle.get('bundleId') != expected_bundle['bundleId'] or bundle.get('bundleSha256') != expected_hash
                or not isinstance(context.get('evidenceContext'), str)
                or context.get('evidenceSha256') != hashlib.sha256(context['evidenceContext'].encode()).hexdigest()
                or row['caseSha256'] != json_hash({'query': trace.get('userQuery'), 'evidenceSha256': context['evidenceSha256']})
                or not _time(drill['approvedAt']) <= started <= completed <= _time(review['reviewedAt'])):
            raise ValueError('Rollback run has mismatched identity, version, conditions, evidence or withdrawal outcome')
        if (phase == 'candidate-before' and completed >= withdrawal
                or phase == 'candidate-inflight' and not started < withdrawal < completed
                or not candidate and started < withdrawal):
            raise ValueError('Drill timeline does not prove before, in-flight and after-withdrawal behavior')
        cases.add((row['caseSha256'], context['evidenceSha256']))
        run_ids.add(row['runId'])
    if len(cases) != 1:
        raise ValueError('Rollback must rerun the same fixed case and evidence before and after withdrawal')


def authorize_serving(approved_artifact, shadow_artifact, drill_artifact, rollback_artifact, review, proofs,
                      evaluator, publisher, controls):
    shadow, approved, pack = _shadow(shadow_artifact, approved_artifact, evaluator, publisher)
    drill, _, _ = _drill(drill_artifact, approved_artifact, publisher)
    rollback = verified(rollback_artifact, evaluator, 'ROLLBACK_ACCEPTANCE')
    exact_fields(review, {'approvedArtifactSha256', 'shadowAcceptanceSha256', 'rollbackAcceptanceSha256', 'activationId',
                         'approver', 'approvedAt', 'expiresAt', 'reason', 'internalAccountIds', 'checks'}, 'serving authorization')
    _review(review, ACTIVATION_CHECKS, proofs)
    nonempty(review['activationId'], 'serving activationId')
    if (rollback.get('schemaVersion') != 1 or rollback.get('status') != 'ACCEPTED_FOR_SCOPED_ACTIVATION'
            or rollback.get('approvedArtifactSha256') != approved_artifact['payloadSha256']
            or rollback.get('drillAuthorizationSha256') != drill_artifact['payloadSha256']
            or drill.get('shadowAcceptanceSha256') != shadow_artifact['payloadSha256']
            or rollback.get('evaluatorSha256') != evaluator_hash()
            or rollback.get('scope') != pack['scope'] or rollback.get('expectedComparisonIdentity') != pack['expectedComparisonIdentity']
            or review['approvedArtifactSha256'] != approved_artifact['payloadSha256']
            or review['shadowAcceptanceSha256'] != shadow_artifact['payloadSha256']
            or review['rollbackAcceptanceSha256'] != rollback_artifact['payloadSha256']
            or not _accounts(review['internalAccountIds']) <= _accounts(rollback['internalAccountIds']) <= _accounts(shadow['internalAccountIds'])
            or review['activationId'] == drill['activationId'] or _time(review['approvedAt']) < _time(rollback['reviewedAt'])):
        raise ValueError('Serving authorization changed the accepted rollback, conditions or internal-account scope')
    _controls(controls, pack['bundle']['bundleId'])
    return sign_publisher({'kind': 'METHOD_ACTIVATION', 'schemaVersion': 1, 'mode': 'SERVING', **review,
                           'packageSha256': approved['packageSha256'], 'approvalSha256': approved['approvalSha256'],
                           'scope': pack['scope'], 'expectedComparisonIdentity': pack['expectedComparisonIdentity']}, publisher)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mode', required=True, choices=['authorize-drill', 'accept-drill', 'authorize-serving'])
    for name in ('input', 'review', 'evidence-index', 'output'):
        parser.add_argument('--' + name, required=True, type=Path)
    parser.add_argument('--control-directory', type=Path)
    args = parser.parse_args()
    if args.output.exists() or args.mode != 'accept-drill' and args.control_directory is None:
        parser.error('Use a new output directory; authorization requires --control-directory')
    try:
        from run_evolution_experiment import write_once
        read = lambda path: json.loads(path.read_bytes())
        paths = read(args.input)
        required = {'approvedArtifact', 'shadowAcceptance'} if args.mode == 'authorize-drill' else {'approvedArtifact', 'drillAuthorization'}
        if args.mode == 'authorize-serving':
            required |= {'shadowAcceptance', 'rollbackAcceptance'}
        exact_fields(paths, required, 'deployment input paths')
        inputs = {name: read(args.input.resolve().parent / path) for name, path in paths.items()}
        proofs = {digest: (args.evidence_index.resolve().parent / path).read_bytes() for digest, path in read(args.evidence_index).items()}
        evaluator, publisher = read_key(), publisher_key()
        if evaluator == publisher:
            raise ValueError('Evaluator and publisher credentials must be distinct')
        common = (read(args.review), proofs, evaluator, publisher)
        if args.mode == 'authorize-drill':
            result = authorize_drill(inputs['approvedArtifact'], inputs['shadowAcceptance'], *common, args.control_directory)
        elif args.mode == 'accept-drill':
            result = accept_drill(inputs['approvedArtifact'], inputs['drillAuthorization'], *common)
        else:
            result = authorize_serving(inputs['approvedArtifact'], inputs['shadowAcceptance'], inputs['drillAuthorization'],
                                       inputs['rollbackAcceptance'], *common, args.control_directory)
        args.output.mkdir(parents=True, exist_ok=False)
        name = 'rollback-acceptance.json' if args.mode == 'accept-drill' else 'activation-authorization.json'
        write_once(args.output / name, result)
        print(json.dumps({'status': 'ROLLBACK_ACCEPTED' if args.mode == 'accept-drill' else 'AUTHORIZATION_SIGNED',
                          'mode': args.mode, 'payloadSha256': result['payloadSha256'], 'output': str(args.output)}))
        return 0
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(2, f'Deployment record rejected: {error}. Check original traces, independent proofs and operator controls.\n')


if __name__ == '__main__':
    raise SystemExit(main())
