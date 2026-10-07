"""Approve a bounded shadow batch and attest its independent acceptance; never activate."""
from __future__ import annotations

import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlsplit
from uuid import uuid4

from evolution_acceptance import (require_gate, validate_comparison_gate, verified, seal, evaluator_hash, read_key)
from evolution_candidates import bundle_content_sha256
from evolution_dataset import exact_fields, validate_cases, execution_payload, nonempty, timestamp, case_hash
from evolution_release import verify_publisher, sign_publisher, publisher_key
from evolution_rubric import DIMENSIONS
from eval_common import json_hash

AUTH_CHECKS = {'accountScope', 'isolatedInstance', 'modelOnlyEgress', 'restrictedStorage', 'baselineDelivery'}
ACCEPT_CHECKS = {'noBusinessWrites', 'baselineOnlyDelivery', 'overloadIsolation', 'privacyRetention', 'deploymentCompatibility'}


def execution_bytes(plan):
    return (json.dumps(plan['execution'], ensure_ascii=False, indent=2) + '\n').encode()


def approved_package(artifact, manifest, key):
    approved = verify_publisher(artifact, key)
    package = approved.get('package') or {}
    bundle = package.get('bundle') or {}
    if (approved.get('kind') != 'APPROVED_METHOD_PACKAGE' or approved.get('schemaVersion') != 1
            or approved.get('status') != 'APPROVED_PENDING_VALIDATION'
            or approved.get('packageSha256') != json_hash(package)
            or package.get('acceptanceGateSha256') != manifest['acceptanceGateSha256']
            or package.get('evaluatorSha256') != evaluator_hash()
            or package.get('expectedComparisonIdentity') != manifest['expectedComparisonIdentity']
            or bundle.get('bundleId') != manifest['candidateBundleId']
            or bundle.get('contentSha256') != manifest['candidateBundleSha256']
            or bundle.get('fixedContractSha256') != manifest['fixedContractSha256']):
        raise ValueError('Shadow requires the unchanged publisher-approved package under this evaluator, build and gate')
    if bundle_content_sha256(bundle['bundleId'], bundle['parentBundleId'], bundle['fixedContractSha256'], bundle['method']) != bundle['contentSha256']:
        raise ValueError('Approved shadow method bytes changed')
    return approved


def prepare_plan(sources, gate_artifact, approved_artifact, evaluator_key, publisher):
    gate = require_gate(gate_artifact, evaluator_key)
    index = validate_cases(sources)
    ids = gate['policy']['cohorts']['VALIDATION']['caseIds']
    if set(index) != set(ids) or any(case['split'] != 'VALIDATION' or case_hash(case) != gate['caseHashes'][case['caseId']] for case in sources):
        raise ValueError('Shadow replays exactly the authorized frozen validation cohort; do not expose or reuse holdout')
    package = verify_publisher(approved_artifact, publisher)['package']
    policy, baseline, bundle = gate['policy'], gate['baselineBundle'], package['bundle']
    repeats = policy['repeats']['VALIDATION']
    cases = [index[case_id] for case_id in ids]
    manifest = {'schema': 'fundamentals_evolution_comparison_manifest_v2', 'evaluationSplit': 'SHADOW',
                'acceptanceGateSha256': gate_artifact['payloadSha256'], 'selectionSha256': None,
                'baselineBundleId': baseline['bundleId'], 'baselineBundleSha256': baseline['bundleSha256'],
                'candidateBundleId': bundle['bundleId'], 'candidateBundleSha256': bundle['contentSha256'],
                'fixedContractSha256': baseline['fixedContractSha256'], 'expectedComparisonIdentity': gate['expectedComparisonIdentity'],
                **{name: policy[name] for name in ('minimumGroups', 'bootstrapSamples', 'confidenceLevel', 'seed', 'primaryDimension')},
                'nonRegressionDimensions': list(DIMENSIONS), 'maxRunsPerArm': len(cases) * repeats,
                'cases': [{'caseId': case['caseId'], 'issuerId': case['issuerId'], 'reportFamilyId': case['reportFamilyId'],
                           'caseSha256': case_hash(case), 'repeatIds': list(range(1, repeats + 1)),
                           'expectedEvidenceSnapshotSha256': execution_payload(case)['contextSha256'],
                           'expectedHistorySnapshotSha256': json_hash(case['history'])} for case in cases]}
    approved_package(approved_artifact, manifest, publisher)
    validate_comparison_gate(manifest, gate_artifact, evaluator_key)
    execution = {'schemaVersion': 2, 'context': {'experimentId': gate['experimentId'], 'evaluatorVersion': evaluator_hash(), 'runMode': 'SHADOW'},
                 'cases': [execution_payload(case) for case in cases],
                 'runs': [{'runId': str(uuid4()), 'caseId': case['caseId'], 'bundleId': arm, 'repeatId': repeat}
                          for case in cases for repeat in range(1, repeats + 1) for arm in (baseline['bundleId'], bundle['bundleId'])]}
    return {'schema': 'fundamentals_shadow_plan_v1', 'comparison': manifest, 'execution': execution}


