"""Offline PROPOSED experiences and single-method candidates; never approve or publish.

Static checks catch explicit syntax/scope violations, not arbitrary natural-language
intent. Independent review and evaluation remain required. A matching hash binds
content; it does not authenticate a reviewer, source, or E07 authorization.
"""
from __future__ import annotations

import argparse
import copy
import json
import re
from pathlib import Path

from evolution_dataset import _unique_object, exact_fields, identifier, nonempty
from ordinary_answer_quality import json_hash, sha256


EXPERIENCE_FIELDS = {'role', 'taskType', 'triggerTags', 'requiredEvidence', 'requiredCapabilities',
                     'method', 'applicabilityBoundary', 'counterexamples', 'parentExperienceHashes', 'sources'}
EXPERIENCE_V2_FIELDS = EXPERIENCE_FIELDS | {'version', 'forbiddenInferences', 'sourceRunIds'}
SOURCE_FIELDS = {'caseId', 'split', 'origin', 'issuerId', 'issuerName', 'traceSha256',
                 'externalFeedbackSha256', 'feedbackKind', 'issueType'}
COMMON_FIELDS = {'schemaVersion', 'kind', 'status', 'reviewStatus', 'validationStatus', 'recordSha256'}
CANDIDATE_FIELDS = {'parentBundleId', 'parentMethodSha256', 'changes', 'methodSha256',
                    'experienceRecordSha256', 'applicabilityBoundary', 'counterexamples',
                    'sourceExplanation', 'sources', 'generationMode'}
ATTRIBUTIONS = {'METHOD_ERROR': 'METHOD_CANDIDATE', 'MISSING_DATA': 'DATA_QUEUE',
                'UNSUPPORTED_CAPABILITY': 'DEFECT_QUEUE', 'EXECUTOR_ERROR': 'DEFECT_QUEUE',
                'PROVIDER_FAILURE': 'PROVIDER_QUEUE', 'EVALUATION_FAILURE': 'EVALUATION_QUEUE'}
CAPABILITIES = {'evidence-reading', 'period-comparison', 'unit-comparison', 'arithmetic'}
HASH = re.compile(r'[0-9a-f]{64}\Z')
# Conservative tripwires, not a claim that prose can be proven safe with regexes.
UNSAFE = re.compile(
    r'\[\s*E\d+\s*\]|(?<![A-Za-z0-9])E\d+(?![A-Za-z0-9])|```|<\s*script\b|'
    r'https?://|file://|[A-Za-z]:[\\/]|(?:^|\s)/(?:etc|tmp|home|usr)/|'
    r'\b(?:exec|eval|subprocess|os\.system)\s*\(|\b(?:import|require)\s*[ (]|'
    r'工具|模型|路由|系统提示|输出章节|输出契约|门禁|保留集|评分|发布|部署|下单|撤单|改单|'
    r'\b(?:tool|model|routing|system prompt|holdout|deploy|publish|approval|grader)\b|'
    r'忽略.{0,12}(?:规则|指令)|跳过.{0,12}(?:检查|审核)|'
    r'\d+(?:\.\d+)?\s*(?:%|亿元|万元|元|美元|亿|万|million|billion|USD|CNY|HKD)\b|'
    r'\d+(?:\.\d+)?\s*(?:亿元|万元|美元|元|亿|万|%)|'
    r'(?:revenue|profit|cash flow|收入|利润|营收|现金流).{0,16}\d', re.IGNORECASE)


def _hash(value: object, location: str) -> None:
    if not isinstance(value, str) or not HASH.fullmatch(value):
        raise ValueError(location + ': expected lowercase SHA-256')


def _text_list(value: object, location: str, *, allow_empty: bool = False) -> None:
    if not isinstance(value, list) or (not value and not allow_empty):
        raise ValueError(location + ': expected text list')
    for item in value:
        nonempty(item, location)
    if len(set(value)) != len(value):
        raise ValueError(location + ': duplicate item')


