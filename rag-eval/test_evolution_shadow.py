"""Fictional signed shadow controls, provider responses and isolation proofs."""
import copy
import io
import json
import os
import tempfile
import unittest
from contextlib import redirect_stdout
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import Mock, patch

from evolution_acceptance import evaluator_hash
from evolution_candidates import bundle_content_sha256
from evolution_release import sign_publisher
from evolution_shadow import (prepare_plan, authorize, verify_authorization, execution_bytes, accept_shadow,
                              AUTH_CHECKS, ACCEPT_CHECKS, main as shadow_main)
from evolution_experiment import ExperimentLedger, replay_usage
from run_evolution_replay import run_paired_cases, main as replay_main
from ordinary_answer_quality import json_hash, sha256
from test_evolution_acceptance import comparison, KEY, rate, resource_audit, reviewed_comparison
from test_evolution_experiment import reservations
from test_evolution_release import PUBLISHER_KEY


class ShadowTest(unittest.TestCase):
    def setUp(self):
        self.sources, self.gold, _, self.gate, self.baseline, self.candidate, _ = comparison()
        bundle = {'bundleId': 'candidate-v1', 'parentBundleId': 'baseline-v1', 'method': '核对期间与单位，再比较指标。',
                  'fixedContractSha256': self.gate['payload']['baselineBundle']['fixedContractSha256']}
        bundle['contentSha256'] = bundle_content_sha256(bundle['bundleId'], bundle['parentBundleId'], bundle['fixedContractSha256'], bundle['method'])
        package = {'bundle': bundle, 'acceptanceGateSha256': self.gate['payloadSha256'], 'evaluatorSha256': evaluator_hash(),
                   'expectedComparisonIdentity': self.gate['payload']['expectedComparisonIdentity']}
        # Publisher authenticity is the shadow boundary; E13 package preparation has separate tests.
        self.approved = sign_publisher({'kind': 'APPROVED_METHOD_PACKAGE', 'schemaVersion': 1,
                                       'status': 'APPROVED_PENDING_VALIDATION', 'package': package,
                                       'packageSha256': json_hash(package), 'approvalSha256': sha256('fictional approval')}, PUBLISHER_KEY)
        self.shadow_sources = [case for case in self.sources if case['split'] == 'VALIDATION']
        self.plan = prepare_plan(self.shadow_sources, self.gate, self.approved, KEY, PUBLISHER_KEY)
        self.digest = sha256(execution_bytes(self.plan).decode())
        self.proofs = {sha256(name): name.encode() for name in AUTH_CHECKS | ACCEPT_CHECKS}
        self.review = {'planSha256': json_hash(self.plan), 'approver': 'unit publisher',
                       'approvedAt': (datetime.now(timezone.utc) - timedelta(seconds=1)).isoformat(),
                       'internalAccountIds': ['unit-internal-account'], 'endpoint': 'https://example.invalid/api/eval/evolution/replay',
                       'restrictedStore': '.', 'retentionUntil': (datetime.now(timezone.utc) + timedelta(days=1)).isoformat(),
                       'checks': {name: {'status': 'PASS', 'evidenceSha256': sha256(name)} for name in AUTH_CHECKS}}

    def auth(self):
        return authorize(self.plan, self.shadow_sources, self.gate, self.approved, self.review, self.proofs, KEY, PUBLISHER_KEY)

    def response(self, request):
        report = self.baseline if request['bundleId'] == 'baseline-v1' else self.candidate
        result = copy.deepcopy(next(row['replay'] for row in report['cases'] if row['replay']['caseId'] == request['caseId']))
        result.update(runId=request['runId'], executionFileSha256=self.digest)
        result['methodBundle']['bundleSha256'] = self.plan['comparison'][('baseline' if request['bundleId'] == 'baseline-v1' else 'candidate') + 'BundleSha256']
        result['runContext'].update(runMode='SHADOW', runId=request['runId'], bundleHash=result['methodBundle']['bundleSha256'])
        from evolution_release import verify_publisher
        approved = verify_publisher(self.approved, PUBLISHER_KEY)
        result['comparisonIdentity']['shadow'] = {'approvedArtifactSha256': self.approved['payloadSha256'],
                'packageSha256': approved['packageSha256'], 'approvalSha256': approved['approvalSha256'],
                'conditionsMatch': True, 'outputScope': 'ISOLATED_REPLAY_ONLY'}
        return result

    def run_batch(self, root, authorization, calls):
        ledger = ExperimentLedger(root / 'ledger.sqlite', self.gate, KEY, reservations())
        identity = {'executionFileSha256': self.digest, 'comparisonManifestSha256': json_hash(self.plan['comparison'])}
        def invoke(request):
            def send(_):
                calls.append(request['runId'])
                return self.response(request)
            return ledger.call(request['runId'], {**identity, 'request': request}, ('ANALYST', 'FINAL_ANSWER'), send, replay_usage)
        return run_paired_cases(self.plan['execution'], self.digest, invoke, self.plan['comparison'], self.gate, KEY,
                                started_at=ledger.bind_plan(identity), shadow_authorization=authorization,
                                approved_artifact=self.approved, publisher_key=PUBLISHER_KEY, endpoint=self.review['endpoint']), ledger

    def test_authorized_batch_resumes_shared_budget_and_can_only_accept_for_rollback(self):
        with tempfile.TemporaryDirectory() as directory:
            self.review['restrictedStore'] = directory
            authorization = self.auth()
            calls = []
            (baseline, candidate), ledger = self.run_batch(Path(directory), authorization, calls)
            first_calls = list(calls)
            self.assertEqual(len(calls), 6)
            self.assertEqual(ledger.snapshot()['callsReserved']['ANALYST'], 6)
            self.run_batch(Path(directory), authorization, calls)
            self.assertEqual(calls, first_calls)
            baseline, candidate = rate(baseline, self.sources, self.gold, baseline=True), rate(candidate, self.sources, self.gold)
            result = reviewed_comparison(self.plan['comparison'], baseline, candidate, self.sources, self.gold,
                        acceptance_gate=self.gate, resource_audit=resource_audit(self.gate, baseline, candidate), evaluator_key=KEY,
                        shadow_authorization=authorization, approved_artifact=self.approved, publisher_key=PUBLISHER_KEY)
            self.assertTrue(result['releaseEvidence']['shadowChecksPassed'], result['reasons'])
            self.assertFalse(result['releaseEvidence']['eligible'])
            review = {'comparisonEvidenceSha256': result['signedReleaseEvidence']['payloadSha256'],
                      'reviewer': 'unit independent acceptance', 'reviewedAt': datetime.now(timezone.utc).isoformat(),
                      'checks': {name: {'status': 'PASS', 'evidenceSha256': sha256(name)} for name in ACCEPT_CHECKS}}
            accepted = accept_shadow(result, authorization, self.approved, review, self.proofs, KEY, PUBLISHER_KEY)
            self.assertEqual(accepted['payload']['status'], 'ACCEPTED_FOR_ROLLBACK_DRILL')
            with self.assertRaisesRegex(ValueError, 'proof missing'):
                accept_shadow(result, authorization, self.approved, review, {}, KEY, PUBLISHER_KEY)
            candidate['cases'][0]['replay']['comparisonIdentity']['shadow']['conditionsMatch'] = False
            candidate = rate(candidate, self.sources, self.gold)
            invalid = reviewed_comparison(self.plan['comparison'], baseline, candidate, self.sources, self.gold,
                        acceptance_gate=self.gate, resource_audit=resource_audit(self.gate, baseline, candidate), evaluator_key=KEY,
                        shadow_authorization=authorization, approved_artifact=self.approved, publisher_key=PUBLISHER_KEY)
            self.assertFalse(invalid['releaseEvidence']['shadowChecksPassed'])
            self.assertIn('SHADOW_APPROVAL_OR_CONDITIONS_NOT_PROVEN', invalid['reasons'])

    def test_holdout_changed_execution_endpoint_expiration_and_missing_approval_are_rejected(self):
        with self.assertRaisesRegex(ValueError, 'validation cohort'):
            prepare_plan(self.sources, self.gate, self.approved, KEY, PUBLISHER_KEY)
        authorization = self.auth()
        for digest, endpoint in [('0' * 64, self.review['endpoint']), (self.digest, 'https://other.invalid/replay')]:
            with self.assertRaises(ValueError):
                verify_authorization(authorization, self.approved, self.plan['comparison'], digest, PUBLISHER_KEY, endpoint=endpoint)
        changed = copy.deepcopy(self.plan)
        changed['execution']['cases'][0]['context'] = 'changed evidence'
        with self.assertRaisesRegex(ValueError, 'changed accepted cases'):
            authorize(changed, self.shadow_sources, self.gate, self.approved, {**self.review, 'planSha256': json_hash(changed)}, self.proofs, KEY, PUBLISHER_KEY)
        self.review['retentionUntil'] = (datetime.now(timezone.utc) - timedelta(seconds=2)).isoformat()
        with self.assertRaisesRegex(ValueError, 'expired'):
            self.auth()
        with self.assertRaises((ValueError, TypeError)):
            run_paired_cases(self.plan['execution'], self.digest, lambda _: self.fail('No call without shadow authorization'),
                             self.plan['comparison'], self.gate, KEY)

    def test_cli_enforces_storage_and_reuses_recorded_calls(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.review['restrictedStore'] = directory
            authorization = self.auth()
            values = {'gate': self.gate, 'approved': self.approved, 'authorization': authorization,
                      'comparison': self.plan['comparison'], 'reservations': reservations()}
            for name, value in values.items():
                (root / (name + '.json')).write_text(json.dumps(value), encoding='utf-8')
            (root / 'execution.json').write_bytes(execution_bytes(self.plan))
            network_calls = []
            def open_response(request, timeout):
                body = json.loads(request.data)
                network_calls.append(body)
                return io.BytesIO(json.dumps(self.response(body)).encode())
            def run(output):
                argv = ['replay', '--paired', '--input', str(root / 'execution.json'), '--output', str(root / output),
                        '--endpoint', self.review['endpoint'], '--comparison-manifest', str(root / 'comparison.json'),
                        '--acceptance-gate', str(root / 'gate.json'), '--experiment-ledger', str(root / 'ledger.sqlite'),
                        '--resource-reservations', str(root / 'reservations.json'), '--approved-artifact', str(root / 'approved.json'),
                        '--shadow-authorization', str(root / 'authorization.json')]
                with patch('sys.argv', argv), patch.dict(os.environ, {'STOCKSAGE_ADMIN_TOKEN': 'unit-only-token',
                          'STOCKSAGE_EVOLUTION_EVALUATOR_KEY': KEY.decode(), 'STOCKSAGE_EVOLUTION_PUBLISHER_KEY': PUBLISHER_KEY.decode()}), \
                        patch('urllib.request.build_opener', return_value=Mock(open=open_response)), redirect_stdout(io.StringIO()):
                    return replay_main()
            self.assertEqual(run('first'), 0)
            self.assertEqual(run('restored'), 0)
            self.assertEqual(len(network_calls), 6)
            self.assertTrue((root / 'first/resource-review/audit-template.json').exists())
            with patch('sys.stderr', new=io.StringIO()), self.assertRaises(SystemExit) as rejected:
                run('../outside-shadow-store')
            self.assertEqual(rejected.exception.code, 2)
            self.assertEqual(len(network_calls), 6)

    def test_prepare_and_authorize_cli_export_the_exact_registered_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name, value in {'gate': self.gate, 'approved': self.approved}.items():
                (root / (name + '.json')).write_text(json.dumps(value), encoding='utf-8')
            (root / 'sources.jsonl').write_text(''.join(json.dumps(case) + '\n' for case in self.shadow_sources), encoding='utf-8')
            base = ['shadow', '--gate', str(root / 'gate.json'), '--source-cases', str(root / 'sources.jsonl'),
                    '--approved-artifact', str(root / 'approved.json')]
            environment = {'STOCKSAGE_EVOLUTION_EVALUATOR_KEY': KEY.decode(), 'STOCKSAGE_EVOLUTION_PUBLISHER_KEY': PUBLISHER_KEY.decode()}
            with patch('sys.argv', base + ['--mode', 'prepare', '--output', str(root / 'prepared')]), \
                    patch.dict(os.environ, environment), redirect_stdout(io.StringIO()):
                self.assertEqual(shadow_main(), 0)
            plan = json.loads((root / 'prepared/shadow-plan.json').read_text(encoding='utf-8'))
            review = {**self.review, 'planSha256': json_hash(plan), 'restrictedStore': directory}
            (root / 'review.json').write_text(json.dumps(review), encoding='utf-8')
            for name in AUTH_CHECKS:
                (root / (name + '.txt')).write_bytes(name.encode())
            (root / 'proofs.json').write_text(json.dumps({sha256(name): name + '.txt' for name in AUTH_CHECKS}), encoding='utf-8')
            with patch('sys.argv', base + ['--mode', 'authorize', '--output', str(root / 'authorized'),
                                          '--plan', str(root / 'prepared/shadow-plan.json'), '--review', str(root / 'review.json'),
                                          '--evidence-index', str(root / 'proofs.json')]), \
                    patch.dict(os.environ, environment), redirect_stdout(io.StringIO()):
                self.assertEqual(shadow_main(), 0)
            raw = (root / 'authorized/execution.json').read_bytes()
            self.assertEqual(raw, execution_bytes(plan))
            authorization = json.loads((root / 'authorized/shadow-authorization.json').read_text())
            verify_authorization(authorization, self.approved, plan['comparison'], sha256(raw.decode()), PUBLISHER_KEY)


if __name__ == '__main__':
    unittest.main()
