"""Fictional signed deployment records; no server is activated by these checks."""
import copy
import io
import json
import os
import tempfile
import unittest
from contextlib import redirect_stdout
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import patch

import evolution_deployment as deployment
from evolution_acceptance import evaluator_hash, seal
from evolution_release import approve_package, prepare_package, verify_publisher, CHECKS
from eval_common import json_hash, sha256
from test_evolution_acceptance import KEY
from test_evolution_release import fixture, PUBLISHER_KEY


class DeploymentTest(unittest.TestCase):
    def setUp(self):
        helper, candidate, evidence, build = fixture()
        self.package = prepare_package(candidate, helper.registry, helper.gate, evidence, build, KEY)
        self.now = datetime.now(timezone.utc)
        self.at = lambda seconds: (self.now + timedelta(seconds=seconds)).isoformat()
        names = CHECKS | deployment.AUTH_CHECKS | deployment.ROLLBACK_CHECKS | deployment.ACTIVATION_CHECKS
        self.proofs = {sha256(name): name.encode() for name in names}
        self.checks = lambda names: {name: {'status': 'PASS', 'evidenceSha256': sha256(name)} for name in names}
        self.approved, _ = approve_package(self.package, {'packageSha256': json_hash(self.package), 'approver': 'unit-publisher',
            'approvedAt': self.at(-100), 'reason': 'fictional', 'checks': self.checks(CHECKS)}, PUBLISHER_KEY, self.proofs)
        self.shadow = seal({'kind': 'SHADOW_ACCEPTANCE', 'schemaVersion': 1, 'status': 'ACCEPTED_FOR_ROLLBACK_DRILL',
            'approvedArtifactSha256': self.approved['payloadSha256'], 'packageSha256': json_hash(self.package),
            'gateSha256': self.package['acceptanceGateSha256'], 'evaluatorSha256': evaluator_hash(),
            'candidateBundleSha256': self.package['bundle']['contentSha256'], 'internalAccountIds': ['internal-one'],
            'retentionUntil': self.at(200)}, KEY)
        self.review = {'approvedArtifactSha256': self.approved['payloadSha256'], 'shadowAcceptanceSha256': self.shadow['payloadSha256'],
            'activationId': 'unit-drill', 'approver': 'unit-publisher', 'approvedAt': self.at(-90), 'expiresAt': self.at(100),
            'reason': 'fictional drill', 'internalAccountIds': ['internal-one'], 'checks': self.checks(deployment.AUTH_CHECKS)}

    def drill(self, root):
        return deployment.authorize_drill(self.approved, self.shadow, self.review, self.proofs, KEY, PUBLISHER_KEY, root)

    def rollback(self, drill):
        evidence = 'SYNTHETIC fixed evidence only'
        digest = sha256(evidence)
        case_hash = json_hash({'query': 'SYNTHETIC question', 'evidenceSha256': digest})
        rows = {}
        for phase, times in {'candidate-before': (-80, -70), 'candidate-inflight': (-65, -50),
                             'candidate-denied': (-45, -40), 'baseline-after': (-35, -30)}.items():
            candidate = phase.startswith('candidate') and phase != 'candidate-denied'
            bundle = self.package['bundle'] if candidate else self.package['rollback']
            method = {'kind': 'ordinary-evidence', 'route': 'FUNDAMENTALS', 'analystStatus': 'COMPLETED',
                'methodBundle': {'bundleId': bundle['bundleId'], 'bundleSha256': bundle['contentSha256' if candidate else 'bundleSha256']},
                'analystCompletedAt': self.at(times[1]),
                'methodSelection': {'mode': 'DRILL', 'authorizationSha256': drill['payloadSha256'], 'caseSha256': case_hash,
                    'pinnedAt': self.at(times[0]), 'reason': 'APPROVED_SCOPE' if candidate else 'BUNDLE_WITHDRAWN',
                    'comparisonIdentity': self.package['expectedComparisonIdentity']}}
            method['analystInvocation'] = {'kind': 'model-invocation', 'scope': 'fundamentals-analysis',
                'methodBundle': method['methodBundle'], 'actualSystemPromptSha256': sha256('unit system'),
                'actualUserPromptSha256': sha256('unit task')}
            messages = [{'role': 'user', 'text': 'SYNTHETIC question'}]
            context = {'kind': 'answer-context', 'schemaVersion': 1, 'scope': 'ordinary-final-answer',
                'context': evidence, 'contextSha256': digest, 'evidenceContext': evidence, 'evidenceSha256': digest,
                'evidenceCaptureComplete': True, 'hasImages': False, 'promptFingerprintScope': 'TEXT_ONLY',
                'messages': messages, 'promptSha256': sha256(json.dumps(messages, ensure_ascii=False, separators=(',', ':')))}
            trace = {'traceId': 'run-' + phase, 'userId': 'internal-one', 'status': 'success', 'userQuery': 'SYNTHETIC question',
                'steps': json.dumps([{'attributes': method}, {'attributes': context}, {'attributes': {'kind': 'model-invocation',
                    'scope': 'final-answer', 'modelName': 'unit-model', 'modelTier': 'STANDARD'}}])}
            raw = json.dumps(trace).encode()
            import hashlib
            trace_hash = hashlib.sha256(raw).hexdigest()
            self.proofs[trace_hash] = raw
            rows[phase] = {'traceSha256': trace_hash, 'caseSha256': case_hash, 'runId': trace['traceId']}
        return {'drillAuthorizationSha256': drill['payloadSha256'], 'reviewer': 'unit-independent-reviewer',
                'reviewedAt': self.at(-10), 'withdrawnAt': self.at(-60), 'runs': rows,
                'checks': self.checks(deployment.ROLLBACK_CHECKS)}

    def serving(self, drill, rollback, root, review=None):
        review = review or {**self.review, 'activationId': 'unit-serving', 'approvedAt': self.at(-1),
                           'rollbackAcceptanceSha256': rollback['payloadSha256'], 'checks': self.checks(deployment.ACTIVATION_CHECKS)}
        return deployment.authorize_serving(self.approved, self.shadow, drill, rollback, review,
                                            self.proofs, KEY, PUBLISHER_KEY, root)

    def test_complete_chain_binds_distinct_purposes_traces_accounts_and_operator_controls(self):
        with tempfile.TemporaryDirectory() as path:
            root = Path(path)
            drill = self.drill(root)
            self.assertEqual(verify_publisher(drill, PUBLISHER_KEY)['mode'], 'DRILL')
            review = self.rollback(drill)
            accepted = deployment.accept_drill(self.approved, drill, review, self.proofs, KEY, PUBLISHER_KEY)
            serving = self.serving(drill, accepted, root)
            body = verify_publisher(serving, PUBLISHER_KEY)
            self.assertEqual(body['mode'], 'SERVING')
            self.assertEqual(body['scope'], self.package['scope'])
            self.assertEqual(body['rollbackAcceptanceSha256'], accepted['payloadSha256'])
            for marker in ('PROHIBIT_ACTIVATION', 'STABLE_ONLY', 'REVOKE.' + self.package['bundle']['bundleId']):
                (root / marker).touch()
                with self.assertRaisesRegex(ValueError, 'control prohibits'):
                    self.serving(drill, accepted, root)
                with self.assertRaisesRegex(ValueError, 'control prohibits'):
                    self.drill(root)
                (root / marker).unlink()
            (root / 'STOP_GENERATION').touch()
            (root / 'STOP_SHADOW').touch()
            self.serving(drill, accepted, root)
            with self.assertRaises(OSError):
                self.serving(drill, accepted, root / 'missing-controls')

    def test_rejects_missing_fault_proofs_wrong_timelines_reused_runs_and_trace_tampering(self):
        with tempfile.TemporaryDirectory() as path:
            drill = self.drill(Path(path))
            original = self.rollback(drill)
            for fault in deployment.ROLLBACK_CHECKS:
                bad = copy.deepcopy(original)
                bad['checks'][fault]['status'] = 'UNREVIEWED'
                with self.assertRaises(ValueError):
                    deployment.accept_drill(self.approved, drill, bad, self.proofs, KEY, PUBLISHER_KEY)
            for field, value in [('reviewer', self.review['approver']), ('withdrawnAt', self.at(-85))]:
                with self.assertRaises(ValueError):
                    deployment.accept_drill(self.approved, drill, {**original, field: value}, self.proofs, KEY, PUBLISHER_KEY)
            for field, value in [('runId', original['runs']['candidate-before']['runId']), ('caseSha256', '0' * 64)]:
                bad = copy.deepcopy(original)
                bad['runs']['baseline-after'][field] = value
                with self.assertRaises(ValueError):
                    deployment.accept_drill(self.approved, drill, bad, self.proofs, KEY, PUBLISHER_KEY)
            bad_proofs = dict(self.proofs)
            bad_proofs[original['runs']['baseline-after']['traceSha256']] = b'changed'
            with self.assertRaisesRegex(ValueError, 'missing or changed'):
                deployment.accept_drill(self.approved, drill, original, bad_proofs, KEY, PUBLISHER_KEY)
            for step, field, value in [(0, 'analystInvocation', {}), (1, 'evidenceCaptureComplete', False), (1, 'promptSha256', '0' * 64)]:
                trace = json.loads(self.proofs[original['runs']['baseline-after']['traceSha256']])
                steps = json.loads(trace['steps'])
                steps[step]['attributes'][field] = value
                trace['steps'] = json.dumps(steps)
                raw = json.dumps(trace).encode()
                import hashlib
                digest = hashlib.sha256(raw).hexdigest()
                bad = copy.deepcopy(original)
                bad['runs']['baseline-after']['traceSha256'] = digest
                with self.assertRaises(ValueError):
                    deployment.accept_drill(self.approved, drill, bad, {**self.proofs, digest: raw}, KEY, PUBLISHER_KEY)

    def test_no_account_expansion_expiry_or_changed_acceptance_can_authorize_serving(self):
        with tempfile.TemporaryDirectory() as path:
            root = Path(path)
            drill = self.drill(root)
            accepted = deployment.accept_drill(self.approved, drill, self.rollback(drill), self.proofs, KEY, PUBLISHER_KEY)
            review = {**self.review, 'activationId': 'unit-serving', 'approvedAt': self.at(-1),
                      'rollbackAcceptanceSha256': accepted['payloadSha256'], 'checks': self.checks(deployment.ACTIVATION_CHECKS)}
            for field, value in [('internalAccountIds', ['other-user']), ('expiresAt', self.at(-1)),
                                 ('activationId', 'unit-drill'), ('rollbackAcceptanceSha256', '0' * 64)]:
                with self.assertRaises(ValueError):
                    self.serving(drill, accepted, root, {**review, field: value})
            for field, value in [('status', 'UNREVIEWED'), ('evaluatorSha256', '0' * 64), ('scope', {})]:
                changed = seal({**accepted['payload'], field: value}, KEY)
                with self.assertRaises(ValueError):
                    self.serving(drill, changed, root, {**review, 'rollbackAcceptanceSha256': changed['payloadSha256']})

    def test_cli_emits_review_bound_record_and_never_overwrites_a_previous_authorization(self):
        with tempfile.TemporaryDirectory() as path:
            root = Path(path)
            for name, data in {'approved': self.approved, 'shadow': self.shadow, 'review': self.review,
                               'input': {'approvedArtifact': 'approved.json', 'shadowAcceptance': 'shadow.json'}}.items():
                (root / (name + '.json')).write_text(json.dumps(data), encoding='utf-8')
            index = {}
            for index_id, (digest, raw) in enumerate(self.proofs.items()):
                index[digest] = str(index_id) + '.txt'
                (root / index[digest]).write_bytes(raw)
            (root / 'proofs.json').write_text(json.dumps(index), encoding='utf-8')
            argv = ['deployment', '--mode', 'authorize-drill', '--input', str(root / 'input.json'),
                    '--review', str(root / 'review.json'), '--evidence-index', str(root / 'proofs.json'),
                    '--control-directory', str(root), '--output', str(root / 'out')]
            with patch('sys.argv', argv), patch.dict(os.environ, {'STOCKSAGE_EVOLUTION_EVALUATOR_KEY': KEY.decode(),
                         'STOCKSAGE_EVOLUTION_PUBLISHER_KEY': PUBLISHER_KEY.decode()}), redirect_stdout(io.StringIO()):
                self.assertEqual(deployment.main(), 0)
                output = json.loads((root / 'out' / 'activation-authorization.json').read_bytes())
                self.assertEqual(verify_publisher(output, PUBLISHER_KEY)['mode'], 'DRILL')
                with self.assertRaises(SystemExit):
                    deployment.main()


if __name__ == '__main__':
    unittest.main()
