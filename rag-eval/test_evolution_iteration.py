"""Fictional iteration evidence; no fixture establishes real improvement or deployment."""
import copy
import io
import json
import tempfile
import unittest
from contextlib import redirect_stdout
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch

import evolution_iteration as iteration
import test_evolution_release as release_fixture
import test_evolution_experiences as experience_fixture
import test_evolution_deployment as deployment_fixture
import test_evolution_observation as observation_fixture
from evolution_acceptance import comparison_report_hash, seal, select_candidate, verified_comparison
from evolution_release import prepare_package, approve_package, CHECKS
from ordinary_answer_quality import json_hash, sha256
from test_evolution_acceptance import KEY, comparison, reviewed_comparison
from test_evolution_release import PUBLISHER_KEY
from test_ordinary_answer_quality import completed_reviews


def manifest():
    return {'schema': iteration.SCHEMA, 'iterationId': 'iteration-001', 'actor': 'unit-recorder',
            'recordedAt': datetime.now(timezone.utc).isoformat(), 'artifacts': {}, 'proofs': {},
            'unresolved': [{'issueType': 'MISSING_DATA', 'description': 'Fictional missing provider evidence', 'evidenceRefs': []}],
            'nextBatch': {'trigger': 'MANUAL', 'scope': 'Fixed independent batch after separate review'}, 'closure': None}


def fixture():
    original_fixture, inputs = experience_fixture.fixture, {}
    def capture_baseline():
        value = original_fixture()
        inputs.update(sourceCases=value[0], gold=value[1], baselineReport=value[3])
        return value
    with patch.object(experience_fixture, 'fixture', side_effect=capture_baseline), patch.object(
            experience_fixture, 'register', wraps=experience_fixture.register) as registered:
        helper, candidate, evidence, build = release_fixture.fixture()
        proofs = {digest: text.encode() for digest, text in registered.call_args.args[2].items()}
    def report(split, status, selection=None):
        originals = {name: {'fictional': split + name} for name in ('Manifest', 'Baseline', 'Candidate')}
        result = {'schema': 'fundamentals_evolution_comparison_v2', 'status': status,
                  'evidence_kind': 'PUBLIC_AUTHORIZED_REVIEW',
                  'stage_decisions': {'analysis': 'IMPROVED', 'finalAnswer': status}, 'metrics': {'fixture': True},
                  'manifest_sha256': json_hash(originals['Manifest']), 'baseline_report_sha256': json_hash(originals['Baseline']),
                  'candidate_report_sha256': json_hash(originals['Candidate'])}
        body = {**evidence['payload'], 'evaluationSplit': split, 'comparisonStatus': status,
                'eligible': split == 'HOLDOUT' and status == 'IMPROVED',
                'authorization': 'PENDING_HUMAN_APPROVAL' if split == 'HOLDOUT' and status == 'IMPROVED' else 'NOT_AUTHORIZED',
                'selectionSha256': selection, 'comparisonReportSha256': comparison_report_hash(result)}
        result['releaseEvidence'] = {name: value for name, value in body.items() if name != 'kind'}
        result['signedReleaseEvidence'] = seal(body, KEY)
        return result
    validation = report('VALIDATION', 'IMPROVED')
    selected = select_candidate(validation, KEY, 'unit-independent-evaluator', 'fictional selection')
    artifacts = {**inputs, 'gate': helper.gate, 'candidate': candidate, 'registry': helper.registry, 'build': build,
                 'validation': validation, 'selection': selected,
                 'holdout': report('HOLDOUT', 'NO_IMPROVEMENT', selected['payloadSha256'])}
    artifacts.update({split.lower() + name: {'fictional': split + name}
                      for split in ('VALIDATION', 'HOLDOUT') for name in ('Manifest', 'Baseline', 'Candidate')})
    return artifacts, proofs, report


