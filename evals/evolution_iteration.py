"""Produce an evidence-bound iteration record; missing real evidence leaves it OPEN.

This reads artifacts and writes a new report directory. It does not generate
candidates, call models, deploy methods, or schedule subsequent iterations.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path

from evolution_acceptance import read_key, require_gate, verified, verified_comparison
from evolution_candidates import attribute_failure, export_eval_bundle, validate_record
from evolution_dataset import exact_fields, identifier, nonempty, timestamp, load_jsonl
from evolution_deployment import _drill, _shadow
from evolution_experiences import load_registry
from evolution_observation import observe
from evolution_reflection import verify_feedback
from evolution_release import prepare_package, publisher_key, verify_publisher
from evolution_shadow import check_proofs
from eval_common import json_hash

SCHEMA = 'fundamentals_iteration_manifest_v1'
COMMON = {'gate', 'candidate', 'registry', 'validation', 'selection', 'holdout', 'sourceCases', 'gold', 'baselineReport'}
RELEASE = {'build', 'approved', 'shadow', 'drill', 'rollback', 'activation', 'execution', 'qualityReviews', 'operationalReviews'}
ARTIFACTS = COMMON | RELEASE | {'offlineDrill', 'search'} | {
    stage + name for stage in ('validation', 'holdout') for name in ('Manifest', 'Baseline', 'Candidate')}
CLOSURES = {'CLOSED_RELEASED', 'CLOSED_BASELINE_RETAINED', 'CLOSED_INCONCLUSIVE'}


def load_inputs(path):
    manifest = json.loads(path.read_bytes())
    exact_fields(manifest, {'schema', 'iterationId', 'actor', 'recordedAt', 'artifacts', 'proofs',
                            'unresolved', 'nextBatch', 'closure'}, 'iteration manifest')
    if manifest['schema'] != SCHEMA or not set(manifest['artifacts']) <= ARTIFACTS:
        raise ValueError('Unsupported iteration manifest or artifact role')
    identifier(manifest['iterationId'], 'iteration ID')
    nonempty(manifest['actor'], 'iteration actor')
    timestamp(manifest['recordedAt'], 'iteration timestamp')
    exact_fields(manifest['nextBatch'], {'trigger', 'scope'}, 'next batch')
    if manifest['nextBatch']['trigger'] != 'MANUAL':
        raise ValueError('V1 iterations are fixed manually triggered batches')
    nonempty(manifest['nextBatch']['scope'], 'next batch scope')
    artifacts, inventory, proofs = {}, {}, {}
    for role, entry in manifest['artifacts'].items():
        exact_fields(entry, {'path', 'sha256'}, 'artifact reference')
        source = (path.parent / entry['path']).resolve()
        raw = source.read_bytes()
        if hashlib.sha256(raw).hexdigest() != entry['sha256']:
            raise ValueError('Iteration artifact missing or changed: ' + role)
        artifacts[role] = load_jsonl(source) if role in {'sourceCases', 'gold'} and source.suffix == '.jsonl' else json.loads(raw)
        inventory[role] = {'path': str(source), 'sha256': entry['sha256']}
    for digest, location in manifest['proofs'].items():
        source = (path.parent / location).resolve()
        raw = source.read_bytes()
        if hashlib.sha256(raw).hexdigest() != digest:
            raise ValueError('Iteration proof missing or changed: ' + digest)
        proofs[digest] = raw
        inventory['proof:' + digest] = {'path': str(source), 'sha256': digest}
    known = set(proofs) | {entry['sha256'] for entry in inventory.values()}
    for issue in manifest['unresolved']:
        exact_fields(issue, {'issueType', 'description', 'evidenceRefs'}, 'unresolved issue')
        attribute_failure(issue['issueType'])
        nonempty(issue['description'], 'unresolved description')
        if not isinstance(issue['evidenceRefs'], list) or not set(issue['evidenceRefs']) <= known:
            raise ValueError('Unresolved issue references an unindexed artifact')
    return manifest, artifacts, proofs, inventory


def assemble(manifest, artifacts, proofs, inventory, evaluator=None, publisher=None):
    missing = sorted(COMMON - set(artifacts))
    lineage, comparisons = {}, {}
    engineering = 'UNVERIFIED'
    if 'offlineDrill' in artifacts:
        from check_evolution_drill_exports import check
        offline = check(Path(inventory['offlineDrill']['path']))
        engineering = 'OFFLINE_ORDINARY_CHAIN_VERIFIED'
        lineage['offlineDrill'] = offline
    gate = require_gate(artifacts['gate'], evaluator, allow_mechanism=True) if 'gate' in artifacts else None
    if gate:
        for role, field in [('sourceCases', 'sourceCasesSha256'), ('gold', 'goldSha256'), ('baselineReport', 'baselineReportSha256')]:
            if role in artifacts and json_hash(artifacts[role]) != gate['review'][field]:
                raise ValueError('Baseline acceptance refers to different original inputs: ' + role)
    candidate = artifacts.get('candidate')
    if candidate:
        validate_record(candidate)
        lineage['candidate'] = {name: candidate[name] for name in ('recordSha256', 'parentBundleId', 'parentMethodSha256',
            'methodSha256', 'experienceRecordSha256', 'generationMode', 'sourceExplanation')}
    registry = load_registry(artifacts['registry'], evaluator) if 'registry' in artifacts else None
    bundle = export_eval_bundle(candidate, gate['baselineBundle']['fixedContractSha256'])['bundles'][0] if gate and candidate else None
    if registry and candidate:
        entry = registry['entries'].get(candidate['experienceRecordSha256'])
        if not entry:
            raise ValueError('Candidate experience is absent from the indexed registry')
        if entry['evaluatedCandidateSha256'] not in {None, candidate['recordSha256']}:
            raise ValueError('Experience evaluation refers to a different candidate')
        lineage['experience'] = {'recordSha256': candidate['experienceRecordSha256'], 'state': entry['state'],
                                'parentExperienceHashes': entry['record']['parentExperienceHashes'],
                                'version': entry['record']['version']}
        lineage['events'] = registry['events']
        lineage['sources'] = candidate['sources']
        for source in candidate['sources']:
            hashes = (source['traceSha256'], source['externalFeedbackSha256'])
            if not gate or gate['status'] != 'ACCEPTED' or any(digest not in proofs for digest in hashes):
                missing.append('developmentSource:' + source['caseId'])
                continue
            feedback = verify_feedback(*(proofs[digest].decode('utf-8') for digest in hashes), artifacts['gate'], evaluator)
            if feedback['issueType'] != 'METHOD_ERROR' or any(feedback[name] != source[name] for name in ('caseId', 'issuerId', 'issuerName')):
                raise ValueError('Candidate lineage differs from independently reviewed method-error feedback')
    for name, split in [('validation', 'VALIDATION'), ('holdout', 'HOLDOUT')]:
        if name not in artifacts:
            continue
        evidence = verified_comparison(artifacts[name], evaluator)
        if (not gate or not bundle or evidence['evaluationSplit'] != split
                or evidence['acceptanceGateSha256'] != artifacts['gate']['payloadSha256']
                or evidence['candidateBundleSha256'] != bundle['contentSha256']
                or evidence['expectedComparisonIdentity'] != gate['expectedComparisonIdentity']
                or evidence['sourceCasesSha256'] != gate['review']['sourceCasesSha256']
                or evidence['goldSha256'] != gate['review']['goldSha256']):
            raise ValueError('Iteration comparison changed its source, candidate, gate or runtime conditions')
        comparisons[name] = evidence
        for suffix, field in [('Manifest', 'manifest_sha256'), ('Baseline', 'baseline_report_sha256'), ('Candidate', 'candidate_report_sha256')]:
            role = name + suffix
            if role not in artifacts:
                missing.append(role)
            elif json_hash(artifacts[role]) != artifacts[name].get(field):
                raise ValueError('Comparison index changed its original execution inputs: ' + role)
    if 'selection' in artifacts:
        selected = verified(artifacts['selection'], evaluator, 'CANDIDATE_SELECTION')
        if ('validation' not in comparisons or comparisons['validation']['comparisonStatus'] != 'IMPROVED'
                or selected['validationEvidenceSha256'] != artifacts['validation']['signedReleaseEvidence']['payloadSha256']
                or selected['gateSha256'] != artifacts['gate']['payloadSha256']
                or selected['candidateBundleSha256'] != bundle['contentSha256']
                or 'holdout' in comparisons and comparisons['holdout']['selectionSha256'] != artifacts['selection']['payloadSha256']):
            raise ValueError('Holdout selection does not descend from the indexed validation result')
    # Validation can legitimately reject every candidate; do not consume holdout just to close a failed batch.
    final_name = 'holdout' if 'holdout' in comparisons else 'validation'
    terminal = artifacts.get(final_name) if final_name in comparisons else None
    if final_name == 'validation' and terminal and terminal['status'] in {'NO_IMPROVEMENT', 'INCONCLUSIVE'}:
        missing = [name for name in missing if name not in {'holdout', 'selection'}]
    stages = (terminal or {}).get('stage_decisions') or {}
    result = {'schema': 'fundamentals_iteration_record_v1', 'iterationId': manifest['iterationId'],
              'actor': manifest['actor'], 'recordedAt': manifest['recordedAt'], 'status': 'OPEN',
              'engineeringResult': engineering, 'componentQuality': stages.get('analysis', 'UNVERIFIED'),
              'finalAnswerQuality': stages.get('finalAnswer', 'UNVERIFIED'), 'qualityScope': 'FROZEN_COMPONENT_CHAIN',
              'comparisonStatus': (terminal or {}).get('status', 'UNVERIFIED'), 'comparisonSplit': final_name.upper() if terminal else None,
              'lineage': lineage, 'artifacts': inventory,
              'unresolved': [{**issue, 'queue': attribute_failure(issue['issueType'])} for issue in manifest['unresolved']],
              'nextBatch': manifest['nextBatch'], 'missingEvidence': sorted(set(missing)),
              'activationAuthorized': False}
    closure = manifest['closure']
    if closure is None:
        return result
    exact_fields(closure, {'actor', 'at', 'decision', 'reason', 'checks'}, 'iteration closure')
    nonempty(closure['actor'], 'closure actor')
    nonempty(closure['reason'], 'closure reason')
    timestamp(closure['at'], 'closure time')
    if closure['decision'] not in CLOSURES or missing or not gate or gate['status'] != 'ACCEPTED' or not terminal:
        raise ValueError('Iteration closure requires the real accepted baseline, complete lineage and signed independent comparison')
    if datetime.fromisoformat(closure['at'].replace('Z', '+00:00')) > datetime.now(timezone.utc):
        raise ValueError('Iteration cannot close in the future')
    check_proofs(closure['checks'], {'lineage', 'engineeringLoop', 'runtimeEndState'}, proofs)
    status = comparisons[final_name]['comparisonStatus']
    if closure['decision'] == 'CLOSED_BASELINE_RETAINED' and status != 'NO_IMPROVEMENT':
        raise ValueError('Baseline-retained closure requires a signed no-improvement result')
    if closure['decision'] == 'CLOSED_INCONCLUSIVE' and status != 'INCONCLUSIVE':
        raise ValueError('Inconclusive closure requires a signed inconclusive result')
    if closure['decision'] == 'CLOSED_RELEASED':
        if final_name != 'holdout' or status != 'IMPROVED' or not RELEASE <= set(artifacts):
            raise ValueError('Released closure requires improved holdout and the complete approved deployment/observation chain')
        expected = prepare_package(candidate, artifacts['registry'], artifacts['gate'], terminal['signedReleaseEvidence'], artifacts['build'], evaluator)
        approved = verify_publisher(artifacts['approved'], publisher)
        if approved['package'] != expected:
            raise ValueError('Published package differs from the indexed accepted candidate')
        _shadow(artifacts['shadow'], artifacts['approved'], evaluator, publisher)
        drill, _, _ = _drill(artifacts['drill'], artifacts['approved'], publisher)
        rollback = verified(artifacts['rollback'], evaluator, 'ROLLBACK_ACCEPTANCE')
        activation = verify_publisher(artifacts['activation'], publisher)
        if (rollback.get('status') != 'ACCEPTED_FOR_SCOPED_ACTIVATION'
                or rollback.get('drillAuthorizationSha256') != artifacts['drill']['payloadSha256']
                or rollback.get('approvedArtifactSha256') != artifacts['approved']['payloadSha256']
                or drill.get('shadowAcceptanceSha256') != artifacts['shadow']['payloadSha256']
                or activation.get('shadowAcceptanceSha256') != artifacts['shadow']['payloadSha256']
                or activation.get('rollbackAcceptanceSha256') != artifacts['rollback']['payloadSha256']
                or not set(activation['internalAccountIds']) <= set(rollback['internalAccountIds'])):
            raise ValueError('Released closure changed the shadow/drill/rollback/activation lineage')
        observed = observe(artifacts['execution'], artifacts['activation'], artifacts['approved'], publisher,
                           artifacts['qualityReviews'], artifacts['operationalReviews'])
        if (observed['decision'] != 'MAINTAIN_REVIEWED_SCOPE'
                or not any(row['bundleId'] == bundle['bundleId'] for row in observed['runs'])):
            raise ValueError('Released closure requires reviewed actual candidate serving observations without failed or unknown checks')
        result['servingObservation'] = observed
    result.update(status=closure['decision'], engineeringResult='ACCEPTED_SCOPED_ITERATION', closure=closure)
    return result


def markdown(record):
    lines = [f"# {record['iterationId']}", '', f"状态：**{record['status']}**", '',
             f"记录人：{record['actor']}；记录时间：{record['recordedAt']}", '',
             '| 结果 | 结论 |', '|---|---|', f"| 工程闭环 | {record['engineeringResult']} |",
             f"| 组件主指标 | {record['componentQuality']} |", f"| 最终回答主指标 | {record['finalAnswerQuality']} |",
             f"| 独立比较总判定 | {record['comparisonStatus']} |", '',
             '主指标变化与包含硬门禁的总判定分开记录，仅覆盖固定证据下的组件链；本记录不授权部署或扩量。', '',
             '完整谱系、制品路径和内容哈希见 [artifact-index.json](artifact-index.json)。', '', '## 尚缺证据', '']
    lines += ['- ' + name for name in record['missingEvidence']] or ['- 无索引缺项；仍以结案证据和实际验收范围为准。']
    lines += ['', '## 未解决问题', '']
    lines += [f"- {issue['issueType']} → {issue['queue']}：{issue['description']}" for issue in record['unresolved']] or ['- 未登记。']
    lines += ['', '## 下一轮', '', '触发方式：MANUAL。使用新登记的固定批次，继续执行预算、独立评估和人工发布约束；不得复用已消费的独立保留集冒充新样本。', '', record['nextBatch']['scope'], '']
    if 'closure' in record:
        lines += ['## 结案依据', '', record['closure']['reason'], '']
    return '\n'.join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        parser.error('Use a new output directory; prior iteration records are immutable')
    try:
        from run_evolution_experiment import write_once
        manifest, artifacts, proofs, inventory = load_inputs(args.manifest)
        evaluator = read_key() if set(artifacts) & {'gate', 'registry', 'validation', 'selection', 'holdout'} else None
        publisher = publisher_key() if set(artifacts) & {'approved', 'activation'} else None
        if evaluator is not None and evaluator == publisher:
            raise ValueError('Evaluator and publisher credentials must be distinct')
        result = assemble(manifest, artifacts, proofs, inventory, evaluator, publisher)
        args.output.mkdir(parents=True, exist_ok=False)
        write_once(args.output / 'artifact-index.json', result)
        (args.output / (manifest['iterationId'] + '.md')).write_text(markdown(result), encoding='utf-8')
        print(json.dumps({'status': result['status'], 'output': str(args.output)}))
        return 0
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(2, f'Iteration record rejected: {error}. Check immutable source files, lineage and the closure evidence.\n')


if __name__ == '__main__':
    raise SystemExit(main())
