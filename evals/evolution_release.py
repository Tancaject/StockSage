"""Publisher-owned method packages. Approval prepares validation, never activation."""
from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import os
from pathlib import Path

from evolution_acceptance import require_gate, verified, evaluator_hash, require_hash, read_key
from evolution_candidates import validate_record, export_eval_bundle
from evolution_experiences import load_registry, unavailable_reason
from evolution_dataset import exact_fields, nonempty, timestamp
from eval_common import json_hash

SCHEMA = 'fundamentals_publisher_artifact_v1'
CHECKS = {'scope', 'negativeCases', 'cost', 'privacy', 'dependencies', 'rollback'}


def prepare_package(candidate, registry, gate_artifact, evidence_artifact, build_identity, evaluator_key):
    gate = require_gate(gate_artifact, evaluator_key)
    evidence = verified(evidence_artifact, evaluator_key, 'RELEASE_EVIDENCE')
    validate_record(candidate)
    if (evidence.get('schema') != 'fundamentals_evolution_release_evidence_v2'
            or evidence.get('evaluatorSha256') != evaluator_hash()
            or evidence.get('acceptanceGateSha256') != gate_artifact['payloadSha256']
            or evidence.get('evaluationSplit') != 'HOLDOUT' or evidence.get('comparisonStatus') != 'IMPROVED'
            or evidence.get('eligible') is not True or evidence.get('authorization') != 'PENDING_HUMAN_APPROVAL'
            or evidence.get('expectedComparisonIdentity') != gate['expectedComparisonIdentity']):
        raise ValueError('Publication requires unchanged accepted holdout evidence under the current E07 evaluator')
    for field in ('reviewAuditSha256', 'resourceAuditSha256', 'selectionSha256', 'sourceCasesSha256', 'goldSha256'):
        require_hash(evidence.get(field), 'release evidence.' + field)
    if (evidence['sourceCasesSha256'] != gate['review']['sourceCasesSha256']
            or evidence['goldSha256'] != gate['review']['goldSha256']
            or evidence['fixedContractSha256'] != gate['baselineBundle']['fixedContractSha256']):
        raise ValueError('Release evidence changed the accepted data, labels or fixed contract')
    bundle = export_eval_bundle(candidate, evidence['fixedContractSha256'])['bundles'][0]
    if bundle['contentSha256'] != evidence.get('candidateBundleSha256'):
        raise ValueError('Publication candidate differs from the independently evaluated method bytes')
    body = load_registry(registry, evaluator_key)
    digest = candidate['experienceRecordSha256']
    entry = body['entries'].get(digest)
    if (not entry or entry['state'] != 'APPROVED' or unavailable_reason(body, digest)
            or entry['gateSha256'] != gate_artifact['payloadSha256']
            or entry['evaluatedCandidateSha256'] != candidate['recordSha256']):
        raise ValueError('Package must reference an available approved experience for this exact candidate and gate')
    rollback = gate['baselineBundle']
    if candidate['parentBundleId'] != rollback['bundleId']:
        raise ValueError('Initial publication must retain the accepted baseline as its rollback target')
    if (json_hash(build_identity) != gate['expectedComparisonIdentity']['runtimeBuildSha256']
            or build_identity.get('status') != 'VERIFIED_ARTIFACT'
            or build_identity.get('runtimeArtifact', {}).get('scope') != 'CODE_SOURCE_FILE'
            or build_identity.get('manifest', {}).get('artifactFormat') != 'SPRING_BOOT_JAR'
            or build_identity['runtimeArtifact'].get('sha256') != build_identity['manifest'].get('artifactSha256')):
        raise ValueError('Package must use the exact packaged build that passed independent evaluation')
    experience = entry['record']
    return {'kind': 'METHOD_RELEASE_PACKAGE', 'schemaVersion': 1, 'bundle': bundle,
            'scope': {'route': 'ORDINARY_FUNDAMENTALS', 'taskTags': experience['triggerTags'],
                      'requiredEvidence': experience['requiredEvidence'], 'requiredCapabilities': experience['requiredCapabilities'],
                      'applicabilityBoundary': experience['applicabilityBoundary']},
            'experience': {'recordSha256': digest, 'version': experience['version'], 'registrySha256': registry['payloadSha256']},
            'candidateSha256': candidate['recordSha256'], 'releaseEvidenceSha256': evidence_artifact['payloadSha256'],
            'acceptanceGateSha256': gate_artifact['payloadSha256'], 'evaluatorSha256': evaluator_hash(),
            'buildIdentity': build_identity, 'expectedComparisonIdentity': gate['expectedComparisonIdentity'],
            'rollback': {'bundleId': rollback['bundleId'], 'bundleSha256': rollback['bundleSha256']}}


def publisher_key():
    key = os.environ.get('STOCKSAGE_EVOLUTION_PUBLISHER_KEY', '').encode()
    if len(key) < 32:
        raise ValueError('Configure a separate STOCKSAGE_EVOLUTION_PUBLISHER_KEY of at least 32 UTF-8 bytes')
    return key


