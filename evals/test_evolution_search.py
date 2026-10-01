"""Fictional generator and evaluator artifacts; no provider or production approval."""
import copy
import io
import json
import os
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch

import test_evolution_experiences as experience_fixtures
import evolution_generator as generator
from test_evolution_experiment import reservations as base_reservations
from test_evolution_acceptance import KEY
from evolution_acceptance import seal, evaluator_hash, validate_comparison_gate, comparison_report_hash
from evolution_candidates import bundle_content_sha256
from evolution_experiment import ExperimentLedger, search_history_proofs
from run_evolution_experiment import run_search, validate_plan, main
from eval_common import json_hash, sha256


def reservations():
    value = base_reservations()
    value['roles']['GENERATOR'].update(maxTokens=8000, maxCostUsd='0.016')
    return value


class EvolutionSearchTest(unittest.TestCase):
    def setUp(self):
        helper = experience_fixtures.ExperienceLifecycleTest()
        helper.setUp()
        baseline = helper.gate['payload']['baselineBundle']
        baseline['bundleSha256'] = bundle_content_sha256(baseline['bundleId'], None, baseline['fixedContractSha256'], helper.parent)
        helper.gate = seal(helper.gate['payload'], KEY)
        self.experience = helper.add()
        helper.validate(self.experience)
        self.gate, self.registry, self.parent = helper.gate, helper.registry, helper.parent
        self.sources = [row for row in helper.sources if row['split'] != 'HOLDOUT']
        self.plan = {'schema': 'fundamentals_evolution_search_v1', 'experimentId': 'unit-test-experiment',
                     'acceptanceGateSha256': self.gate['payloadSha256'], 'registrySha256': self.registry['payloadSha256'],
                     'sourceCasesSha256': json_hash(self.sources), 'baselineMethodSha256': sha256(self.parent),
                     'experienceHashes': [self.experience['recordSha256']], 'roundLimit': 2, 'noImprovementLimit': 1,
                     'validationLimit': 1, 'taskTags': ['period-comparison'], 'evidenceTags': ['dated-values'],
                     'capabilities': ['evidence-reading'],
                     'generator': {'endpoint': 'https://example.invalid/v1/chat/completions', 'model': 'unit-generator',
                                   'temperature': 0, 'maxOutputTokens': 100, 'timeoutSeconds': 1},
                     'inputs': dict(gate='gate.json', registry='registry.json', sources='sources.jsonl',
                                    baselineMethod='baseline.txt', reservations='resources.json'),
                     'workspace': 'search', 'ledger': 'ledger.sqlite'}
        self.calls = []

    def reply(self, request):
        self.calls.append(request)
        proposal = {'changes': {'fundamentals.method': self.parent + '\n' + self.experience['method']},
                    'sourceExplanation': '在比较之前显式核对期间。'}
        return {'request': request['body'], 'response': {'model': self.plan['generator']['model'],
                'usage': {'prompt_tokens': 40, 'completion_tokens': 10},
                'choices': [{'finish_reason': 'stop', 'message': {'role': 'assistant', 'content': json.dumps(proposal)}}]}}

    def run_stage(self, root, mode='search', **kwargs):
        ledger = ExperimentLedger(root / 'ledger.sqlite', self.gate, KEY, reservations())
        return run_search(self.plan, self.gate, self.registry, self.sources, self.parent, ledger, root / 'search', KEY,
                          mode=mode, invoke=kwargs.pop('invoke', self.reply), **kwargs)

    def decision(self, root, split, status):
        directory = root / 'search/round-001' / split.lower()
        comparison = json.loads((directory / 'comparison.json').read_text(encoding='utf-8'))
        validate_comparison_gate(comparison, self.gate, KEY)
        evidence = {'kind': 'RELEASE_EVIDENCE', 'schema': 'fundamentals_evolution_release_evidence_v2',
                    'evaluatorSha256': evaluator_hash(), 'acceptanceGateSha256': self.gate['payloadSha256'],
                    'manifest_sha256': json_hash(comparison), 'candidateBundleSha256': comparison['candidateBundleSha256'],
                    'evaluationSplit': split, 'eligible': False, 'authorization': 'NOT_AUTHORIZED', 'comparisonStatus': status}
        result = {'schema': 'fundamentals_evolution_comparison_v2', 'status': status,
                  'metrics': {'fixture': 'Fictional decision; not a quality measurement'}, 'excluded': []}
        evidence['comparisonReportSha256'] = comparison_report_hash(result)
        result.update(releaseEvidence={k: v for k, v in evidence.items() if k != 'kind'},
                      signedReleaseEvidence=seal(evidence, KEY))
        (directory / 'comparison-result.json').write_text(json.dumps(result), encoding='utf-8')

    def test_resume_progresses_from_generation_to_development_validation_and_nomination(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.assertEqual(self.run_stage(root, 'validate')['status'], 'VALIDATED')
            self.assertEqual(self.run_stage(root, 'compare')['status'], 'PENDING_GENERATION')
            first = self.run_stage(root)
            self.assertEqual(first['status'], 'PENDING_DEVELOPMENT')
            frozen_execution = (root / 'search/round-001/development/execution.json').read_bytes()
            self.assertEqual(self.run_stage(root)['status'], 'PENDING_DEVELOPMENT')
            self.assertEqual(frozen_execution, (root / 'search/round-001/development/execution.json').read_bytes())
            self.assertEqual(len(self.calls), 1)
            sent = json.dumps(self.calls)
            self.assertNotIn(KEY.decode(), sent)
            self.assertNotIn('holdout-0', sent)
            self.assertNotIn('validation-0', sent)
            self.decision(root, 'DEVELOPMENT', 'IMPROVED')
            self.assertEqual(self.run_stage(root, 'compare')['status'], 'PENDING_VALIDATION')
            self.decision(root, 'VALIDATION', 'IMPROVED')
            result = self.run_stage(root, 'compare')
            self.assertEqual((result['status'], result['decision']), ('COMPLETED', 'VALIDATION_IMPROVED'))
            self.assertEqual(result['nominatedCandidate'], first['rounds'][0]['candidateSha256'])
            self.assertFalse(result['releaseEligible'])
            self.assertEqual(result['resources']['callsReserved']['GENERATOR'], 1)
            events = result['resources']['searchEvents']
            self.assertEqual([row['to'] for row in events if row['entityType'] == 'CANDIDATE'],
                             ['PROPOSED', 'VALIDATED', 'PENDING_DEVELOPMENT', 'EVALUATED_DEVELOPMENT',
                              'PENDING_VALIDATION', 'EVALUATED_VALIDATION', 'NOMINATED'])
            self.assertEqual([row['sequence'] for row in events], list(range(1, len(events) + 1)))
            self.assertEqual(events[-1]['to'], 'COMPLETED')
            self.assertTrue(all(row['actor'] and row['at'] and row['reason'] and row['evidenceRefs'] for row in events))
            refs = search_history_proofs(result['resources'])
            ledger = ExperimentLedger(root / 'ledger.sqlite', self.gate, KEY, reservations())
            ledger.export_review(root / 'audit')
            for digest in refs:
                self.assertEqual(json_hash(json.loads((root / 'audit/proofs' / (digest + '.json')).read_text(encoding='utf-8'))), digest)
            outcome = json.loads((root / 'search/round-001/outcome.json').read_text(encoding='utf-8'))
            self.assertEqual(outcome['status'], 'VALIDATION_IMPROVED')
            self.assertEqual(self.run_stage(root)['resources']['searchEvents'], events)
            self.assertEqual(len(self.calls), 1)
            changed = copy.deepcopy(result['resources'])
            changed['searchEvents'][0]['from'] = 'COMPLETED'
            with self.assertRaisesRegex(ValueError, 'not continuous'):
                search_history_proofs(changed)

    def test_no_improvement_and_duplicate_methods_terminate_without_extra_replays(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.run_stage(root)
            self.decision(root, 'DEVELOPMENT', 'NO_IMPROVEMENT')
            result = self.run_stage(root)
            self.assertEqual(result['decision'], 'NO_IMPROVEMENT')
            self.assertEqual(result['stopReason'], 'NO_IMPROVEMENT_LIMIT')
            self.assertEqual(len(self.calls), 1)
        self.plan['noImprovementLimit'] = 2
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.run_stage(root)
            self.decision(root, 'DEVELOPMENT', 'NO_IMPROVEMENT')
            result = self.run_stage(root)
            self.assertEqual(result['rounds'][1]['status'], 'REJECTED_STATIC')
            self.assertIn('Duplicate method', result['rounds'][1]['reason'])
            self.assertEqual(result['resources']['callsReserved']['GENERATOR'], 2)
            self.assertFalse((root / 'search/round-002/development').exists())

    def test_wrong_signed_decision_and_changed_search_plan_cannot_advance(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.run_stage(root)
            self.decision(root, 'DEVELOPMENT', 'IMPROVED')
            path = root / 'search/round-001/development/comparison-result.json'
            changed = json.loads(path.read_text(encoding='utf-8'))
            changed['signedReleaseEvidence']['payload']['candidateBundleSha256'] = 'f' * 64
            changed['signedReleaseEvidence'] = seal(changed['signedReleaseEvidence']['payload'], KEY)
            path.write_text(json.dumps(changed), encoding='utf-8')
            with self.assertRaises(ValueError):
                self.run_stage(root, 'compare')
            self.plan['generator']['temperature'] = 1
            with self.assertRaisesRegex(ValueError, 'Immutable experiment artifact'):
                self.run_stage(root)
            ledger = ExperimentLedger(root / 'ledger.sqlite', self.gate, KEY, reservations())
            with self.assertRaisesRegex(ValueError, 'another search manifest'):
                run_search(self.plan, self.gate, self.registry, self.sources, self.parent, ledger, root / 'another-output', KEY,
                           mode='search', invoke=self.reply)
            self.assertEqual(len(self.calls), 1)

    def test_search_rejects_changed_report_metrics_or_exclusions_before_advancing(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.run_stage(root)
            self.decision(root, 'DEVELOPMENT', 'IMPROVED')
            path = root / 'search/round-001/development/comparison-result.json'
            original = json.loads(path.read_text(encoding='utf-8'))
            for field, value in (('metrics', {'fixture': 'edited score'}),
                                 ('excluded', [{'id': 'silently-excluded-failure'}])):
                with self.subTest(field=field):
                    changed = copy.deepcopy(original)
                    changed[field] = value
                    path.write_text(json.dumps(changed), encoding='utf-8')
                    with self.assertRaisesRegex(ValueError, 'report binding'):
                        self.run_stage(root, 'compare')
                    self.assertFalse((root / 'search/round-001/validation').exists())
                    self.assertEqual(len(self.calls), 1)
            path.write_text(json.dumps(original), encoding='utf-8')
            self.assertEqual(self.run_stage(root, 'compare')['status'], 'PENDING_VALIDATION')

    def test_a_consumed_decision_cannot_be_replaced_even_with_a_new_valid_signature(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.run_stage(root)
            self.decision(root, 'DEVELOPMENT', 'IMPROVED')
            self.assertEqual(self.run_stage(root, 'compare')['status'], 'PENDING_VALIDATION')
            self.decision(root, 'DEVELOPMENT', 'NO_IMPROVEMENT')
            with self.assertRaisesRegex(ValueError, 'milestone changed'):
                self.run_stage(root, 'compare')
            snapshot = ExperimentLedger(root / 'ledger.sqlite', self.gate, KEY, reservations()).snapshot()
            self.assertEqual(snapshot['searchEvents'][-1]['to'], 'BLOCKED')
            self.assertEqual(snapshot['searchEvents'][-1]['reason'], 'CONTRACT_OR_STORAGE_ERROR')
            search_history_proofs(snapshot)
            self.assertEqual(len(self.calls), 1)

    def test_lost_generator_response_stop_and_invalid_proposal_keep_the_ledger(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def timeout(_):
                self.calls.append('timed-out')
                raise TimeoutError()
            result = self.run_stage(root, invoke=timeout)
            self.assertEqual(result['reason'], 'GENERATOR_SUBMISSION_OUTCOME_UNKNOWN')
            result = self.run_stage(root)
            self.assertEqual(result['reason'], 'SUBMISSION_PENDING_OR_UNKNOWN')
            self.assertEqual(len(self.calls), 1)
            self.assertIsNone(result['resources']['tokensObserved'])
            self.assertEqual(result['resources']['searchEvents'][-1]['to'], 'BLOCKED')
            search_history_proofs(result['resources'])
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            stopped = self.run_stage(root, stop_requested=lambda: True)
            self.assertEqual(stopped['status'], 'CANCELLED')
            self.assertEqual(stopped['resources']['searchEvents'][-1]['to'], 'CANCELLED')
            def invalid(request):
                response = self.reply(request)
                response['response']['choices'][0]['message']['content'] = '{"tools": ["new-tool"]}'
                return response
            result = self.run_stage(root, invoke=invalid)
            self.assertEqual(result['rounds'][0]['status'], 'REJECTED_STATIC')
            self.assertEqual(result['resources']['callsReserved']['GENERATOR'], 1)
            self.assertFalse((root / 'search/round-001/development').exists())
            self.assertEqual([row['to'] for row in result['resources']['searchEvents'] if row['entityType'] == 'CANDIDATE'],
                             ['PROPOSED', 'REJECTED_STATIC'])
            search_history_proofs(result['resources'])

    def test_holdout_sources_and_unbounded_rounds_are_rejected_before_generation(self):
        altered = copy.deepcopy(self.sources)
        altered[0]['split'] = 'HOLDOUT'
        with self.assertRaises(ValueError):
            validate_plan(self.plan, self.gate, self.registry, altered, self.parent, KEY)
        self.plan['roundLimit'] = 999
        with self.assertRaisesRegex(ValueError, 'bounds'):
            validate_plan(self.plan, self.gate, self.registry, self.sources, self.parent, KEY)

    def test_validation_feedback_is_not_returned_to_the_next_generator(self):
        self.plan.update(noImprovementLimit=2, validationLimit=2)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.run_stage(root)
            self.decision(root, 'DEVELOPMENT', 'IMPROVED')
            self.run_stage(root, 'compare')
            self.decision(root, 'VALIDATION', 'NO_IMPROVEMENT')
            self.run_stage(root)
            self.assertEqual(len(self.calls), 2)
            sent = self.calls[-1]['body']['messages'][-1]['content']
            self.assertIn('IMPROVED', sent)
            self.assertNotIn('NO_IMPROVEMENT', sent)
            self.assertNotIn('validation-0', sent)

    def test_http_adapter_sends_only_generator_credentials_and_fixed_request(self):
        config = self.plan['generator']
        body = generator.request_body(config, {'method': 'fictional method'})
        response = {'model': config['model'], 'usage': {'prompt_tokens': 10, 'completion_tokens': 5}}
        with patch.dict(os.environ, {'STOCKSAGE_EVOLUTION_GENERATOR_KEY': 'fake-generator-key',
                                    'STOCKSAGE_EVOLUTION_EVALUATOR_KEY': KEY.decode()}), patch('urllib.request.build_opener') as opener:
            opener.return_value.open.return_value = io.BytesIO(json.dumps(response).encode())
            result = generator.invoke(config, body)
            request = opener.return_value.open.call_args.args[0]
            self.assertEqual(request.get_header('Authorization'), 'Bearer fake-generator-key')
            self.assertNotIn(KEY.decode(), request.data.decode())
            self.assertNotIn('tools', json.loads(request.data))
            self.assertEqual(result['response'], response)

    def test_cli_validates_freezes_and_resumes_the_same_experiment(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name, value in [('manifest.json', self.plan), ('gate.json', self.gate),
                                ('registry.json', self.registry), ('resources.json', reservations())]:
                (root / name).write_text(json.dumps(value), encoding='utf-8')
            (root / 'sources.jsonl').write_text('\n'.join(json.dumps(row) for row in self.sources), encoding='utf-8')
            (root / 'baseline.txt').write_text(self.parent, encoding='utf-8')
            with patch.dict(os.environ, {'STOCKSAGE_EVOLUTION_EVALUATOR_KEY': KEY.decode(),
                                        'STOCKSAGE_EVOLUTION_GENERATOR_KEY': 'test-generator-key'}):
                for mode, expected in [('validate', 0), ('search', 3), ('compare', 3)]:
                    with patch('sys.argv', ['search', '--manifest', str(root / 'manifest.json'), '--mode', mode]), \
                            patch('evolution_generator.invoke', side_effect=lambda config, body: self.reply({'config': config, 'body': body})), \
                            redirect_stdout(io.StringIO()) as output:
                        self.assertEqual(main(), expected)
                    summary = json.loads(output.getvalue())
                    self.assertTrue(Path(summary['report']).is_file())
                    self.assertEqual(summary['status'], 'VALIDATED' if mode == 'validate' else 'PENDING_DEVELOPMENT')
            self.assertEqual(len(self.calls), 1)


if __name__ == '__main__':
    unittest.main()