def close(request, decision, proofs):
    proof = b'Fictional independent lineage/engineering/runtime end-state proof'
    digest = sha256(proof.decode())
    proofs[digest] = proof
    request['closure'] = {'actor': 'unit-owner', 'at': datetime.now(timezone.utc).isoformat(),
                          'decision': decision, 'reason': 'Fictional closure mechanism only',
                          'checks': {name: {'status': 'PASS', 'evidenceSha256': digest}
                                     for name in ('lineage', 'engineeringLoop', 'runtimeEndState')}}


class IterationTest(unittest.TestCase):
    def test_empty_real_evidence_stays_open_and_cli_emits_named_report_and_index(self):
        request = manifest()
        result = iteration.assemble(request, {}, {}, {})
        self.assertEqual(result['status'], 'OPEN')
        self.assertEqual(result['finalAnswerQuality'], 'UNVERIFIED')
        self.assertEqual(result['unresolved'][0]['queue'], 'DATA_QUEUE')
        self.assertIn('holdout', result['missingEvidence'])
        close(request, 'CLOSED_INCONCLUSIVE', {})
        with self.assertRaisesRegex(ValueError, 'real accepted baseline'):
            iteration.assemble(request, {}, {}, {})
        request['closure'] = None
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'input.json').write_text(json.dumps(request), encoding='utf-8')
            args = ['iteration', '--manifest', str(root / 'input.json'), '--output', str(root / 'out')]
            with patch('sys.argv', args), redirect_stdout(io.StringIO()):
                self.assertEqual(iteration.main(), 0)
            self.assertIn('UNVERIFIED', (root / 'out' / 'iteration-001.md').read_text(encoding='utf-8'))
            self.assertEqual(json.loads((root / 'out' / 'artifact-index.json').read_bytes())['status'], 'OPEN')
            request['nextBatch']['trigger'] = 'EVERY_REQUEST'
            (root / 'input.json').write_text(json.dumps(request), encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'manually triggered'):
                iteration.load_inputs(root / 'input.json')
            request['nextBatch']['trigger'] = 'MANUAL'
            source = root / 'search.json'
            source.write_text('{}', encoding='utf-8')
            request['artifacts'] = {'search': {'path': 'search.json', 'sha256': sha256('{}')}}
            (root / 'input.json').write_text(json.dumps(request), encoding='utf-8')
            iteration.load_inputs(root / 'input.json')
            source.write_text('{"changed":true}', encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'artifact missing or changed'):
                iteration.load_inputs(root / 'input.json')

    def test_baseline_and_inconclusive_closure_require_bound_lineage_and_never_consume_holdout_unnecessarily(self):
        artifacts, proofs, report = fixture()
        request = manifest()
        close(request, 'CLOSED_BASELINE_RETAINED', proofs)
        result = iteration.assemble(request, artifacts, proofs, {}, KEY)
        self.assertEqual(result['status'], 'CLOSED_BASELINE_RETAINED')
        self.assertEqual(result['componentQuality'], 'IMPROVED')
        self.assertEqual(result['finalAnswerQuality'], 'NO_IMPROVEMENT')
        artifacts['holdout'] = report('HOLDOUT', 'INCONCLUSIVE', artifacts['selection']['payloadSha256'])
        close(request, 'CLOSED_INCONCLUSIVE', proofs)
        self.assertEqual(iteration.assemble(request, artifacts, proofs, {}, KEY)['status'], 'CLOSED_INCONCLUSIVE')
        del artifacts['holdout'], artifacts['selection']
        artifacts['validation'] = report('VALIDATION', 'NO_IMPROVEMENT')
        close(request, 'CLOSED_BASELINE_RETAINED', proofs)
        self.assertEqual(iteration.assemble(request, artifacts, proofs, {}, KEY)['comparisonSplit'], 'VALIDATION')
        with self.assertRaisesRegex(ValueError, 'complete lineage'):
            iteration.assemble(request, artifacts, {}, {}, KEY)
        changed = copy.deepcopy(artifacts)
        changed['validation']['stage_decisions']['finalAnswer'] = 'IMPROVED'
        with self.assertRaisesRegex(ValueError, 'decisions/metrics'):
            iteration.assemble(request, changed, proofs, {}, KEY)

    def test_released_closure_requires_actual_candidate_observation_and_all_predecessor_bindings(self):
        artifacts, proofs, report = fixture()
        artifacts['holdout'] = report('HOLDOUT', 'IMPROVED', artifacts['selection']['payloadSha256'])
        request = manifest()
        close(request, 'CLOSED_RELEASED', proofs)
        with self.assertRaisesRegex(ValueError, 'complete approved deployment'):
            iteration.assemble(request, artifacts, proofs, {}, KEY, PUBLISHER_KEY)
        helper = deployment_fixture.DeploymentTest()
        helper.setUp()
        helper.package = prepare_package(artifacts['candidate'], artifacts['registry'], artifacts['gate'],
                                        artifacts['holdout']['signedReleaseEvidence'], artifacts['build'], KEY)
        helper.approved, _ = approve_package(helper.package, {'packageSha256': json_hash(helper.package),
            'approver': 'unit-publisher', 'approvedAt': helper.at(-100), 'reason': 'fictional', 'checks': helper.checks(CHECKS)},
            PUBLISHER_KEY, helper.proofs)
        body = {**helper.shadow['payload'], 'approvedArtifactSha256': helper.approved['payloadSha256'],
                'packageSha256': json_hash(helper.package), 'gateSha256': artifacts['gate']['payloadSha256'],
                'candidateBundleSha256': helper.package['bundle']['contentSha256']}
        helper.shadow = seal(body, KEY)
        helper.review.update(approvedArtifactSha256=helper.approved['payloadSha256'], shadowAcceptanceSha256=helper.shadow['payloadSha256'])
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            drill = helper.drill(root)
            rollback = deployment_fixture.deployment.accept_drill(helper.approved, drill, helper.rollback(drill), helper.proofs, KEY, PUBLISHER_KEY)
            activation = helper.serving(drill, rollback, root)
            observations = observation_fixture.ObservationTest()
            observations.helper, observations.activation = helper, activation
            execution = observations.batch()
            execution['cases'][1]['trace']['status'] = 'success'
            execution['cases'][1]['passed'] = True
            execution['status'] = 'PASS'
            artifacts.update(approved=helper.approved, shadow=helper.shadow, drill=drill, rollback=rollback, activation=activation,
                             execution=execution, qualityReviews=completed_reviews(execution), operationalReviews=observations.reviews(execution))
            result = iteration.assemble(request, artifacts, proofs, {}, KEY, PUBLISHER_KEY)
            self.assertEqual(result['status'], 'CLOSED_RELEASED')
            self.assertFalse(result['activationAuthorized'])
            artifacts['operationalReviews']['reviews'][0]['hardGates']['tenant_isolation']['status'] = 'FAIL'
            with self.assertRaisesRegex(ValueError, 'without failed or unknown'):
                iteration.assemble(request, artifacts, proofs, {}, KEY, PUBLISHER_KEY)

    def test_actual_comparison_producer_binds_metrics_and_stage_decisions(self):
        sources, gold, plan, gate, baseline, candidate, audit = comparison()
        result = reviewed_comparison(plan, baseline, candidate, sources, gold, acceptance_gate=gate, resource_audit=audit, evaluator_key=KEY)
        evidence = verified_comparison(result, KEY)
        self.assertEqual(evidence['comparisonReportSha256'], comparison_report_hash(result))
        for field, value in [('stage_decisions', {'analysis': 'NO_IMPROVEMENT'}), ('metrics', {}), ('status', 'NO_IMPROVEMENT')]:
            with self.assertRaisesRegex(ValueError, 'decisions/metrics'):
                verified_comparison({**result, field: value}, KEY)


if __name__ == '__main__':
    unittest.main()