def validate_method(method: str, *, forbidden_literals: list[str] = ()) -> None:
    nonempty(method, 'fundamentals.method')
    if len(method.encode('utf-16-le')) // 2 > 2000:
        raise ValueError('fundamentals.method: exceeds 2000 Java characters')
    if any(ord(char) < 32 and char not in '\n\t' for char in method) or UNSAFE.search(method):
        raise ValueError('fundamentals.method: explicit scope escape, evidence reference, code, or company value')
    if any(literal.casefold() in method.casefold() for literal in forbidden_literals if literal):
        raise ValueError('fundamentals.method: case-specific issuer or answer literal')


def attribute_failure(issue_type: str) -> str:
    if not isinstance(issue_type, str) or issue_type not in ATTRIBUTIONS:
        raise ValueError('unknown failure attribution')
    return ATTRIBUTIONS[issue_type]


def _sources(sources: list[dict]) -> list[str]:
    if not isinstance(sources, list) or not sources:
        raise ValueError('sources: at least one development case and external feedback are required')
    identities, forbidden = set(), []
    for source in sources:
        exact_fields(source, SOURCE_FIELDS, 'source')
        identifier(source['caseId'], 'source.caseId')
        if source['split'] != 'DEVELOPMENT' or source['origin'] not in ('SYNTHETIC', 'PUBLIC_AUTHORIZED'):
            raise ValueError('source: only authorized development material is allowed')
        if source['feedbackKind'] not in ('INDEPENDENT_REVIEW', 'VERIFIED_CORRECTION', 'SYNTHETIC_FIXTURE'):
            raise ValueError('source: external feedback is required, not model self-review')
        if source['origin'] == 'PUBLIC_AUTHORIZED' and source['feedbackKind'] == 'SYNTHETIC_FIXTURE':
            raise ValueError('source: synthetic feedback cannot label real material')
        if attribute_failure(source['issueType']) != 'METHOD_CANDIDATE':
            raise ValueError('source: only a method failure can propose an experience')
        for field in ('traceSha256', 'externalFeedbackSha256'):
            _hash(source[field], 'source.' + field)
        for field in ('issuerId', 'issuerName'):
            nonempty(source[field], 'source.' + field)
            forbidden.append(source[field])
        identity = (source['caseId'], source['traceSha256'], source['externalFeedbackSha256'])
        if identity in identities:
            raise ValueError('source: duplicate provenance')
        identities.add(identity)
    return forbidden


def _experience_spec(spec: dict) -> None:
    v2 = isinstance(spec, dict) and 'version' in spec
    exact_fields(spec, EXPERIENCE_V2_FIELDS if v2 else EXPERIENCE_FIELDS, 'experience spec')
    if v2:
        if type(spec['version']) is not int or spec['version'] < 1:
            raise ValueError('experience version must be a positive integer')
        for field in ('forbiddenInferences', 'sourceRunIds'):
            _text_list(spec[field], field)
        for text in spec['forbiddenInferences']:
            validate_method(text)
        for run in spec['sourceRunIds']:
            if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,119}', run):
                raise ValueError('sourceRunIds must reference registered execution identifiers')
    if spec['role'] != 'FUNDAMENTALS' or spec['taskType'] != 'ORDINARY_FUNDAMENTALS':
        raise ValueError('experience: unsupported role or task type')
    for field in ('triggerTags', 'requiredEvidence', 'requiredCapabilities'):
        _text_list(spec[field], field)
        for tag in spec[field]:
            identifier(tag, field)
    if not set(spec['requiredCapabilities']) <= CAPABILITIES:
        raise ValueError('experience: unsupported prerequisite capability')
    validate_method(spec['applicabilityBoundary'])
    _text_list(spec['counterexamples'], 'counterexamples')
    for counterexample in spec['counterexamples']:
        validate_method(counterexample)
    _text_list(spec['parentExperienceHashes'], 'parentExperienceHashes', allow_empty=True)
    for parent in spec['parentExperienceHashes']:
        _hash(parent, 'parentExperienceHashes')
    forbidden = _sources(spec['sources'])
    for text in [spec['method'], spec['applicabilityBoundary'], *spec['counterexamples'], *spec.get('forbiddenInferences', [])]:
        validate_method(text, forbidden_literals=forbidden)


