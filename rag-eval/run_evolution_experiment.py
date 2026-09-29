"""Bounded method search controller. Independent comparison artifacts advance each round.

Run in the protected evaluator/controller environment. The remote optimizer receives
only a validated method experience, the fixed baseline and development feedback.
No holdout source, labels, evaluator key or publisher credential enters its request.
"""
from __future__ import annotations

import argparse
import json
import os
import sqlite3
import tempfile
from pathlib import Path

from evolution_acceptance import require_gate, verified_comparison, evaluator_hash, read_key, require_hash
from evolution_candidates import export_eval_bundle, bundle_content_sha256, experience_matches
from evolution_dataset import exact_fields, validate_cases, case_hash, execution_payload, load_jsonl
from evolution_experiences import load_registry, unavailable_reason, compile_registered
from evolution_experiment import ExperimentLedger, BudgetStop
from evolution_compare import V2_SCHEMA
from ordinary_answer_quality import json_hash, sha256, DIMENSIONS
import evolution_generator as generator


def write_once(path: Path, value: dict) -> None:
    """Atomic create; an interrupted process cannot leave a half-written approved input."""
    data = (json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode()
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as temporary:
        pending = Path(temporary.name)
        temporary.write(data)
        temporary.flush()
        os.fsync(temporary.fileno())
    try:
        try:
            os.link(pending, path)
        except FileExistsError:
            if path.read_bytes() != data:
                raise ValueError('Immutable experiment artifact changed: ' + str(path))
    finally:
        pending.unlink()


def validate_plan(plan, gate_artifact, registry, sources, baseline_method, key):
    exact_fields(plan, {'schema', 'experimentId', 'acceptanceGateSha256', 'registrySha256', 'sourceCasesSha256',
                       'baselineMethodSha256', 'experienceHashes', 'roundLimit', 'noImprovementLimit', 'validationLimit',
                       'taskTags', 'evidenceTags', 'capabilities', 'generator', 'inputs', 'workspace', 'ledger'}, 'search manifest')
    if plan['schema'] != 'fundamentals_evolution_search_v1':
        raise ValueError('Unsupported search manifest')
    gate = require_gate(gate_artifact, key)
    if (plan['experimentId'] != gate['experimentId'] or plan['acceptanceGateSha256'] != gate_artifact['payloadSha256']
            or plan['registrySha256'] != registry['payloadSha256'] or plan['sourceCasesSha256'] != json_hash(sources)
            or plan['baselineMethodSha256'] != sha256(baseline_method)):
        raise ValueError('Search inputs differ from their frozen identities')
    baseline = gate['baselineBundle']
    if bundle_content_sha256(baseline['bundleId'], None, baseline['fixedContractSha256'], baseline_method) != baseline['bundleSha256']:
        raise ValueError('Search must start from the exact E07 baseline method')
    for name in ('roundLimit', 'noImprovementLimit', 'validationLimit'):
        if type(plan[name]) is not int or plan[name] < 1:
            raise ValueError(name + ' must be a positive integer')
    if (plan['roundLimit'] > min(gate['policy']['limits']['maxRounds'], gate['policy']['limits']['maxCandidates'])
            or plan['validationLimit'] > plan['roundLimit'] or plan['noImprovementLimit'] > plan['roundLimit']):
        raise ValueError('Search bounds cannot exceed the accepted E07 limits')
    indexed = validate_cases(sources)
    expected = {case for split in ('DEVELOPMENT', 'VALIDATION') for case in gate['policy']['cohorts'][split]['caseIds']}
    if set(indexed) != expected:
        raise ValueError('Search sources must contain exactly the frozen development and validation cohorts, never holdout')
    for case in sources:
        if (case['split'] not in {'DEVELOPMENT', 'VALIDATION'} or case_hash(case) != gate['caseHashes'][case['caseId']]
                or case['caseId'] not in gate['policy']['cohorts'][case['split']]['caseIds']):
            raise ValueError('Search source belongs to another frozen cohort')
    for name in ('taskTags', 'evidenceTags', 'capabilities', 'experienceHashes'):
        values = plan[name]
        if not isinstance(values, list) or not values or any(not isinstance(value, str) or not value for value in values) or len(set(values)) != len(values):
            raise ValueError(name + ' must contain distinct nonempty strings')
    body = load_registry(registry, key)
    for digest in plan['experienceHashes']:
        require_hash(digest, 'experience hash')
        entry = body['entries'].get(digest)
        if (entry is None or entry['state'] not in {'VALIDATED', 'EVALUATED', 'APPROVED'}
                or entry['gateSha256'] != plan['acceptanceGateSha256'] or unavailable_reason(body, digest)
                or not experience_matches(entry['record'], set(plan['taskTags']), set(plan['evidenceTags']), set(plan['capabilities']))):
            raise ValueError('Search requires applicable, independently validated experiences from the accepted experiment')
    generator.validate_config(plan['generator'])
    exact_fields(plan['inputs'], {'gate', 'registry', 'sources', 'baselineMethod', 'reservations'}, 'search input paths')
    return gate, body


def stage_plan(plan, gate, sources, candidate, split):
    bundle = export_eval_bundle(candidate, gate['baselineBundle']['fixedContractSha256'])['bundles'][0]
    selected = {case['caseId']: case for case in sources if case['split'] == split}
    definitions = [execution_payload(selected[case_id]) for case_id in gate['policy']['cohorts'][split]['caseIds']]
    repeats = list(range(1, gate['policy']['repeats'][split] + 1))
    comparison = {'schema': V2_SCHEMA, 'baselineBundleId': gate['baselineBundle']['bundleId'],
                  'baselineBundleSha256': gate['baselineBundle']['bundleSha256'], 'candidateBundleId': bundle['bundleId'],
                  'candidateBundleSha256': bundle['contentSha256'], 'fixedContractSha256': bundle['fixedContractSha256'],
                  'expectedComparisonIdentity': gate['expectedComparisonIdentity'],
                  **{name: gate['policy'][name] for name in ('minimumGroups', 'bootstrapSamples', 'seed', 'confidenceLevel', 'primaryDimension')},
                  'nonRegressionDimensions': list(DIMENSIONS), 'maxRunsPerArm': len(definitions) * len(repeats),
                  'acceptanceGateSha256': plan['acceptanceGateSha256'], 'evaluationSplit': split, 'selectionSha256': None,
                  'cases': [{'caseId': row['caseId'], 'issuerId': selected[row['caseId']]['issuerId'],
                             'reportFamilyId': selected[row['caseId']]['reportFamilyId'], 'caseSha256': row['caseSha256'],
                             'expectedEvidenceSnapshotSha256': row['contextSha256'],
                             'expectedHistorySnapshotSha256': json_hash(row['history']), 'repeatIds': repeats} for row in definitions]}
    execution = {'schemaVersion': 2, 'context': {'experimentId': plan['experimentId'], 'evaluatorVersion': evaluator_hash(), 'runMode': split},
                 'cases': definitions, 'runs': []}
    for row in definitions:
        for repeat in repeats:
            for bundle_id in (comparison['baselineBundleId'], comparison['candidateBundleId']):
                identity = [json_hash(plan), candidate['recordSha256'], split, row['caseId'], repeat, bundle_id]
                execution['runs'].append({'runId': 'search-' + json_hash(identity), 'caseId': row['caseId'],
                                          'bundleId': bundle_id, 'repeatId': repeat})
    return comparison, execution


def reviewed_decision(path, comparison, gate_artifact, key):
    if not path.exists():
        return None
    result = json.loads(path.read_text(encoding='utf-8'))
    signed = result['signedReleaseEvidence']
    evidence = verified_comparison(result, key)
    if (evidence.get('schema') != 'fundamentals_evolution_release_evidence_v2'
            or evidence.get('evaluatorSha256') != evaluator_hash()
            or evidence.get('acceptanceGateSha256') != gate_artifact['payloadSha256']
            or evidence.get('manifest_sha256') != json_hash(comparison)
            or evidence.get('candidateBundleSha256') != comparison['candidateBundleSha256']
            or evidence.get('evaluationSplit') != comparison['evaluationSplit']
            or evidence.get('eligible') is not False or evidence.get('authorization') != 'NOT_AUTHORIZED'
            or evidence.get('comparisonStatus') not in {'IMPROVED', 'NO_IMPROVEMENT', 'INCONCLUSIVE'}
            or {name: value for name, value in evidence.items() if name != 'kind'} != result.get('releaseEvidence')
            or result.get('status') != evidence['comparisonStatus']):
        raise ValueError('Independent comparison does not bind this candidate, cohort, evaluator and frozen plan')
    return {'decision': evidence['comparisonStatus'], 'evidenceSha256': signed['payloadSha256']}, result


def run_search(plan, gate_artifact, registry, sources, baseline_method, ledger, workspace, key, *, mode, invoke=None, stop_requested=lambda: False):
    if mode not in {'validate', 'search', 'compare'}:
        raise ValueError('Search mode must be validate, search or compare')
    gate, entries = validate_plan(plan, gate_artifact, registry, sources, baseline_method, key)
    workspace = Path(workspace)
    if plan['generator']['maxOutputTokens'] > ledger.reservations['roles']['GENERATOR']['maxTokens']:
        raise ValueError('Generator output cap exceeds its frozen total-token reservation')
    write_once(workspace / 'search-contract.json', {'manifest': plan, 'resourceContractSha256': ledger.identity})
    result = {'schema': 'fundamentals_evolution_search_result_v1', 'experimentId': plan['experimentId'],
              'manifestSha256': json_hash(plan), 'status': 'VALIDATED' if mode == 'validate' else 'COMPLETED',
              'decision': 'NO_DATA', 'rounds': [], 'nominatedCandidate': None, 'releaseEligible': False}
    search_id = json_hash(plan)
    ledger.search_event(search_id, search_id, 'SEARCH', 'CREATED', 'FROZEN_SEARCH_CONTRACT',
                        {'manifest': plan, 'resourceContractSha256': ledger.identity}, milestone='created')
    ledger.search_event(search_id, search_id, 'SEARCH', 'VALIDATED', 'E07_AND_INPUTS_VALIDATED',
                        {'gateSha256': gate_artifact['payloadSha256'], 'registrySha256': registry['payloadSha256'],
                         'manifestSha256': search_id}, milestone='validated')
    if mode == 'validate':
        return {**result, 'resources': ledger.snapshot()}
    completed = ledger.completed_search(search_id)
    if completed is not None:
        return {**completed, 'resources': ledger.snapshot()}
    ledger.search_event(search_id, search_id, 'SEARCH', 'RUNNING', 'CONTROLLER_STARTED_OR_RESUMED', {'mode': mode})

    def finish():
        reason = result.get('reason') or result.get('stopReason') or result['decision']
        ledger.search_event(search_id, search_id, 'SEARCH', result['status'],
                            reason if reason != 'NO_DATA' else result['status'], {'result': result})
        return {**result, 'resources': ledger.snapshot()}

    def candidate_event(action, state, reason, proof):
        ledger.search_event(search_id, action, 'CANDIDATE', state, reason, proof, milestone=state)
    stale, validations, development_feedback, seen_methods = 0, 0, [], set()
    try:
        for number in range(1, plan['roundLimit'] + 1) if mode != 'validate' else ():
            if stop_requested():
                raise BudgetStop('OPERATOR_STOP')
            digest = plan['experienceHashes'][(number - 1) % len(plan['experienceHashes'])]
            experience = entries['entries'][digest]['record']
            action = 'generate-' + json_hash([json_hash(plan), number])
            material = {'target': 'fundamentals.method', 'baselineMethod': baseline_method,
                        'experience': experience, 'previousDevelopmentDecisions': development_feedback}
            body = generator.request_body(plan['generator'], material)
            request = {'config': plan['generator'], 'body': body}
            found, response = ledger.recorded(action, request, ('GENERATOR',))
            if not found:
                if mode != 'search':
                    result.update(status='PENDING_GENERATION', nextStep='Run mode search to generate the next registered candidate.')
                    break
                # Bound serialized input before sending; provider token observations remain authoritative.
                if len(json.dumps(body, ensure_ascii=False).encode()) + plan['generator']['maxOutputTokens'] > ledger.reservations['roles']['GENERATOR']['maxTokens']:
                    raise BudgetStop('GENERATOR_INPUT_EXCEEDS_FROZEN_RESERVATION', exit_code=4)
                if invoke is None:
                    if not os.environ.get('STOCKSAGE_EVOLUTION_GENERATOR_KEY'):
                        raise BudgetStop('MISSING_GENERATOR_KEY')
                    invoke = lambda request: generator.invoke(request['config'], request['body'])
                try:
                    response = ledger.call(action, request, ('GENERATOR',), invoke, lambda value: generator.usage(value, action), stop_requested=stop_requested)
                except BudgetStop:
                    raise
                except (OSError, ValueError, TypeError, KeyError) as error:
                    result.update(status='BLOCKED', reason='GENERATOR_SUBMISSION_OUTCOME_UNKNOWN', errorType=type(error).__name__)
                    return finish()
            if ledger.snapshot()['status'] != 'READY':
                raise BudgetStop(ledger.snapshot()['reason'])
            directory = workspace / ('round-' + str(number).zfill(3))
            row = {'round': number, 'experienceSha256': digest, 'generationRunId': action, 'parentBundleId': gate['baselineBundle']['bundleId']}
            result['rounds'].append(row)
            candidate_event(action, 'PROPOSED', 'GENERATOR_RESPONSE_RECORDED',
                            {'response': response, 'experienceSha256': digest, 'generationRunId': action})
            try:
                proposal = generator.proposal(response, plan['generator'], body)
                candidate = compile_registered(registry, digest, key, gate['baselineBundle']['bundleId'], baseline_method, proposal,
                                               set(plan['taskTags']), set(plan['evidenceTags']), set(plan['capabilities']), generation_mode='INJECTED_INVOKE')
                if candidate['methodSha256'] in seen_methods:
                    raise ValueError('Duplicate method; retained generation cost is not a new candidate experiment')
                seen_methods.add(candidate['methodSha256'])
            except (ValueError, KeyError, TypeError) as error:
                row.update(status='REJECTED_STATIC', reason=str(error), generatorResponseSha256=json_hash(response))
                write_once(directory / 'rejected.json', row)
                candidate_event(action, 'REJECTED_STATIC', 'STATIC_VALIDATION_FAILED', row)
                stale += 1
            else:
                row.update(candidateId=candidate['candidateId'], candidateSha256=candidate['recordSha256'])
                write_once(directory / 'candidate.json', candidate)
                write_once(directory / 'bundles.json', export_eval_bundle(candidate, gate['baselineBundle']['fixedContractSha256']))
                candidate_event(action, 'VALIDATED', 'METHOD_ONLY_CONTRACT_PASSED', candidate)
                for split in ('DEVELOPMENT', 'VALIDATION'):
                    comparison, execution = stage_plan(plan, gate, sources, candidate, split)
                    stage = directory / split.lower()
                    write_once(stage / 'comparison.json', comparison)
                    write_once(stage / 'execution.json', execution)
                    reviewed = reviewed_decision(stage / 'comparison-result.json', comparison, gate_artifact, key)
                    if reviewed is None:
                        row['status'] = 'PENDING_' + split
                        result.update(status=row['status'], nextStep='Register the emitted execution/bundle files in the isolated Java instance, run paired replay with the shared ledger, then save the independent signed comparison as ' + str(stage / 'comparison-result.json'))
                        candidate_event(action, row['status'], 'INDEPENDENT_COMPARISON_REQUIRED',
                                        {'comparison': comparison, 'execution': execution})
                        return finish()
                    decision, comparison_result = reviewed
                    candidate_event(action, 'EVALUATED_' + split, 'INDEPENDENT_COMPARISON_VERIFIED', comparison_result)
                    row[split.lower()] = decision
                    if split == 'DEVELOPMENT':
                        development_feedback.append({'candidateSha256': candidate['recordSha256'], **decision})
                        if decision['decision'] != 'IMPROVED':
                            row['status'] = 'REJECTED_DEVELOPMENT'
                            candidate_event(action, 'REJECTED_DEVELOPMENT', decision['decision'], row)
                            stale += 1
                            break
                        validations += 1
                    elif decision['decision'] == 'IMPROVED':
                        row['status'] = 'VALIDATION_IMPROVED'
                        result.update(decision='VALIDATION_IMPROVED', nominatedCandidate=candidate['recordSha256'])
                        write_once(directory / 'outcome.json', row)
                        candidate_event(action, 'NOMINATED', 'INDEPENDENT_VALIDATION_IMPROVED', row)
                        return finish()
                    else:
                        row['status'] = 'REJECTED_VALIDATION'
                        candidate_event(action, 'REJECTED_VALIDATION', decision['decision'], row)
                        stale += 1
                write_once(directory / 'outcome.json', row)
            if stale >= plan['noImprovementLimit'] or validations >= plan['validationLimit']:
                result['stopReason'] = 'NO_IMPROVEMENT_LIMIT' if stale >= plan['noImprovementLimit'] else 'VALIDATION_LIMIT'
                break
        if result['status'] == 'COMPLETED':
            result['decision'] = 'INCONCLUSIVE' if any(row.get(split, {}).get('decision') == 'INCONCLUSIVE'
                for row in result['rounds'] for split in ('development', 'validation')) else 'NO_IMPROVEMENT'
            result.setdefault('stopReason', 'ROUND_LIMIT')
    except BudgetStop as error:
        result.update(status='BUDGET_EXHAUSTED' if error.exit_code == 4 else 'CANCELLED' if error.reason == 'OPERATOR_STOP' else 'BLOCKED',
                      reason=error.reason)
    except (OSError, ValueError, KeyError, TypeError, sqlite3.Error) as error:
        result.update(status='BLOCKED', reason='CONTRACT_OR_STORAGE_ERROR', errorType=type(error).__name__)
        finish()
        raise
    return finish()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', required=True, type=Path)
    parser.add_argument('--mode', required=True, choices=['validate', 'search', 'compare'])
    parser.add_argument('--stop-file', type=Path)
    args = parser.parse_args()
    try:
        root = args.manifest.resolve().parent
        plan = json.loads(args.manifest.read_text(encoding='utf-8'))
        def path(name):
            resolved = (root / name).resolve()
            if not resolved.is_relative_to(root):
                raise ValueError('Experiment paths must stay inside the manifest directory')
            return resolved
        read = lambda name: json.loads(path(name).read_text(encoding='utf-8'))
        inputs = plan['inputs']
        gate, registry = read(inputs['gate']), read(inputs['registry'])
        sources, baseline = load_jsonl(path(inputs['sources'])), path(inputs['baselineMethod']).read_text(encoding='utf-8')
        key = read_key()
        validate_plan(plan, gate, registry, sources, baseline, key)
        ledger = ExperimentLedger(path(plan['ledger']), gate, key, read(inputs['reservations']))
        result = run_search(plan, gate, registry, sources, baseline, ledger, path(plan['workspace']), key, mode=args.mode,
                            stop_requested=lambda: args.stop_file is not None and args.stop_file.exists())
        report = path(plan['workspace']) / ('report-' + json_hash(result) + '.json')
        write_once(report, result)
    except (OSError, ValueError, KeyError, TypeError, sqlite3.Error) as error:
        parser.exit(2, f'Experiment contract/storage error: {error}. Preserve the ledger and original artifacts before correcting the input.\n')
    print(json.dumps({'status': result['status'], 'decision': result['decision'], 'report': str(report)}, ensure_ascii=False))
    return 0 if result['status'] in {'VALIDATED', 'COMPLETED'} else 4 if result['status'] == 'BUDGET_EXHAUSTED' else 3


if __name__ == '__main__':
    raise SystemExit(main())