def check_proofs(checks, names, proofs):
    exact_fields(checks, names, 'independent checks')
    for name, check in checks.items():
        exact_fields(check, {'status', 'evidenceSha256'}, 'independent check.' + name)
        raw = proofs.get(check['evidenceSha256'])
        if check['status'] != 'PASS' or not isinstance(raw, bytes) or hashlib.sha256(raw).hexdigest() != check['evidenceSha256']:
            raise ValueError('Independent proof missing, changed or not passed: ' + name)


def authorize(plan, sources, gate, approved_artifact, review, proofs, evaluator_key, publisher):
    from run_evolution_replay import run_cases
    exact_fields(plan, {'schema', 'comparison', 'execution'}, 'shadow plan')
    if plan['schema'] != 'fundamentals_shadow_plan_v1' or plan['comparison'].get('evaluationSplit') != 'SHADOW':
        raise ValueError('Expected a frozen shadow plan')
    exact_fields(plan['execution'], {'schemaVersion', 'context', 'cases', 'runs'}, 'shadow execution')
    expected = prepare_plan(sources, gate, approved_artifact, evaluator_key, publisher)
    if plan['comparison'] != expected['comparison'] or any(plan['execution'].get(name) != expected['execution'][name] for name in ('schemaVersion', 'context', 'cases')):
        raise ValueError('Shadow plan changed accepted cases, scope or comparison conditions')
    digest = hashlib.sha256(execution_bytes(plan)).hexdigest()
    for arm in ('baseline', 'candidate'):
        run_cases(plan['execution'], plan['comparison'][arm + 'BundleId'], digest, None, plan['comparison'], validate_only=True)
    if len(plan['execution']['runs']) != len(expected['execution']['runs']):
        raise ValueError('Shadow plan has extra or missing registered runs')
    exact_fields(review, {'planSha256', 'approver', 'approvedAt', 'internalAccountIds', 'endpoint', 'restrictedStore', 'retentionUntil', 'checks'}, 'shadow authorization')
    if review['planSha256'] != json_hash(plan):
        raise ValueError('Shadow authorization refers to another plan')
    nonempty(review['approver'], 'shadow approver')
    nonempty(review['restrictedStore'], 'restricted shadow store')
    accounts = review['internalAccountIds']
    if not isinstance(accounts, list) or not accounts or any(not isinstance(item, str) or not item.strip() for item in accounts) or len(set(accounts)) != len(accounts):
        raise ValueError('Shadow authorization needs distinct explicit internal account IDs')
    address = urlsplit(review['endpoint'])
    if (address.scheme not in {'https', 'http'} or not address.hostname or address.username or address.password
            or address.query or address.fragment or address.scheme == 'http' and address.hostname not in {'localhost', '127.0.0.1', '::1'}):
        raise ValueError('Shadow endpoint must use HTTPS or loopback HTTP without credentials, query or fragment')
    timestamp(review['approvedAt'], 'shadow approvedAt')
    timestamp(review['retentionUntil'], 'shadow retentionUntil')
    check_proofs(review['checks'], AUTH_CHECKS, proofs)
    approved = approved_package(approved_artifact, plan['comparison'], publisher)
    payload = {'kind': 'SHADOW_AUTHORIZATION', 'schemaVersion': 1, **review,
               'restrictedStore': str(Path(review['restrictedStore']).resolve()),
               'approvedArtifactSha256': approved_artifact['payloadSha256'], 'packageSha256': approved['packageSha256'],
               'approvalSha256': approved['approvalSha256'], 'comparisonSha256': json_hash(plan['comparison']),
               'executionFileSha256': digest}
    signed = sign_publisher(payload, publisher)
    verify_authorization(signed, approved_artifact, plan['comparison'], digest, publisher)
    return signed