def sign_publisher(payload, key):
    if len(key) < 32:
        raise ValueError('Publisher key must contain at least 32 bytes')
    raw = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()
    return {'schema': SCHEMA, 'payloadBase64': base64.b64encode(raw).decode(),
            'payloadSha256': hashlib.sha256(raw).hexdigest(), 'signature': hmac.new(key, raw, hashlib.sha256).hexdigest()}


def verify_publisher(artifact, key):
    exact_fields(artifact, {'schema', 'payloadBase64', 'payloadSha256', 'signature'}, 'publisher artifact')
    if artifact['schema'] != SCHEMA or len(key) < 32:
        raise ValueError('Unsupported publisher artifact or missing publisher key')
    raw = base64.b64decode(artifact['payloadBase64'], validate=True)
    if (hashlib.sha256(raw).hexdigest() != artifact['payloadSha256']
            or not hmac.compare_digest(hmac.new(key, raw, hashlib.sha256).hexdigest(), artifact['signature'])):
        raise ValueError('Publisher signature or immutable package content changed')
    return json.loads(raw)


def approve_package(package, approval, key, evidence_artifacts):
    exact_fields(approval, {'packageSha256', 'approver', 'approvedAt', 'reason', 'checks'}, 'publisher approval')
    if approval['packageSha256'] != json_hash(package):
        raise ValueError('Human approval refers to different package content')
    nonempty(approval['approver'], 'publisher approver')
    nonempty(approval['reason'], 'publisher reason')
    timestamp(approval['approvedAt'], 'publisher approvedAt')
    exact_fields(approval['checks'], CHECKS, 'publisher checks')
    for name, check in approval['checks'].items():
        exact_fields(check, {'status', 'evidenceSha256'}, 'publisher check.' + name)
        if check['status'] != 'PASS':
            raise ValueError('Publisher check has not passed: ' + name)
        require_hash(check['evidenceSha256'], 'publisher check evidence')
        proof = evidence_artifacts.get(check['evidenceSha256'])
        if not isinstance(proof, bytes) or hashlib.sha256(proof).hexdigest() != check['evidenceSha256']:
            raise ValueError('Publisher review evidence is missing or changed: ' + name)
    decision = sign_publisher({'kind': 'METHOD_PUBLISHER_APPROVAL', **approval}, key)
    artifact = sign_publisher({'kind': 'APPROVED_METHOD_PACKAGE', 'schemaVersion': 1,
                              'status': 'APPROVED_PENDING_VALIDATION', 'package': package,
                              'packageSha256': json_hash(package), 'approvalSha256': decision['payloadSha256']}, key)
    return artifact, decision


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mode', required=True, choices=['prepare', 'approve'])
    for name in ('input', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--approval', type=Path)
    parser.add_argument('--approval-evidence', type=Path)
    args = parser.parse_args()
    if args.output.exists() or args.mode == 'approve' and (args.approval is None or args.approval_evidence is None):
        parser.error('Use a new output directory; approve requires --approval and --approval-evidence')
    try:
        from run_evolution_experiment import write_once
        read = lambda path: json.loads(Path(path).read_bytes().decode('utf-8'))
        paths = read(args.input)
        exact_fields(paths, {'candidate', 'registry', 'gate', 'releaseEvidence', 'buildIdentity'}, 'publisher inputs')
        inputs = {name: read(args.input.resolve().parent / path) for name, path in paths.items()}
        evaluator_key = read_key()
        package = prepare_package(inputs['candidate'], inputs['registry'], inputs['gate'], inputs['releaseEvidence'],
                                  inputs['buildIdentity'], evaluator_key)
        outputs = {'package.json': package}
        status = 'PENDING_PUBLISHER_APPROVAL'
        if args.mode == 'prepare':
            outputs['approval-template.json'] = {'packageSha256': json_hash(package), 'approver': None,
                'approvedAt': None, 'reason': None, 'checks': {name: {'status': 'UNREVIEWED', 'evidenceSha256': None} for name in sorted(CHECKS)}}
        else:
            key = publisher_key()
            if key == evaluator_key:
                raise ValueError('Evaluator and publisher must use different protected credentials')
            evidence_index = read(args.approval_evidence)
            proofs = {digest: (args.approval_evidence.resolve().parent / path).read_bytes() for digest, path in evidence_index.items()}
            artifact, approval = approve_package(package, read(args.approval), key, proofs)
            outputs.update({'approved-artifact.json': artifact, 'publisher-approval.json': approval})
            status = 'APPROVED_PENDING_VALIDATION'
        args.output.mkdir(parents=True, exist_ok=False)
        for name, value in outputs.items():
            write_once(args.output / name, value)
        print(json.dumps({'status': status, 'packageSha256': json_hash(package), 'output': str(args.output)}))
        return 0
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(2, f'Method publication rejected: {error}. Check the accepted candidate, current registry and independent approval.\n')


if __name__ == '__main__':
    raise SystemExit(main())
