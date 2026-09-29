"""Fictional publisher approvals, never deployed authorization or real quality gains."""
import copy
import io
import json
import os
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch

import test_evolution_experiences as fixtures
from test_evolution_acceptance import KEY
from evolution_acceptance import seal
from evolution_candidates import bundle_content_sha256
from evolution_release import prepare_package, approve_package, verify_publisher, CHECKS, main
from ordinary_answer_quality import json_hash, sha256

PUBLISHER_KEY = b'unit-test-only-independent-publisher-secret'
PROOFS = {sha256(name): name.encode() for name in CHECKS}


def fixture():
    helper = fixtures.ExperienceLifecycleTest()
    helper.setUp()
    baseline = helper.gate['payload']['baselineBundle']
    baseline['fixedContractSha256'] = sha256('test-contract')
    baseline['bundleSha256'] = bundle_content_sha256('baseline-v1', None, baseline['fixedContractSha256'], helper.parent)
    helper.gate = seal(helper.gate['payload'], KEY)
    record = helper.add()
    candidate = helper.approve(record)
    evidence = helper.evidence(candidate)['payload']
    evidence.update(expectedComparisonIdentity=helper.gate['payload']['expectedComparisonIdentity'],
                    sourceCasesSha256=helper.gate['payload']['review']['sourceCasesSha256'],
                    goldSha256=helper.gate['payload']['review']['goldSha256'],
                    reviewAuditSha256=sha256('fictional blind review'), resourceAuditSha256=sha256('fictional resource audit'),
                    selectionSha256=sha256('fictional validation selection'))
    build = helper.trace['replay']['comparisonIdentity']['runtimeBuild']
    return helper, candidate, seal(evidence, KEY), build


class ReleaseTest(unittest.TestCase):
    def setUp(self):
        self.helper, self.candidate, self.evidence, self.build = fixture()

    def prepare(self):
        return prepare_package(self.candidate, self.helper.registry, self.helper.gate, self.evidence, self.build, KEY)

    def approval(self, package):
        return {'packageSha256': json_hash(package), 'approver': 'fictional-independent-publisher',
                'approvedAt': '2026-09-28T00:00:00Z', 'reason': 'Unit fixture only',
                'checks': {name: {'status': 'PASS', 'evidenceSha256': sha256(name)} for name in CHECKS}}

    def test_prepare_and_approval_bind_content_without_activation(self):
        package = self.prepare()
        artifact, decision = approve_package(package, self.approval(package), PUBLISHER_KEY, PROOFS)
        body = verify_publisher(artifact, PUBLISHER_KEY)
        self.assertEqual(body['status'], 'APPROVED_PENDING_VALIDATION')
        self.assertEqual(body['approvalSha256'], decision['payloadSha256'])
        self.assertEqual(body['package'], package)
        self.assertEqual(package['experience']['version'], 1)
        changed = copy.deepcopy(artifact)
        changed['payloadSha256'] = '0' * 64
        with self.assertRaises(ValueError):
            verify_publisher(changed, PUBLISHER_KEY)
        with self.assertRaises(ValueError):
            verify_publisher(artifact, KEY)
        approval = self.approval(package)
        approval['checks']['privacy']['status'] = 'UNREVIEWED'
        with self.assertRaisesRegex(ValueError, 'not passed'):
            approve_package(package, approval, PUBLISHER_KEY, PROOFS)
        with self.assertRaisesRegex(ValueError, 'different package'):
            approve_package({**package, 'scope': {}}, self.approval(package), PUBLISHER_KEY, PROOFS)
        with self.assertRaisesRegex(ValueError, 'missing or changed'):
            approve_package(package, self.approval(package), PUBLISHER_KEY, {})

    def test_unqualified_evidence_changed_build_or_revoked_experience_cannot_publish(self):
        original = self.evidence
        for field, value in [('eligible', False), ('evaluationSplit', 'VALIDATION'),
                             ('candidateBundleSha256', '0' * 64), ('reviewAuditSha256', None),
                             ('fixedContractSha256', '0' * 64)]:
            with self.subTest(field=field):
                self.evidence = seal({**original['payload'], field: value}, KEY)
                with self.assertRaises(ValueError):
                    self.prepare()
        self.evidence = original
        self.build = {**self.build, 'status': 'UNATTESTED'}
        with self.assertRaisesRegex(ValueError, 'exact packaged build'):
            self.prepare()
        self.build = self.helper.trace['replay']['comparisonIdentity']['runtimeBuild']
        record = next(iter(self.helper.registry['payload']['entries'].values()))['record']
        self.helper.withdraw(record, 'REVOKED')
        with self.assertRaisesRegex(ValueError, 'available approved experience'):
            self.prepare()

    def test_cli_recomputes_package_and_keeps_evaluator_and_publisher_credentials_separate(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            data = {'candidate': self.candidate, 'registry': self.helper.registry, 'gate': self.helper.gate,
                    'releaseEvidence': self.evidence, 'buildIdentity': self.build}
            for name, value in data.items():
                (root / (name + '.json')).write_text(json.dumps(value), encoding='utf-8')
            (root / 'input.json').write_text(json.dumps({name: name + '.json' for name in data}), encoding='utf-8')
            for name in CHECKS:
                (root / (name + '.txt')).write_bytes(name.encode())
            (root / 'proofs.json').write_text(json.dumps({sha256(name): name + '.txt' for name in CHECKS}), encoding='utf-8')
            def run(mode, output, key=PUBLISHER_KEY):
                argv = ['release', '--mode', mode, '--input', str(root / 'input.json'), '--output', str(root / output)]
                if mode == 'approve':
                    argv += ['--approval', str(root / 'approval.json'), '--approval-evidence', str(root / 'proofs.json')]
                with patch('sys.argv', argv), patch.dict(os.environ, {'STOCKSAGE_EVOLUTION_EVALUATOR_KEY': KEY.decode(),
                          'STOCKSAGE_EVOLUTION_PUBLISHER_KEY': key.decode()}), redirect_stdout(io.StringIO()):
                    return main()
            self.assertEqual(run('prepare', 'prepared'), 0)
            package = json.loads((root / 'prepared/package.json').read_text(encoding='utf-8'))
            (root / 'approval.json').write_text(json.dumps(self.approval(package)), encoding='utf-8')
            with redirect_stdout(io.StringIO()), patch('sys.stderr', new=io.StringIO()), self.assertRaises(SystemExit) as rejected:
                run('approve', 'invalid', KEY)
            self.assertEqual(rejected.exception.code, 2)
            self.assertEqual(run('approve', 'approved'), 0)
            body = verify_publisher(json.loads((root / 'approved/approved-artifact.json').read_text()), PUBLISHER_KEY)
            self.assertEqual(body['packageSha256'], json_hash(package))


if __name__ == '__main__':
    unittest.main()