def _seal(body: dict, id_field: str, prefix: str) -> dict:
    record = copy.deepcopy(body)
    digest = json_hash(record)
    record[id_field], record['recordSha256'] = prefix + digest[:24], digest
    return record


def _base(kind: str) -> dict:
    return {'schemaVersion': 1, 'kind': kind, 'status': 'PROPOSED',
            'reviewStatus': 'UNREVIEWED', 'validationStatus': 'NOT_RUN'}


def build_experience(spec: dict, source_artifacts: dict[str, str], *, parents: list[dict] = ()) -> dict:
    """Bind supplied trace/feedback bytes; callers retain their independently governed artifacts."""
    _experience_spec(spec)
    for source in spec['sources']:
        for field in ('traceSha256', 'externalFeedbackSha256'):
            digest = source[field]
            text = source_artifacts.get(digest)
            if not isinstance(text, str) or not text.strip() or sha256(text) != digest:
                raise ValueError('source artifact is missing or its hash mismatches')
    for parent in parents:
        validate_record(parent)
        if parent['kind'] != 'RESEARCH_EXPERIENCE':
            raise ValueError('parent must be an experience')
    if len(parents) != len(spec['parentExperienceHashes']) or {p['recordSha256'] for p in parents} != set(spec['parentExperienceHashes']):
        raise ValueError('parent experience hashes must resolve exactly')
    if 'version' in spec and spec['version'] != 1 + max((parent.get('version', 1) for parent in parents), default=0):
        raise ValueError('experience version must follow its immutable parent versions')
    return _seal({**_base('RESEARCH_EXPERIENCE'), 'schemaVersion': 2 if 'version' in spec else 1,
                  **copy.deepcopy(spec), 'methodSha256': sha256(spec['method'])},
                 'experienceId', 'exp-')


def experience_matches(experience: dict, task_tags: set[str], evidence_tags: set[str], capabilities: set[str]) -> bool:
    """Offline condition matching only; PROPOSED matches never authorize runtime loading."""
    validate_record(experience)
    if experience['kind'] != 'RESEARCH_EXPERIENCE':
        raise ValueError('expected experience')
    return (set(experience['triggerTags']) <= task_tags and set(experience['requiredEvidence']) <= evidence_tags
            and set(experience['requiredCapabilities']) <= capabilities)


def compile_candidate(parent_bundle_id: str, parent_method: str, proposal: dict, experience: dict,
                      *, generation_mode: str = 'EXTERNAL_PROPOSAL') -> dict:
    """Freeze one complete replacement; no experience is appended dynamically during evaluation."""
    identifier(parent_bundle_id, 'parentBundleId')
    validate_method(parent_method)
    validate_record(experience)
    if experience['kind'] != 'RESEARCH_EXPERIENCE':
        raise ValueError('candidate requires one experience')
    exact_fields(proposal, {'changes', 'sourceExplanation'}, 'proposal')
    exact_fields(proposal['changes'], {'fundamentals.method'}, 'proposal.changes')
    validate_method(proposal['sourceExplanation'])
    method = proposal['changes']['fundamentals.method']
    validate_method(method, forbidden_literals=_sources(experience['sources']))
    if method == parent_method or experience['method'] not in method:
        raise ValueError('candidate must change the parent and compile the selected experience method')
    if generation_mode not in ('EXTERNAL_PROPOSAL', 'INJECTED_INVOKE'):
        raise ValueError('unsupported generation mode')
    return _seal({**_base('METHOD_CANDIDATE'), 'parentBundleId': parent_bundle_id,
                  'parentMethodSha256': sha256(parent_method), 'changes': copy.deepcopy(proposal['changes']),
                  'methodSha256': sha256(method), 'experienceRecordSha256': experience['recordSha256'],
                  'applicabilityBoundary': experience['applicabilityBoundary'],
                  'counterexamples': copy.deepcopy(experience['counterexamples']),
                  'sourceExplanation': proposal['sourceExplanation'], 'sources': copy.deepcopy(experience['sources']),
                  'generationMode': generation_mode}, 'candidateId', 'candidate-')