def verify_authorization(artifact, approved_artifact, manifest, execution_hash, key, *, endpoint=None):
    if manifest.get('evaluationSplit') != 'SHADOW':
        raise ValueError('Shadow authorization cannot authorize another execution mode')
    approved = approved_package(approved_artifact, manifest, key)
    body = verify_publisher(artifact, key)
    if (body.get('kind') != 'SHADOW_AUTHORIZATION' or body.get('schemaVersion') != 1
            or body.get('comparisonSha256') != json_hash(manifest) or body.get('executionFileSha256') != execution_hash
            or body.get('approvedArtifactSha256') != approved_artifact['payloadSha256']
            or body.get('packageSha256') != approved['packageSha256'] or body.get('approvalSha256') != approved['approvalSha256']
            or endpoint is not None and body.get('endpoint') != endpoint):
        raise ValueError('Shadow authorization differs from the package, execution, comparison or endpoint')
    now = datetime.now(timezone.utc)
    if not datetime.fromisoformat(body['approvedAt'].replace('Z', '+00:00')) <= now < datetime.fromisoformat(body['retentionUntil'].replace('Z', '+00:00')):
        raise ValueError('Shadow authorization is not effective or its raw-data retention period has expired')
    return body


def accept_shadow(comparison, authorization, approved_artifact, review, proofs, evaluator_key, publisher):
    from evolution_acceptance import verified_comparison
    evidence = verified_comparison(comparison, evaluator_key)
    auth = verify_publisher(authorization, publisher)
    approved = verify_publisher(approved_artifact, publisher)
    if (comparison.get('releaseEvidence') != {name: value for name, value in evidence.items() if name != 'kind'}
            or evidence.get('evaluatorSha256') != evaluator_hash() or evidence.get('evaluationSplit') != 'SHADOW'
            or evidence.get('shadowChecksPassed') is not True or evidence.get('eligible') is not False
            or evidence.get('shadowAuthorizationSha256') != authorization['payloadSha256']
            or auth.get('kind') != 'SHADOW_AUTHORIZATION' or auth.get('comparisonSha256') != evidence.get('manifest_sha256')
            or auth.get('approvedArtifactSha256') != approved_artifact['payloadSha256']
            or evidence.get('candidateBundleSha256') != approved['package']['bundle']['contentSha256']):
        raise ValueError('Shadow acceptance requires unchanged signed quality, resource, provenance and review checks')
    exact_fields(review, {'comparisonEvidenceSha256', 'reviewer', 'reviewedAt', 'checks'}, 'shadow acceptance review')
    if review['comparisonEvidenceSha256'] != comparison['signedReleaseEvidence']['payloadSha256']:
        raise ValueError('Shadow acceptance refers to another comparison')
    nonempty(review['reviewer'], 'shadow acceptance reviewer')
    timestamp(review['reviewedAt'], 'shadow reviewedAt')
    expiry = datetime.fromisoformat(auth['retentionUntil'].replace('Z', '+00:00'))
    if datetime.now(timezone.utc) >= expiry or not datetime.fromisoformat(auth['approvedAt'].replace('Z', '+00:00')) <= datetime.fromisoformat(review['reviewedAt'].replace('Z', '+00:00')) < expiry:
        raise ValueError('Independent shadow review must occur within the authorized retention period')
    check_proofs(review['checks'], ACCEPT_CHECKS, proofs)
    return seal({'kind': 'SHADOW_ACCEPTANCE', 'schemaVersion': 1, 'status': 'ACCEPTED_FOR_ROLLBACK_DRILL', **review,
                 'approvedArtifactSha256': approved_artifact['payloadSha256'], 'packageSha256': approved['packageSha256'],
                 'shadowAuthorizationSha256': authorization['payloadSha256'], 'evaluatorSha256': evaluator_hash(),
                 'gateSha256': evidence['acceptanceGateSha256'], 'candidateBundleSha256': evidence['candidateBundleSha256'],
                 'internalAccountIds': auth['internalAccountIds'], 'retentionUntil': auth['retentionUntil']}, evaluator_key)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mode', required=True, choices=['prepare', 'authorize', 'accept'])
    for name in ('approved-artifact', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    for name in ('gate', 'source-cases', 'plan', 'review', 'evidence-index', 'comparison-result', 'authorization'):
        parser.add_argument('--' + name, type=Path)
    args = parser.parse_args()
    required = {'prepare': ['gate', 'source_cases'], 'authorize': ['gate', 'source_cases', 'plan', 'review', 'evidence_index'],
                'accept': ['comparison_result', 'authorization', 'review', 'evidence_index']}[args.mode]
    if args.output.exists() or any(getattr(args, name) is None for name in required):
        parser.error('Use a new output directory and supply: ' + ', '.join('--' + name.replace('_', '-') for name in required))
    try:
        from run_evolution_experiment import write_once
        from evolution_dataset import load_jsonl
        read = lambda path: json.loads(path.read_bytes().decode('utf-8'))
        evaluator, publisher = read_key(), publisher_key()
        if evaluator == publisher:
            raise ValueError('Publisher and evaluator credentials must be distinct')
        approved = read(args.approved_artifact)
        proofs = {digest: (args.evidence_index.resolve().parent / path).read_bytes() for digest, path in read(args.evidence_index).items()} if args.evidence_index else {}
        if args.mode == 'prepare':
            plan = prepare_plan(load_jsonl(args.source_cases), read(args.gate), approved, evaluator, publisher)
            outputs = {'shadow-plan.json': plan, 'authorization-review.json': {'planSha256': json_hash(plan), 'approver': None,
                'approvedAt': None, 'internalAccountIds': [], 'endpoint': None, 'restrictedStore': None, 'retentionUntil': None,
                'checks': {name: {'status': 'UNREVIEWED', 'evidenceSha256': None} for name in sorted(AUTH_CHECKS)}}}
            status = 'PENDING_SHADOW_AUTHORIZATION'
        elif args.mode == 'authorize':
            plan = read(args.plan)
            signed = authorize(plan, load_jsonl(args.source_cases), read(args.gate), approved, read(args.review), proofs, evaluator, publisher)
            outputs = {'shadow-authorization.json': signed, 'comparison.json': plan['comparison'], 'execution.json': plan['execution'],
                       'eval-bundles.json': {'schemaVersion': 1, 'bundles': [verify_publisher(approved, publisher)['package']['bundle']]}}
            status = 'SHADOW_AUTHORIZED'
        else:
            acceptance = accept_shadow(read(args.comparison_result), read(args.authorization), approved, read(args.review), proofs, evaluator, publisher)
            outputs = {'shadow-acceptance.json': acceptance}
            status = acceptance['payload']['status']
        args.output.mkdir(parents=True, exist_ok=False)
        for name, value in outputs.items():
            write_once(args.output / name, value)
        print(json.dumps({'status': status, 'output': str(args.output)}))
        return 0
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(2, f'Shadow workflow rejected: {error}. Check the frozen plan, approved artifact and independent proofs.\n')


if __name__ == '__main__':
    raise SystemExit(main())
