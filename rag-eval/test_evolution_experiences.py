"""Fictional provenance and evaluator signatures; no live approval or provider call."""
import copy
import json
import io
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch

import test_evolution_candidates as candidate_fixtures
from test_evolution_acceptance import fixture, KEY
from evolution_acceptance import evaluator_hash, seal
from evolution_candidates import build_experience, export_eval_bundle
from evolution_reflection import feedback_template, sign_feedback
from evolution_experiences import (empty_registry, register, transition, conflict, compile_registered,
                                   select_approved, load_approved, main)
from ordinary_answer_quality import sha256

TAGS = ({'period-comparison'}, {'dated-values'}, {'evidence-reading'})


class ExperienceLifecycleTest(unittest.TestCase):
    def setUp(self):
        self.sources, _, _, baseline, _, _, self.gate, _ = fixture()
        helper = candidate_fixtures.EvolutionCandidatesTest()
        helper.setUp()
        self.spec, self.parent = helper.spec, helper.parent_method
        self.trace = copy.deepcopy(baseline['cases'][0])
        self.spec.update(version=1, forbiddenInferences=['不得把缺失期间推定为可比期间。'],
                         sourceRunIds=[self.trace['replay']['runId']])
        source = self.spec['sources'][0]
        source.update(caseId=self.sources[0]['caseId'], origin='PUBLIC_AUTHORIZED',
                      issuerId=self.sources[0]['issuerId'], feedbackKind='INDEPENDENT_REVIEW')
        self.registry = empty_registry(KEY)

    def add(self, *, suffix='', parents=(), trace=None):
        spec = copy.deepcopy(self.spec)
        spec['method'] += suffix
        spec['parentExperienceHashes'] = [row['recordSha256'] for row in parents]
        spec['version'] = 1 + max((row['version'] for row in parents), default=0)
        trace_text = json.dumps(trace or self.trace, ensure_ascii=False)
        review = feedback_template(trace_text, self.sources[0], self.gate, KEY)
        review.update(reviewer='independent-test-reviewer', reviewedAt='2026-09-28T00:00:00Z',
                      issuerName=spec['sources'][0]['issuerName'], issueType='METHOD_ERROR', findings=[
                          {'location': '/replay/analysis/answer', 'excerpt': (trace or self.trace)['replay']['analysis']['answer'],
                           'reason': 'Fictional period mismatch review. ' + suffix}])
        feedback = json.dumps(sign_feedback(trace_text, self.sources[0], review, self.gate, KEY))
        spec['sources'][0].update(traceSha256=sha256(trace_text), externalFeedbackSha256=sha256(feedback))
        artifacts = {sha256(trace_text): trace_text, sha256(feedback): feedback}
        record = build_experience(spec, artifacts, parents=parents)
        self.registry = register(self.registry, record, artifacts, self.gate, KEY, 'test-reviewer', 'test registration')
        return record

    def validate(self, record):
        digest = record['recordSha256']
        review = dict(experienceSha256=digest, reviewer='test-reviewer', reviewedAt='2026-09-28T00:00:00Z',
                      methodOnly='PASS', sourceAuthorized='PASS', injectionReview='PASS', applicabilityReview='PASS')
        self.registry = transition(self.registry, digest, 'VALIDATED', KEY, 'test-reviewer', 'static review', review)

    def compile(self, record):
        proposal = {'changes': {'fundamentals.method': self.parent + '\n' + record['method']},
                    'sourceExplanation': '增加期间对照步骤。'}
        return compile_registered(self.registry, record['recordSha256'], KEY, 'baseline-v1', self.parent, proposal, *TAGS)

    def evidence(self, candidate):
        contract = sha256('test-contract')
        bundle = export_eval_bundle(candidate, contract)['bundles'][0]
        return seal(dict(kind='RELEASE_EVIDENCE', schema='fundamentals_evolution_release_evidence_v2',
                         fixedContractSha256=contract, candidateBundleSha256=bundle['contentSha256'],
                         evaluatorSha256=evaluator_hash(), acceptanceGateSha256=self.gate['payloadSha256'],
                         evaluationSplit='HOLDOUT', eligible=True, comparisonStatus='IMPROVED',
                         authorization='PENDING_HUMAN_APPROVAL'), KEY)

    def approve(self, record):
        self.validate(record)
        candidate = self.compile(record)
        for state in ('EVALUATED', 'APPROVED'):
            self.registry = transition(self.registry, record['recordSha256'], state, KEY, 'test-reviewer',
                                       'fictional acceptance', self.evidence(candidate), candidate=candidate)
        return candidate

    def withdraw(self, record, state):
        self.registry = transition(self.registry, record['recordSha256'], state, KEY, 'test-reviewer', 'withdraw',
                                   {'reason': 'test correction', 'artifactSha256': sha256('test correction')})

    def test_full_lifecycle_selects_only_approved_applicable_methods_and_revokes_without_rewrite(self):
        record = self.add()
        original = copy.deepcopy(record)
        proposed_registry = copy.deepcopy(self.registry)
        self.assertEqual(select_approved(self.registry, KEY, *TAGS)['selection'], 'BASELINE')
        with self.assertRaisesRegex(ValueError, 'unvalidated'):
            self.compile(record)
        with self.assertRaisesRegex(ValueError, 'Illegal experience transition'):
            transition(self.registry, record['recordSha256'], 'APPROVED', KEY, 'reviewer', 'skip', {})
        self.approve(record)
        self.assertEqual(select_approved(self.registry, KEY, *TAGS)['experience'], record)
        self.assertEqual(select_approved(self.registry, KEY, TAGS[0], set(), TAGS[2])['selection'], 'BASELINE')
        self.withdraw(record, 'REVOKED')
        self.assertEqual(select_approved(self.registry, KEY, *TAGS)['selection'], 'BASELINE')
        with self.assertRaises(ValueError):
            self.compile(record)
        self.assertEqual(self.registry['payload']['entries'][record['recordSha256']]['record'], original)
        self.assertEqual(proposed_registry['payload']['entries'][record['recordSha256']]['state'], 'PROPOSED')

    def test_wrong_evaluator_gate_candidate_or_permission_cannot_approve(self):
        record = self.add()
        self.validate(record)
        candidate = self.compile(record)
        for field, value in [('evaluatorSha256', 'a' * 64), ('acceptanceGateSha256', 'b' * 64),
                             ('candidateBundleSha256', 'c' * 64)]:
            payload = self.evidence(candidate)['payload']
            payload[field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                transition(self.registry, record['recordSha256'], 'EVALUATED', KEY, 'reviewer', 'test',
                           seal(payload, KEY), candidate=candidate)
        evidence = self.evidence(candidate)
        self.registry = transition(self.registry, record['recordSha256'], 'EVALUATED', KEY, 'reviewer', 'test', evidence, candidate=candidate)
        payload = evidence['payload']
        payload['authorization'] = 'NOT_AUTHORIZED'
        with self.assertRaises(ValueError):
            transition(self.registry, record['recordSha256'], 'APPROVED', KEY, 'reviewer', 'test', seal(payload, KEY), candidate=candidate)

    def test_conflicts_and_withdrawn_parents_block_compilation_and_selection(self):
        first = self.add()
        self.approve(first)
        second = self.add(suffix='明确区分重述前后的数值。', parents=[first])
        self.approve(second)
        self.assertEqual(select_approved(self.registry, KEY, *TAGS)['reason'], 'AMBIGUOUS_APPROVED_METHODS')
        hashes = first['recordSha256'], second['recordSha256']
        self.registry = conflict(self.registry, *hashes, KEY, 'reviewer', 'incompatible rules', sha256('conflict'))
        self.assertEqual(select_approved(self.registry, KEY, *TAGS)['selection'], 'BASELINE')
        with self.assertRaises(ValueError):
            self.compile(second)
        with self.assertRaises(ValueError):
            conflict(self.registry, *hashes, KEY, 'reviewer', 'unresolved', sha256('conflict'), resolve=True)
        self.withdraw(first, 'REVOKED')
        self.registry = conflict(self.registry, *hashes, KEY, 'reviewer', 'withdrawal', sha256('conflict'), resolve=True)
        with self.assertRaises(ValueError):
            self.compile(second)
        self.assertEqual(select_approved(self.registry, KEY, *TAGS)['selection'], 'BASELINE')

    def test_registration_rejects_holdout_and_wrong_experiment_even_with_matching_case_hashes(self):
        for field, value in [('runMode', 'HOLDOUT'), ('experimentId', 'another-experiment')]:
            trace = copy.deepcopy(self.trace)
            trace['replay']['runContext'][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.add(trace=trace)

    def test_duplicate_retains_provenance_and_loader_fails_to_explicit_baseline(self):
        record = self.add()
        self.approve(record)
        self.spec['sources'][0]['issuerName'] = 'Another independent provenance note'
        duplicate = self.add()
        self.assertEqual(self.registry['payload']['entries'][duplicate['recordSha256']]['state'], 'REJECTED')
        with patch('evolution_experiences.evaluator_hash', return_value='f' * 64):
            self.assertEqual(select_approved(self.registry, KEY, *TAGS)['selection'], 'BASELINE')
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'approved.json'
            self.assertEqual(load_approved(path, KEY, *TAGS)['reason'], 'REGISTRY_LOAD_FAILED')
            path.write_text(json.dumps(self.registry), encoding='utf-8')
            before = path.read_bytes()
            self.assertEqual(load_approved(path, KEY, *TAGS)['selection'], 'EXPERIENCE')
            self.assertEqual(load_approved(path, b'wrong-test-key-at-least-32-characters', *TAGS)['reason'], 'REGISTRY_LOAD_FAILED')
            self.assertEqual(before, path.read_bytes())

    def test_cli_compiles_the_reviewed_version_without_mutating_the_registry(self):
        record = self.add()
        self.validate(record)
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            registry, parent, proposal, request, output = [root / name for name in
                                                         ('registry.json', 'parent.txt', 'proposal.json', 'request.json', 'candidate.json')]
            registry.write_text(json.dumps(self.registry), encoding='utf-8')
            parent.write_text(self.parent, encoding='utf-8')
            proposal.write_text(json.dumps({'changes': {'fundamentals.method': self.parent + '\n' + record['method']},
                                            'sourceExplanation': '增加期间对照步骤。'}), encoding='utf-8')
            request.write_text(json.dumps(dict(experienceSha256=record['recordSha256'], parentBundleId='baseline-v1',
                                               parentMethod=str(parent), proposal=str(proposal), taskTags=list(TAGS[0]),
                                               evidenceTags=list(TAGS[1]), capabilities=list(TAGS[2]))), encoding='utf-8')
            before = registry.read_bytes()
            with patch('evolution_experiences.read_key', return_value=KEY), patch('sys.argv', ['experiences',
                       '--operation', 'compile', '--registry', str(registry), '--input', str(request), '--output', str(output)]):
                with redirect_stdout(io.StringIO()) as stdout:
                    self.assertEqual(main(), 0)
                self.assertEqual(json.loads(stdout.getvalue())['status'], 'PROPOSED')
            self.assertEqual(json.loads(output.read_text(encoding='utf-8')), self.compile(record))
            self.assertEqual(before, registry.read_bytes())


if __name__ == '__main__':
    unittest.main()