def generate_candidate(parent_bundle_id: str, parent_method: str, experience: dict, *, invoke,
                       require_e07_gate=None) -> dict:
    """One injected generation call. Trusted orchestration must enforce E07; default is deny.

    The gate callback must raise on failure; its return value is not a credential.
    This module has no provider, credentials, holdout reader, approval writer, or
    release API. INJECTED_INVOKE records the code path, not proof of a real model call.
    """
    if require_e07_gate is None:
        raise PermissionError('E07 gate is required before candidate generation')
    require_e07_gate()
    validate_record(experience)
    if experience['kind'] != 'RESEARCH_EXPERIENCE':
        raise ValueError('candidate generation requires an experience')
    identifier(parent_bundle_id, 'parentBundleId')
    validate_method(parent_method)
    proposal = invoke({'target': 'fundamentals.method', 'parentBundleId': parent_bundle_id,
                       'parentMethod': parent_method, 'experience': copy.deepcopy(experience)})
    return compile_candidate(parent_bundle_id, parent_method, proposal, experience, generation_mode='INJECTED_INVOKE')


def validate_record(record: dict) -> None:
    if not isinstance(record, dict) or record.get('kind') not in ('RESEARCH_EXPERIENCE', 'METHOD_CANDIDATE'):
        raise ValueError('unsupported artifact kind')
    is_experience = record['kind'] == 'RESEARCH_EXPERIENCE'
    id_field, prefix = ('experienceId', 'exp-') if is_experience else ('candidateId', 'candidate-')
    experience_fields = EXPERIENCE_V2_FIELDS if record.get('schemaVersion') == 2 else EXPERIENCE_FIELDS
    fields = experience_fields | {'methodSha256'} if is_experience else CANDIDATE_FIELDS
    exact_fields(record, COMMON_FIELDS | fields | {id_field}, 'record')
    if type(record['schemaVersion']) is not int or record['schemaVersion'] not in ({1, 2} if is_experience else {1}):
        raise ValueError('unsupported schemaVersion')
    if (record['status'], record['reviewStatus'], record['validationStatus']) != ('PROPOSED', 'UNREVIEWED', 'NOT_RUN'):
        raise ValueError('this store accepts only unreviewed, unevaluated PROPOSED artifacts')
    body = {key: value for key, value in record.items() if key not in ('recordSha256', id_field)}
    digest = json_hash(body)
    if record['recordSha256'] != digest or record[id_field] != prefix + digest[:24]:
        raise ValueError('record identity or hash mismatch')
    if is_experience:
        _experience_spec({key: record[key] for key in experience_fields})
        method = record['method']
    else:
        identifier(record['parentBundleId'], 'parentBundleId')
        for field in ('parentMethodSha256', 'experienceRecordSha256'):
            _hash(record[field], field)
        exact_fields(record['changes'], {'fundamentals.method'}, 'changes')
        method = record['changes']['fundamentals.method']
        validate_method(method, forbidden_literals=_sources(record['sources']))
        validate_method(record['sourceExplanation'])
        validate_method(record['applicabilityBoundary'])
        _text_list(record['counterexamples'], 'counterexamples')
        for counterexample in record['counterexamples']:
            validate_method(counterexample)
        if record['generationMode'] not in ('EXTERNAL_PROPOSAL', 'INJECTED_INVOKE'):
            raise ValueError('unsupported generation mode')
    if record['methodSha256'] != sha256(method):
        raise ValueError('method hash mismatch')


