"""Prepare and approve a FUNDAMENTALS method package for serving.

prepare: checks the accepted holdout evidence and the approved experience, then writes package.json.
approve: binds a human approval to that exact package and writes approved-method.json; its sha256
is printed for the backend setting stocksage.evolution.fundamentals.approved-artifact-sha256.
Whether the package is actually served is decided by the backend rollout settings, not here.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

from evolution_acceptance import require_gate, verified, evaluator_hash, require_hash, read_key
from evolution_candidates import validate_record, export_eval_bundle, require_catalog_tags
from evolution_experiences import load_registry, unavailable_reason
from evolution_dataset import exact_fields, nonempty, timestamp
from eval_common import json_hash

CONDITIONS = ('modelConfigSha256', 'fixedFinalPromptSha256')


def prepare_package(candidate, registry, gate_artifact, evidence_artifact, evaluator_key):
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
    if candidate['parentBundleId'] != gate['baselineBundle']['bundleId']:
        raise ValueError('Initial publication must use the accepted baseline as its parent')
    experience = entry['record']
    require_catalog_tags(experience['triggerTags'], experience['requiredEvidence'], experience['requiredCapabilities'])
    identity = gate['expectedComparisonIdentity']
    for name in CONDITIONS:
        require_hash(identity.get(name), 'expectedComparisonIdentity.' + name)
    return {'kind': 'APPROVED_METHOD', 'schemaVersion': 2, 'bundle': bundle,
            'scope': {'route': 'ORDINARY_FUNDAMENTALS', 'taskTags': experience['triggerTags'],
                      'requiredEvidence': experience['requiredEvidence'], 'requiredCapabilities': experience['requiredCapabilities'],
                      'applicabilityBoundary': experience['applicabilityBoundary']},
            'expectedConditions': {name: identity[name] for name in CONDITIONS},
            'experience': {'recordSha256': digest, 'version': experience['version']},
            'evidence': {'releaseEvidenceSha256': evidence_artifact['payloadSha256'],
                         'acceptanceGateSha256': gate_artifact['payloadSha256'], 'evaluatorSha256': evaluator_hash()}}


def approve_package(package, approval):
    exact_fields(approval, {'packageSha256', 'approver', 'approvedAt', 'reason'}, 'approval')
    if approval['packageSha256'] != json_hash(package):
        raise ValueError('Human approval refers to different package content')
    nonempty(approval['approver'], 'approver')
    nonempty(approval['reason'], 'reason')
    timestamp(approval['approvedAt'], 'approvedAt')
    return {**package, 'approval': {name: approval[name] for name in ('approver', 'approvedAt', 'reason')}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mode', required=True, choices=['prepare', 'approve'])
    parser.add_argument('--input', type=Path, required=True,
                        help='prepare: JSON mapping candidate/registry/gate/releaseEvidence to files; approve: package.json')
    parser.add_argument('--output', type=Path, required=True, help='New output directory')
    parser.add_argument('--approval', type=Path, help='approve: {packageSha256, approver, approvedAt, reason}')
    args = parser.parse_args()
    if args.output.exists() or args.mode == 'approve' and args.approval is None:
        parser.error('Use a new output directory; approve requires --approval')
    read = lambda path: json.loads(Path(path).read_bytes().decode('utf-8'))
    try:
        if args.mode == 'prepare':
            paths = read(args.input)
            exact_fields(paths, {'candidate', 'registry', 'gate', 'releaseEvidence'}, 'release inputs')
            inputs = {name: read(args.input.resolve().parent / path) for name, path in paths.items()}
            package = prepare_package(inputs['candidate'], inputs['registry'], inputs['gate'], inputs['releaseEvidence'], read_key())
            outputs = {'package.json': package,
                       'approval-template.json': {'packageSha256': json_hash(package), 'approver': None, 'approvedAt': None, 'reason': None}}
        else:
            outputs = {'approved-method.json': approve_package(read(args.input), read(args.approval))}
        args.output.mkdir(parents=True, exist_ok=False)
        written = {}
        for name, value in outputs.items():
            data = (json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
            with (args.output / name).open('xb') as handle:
                handle.write(data)
            written[name] = hashlib.sha256(data).hexdigest()
        print(json.dumps({'mode': args.mode, 'output': str(args.output), 'sha256': written}, ensure_ascii=False))
        return 0
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(2, f'Method release rejected: {error}. Check the accepted candidate, registry and approval.\n')


if __name__ == '__main__':
    raise SystemExit(main())
