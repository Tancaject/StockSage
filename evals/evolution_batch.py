"""Compose immutable experience bundles for isolated AI diagnostics, never release.

The caller verifies failure attribution and member compatibility before composition.
Hashes bind those inputs; they do not establish review quality or approval.
"""
from __future__ import annotations

from evolution_candidates import (_hash, _seal, bundle_content_sha256, validate_method,
                                  validate_record)
from evolution_dataset import identifier
from eval_common import json_hash, sha256


def compose_diagnostic_bundle(parent_id: str, parent_method: str, experiences: list[dict],
                              fixed_contract_sha: str) -> dict:
    """Preserve the parent verbatim and append each independently conditioned lesson."""
    identifier(parent_id, 'parentBundleId')
    validate_method(parent_method)
    _hash(fixed_contract_sha, 'fixedContractSha256')
    if not isinstance(experiences, list) or not experiences:
        raise ValueError('Diagnostic composition requires a nonempty ordered experience list')
    members, sections, seen, forbidden = [], [], set(), []
    for experience in experiences:
        validate_record(experience)
        if experience['kind'] != 'RESEARCH_EXPERIENCE' or experience['schemaVersion'] != 2:
            raise ValueError('Diagnostic members must be versioned v2 experiences')
        digest = experience['recordSha256']
        if digest in seen:
            raise ValueError('Diagnostic members cannot repeat an experience')
        seen.add(digest)
        members.append({field: experience[field] for field in
                        ('experienceId', 'version', 'recordSha256', 'methodSha256')})
        forbidden.extend(source[field] for source in experience['sources']
                         for field in ('issuerId', 'issuerName'))
        sections.append('\n'.join([
            '任务条件：' + '、'.join(experience['triggerTags']),
            '证据条件：' + '、'.join(experience['requiredEvidence']),
            '所需能力：' + '、'.join(experience['requiredCapabilities']),
            '适用边界：' + experience['applicabilityBoundary'],
            '操作：' + experience['method'],
            '反例：' + '；'.join(experience['counterexamples']),
            '禁止推断：' + '；'.join(experience['forbiddenInferences']),
        ]))
    method = parent_method + '\n\n以下补充步骤仅在各自适用条件满足时采用；条件不满足时保留原有分析方法。\n\n' + '\n\n'.join(sections)
    validate_method(method, forbidden_literals=forbidden)
    record = _seal({'schemaVersion': 1, 'kind': 'DIAGNOSTIC_METHOD_BUNDLE',
                    'status': 'DIAGNOSTIC', 'diagnosticOnly': True, 'releaseEligible': False,
                    'parentBundleId': parent_id, 'parentMethodSha256': sha256(parent_method),
                    'fixedContractSha256': fixed_contract_sha, 'members': members,
                    'method': method, 'methodSha256': sha256(method)}, 'bundleId', 'diagnostic-')
    record['contentSha256'] = bundle_content_sha256(record['bundleId'], parent_id, fixed_contract_sha, method)
    return record


def validate_diagnostic_bundle(record: dict, parent_id: str, parent_method: str,
                               experiences: list[dict], fixed_contract_sha: str) -> None:
    """Rebuild against caller-owned inputs, including exact parent text and member order."""
    expected = compose_diagnostic_bundle(parent_id, parent_method, experiences, fixed_contract_sha)
    if not isinstance(record, dict) or json_hash(record) != json_hash(expected):
        raise ValueError('Diagnostic bundle differs from its frozen parent, ordered members or content')


def export_diagnostic_bundle(record: dict, parent_id: str, parent_method: str,
                             experiences: list[dict], fixed_contract_sha: str) -> dict:
    """Export only the existing isolated replay contract; this is not release evidence."""
    validate_diagnostic_bundle(record, parent_id, parent_method, experiences, fixed_contract_sha)
    return {'schemaVersion': 1, 'bundles': [{field: record[field] for field in
            ('bundleId', 'parentBundleId', 'method', 'fixedContractSha256', 'contentSha256')}]}