def _java_frame(value: str | None) -> str:
    return '-1:' if value is None else str(len(value.encode('utf-16-le')) // 2) + ':' + value


def bundle_content_sha256(bundle_id: str, parent_bundle_id: str | None, fixed_contract_sha256: str,
                          method: str) -> str:
    """AgentPolicyBundle fingerprint framing uses Java UTF-16 lengths, then UTF-8 SHA-256."""
    return sha256(''.join(_java_frame(value) for value in (
        'FUNDAMENTALS_METHOD_V1', bundle_id, parent_bundle_id, fixed_contract_sha256, method)))


def export_eval_bundle(candidate: dict, fixed_contract_sha256: str) -> dict:
    """Export frozen candidate bytes for isolated evaluation, without granting approval.

    The caller must obtain fixed_contract_sha256 from a trusted build or baseline
    replay identity. Its syntactic validity and the resulting hash do not authenticate it.
    """
    validate_record(candidate)
    if candidate['kind'] != 'METHOD_CANDIDATE':
        raise ValueError('eval bundle export requires a candidate')
    _hash(fixed_contract_sha256, 'fixedContractSha256')
    method = candidate['changes']['fundamentals.method']
    bundle_id, parent_id = candidate['candidateId'], candidate['parentBundleId']
    return {'schemaVersion': 1, 'bundles': [{
        'bundleId': bundle_id, 'parentBundleId': parent_id, 'method': method,
        'fixedContractSha256': fixed_contract_sha256,
        'contentSha256': bundle_content_sha256(bundle_id, parent_id, fixed_contract_sha256, method)}]}


def _save_new_json(path: Path, value: dict) -> None:
    with path.open('x', encoding='utf-8', newline='\n') as stream:
        stream.write(json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + '\n')


def save_immutable(path: Path, record: dict) -> None:
    validate_record(record)
    _save_new_json(path, record)


def load_record(path: Path) -> dict:
    record = json.loads(path.read_text(encoding='utf-8'), object_pairs_hook=_unique_object)
    validate_record(record)
    return record


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--proposal', required=True, type=Path, help='External JSON proposal, not a generator command')
    parser.add_argument('--experience', required=True, type=Path)
    parser.add_argument('--parent-bundle', required=True)
    parser.add_argument('--parent-method', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--eval-bundle-output', type=Path, help='New isolated-eval bundle file; never production approval')
    parser.add_argument('--fixed-contract-sha256', help='Identity from a trusted build or baseline replay; required with eval export')
    args = parser.parse_args()
    try:
        if bool(args.eval_bundle_output) != bool(args.fixed_contract_sha256):
            raise ValueError('--eval-bundle-output and --fixed-contract-sha256 must be supplied together')
        proposal = json.loads(args.proposal.read_text(encoding='utf-8'), object_pairs_hook=_unique_object)
        candidate = compile_candidate(args.parent_bundle, args.parent_method.read_text(encoding='utf-8'),
                                      proposal, load_record(args.experience))
        eval_bundle = export_eval_bundle(candidate, args.fixed_contract_sha256) if args.eval_bundle_output else None
        save_immutable(args.output, candidate)
        if args.eval_bundle_output:
            _save_new_json(args.eval_bundle_output, eval_bundle)
    except (OSError, ValueError, TypeError) as error:
        print(json.dumps({'status': 'INVALID', 'error': str(error)}, ensure_ascii=False))
        return 2
    print(json.dumps({'status': 'PROPOSED', 'candidateId': candidate['candidateId'],
                      'recordSha256': candidate['recordSha256'], 'reviewStatus': 'UNREVIEWED',
                      'validationStatus': 'NOT_RUN', 'generationMode': 'EXTERNAL_PROPOSAL'}, ensure_ascii=False))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
